package com.guxx.ray.ai.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import java.time.Instant;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "user_profile", uniqueConstraints =
        @UniqueConstraint(name = "uk_user_profile_steam_id", columnNames = "steam_id"))
public class UserProfile {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "steam_id", nullable = false, length = 20, updatable = false)
    private String steamId;

    @Column(name = "persona_name", nullable = false, length = 255)
    private String personaName;

    @Column(name = "avatar_full", columnDefinition = "text")
    private String avatarFull;

    @Column(name = "loc_country_code", length = 2)
    private String locCountryCode;

    @Column(name = "current_mmr")
    private Integer currentMmr;

    @Column(name = "mmr_stars")
    private Integer mmrStars;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    private void onCreate() {
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    private void onUpdate() {
        updatedAt = Instant.now();
    }
}
