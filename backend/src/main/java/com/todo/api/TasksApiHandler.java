package com.todo.api;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.amazonaws.services.lambda.runtime.logging.LogLevel;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.todo.shared.Clients;
import com.todo.shared.TaskRepository;

import java.time.Clock;
import java.util.Map;

/**
 * REST API for tasks, behind a Cognito User Pool authorizer. The caller's identity
 * ({@code sub}, {@code email}) is taken from the verified token claims, never from the request body.
 *
 * <pre>
 * GET    /tasks[?status=Pending|Completed|Expired]
 * POST   /tasks
 * GET    /tasks/{taskId}
 * PUT    /tasks/{taskId}
 * DELETE /tasks/{taskId}
 * </pre>
 */
public class TasksApiHandler implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private final TaskService service;
    private final String allowedOrigin;

    public TasksApiHandler() {
        this(new TaskService(new TaskRepository(Clients.DYNAMODB, System.getenv("TABLE_NAME")), Clock.systemUTC()),
                System.getenv().getOrDefault("ALLOWED_ORIGIN", "*"));
    }

    TasksApiHandler(TaskService service, String allowedOrigin) {
        this.service = service;
        this.allowedOrigin = allowedOrigin;
    }

    @Override
    public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent request, Context context) {
        String route = request.getHttpMethod() + " " + request.getResource();
        try {
            Map<String, String> claims = claims(request);
            String userId = claims.get("sub");
            if (userId == null) {
                throw new ApiException(401, "Unauthorized");
            }
            String taskId = request.getPathParameters() == null ? null : request.getPathParameters().get("taskId");

            return switch (route) {
                case "GET /tasks" -> json(200, service.list(userId, queryParam(request, "status")));
                case "POST /tasks" -> json(201, service.create(userId, claims.get("email"),
                        body(request, TaskRequests.Create.class)));
                case "GET /tasks/{taskId}" -> json(200, service.get(userId, taskId));
                case "PUT /tasks/{taskId}" -> json(200, service.update(userId, taskId,
                        body(request, TaskRequests.Update.class)));
                case "DELETE /tasks/{taskId}" -> {
                    service.delete(userId, taskId);
                    yield response(204, null);
                }
                default -> throw new ApiException(404, "Route not found");
            };
        } catch (ApiException e) {
            context.getLogger().log(route + " -> " + e.statusCode() + ": " + e.getMessage(), LogLevel.WARN);
            return json(e.statusCode(), Map.of("message", e.getMessage()));
        } catch (Exception e) {
            context.getLogger().log(route + " failed: " + e, LogLevel.ERROR);
            return json(500, Map.of("message", "Internal server error"));
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> claims(APIGatewayProxyRequestEvent request) {
        if (request.getRequestContext() == null || request.getRequestContext().getAuthorizer() == null) {
            return Map.of();
        }
        Object claims = request.getRequestContext().getAuthorizer().get("claims");
        return claims instanceof Map<?, ?> map ? (Map<String, String>) map : Map.of();
    }

    private static String queryParam(APIGatewayProxyRequestEvent request, String name) {
        return request.getQueryStringParameters() == null ? null : request.getQueryStringParameters().get(name);
    }

    private static <T> T body(APIGatewayProxyRequestEvent request, Class<T> type) {
        if (request.getBody() == null || request.getBody().isBlank()) {
            throw ApiException.badRequest("Request body is required");
        }
        try {
            return MAPPER.readValue(request.getBody(), type);
        } catch (JsonProcessingException e) {
            throw ApiException.badRequest("Request body must be valid JSON");
        }
    }

    private APIGatewayProxyResponseEvent json(int status, Object body) {
        try {
            return response(status, MAPPER.writeValueAsString(body));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private APIGatewayProxyResponseEvent response(int status, String body) {
        return new APIGatewayProxyResponseEvent()
                .withStatusCode(status)
                .withHeaders(Map.of(
                        "Content-Type", "application/json",
                        "Access-Control-Allow-Origin", allowedOrigin))
                .withBody(body);
    }
}
