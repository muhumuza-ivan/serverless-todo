package com.todo.expiry;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.logging.LogLevel;
import com.todo.shared.Clients;
import com.todo.shared.Task;
import com.todo.shared.TaskRepository;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.MessageAttributeValue;
import software.amazon.awssdk.services.sns.model.PublishRequest;

import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Optional;

/**
 * Invoked by the task's EventBridge Scheduler schedule at its deadline.
 *
 * <ol>
 *   <li>Conditionally flips the task Pending → Expired (only if the deadline still matches,
 *       so stale or duplicate firings do nothing).</li>
 *   <li>Publishes an email notification; the {@code email} message attribute is matched by the
 *       owner's subscription filter policy, so only the owner receives it.</li>
 *   <li>Records {@code NotifiedAt}. If publishing fails the invocation fails, the scheduler
 *       retries, and the retry notifies via {@link TaskRepository#findExpiredAwaitingNotification}.</li>
 * </ol>
 */
public class ExpiryHandler implements RequestHandler<Map<String, Object>, String> {

    private final TaskRepository repository;
    private final SnsClient sns;
    private final String topicArn;
    private final Clock clock;

    public ExpiryHandler() {
        this(new TaskRepository(Clients.dynamoDb(), System.getenv("TABLE_NAME")), Clients.sns(),
                System.getenv("TOPIC_ARN"), Clock.systemUTC());
    }

    ExpiryHandler(TaskRepository repository, SnsClient sns, String topicArn, Clock clock) {
        this.repository = repository;
        this.sns = sns;
        this.topicArn = topicArn;
        this.clock = clock;
    }

    @Override
    public String handleRequest(Map<String, Object> input, Context context) {
        String taskId = (String) input.get("taskId");
        String userId = (String) input.get("userId");
        String deadline = (String) input.get("deadline");
        long deadlineEpoch = ((Number) input.get("deadlineEpoch")).longValue();
        String now = clock.instant().truncatedTo(ChronoUnit.SECONDS).toString();

        Optional<Task> task = repository.expire(userId, taskId, deadline, deadlineEpoch, now);
        if (task.isPresent()) {
            context.getLogger().log("Expired task " + taskId, LogLevel.INFO);
        } else {
            task = repository.findExpiredAwaitingNotification(userId, taskId, deadlineEpoch);
            if (task.isEmpty()) {
                context.getLogger().log("Task " + taskId + " not pending at deadline " + deadline
                        + " (completed, deleted, rescheduled or already handled); nothing to do", LogLevel.INFO);
                return "SKIPPED";
            }
            context.getLogger().log("Task " + taskId + " already expired; retrying notification", LogLevel.WARN);
        }

        notifyOwner(task.get(), context);
        repository.markNotified(userId, taskId, now);
        return "EXPIRED";
    }

    private void notifyOwner(Task task, Context context) {
        if (task.email() == null) {
            context.getLogger().log("Task " + task.taskId() + " has no owner email; skipping notification", LogLevel.WARN);
            return;
        }
        String messageId = sns.publish(PublishRequest.builder()
                .topicArn(topicArn)
                .subject("To-Do: a task has expired")
                .message("""
                        Your task has expired before it was completed.

                        Task:     %s
                        Date:     %s
                        Deadline: %s (UTC)

                        Open the To-Do app to review your expired tasks.
                        """.formatted(task.description(), task.date(), task.deadline()))
                .messageAttributes(Map.of("email", MessageAttributeValue.builder()
                        .dataType("String")
                        .stringValue(task.email())
                        .build()))
                .build()).messageId();
        context.getLogger().log("Notified owner of task " + task.taskId() + " (SNS message " + messageId + ")",
                LogLevel.INFO);
    }
}
