package com.guxx.ray.ai.auth;

import io.jsonwebtoken.JwtException;
import org.junit.jupiter.api.Test;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import static org.assertj.core.api.Assertions.*;

class JwtServiceTest {
    static final String SECRET = Base64.getEncoder().encodeToString("test-only-secret-key-32-bytes-long!".getBytes());
    private final Instant now = Instant.parse("2026-10-08T10:00:00Z");
    private final JwtProperties properties = new JwtProperties(SECRET, Duration.ofDays(7));

    private JwtService service(Instant time) {
        return new JwtService(properties, Clock.fixed(time, ZoneOffset.UTC));
    }

    @Test
    void issuesSevenDaySessionAndRejectsExpiration() {
        String token = service(now).issue("76561198000000000");
        assertThat(service(now).parse(token)).isEqualTo(
                new SessionPrincipal("76561198000000000", now.plus(Duration.ofDays(7))));
        assertThatThrownBy(() -> service(now.plus(Duration.ofDays(7))).parse(token)).isInstanceOf(JwtException.class);
    }

    @Test
    void rejectsWrongKeyAndMalformedToken() {
        String otherKey = Base64.getEncoder().encodeToString("a-different-test-key-32-bytes-long!".getBytes());
        var other = new JwtService(new JwtProperties(otherKey, Duration.ofDays(7)), Clock.fixed(now, ZoneOffset.UTC));
        assertThatThrownBy(() -> service(now).parse(other.issue("76561198000000000"))).isInstanceOf(JwtException.class);
        assertThatThrownBy(() -> service(now).parse("not-a-jwt")).isInstanceOf(JwtException.class);
    }

    @Test
    void refusesMissingSecret() {
        assertThatThrownBy(() -> new JwtService(new JwtProperties("", Duration.ofDays(7)), Clock.systemUTC()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
