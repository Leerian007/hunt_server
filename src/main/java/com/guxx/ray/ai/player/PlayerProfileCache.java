package com.guxx.ray.ai.player;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import java.time.Duration;
import java.util.Optional;

@Component
public class PlayerProfileCache {
    public static final Duration TTL = Duration.ofMinutes(30);
    private static final Logger log = LoggerFactory.getLogger(PlayerProfileCache.class);
    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;

    public PlayerProfileCache(StringRedisTemplate redis, ObjectMapper mapper) {
        this.redis = redis;
        this.mapper = mapper;
    }

    public Optional<PlayerSummary> get(String steamId) {
        try {
            String json = redis.opsForValue().get(key(steamId));
            if (json == null) return Optional.empty();
            PlayerSummary profile = mapper.readValue(json, PlayerSummary.class);
            if (profile != null && steamId.equals(profile.steamId()) && profile.personaName() != null
                    && !profile.personaName().isBlank()) return Optional.of(profile);
        } catch (DataAccessException | JsonProcessingException ex) {
            log.warn("Player profile cache read failed for {}; fetching from Steam", steamId);
        }
        return Optional.empty();
    }

    public void put(PlayerSummary profile) {
        try {
            redis.opsForValue().set(key(profile.steamId()), mapper.writeValueAsString(profile), TTL);
        } catch (DataAccessException | JsonProcessingException ex) {
            log.warn("Player profile cache write failed for {}", profile.steamId());
        }
    }

    public void evict(String steamId) {
        try {
            redis.delete(key(steamId));
        } catch (DataAccessException ex) {
            log.warn("Player profile cache eviction failed for {}", steamId);
        }
    }

    private String key(String steamId) {
        return "player:steam:summary:v1:" + steamId;
    }
}
