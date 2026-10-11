package com.todo.stream;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.events.DynamodbEvent;
import com.amazonaws.services.lambda.runtime.events.StreamsEventResponse;
import com.amazonaws.services.lambda.runtime.events.models.dynamodb.AttributeValue;
import com.amazonaws.services.lambda.runtime.events.models.dynamodb.StreamRecord;
import com.todo.shared.TaskEvent;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;
import software.amazon.awssdk.services.sqs.model.SendMessageResponse;
import software.amazon.awssdk.services.sqs.model.SqsException;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class StreamProcessorHandlerTest {

    @Test
    void insertOfPendingTaskSchedules() {
        TaskEvent e = map("INSERT", null, image("Pending", "100")).orElseThrow();
        assertEquals(TaskEvent.Action.SCHEDULE, e.action());
        assertEquals("t1", e.taskId());
        assertEquals(100L, e.deadlineEpoch());
    }

    @Test
    void completingOrDeletingPendingTaskCancels() {
        assertEquals(TaskEvent.Action.CANCEL,
                map("MODIFY", image("Pending", "100"), image("Completed", "100")).orElseThrow().action());
        assertEquals(TaskEvent.Action.CANCEL,
                map("REMOVE", image("Pending", "100"), null).orElseThrow().action());
    }

    @Test
    void reopeningOrMovingDeadlineReschedules() {
        assertEquals(TaskEvent.Action.SCHEDULE,
                map("MODIFY", image("Completed", "100"), image("Pending", "200")).orElseThrow().action());
        TaskEvent moved = map("MODIFY", image("Pending", "100"), image("Pending", "200")).orElseThrow();
        assertEquals(TaskEvent.Action.SCHEDULE, moved.action());
        assertEquals(200L, moved.deadlineEpoch());
    }

    @Test
    void irrelevantChangesAreIgnored() {
        assertTrue(map("MODIFY", image("Pending", "100"), image("Pending", "100")).isEmpty(), "description edit");
        assertTrue(map("MODIFY", image("Pending", "100"), image("Expired", "100")).isEmpty(), "system expiry");
        assertTrue(map("MODIFY", image("Expired", "100"), image("Expired", "100")).isEmpty(), "NotifiedAt set");
        assertTrue(map("REMOVE", image("Completed", "100"), null).isEmpty(), "schedule already cancelled");
        assertTrue(map("REMOVE", image("Expired", "100"), null).isEmpty(), "schedule already fired");
    }

    @Test
    void sendsToFifoKeyedByTaskAndStopsAtFirstFailure() {
        SqsClient sqs = mock(SqsClient.class);
        when(sqs.sendMessage(any(SendMessageRequest.class)))
                .thenReturn(SendMessageResponse.builder().build())
                .thenThrow(SqsException.builder().message("throttled").build());
        Context context = mock(Context.class);
        when(context.getLogger()).thenReturn(mock(LambdaLogger.class));

        DynamodbEvent event = new DynamodbEvent();
        event.setRecords(List.of(
                record("INSERT", null, image("Pending", "100"), "1"),
                record("INSERT", null, image("Pending", "100"), "2"),
                record("INSERT", null, image("Pending", "100"), "3")));

        StreamsEventResponse response = new StreamProcessorHandler(sqs, "q.fifo").handleRequest(event, context);

        ArgumentCaptor<SendMessageRequest> captor = ArgumentCaptor.forClass(SendMessageRequest.class);
        verify(sqs, times(2)).sendMessage(captor.capture());
        assertEquals("t1", captor.getAllValues().get(0).messageGroupId());
        assertEquals("event-1", captor.getAllValues().get(0).messageDeduplicationId());
        assertEquals(List.of("2", "3"), response.getBatchItemFailures().stream()
                .map(StreamsEventResponse.BatchItemFailure::getItemIdentifier).toList());
    }

    private static Optional<TaskEvent> map(String eventName, Map<String, AttributeValue> oldImage,
                                           Map<String, AttributeValue> newImage) {
        return StreamProcessorHandler.toTaskEvent(record(eventName, oldImage, newImage, "1"));
    }

    private static DynamodbEvent.DynamodbStreamRecord record(String eventName, Map<String, AttributeValue> oldImage,
                                                            Map<String, AttributeValue> newImage, String seq) {
        StreamRecord streamRecord = new StreamRecord();
        streamRecord.setOldImage(oldImage);
        streamRecord.setNewImage(newImage);
        streamRecord.setSequenceNumber(seq);
        DynamodbEvent.DynamodbStreamRecord record = new DynamodbEvent.DynamodbStreamRecord();
        record.setEventName(eventName);
        record.setEventID("event-" + seq);
        record.setDynamodb(streamRecord);
        return record;
    }

    private static Map<String, AttributeValue> image(String status, String deadlineEpoch) {
        Map<String, AttributeValue> image = new HashMap<>();
        image.put("TaskId", new AttributeValue().withS("t1"));
        image.put("UserId", new AttributeValue().withS("u1"));
        image.put("Status", new AttributeValue().withS(status));
        image.put("Deadline", new AttributeValue().withS("2026-10-11T10:05:00Z"));
        image.put("DeadlineEpoch", new AttributeValue().withN(deadlineEpoch));
        return image;
    }
}
