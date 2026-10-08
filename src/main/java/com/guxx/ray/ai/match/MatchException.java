package com.guxx.ray.ai.match;

import org.springframework.http.HttpStatus;

public class MatchException extends RuntimeException {
    private final HttpStatus status;

    public MatchException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public HttpStatus status() {
        return status;
    }
}
