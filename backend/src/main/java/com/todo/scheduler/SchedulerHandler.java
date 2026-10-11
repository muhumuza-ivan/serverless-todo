package com.todo.scheduler;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.SQSBatchResponse;
import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import com.amazonaws.services.lambda.runtime.logging.LogLevel;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.todo.shared.Clients;
import com.todo.shared.TaskEvent;
import software.amazon.awssdk.services.scheduler.SchedulerClient;
import software.amazon.awssdk.services.scheduler.model.ActionAfterCompletion;
import software.amazon.awssdk.services.scheduler.model.ConflictException;
import software.amazon.awssdk.services.scheduler.model.CreateScheduleRequest;
import software.amazon.awssdk.services.scheduler.model.DeadLetterConfig;
import software.amazon.awssdk.services.scheduler.model.DeleteScheduleRequest;
import software.amazon.awssdk.services.scheduler.model.FlexibleTimeWindow;
import software.amazon.awssdk.services.scheduler.model.FlexibleTimeWindowMode;
import software.amazon.awssdk.services.scheduler.model.ResourceNotFoundException;
import software.amazon.awssdk.services.scheduler.model.RetryPolicy;
import software.amazon.awssdk.services.scheduler.model.Target;
import software.amazon.awssdk.services.scheduler.model.UpdateScheduleRequest;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Consumes the task-events FIFO queue and manages one-time EventBridge Scheduler schedules
 * ({@code task-<taskId>}) that invoke the expiry function at the task's deadline.
 *
 * Idempotent: creating an existing schedule replaces it, deleting a missing one is a no-op.
 * Within a FIFO batch, once a message fails all later messages are reported as failed too,
 * so ordering per task is preserved on retry.
 */
public class SchedulerHandler implements RequestHandler<SQSEvent, SQSBatchResponse> {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final DateTimeFormatter AT_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);
    /** Deadlines already in the past (e.g. after a processing delay) fire shortly instead. */
    static final Duration MIN_DELAY = Duration.ofSeconds(30);

    private final SchedulerClient scheduler;
    private final Clock clock;
    private final Config config;

    public record Config(String groupName, String targetArn, String roleArn, String deadLetterArn) {
    }

    public SchedulerHandler() {
        this(Clients.scheduler(), Clock.systemUTC(), new Config(
                System.getenv("SCHEDULE_GROUP_NAME"),
                System.getenv("EXPIRY_FUNCTION_ARN"),
                System.getenv("SCHEDULER_ROLE_ARN"),
                System.getenv("EXPIRY_DLQ_ARN")));
    }

    SchedulerHandler(SchedulerClient scheduler, Clock clock, Config config) {
        this.scheduler = scheduler;
        this.clock = clock;
        this.config = config;
    }

    @Override
    public SQSBatchResponse handleRequest(SQSEvent event, Context context) {
        List<SQSBatchResponse.BatchItemFailure> failures = new ArrayList<>();
        boolean failed = false;

        for (SQSEvent.SQSMessage message : event.getRecords()) {
            if (failed) {
                failures.add(new SQSBatchResponse.BatchItemFailure(message.getMessageId()));
                continue;
            }
            try {
                TaskEvent taskEvent = MAPPER.readValue(message.getBody(), TaskEvent.class);
                switch (taskEvent.action()) {
                    case SCHEDULE -> schedule(taskEvent, context);
                    case CANCEL -> cancel(taskEvent, context);
                }
            } catch (Exception e) {
                context.getLogger().log("Failed to process message " + message.getMessageId() + ": " + e,
                        LogLevel.ERROR);
                failures.add(new SQSBatchResponse.BatchItemFailure(message.getMessageId()));
                failed = true;
            }
        }
        return new SQSBatchResponse(failures);
    }

    private void schedule(TaskEvent e, Context context) throws JsonProcessingException {
        Instant earliest = clock.instant().plus(MIN_DELAY);
        Instant deadline = Instant.ofEpochSecond(e.deadlineEpoch());
        Instant fireAt = deadline.isBefore(earliest) ? earliest : deadline;

        String name = TaskEvent.scheduleName(e.taskId());
        String expression = "at(" + AT_FORMAT.format(fireAt) + ")";
        Target target = Target.builder()
                .arn(config.targetArn())
                .roleArn(config.roleArn())
                .input(MAPPER.writeValueAsString(Map.of(
                        "taskId", e.taskId(),
                        "userId", e.userId(),
                        "deadline", e.deadline(),
                        "deadlineEpoch", e.deadlineEpoch())))
                .retryPolicy(RetryPolicy.builder().maximumRetryAttempts(5).maximumEventAgeInSeconds(3600).build())
                .deadLetterConfig(DeadLetterConfig.builder().arn(config.deadLetterArn()).build())
                .build();
        FlexibleTimeWindow window = FlexibleTimeWindow.builder().mode(FlexibleTimeWindowMode.OFF).build();
        String description = "Expire task " + e.taskId();

        try {
            scheduler.createSchedule(CreateScheduleRequest.builder()
                    .name(name)
                    .groupName(config.groupName())
                    .scheduleExpression(expression)
                    .scheduleExpressionTimezone("UTC")
                    .flexibleTimeWindow(window)
                    .target(target)
                    .actionAfterCompletion(ActionAfterCompletion.DELETE)
                    .description(description)
                    .build());
            context.getLogger().log("Created schedule " + name + " " + expression, LogLevel.INFO);
        } catch (ConflictException exists) {
            // Re-delivery or deadline change: replace the existing schedule.
            scheduler.updateSchedule(UpdateScheduleRequest.builder()
                    .name(name)
                    .groupName(config.groupName())
                    .scheduleExpression(expression)
                    .scheduleExpressionTimezone("UTC")
                    .flexibleTimeWindow(window)
                    .target(target)
                    .actionAfterCompletion(ActionAfterCompletion.DELETE)
                    .description(description)
                    .build());
            context.getLogger().log("Updated schedule " + name + " " + expression, LogLevel.INFO);
        }
    }

    private void cancel(TaskEvent e, Context context) {
        String name = TaskEvent.scheduleName(e.taskId());
        try {
            scheduler.deleteSchedule(DeleteScheduleRequest.builder()
                    .name(name)
                    .groupName(config.groupName())
                    .build());
            context.getLogger().log("Cancelled schedule " + name, LogLevel.INFO);
        } catch (ResourceNotFoundException alreadyGone) {
            context.getLogger().log("Schedule " + name + " already gone (fired or cancelled)", LogLevel.INFO);
        }
    }
}
