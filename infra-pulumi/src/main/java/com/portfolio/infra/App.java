package com.portfolio.infra;

import com.pulumi.Pulumi;
import com.pulumi.aws.AwsFunctions;
import com.portfolio.infra.components.AlbComponent;
import com.portfolio.infra.components.ApiGatewayComponent;
import com.portfolio.infra.components.CloudMapComponent;
import com.portfolio.infra.components.CognitoComponent;
import com.portfolio.infra.components.DynamoDbComponent;
import com.portfolio.infra.components.EcrComponent;
import com.portfolio.infra.components.EcsClusterComponent;
import com.portfolio.infra.components.ElastiCacheComponent;
import com.portfolio.infra.components.EcsServiceComponent;
import com.portfolio.infra.components.GithubOidcComponent;
import com.portfolio.infra.components.NetworkComponent;
import com.portfolio.infra.components.ObservabilityComponent;
import com.portfolio.infra.components.RdsComponent;
import com.portfolio.infra.components.S3Component;
import com.portfolio.infra.components.SnsSqsComponent;

import java.util.List;

public class App {
    public static void main(String[] args) {
        Pulumi.run(ctx -> {
            var config     = ctx.config();
            var org        = config.require("org");
            var env        = config.require("environment");
            var alarmEmail = config.require("alarm_email");
            var githubOrg  = config.require("github_org");
            var githubRepo = config.require("github_repo");
            var awsRegion  = AwsFunctions.getRegion().applyValue(r -> r.name());

            // Step 2 — foundational (no deps between them)
            var network = new NetworkComponent(org, env);
            var ecr     = new EcrComponent(org, env);

            // Step 3 — auth + API layer
            var cognito = new CognitoComponent(org, env);
            var alb     = new AlbComponent(org, env, network);
            var apiGw   = new ApiGatewayComponent(org, env, network, cognito, alb);

            // Step 4 — data layer (all independent of each other)
            var rds         = new RdsComponent(org, env, network);
            var dynamoDb    = new DynamoDbComponent(org, env);
            var elastiCache = new ElastiCacheComponent(org, env, network);

            // Step 5 — supporting services (EcsCluster + CloudMap need network; others standalone)
            var ecsCluster  = new EcsClusterComponent(org, env);
            var cloudMap    = new CloudMapComponent(org, env, network);
            var snsSqs      = new SnsSqsComponent(org, env);
            var s3          = new S3Component(org, env);

            // Step 6 — ECS services (×4)
            var userSvc = new EcsServiceComponent(org, env, ecsCluster, network, alb, cognito,
                    EcsServiceComponent.Config.builder()
                            .serviceName("user-service")
                            .imageUri(ecr.repositoryUrls().get("user-service"))
                            .listenerRulePriority(100)
                            .awsRegion(awsRegion)
                            .pathPatterns(List.of("/users", "/users/*"))
                            .rdsAddress(rds.instanceAddress())
                            .rdsSecretArn(rds.secretArn())
                            .dbName("userdb")
                            .albArnSuffix(alb.arnSuffix())
                            .build());

            var catalogSvc = new EcsServiceComponent(org, env, ecsCluster, network, alb, cognito,
                    EcsServiceComponent.Config.builder()
                            .serviceName("catalog-service")
                            .imageUri(ecr.repositoryUrls().get("catalog-service"))
                            .listenerRulePriority(110)
                            .awsRegion(awsRegion)
                            .pathPatterns(List.of("/catalog", "/catalog/*"))
                            .dynamoTableArn(dynamoDb.tableArn())
                            .dynamoTableName(dynamoDb.tableName())
                            .redisEndpoint(elastiCache.primaryEndpoint())
                            .cloudMapNamespaceId(cloudMap.namespaceId())
                            .enableCloudMap(true)
                            .albArnSuffix(alb.arnSuffix())
                            .build());

            var orderSvc = new EcsServiceComponent(org, env, ecsCluster, network, alb, cognito,
                    EcsServiceComponent.Config.builder()
                            .serviceName("order-service")
                            .imageUri(ecr.repositoryUrls().get("order-service"))
                            .listenerRulePriority(120)
                            .awsRegion(awsRegion)
                            .pathPatterns(List.of("/orders", "/orders/*"))
                            .rdsAddress(rds.instanceAddress())
                            .rdsSecretArn(rds.secretArn())
                            .dbName("userdb")
                            .snsTopicArn(snsSqs.topicArn())
                            .sqsQueueUrl(snsSqs.queueUrl())
                            .sqsQueueArn(snsSqs.queueArn())
                            .cloudMapNamespaceId(cloudMap.namespaceId())
                            .enableCloudMap(true)
                            .albArnSuffix(alb.arnSuffix())
                            .build());

            var fileSvc = new EcsServiceComponent(org, env, ecsCluster, network, alb, cognito,
                    EcsServiceComponent.Config.builder()
                            .serviceName("file-service")
                            .imageUri(ecr.repositoryUrls().get("file-service"))
                            .listenerRulePriority(130)
                            .awsRegion(awsRegion)
                            .pathPatterns(List.of("/files", "/files/*"))
                            .s3BucketName(s3.bucketName())
                            .s3BucketArn(s3.bucketArn())
                            .cloudMapNamespaceId(cloudMap.namespaceId())
                            .enableCloudMap(true)
                            .albArnSuffix(alb.arnSuffix())
                            .build());

            // Step 7 — security + observability
            var oidc = new GithubOidcComponent(org, env, githubOrg, githubRepo);

            var obs = new ObservabilityComponent(org, env, alarmEmail,
                    awsRegion, ecsCluster.clusterName(),
                    com.pulumi.core.Output.of(org + "-" + env + "-postgres"),
                    snsSqs.queueName(), snsSqs.dlqName(),
                    dynamoDb.tableName(), alb.arnSuffix(),
                    List.of(
                            new ObservabilityComponent.ServiceInfo("user-service",
                                    userSvc.ecsServiceName(), userSvc.targetGroupArnSuffix()),
                            new ObservabilityComponent.ServiceInfo("catalog-service",
                                    catalogSvc.ecsServiceName(), catalogSvc.targetGroupArnSuffix()),
                            new ObservabilityComponent.ServiceInfo("order-service",
                                    orderSvc.ecsServiceName(), orderSvc.targetGroupArnSuffix()),
                            new ObservabilityComponent.ServiceInfo("file-service",
                                    fileSvc.ecsServiceName(), fileSvc.targetGroupArnSuffix())));

            // Stack outputs
            ctx.export("vpcId",               network.vpcId());
            ctx.export("apiGatewayEndpoint",  apiGw.apiEndpoint());
            ctx.export("cognitoUserPoolId",   cognito.userPoolId());
            ctx.export("cognitoAppClientId",  cognito.appClientId());
            ctx.export("ecrUserServiceUrl",   ecr.repositoryUrls().get("user-service"));
            ctx.export("ciRoleArn",           oidc.roleArn());
            ctx.export("rdsEndpoint",         rds.instanceEndpoint());
            ctx.export("catalogTableName",    dynamoDb.tableName());
            ctx.export("redisEndpoint",       elastiCache.primaryEndpoint());
            ctx.export("ecsClusterArn",       ecsCluster.clusterArn());
            ctx.export("cloudMapNamespaceId", cloudMap.namespaceId());
            ctx.export("ordersTopicArn",      snsSqs.topicArn());
            ctx.export("filesBucketName",     s3.bucketName());
            ctx.export("alarmTopicArn",       obs.alarmTopicArn());
        });
    }
}
