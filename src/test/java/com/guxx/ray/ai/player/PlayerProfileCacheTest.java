package com.guxx.ray.ai.player;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import java.time.Duration;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class PlayerProfileCacheTest {
    private static final String ID = "76561198000000000";
    private static final String KEY = "player:steam:summary:v1:" + ID;
    private ValueOperations<String, String> values;
    private PlayerProfileCache cache;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setup() {
        var redis = mock(StringRedisTemplate.class);
        values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        cache = new PlayerProfileCache(redis, new ObjectMapper());
    }

    @Test
    void roundTripsJsonWithThirtyMinuteTtl() {
        var profile = new PlayerSummary(ID, "Hunter", "avatar", "CN");
        cache.put(profile);
        var json = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(values).set(eq(KEY), json.capture(), eq(Duration.ofMinutes(30)));
        when(values.get(KEY)).thenReturn(json.getValue());
        assertThat(cache.get(ID)).contains(profile);
    }

    @Test
    void treatsCorruptionWrongIdentityAndRedisOutageAsCacheMiss() {
        when(values.get(KEY)).thenReturn("broken", "{\"steam_id\":\"different\",\"persona_name\":\"Hunter\"}")
                .thenThrow(new DataAccessResourceFailureException("offline"));
        assertThat(cache.get(ID)).isEmpty();
        assertThat(cache.get(ID)).isEmpty();
        assertThat(cache.get(ID)).isEmpty();
    }
}
