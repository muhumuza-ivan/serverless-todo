package com.todo.shared;

public enum TaskStatus {
    Pending,
    Completed,
    Expired;

    /** Case-insensitive parse; returns null for unknown values. */
    public static TaskStatus parse(String value) {
        if (value == null) {
            return null;
        }
        for (TaskStatus s : values()) {
            if (s.name().equalsIgnoreCase(value.trim())) {
                return s;
            }
        }
        return null;
    }
}
