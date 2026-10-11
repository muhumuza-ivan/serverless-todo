package com.todo.auth;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.logging.LogLevel;

import java.util.HashMap;
import java.util.Map;

/**
 * Cognito Pre Sign-up trigger: auto-confirms the user and marks the email as verified,
 * so no verification code is needed. Deliberately makes no AWS calls to stay well within
 * Cognito's 5 second trigger timeout.
 */
public class PreSignUpHandler implements RequestHandler<Map<String, Object>, Map<String, Object>> {

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> handleRequest(Map<String, Object> event, Context context) {
        Map<String, Object> response = (Map<String, Object>) event.get("response");
        if (response == null) {
            response = new HashMap<>();
            event.put("response", response);
        }
        response.put("autoConfirmUser", true);
        response.put("autoVerifyEmail", true);

        context.getLogger().log("Auto-confirmed user " + event.get("userName"), LogLevel.INFO);
        return event;
    }
}
