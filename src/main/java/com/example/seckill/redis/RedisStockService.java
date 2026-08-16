package com.example.seckill.redis;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.util.DigestUtils;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * Redis 秒杀库存服务
 * - 活动预热：将 MySQL 库存加载到 Redis
 * - 原子扣库存：执行 Lua 脚本
 * - 动态秒杀路径：生成/校验隐藏接口地址
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RedisStockService {

    private final RedisTemplate<String, Object> redisTemplate;
    private final DefaultRedisScript<Long> seckillScript;

    private static final String STOCK_PREFIX = "seckill:stock:";
    private static final String USERS_PREFIX = "seckill:users:";
    private static final String PATH_PREFIX  = "seckill:path:";

    // ==================== 库存预热 ====================

    /**
     * 活动开始前，将秒杀商品库存加载到 Redis
     */
    public void warmUpStock(Long itemId, int stock) {
        String key = STOCK_PREFIX + itemId;
        redisTemplate.opsForValue().set(key, stock, Duration.ofHours(24));
        log.info("库存预热完成 itemId={} stock={}", itemId, stock);
    }

    /**
     * 仅当库存 Key 不存在时才加载（SETNX，原子操作，防并发重复加载）
     * @return true=首次加载成功  false=Key 已存在
     */
    public boolean warmUpStockIfAbsent(Long itemId, int stock) {
        String key = STOCK_PREFIX + itemId;
        Boolean set = redisTemplate.opsForValue().setIfAbsent(key, stock, Duration.ofHours(24));
        if (Boolean.TRUE.equals(set)) {
            log.info("自动预热完成 itemId={} stock={}", itemId, stock);
        }
        return Boolean.TRUE.equals(set);
    }

    /**
     * 检查库存 Key 是否存在于 Redis
     */
    public boolean stockKeyExists(Long itemId) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(STOCK_PREFIX + itemId));
    }

    /**
     * 获取 Redis 中的剩余库存
     */
    public int getStock(Long itemId) {
        Object val = redisTemplate.opsForValue().get(STOCK_PREFIX + itemId);
        return val == null ? 0 : Integer.parseInt(val.toString());
    }

    // ==================== 原子秒杀 ====================

    /**
     * 执行 Lua 脚本：扣库存 + 校验一人一单
     * @return 1-成功 -1-库存不足 -2-已购买
     */
    public long executeSeckill(Long itemId, Long userId, int limitPerUser) {
        List<String> keys = Arrays.asList(
                STOCK_PREFIX + itemId,
                USERS_PREFIX + itemId
        );
        Long result = redisTemplate.execute(
                seckillScript, keys, userId.toString(), String.valueOf(limitPerUser)
        );
        return result == null ? -1 : result;
    }

    /**
     * 回滚库存（MQ 消费失败时调用）
     */
    public void rollbackStock(Long itemId, Long userId) {
        redisTemplate.opsForValue().increment(STOCK_PREFIX + itemId, 1);
        redisTemplate.opsForSet().remove(USERS_PREFIX + itemId, userId.toString());
        log.warn("库存回滚 itemId={} userId={}", itemId, userId);
    }

    // ==================== 动态秒杀路径 ====================

    /**
     * 生成动态秒杀地址（MD5 加密，60秒有效）
     * 每个用户独立生成，防止提前暴露真实接口
     */
    public String generatePathKey(Long itemId, Long userId) {
        // MD5(itemId + userId + uuid) — 不可逆、不可预测
        String raw = itemId + ":" + userId + ":" + UUID.randomUUID();
        String pathKey = DigestUtils.md5DigestAsHex(raw.getBytes(StandardCharsets.UTF_8));
        String key = PATH_PREFIX + itemId + ":" + userId;
        // 60秒有效期
        redisTemplate.opsForValue().set(key, pathKey, Duration.ofSeconds(60));
        return pathKey;
    }

    /**
     * 校验动态秒杀路径是否正确
     */
    public boolean validatePathKey(Long itemId, Long userId, String pathKey) {
        String key = PATH_PREFIX + itemId + ":" + userId;
        Object stored = redisTemplate.opsForValue().get(key);
        return stored != null && stored.toString().equals(pathKey);
    }

    /**
     * 检查用户是否在已购集合中（Lua 脚本已通过，等待 MQ 创建订单）
     */
    public boolean isUserInPurchasedSet(Long itemId, Long userId) {
        Boolean member = redisTemplate.opsForSet().isMember(USERS_PREFIX + itemId, userId.toString());
        return Boolean.TRUE.equals(member);
    }

    /**
     * 清除活动数据
     */
    public void clearActivity(Long itemId) {
        redisTemplate.delete(Arrays.asList(
                STOCK_PREFIX + itemId,
                USERS_PREFIX + itemId,
                PATH_PREFIX + itemId
        ));
    }
}
