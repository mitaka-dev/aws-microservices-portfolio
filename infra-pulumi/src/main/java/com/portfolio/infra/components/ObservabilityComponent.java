package com.portfolio.infra.components;

import com.pulumi.aws.cloudwatch.Dashboard;
import com.pulumi.aws.cloudwatch.DashboardArgs;
import com.pulumi.aws.cloudwatch.MetricAlarm;
import com.pulumi.aws.cloudwatch.MetricAlarmArgs;
import com.pulumi.aws.sns.Topic;
import com.pulumi.aws.sns.TopicArgs;
import com.pulumi.aws.sns.TopicSubscription;
import com.pulumi.aws.sns.TopicSubscriptionArgs;
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
 * Mirrors infra/modules/observability: SNS alarm topic + email subscription,
 * CloudWatch dashboard (10 widgets), and 9 alarms (4× 5xx, 4× ECS running,
 * SQS age, DLQ depth, RDS CPU).
 */
public class ObservabilityComponent extends ComponentResource {

    /** Per-service data needed for dashboard widgets and alarms. */
    public static final class ServiceInfo {
        public final String name;
        public final Output<String> ecsServiceName;
        public final Output<String> targetGroupArnSuffix;

        public ServiceInfo(String name, Output<String> ecsServiceName,
                           Output<String> targetGroupArnSuffix) {
            this.name = name;
            this.ecsServiceName = ecsServiceName;
            this.targetGroupArnSuffix = targetGroupArnSuffix;
        }
    }

    private final Output<String> alarmTopicArn;

    public ObservabilityComponent(String org, String env, String alarmEmail,
            Output<String> awsRegion, Output<String> clusterName,
            Output<String> rdsIdentifier, Output<String> sqsQueueName, Output<String> dlqName,
            Output<String> dynamoTableName, Output<String> albArnSuffix,
            List<ServiceInfo> services) {
        super("portfolio:infra:Observability", org + "-" + env + "-observability",
              ComponentResourceOptions.builder().build());

        var prefix = org + "-" + env;
        var opts   = CustomResourceOptions.builder().parent(this).build();

        // ── Alarm SNS topic + email subscription ─────────────────────────────
        var alarmTopic = new Topic(prefix + "-alarms", TopicArgs.builder()
                .name(prefix + "-alarms")
                .tags(Map.of("Name", prefix + "-alarms"))
                .build(), opts);

        new TopicSubscription(prefix + "-alarms-email", TopicSubscriptionArgs.builder()
                .topic(alarmTopic.arn())
                .protocol("email")
                .endpoint(alarmEmail)
                .build(), opts);

        // ── CloudWatch Dashboard ──────────────────────────────────────────────
        var named = new LinkedHashMap<String, Output<String>>();
        named.put("region",    awsRegion);
        named.put("albSuffix", albArnSuffix);
        named.put("cluster",   clusterName);
        named.put("rds",       rdsIdentifier);
        named.put("sqs",       sqsQueueName);
        named.put("dynamo",    dynamoTableName);
        for (int i = 0; i < services.size(); i++) {
            named.put("tg"  + i, services.get(i).targetGroupArnSuffix);
            named.put("svc" + i, services.get(i).ecsServiceName);
        }
        var keys     = new ArrayList<>(named.keySet());
        var values   = keys.stream().map(named::get).collect(Collectors.toList());
        var svcNames = services.stream().map(s -> s.name).toArray(String[]::new);

        var dashboardBody = Output.all(values).applyValue(vals -> {
            var r = new HashMap<String, String>();
            for (int i = 0; i < keys.size(); i++) r.put(keys.get(i), vals.get(i));
            return buildDashboardBody(r, svcNames);
        });

        new Dashboard(prefix + "-portfolio", DashboardArgs.builder()
                .dashboardName(prefix + "-portfolio")
                .dashboardBody(dashboardBody)
                .build(), opts);

        // ── Alarm actions ─────────────────────────────────────────────────────
        var alarmActions = alarmTopic.arn().applyValue(List::of);

        // ── 5xx alarms per service ────────────────────────────────────────────
        for (var svc : services) {
            var dims = Output.all(List.of(albArnSuffix, svc.targetGroupArnSuffix))
                    .applyValue(vals -> Map.of(
                            "LoadBalancer", vals.get(0),
                            "TargetGroup",  vals.get(1)));
            new MetricAlarm(prefix + "-" + svc.name + "-5xx",
                    MetricAlarmArgs.builder()
                            .name(prefix + "-" + svc.name + "-5xx")
                            .comparisonOperator("GreaterThanThreshold")
                            .evaluationPeriods(2)
                            .metricName("HTTPCode_Target_5XX_Count")
                            .namespace("AWS/ApplicationELB")
                            .period(60)
                            .statistic("Sum")
                            .threshold(10.0)
                            .alarmDescription("5xx errors > 10/min for " + svc.name)
                            .alarmActions(alarmActions)
                            .okActions(alarmActions)
                            .treatMissingData("notBreaching")
                            .dimensions(dims)
                            .tags(Map.of("Name", prefix + "-" + svc.name + "-5xx"))
                            .build(), opts);
        }

        // ── ECS running task count alarms per service ─────────────────────────
        for (var svc : services) {
            var dims = Output.all(List.of(clusterName, svc.ecsServiceName))
                    .applyValue(vals -> Map.of(
                            "ClusterName", vals.get(0),
                            "ServiceName", vals.get(1)));
            new MetricAlarm(prefix + "-" + svc.name + "-running",
                    MetricAlarmArgs.builder()
                            .name(prefix + "-" + svc.name + "-running")
                            .comparisonOperator("LessThanThreshold")
                            .evaluationPeriods(2)
                            .metricName("RunningTaskCount")
                            .namespace("ECS/ContainerInsights")
                            .period(60)
                            .statistic("Average")
                            .threshold(1.0)
                            .alarmDescription("ECS " + svc.name + " has no running tasks")
                            .alarmActions(alarmActions)
                            .okActions(alarmActions)
                            .treatMissingData("breaching")
                            .dimensions(dims)
                            .tags(Map.of("Name", prefix + "-" + svc.name + "-running"))
                            .build(), opts);
        }

        // ── SQS oldest message age alarm ──────────────────────────────────────
        var sqsDims = sqsQueueName.applyValue(n -> Map.of("QueueName", n));
        new MetricAlarm(prefix + "-sqs-message-age",
                MetricAlarmArgs.builder()
                        .name(prefix + "-sqs-message-age")
                        .comparisonOperator("GreaterThanThreshold")
                        .evaluationPeriods(1)
                        .metricName("ApproximateAgeOfOldestMessage")
                        .namespace("AWS/SQS")
                        .period(60)
                        .statistic("Maximum")
                        .threshold(60.0)
                        .alarmDescription("SQS oldest message age > 60s — consumer may be stuck")
                        .alarmActions(alarmActions)
                        .okActions(alarmActions)
                        .treatMissingData("notBreaching")
                        .dimensions(sqsDims)
                        .tags(Map.of("Name", prefix + "-sqs-message-age"))
                        .build(), opts);

        // ── DLQ depth alarm ───────────────────────────────────────────────────
        var dlqDims = dlqName.applyValue(n -> Map.of("QueueName", n));
        new MetricAlarm(prefix + "-dlq-depth",
                MetricAlarmArgs.builder()
                        .name(prefix + "-dlq-depth")
                        .comparisonOperator("GreaterThanOrEqualToThreshold")
                        .evaluationPeriods(1)
                        .metricName("ApproximateNumberOfMessagesVisible")
                        .namespace("AWS/SQS")
                        .period(60)
                        .statistic("Maximum")
                        .threshold(1.0)
                        .alarmDescription("DLQ has messages — processing failures detected")
                        .alarmActions(alarmActions)
                        .okActions(alarmActions)
                        .treatMissingData("notBreaching")
                        .dimensions(dlqDims)
                        .tags(Map.of("Name", prefix + "-dlq-depth"))
                        .build(), opts);

        // ── RDS CPU alarm ─────────────────────────────────────────────────────
        var rdsDims = rdsIdentifier.applyValue(id -> Map.of("DBInstanceIdentifier", id));
        new MetricAlarm(prefix + "-rds-cpu",
                MetricAlarmArgs.builder()
                        .name(prefix + "-rds-cpu")
                        .comparisonOperator("GreaterThanThreshold")
                        .evaluationPeriods(2)
                        .metricName("CPUUtilization")
                        .namespace("AWS/RDS")
                        .period(60)
                        .statistic("Average")
                        .threshold(80.0)
                        .alarmDescription("RDS CPU utilization > 80%")
                        .alarmActions(alarmActions)
                        .okActions(alarmActions)
                        .treatMissingData("notBreaching")
                        .dimensions(rdsDims)
                        .tags(Map.of("Name", prefix + "-rds-cpu"))
                        .build(), opts);

        this.alarmTopicArn = alarmTopic.arn();
        this.registerOutputs(Map.of("alarmTopicArn", this.alarmTopicArn));
    }

    public Output<String> alarmTopicArn() { return alarmTopicArn; }

    private static String buildDashboardBody(Map<String, String> r, String[] svcNames) {
        var region    = r.get("region");
        var albSuffix = r.get("albSuffix");
        var cluster   = r.get("cluster");
        var rds       = r.get("rds");
        var sqs       = r.get("sqs");
        var dynamo    = r.get("dynamo");

        var albReq = new StringBuilder();
        var albLat = new StringBuilder();
        var ecsCpu = new StringBuilder();
        var ecsMem = new StringBuilder();
        for (int i = 0; i < svcNames.length; i++) {
            var tg   = r.get("tg" + i);
            var svc  = r.get("svc" + i);
            var name = svcNames[i];
            if (i > 0) { albReq.append(","); albLat.append(","); ecsCpu.append(","); ecsMem.append(","); }
            albReq.append("[\"AWS/ApplicationELB\",\"RequestCount\",\"LoadBalancer\",\"")
                    .append(albSuffix).append("\",\"TargetGroup\",\"").append(tg)
                    .append("\",{\"label\":\"").append(name).append("\"}]");
            albLat.append("[\"AWS/ApplicationELB\",\"TargetResponseTime\",\"LoadBalancer\",\"")
                    .append(albSuffix).append("\",\"TargetGroup\",\"").append(tg)
                    .append("\",{\"label\":\"").append(name).append("\"}]");
            ecsCpu.append("[\"ECS/ContainerInsights\",\"CpuUtilized\",\"ClusterName\",\"")
                    .append(cluster).append("\",\"ServiceName\",\"").append(svc)
                    .append("\",{\"label\":\"").append(name).append("\"}]");
            ecsMem.append("[\"ECS/ContainerInsights\",\"MemoryUtilized\",\"ClusterName\",\"")
                    .append(cluster).append("\",\"ServiceName\",\"").append(svc)
                    .append("\",{\"label\":\"").append(name).append("\"}]");
        }

        return "{\"widgets\":["
                + w(0, 0, 24, 6, "ALB Requests per Minute",       "Sum",     region, "[" + albReq + "]") + ","
                + w(0, 6, 24, 6, "ALB p99 Latency (seconds)",     "p99",     region, "[" + albLat + "]") + ","
                + w(0, 12, 12, 6, "ECS CPU Utilization (cores)",  "Average", region, "[" + ecsCpu + "]") + ","
                + w(12, 12, 12, 6, "ECS Memory Utilization (MB)", "Average", region, "[" + ecsMem + "]") + ","
                + w(0, 18, 12, 6, "RDS Connections", "Average", region,
                        "[[\"AWS/RDS\",\"DatabaseConnections\",\"DBInstanceIdentifier\",\"" + rds + "\"]]") + ","
                + w(12, 18, 12, 6, "RDS CPU (%)", "Average", region,
                        "[[\"AWS/RDS\",\"CPUUtilization\",\"DBInstanceIdentifier\",\"" + rds + "\"]]") + ","
                + w(0, 24, 12, 6, "SQS Messages Visible", "Maximum", region,
                        "[[\"AWS/SQS\",\"ApproximateNumberOfMessagesVisible\",\"QueueName\",\"" + sqs + "\"]]") + ","
                + w(12, 24, 12, 6, "SQS Oldest Message Age (seconds)", "Maximum", region,
                        "[[\"AWS/SQS\",\"ApproximateAgeOfOldestMessage\",\"QueueName\",\"" + sqs + "\"]]") + ","
                + w(0, 30, 12, 6, "DynamoDB Consumed RCU", "Sum", region,
                        "[[\"AWS/DynamoDB\",\"ConsumedReadCapacityUnits\",\"TableName\",\"" + dynamo + "\"]]") + ","
                + w(12, 30, 12, 6, "DynamoDB Consumed WCU", "Sum", region,
                        "[[\"AWS/DynamoDB\",\"ConsumedWriteCapacityUnits\",\"TableName\",\"" + dynamo + "\"]]")
                + "]}";
    }

    private static String w(int x, int y, int width, int height,
                             String title, String stat, String region, String metrics) {
        return "{\"type\":\"metric\",\"x\":" + x + ",\"y\":" + y
                + ",\"width\":" + width + ",\"height\":" + height
                + ",\"properties\":{\"title\":\"" + title + "\",\"view\":\"timeSeries\""
                + ",\"stat\":\"" + stat + "\",\"period\":60,\"region\":\"" + region + "\""
                + ",\"metrics\":" + metrics + "}}";
    }
}
