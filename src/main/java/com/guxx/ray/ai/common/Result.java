package com.guxx.ray.ai.common;

import com.fasterxml.jackson.annotation.JsonInclude;

/** Application code 0 denotes success; HTTP status codes are set independently. */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record Result<T>(int code, String message, T data) {
    public static <T> Result<T> success(T data) {
        return new Result<>(0, "success", data);
    }

    public static Result<Void> success() {
        return success(null);
    }

    public static <T> Result<T> error(int code, String message) {
        if (code == 0) {
            throw new IllegalArgumentException("Error code must not be 0");
        }
        return new Result<>(code, message, null);
    }
}