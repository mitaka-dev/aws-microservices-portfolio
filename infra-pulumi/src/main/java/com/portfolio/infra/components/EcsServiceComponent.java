package com.portfolio.infra.components;

import com.pulumi.aws.appautoscaling.Policy;
import com.pulumi.aws.appautoscaling.PolicyArgs;
import com.pulumi.aws.appautoscaling.Target;
import com.pulumi.aws.appautoscaling.TargetArgs;
import com.pulumi.aws.appautoscaling.inputs.PolicyTargetTrackingScalingPolicyConfigurationArgs;
import com.pulumi.aws.appautoscaling.inputs.PolicyTargetTrackingScalingPolicyConfigurationPredefinedMetricSpecificationArgs;
import com.pulumi.aws.cloudwatch.LogGroup;
import com.pulumi.aws.cloudwatch.LogGroupArgs;
import com.pulumi.aws.ec2.SecurityGroup;
import com.pulumi.aws.ec2.SecurityGroupArgs;
import com.pulumi.aws.ec2.inputs.SecurityGroupEgressArgs;
import com.pulumi.aws.ecs.Service;
import com.pulumi.aws.ecs.ServiceArgs;
import com.pulumi.aws.ecs.TaskDefinition;
import com.pulumi.aws.ecs.TaskDefinitionArgs;
import com.pulumi.aws.ecs.inputs.ServiceLoadBalancerArgs;
import com.pulumi.aws.ecs.inputs.ServiceNetworkConfigurationArgs;
import com.pulumi.aws.ecs.inputs.ServiceServiceRegistriesArgs;
import com.pulumi.aws.iam.Role;
import com.pulumi.aws.iam.RoleArgs;
import com.pulumi.aws.iam.RolePolicy;
import com.pulumi.aws.iam.RolePolicyArgs;
import com.pulumi.aws.iam.RolePolicyAttachment;
import com.pulumi.aws.iam.RolePolicyAttachmentArgs;
import com.pulumi.aws.lb.ListenerRule;
import com.pulumi.aws.lb.ListenerRuleArgs;
import com.pulumi.aws.lb.TargetGroup;
import com.pulumi.aws.lb.TargetGroupArgs;
import com.pulumi.aws.lb.inputs.ListenerRuleActionArgs;
import com.pulumi.aws.lb.inputs.ListenerRuleConditionArgs;
import com.pulumi.aws.lb.inputs.ListenerRuleConditionPathPatternArgs;
import com.pulumi.aws.lb.inputs.TargetGroupHealthCheckArgs;
import com.pulumi.core.Output;
import com.pulumi.resources.ComponentResource;
import com.pulumi.resources.ComponentResourceOptions;
import com.pulumi.resources.CustomResourceOptions;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Mirrors infra/modules/ecs-service: Fargate service with IAM roles, CloudWatch
 * logs, ALB target group, optional Cloud Map registration, ADOT sidecar, and
 * application auto-scaling. Instantiated once per service (×4).
 */
public class EcsServiceComponent extends ComponentResource {

    private static final String ECS_ASSUME_POLICY =
            "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\"," +
            "\"Principal\":{\"Service\":\"ecs-tasks.amazonaws.com\"}," +
            "\"Action\":\"sts:AssumeRole\"}]}";

    // YAML escaped for JSON embedding as AOT_CONFIG_CONTENT env var
    private static final String ADOT_CONFIG =
            "extensions:\\n  health_check:\\nreceivers:\\n  otlp:\\n    protocols:\\n      grpc:\\n" +
            "        endpoint: 0.0.0.0:4317\\nprocessors:\\n  batch/traces:\\n    timeout: 1s\\n" +
            "    send_batch_size: 50\\nexporters:\\n  awsxray:\\nservice:\\n  extensions: [health_check]\\n" +
            "  pipelines:\\n    traces:\\n      receivers: [otlp]\\n      processors: [batch/traces]\\n" +
            "      exporters: [awsxray]";

    // ── Per-service configuration ─────────────────────────────────────────────

    public static final class Config {
        final String serviceName;
        final Output<String> imageUri;
        final int listenerRulePriority;
        final Output<String> awsRegion;
        final int containerPort;
        final int cpu;
        final int memory;
        final List<String> pathPatterns;
        // Optional integrations — null = disabled
        final Output<String> rdsAddress;
        final Output<String> rdsSecretArn;
        final String dbName;
        final Output<String> dynamoTableArn;
        final Output<String> dynamoTableName;
        final Output<String> redisEndpoint;
        final Output<String> snsTopicArn;
        final Output<String> sqsQueueUrl;
        final Output<String> sqsQueueArn;
        final Output<String> s3BucketName;
        final Output<String> s3BucketArn;
        final Output<String> cloudMapNamespaceId;
        final boolean enableCloudMap;
        final Output<String> albArnSuffix;

        private Config(Builder b) {
            this.serviceName          = b.serviceName;
            this.imageUri             = b.imageUri;
            this.listenerRulePriority = b.listenerRulePriority;
            this.awsRegion            = b.awsRegion;
            this.containerPort        = b.containerPort;
            this.cpu                  = b.cpu;
            this.memory               = b.memory;
            this.pathPatterns         = b.pathPatterns;
            this.rdsAddress           = b.rdsAddress;
            this.rdsSecretArn         = b.rdsSecretArn;
            this.dbName               = b.dbName;
            this.dynamoTableArn       = b.dynamoTableArn;
            this.dynamoTableName      = b.dynamoTableName;
            this.redisEndpoint        = b.redisEndpoint;
            this.snsTopicArn          = b.snsTopicArn;
            this.sqsQueueUrl          = b.sqsQueueUrl;
            this.sqsQueueArn          = b.sqsQueueArn;
            this.s3BucketName         = b.s3BucketName;
            this.s3BucketArn          = b.s3BucketArn;
            this.cloudMapNamespaceId  = b.cloudMapNamespaceId;
            this.enableCloudMap       = b.enableCloudMap;
            this.albArnSuffix         = b.albArnSuffix;
        }

        public static Builder builder() { return new Builder(); }

        public static final class Builder {
            private String serviceName;
            private Output<String> imageUri;
            private int listenerRulePriority = 100;
            private Output<String> awsRegion;
            private int containerPort = 8080;
            private int cpu = 256;
            private int memory = 512;
            private List<String> pathPatterns;
            private Output<String> rdsAddress;
            private Output<String> rdsSecretArn;
            private String dbName;
            private Output<String> dynamoTableArn;
            private Output<String> dynamoTableName;
            private Output<String> redisEndpoint;
            private Output<String> snsTopicArn;
            private Output<String> sqsQueueUrl;
            private Output<String> sqsQueueArn;
            private Output<String> s3BucketName;
            private Output<String> s3BucketArn;
            private Output<String> cloudMapNamespaceId;
            private boolean enableCloudMap = false;
            private Output<String> albArnSuffix;

            public Builder serviceName(String v)           { this.serviceName = v; return this; }
            public Builder imageUri(Output<String> v)      { this.imageUri = v; return this; }
            public Builder listenerRulePriority(int v)     { this.listenerRulePriority = v; return this; }
            public Builder awsRegion(Output<String> v)     { this.awsRegion = v; return this; }
            public Builder containerPort(int v)            { this.containerPort = v; return this; }
            public Builder cpu(int v)                      { this.cpu = v; return this; }
            public Builder memory(int v)                   { this.memory = v; return this; }
            public Builder pathPatterns(List<String> v)    { this.pathPatterns = v; return this; }
            public Builder rdsAddress(Output<String> v)    { this.rdsAddress = v; return this; }
            public Builder rdsSecretArn(Output<String> v)  { this.rdsSecretArn = v; return this; }
            public Builder dbName(String v)                { this.dbName = v; return this; }
            public Builder dynamoTableArn(Output<String> v){ this.dynamoTableArn = v; return this; }
            public Builder dynamoTableName(Output<String> v){ this.dynamoTableName = v; return this; }
            public Builder redisEndpoint(Output<String> v) { this.redisEndpoint = v; return this; }
            public Builder snsTopicArn(Output<String> v)   { this.snsTopicArn = v; return this; }
            public Builder sqsQueueUrl(Output<String> v)   { this.sqsQueueUrl = v; return this; }
            public Builder sqsQueueArn(Output<String> v)   { this.sqsQueueArn = v; return this; }
            public Builder s3BucketName(Output<String> v)  { this.s3BucketName = v; return this; }
            public Builder s3BucketArn(Output<String> v)   { this.s3BucketArn = v; return this; }
            public Builder cloudMapNamespaceId(Output<String> v) { this.cloudMapNamespaceId = v; return this; }
            public Builder enableCloudMap(boolean v)       { this.enableCloudMap = v; return this; }
            public Builder albArnSuffix(Output<String> v)  { this.albArnSuffix = v; return this; }

            public Config build() { return new Config(this); }
        }
    }

    private final Output<String> taskSgId;
    private final Output<String> ecsServiceName;
    private final Output<String> targetGroupArnSuffix;

    public EcsServiceComponent(
            String org, String env,
            EcsClusterComponent ecsCluster,
            NetworkComponent network,
            AlbComponent alb,
            CognitoComponent cognito,
            Config cfg) {

        super("portfolio:infra:EcsService", org + "-" + env + "-" + cfg.serviceName,
              ComponentResourceOptions.builder().build());

        var prefix  = org + "-" + env;
        var svcName = cfg.serviceName;
        var opts    = CustomResourceOptions.builder().parent(this).build();

        // ── CloudWatch log group ──────────────────────────────────────────────
        var logGroupName = "/ecs/" + prefix + "/" + svcName;
        new LogGroup(prefix + "-" + svcName + "-logs", LogGroupArgs.builder()
                .name(logGroupName)
                .retentionInDays(7)
                .tags(Map.of("Name", prefix + "-" + svcName))
                .build(), opts);

        // ── IAM exec role ─────────────────────────────────────────────────────
        var execRole = new Role(prefix + "-" + svcName + "-exec", RoleArgs.builder()
                .name(prefix + "-" + svcName + "-exec")
                .assumeRolePolicy(ECS_ASSUME_POLICY)
                .build(), opts);

        new RolePolicyAttachment(prefix + "-" + svcName + "-exec-basic",
                RolePolicyAttachmentArgs.builder()
                        .role(execRole.name())
                        .policyArn("arn:aws:iam::aws:policy/service-role/AmazonECSTaskExecutionRolePolicy")
                        .build(), opts);

        if (cfg.rdsSecretArn != null) {
            new RolePolicy(prefix + "-" + svcName + "-exec-secrets", RolePolicyArgs.builder()
                    .name(prefix + "-" + svcName + "-exec-secrets")
                    .role(execRole.id())
                    .policy(cfg.rdsSecretArn.applyValue(arn ->
                            "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\"," +
                            "\"Action\":[\"secretsmanager:GetSecretValue\"]," +
                            "\"Resource\":\"" + arn + "\"}]}"))
                    .build(), opts);
        }

        // ── IAM task role ─────────────────────────────────────────────────────
        var taskRole = new Role(prefix + "-" + svcName + "-task", RoleArgs.builder()
                .name(prefix + "-" + svcName + "-task")
                .assumeRolePolicy(ECS_ASSUME_POLICY)
                .build(), opts);

        new RolePolicy(prefix + "-" + svcName + "-task-cw", RolePolicyArgs.builder()
                .name(prefix + "-" + svcName + "-task-cw")
                .role(taskRole.id())
                .policy("{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\"," +
                        "\"Action\":[\"cloudwatch:PutMetricData\"],\"Resource\":\"*\"}]}")
                .build(), opts);

        new RolePolicy(prefix + "-" + svcName + "-task-xray", RolePolicyArgs.builder()
                .name(prefix + "-" + svcName + "-task-xray")
                .role(taskRole.id())
                .policy("{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\"," +
                        "\"Action\":[\"xray:PutTraceSegments\",\"xray:PutTelemetryRecords\"," +
                        "\"xray:GetSamplingRules\",\"xray:GetSamplingTargets\"]," +
                        "\"Resource\":\"*\"}]}")
                .build(), opts);

        if (cfg.dynamoTableArn != null) {
            new RolePolicy(prefix + "-" + svcName + "-task-ddb", RolePolicyArgs.builder()
                    .name(prefix + "-" + svcName + "-task-ddb")
                    .role(taskRole.id())
                    .policy(cfg.dynamoTableArn.applyValue(arn ->
                            "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\"," +
                            "\"Action\":[\"dynamodb:GetItem\",\"dynamodb:PutItem\",\"dynamodb:UpdateItem\"," +
                            "\"dynamodb:DeleteItem\",\"dynamodb:Query\",\"dynamodb:Scan\"," +
                            "\"dynamodb:BatchWriteItem\",\"dynamodb:BatchGetItem\",\"dynamodb:TransactWriteItems\"]," +
                            "\"Resource\":[\"" + arn + "\",\"" + arn + "/index/*\"]}]}"))
                    .build(), opts);
        }

        if (cfg.sqsQueueArn != null) {
            new RolePolicy(prefix + "-" + svcName + "-task-sqs", RolePolicyArgs.builder()
                    .name(prefix + "-" + svcName + "-task-sqs")
                    .role(taskRole.id())
                    .policy(cfg.sqsQueueArn.applyValue(arn ->
                            "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\"," +
                            "\"Action\":[\"sqs:ReceiveMessage\",\"sqs:DeleteMessage\"," +
                            "\"sqs:GetQueueAttributes\",\"sqs:ChangeMessageVisibility\"]," +
                            "\"Resource\":\"" + arn + "\"}]}"))
                    .build(), opts);
        }

        if (cfg.snsTopicArn != null) {
            new RolePolicy(prefix + "-" + svcName + "-task-sns", RolePolicyArgs.builder()
                    .name(prefix + "-" + svcName + "-task-sns")
                    .role(taskRole.id())
                    .policy(cfg.snsTopicArn.applyValue(arn ->
                            "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\"," +
                            "\"Action\":[\"sns:Publish\"],\"Resource\":\"" + arn + "\"}]}"))
                    .build(), opts);
        }

        if (cfg.s3BucketArn != null) {
            new RolePolicy(prefix + "-" + svcName + "-task-s3", RolePolicyArgs.builder()
                    .name(prefix + "-" + svcName + "-task-s3")
                    .role(taskRole.id())
                    .policy(cfg.s3BucketArn.applyValue(arn ->
                            "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\"," +
                            "\"Action\":[\"s3:GetObject\",\"s3:PutObject\"]," +
                            "\"Resource\":\"" + arn + "/*\"}]}"))
                    .build(), opts);
        }

        // ── Cloud Map service (optional) ──────────────────────────────────────
        Output<String> cloudMapRegistryArn = null;
        if (cfg.enableCloudMap && cfg.cloudMapNamespaceId != null) {
            var cmSvc = new com.pulumi.aws.servicediscovery.Service(
                    prefix + "-" + svcName + "-cm",
                    com.pulumi.aws.servicediscovery.ServiceArgs.builder()
                            .name(svcName)
                            .namespaceId(cfg.cloudMapNamespaceId)
                            .dnsConfig(com.pulumi.aws.servicediscovery.inputs.ServiceDnsConfigArgs.builder()
                                    .namespaceId(cfg.cloudMapNamespaceId)
                                    .routingPolicy("MULTIVALUE")
                                    .dnsRecords(com.pulumi.aws.servicediscovery.inputs.ServiceDnsConfigDnsRecordArgs.builder()
                                            .type("A")
                                            .ttl(10)
                                            .build())
                                    .build())
                            .healthCheckCustomConfig(
                                    com.pulumi.aws.servicediscovery.inputs.ServiceHealthCheckCustomConfigArgs.builder()
                                            .failureThreshold(1)
                                            .build())
                            .build(), opts);
            cloudMapRegistryArn = cmSvc.arn();
        }

        // ── Container definitions ─────────────────────────────────────────────
        var containerDefs = buildContainerDefs(svcName, cfg, logGroupName, cognito.issuerUri());

        // ── Task definition ───────────────────────────────────────────────────
        var taskDef = new TaskDefinition(prefix + "-" + svcName, TaskDefinitionArgs.builder()
                .family(prefix + "-" + svcName)
                .requiresCompatibilities(List.of("FARGATE"))
                .networkMode("awsvpc")
                .cpu(String.valueOf(cfg.cpu))
                .memory(String.valueOf(cfg.memory))
                .executionRoleArn(execRole.arn())
                .taskRoleArn(taskRole.arn())
                .containerDefinitions(containerDefs)
                .tags(Map.of("Name", prefix + "-" + svcName))
                .build(), opts);

        // ── ALB target group ──────────────────────────────────────────────────
        var tg = new TargetGroup(prefix + "-" + svcName + "-tg", TargetGroupArgs.builder()
                .name(prefix + "-" + svcName + "-tg")
                .port(cfg.containerPort)
                .protocol("HTTP")
                .vpcId(network.vpcId())
                .targetType("ip")
                .deregistrationDelay(30)
                .healthCheck(TargetGroupHealthCheckArgs.builder()
                        .path("/actuator/health")
                        .matcher("200")
                        .interval(30)
                        .timeout(5)
                        .healthyThreshold(2)
                        .unhealthyThreshold(3)
                        .build())
                .tags(Map.of("Name", prefix + "-" + svcName + "-tg"))
                .build(), opts);

        var rule = new ListenerRule(prefix + "-" + svcName + "-rule", ListenerRuleArgs.builder()
                .listenerArn(alb.httpListenerArn())
                .priority(cfg.listenerRulePriority)
                .actions(List.of(ListenerRuleActionArgs.builder()
                        .type("forward")
                        .targetGroupArn(tg.arn())
                        .build()))
                .conditions(List.of(ListenerRuleConditionArgs.builder()
                        .pathPattern(ListenerRuleConditionPathPatternArgs.builder()
                                .values(cfg.pathPatterns)
                                .build())
                        .build()))
                .build(), opts);

        // ── Task security group ───────────────────────────────────────────────
        var taskSg = new SecurityGroup(prefix + "-" + svcName + "-sg", SecurityGroupArgs.builder()
                .name(prefix + "-" + svcName + "-sg")
                .vpcId(network.vpcId())
                .egress(SecurityGroupEgressArgs.builder()
                        .fromPort(0).toPort(0).protocol("-1")
                        .cidrBlocks(List.of("0.0.0.0/0"))
                        .build())
                .tags(Map.of("Name", prefix + "-" + svcName + "-sg"))
                .build(), opts);

        // ── ECS service ───────────────────────────────────────────────────────
        var svcOptsBuilder = CustomResourceOptions.builder()
                .parent(this)
                .ignoreChanges(List.of("desiredCount"))
                .dependsOn(List.of(rule));

        var svcArgsBuilder = ServiceArgs.builder()
                .name(prefix + "-" + svcName)
                .cluster(ecsCluster.clusterArn())
                .taskDefinition(taskDef.arn())
                .desiredCount(1)
                .launchType("FARGATE")
                .networkConfiguration(ServiceNetworkConfigurationArgs.builder()
                        .subnets(network.privateSubnetIds())
                        .securityGroups(taskSg.id().applyValue(List::of))
                        .assignPublicIp(false)
                        .build())
                .loadBalancers(ServiceLoadBalancerArgs.builder()
                        .targetGroupArn(tg.arn())
                        .containerName(svcName)
                        .containerPort(cfg.containerPort)
                        .build())
                .deploymentMinimumHealthyPercent(0)
                .deploymentMaximumPercent(200)
                .healthCheckGracePeriodSeconds(120)
                .tags(Map.of("Name", prefix + "-" + svcName));

        if (cloudMapRegistryArn != null) {
            svcArgsBuilder.serviceRegistries(ServiceServiceRegistriesArgs.builder()
                    .registryArn(cloudMapRegistryArn)
                    .build());
        }

        var svc = new Service(prefix + "-" + svcName, svcArgsBuilder.build(), svcOptsBuilder.build());

        // ── Application auto-scaling ──────────────────────────────────────────
        var resourceId = ecsCluster.clusterName().applyValue(
                cn -> "service/" + cn + "/" + prefix + "-" + svcName);

        var asgOpts = CustomResourceOptions.builder().parent(this).dependsOn(List.of(svc)).build();

        var scalingTarget = new Target(prefix + "-" + svcName + "-asg", TargetArgs.builder()
                .maxCapacity(3)
                .minCapacity(1)
                .resourceId(resourceId)
                .scalableDimension("ecs:service:DesiredCount")
                .serviceNamespace("ecs")
                .build(), asgOpts);

        new Policy(prefix + "-" + svcName + "-cpu", PolicyArgs.builder()
                .name(prefix + "-" + svcName + "-cpu")
                .policyType("TargetTrackingScaling")
                .resourceId(scalingTarget.resourceId())
                .scalableDimension(scalingTarget.scalableDimension())
                .serviceNamespace(scalingTarget.serviceNamespace())
                .targetTrackingScalingPolicyConfiguration(
                        PolicyTargetTrackingScalingPolicyConfigurationArgs.builder()
                                .predefinedMetricSpecification(
                                        PolicyTargetTrackingScalingPolicyConfigurationPredefinedMetricSpecificationArgs.builder()
                                                .predefinedMetricType("ECSServiceAverageCPUUtilization")
                                                .build())
                                .targetValue(70.0)
                                .scaleInCooldown(300)
                                .scaleOutCooldown(60)
                                .build())
                .build(), opts);

        if (cfg.albArnSuffix != null) {
            var resourceLabel = Output.all(List.of(cfg.albArnSuffix, tg.arnSuffix()))
                    .applyValue(arns -> arns.get(0) + "/" + arns.get(1));

            new Policy(prefix + "-" + svcName + "-alb", PolicyArgs.builder()
                    .name(prefix + "-" + svcName + "-alb-requests")
                    .policyType("TargetTrackingScaling")
                    .resourceId(scalingTarget.resourceId())
                    .scalableDimension(scalingTarget.scalableDimension())
                    .serviceNamespace(scalingTarget.serviceNamespace())
                    .targetTrackingScalingPolicyConfiguration(
                            PolicyTargetTrackingScalingPolicyConfigurationArgs.builder()
                                    .predefinedMetricSpecification(
                                            PolicyTargetTrackingScalingPolicyConfigurationPredefinedMetricSpecificationArgs.builder()
                                                    .predefinedMetricType("ALBRequestCountPerTarget")
                                                    .resourceLabel(resourceLabel)
                                                    .build())
                                    .targetValue(50.0)
                                    .scaleInCooldown(300)
                                    .scaleOutCooldown(60)
                                    .build())
                    .build(), opts);
        }

        // ── Outputs ───────────────────────────────────────────────────────────
        this.taskSgId             = taskSg.id();
        this.ecsServiceName       = svc.name();
        this.targetGroupArnSuffix = tg.arnSuffix();

        this.registerOutputs(Map.of(
                "taskSgId",             this.taskSgId,
                "ecsServiceName",       this.ecsServiceName,
                "targetGroupArnSuffix", this.targetGroupArnSuffix
        ));
    }

    // ── Container definition JSON builder ─────────────────────────────────────

    private static Output<String> buildContainerDefs(
            String svcName, Config cfg, String logGroupName, Output<String> cognitoIssuerUri) {

        var named = new LinkedHashMap<String, Output<String>>();
        named.put("image",   cfg.imageUri);
        named.put("issuer",  cognitoIssuerUri);
        named.put("region",  cfg.awsRegion);
        if (cfg.rdsAddress     != null) named.put("rdsAddr",   cfg.rdsAddress);
        if (cfg.rdsSecretArn   != null) named.put("secretArn", cfg.rdsSecretArn);
        if (cfg.dynamoTableName != null) named.put("dynamo",   cfg.dynamoTableName);
        if (cfg.redisEndpoint  != null) named.put("redis",     cfg.redisEndpoint);
        if (cfg.snsTopicArn    != null) named.put("sns",       cfg.snsTopicArn);
        if (cfg.sqsQueueUrl    != null) named.put("sqs",       cfg.sqsQueueUrl);
        if (cfg.s3BucketName   != null) named.put("s3",        cfg.s3BucketName);

        var keys   = new ArrayList<>(named.keySet());
        var values = keys.stream().map(named::get).collect(Collectors.toList());
        final var port  = cfg.containerPort;
        final var dbNm  = cfg.dbName != null ? cfg.dbName : "";

        return Output.all(values).applyValue(vals -> {
            var r = new HashMap<String, String>();
            for (int i = 0; i < keys.size(); i++) r.put(keys.get(i), vals.get(i));

            var region = r.get("region");

            // ── Environment variables ─────────────────────────────────────────
            var env = new StringBuilder("[");
            env.append(kv("SPRING_PROFILES_ACTIVE", "aws")).append(",");
            env.append(kv("AWS_REGION", region)).append(",");
            env.append(kv("COGNITO_ISSUER_URI", r.get("issuer")));
            if (r.containsKey("rdsAddr"))  { env.append(",").append(kv("DB_HOST", r.get("rdsAddr"))); }
            if (!dbNm.isEmpty())           { env.append(",").append(kv("DB_NAME", dbNm)); }
            if (r.containsKey("dynamo"))   { env.append(",").append(kv("DYNAMODB_TABLE_NAME", r.get("dynamo"))); }
            if (r.containsKey("redis"))    { env.append(",").append(kv("REDIS_HOST", r.get("redis"))).append(",").append(kv("REDIS_PORT", "6379")); }
            if (r.containsKey("sns"))      { env.append(",").append(kv("SNS_ORDERS_TOPIC_ARN", r.get("sns"))); }
            if (r.containsKey("sqs"))      { env.append(",").append(kv("SQS_ORDERS_QUEUE_URL", r.get("sqs"))); }
            if (r.containsKey("s3"))       { env.append(",").append(kv("S3_BUCKET_NAME", r.get("s3"))); }
            env.append(",").append(kv("JAVA_TOOL_OPTIONS", "-javaagent:/otel/javaagent.jar"));
            env.append(",").append(kv("OTEL_EXPORTER_OTLP_ENDPOINT", "http://localhost:4317"));
            env.append(",").append(kv("OTEL_TRACES_EXPORTER", "otlp"));
            env.append(",").append(kv("OTEL_METRICS_EXPORTER", "none"));
            env.append(",").append(kv("OTEL_LOGS_EXPORTER", "none"));
            env.append(",").append(kv("OTEL_SERVICE_NAME", svcName));
            env.append("]");

            // ── Secrets ───────────────────────────────────────────────────────
            var sec = new StringBuilder("[");
            if (r.containsKey("secretArn")) {
                sec.append(sec("SPRING_DATASOURCE_USERNAME", r.get("secretArn") + ":username::"))
                   .append(",")
                   .append(sec("SPRING_DATASOURCE_PASSWORD", r.get("secretArn") + ":password::"));
            }
            sec.append("]");

            var logCfg = logCfg(logGroupName, region, "ecs");
            var adotLogCfg = logCfg(logGroupName, region, "adot");

            return "[" +
                "{\"name\":\"" + svcName + "\"," +
                "\"image\":\"" + r.get("image") + "\"," +
                "\"essential\":true," +
                "\"portMappings\":[{\"containerPort\":" + port + ",\"protocol\":\"tcp\"}]," +
                "\"environment\":" + env + "," +
                "\"secrets\":" + sec + "," +
                "\"logConfiguration\":" + logCfg +
                "}," +
                "{\"name\":\"adot-collector\"," +
                "\"image\":\"public.ecr.aws/aws-observability/aws-otel-collector:v0.43.1\"," +
                "\"essential\":false," +
                "\"environment\":[{\"name\":\"AOT_CONFIG_CONTENT\",\"value\":\"" + ADOT_CONFIG + "\"}]," +
                "\"logConfiguration\":" + adotLogCfg +
                "}]";
        });
    }

    private static String kv(String k, String v) {
        return "{\"name\":\"" + k + "\",\"value\":\"" + v + "\"}";
    }

    private static String sec(String k, String valueFrom) {
        return "{\"name\":\"" + k + "\",\"valueFrom\":\"" + valueFrom + "\"}";
    }

    private static String logCfg(String logGroup, String region, String prefix) {
        return "{\"logDriver\":\"awslogs\",\"options\":{" +
               "\"awslogs-group\":\"" + logGroup + "\"," +
               "\"awslogs-region\":\"" + region + "\"," +
               "\"awslogs-stream-prefix\":\"" + prefix + "\"}}";
    }

    public Output<String> taskSgId()             { return taskSgId; }
    public Output<String> ecsServiceName()       { return ecsServiceName; }
    public Output<String> targetGroupArnSuffix() { return targetGroupArnSuffix; }
}
