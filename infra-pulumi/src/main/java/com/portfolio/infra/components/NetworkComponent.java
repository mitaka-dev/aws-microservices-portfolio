package com.portfolio.infra.components;

import com.pulumi.aws.AwsFunctions;
import com.pulumi.aws.ec2.*;
import com.pulumi.aws.inputs.GetAvailabilityZonesArgs;
import com.pulumi.aws.inputs.GetRegionArgs;
import com.pulumi.core.Output;
import com.pulumi.resources.ComponentResource;
import com.pulumi.resources.ComponentResourceOptions;
import com.pulumi.resources.CustomResourceOptions;

import java.util.List;
import java.util.Map;

/**
 * Mirrors infra/modules/network: VPC, 2 public + 2 private subnets,
 * single NAT Gateway, IGW, route tables.
 */
public class NetworkComponent extends ComponentResource {

    private final Output<String> vpcId;
    private final Output<List<String>> publicSubnetIds;
    private final Output<List<String>> privateSubnetIds;

    public NetworkComponent(String org, String env) {
        super("portfolio:infra:Network", org + "-" + env + "-network",
              ComponentResourceOptions.builder().build());

        var prefix = org + "-" + env;
        var opts   = CustomResourceOptions.builder().parent(this).build();

        // AZ lookup — same as data "aws_availability_zones" "available"
        var azs = AwsFunctions.getAvailabilityZones(GetAvailabilityZonesArgs.builder()
                .state("available")
                .build());

        // Region lookup — used for Gateway endpoint service names
        var region = AwsFunctions.getRegion(GetRegionArgs.builder().build());

        // ── VPC ───────────────────────────────────────────────────────────────
        var vpc = new Vpc(prefix + "-vpc", VpcArgs.builder()
                .cidrBlock("10.0.0.0/16")
                .enableDnsSupport(true)
                .enableDnsHostnames(true)
                .tags(Map.of("Name", prefix + "-vpc"))
                .build(), opts);

        // ── Internet Gateway ──────────────────────────────────────────────────
        var igw = new InternetGateway(prefix + "-igw", InternetGatewayArgs.builder()
                .vpcId(vpc.id())
                .tags(Map.of("Name", prefix + "-igw"))
                .build(), opts);

        // ── Public subnets (map_public_ip_on_launch = true) ───────────────────
        var publicCidrs = List.of("10.0.1.0/24", "10.0.2.0/24");
        var publicSubnets = new Subnet[2];
        for (int i = 0; i < 2; i++) {
            final int idx = i;
            publicSubnets[i] = new Subnet(prefix + "-public-" + (i + 1), SubnetArgs.builder()
                    .vpcId(vpc.id())
                    .cidrBlock(publicCidrs.get(i))
                    .availabilityZone(azs.applyValue(r -> r.names().get(idx)))
                    .mapPublicIpOnLaunch(true)
                    .tags(Map.of("Name", prefix + "-public-" + (idx + 1)))
                    .build(), opts);
        }

        // ── Private subnets ───────────────────────────────────────────────────
        var privateCidrs = List.of("10.0.10.0/24", "10.0.20.0/24");
        var privateSubnets = new Subnet[2];
        for (int i = 0; i < 2; i++) {
            final int idx = i;
            privateSubnets[i] = new Subnet(prefix + "-private-" + (i + 1), SubnetArgs.builder()
                    .vpcId(vpc.id())
                    .cidrBlock(privateCidrs.get(i))
                    .availabilityZone(azs.applyValue(r -> r.names().get(idx)))
                    .tags(Map.of("Name", prefix + "-private-" + (idx + 1)))
                    .build(), opts);
        }

        // ── NAT Gateway (single — cost optimisation over per-AZ) ─────────────
        var natEip = new Eip(prefix + "-nat-eip", EipArgs.builder()
                .domain("vpc")
                .tags(Map.of("Name", prefix + "-nat-eip"))
                .build(), opts);

        var nat = new NatGateway(prefix + "-nat", NatGatewayArgs.builder()
                .allocationId(natEip.id())
                .subnetId(publicSubnets[0].id())
                .tags(Map.of("Name", prefix + "-nat"))
                .build(), CustomResourceOptions.builder()
                        .parent(this)
                        .dependsOn(List.of(igw))
                        .build());

        // ── Public route table ────────────────────────────────────────────────
        var publicRt = new RouteTable(prefix + "-rt-public", RouteTableArgs.builder()
                .vpcId(vpc.id())
                .tags(Map.of("Name", prefix + "-rt-public"))
                .build(), opts);

        new Route(prefix + "-route-public-internet", RouteArgs.builder()
                .routeTableId(publicRt.id())
                .destinationCidrBlock("0.0.0.0/0")
                .gatewayId(igw.id())
                .build(), opts);

        for (int i = 0; i < 2; i++) {
            new RouteTableAssociation(prefix + "-rta-public-" + (i + 1),
                    RouteTableAssociationArgs.builder()
                            .subnetId(publicSubnets[i].id())
                            .routeTableId(publicRt.id())
                            .build(), opts);
        }

        // ── Private route table ───────────────────────────────────────────────
        var privateRt = new RouteTable(prefix + "-rt-private", RouteTableArgs.builder()
                .vpcId(vpc.id())
                .tags(Map.of("Name", prefix + "-rt-private"))
                .build(), opts);

        new Route(prefix + "-route-private-nat", RouteArgs.builder()
                .routeTableId(privateRt.id())
                .destinationCidrBlock("0.0.0.0/0")
                .natGatewayId(nat.id())
                .build(), opts);

        for (int i = 0; i < 2; i++) {
            new RouteTableAssociation(prefix + "-rta-private-" + (i + 1),
                    RouteTableAssociationArgs.builder()
                            .subnetId(privateSubnets[i].id())
                            .routeTableId(privateRt.id())
                            .build(), opts);
        }

        // ── Gateway VPC endpoints (free — no hourly charge, no ENI) ─────────
        // S3: routes ECR image-layer pulls off NAT (ECR stores layers in S3)
        new VpcEndpoint(prefix + "-vpce-s3", VpcEndpointArgs.builder()
                .vpcId(vpc.id())
                .serviceName(region.applyValue(r -> "com.amazonaws." + r.name() + ".s3"))
                .vpcEndpointType("Gateway")
                .routeTableIds(Output.all(privateRt.id(), publicRt.id()))
                .tags(Map.of("Name", prefix + "-vpce-s3"))
                .build(), opts);

        // DynamoDB: routes catalog-service DynamoDB traffic off NAT
        new VpcEndpoint(prefix + "-vpce-dynamodb", VpcEndpointArgs.builder()
                .vpcId(vpc.id())
                .serviceName(region.applyValue(r -> "com.amazonaws." + r.name() + ".dynamodb"))
                .vpcEndpointType("Gateway")
                .routeTableIds(Output.all(privateRt.id(), publicRt.id()))
                .tags(Map.of("Name", prefix + "-vpce-dynamodb"))
                .build(), opts);

        // ── Outputs ───────────────────────────────────────────────────────────
        this.vpcId = vpc.id();
        this.publicSubnetIds  = Output.all(List.of(publicSubnets[0].id(),  publicSubnets[1].id()));
        this.privateSubnetIds = Output.all(List.of(privateSubnets[0].id(), privateSubnets[1].id()));

        this.registerOutputs(Map.of(
                "vpcId",            this.vpcId,
                "publicSubnetIds",  this.publicSubnetIds,
                "privateSubnetIds", this.privateSubnetIds
        ));
    }

    public Output<String>       vpcId()            { return vpcId; }
    public Output<List<String>> publicSubnetIds()  { return publicSubnetIds; }
    public Output<List<String>> privateSubnetIds() { return privateSubnetIds; }
}
