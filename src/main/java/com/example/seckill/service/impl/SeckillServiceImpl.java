package com.example.seckill.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.seckill.dto.SeckillResult;
import com.example.seckill.entity.SeckillActivity;
import com.example.seckill.entity.SeckillItem;
import com.example.seckill.entity.SeckillOrder;
import com.example.seckill.mapper.SeckillActivityMapper;
import com.example.seckill.mapper.SeckillItemMapper;
import com.example.seckill.mapper.SeckillOrderMapper;
import com.example.seckill.mq.SeckillMessage;
import com.example.seckill.mq.SeckillProducer;
import com.example.seckill.redis.RedisStockService;
import com.example.seckill.redis.SoldOutCache;
import com.example.seckill.service.SeckillService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Random;

@Slf4j
@Service
@RequiredArgsConstructor
public class SeckillServiceImpl implements SeckillService {

    private final SeckillItemMapper itemMapper;
    private final SeckillActivityMapper activityMapper;
    private final SeckillOrderMapper orderMapper;
    private final RedisStockService redisStockService;
    private final SeckillProducer seckillProducer;
    private final SoldOutCache soldOutCache;

    // ==================== 获取秒杀路径（隐藏接口） ====================

    @Override
    public String getSeckillPath(Long userId, Long itemId) {
        // 校验活动是否进行中
        validateActivityTime(itemId);

        // 生成动态路径
        String pathKey = redisStockService.generatePathKey(itemId, userId);
        log.info("用户 {} 获取秒杀路径 itemId={} pathKey={}", userId, itemId, pathKey);
        return pathKey;
    }

    // ==================== 执行秒杀 ====================

    @Override
    public SeckillResult executeSeckill(Long userId, Long itemId, String pathKey) {
        // 0. JVM本地售罄标记 — 不走Redis，直接返回
        if (soldOutCache.isSoldOut(itemId)) {
            return SeckillResult.builder().status(2).build(); // 已抢完
        }

        // 1. 校验动态路径
        if (!redisStockService.validatePathKey(itemId, userId, pathKey)) {
            return SeckillResult.builder().status(2).build(); // 非法请求 / 已抢完
        }

        // 2. 校验活动是否进行中
        SeckillItem item = validateActivityTime(itemId);
        if (item == null) {
            return SeckillResult.builder().status(5).build(); // 活动已结束
        }

        // 3. 自动预热：如果 Redis 中还没有库存，从 MySQL 加载（SETNX，仅首次生效）
        if (!redisStockService.stockKeyExists(itemId)) {
            redisStockService.warmUpStockIfAbsent(itemId, item.getStock());
        }

        // 4. Redis Lua 原子操作：扣库存 + 一人一单校验
        long result = redisStockService.executeSeckill(itemId, userId, item.getLimitPerUser());

        switch ((int) result) {
            case -1:
                log.info("库存不足 userId={} itemId={}", userId, itemId);
                soldOutCache.markSoldOut(itemId); // JVM本地标记售罄
                return SeckillResult.builder().status(2).build(); // 已抢完
            case -2:
                log.info("已购买过 userId={} itemId={}", userId, itemId);
                return SeckillResult.builder().status(3).build(); // 已买过
            case 1:
                // 5. 秒杀成功，异步 MQ 下单 / 同步兜底
                try {
                    SeckillMessage message = new SeckillMessage(userId, itemId, System.currentTimeMillis());
                    seckillProducer.sendSeckillOrder(message);
                    log.info("秒杀成功（MQ排队中） userId={} itemId={}", userId, itemId);
                    return SeckillResult.builder()
                            .status(0) // 排队中
                            .productName(item.getProductName())
                            .amount(item.getSeckillPrice())
                            .createTime(LocalDateTime.now())
                            .build();
                } catch (Exception mqEx) {
                    log.warn("MQ 不可用，同步创建订单 userId={} itemId={}", userId, itemId);
                    SeckillOrder order = createOrderSync(userId, itemId, item);
                    return SeckillResult.builder()
                            .status(1) // 直接抢到
                            .orderId(order.getId())
                            .orderNo(order.getOrderNo())
                            .productName(item.getProductName())
                            .amount(order.getAmount())
                            .createTime(order.getCreatedAt())
                            .build();
                }
            default:
                return SeckillResult.builder().status(2).build();
        }
    }

    // ==================== 查询秒杀结果 ====================

    @Override
    public SeckillResult getSeckillResult(Long userId, Long itemId) {
        // 查 MySQL 订单
        SeckillOrder order = orderMapper.selectOne(
                new LambdaQueryWrapper<SeckillOrder>()
                        .eq(SeckillOrder::getUserId, userId)
                        .eq(SeckillOrder::getItemId, itemId)
        );

        if (order != null) {
            // 已生成订单
            SeckillItem item = itemMapper.selectById(order.getItemId());
            return SeckillResult.builder()
                    .status(order.getStatus() == 0 ? 1 : 1) // 1=已抢到
                    .orderId(order.getId())
                    .orderNo(order.getOrderNo())
                    .productName(item != null ? item.getProductName() : "")
                    .amount(order.getAmount())
                    .createTime(order.getCreatedAt())
                    .build();
        }

        // 检查用户是否在 Redis 已购集合中（Lua 脚本已通过，MQ 正在消费中）
        if (redisStockService.isUserInPurchasedSet(itemId, userId)) {
            return SeckillResult.builder().status(0).build(); // 排队中，等待 MQ 创建订单
        }

        // 检查 Redis 库存
        int stock = redisStockService.getStock(itemId);
        if (stock <= 0) {
            return SeckillResult.builder().status(2).build(); // 已抢完
        }

        return SeckillResult.builder().status(0).build(); // 排队中
    }

    // ==================== 活动预热 ====================

    @Override
    public void warmUpActivity(Long activityId) {
        List<SeckillItem> items = itemMapper.selectItemsByActivity(activityId);
        for (SeckillItem item : items) {
            redisStockService.warmUpStock(item.getId(), item.getStock());
        }
        log.info("活动预热完成 activityId={} 商品数={}", activityId, items.size());
    }

    // ==================== 私有方法 ====================

    /**
     * MQ 不可用时同步创建订单（兜底）
     */
    private SeckillOrder createOrderSync(Long userId, Long itemId, SeckillItem item) {
        // 扣减 MySQL 库存
        int affected = orderMapper.deductStock(itemId);
        if (affected <= 0) {
            redisStockService.rollbackStock(itemId, userId);
            throw new RuntimeException("MySQL 库存不足");
        }

        // 创建订单
        SeckillOrder order = new SeckillOrder();
        order.setUserId(userId);
        order.setItemId(itemId);
        order.setOrderNo("SK" + System.currentTimeMillis() + (new Random().nextInt(9000) + 1000));
        order.setAmount(item.getSeckillPrice());
        order.setStatus(0);
        order.setCreatedAt(LocalDateTime.now());
        orderMapper.insert(order);

        log.info("同步订单创建成功 orderNo={} userId={} itemId={}", order.getOrderNo(), userId, itemId);
        return order;
    }

    private SeckillItem validateActivityTime(Long itemId) {
        SeckillItem item = itemMapper.selectItemDetail(itemId);
        if (item == null) return null;

        SeckillActivity activity = activityMapper.selectById(item.getActivityId());
        if (activity == null) return null;

        LocalDateTime now = LocalDateTime.now();
        if (now.isBefore(activity.getStartTime())) return null;  // 未到开抢时间
        if (now.isAfter(activity.getEndTime())) return null;      // 已结束
        if (activity.getStatus() == 3) return null;               // 手动结束

        return item;
    }
}
