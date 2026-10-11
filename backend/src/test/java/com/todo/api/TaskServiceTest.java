package com.todo.api;

import com.todo.shared.Task;
import com.todo.shared.TaskRepository;
import com.todo.shared.TaskStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class TaskServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-11T10:00:00Z");
    private static final String USER = "user-1";
    private static final String TASK_ID = "6f1c1c5e-6a3b-4d8e-9a43-2f7d5a0b1c2d";

    private TaskRepository repository;
    private TaskService service;

    @BeforeEach
    void setUp() {
        repository = mock(TaskRepository.class);
        service = new TaskService(repository, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void createDefaultsDeadlineToFiveMinutesAndDateToToday() {
        Task task = service.create(USER, "u@example.com", new TaskRequests.Create("  Buy milk ", null, null));

        assertEquals("Buy milk", task.description());
        assertEquals(TaskStatus.Pending, task.status());
        assertEquals("2026-10-11T10:05:00Z", task.deadline());
        assertEquals(NOW.plusSeconds(300).getEpochSecond(), task.deadlineEpoch());
        assertEquals("2026-10-11", task.date());
        assertEquals("u@example.com", task.email());
        verify(repository).create(task);
    }

    @Test
    void createRejectsMissingDescriptionAndPastDeadline() {
        assertEquals(400, assertThrows(ApiException.class,
                () -> service.create(USER, null, new TaskRequests.Create(" ", null, null))).statusCode());
        assertEquals(400, assertThrows(ApiException.class,
                () -> service.create(USER, null, new TaskRequests.Create("x", null, "2026-10-11T10:00:30Z"))).statusCode());
        verify(repository, never()).create(any());
    }

    @Test
    void completingAPendingTaskUsesOptimisticStatusCheck() {
        when(repository.get(USER, TASK_ID)).thenReturn(Optional.of(task(TaskStatus.Pending, NOW.plusSeconds(120))));
        when(repository.replaceIfStatus(any(), eq(TaskStatus.Pending))).thenReturn(true);

        Task updated = service.update(USER, TASK_ID, new TaskRequests.Update(null, null, "completed", null));

        assertEquals(TaskStatus.Completed, updated.status());
        assertEquals(NOW.toString(), updated.updatedAt());
    }

    @Test
    void expiredTasksAreReadOnly() {
        when(repository.get(USER, TASK_ID)).thenReturn(Optional.of(task(TaskStatus.Expired, NOW.minusSeconds(60))));

        ApiException e = assertThrows(ApiException.class,
                () -> service.update(USER, TASK_ID, new TaskRequests.Update("new", null, null, null)));
        assertEquals(409, e.statusCode());
    }

    @Test
    void clientsCannotSetExpiredStatus() {
        when(repository.get(USER, TASK_ID)).thenReturn(Optional.of(task(TaskStatus.Pending, NOW.plusSeconds(120))));

        assertEquals(400, assertThrows(ApiException.class,
                () -> service.update(USER, TASK_ID, new TaskRequests.Update(null, null, "Expired", null))).statusCode());
    }

    @Test
    void reopeningRequiresAFutureDeadline() {
        when(repository.get(USER, TASK_ID)).thenReturn(Optional.of(task(TaskStatus.Completed, NOW.minusSeconds(10))));

        assertEquals(409, assertThrows(ApiException.class,
                () -> service.update(USER, TASK_ID, new TaskRequests.Update(null, null, "Pending", null))).statusCode());

        when(repository.replaceIfStatus(any(), eq(TaskStatus.Completed))).thenReturn(true);
        Task reopened = service.update(USER, TASK_ID,
                new TaskRequests.Update(null, null, "Pending", "2026-10-11T11:00:00Z"));
        assertEquals(TaskStatus.Pending, reopened.status());
        assertEquals("2026-10-11T11:00:00Z", reopened.deadline());
    }

    @Test
    void concurrentStatusChangeIsAConflict() {
        when(repository.get(USER, TASK_ID)).thenReturn(Optional.of(task(TaskStatus.Pending, NOW.plusSeconds(120))));
        when(repository.replaceIfStatus(any(), any())).thenReturn(false);

        assertEquals(409, assertThrows(ApiException.class,
                () -> service.update(USER, TASK_ID, new TaskRequests.Update("x", null, null, null))).statusCode());
    }

    @Test
    void invalidIdsAndMissingTasksAre404() {
        assertEquals(404, assertThrows(ApiException.class, () -> service.get(USER, "not-a-uuid")).statusCode());
        when(repository.delete(USER, TASK_ID)).thenReturn(false);
        assertEquals(404, assertThrows(ApiException.class, () -> service.delete(USER, TASK_ID)).statusCode());
    }

    @Test
    void listRejectsUnknownStatus() {
        assertEquals(400, assertThrows(ApiException.class, () -> service.list(USER, "Done")).statusCode());
        service.list(USER, "pending");
        verify(repository).list(USER, TaskStatus.Pending);
    }

    private static Task task(TaskStatus status, Instant deadline) {
        return new Task(TASK_ID, USER, "u@example.com", "desc", "2026-10-11", status,
                deadline.toString(), deadline.getEpochSecond(), NOW.toString(), NOW.toString());
    }
}
