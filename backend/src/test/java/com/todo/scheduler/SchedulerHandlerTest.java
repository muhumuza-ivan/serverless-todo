package com.todo.scheduler;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.events.SQSBatchResponse;
import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.services.scheduler.SchedulerClient;
import software.amazon.awssdk.services.scheduler.model.ActionAfterCompletion;
import software.amazon.awssdk.services.scheduler.model.ConflictException;
import software.amazon.awssdk.services.scheduler.model.CreateScheduleRequest;
import software.amazon.awssdk.services.scheduler.model.DeleteScheduleRequest;
import software.amazon.awssdk.services.scheduler.model.ResourceNotFoundException;
import software.amazon.awssdk.services.scheduler.model.UpdateScheduleRequest;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class SchedulerHandlerTest {

    private static final Instant NOW = Instant.parse("2026-10-11T10:00:00Z");
    private static final SchedulerHandler.Config CONFIG =
            new SchedulerHandler.Config("group", "arn:expiry", "arn:role", "arn:dlq");

    private SchedulerClient scheduler;
    private SchedulerHandler handler;
    private Context context;

    @BeforeEach
    void setUp() {
        scheduler = mock(SchedulerClient.class);
        handler = new SchedulerHandler(scheduler, Clock.fixed(NOW, ZoneOffset.UTC), CONFIG);
        context = mock(Context.class);
        when(context.getLogger()).thenReturn(mock(LambdaLogger.class));
    }

    @Test
    void scheduleCreatesOneTimeScheduleAtDeadline() {
        long deadline = NOW.plusSeconds(300).getEpochSecond();
        handler.handleRequest(sqs(scheduleMessage("m1", deadline)), context);

        ArgumentCaptor<CreateScheduleRequest> captor = ArgumentCaptor.forClass(CreateScheduleRequest.class);
        verify(scheduler).createSchedule(captor.capture());
        CreateScheduleRequest req = captor.getValue();
        assertEquals("task-t1", req.name());
        assertEquals("group", req.groupName());
        assertEquals("at(2026-10-11T10:05:00)", req.scheduleExpression());
        assertEquals("UTC", req.scheduleExpressionTimezone());
        assertEquals(ActionAfterCompletion.DELETE, req.actionAfterCompletion());
        assertEquals("arn:expiry", req.target().arn());
        assertEquals("arn:dlq", req.target().deadLetterConfig().arn());
        assertTrue(req.target().input().contains("\"deadlineEpoch\":" + deadline));
    }

    @Test
    void existingScheduleIsReplaced() {
        when(scheduler.createSchedule(any(CreateScheduleRequest.class)))
                .thenThrow(ConflictException.builder().message("exists").build());

        SQSBatchResponse res = handler.handleRequest(sqs(scheduleMessage("m1", NOW.plusSeconds(600).getEpochSecond())), context);

        ArgumentCaptor<UpdateScheduleRequest> captor = ArgumentCaptor.forClass(UpdateScheduleRequest.class);
        verify(scheduler).updateSchedule(captor.capture());
        assertEquals("at(2026-10-11T10:10:00)", captor.getValue().scheduleExpression());
        assertTrue(res.getBatchItemFailures().isEmpty());
    }

    @Test
    void pastDeadlineFiresShortlyInstead() {
        handler.handleRequest(sqs(scheduleMessage("m1", NOW.minusSeconds(60).getEpochSecond())), context);

        ArgumentCaptor<CreateScheduleRequest> captor = ArgumentCaptor.forClass(CreateScheduleRequest.class);
        verify(scheduler).createSchedule(captor.capture());
        assertEquals("at(2026-10-11T10:00:30)", captor.getValue().scheduleExpression());
    }

    @Test
    void cancelDeletesScheduleAndToleratesMissingOne() {
        when(scheduler.deleteSchedule(any(DeleteScheduleRequest.class)))
                .thenThrow(ResourceNotFoundException.builder().message("gone").build());

        SQSBatchResponse res = handler.handleRequest(sqs(cancelMessage("m1")), context);

        verify(scheduler).deleteSchedule(DeleteScheduleRequest.builder().name("task-t1").groupName("group").build());
        assertTrue(res.getBatchItemFailures().isEmpty());
    }

    @Test
    void failureFailsRestOfFifoBatch() {
        when(scheduler.deleteSchedule(any(DeleteScheduleRequest.class)))
                .thenThrow(new RuntimeException("throttled"));

        SQSBatchResponse res = handler.handleRequest(
                sqs(cancelMessage("m1"), scheduleMessage("m2", NOW.plusSeconds(300).getEpochSecond())), context);

        verify(scheduler, never()).createSchedule(any(CreateScheduleRequest.class));
        assertEquals(List.of("m1", "m2"), res.getBatchItemFailures().stream()
                .map(SQSBatchResponse.BatchItemFailure::getItemIdentifier).toList());
    }

    private static SQSEvent.SQSMessage scheduleMessage(String id, long deadlineEpoch) {
        return message(id, """
                {"action":"SCHEDULE","taskId":"t1","userId":"u1","deadline":"%s","deadlineEpoch":%d}"""
                .formatted(Instant.ofEpochSecond(deadlineEpoch), deadlineEpoch));
    }

    private static SQSEvent.SQSMessage cancelMessage(String id) {
        return message(id, """
                {"action":"CANCEL","taskId":"t1","userId":"u1","deadline":"x","deadlineEpoch":1}""");
    }

    private static SQSEvent.SQSMessage message(String id, String body) {
        SQSEvent.SQSMessage m = new SQSEvent.SQSMessage();
        m.setMessageId(id);
        m.setBody(body);
        return m;
    }

    private static SQSEvent sqs(SQSEvent.SQSMessage... messages) {
        SQSEvent event = new SQSEvent();
        event.setRecords(List.of(messages));
        return event;
    }
}
