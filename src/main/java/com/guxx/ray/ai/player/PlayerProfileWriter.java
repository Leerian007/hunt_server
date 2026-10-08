package com.guxx.ray.ai.player;

import com.guxx.ray.ai.repository.UserProfileRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Service
public class PlayerProfileWriter {
    private static final Logger log = LoggerFactory.getLogger(PlayerProfileWriter.class);
    private final UserProfileRepository users;
    private final PlayerProfileCache cache;

    public PlayerProfileWriter(UserProfileRepository users, PlayerProfileCache cache) {
        this.users = users;
        this.cache = cache;
    }

    @Async("playerProfileExecutor")
    public void persist(PlayerSummary profile) {
        try {
            users.upsertSteamProfile(profile.steamId(), profile.personaName(), profile.avatarFull(), profile.locCountryCode());
        } catch (RuntimeException ex) {
            log.error("Asynchronous player profile persistence failed for {} ({})", profile.steamId(), ex.getClass().getSimpleName());
            // A later request will miss the cache and retry the full refresh.
            cache.evict(profile.steamId());
        }
    }
}
