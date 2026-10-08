package com.guxx.ray.ai.repository;

import com.guxx.ray.ai.entity.UserProfile;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Lock;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;
import java.util.Optional;

public interface UserProfileRepository extends JpaRepository<UserProfile, Long> {
    Optional<UserProfile> findBySteamId(String steamId);
    boolean existsBySteamId(String steamId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from UserProfile u where u.steamId = :steamId")
    Optional<UserProfile> findBySteamIdForUpdate(@Param("steamId") String steamId);

    @Modifying
    @Query(value = """
            INSERT INTO user_profile (steam_id, persona_name, created_at, updated_at)
            VALUES (:steamId, :steamId, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            ON CONFLICT (steam_id) DO NOTHING
            """, nativeQuery = true)
    int ensureProfile(@Param("steamId") String steamId);

    // Atomic across concurrent cache misses; only Steam-owned fields are updated.
    @Transactional
    @Modifying
    @Query(value = """
            INSERT INTO user_profile (steam_id, persona_name, avatar_full, loc_country_code, created_at, updated_at)
            VALUES (:steamId, :personaName, :avatarFull, :countryCode, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            ON CONFLICT (steam_id) DO UPDATE SET
                persona_name = EXCLUDED.persona_name,
                avatar_full = EXCLUDED.avatar_full,
                loc_country_code = EXCLUDED.loc_country_code,
                updated_at = CURRENT_TIMESTAMP
            """, nativeQuery = true)
    void upsertSteamProfile(@Param("steamId") String steamId, @Param("personaName") String personaName,
                            @Param("avatarFull") String avatarFull, @Param("countryCode") String countryCode);
}
