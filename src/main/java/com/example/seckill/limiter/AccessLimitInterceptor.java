package com.example.seckill.limiter;

import com.alibaba.fastjson2.JSON;
import com.example.seckill.annotation.AccessLimit;
import com.example.seckill.dto.Response;
import com.example.seckill.utils.JwtUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

/**
 * 接口限流拦截器
 * 读取 @AccessLimit 注解，基于 Redis 计数器实现 N秒内最多M次请求
 */
@Component
@RequiredArgsConstructor
public class AccessLimitInterceptor implements HandlerInterceptor {

    private final RedisTemplate<String, Object> redisTemplate;
    private final JwtUtil jwtUtil;

    private static final String LIMIT_PREFIX = "access:limit:";

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response,
                             Object handler) throws Exception {
        if (!(handler instanceof HandlerMethod handlerMethod)) {
            return true;
        }

        AccessLimit accessLimit = handlerMethod.getMethodAnnotation(AccessLimit.class);
        if (accessLimit == null) {
            return true; // 无注解，放行
        }

        int seconds = accessLimit.seconds();
        int maxCount = accessLimit.maxCount();

        // 获取用户标识（优先取 token 中的 userId，未登录则用 IP）
        String userKey = resolveUserKey(request);

        String redisKey = LIMIT_PREFIX + handlerMethod.getMethod().getName() + ":" + userKey;

        // Redis 原子计数
        Long count = redisTemplate.opsForValue().increment(redisKey);
        if (count != null && count == 1) {
            // 首次请求，设置过期时间
            redisTemplate.expire(redisKey, seconds, TimeUnit.SECONDS);
        }

        if (count != null && count > maxCount) {
            // 超出限流
            response.setContentType("application/json;charset=UTF-8");
            response.setStatus(429);
            response.getWriter().write(JSON.toJSONString(
                    Response.error(429, "请求过于频繁，请" + seconds + "秒后再试")
            ));
            return false;
        }

        return true;
    }

    /**
     * 解析用户标识：优先 JWT userId → 其次 IP
     */
    private String resolveUserKey(HttpServletRequest request) {
        String auth = request.getHeader("Authorization");
        if (auth != null && auth.startsWith("Bearer ")) {
            try {
                Long userId = jwtUtil.validateAndGetUserId(auth.substring(7));
                if (userId != null) return "user:" + userId;
            } catch (Exception ignored) {
            }
        }
        return "ip:" + getClientIp(request);
    }

    private String getClientIp(HttpServletRequest request) {
        String ip = request.getHeader("X-Forwarded-For");
        if (ip == null || ip.isEmpty()) ip = request.getHeader("X-Real-IP");
        if (ip == null || ip.isEmpty()) ip = request.getRemoteAddr();
        return ip;
    }
}
