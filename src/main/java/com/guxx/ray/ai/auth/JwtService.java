package com.guxx.ray.ai.auth;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Service;
import javax.crypto.SecretKey;
import java.time.Clock;
import java.time.Instant;
import java.util.Date;

@Service
public class JwtService {
    private static final String ISSUER = "hunt1896";
    private final SecretKey key;
    private final JwtProperties properties;
    private final Clock clock;

    public JwtService(JwtProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
        if (properties.secret() == null || properties.secret().isBlank()) {
            throw new IllegalArgumentException("JWT_SECRET must contain a Base64-encoded random key of at least 32 bytes");
        }
        key = Keys.hmacShaKeyFor(Decoders.BASE64.decode(properties.secret()));
        if (properties.expiration() == null || properties.expiration().getSeconds() < 1) {
            throw new IllegalArgumentException("JWT expiration must be at least one second");
        }
    }

    public String issue(String steamId) {
        if (steamId == null || !steamId.matches("[0-9]{17}")) {
            throw new IllegalArgumentException("Invalid Steam ID");
        }
        Instant now = clock.instant();
        return Jwts.builder().issuer(ISSUER).subject(steamId).issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(properties.expiration())))
                .signWith(key, Jwts.SIG.HS256).compact();
    }

    public SessionPrincipal parse(String token) {
        var parsed = Jwts.parser().verifyWith(key).requireIssuer(ISSUER)
                .clock(() -> Date.from(clock.instant())).build().parseSignedClaims(token);
        if (!"HS256".equals(parsed.getHeader().getAlgorithm())) {
            throw new JwtException("Unsupported session signature algorithm");
        }
        Claims claims = parsed.getPayload();
        if (claims.getSubject() == null || !claims.getSubject().matches("[0-9]{17}")
                || claims.getExpiration() == null || claims.getIssuedAt() == null
                || !claims.getExpiration().toInstant().isAfter(clock.instant())
                || claims.getIssuedAt().toInstant().isAfter(clock.instant())) {
            throw new JwtException("Invalid session claims");
        }
        return new SessionPrincipal(claims.getSubject(), claims.getExpiration().toInstant());
    }
}
