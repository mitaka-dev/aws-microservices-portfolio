package com.portfolio.infra.components;

import com.pulumi.aws.dynamodb.Table;
import com.pulumi.aws.dynamodb.TableArgs;
import com.pulumi.aws.dynamodb.inputs.TableAttributeArgs;
import com.pulumi.aws.dynamodb.inputs.TableGlobalSecondaryIndexArgs;
import com.pulumi.aws.dynamodb.inputs.TablePointInTimeRecoveryArgs;
import com.pulumi.aws.dynamodb.inputs.TableServerSideEncryptionArgs;
import com.pulumi.core.Output;
import com.pulumi.resources.ComponentResource;
import com.pulumi.resources.ComponentResourceOptions;
import com.pulumi.resources.CustomResourceOptions;

import java.util.List;
import java.util.Map;

/**
 * Mirrors infra/modules/dynamodb: catalog table (pk/sk), GSI1 (gsi1pk/gsi1sk),
 * SSE enabled, on-demand billing.
 */
public class DynamoDbComponent extends ComponentResource {

    private final Output<String> tableArn;
    private final Output<String> tableName;

    public DynamoDbComponent(String org, String env) {
        super("portfolio:infra:DynamoDb", org + "-" + env + "-dynamodb",
              ComponentResourceOptions.builder().build());

        var prefix = org + "-" + env;
        var opts   = CustomResourceOptions.builder().parent(this).build();

        var table = new Table(prefix + "-catalog", TableArgs.builder()
                .name(prefix + "-catalog")
                .billingMode("PAY_PER_REQUEST")
                .hashKey("pk")
                .rangeKey("sk")
                .attributes(List.of(
                        TableAttributeArgs.builder().name("pk").type("S").build(),
                        TableAttributeArgs.builder().name("sk").type("S").build(),
                        TableAttributeArgs.builder().name("gsi1pk").type("S").build(),
                        TableAttributeArgs.builder().name("gsi1sk").type("S").build()
                ))
                .globalSecondaryIndexes(List.of(
                        TableGlobalSecondaryIndexArgs.builder()
                                .name("GSI1")
                                .hashKey("gsi1pk")
                                .rangeKey("gsi1sk")
                                .projectionType("ALL")
                                .build()
                ))
                .serverSideEncryption(TableServerSideEncryptionArgs.builder()
                        .enabled(true)
                        .build())
                .pointInTimeRecovery(TablePointInTimeRecoveryArgs.builder()
                        .enabled(false)
                        .build())
                .tags(Map.of("Name", prefix + "-catalog"))
                .build(), opts);

        this.tableArn  = table.arn();
        this.tableName = table.name();

        this.registerOutputs(Map.of(
                "tableArn",  this.tableArn,
                "tableName", this.tableName
        ));
    }

    public Output<String> tableArn()  { return tableArn; }
    public Output<String> tableName() { return tableName; }
}
