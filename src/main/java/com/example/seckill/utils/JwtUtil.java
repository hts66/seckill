package com.example.seckill.utils;

import com.example.seckill.entity.User;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

@Component
public class JwtUtil {
    @Value("${jwt.secret}")
    private String secret;
    @Value("${jwt.access-expiration}")
    private long accessExpiration;
    @Value("${jwt.refresh-expiration}")
    private long refreshExpiration;
    private volatile SecretKey signingKey;

    public String generateAccessToken(User user) {
        return buildToken(user, "access", null, accessExpiration);
    }

    public String generateRefreshToken(User user, String tokenId) {
        return buildToken(user, "refresh", tokenId, refreshExpiration);
    }

    private String buildToken(User user, String type, String tokenId, long expiration) {
        Date now = new Date();
        var builder = Jwts.builder()
                .subject(user.getId().toString())
                .claim("email", user.getEmail())
                .claim("role", user.getRole() == null ? 0 : user.getRole())
                .claim("tokenVersion", user.getTokenVersion() == null ? 0 : user.getTokenVersion())
                .claim("type", type)
                .issuedAt(now)
                .expiration(new Date(now.getTime() + expiration));
        if (tokenId != null) builder.id(tokenId);
        return builder.signWith(getSigningKey()).compact();
    }

    public Claims parseToken(String token) {
        return Jwts.parser().verifyWith(getSigningKey()).build().parseSignedClaims(token).getPayload();
    }

    public Long getUserId(String token) {
        return Long.parseLong(parseToken(token).getSubject());
    }

    public String getEmail(String token) {
        return parseToken(token).get("email", String.class);
    }

    public boolean isTokenExpired(String token) {
        try {
            return parseToken(token).getExpiration().before(new Date());
        } catch (Exception e) {
            return true;
        }
    }

    public Long validateAndGetUserId(String token) {
        try {
            Claims claims = parseToken(token);
            if (!"access".equals(claims.get("type", String.class))) return null;
            return Long.parseLong(claims.getSubject());
        } catch (Exception e) {
            return null;
        }
    }

    public long getRefreshExpiration() {
        return refreshExpiration;
    }

    private SecretKey getSigningKey() {
        if (signingKey == null) {
            synchronized (this) {
                if (signingKey == null) signingKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
            }
        }
        return signingKey;
    }
}
