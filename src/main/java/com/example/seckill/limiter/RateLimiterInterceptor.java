package com.example.seckill.limiter;

import com.example.seckill.dto.Response;
import com.example.seckill.utils.JwtUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;
import java.time.Duration;

/**
 * 令牌桶限流拦截器：每用户每秒最多 N 次请求
 * 使用 Redis 实现分布式限流
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RateLimiterInterceptor implements HandlerInterceptor {

    private final RedisTemplate<String, Object> redisTemplate;
    private final JwtUtil jwtUtil;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final int MAX_REQUESTS_PER_SECOND = 5;
    private static final String RATE_PREFIX = "rate:user:";

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response,
                             Object handler) throws Exception {
        // 获取用户 ID
        Long userId = extractUserId(request);
        if (userId == null) {
            writeError(response, 401, "未登录");
            return false;
        }

        // 令牌桶限流
        String key = RATE_PREFIX + userId;
        Long count = redisTemplate.opsForValue().increment(key);

        if (count == 1) {
            // 第一次请求，设置过期时间（滑动窗口 1 秒）
            redisTemplate.expire(key, Duration.ofSeconds(1));
        }

        if (count != null && count > MAX_REQUESTS_PER_SECOND) {
            log.warn("用户 {} 触发限流 count={}", userId, count);
            writeError(response, 429, "请求过于频繁，请稍后再试");
            return false;
        }

        return true;
    }

    private Long extractUserId(HttpServletRequest request) {
        String auth = request.getHeader("Authorization");
        if (auth != null && auth.startsWith("Bearer ")) {
            return jwtUtil.validateAndGetUserId(auth.substring(7));
        }
        return null;
    }

    private void writeError(HttpServletResponse response, int code, String msg) throws IOException {
        response.setContentType("application/json;charset=UTF-8");
        response.setStatus(200); // 业务层返回 JSON
        response.getWriter().write(objectMapper.writeValueAsString(
                Response.error(code, msg)
        ));
    }
}
