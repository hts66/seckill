package com.example.seckill.service;

import com.example.seckill.dto.SeckillResult;

public interface SeckillService {

    /**
     * 获取秒杀路径（活动开始后才能获取，防提前暴露）
     */
    String getSeckillPath(Long userId, Long itemId);

    /**
     * 执行秒杀（核心流程）
     * 1. 校验动态路径
     * 2. Redis Lua 脚本原子扣库存 + 校验一人一单
     * 3. 发送 MQ 异步下单
     */
    SeckillResult executeSeckill(Long userId, Long itemId, String pathKey);

    /**
     * 查询秒杀结果（前端轮询）
     */
    SeckillResult getSeckillResult(Long userId, Long itemId);

    /**
     * 活动预热：加载库存到 Redis
     */
    void warmUpActivity(Long activityId);
}
