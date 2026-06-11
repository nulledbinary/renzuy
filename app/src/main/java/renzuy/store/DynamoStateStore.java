package renzuy.store;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.DeleteItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;

/**
 * DynamoDB-backed {@link StateStore}. One table, composite key
 * ({@code pk} HASH, {@code sk} RANGE), every attribute stored as a string.
 *
 * <p>The ECS task role carries a policy scoped to exactly this table
 * (GetItem / PutItem / UpdateItem / DeleteItem / Query); credentials and
 * region come from the SDK default chains, which on Fargate resolve to the
 * task role and the {@code AWS_REGION} env var set in the task definition.
 *
 * <p>Calls are synchronous — items are a few hundred bytes and the table is
 * in-region, so latency is single-digit milliseconds, in line with the file
 * I/O the previous stores performed on the same threads.
 */
public final class DynamoStateStore implements StateStore {

    private final DynamoDbClient client;
    private final String table;

    public DynamoStateStore(DynamoDbClient client, String table) {
        this.client = client;
        this.table = table;
    }

    @Override
    public Optional<Map<String, String>> get(String pk, String sk) {
        GetItemRequest request = GetItemRequest.builder()
                .tableName(table)
                .key(keyOf(pk, sk))
                .build();
        Map<String, AttributeValue> item = client.getItem(request).item();
        if (item == null || item.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(attributesOf(item));
    }

    @Override
    public Map<String, Map<String, String>> query(String pk) {
        Map<String, Map<String, String>> out = new LinkedHashMap<>();
        Map<String, AttributeValue> startKey = null;
        do {
            QueryRequest request = QueryRequest.builder()
                    .tableName(table)
                    .keyConditionExpression("pk = :pk")
                    .expressionAttributeValues(Map.of(":pk", AttributeValue.fromS(pk)))
                    .exclusiveStartKey(startKey)
                    .build();
            QueryResponse response = client.query(request);
            for (Map<String, AttributeValue> item : response.items()) {
                AttributeValue sk = item.get("sk");
                if (sk != null && sk.s() != null) {
                    out.put(sk.s(), attributesOf(item));
                }
            }
            startKey = response.hasLastEvaluatedKey() ? response.lastEvaluatedKey() : null;
        } while (startKey != null && !startKey.isEmpty());
        return out;
    }

    @Override
    public void put(String pk, String sk, Map<String, String> attributes) {
        Map<String, AttributeValue> item = new HashMap<>(keyOf(pk, sk));
        for (Map.Entry<String, String> e : attributes.entrySet()) {
            item.put(e.getKey(), AttributeValue.fromS(e.getValue()));
        }
        client.putItem(PutItemRequest.builder().tableName(table).item(item).build());
    }

    @Override
    public void delete(String pk, String sk) {
        client.deleteItem(DeleteItemRequest.builder().tableName(table).key(keyOf(pk, sk)).build());
    }

    private static Map<String, AttributeValue> keyOf(String pk, String sk) {
        return Map.of("pk", AttributeValue.fromS(pk), "sk", AttributeValue.fromS(sk));
    }

    private static Map<String, String> attributesOf(Map<String, AttributeValue> item) {
        Map<String, String> attrs = new LinkedHashMap<>();
        for (Map.Entry<String, AttributeValue> e : item.entrySet()) {
            String name = e.getKey();
            if ("pk".equals(name) || "sk".equals(name)) continue;
            if (e.getValue().s() != null) {
                attrs.put(name, e.getValue().s());
            }
        }
        return attrs;
    }
}
