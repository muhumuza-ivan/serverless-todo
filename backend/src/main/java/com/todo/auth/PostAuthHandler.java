package com.todo.auth;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.logging.LogLevel;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.todo.shared.Clients;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.SubscribeRequest;
import software.amazon.awssdk.services.sns.model.SubscribeResponse;

import java.util.List;
import java.util.Map;

/**
 * Cognito Post Authentication trigger: subscribes the user's email to the task
 * notification topic with a filter policy, so each user only receives their own
 * expiry notifications.
 *
 * Runs on every sign-in. SNS Subscribe is idempotent when the endpoint and attributes
 * are identical (it returns the existing subscription), so repeated sign-ins are safe.
 * Failures are logged but never rethrown: a notification problem must not block sign-in.
 */
public class PostAuthHandler implements RequestHandler<Map<String, Object>, Map<String, Object>> {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final SnsClient sns;
    private final String topicArn;

    public PostAuthHandler() {
        this(Clients.sns(), System.getenv("TOPIC_ARN"));
    }

    PostAuthHandler(SnsClient sns, String topicArn) {
        this.sns = sns;
        this.topicArn = topicArn;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> handleRequest(Map<String, Object> event, Context context) {
        Map<String, Object> request = (Map<String, Object>) event.get("request");
        Map<String, Object> attributes = request == null ? null : (Map<String, Object>) request.get("userAttributes");
        String email = attributes == null ? null : (String) attributes.get("email");

        if (email == null || email.isBlank()) {
            context.getLogger().log("No email attribute on user " + event.get("userName") + ", skipping subscription", LogLevel.WARN);
            return event;
        }

        try {
            SubscribeResponse result = sns.subscribe(SubscribeRequest.builder()
                    .topicArn(topicArn)
                    .protocol("email")
                    .endpoint(email)
                    .attributes(Map.of("FilterPolicy", filterPolicy(email)))
                    .returnSubscriptionArn(true)
                    .build());
            context.getLogger().log("Subscribed user " + event.get("userName") + ": " + result.subscriptionArn(), LogLevel.INFO);
        } catch (Exception e) {
            context.getLogger().log("Failed to subscribe user " + event.get("userName") + ": " + e.getMessage(), LogLevel.ERROR);
        }
        return event;
    }

    static String filterPolicy(String email) {
        try {
            return MAPPER.writeValueAsString(Map.of("email", List.of(email)));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
