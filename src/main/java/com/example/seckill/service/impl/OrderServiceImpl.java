package com.example.seckill.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.example.seckill.entity.SeckillOrder;
import com.example.seckill.mapper.SeckillOrderMapper;
import com.example.seckill.redis.RedisStockService;
import com.example.seckill.service.OrderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class OrderServiceImpl extends ServiceImpl<SeckillOrderMapper, SeckillOrder>
        implements OrderService {

    private final SeckillOrderMapper orderMapper;
    private final RedisStockService redisStockService;

    @Override
    public List<SeckillOrder> getUserOrders(Long userId) {
        return orderMapper.selectOrdersByUser(userId);
    }

    @Override
    public SeckillOrder getByOrderNo(String orderNo) {
        return orderMapper.selectOne(
                new LambdaQueryWrapper<SeckillOrder>()
                        .eq(SeckillOrder::getOrderNo, orderNo)
        );
    }

    @Override
    @Transactional
    public boolean payOrder(Long userId, String orderNo) {
        SeckillOrder order = getByOrderNo(orderNo);
        if (order == null || !order.getUserId().equals(userId)) {
            throw new RuntimeException("订单不存在");
        }
        if (order.getStatus() != 0) {
            throw new RuntimeException("订单状态异常，无法支付");
        }

        order.setStatus(1); // 已支付
        order.setPayTime(LocalDateTime.now());
        orderMapper.updateById(order);
        log.info("订单支付成功 orderNo={} userId={}", orderNo, userId);
        return true;
    }

    @Override
    @Transactional
    public boolean cancelOrder(Long userId, String orderNo) {
        SeckillOrder order = getByOrderNo(orderNo);
        if (order == null || !order.getUserId().equals(userId)) {
            throw new RuntimeException("订单不存在");
        }
        if (order.getStatus() != 0) {
            throw new RuntimeException("订单状态异常，无法取消");
        }

        order.setStatus(2); // 已取消
        order.setCancelTime(LocalDateTime.now());
        orderMapper.updateById(order);

        // 释放 Redis 库存
        redisStockService.rollbackStock(order.getItemId(), userId);
        log.info("订单取消成功 orderNo={} userId={}", orderNo, userId);
        return true;
    }
}
