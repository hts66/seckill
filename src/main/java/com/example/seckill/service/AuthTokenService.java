package com.example.seckill.service;

import com.example.seckill.dto.LoginResponse;
import com.example.seckill.entity.User;
import com.example.seckill.utils.JwtUtil;
import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AuthTokenService {
    private static final String REFRESH_PREFIX = "auth:refresh:";
    private final JwtUtil jwtUtil;
    private final UserService userService;
    private final StringRedisTemplate redisTemplate;

    public LoginResponse issue(User user) {
        String tokenId = UUID.randomUUID().toString();
        String accessToken = jwtUtil.generateAccessToken(user);
        String refreshToken = jwtUtil.generateRefreshToken(user, tokenId);
        String sessionValue = user.getId() + ":" + version(user);
        redisTemplate.opsForValue().set(REFRESH_PREFIX + tokenId, sessionValue,
                Duration.ofMillis(jwtUtil.getRefreshExpiration()));
        return new LoginResponse(accessToken, refreshToken, toUserVO(user));
    }

    public LoginResponse refresh(String refreshToken) {
        Claims claims = jwtUtil.parseToken(refreshToken);
        if (!"refresh".equals(claims.get("type", String.class)) || claims.getId() == null) {
            throw new IllegalArgumentException("刷新令牌无效");
        }
        String key = REFRESH_PREFIX + claims.getId();
        String session = redisTemplate.opsForValue().get(key);
        if (session == null) throw new IllegalArgumentException("登录状态已失效");

        Long userId = Long.parseLong(claims.getSubject());
        User user = userService.getById(userId);
        Integer claimVersion = claims.get("tokenVersion", Integer.class);
        if (user == null || user.getDeletedAt() != null || user.getStatus() == null || user.getStatus() != 1
                || claimVersion == null || claimVersion != version(user)
                || !session.equals(userId + ":" + version(user))) {
            redisTemplate.delete(key);
            throw new IllegalArgumentException("登录状态已失效");
        }
        redisTemplate.delete(key);
        return issue(user);
    }

    public void revoke(String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) return;
        try {
            Claims claims = jwtUtil.parseToken(refreshToken);
            if (claims.getId() != null) redisTemplate.delete(REFRESH_PREFIX + claims.getId());
        } catch (Exception ignored) {
            // Logout is idempotent.
        }
    }

    public void revokeAll(User user) {
        user.setTokenVersion(version(user) + 1);
        userService.updateById(user);
    }

    private int version(User user) {
        return user.getTokenVersion() == null ? 0 : user.getTokenVersion();
    }

    private LoginResponse.UserVO toUserVO(User user) {
        return new LoginResponse.UserVO(user.getId(), user.getEmail(), user.getUsername(),
                user.getAvatar(), user.getRole() == null ? 0 : user.getRole());
    }
}
