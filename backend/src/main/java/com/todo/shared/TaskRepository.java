package com.todo.shared;

import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.DeleteItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.ReturnValue;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;

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

    /**
     * Marks a task Expired, but only if it is still Pending with the deadline the expiry was
     * scheduled for. A stale schedule (task completed, deleted or re-scheduled) is a no-op.
     *
     * @return the expired task, or empty if the condition did not hold
     */
    public Optional<Task> expire(String userId, String taskId, String deadline, long deadlineEpoch, String now) {
        try {
            Map<String, AttributeValue> item = ddb.updateItem(UpdateItemRequest.builder()
                    .tableName(tableName)
                    .key(TaskItems.key(userId, taskId))
                    .updateExpression("SET #status = :expired, GSI1SK = :gsi1sk, UpdatedAt = :now, ExpiredAt = :now")
                    .conditionExpression("#status = :pending AND DeadlineEpoch = :deadline")
                    .expressionAttributeNames(Map.of("#status", "Status"))
                    .expressionAttributeValues(Map.of(
                            ":expired", AttributeValue.fromS(TaskStatus.Expired.name()),
                            ":pending", AttributeValue.fromS(TaskStatus.Pending.name()),
                            ":gsi1sk", AttributeValue.fromS(TaskItems.statusPrefix(TaskStatus.Expired) + deadline),
                            ":deadline", AttributeValue.fromN(Long.toString(deadlineEpoch)),
                            ":now", AttributeValue.fromS(now)))
                    .returnValues(ReturnValue.ALL_NEW)
                    .build()).attributes();
            return Optional.of(TaskItems.fromItem(item));
        } catch (ConditionalCheckFailedException e) {
            return Optional.empty();
        }
    }

    /**
     * Finds a task that this expiry already marked Expired but whose owner was not yet
     * notified (a previous attempt failed after the update), so a retry can still notify.
     */
    public Optional<Task> findExpiredAwaitingNotification(String userId, String taskId, long deadlineEpoch) {
        Map<String, AttributeValue> item = ddb.getItem(GetItemRequest.builder()
                .tableName(tableName)
                .key(TaskItems.key(userId, taskId))
                .consistentRead(true)
                .build()).item();
        if (item == null || item.isEmpty() || item.containsKey("NotifiedAt")) {
            return Optional.empty();
        }
        Task task = TaskItems.fromItem(item);
        return task.status() == TaskStatus.Expired && task.deadlineEpoch() == deadlineEpoch
                ? Optional.of(task)
                : Optional.empty();
    }

    /** Records that the expiry notification was sent; a repeat call is a no-op. */
    public void markNotified(String userId, String taskId, String now) {
        try {
            ddb.updateItem(UpdateItemRequest.builder()
                    .tableName(tableName)
                    .key(TaskItems.key(userId, taskId))
                    .updateExpression("SET NotifiedAt = :now")
                    .conditionExpression("attribute_exists(PK) AND attribute_not_exists(NotifiedAt)")
                    .expressionAttributeValues(Map.of(":now", AttributeValue.fromS(now)))
                    .build());
        } catch (ConditionalCheckFailedException e) {
            // Already marked, or the task was deleted in the meantime.
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
