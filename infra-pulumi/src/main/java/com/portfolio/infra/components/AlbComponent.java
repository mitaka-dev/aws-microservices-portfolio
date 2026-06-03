package com.portfolio.infra.components;

import com.pulumi.aws.ec2.SecurityGroup;
import com.pulumi.aws.ec2.SecurityGroupArgs;
import com.pulumi.aws.ec2.inputs.SecurityGroupEgressArgs;
import com.pulumi.aws.lb.Listener;
import com.pulumi.aws.lb.ListenerArgs;
import com.pulumi.aws.lb.LoadBalancer;
import com.pulumi.aws.lb.LoadBalancerArgs;
import com.pulumi.aws.lb.inputs.ListenerDefaultActionArgs;
import com.pulumi.aws.lb.inputs.ListenerDefaultActionFixedResponseArgs;
import com.pulumi.core.Output;
import com.pulumi.resources.ComponentResource;
import com.pulumi.resources.ComponentResourceOptions;
import com.pulumi.resources.CustomResourceOptions;

import java.util.List;
import java.util.Map;

/**
 * Mirrors infra/modules/alb: internal ALB, HTTP listener, security group.
 */
public class AlbComponent extends ComponentResource {

    private final Output<String> albArn;
    private final Output<String> albDnsName;
    private final Output<String> albSgId;
    private final Output<String> httpListenerArn;
    private final Output<String> arnSuffix;

    public AlbComponent(String org, String env, NetworkComponent network) {
        super("portfolio:infra:Alb", org + "-" + env + "-alb",
              ComponentResourceOptions.builder().build());

        var prefix = org + "-" + env;
        var opts   = CustomResourceOptions.builder().parent(this).build();

        // ── Security group ────────────────────────────────────────────────────
        var albSg = new SecurityGroup(prefix + "-alb-sg", SecurityGroupArgs.builder()
                .name(prefix + "-alb-sg")
                .vpcId(network.vpcId())
                .egress(SecurityGroupEgressArgs.builder()
                        .fromPort(0).toPort(0).protocol("-1")
                        .cidrBlocks(List.of("0.0.0.0/0"))
                        .build())
                .tags(Map.of("Name", prefix + "-alb-sg"))
                .build(), opts);

        // ── Internal ALB (private subnets) ────────────────────────────────────
        var alb = new LoadBalancer(prefix + "-alb", LoadBalancerArgs.builder()
                .name(prefix + "-alb")
                .internal(true)
                .loadBalancerType("application")
                .securityGroups(albSg.id().applyValue(List::of))
                .subnets(network.privateSubnetIds())
                .tags(Map.of("Name", prefix + "-alb"))
                .build(), opts);

        // ── HTTP listener — default 404 fixed response ─────────────────────────
        var listener = new Listener(prefix + "-http", ListenerArgs.builder()
                .loadBalancerArn(alb.arn())
                .port(80)
                .protocol("HTTP")
                .defaultActions(List.of(
                        ListenerDefaultActionArgs.builder()
                                .type("fixed-response")
                                .fixedResponse(ListenerDefaultActionFixedResponseArgs.builder()
                                        .contentType("application/json")
                                        .messageBody("{\"message\":\"Not Found\"}")
                                        .statusCode("404")
                                        .build())
                                .build()))
                .build(), opts);

        // ── Outputs ───────────────────────────────────────────────────────────
        this.albArn         = alb.arn();
        this.albDnsName     = alb.dnsName();
        this.albSgId        = albSg.id();
        this.httpListenerArn = listener.arn();
        this.arnSuffix      = alb.arnSuffix();

        this.registerOutputs(Map.of(
                "albArn",          this.albArn,
                "albDnsName",      this.albDnsName,
                "albSgId",         this.albSgId,
                "httpListenerArn", this.httpListenerArn,
                "arnSuffix",       this.arnSuffix
        ));
    }

    public Output<String> albArn()          { return albArn; }
    public Output<String> albDnsName()      { return albDnsName; }
    public Output<String> albSgId()         { return albSgId; }
    public Output<String> httpListenerArn() { return httpListenerArn; }
    public Output<String> arnSuffix()       { return arnSuffix; }
}
