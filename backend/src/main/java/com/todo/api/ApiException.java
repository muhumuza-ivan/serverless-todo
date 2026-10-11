package com.todo.api;

/** An error that maps directly to an HTTP status code and client-safe message. */
public class ApiException extends RuntimeException {

    private final int statusCode;

    public ApiException(int statusCode, String message) {
        super(message);
        this.statusCode = statusCode;
    }

    public int statusCode() {
        return statusCode;
    }

    public static ApiException badRequest(String message) {
        return new ApiException(400, message);
    }

    public static ApiException notFound() {
        return new ApiException(404, "Task not found");
    }

    public static ApiException conflict(String message) {
        return new ApiException(409, message);
    }
}
