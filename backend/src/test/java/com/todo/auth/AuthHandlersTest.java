package com.todo.auth;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.SnsException;
import software.amazon.awssdk.services.sns.model.SubscribeRequest;
import software.amazon.awssdk.services.sns.model.SubscribeResponse;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AuthHandlersTest {

    private static final String TOPIC = "arn:aws:sns:eu-west-1:123456789012:tasks";

    private Context context;
    private SnsClient sns;

    @BeforeEach
    void setUp() {
        context = mock(Context.class);
        when(context.getLogger()).thenReturn(mock(LambdaLogger.class));
        sns = mock(SnsClient.class);
    }

    @Test
    @SuppressWarnings("unchecked")
    void preSignUpAutoConfirmsAndVerifiesEmail() {
        Map<String, Object> event = new HashMap<>(Map.of("userName", "u1", "response", new HashMap<>()));

        Map<String, Object> out = new PreSignUpHandler().handleRequest(event, context);

        Map<String, Object> response = (Map<String, Object>) out.get("response");
        assertEquals(true, response.get("autoConfirmUser"));
        assertEquals(true, response.get("autoVerifyEmail"));
    }

    @Test
    void postAuthSubscribesEmailWithFilterPolicy() {
        when(sns.subscribe(any(SubscribeRequest.class)))
                .thenReturn(SubscribeResponse.builder().subscriptionArn("pending confirmation").build());
        Map<String, Object> event = postAuthEvent("user@example.com");

        Map<String, Object> out = new PostAuthHandler(sns, TOPIC).handleRequest(event, context);

        ArgumentCaptor<SubscribeRequest> captor = ArgumentCaptor.forClass(SubscribeRequest.class);
        verify(sns).subscribe(captor.capture());
        SubscribeRequest req = captor.getValue();
        assertEquals(TOPIC, req.topicArn());
        assertEquals("email", req.protocol());
        assertEquals("user@example.com", req.endpoint());
        assertEquals("{\"email\":[\"user@example.com\"]}", req.attributes().get("FilterPolicy"));
        assertSame(event, out);
    }

    @Test
    void postAuthNeverBlocksSignInOnSnsFailure() {
        when(sns.subscribe(any(SubscribeRequest.class))).thenThrow(SnsException.builder().message("boom").build());
        Map<String, Object> event = postAuthEvent("user@example.com");

        assertSame(event, new PostAuthHandler(sns, TOPIC).handleRequest(event, context));
    }

    @Test
    void postAuthSkipsUsersWithoutEmail() {
        Map<String, Object> event = new HashMap<>(Map.of("userName", "u1", "request", Map.of("userAttributes", Map.of())));

        new PostAuthHandler(sns, TOPIC).handleRequest(event, context);

        verifyNoInteractions(sns);
    }

    private static Map<String, Object> postAuthEvent(String email) {
        return new HashMap<>(Map.of(
                "userName", "u1",
                "request", Map.of("userAttributes", Map.of("email", email)),
                "response", new HashMap<>()));
    }
}
