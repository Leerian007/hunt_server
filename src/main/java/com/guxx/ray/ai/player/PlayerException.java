package com.guxx.ray.ai.player;

import org.springframework.http.HttpStatus;

public class PlayerException extends RuntimeException {
    private final HttpStatus status;

    public PlayerException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public HttpStatus status() {
        return status;
    }
}
