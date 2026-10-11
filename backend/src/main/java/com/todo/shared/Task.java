package com.todo.shared;

import com.fasterxml.jackson.annotation.JsonIgnore;

/**
 * A user's task. Timestamps are ISO-8601 UTC strings; {@code deadlineEpoch} mirrors
 * {@code deadline} in epoch seconds for numeric comparisons.
 */
public record Task(
        String taskId,
        String userId,
        @JsonIgnore String email,
        String description,
        String date,
        TaskStatus status,
        String deadline,
        long deadlineEpoch,
        String createdAt,
        String updatedAt) {

    public Task withDescription(String value) {
        return new Task(taskId, userId, email, value, date, status, deadline, deadlineEpoch, createdAt, updatedAt);
    }

    public Task withDate(String value) {
        return new Task(taskId, userId, email, description, value, status, deadline, deadlineEpoch, createdAt, updatedAt);
    }

    public Task withStatus(TaskStatus value) {
        return new Task(taskId, userId, email, description, date, value, deadline, deadlineEpoch, createdAt, updatedAt);
    }

    public Task withDeadline(String value, long epoch) {
        return new Task(taskId, userId, email, description, date, status, value, epoch, createdAt, updatedAt);
    }

    public Task withUpdatedAt(String value) {
        return new Task(taskId, userId, email, description, date, status, deadline, deadlineEpoch, createdAt, value);
    }
}
