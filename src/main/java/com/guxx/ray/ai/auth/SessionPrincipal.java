package com.guxx.ray.ai.auth;

import java.security.Principal;
import java.time.Instant;

public record SessionPrincipal(String steamId, Instant expiresAt) implements Principal {
    @Override
    public String getName() {
        return steamId;
    }
}
