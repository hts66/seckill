package com.example.seckill.mq;

import com.example.seckill.entity.SeckillItem;
import com.example.seckill.entity.SeckillOrder;
import com.example.seckill.mapper.SeckillItemMapper;
import com.example.seckill.mapper.SeckillOrderMapper;
import com.example.seckill.redis.RedisStockService;
import com.rabbitmq.client.Channel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.Random;

import static com.example.seckill.config.RabbitMQConfig.*;

/**
 * 秒杀消息消费者
 * - 消费秒杀下单消息 → 扣 MySQL 库存 + 创建订单
 * - 消费死信消息 → 取消超时订单 + 释放库存
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SeckillConsumer {

    private final SeckillItemMapper itemMapper;
    private final SeckillOrderMapper orderMapper;
    private final RedisStockService redisStockService;
    private final SeckillProducer seckillProducer;

    /**
     * 消费秒杀下单消息：异步创建订单
     */
    @RabbitListener(queues = SECKILL_QUEUE)
    @Transactional
    public void handleSeckillOrder(SeckillMessage message, Channel channel,
                                   @Header(AmqpHeaders.DELIVERY_TAG) long tag) throws IOException {
        try {
            Long userId = message.getUserId();
            Long itemId = message.getItemId();
            log.info("消费秒杀下单消息 userId={} itemId={}", userId, itemId);

            // 1. 再次校验（兜底）：Redis 中是否已购买
            // 这里简化处理，依赖 MySQL 唯一索引 uk_user_item

            // 2. 扣减 MySQL 库存
            int affected = orderMapper.deductStock(itemId);
            if (affected <= 0) {
                log.error("MySQL 库存扣减失败 itemId={}", itemId);
                redisStockService.rollbackStock(itemId, userId);
                channel.basicAck(tag, false);
                return;
            }

            // 3. 查询秒杀商品信息
            SeckillItem item = itemMapper.selectById(itemId);
            if (item == null) {
                redisStockService.rollbackStock(itemId, userId);
                channel.basicAck(tag, false);
                return;
            }

            // 4. 创建订单
            SeckillOrder order = new SeckillOrder();
            order.setUserId(userId);
            order.setItemId(itemId);
            order.setOrderNo(generateOrderNo());
            order.setAmount(item.getSeckillPrice());
            order.setStatus(0); // 待支付

            try {
                orderMapper.insert(order);
            } catch (Exception e) {
                // 唯一索引冲突 = 重复下单，忽略
                log.warn("重复下单 userId={} itemId={}", userId, itemId);
                channel.basicAck(tag, false);
                return;
            }

            // 5. 发送延迟消息：15 分钟后检查是否已支付，未支付则取消
            seckillProducer.sendOrderCancelDelay(order.getOrderNo(), 15 * 60 * 1000);

            log.info("秒杀订单创建成功 orderNo={} userId={} itemId={}",
                    order.getOrderNo(), userId, itemId);
            channel.basicAck(tag, false);

        } catch (Exception e) {
            log.error("消费秒杀下单消息失败", e);
            // 消费失败不重试，手动处理
            channel.basicNack(tag, false, false);
        }
    }

    /**
     * 消费死信消息：取消超时订单 + 释放库存
     */
    @RabbitListener(queues = DEAD_QUEUE)
    @Transactional
    public void handleOrderCancel(String orderNo, Channel channel,
                                  @Header(AmqpHeaders.DELIVERY_TAG) long tag) throws IOException {
        try {
            log.info("消费订单取消消息 orderNo={}", orderNo);

            // 查询订单
            SeckillOrder order = orderMapper.selectList(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<SeckillOrder>()
                            .eq(SeckillOrder::getOrderNo, orderNo)
            ).stream().findFirst().orElse(null);

            if (order == null) {
                channel.basicAck(tag, false);
                return;
            }

            // 仅取消"待支付"状态的订单
            if (order.getStatus() == 0) {
                order.setStatus(3); // 超时取消
                order.setCancelTime(LocalDateTime.now());
                orderMapper.updateById(order);

                // 释放 Redis 库存
                redisStockService.rollbackStock(order.getItemId(), order.getUserId());
                log.info("订单超时取消成功 orderNo={}", orderNo);
            }

            channel.basicAck(tag, false);

        } catch (Exception e) {
            log.error("消费订单取消消息失败 orderNo={}", orderNo, e);
            channel.basicNack(tag, false, false);
        }
    }

    private String generateOrderNo() {
        long timestamp = System.currentTimeMillis();
        int random = new Random().nextInt(9000) + 1000;
        return "SK" + timestamp + random;
    }
}
