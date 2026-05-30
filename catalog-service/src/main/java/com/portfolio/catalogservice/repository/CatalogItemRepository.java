package com.portfolio.catalogservice.repository;

import com.portfolio.catalogservice.model.CatalogItem;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbIndex;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryConditional;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
public class CatalogItemRepository {

    public static final String TABLE_NAME = "catalog";

    private final DynamoDbTable<CatalogItem> table;
    private final DynamoDbIndex<CatalogItem> gsi1;
    private final DynamoDbClient dynamoDbClient;
    private final String tableName;

    public CatalogItemRepository(DynamoDbEnhancedClient enhancedClient,
                                 DynamoDbClient dynamoDbClient,
                                 @Value("${dynamodb.table-name:catalog}") String tableName) {
        this.dynamoDbClient = dynamoDbClient;
        this.tableName = tableName;
        this.table = enhancedClient.table(tableName, TableSchema.fromBean(CatalogItem.class));
        this.gsi1 = table.index("GSI1");
    }

    public void save(CatalogItem item) {
        table.putItem(item);
    }

    public Optional<CatalogItem> findById(String id) {
        var key = Key.builder()
            .partitionValue("PRODUCT#" + id)
            .sortValue("PRODUCT#" + id)
            .build();
        return Optional.ofNullable(table.getItem(key));
    }

    public List<CatalogItem> findAll() {
        var condition = QueryConditional.keyEqualTo(
            Key.builder().partitionValue("PRODUCTS").build()
        );
        return gsi1.query(r -> r.queryConditional(condition))
            .stream()
            .flatMap(page -> page.items().stream())
            .toList();
    }

    public boolean decrementStock(String id, int qty) {
        try {
            dynamoDbClient.updateItem(r -> r
                .tableName(tableName)
                .key(Map.of(
                    "pk", AttributeValue.fromS("PRODUCT#" + id),
                    "sk", AttributeValue.fromS("PRODUCT#" + id)))
                .updateExpression("SET #stock = #stock - :qty")
                .conditionExpression("#stock >= :qty")
                .expressionAttributeNames(Map.of("#stock", "stock"))
                .expressionAttributeValues(Map.of(
                    ":qty", AttributeValue.fromN(String.valueOf(qty)))));
            return true;
        } catch (ConditionalCheckFailedException e) {
            return false;
        }
    }
}
