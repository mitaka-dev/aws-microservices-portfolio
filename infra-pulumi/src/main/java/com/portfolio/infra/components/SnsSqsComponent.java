package com.portfolio.infra.components;

import com.pulumi.aws.sns.Topic;
import com.pulumi.aws.sns.TopicArgs;
import com.pulumi.aws.sns.TopicSubscription;
import com.pulumi.aws.sns.TopicSubscriptionArgs;
import com.pulumi.aws.sqs.Queue;
import com.pulumi.aws.sqs.QueueArgs;
import com.pulumi.aws.sqs.QueuePolicy;
import com.pulumi.aws.sqs.QueuePolicyArgs;
import com.pulumi.core.Output;
import com.pulumi.resources.ComponentResource;
import com.pulumi.resources.ComponentResourceOptions;
import com.pulumi.resources.CustomResourceOptions;

import java.util.List;
import java.util.Map;

/**
 * Mirrors infra/modules/sns-sqs: SNS topic, SQS queue + DLQ, SNS→SQS
 * subscription, queue policy.
 */
public class SnsSqsComponent extends ComponentResource {

    private final Output<String> topicArn;
    private final Output<String> queueArn;
    private final Output<String> queueName;
    private final Output<String> dlqArn;
    private final Output<String> dlqName;
    private final Output<String> queueUrl;

    public SnsSqsComponent(String org, String env) {
        super("portfolio:infra:SnsSqs", org + "-" + env + "-sns-sqs",
              ComponentResourceOptions.builder().build());

        var prefix = org + "-" + env;
        var opts   = CustomResourceOptions.builder().parent(this).build();

        // ── DLQ (14-day retention) ────────────────────────────────────────────
        var dlq = new Queue(prefix + "-orders-processing-dlq", QueueArgs.builder()
                .name(prefix + "-orders-processing-dlq")
                .messageRetentionSeconds(1209600)
                .build(), opts);

        // ── Main queue with redrive policy ────────────────────────────────────
        var redrivePolicy = dlq.arn().applyValue(arn ->
                "{\"deadLetterTargetArn\":\"" + arn + "\",\"maxReceiveCount\":3}");

        var queue = new Queue(prefix + "-orders-processing", QueueArgs.builder()
                .name(prefix + "-orders-processing")
                .visibilityTimeoutSeconds(30)
                .messageRetentionSeconds(86400)
                .redrivePolicy(redrivePolicy)
                .build(), opts);

        // ── SNS topic ─────────────────────────────────────────────────────────
        var topic = new Topic(prefix + "-orders-events", TopicArgs.builder()
                .name(prefix + "-orders-events")
                .build(), opts);

        // ── SNS → SQS subscription ────────────────────────────────────────────
        new TopicSubscription(prefix + "-orders-sub", TopicSubscriptionArgs.builder()
                .topic(topic.arn())
                .protocol("sqs")
                .endpoint(queue.arn())
                .rawMessageDelivery(true)
                .build(), opts);

        // ── Queue policy — allow SNS to send messages ─────────────────────────
        var policy = Output.all(List.of(queue.arn(), topic.arn()))
                .applyValue(arns -> {
                    var qArn = arns.get(0);
                    var tArn = arns.get(1);
                    return "{\"Version\":\"2012-10-17\",\"Statement\":[{" +
                           "\"Effect\":\"Allow\"," +
                           "\"Principal\":{\"Service\":\"sns.amazonaws.com\"}," +
                           "\"Action\":\"sqs:SendMessage\"," +
                           "\"Resource\":\"" + qArn + "\"," +
                           "\"Condition\":{\"ArnEquals\":{\"aws:SourceArn\":\"" + tArn + "\"}}" +
                           "}]}";
                });

        new QueuePolicy(prefix + "-orders-queue-policy", QueuePolicyArgs.builder()
                .queueUrl(queue.url())
                .policy(policy)
                .build(), opts);

        // ── Outputs ───────────────────────────────────────────────────────────
        this.topicArn  = topic.arn();
        this.queueArn  = queue.arn();
        this.queueName = queue.name();
        this.dlqArn    = dlq.arn();
        this.dlqName   = dlq.name();
        this.queueUrl  = queue.url();

        this.registerOutputs(Map.of(
                "topicArn",  this.topicArn,
                "queueArn",  this.queueArn,
                "queueName", this.queueName,
                "dlqArn",    this.dlqArn,
                "dlqName",   this.dlqName,
                "queueUrl",  this.queueUrl
        ));
    }

    public Output<String> topicArn()  { return topicArn; }
    public Output<String> queueArn()  { return queueArn; }
    public Output<String> queueName() { return queueName; }
    public Output<String> dlqArn()    { return dlqArn; }
    public Output<String> dlqName()   { return dlqName; }
    public Output<String> queueUrl()  { return queueUrl; }
}
