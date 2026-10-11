package com.todo.shared;

import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

import java.util.HashMap;
import java.util.Map;

/**
 * Single-table key design and item mapping for tasks.
 *
 * <pre>
 * PK     = USER#&lt;userId&gt;          SK     = TASK#&lt;taskId&gt;
 * GSI1PK = USER#&lt;userId&gt;          GSI1SK = STATUS#&lt;status&gt;#&lt;deadline&gt;
 * </pre>
 *
 * GSI1 lists a user's tasks by status, ordered by deadline.
 */
public final class TaskItems {

    public static final String ENTITY_TYPE = "Task";
    public static final String GSI1 = "GSI1";

    private TaskItems() {
    }

    public static String userPk(String userId) {
        return "USER#" + userId;
    }

    public static String taskSk(String taskId) {
        return "TASK#" + taskId;
    }

    public static String statusPrefix(TaskStatus status) {
        return "STATUS#" + status.name() + "#";
    }

    public static Map<String, AttributeValue> key(String userId, String taskId) {
        return Map.of(
                "PK", s(userPk(userId)),
                "SK", s(taskSk(taskId)));
    }

    public static Map<String, AttributeValue> toItem(Task t) {
        Map<String, AttributeValue> item = new HashMap<>(key(t.userId(), t.taskId()));
        item.put("GSI1PK", s(userPk(t.userId())));
        item.put("GSI1SK", s(statusPrefix(t.status()) + t.deadline()));
        item.put("EntityType", s(ENTITY_TYPE));
        item.put("TaskId", s(t.taskId()));
        item.put("UserId", s(t.userId()));
        if (t.email() != null) {
            item.put("Email", s(t.email()));
        }
        item.put("Description", s(t.description()));
        item.put("Date", s(t.date()));
        item.put("Status", s(t.status().name()));
        item.put("Deadline", s(t.deadline()));
        item.put("DeadlineEpoch", AttributeValue.fromN(Long.toString(t.deadlineEpoch())));
        item.put("CreatedAt", s(t.createdAt()));
        item.put("UpdatedAt", s(t.updatedAt()));
        return item;
    }

    public static Task fromItem(Map<String, AttributeValue> item) {
        return new Task(
                str(item, "TaskId"),
                str(item, "UserId"),
                str(item, "Email"),
                str(item, "Description"),
                str(item, "Date"),
                TaskStatus.parse(str(item, "Status")),
                str(item, "Deadline"),
                item.containsKey("DeadlineEpoch") ? Long.parseLong(item.get("DeadlineEpoch").n()) : 0L,
                str(item, "CreatedAt"),
                str(item, "UpdatedAt"));
    }

    private static AttributeValue s(String value) {
        return AttributeValue.fromS(value);
    }

    private static String str(Map<String, AttributeValue> item, String name) {
        AttributeValue v = item.get(name);
        return v == null ? null : v.s();
    }
}
