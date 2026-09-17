package com.example.seckill.cloud.order;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Redis 秒杀库存回补：只有当用户确实占用过名额（在已购集合中）时才 INCR，
 * SREM/INCR 同一脚本原子完成，重复调用天然幂等。
 * 用户主动取消、超时关单、下单失败补偿三处共用。
 */
@Component
public class StockReleaser {
    private static final String STOCK = "seckill:stock:";
    private static final String USERS = "seckill:users:";

    private final StringRedisTemplate redis;
    private final DefaultRedisScript<Long> releaseScript = new DefaultRedisScript<>("""
            if redis.call('SREM', KEYS[2], ARGV[1]) == 1 then
              return redis.call('INCR', KEYS[1])
            end
            return 0
            """, Long.class);

    public StockReleaser(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /** 回补一件库存与名额；返回回补后的剩余库存（0 表示该用户未占名额，未实际回补）。 */
    public long release(Long itemId, Long userId) {
        Long remaining = redis.execute(releaseScript,
                List.of(STOCK + itemId, USERS + itemId), userId.toString());
        return remaining == null ? 0 : remaining;
    }
}
