package com.todo.stream;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.DynamodbEvent;
import com.amazonaws.services.lambda.runtime.events.StreamsEventResponse;
import com.amazonaws.services.lambda.runtime.events.models.dynamodb.AttributeValue;
import com.amazonaws.services.lambda.runtime.logging.LogLevel;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.todo.shared.Clients;
import com.todo.shared.TaskEvent;
import com.todo.shared.TaskStatus;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Translates task changes on the DynamoDB stream into SCHEDULE / CANCEL messages on the
 * task-events FIFO queue.
 *
 * <ul>
 *   <li>INSERT of a Pending task, or a task becoming Pending again, or a Pending task's
 *       deadline changing → SCHEDULE</li>
 *   <li>Pending → Completed, or a Pending task being deleted → CANCEL</li>
 *   <li>Everything else (description edits, Pending → Expired by the system) → ignored</li>
 * </ul>
 *
 * Messages use {@code MessageGroupId = taskId}, so all events for one task are processed in
 * order, and {@code MessageDeduplicationId = stream eventID}, so stream retries don't create
 * duplicate messages. Records are sent one at a time in stream order; on failure the
 * remaining records are reported as failed so the stream retries from that point.
 */
public class StreamProcessorHandler implements RequestHandler<DynamodbEvent, StreamsEventResponse> {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final SqsClient sqs;
    private final String queueUrl;

    public StreamProcessorHandler() {
        this(Clients.sqs(), System.getenv("TASK_EVENTS_QUEUE_URL"));
    }

    StreamProcessorHandler(SqsClient sqs, String queueUrl) {
        this.sqs = sqs;
        this.queueUrl = queueUrl;
    }

    @Override
    public StreamsEventResponse handleRequest(DynamodbEvent event, Context context) {
        List<StreamsEventResponse.BatchItemFailure> failures = new ArrayList<>();
        boolean failed = false;

        for (DynamodbEvent.DynamodbStreamRecord record : event.getRecords()) {
            String sequenceNumber = record.getDynamodb().getSequenceNumber();
            if (failed) {
                failures.add(new StreamsEventResponse.BatchItemFailure(sequenceNumber));
                continue;
            }
            try {
                Optional<TaskEvent> taskEvent = toTaskEvent(record);
                if (taskEvent.isEmpty()) {
                    continue;
                }
                TaskEvent e = taskEvent.get();
                sqs.sendMessage(SendMessageRequest.builder()
                        .queueUrl(queueUrl)
                        .messageGroupId(e.taskId())
                        .messageDeduplicationId(record.getEventID())
                        .messageBody(MAPPER.writeValueAsString(e))
                        .build());
                context.getLogger().log(e.action() + " task " + e.taskId() + " (stream " + record.getEventName() + ")",
                        LogLevel.INFO);
            } catch (Exception ex) {
                context.getLogger().log("Failed to forward stream record " + sequenceNumber + ": " + ex, LogLevel.ERROR);
                failures.add(new StreamsEventResponse.BatchItemFailure(sequenceNumber));
                failed = true;
            }
        }
        return new StreamsEventResponse(failures);
    }

    static Optional<TaskEvent> toTaskEvent(DynamodbEvent.DynamodbStreamRecord record) {
        Map<String, AttributeValue> oldImage = record.getDynamodb().getOldImage();
        Map<String, AttributeValue> newImage = record.getDynamodb().getNewImage();
        TaskStatus oldStatus = TaskStatus.parse(str(oldImage, "Status"));
        TaskStatus newStatus = TaskStatus.parse(str(newImage, "Status"));

        return switch (record.getEventName()) {
            case "INSERT" -> newStatus == TaskStatus.Pending
                    ? Optional.of(event(TaskEvent.Action.SCHEDULE, newImage))
                    : Optional.empty();
            case "MODIFY" -> {
                if (newStatus == TaskStatus.Pending && (oldStatus != TaskStatus.Pending
                        || !Objects.equals(str(oldImage, "DeadlineEpoch"), str(newImage, "DeadlineEpoch")))) {
                    yield Optional.of(event(TaskEvent.Action.SCHEDULE, newImage));
                }
                if (oldStatus == TaskStatus.Pending && newStatus == TaskStatus.Completed) {
                    yield Optional.of(event(TaskEvent.Action.CANCEL, newImage));
                }
                yield Optional.empty();
            }
            case "REMOVE" -> oldStatus == TaskStatus.Pending
                    ? Optional.of(event(TaskEvent.Action.CANCEL, oldImage))
                    : Optional.empty();
            default -> Optional.empty();
        };
    }

    private static TaskEvent event(TaskEvent.Action action, Map<String, AttributeValue> image) {
        String epoch = str(image, "DeadlineEpoch");
        return new TaskEvent(action, str(image, "TaskId"), str(image, "UserId"), str(image, "Deadline"),
                epoch == null ? 0L : Long.parseLong(epoch));
    }

    private static String str(Map<String, AttributeValue> image, String name) {
        if (image == null || !image.containsKey(name)) {
            return null;
        }
        AttributeValue v = image.get(name);
        return v.getS() != null ? v.getS() : v.getN();
    }
}
