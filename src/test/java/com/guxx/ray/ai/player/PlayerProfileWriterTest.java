package com.guxx.ray.ai.player;

import com.guxx.ray.ai.repository.UserProfileRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringJUnitConfig(PlayerProfileWriterTest.Config.class)
class PlayerProfileWriterTest {
    @Configuration
    @Import({PlayerConfiguration.class, PlayerProfileWriter.class})
    static class Config {
        @Bean UserProfileRepository users() { return mock(UserProfileRepository.class); }
        @Bean PlayerProfileCache cache() { return mock(PlayerProfileCache.class); }
    }

    @Autowired PlayerProfileWriter writer;
    @Autowired UserProfileRepository users;
    @Autowired PlayerProfileCache cache;

    @Test
    void persistsOnWorkerThreadAndEvictsCacheOnFailure() throws Exception {
        var invoked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var thread = new AtomicReference<String>();
        doAnswer(call -> {
            thread.set(Thread.currentThread().getName());
            invoked.countDown();
            if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("timeout");
            throw new IllegalStateException("database unavailable");
        }).when(users).upsertSteamProfile("76561198000000000", "Hunter", "avatar", "CN");
        try {
            writer.persist(new PlayerSummary("76561198000000000", "Hunter", "avatar", "CN"));
            assertThat(invoked.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(thread.get()).startsWith("player-profile-");
        } finally {
            release.countDown();
        }
        verify(cache, timeout(5000)).evict("76561198000000000");
    }
}
