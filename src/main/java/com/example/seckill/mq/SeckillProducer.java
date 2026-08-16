package com.example.seckill.mq;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import static com.example.seckill.config.RabbitMQConfig.*;

/**
 * 秒杀消息生产者
 * - 发送秒杀下单消息到异步队列
 * - 发送延迟消息（订单超时取消）
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SeckillProducer {

    private final RabbitTemplate rabbitTemplate;

    /**
     * 发送秒杀下单消息（异步创建订单）
     */
    public void sendSeckillOrder(SeckillMessage message) {
        rabbitTemplate.convertAndSend(SECKILL_EXCHANGE, SECKILL_ROUTING_KEY, message);
        log.info("发送秒杀下单消息 userId={} itemId={}", message.getUserId(), message.getItemId());
    }

    /**
     * 发送订单超时取消延迟消息
     * @param orderNo  订单号
     * @param delayMs  延迟时间（毫秒），如 15 分钟 = 900000
     */
    public void sendOrderCancelDelay(String orderNo, int delayMs) {
        rabbitTemplate.convertAndSend(SECKILL_EXCHANGE, DELAY_ROUTING_KEY, orderNo,
                msg -> {
                    msg.getMessageProperties().setExpiration(String.valueOf(delayMs));
                    return msg;
                });
        log.info("发送订单超时延迟消息 orderNo={} delayMs={}", orderNo, delayMs);
    }
}
