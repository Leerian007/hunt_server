package com.guxx.ray.ai.auth;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import java.time.Duration;

@Component
public class OpenIdStateStore {
    public static final Duration TTL = Duration.ofMinutes(10);
    private final StringRedisTemplate redis;

    public OpenIdStateStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public void save(String state, String platform) {
        redis.opsForValue().set("auth:steam:state:" + state, platform, TTL);
    }

    public String consume(String state) {
        return redis.opsForValue().getAndDelete("auth:steam:state:" + state);
    }

    public boolean claimNonce(String nonce) {
        return Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(
                "auth:steam:nonce:" + nonce, "1", TTL.plusMinutes(1)));
    }
}
