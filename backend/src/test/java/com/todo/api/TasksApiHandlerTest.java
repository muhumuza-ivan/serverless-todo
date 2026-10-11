package com.todo.api;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.todo.shared.Task;
import com.todo.shared.TaskStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class TasksApiHandlerTest {

    private TaskService service;
    private TasksApiHandler handler;
    private Context context;

    @BeforeEach
    void setUp() {
        service = mock(TaskService.class);
        handler = new TasksApiHandler(service, "https://app.example.com");
        context = mock(Context.class);
        when(context.getLogger()).thenReturn(mock(LambdaLogger.class));
    }

    @Test
    void createUsesIdentityFromTokenClaims() {
        Task task = new Task("id", "sub-1", "u@example.com", "d", "2026-10-11", TaskStatus.Pending,
                "2026-10-11T10:05:00Z", 1L, "c", "u");
        when(service.create(eq("sub-1"), eq("u@example.com"), any())).thenReturn(task);

        APIGatewayProxyResponseEvent res = handler.handleRequest(
                request("POST", "/tasks", "{\"description\":\"d\",\"userId\":\"someone-else\"}"), context);

        assertEquals(201, res.getStatusCode());
        assertEquals("https://app.example.com", res.getHeaders().get("Access-Control-Allow-Origin"));
        assertTrue(res.getBody().contains("\"status\":\"Pending\""));
        assertFalse(res.getBody().contains("u@example.com"), "email is not echoed back");
    }

    @Test
    void listPassesStatusFilter() {
        when(service.list("sub-1", "Expired")).thenReturn(List.of());
        APIGatewayProxyRequestEvent req = request("GET", "/tasks", null)
                .withQueryStringParameters(Map.of("status", "Expired"));

        assertEquals("[]", handler.handleRequest(req, context).getBody());
    }

    @Test
    void deleteReturns204AndMapsApiErrors() {
        APIGatewayProxyRequestEvent req = request("DELETE", "/tasks/{taskId}", null)
                .withPathParameters(Map.of("taskId", "abc"));
        assertEquals(204, handler.handleRequest(req, context).getStatusCode());

        doThrow(ApiException.notFound()).when(service).delete("sub-1", "abc");
        APIGatewayProxyResponseEvent res = handler.handleRequest(req, context);
        assertEquals(404, res.getStatusCode());
        assertEquals("{\"message\":\"Task not found\"}", res.getBody());
    }

    @Test
    void malformedJsonIs400AndUnexpectedErrorsAre500() {
        assertEquals(400, handler.handleRequest(request("POST", "/tasks", "{oops"), context).getStatusCode());

        when(service.list(any(), any())).thenThrow(new RuntimeException("ddb down"));
        APIGatewayProxyResponseEvent res = handler.handleRequest(request("GET", "/tasks", null), context);
        assertEquals(500, res.getStatusCode());
        assertFalse(res.getBody().contains("ddb down"));
    }

    @Test
    void missingClaimsIs401() {
        APIGatewayProxyRequestEvent req = new APIGatewayProxyRequestEvent().withHttpMethod("GET").withResource("/tasks");
        assertEquals(401, handler.handleRequest(req, context).getStatusCode());
    }

    private static APIGatewayProxyRequestEvent request(String method, String resource, String body) {
        APIGatewayProxyRequestEvent.ProxyRequestContext ctx = new APIGatewayProxyRequestEvent.ProxyRequestContext();
        ctx.setAuthorizer(Map.of("claims", Map.of("sub", "sub-1", "email", "u@example.com")));
        return new APIGatewayProxyRequestEvent()
                .withHttpMethod(method)
                .withResource(resource)
                .withBody(body)
                .withRequestContext(ctx);
    }
}
