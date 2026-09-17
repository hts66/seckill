package com.example.seckill.cloud.order;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 每用户发消息限流：固定 1 秒窗口，默认 5 条/秒。
 * 固定窗口实现简单、无后台清理线程；超量时前端会收到“发言太频繁”的 error 帧。
 */
@Component
public class ChatRateLimiter {

    private static final class Window {
        volatile long second;
        int count;
    }

    private final int perSecond;
    private final ConcurrentHashMap<Long, Window> windows = new ConcurrentHashMap<>();

    public ChatRateLimiter(@Value("${app.chat.message-rate-per-second:5}") int perSecond) {
        this.perSecond = perSecond;
    }

    public boolean tryAcquire(Long userId) {
        long second = System.currentTimeMillis() / 1000;
        Window w = windows.computeIfAbsent(userId, k -> new Window());
        synchronized (w) {
            if (w.second != second) {
                w.second = second;
                w.count = 0;
            }
            if (w.count >= perSecond) return false;
            w.count++;
            return true;
        }
    }
}
