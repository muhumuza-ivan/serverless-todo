package com.todo.shared;

/**
 * Message on the task-events FIFO queue, produced from the DynamoDB stream.
 * {@code SCHEDULE} creates or replaces the task's expiry schedule; {@code CANCEL} deletes it.
 */
public record TaskEvent(
        Action action,
        String taskId,
        String userId,
        String deadline,
        long deadlineEpoch) {

    public enum Action {
        SCHEDULE,
        CANCEL
    }

    /** Schedules are named after the task so they can be cancelled without a lookup. */
    public static String scheduleName(String taskId) {
        return "task-" + taskId;
    }
}
