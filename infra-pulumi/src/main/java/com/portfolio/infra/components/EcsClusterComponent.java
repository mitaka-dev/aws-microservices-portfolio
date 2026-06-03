package com.portfolio.infra.components;

import com.pulumi.aws.ecs.Cluster;
import com.pulumi.aws.ecs.ClusterArgs;
import com.pulumi.aws.ecs.ClusterCapacityProviders;
import com.pulumi.aws.ecs.ClusterCapacityProvidersArgs;
import com.pulumi.aws.ecs.inputs.ClusterCapacityProvidersDefaultCapacityProviderStrategyArgs;
import com.pulumi.aws.ecs.inputs.ClusterSettingArgs;
import com.pulumi.core.Output;
import com.pulumi.resources.ComponentResource;
import com.pulumi.resources.ComponentResourceOptions;
import com.pulumi.resources.CustomResourceOptions;

import java.util.List;
import java.util.Map;

/**
 * Mirrors infra/modules/ecs-cluster: ECS cluster with container insights,
 * FARGATE + FARGATE_SPOT capacity providers.
 */
public class EcsClusterComponent extends ComponentResource {

    private final Output<String> clusterArn;
    private final Output<String> clusterName;

    public EcsClusterComponent(String org, String env) {
        super("portfolio:infra:EcsCluster", org + "-" + env + "-ecs-cluster",
              ComponentResourceOptions.builder().build());

        var prefix = org + "-" + env;
        var opts   = CustomResourceOptions.builder().parent(this).build();

        var cluster = new Cluster(prefix + "-cluster", ClusterArgs.builder()
                .name(prefix + "-cluster")
                .settings(List.of(
                        ClusterSettingArgs.builder()
                                .name("containerInsights")
                                .value("enabled")
                                .build()
                ))
                .tags(Map.of("Name", prefix + "-cluster"))
                .build(), opts);

        new ClusterCapacityProviders(prefix + "-capacity-providers",
                ClusterCapacityProvidersArgs.builder()
                        .clusterName(cluster.name())
                        .capacityProviders(List.of("FARGATE", "FARGATE_SPOT"))
                        .defaultCapacityProviderStrategies(List.of(
                                ClusterCapacityProvidersDefaultCapacityProviderStrategyArgs.builder()
                                        .capacityProvider("FARGATE")
                                        .weight(1)
                                        .base(1)
                                        .build()
                        ))
                        .build(), opts);

        this.clusterArn  = cluster.arn();
        this.clusterName = cluster.name();

        this.registerOutputs(Map.of(
                "clusterArn",  this.clusterArn,
                "clusterName", this.clusterName
        ));
    }

    public Output<String> clusterArn()  { return clusterArn; }
    public Output<String> clusterName() { return clusterName; }
}
