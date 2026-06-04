package com.portfolio.infra.components;

import com.pulumi.aws.AwsFunctions;
import com.pulumi.aws.s3.BucketV2;
import com.pulumi.aws.s3.BucketV2Args;
import com.pulumi.aws.s3.BucketVersioningV2;
import com.pulumi.aws.s3.BucketVersioningV2Args;
import com.pulumi.aws.s3.BucketServerSideEncryptionConfigurationV2;
import com.pulumi.aws.s3.BucketServerSideEncryptionConfigurationV2Args;
import com.pulumi.aws.s3.BucketPublicAccessBlock;
import com.pulumi.aws.s3.BucketPublicAccessBlockArgs;
import com.pulumi.aws.s3.BucketLifecycleConfigurationV2;
import com.pulumi.aws.s3.BucketLifecycleConfigurationV2Args;
import com.pulumi.aws.s3.inputs.BucketVersioningV2VersioningConfigurationArgs;
import com.pulumi.aws.s3.inputs.BucketServerSideEncryptionConfigurationV2RuleArgs;
import com.pulumi.aws.s3.inputs.BucketServerSideEncryptionConfigurationV2RuleApplyServerSideEncryptionByDefaultArgs;
import com.pulumi.aws.s3.inputs.BucketLifecycleConfigurationV2RuleArgs;
import com.pulumi.aws.s3.inputs.BucketLifecycleConfigurationV2RuleAbortIncompleteMultipartUploadArgs;
import com.pulumi.aws.s3.inputs.BucketLifecycleConfigurationV2RuleFilterArgs;
import com.pulumi.core.Output;
import com.pulumi.resources.ComponentResource;
import com.pulumi.resources.ComponentResourceOptions;
import com.pulumi.resources.CustomResourceOptions;

import java.util.List;
import java.util.Map;

/**
 * Mirrors infra/modules/s3-bucket: private files bucket with versioning,
 * SSE-S3, public-access block, 7-day multipart lifecycle rule.
 */
public class S3Component extends ComponentResource {

    private final Output<String> bucketName;
    private final Output<String> bucketArn;

    public S3Component(String org, String env) {
        super("portfolio:infra:S3", org + "-" + env + "-s3",
              ComponentResourceOptions.builder().build());

        var prefix   = org + "-" + env;
        var opts     = CustomResourceOptions.builder().parent(this).build();
        var identity = AwsFunctions.getCallerIdentity();
        var name     = identity.applyValue(id -> prefix + "-files-" + id.accountId());

        // ── Bucket ────────────────────────────────────────────────────────────
        var bucket = new BucketV2(prefix + "-files", BucketV2Args.builder()
                .bucket(name)
                .tags(Map.of("Name", prefix + "-files"))
                .build(), opts);

        // ── Versioning ────────────────────────────────────────────────────────
        new BucketVersioningV2(prefix + "-files-versioning",
                BucketVersioningV2Args.builder()
                        .bucket(bucket.id())
                        .versioningConfiguration(
                                BucketVersioningV2VersioningConfigurationArgs.builder()
                                        .status("Enabled")
                                        .build())
                        .build(), opts);

        // ── SSE-S3 ────────────────────────────────────────────────────────────
        new BucketServerSideEncryptionConfigurationV2(prefix + "-files-sse",
                BucketServerSideEncryptionConfigurationV2Args.builder()
                        .bucket(bucket.id())
                        .rules(List.of(
                                BucketServerSideEncryptionConfigurationV2RuleArgs.builder()
                                        .applyServerSideEncryptionByDefault(
                                                BucketServerSideEncryptionConfigurationV2RuleApplyServerSideEncryptionByDefaultArgs.builder()
                                                        .sseAlgorithm("AES256")
                                                        .build())
                                        .build()
                        ))
                        .build(), opts);

        // ── Public access block ───────────────────────────────────────────────
        new BucketPublicAccessBlock(prefix + "-files-pab",
                BucketPublicAccessBlockArgs.builder()
                        .bucket(bucket.id())
                        .blockPublicAcls(true)
                        .blockPublicPolicy(true)
                        .ignorePublicAcls(true)
                        .restrictPublicBuckets(true)
                        .build(), opts);

        // ── Lifecycle — abort incomplete multipart after 7 days ───────────────
        new BucketLifecycleConfigurationV2(prefix + "-files-lifecycle",
                BucketLifecycleConfigurationV2Args.builder()
                        .bucket(bucket.id())
                        .rules(List.of(
                                BucketLifecycleConfigurationV2RuleArgs.builder()
                                        .id("abort-incomplete-multipart")
                                        .status("Enabled")
                                        .filter(BucketLifecycleConfigurationV2RuleFilterArgs.builder()
                                                .prefix("")
                                                .build())
                                        .abortIncompleteMultipartUpload(
                                                BucketLifecycleConfigurationV2RuleAbortIncompleteMultipartUploadArgs.builder()
                                                        .daysAfterInitiation(7)
                                                        .build())
                                        .build()
                        ))
                        .build(), opts);

        // ── Outputs ───────────────────────────────────────────────────────────
        this.bucketName = bucket.bucket();
        this.bucketArn  = bucket.arn();

        this.registerOutputs(Map.of(
                "bucketName", this.bucketName,
                "bucketArn",  this.bucketArn
        ));
    }

    public Output<String> bucketName() { return bucketName; }
    public Output<String> bucketArn()  { return bucketArn; }
}
