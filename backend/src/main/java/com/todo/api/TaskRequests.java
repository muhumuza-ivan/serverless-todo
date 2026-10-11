package com.todo.api;

/** Request bodies accepted by the tasks API. All fields are optional at parse time and validated in {@link TaskService}. */
public final class TaskRequests {

    private TaskRequests() {
    }

    /** POST /tasks — {@code deadline} defaults to 5 minutes from now, {@code date} to today (UTC). */
    public record Create(String description, String date, String deadline) {
    }

    /** PUT /tasks/{taskId} — only provided fields are changed. */
    public record Update(String description, String date, String status, String deadline) {
    }
}
