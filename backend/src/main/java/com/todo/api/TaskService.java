package com.todo.api;

import com.todo.shared.Task;
import com.todo.shared.TaskRepository;
import com.todo.shared.TaskStatus;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

/**
 * Task business rules:
 * <ul>
 *   <li>New tasks start Pending with a deadline (default now + 5 minutes).</li>
 *   <li>Only the system sets Expired; expired tasks are read-only and can only be deleted.</li>
 *   <li>A deadline can only be set on a Pending task and must be at least one minute ahead,
 *       which leaves the scheduling pipeline time to register the expiry event.</li>
 *   <li>A Completed task can be reopened only while its deadline is still ahead.</li>
 * </ul>
 */
public class TaskService {

    static final Duration DEFAULT_DEADLINE = Duration.ofMinutes(5);
    static final Duration MIN_LEAD_TIME = Duration.ofMinutes(1);
    static final Duration MAX_LEAD_TIME = Duration.ofDays(365);
    static final int MAX_DESCRIPTION_LENGTH = 500;

    private final TaskRepository repository;
    private final Clock clock;

    public TaskService(TaskRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    public List<Task> list(String userId, String statusFilter) {
        TaskStatus status = null;
        if (statusFilter != null && !statusFilter.isBlank()) {
            status = TaskStatus.parse(statusFilter);
            if (status == null) {
                throw ApiException.badRequest("status must be one of Pending, Completed, Expired");
            }
        }
        return repository.list(userId, status);
    }

    public Task get(String userId, String taskId) {
        return repository.get(userId, validTaskId(taskId)).orElseThrow(ApiException::notFound);
    }

    public Task create(String userId, String email, TaskRequests.Create request) {
        if (request == null) {
            throw ApiException.badRequest("Request body is required");
        }
        Instant now = now();
        Instant deadline = request.deadline() == null
                ? now.plus(DEFAULT_DEADLINE)
                : validDeadline(request.deadline(), now);
        String date = request.date() == null
                ? LocalDate.ofInstant(now, ZoneOffset.UTC).toString()
                : validDate(request.date());

        Task task = new Task(
                UUID.randomUUID().toString(),
                userId,
                email,
                validDescription(request.description()),
                date,
                TaskStatus.Pending,
                deadline.toString(),
                deadline.getEpochSecond(),
                now.toString(),
                now.toString());
        repository.create(task);
        return task;
    }

    public Task update(String userId, String taskId, TaskRequests.Update request) {
        if (request == null || (request.description() == null && request.date() == null
                && request.status() == null && request.deadline() == null)) {
            throw ApiException.badRequest("Provide at least one of description, date, status, deadline");
        }
        Task current = get(userId, taskId);
        if (current.status() == TaskStatus.Expired) {
            throw ApiException.conflict("Expired tasks cannot be modified");
        }

        Instant now = now();
        Task updated = current;
        if (request.description() != null) {
            updated = updated.withDescription(validDescription(request.description()));
        }
        if (request.date() != null) {
            updated = updated.withDate(validDate(request.date()));
        }
        if (request.status() != null) {
            TaskStatus status = TaskStatus.parse(request.status());
            if (status == null || status == TaskStatus.Expired) {
                throw ApiException.badRequest("status must be Pending or Completed");
            }
            updated = updated.withStatus(status);
        }
        if (request.deadline() != null) {
            if (updated.status() != TaskStatus.Pending) {
                throw ApiException.conflict("Deadline can only be changed on a Pending task");
            }
            Instant deadline = validDeadline(request.deadline(), now);
            updated = updated.withDeadline(deadline.toString(), deadline.getEpochSecond());
        }
        if (current.status() == TaskStatus.Completed && updated.status() == TaskStatus.Pending
                && Instant.ofEpochSecond(updated.deadlineEpoch()).isBefore(now.plus(MIN_LEAD_TIME))) {
            throw ApiException.conflict("Deadline has passed; set a new deadline to reopen this task");
        }

        updated = updated.withUpdatedAt(now.toString());
        if (!repository.replaceIfStatus(updated, current.status())) {
            throw ApiException.conflict("Task was modified concurrently (it may have just expired); reload and retry");
        }
        return updated;
    }

    public void delete(String userId, String taskId) {
        if (!repository.delete(userId, validTaskId(taskId))) {
            throw ApiException.notFound();
        }
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.SECONDS);
    }

    private static String validTaskId(String taskId) {
        try {
            return UUID.fromString(taskId).toString();
        } catch (IllegalArgumentException | NullPointerException e) {
            throw ApiException.notFound();
        }
    }

    private static String validDescription(String description) {
        if (description == null || description.isBlank()) {
            throw ApiException.badRequest("description is required");
        }
        String trimmed = description.trim();
        if (trimmed.length() > MAX_DESCRIPTION_LENGTH) {
            throw ApiException.badRequest("description must be at most " + MAX_DESCRIPTION_LENGTH + " characters");
        }
        return trimmed;
    }

    private static String validDate(String date) {
        try {
            return LocalDate.parse(date.trim()).toString();
        } catch (DateTimeParseException e) {
            throw ApiException.badRequest("date must be an ISO date (YYYY-MM-DD)");
        }
    }

    private static Instant validDeadline(String deadline, Instant now) {
        Instant parsed;
        try {
            parsed = OffsetDateTime.parse(deadline.trim()).toInstant().truncatedTo(ChronoUnit.SECONDS);
        } catch (DateTimeParseException e) {
            throw ApiException.badRequest("deadline must be an ISO-8601 timestamp with offset, e.g. 2026-01-01T12:00:00Z");
        }
        if (parsed.isBefore(now.plus(MIN_LEAD_TIME))) {
            throw ApiException.badRequest("deadline must be at least 1 minute in the future");
        }
        if (parsed.isAfter(now.plus(MAX_LEAD_TIME))) {
            throw ApiException.badRequest("deadline must be within one year");
        }
        return parsed;
    }
}
