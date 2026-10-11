package com.todo.expiry;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.todo.shared.Task;
import com.todo.shared.TaskRepository;
import com.todo.shared.TaskStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.PublishRequest;
import software.amazon.awssdk.services.sns.model.PublishResponse;
import software.amazon.awssdk.services.sns.model.SnsException;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ExpiryHandlerTest {

    private static final Instant NOW = Instant.parse("2026-10-11T10:05:00Z");
    private static final Map<String, Object> INPUT = Map.of(
            "taskId", "t1", "userId", "u1", "deadline", "2026-10-11T10:05:00Z", "deadlineEpoch", 1791713100);

    private TaskRepository repository;
    private SnsClient sns;
    private ExpiryHandler handler;
    private Context context;

    @BeforeEach
    void setUp() {
        repository = mock(TaskRepository.class);
        sns = mock(SnsClient.class);
        when(sns.publish(any(PublishRequest.class))).thenReturn(PublishResponse.builder().messageId("m").build());
        handler = new ExpiryHandler(repository, sns, "arn:topic", Clock.fixed(NOW, ZoneOffset.UTC));
        context = mock(Context.class);
        when(context.getLogger()).thenReturn(mock(LambdaLogger.class));
    }

    @Test
    void expiresPendingTaskAndNotifiesOwnerOnly() {
        when(repository.expire("u1", "t1", "2026-10-11T10:05:00Z", 1791713100L, NOW.toString()))
                .thenReturn(Optional.of(expired()));

        assertEquals("EXPIRED", handler.handleRequest(INPUT, context));

        ArgumentCaptor<PublishRequest> captor = ArgumentCaptor.forClass(PublishRequest.class);
        verify(sns).publish(captor.capture());
        assertEquals("owner@example.com", captor.getValue().messageAttributes().get("email").stringValue());
        assertTrue(captor.getValue().message().contains("Buy milk"));
        verify(repository).markNotified("u1", "t1", NOW.toString());
    }

    @Test
    void staleScheduleIsANoOp() {
        when(repository.expire(any(), any(), any(), anyLong(), any())).thenReturn(Optional.empty());
        when(repository.findExpiredAwaitingNotification("u1", "t1", 1791713100L)).thenReturn(Optional.empty());

        assertEquals("SKIPPED", handler.handleRequest(INPUT, context));
        verifyNoInteractions(sns);
        verify(repository, never()).markNotified(any(), any(), any());
    }

    @Test
    void publishFailureFailsInvocationAndRetryStillNotifies() {
        when(repository.expire(any(), any(), any(), anyLong(), any()))
                .thenReturn(Optional.of(expired()))
                .thenReturn(Optional.empty());
        when(sns.publish(any(PublishRequest.class)))
                .thenThrow(SnsException.builder().message("unavailable").build())
                .thenReturn(PublishResponse.builder().messageId("m").build());
        when(repository.findExpiredAwaitingNotification("u1", "t1", 1791713100L)).thenReturn(Optional.of(expired()));

        assertThrows(SnsException.class, () -> handler.handleRequest(INPUT, context));
        verify(repository, never()).markNotified(any(), any(), any());

        assertEquals("EXPIRED", handler.handleRequest(INPUT, context));
        verify(sns, times(2)).publish(any(PublishRequest.class));
        verify(repository).markNotified("u1", "t1", NOW.toString());
    }

    private static Task expired() {
        return new Task("t1", "u1", "owner@example.com", "Buy milk", "2026-10-11", TaskStatus.Expired,
                "2026-10-11T10:05:00Z", 1791713100L, "c", "u");
    }
}
