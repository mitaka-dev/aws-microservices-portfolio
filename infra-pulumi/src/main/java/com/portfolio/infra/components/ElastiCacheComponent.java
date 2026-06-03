package com.portfolio.infra.components;

import com.pulumi.aws.ec2.SecurityGroup;
import com.pulumi.aws.ec2.SecurityGroupArgs;
import com.pulumi.aws.ec2.inputs.SecurityGroupEgressArgs;
import com.pulumi.aws.elasticache.ReplicationGroup;
import com.pulumi.aws.elasticache.ReplicationGroupArgs;
import com.pulumi.aws.elasticache.SubnetGroup;
import com.pulumi.aws.elasticache.SubnetGroupArgs;
import com.pulumi.core.Output;
import com.pulumi.resources.ComponentResource;
import com.pulumi.resources.ComponentResourceOptions;
import com.pulumi.resources.CustomResourceOptions;

import java.util.List;
import java.util.Map;

/**
 * Mirrors infra/modules/elasticache-redis (security-hardened): Redis replication
 * group with at-rest + in-transit encryption, subnet group, SG.
 */
public class ElastiCacheComponent extends ComponentResource {

    private final Output<String> primaryEndpoint;
    private final Output<String> sgId;

    public ElastiCacheComponent(String org, String env, NetworkComponent network) {
        super("portfolio:infra:ElastiCache", org + "-" + env + "-elasticache",
              ComponentResourceOptions.builder().build());

        var prefix = org + "-" + env;
        var opts   = CustomResourceOptions.builder().parent(this).build();

        // ── Subnet group + security group ─────────────────────────────────────
        var subnetGroup = new SubnetGroup(prefix + "-redis-subnet-group", SubnetGroupArgs.builder()
                .name(prefix + "-redis-subnet-group")
                .subnetIds(network.privateSubnetIds())
                .tags(Map.of("Name", prefix + "-redis-subnet-group"))
                .build(), opts);

        var redisSg = new SecurityGroup(prefix + "-redis-sg", SecurityGroupArgs.builder()
                .name(prefix + "-redis-sg")
                .vpcId(network.vpcId())
                .egress(SecurityGroupEgressArgs.builder()
                        .fromPort(0).toPort(0).protocol("-1")
                        .cidrBlocks(List.of("0.0.0.0/0"))
                        .build())
                .tags(Map.of("Name", prefix + "-redis-sg"))
                .build(), opts);

        // ── Redis replication group (encryption at rest + in transit) ─────────
        var redis = new ReplicationGroup(prefix + "-redis", ReplicationGroupArgs.builder()
                .replicationGroupId(prefix + "-redis")
                .description(prefix + " Redis replication group")
                .nodeType("cache.t4g.micro")
                .numCacheClusters(1)
                .engineVersion("7.1")
                .parameterGroupName("default.redis7")
                .subnetGroupName(subnetGroup.name())
                .securityGroupIds(redisSg.id().applyValue(List::of))
                .atRestEncryptionEnabled(true)
                .transitEncryptionEnabled(true)
                .applyImmediately(true)
                .tags(Map.of("Name", prefix + "-redis"))
                .build(), opts);

        // ── Outputs ───────────────────────────────────────────────────────────
        this.primaryEndpoint = redis.primaryEndpointAddress();
        this.sgId            = redisSg.id();

        this.registerOutputs(Map.of(
                "primaryEndpoint", this.primaryEndpoint,
                "sgId",            this.sgId
        ));
    }

    public Output<String> primaryEndpoint() { return primaryEndpoint; }
    public Output<String> sgId()            { return sgId; }
}
