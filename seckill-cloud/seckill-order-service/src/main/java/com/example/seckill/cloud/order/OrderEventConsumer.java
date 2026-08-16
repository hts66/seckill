package com.example.seckill.cloud.order;

import com.example.seckill.cloud.api.*;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

@Service
public class OrderEventConsumer {
    private static final Logger log = LoggerFactory.getLogger(OrderEventConsumer.class);
    private static final String STOCK = "seckill:stock:";
    private static final String USERS = "seckill:users:";
    private final JdbcClient jdbc;
    private final StringRedisTemplate redis;
    private final DefaultRedisScript<Long> compensateScript = new DefaultRedisScript<>("""
            if redis.call('SREM', KEYS[2], ARGV[1]) == 1 then
              redis.call('INCR', KEYS[1])
            end
            redis.call('SET', KEYS[3], 'FAILED:' .. ARGV[2], 'EX', 7200)
            return 1
            """, Long.class);

    public OrderEventConsumer(JdbcClient jdbc, StringRedisTemplate redis) {
        this.jdbc = jdbc;
        this.redis = redis;
    }

    @RabbitListener(queues = RabbitTopology.ORDER_QUEUE)
    public void consume(SeckillOrderCreatedEvent event) {
        String resultKey = "seckill:result:" + event.userId() + ":" + event.itemId();
        log.info("Consuming seckill order event eventId={}, userId={}, itemId={}",
                event.eventId(), event.userId(), event.itemId());

        ExistingOrder existing = findByEvent(event.eventId());
        if (existing != null) {
            markSuccess(resultKey, existing.orderNo());
            return;
        }

        // 同一用户和秒杀项已有订单时也应恢复成功结果，不能误判后再补偿库存。
        existing = findByUserAndItem(event.userId(), event.itemId());
        if (existing != null) {
            markSuccess(resultKey, existing.orderNo());
            return;
        }

        String orderNo = UUID.randomUUID().toString().replace("-", "");
        RuntimeException lastFailure = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                jdbc.sql("INSERT INTO seckill_orders(event_id,user_id,item_id,activity_id,order_no,amount,status,fulfillment_status) " +
                                "VALUES(:e,:u,:i,:a,:n,:m,0,0)")
                        .param("e", event.eventId()).param("u", event.userId()).param("i", event.itemId())
                        .param("a", event.activityId()).param("n", orderNo).param("m", event.amount())
                        .update();
                // 单条 INSERT 已提交后再发布 SUCCESS；若 Redis 暂时异常，抛出让 RabbitMQ 重投，
                // 下次会通过 eventId 找到已有订单并恢复结果，不会重复扣减或重复建单。
                markSuccess(resultKey, orderNo);
                log.info("Seckill order created eventId={}, orderNo={}", event.eventId(), orderNo);
                return;
            } catch (DuplicateKeyException duplicate) {
                existing = findByEvent(event.eventId());
                if (existing == null) existing = findByUserAndItem(event.userId(), event.itemId());
                if (existing != null) {
                    markSuccess(resultKey, existing.orderNo());
                    return;
                }
                lastFailure = duplicate;
                break;
            } catch (RuntimeException failure) {
                lastFailure = failure;
                log.warn("Order insert failed, attempt {}/3, eventId={}", attempt, event.eventId(), failure);
                if (attempt < 3) {
                    try { Thread.sleep(200L * attempt); }
                    catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }

        compensate(event, resultKey, "订单创建失败，请重试");
        log.error("Order event failed after retries and was compensated, eventId={}",
                event.eventId(), lastFailure);
    }

    private ExistingOrder findByEvent(String eventId) {
        return jdbc.sql("SELECT order_no FROM seckill_orders WHERE event_id=:eventId")
                .param("eventId", eventId).query(ExistingOrder.class).optional().orElse(null);
    }

    private ExistingOrder findByUserAndItem(Long userId, Long itemId) {
        return jdbc.sql("SELECT order_no FROM seckill_orders WHERE user_id=:userId AND item_id=:itemId")
                .param("userId", userId).param("itemId", itemId)
                .query(ExistingOrder.class).optional().orElse(null);
    }

    private void markSuccess(String resultKey, String orderNo) {
        redis.opsForValue().set(resultKey, "SUCCESS:" + orderNo, Duration.ofHours(2));
    }

    private void compensate(SeckillOrderCreatedEvent event, String resultKey, String reason) {
        redis.execute(compensateScript,
                List.of(STOCK + event.itemId(), USERS + event.itemId(), resultKey),
                event.userId().toString(), reason);
    }

    private record ExistingOrder(String orderNo) {}
}
