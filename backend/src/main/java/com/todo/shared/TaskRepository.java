package com.todo.shared;

import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.DeleteItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/** DynamoDB access for tasks. Every operation is scoped to the owning user's partition. */
public class TaskRepository {

    private final DynamoDbClient ddb;
    private final String tableName;

    public TaskRepository(DynamoDbClient ddb, String tableName) {
        this.ddb = ddb;
        this.tableName = tableName;
    }

    public Optional<Task> get(String userId, String taskId) {
        Map<String, AttributeValue> item = ddb.getItem(GetItemRequest.builder()
                .tableName(tableName)
                .key(TaskItems.key(userId, taskId))
                .consistentRead(true)
                .build()).item();
        return item == null || item.isEmpty() ? Optional.empty() : Optional.of(TaskItems.fromItem(item));
    }

    /** Lists all of a user's tasks, or only those with the given status (ordered by deadline). */
    public List<Task> list(String userId, TaskStatus status) {
        QueryRequest.Builder query = QueryRequest.builder().tableName(tableName);
        if (status == null) {
            query.keyConditionExpression("PK = :pk AND begins_with(SK, :sk)")
                    .expressionAttributeValues(Map.of(
                            ":pk", AttributeValue.fromS(TaskItems.userPk(userId)),
                            ":sk", AttributeValue.fromS("TASK#")));
        } else {
            query.indexName(TaskItems.GSI1)
                    .keyConditionExpression("GSI1PK = :pk AND begins_with(GSI1SK, :sk)")
                    .expressionAttributeValues(Map.of(
                            ":pk", AttributeValue.fromS(TaskItems.userPk(userId)),
                            ":sk", AttributeValue.fromS(TaskItems.statusPrefix(status))));
        }
        return ddb.queryPaginator(query.build()).items().stream()
                .map(TaskItems::fromItem)
                .toList();
    }

    /** Inserts a new task; fails if the key already exists. */
    public void create(Task task) {
        ddb.putItem(PutItemRequest.builder()
                .tableName(tableName)
                .item(TaskItems.toItem(task))
                .conditionExpression("attribute_not_exists(PK)")
                .build());
    }

    /**
     * Replaces a task only if it still has {@code expectedStatus} (optimistic concurrency,
     * e.g. against the expiry handler flipping it to Expired in the meantime).
     *
     * @return false if the task was deleted or its status changed concurrently
     */
    public boolean replaceIfStatus(Task task, TaskStatus expectedStatus) {
        try {
            ddb.putItem(PutItemRequest.builder()
                    .tableName(tableName)
                    .item(TaskItems.toItem(task))
                    .conditionExpression("attribute_exists(PK) AND #status = :expected")
                    .expressionAttributeNames(Map.of("#status", "Status"))
                    .expressionAttributeValues(Map.of(":expected", AttributeValue.fromS(expectedStatus.name())))
                    .build());
            return true;
        } catch (ConditionalCheckFailedException e) {
            return false;
        }
    }

    /** @return false if the task did not exist */
    public boolean delete(String userId, String taskId) {
        try {
            ddb.deleteItem(DeleteItemRequest.builder()
                    .tableName(tableName)
                    .key(TaskItems.key(userId, taskId))
                    .conditionExpression("attribute_exists(PK)")
                    .build());
            return true;
        } catch (ConditionalCheckFailedException e) {
            return false;
        }
    }
}
