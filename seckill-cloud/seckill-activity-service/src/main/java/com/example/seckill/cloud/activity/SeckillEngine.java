package com.example.seckill.cloud.activity;

import com.example.seckill.cloud.api.*;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Service
public class SeckillEngine {
    private static final String STOCK = "seckill:stock:";
    private static final String USERS = "seckill:users:";
    private final JdbcClient jdbc;
    private final StringRedisTemplate redis;
    private final RabbitTemplate rabbit;
    private final DefaultRedisScript<Long> deductScript = new DefaultRedisScript<>("""
            local stock = redis.call('GET', KEYS[1])
            if not stock or tonumber(stock) <= 0 then return -1 end
            if redis.call('SISMEMBER', KEYS[2], ARGV[1]) == 1 then return -2 end
            redis.call('DECR', KEYS[1])
            redis.call('SADD', KEYS[2], ARGV[1])
            return 1
            """, Long.class);
    private final DefaultRedisScript<Long> compensateScript = new DefaultRedisScript<>("""
            if redis.call('SREM', KEYS[2], ARGV[1]) == 1 then
              redis.call('INCR', KEYS[1])
            end
            redis.call('SET', KEYS[3], 'FAILED:' .. ARGV[2], 'EX', 7200)
            return 1
            """, Long.class);

    public SeckillEngine(JdbcClient jdbc, StringRedisTemplate redis, RabbitTemplate rabbit) {
        this.jdbc = jdbc;
        this.redis = redis;
        this.rabbit = rabbit;
    }

    public record Item(Long id, Long activityId, Long productId, String productName,
                       String productTitle, String productImages, BigDecimal originalPrice,
                       BigDecimal seckillPrice, Integer stock, Integer limitPerUser,
                       String activityName, LocalDateTime activityPreviewTime,
                       LocalDateTime activityStartTime, LocalDateTime activityEndTime) {}

    private static final String ITEM_SELECT = """
            SELECT i.id,i.activity_id,i.product_id,i.product_name,i.product_title,i.product_images,
                   i.original_price,i.seckill_price,i.stock,i.limit_per_user,
                   a.name activity_name,a.preview_time activity_preview_time,
                   a.start_time activity_start_time,a.end_time activity_end_time
              FROM seckill_items i JOIN seckill_activities a ON a.id=i.activity_id
            """;

    public List<Item> visible(boolean upcoming) {
        String range = upcoming
                ? "a.preview_time<=NOW() AND a.start_time>NOW()"
                : "a.start_time<=NOW() AND a.end_time>NOW()";
        return jdbc.sql(ITEM_SELECT + " WHERE i.status=1 AND " + range).query(Item.class).list();
    }

    public List<Item> itemsForActivity(Long activityId) {
        return jdbc.sql(ITEM_SELECT + " WHERE i.activity_id=:id ORDER BY i.id")
                .param("id", activityId).query(Item.class).list();
    }

    public Item itemById(Long id) {
        return jdbc.sql(ITEM_SELECT + " WHERE i.id=:id").param("id", id).query(Item.class).optional()
                .orElseThrow(() -> new IllegalArgumentException("秒杀商品不存在"));
    }

    public String path(Long userId, Long itemId) {
        Item item = itemById(itemId);
        assertActive(item);
        String value = UUID.randomUUID().toString().replace("-", "");
        redis.opsForValue().set("seckill:path:" + userId + ":" + itemId, value, Duration.ofSeconds(60));
        return value;
    }

    public OrderResultView execute(Long userId, Long itemId, Long addressId, String path) {
        String pathKey = "seckill:path:" + userId + ":" + itemId;
        String expected = redis.opsForValue().get(pathKey);
        if (expected == null || !expected.equals(path)) throw new IllegalArgumentException("秒杀路径无效");
        redis.delete(pathKey);

        Item item = itemById(itemId);
        assertActive(item);
        warmIfAbsent(item);
        Long result = redis.execute(deductScript, List.of(STOCK + itemId, USERS + itemId), userId.toString());
        if (result == null || result == -1) return new OrderResultView(2, null, "库存不足");
        if (result == -2) {
            // Redis 集合只代表请求已占用库存，最终购买状态必须以异步订单结果为准。
            String existingResult = redis.opsForValue().get(resultKey(userId, itemId));
            if ("PENDING".equals(existingResult)) return OrderResultView.pending();
            if (existingResult != null && existingResult.startsWith("SUCCESS:")) {
                return new OrderResultView(1, existingResult.substring(8), "抢购成功");
            }
            return new OrderResultView(3, null, "已经购买过");
        }

        String eventId = UUID.randomUUID().toString();
        String resultKey = resultKey(userId, itemId);
        redis.opsForValue().set(resultKey, "PENDING", Duration.ofHours(2));
        try {
            CorrelationData correlation = new CorrelationData(eventId);
            rabbit.convertAndSend(RabbitTopology.EXCHANGE, RabbitTopology.ORDER_KEY,
                    // 地址属于抢购成功后的履约流程，秒杀消息不依赖地址。
                    new SeckillOrderCreatedEvent(eventId, userId, itemId, item.activityId(),
                            item.seckillPrice(), null, LocalDateTime.now()), correlation);
            CorrelationData.Confirm confirm = correlation.getFuture().get(5, TimeUnit.SECONDS);
            if (!confirm.isAck() || correlation.getReturned() != null) {
                throw new IllegalStateException(confirm.getReason() == null
                        ? "订单消息未路由到队列" : confirm.getReason());
            }
            return OrderResultView.pending();
        } catch (Exception publishFailure) {
            // 确认超时无法区分“未送达”和“已送达但确认延迟”，不能贸然补偿，
            // 否则消息随后被消费会造成订单与库存不一致。保留 PENDING 让前端继续轮询。
            if (publishFailure instanceof TimeoutException) {
                return OrderResultView.pending();
            }
            compensateReservation(userId, itemId, "订单消息发送失败，请重试");
            return new OrderResultView(4, null, "订单消息发送失败，请重试");
        }
    }

    public OrderResultView result(Long userId, Long itemId) {
        String value = redis.opsForValue().get(resultKey(userId, itemId));
        if (value == null || "PENDING".equals(value)) return OrderResultView.pending();
        if (value.startsWith("SUCCESS:")) return new OrderResultView(1, value.substring(8), "抢购成功");
        return new OrderResultView(4, null, value.startsWith("FAILED:") ? value.substring(7) : value);
    }

    @RabbitListener(queues = RabbitTopology.COMP_QUEUE)
    public void compensate(SeckillStockCompensationEvent event) {
        String marker = "seckill:compensated:" + event.eventId();
        if (Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(marker, "1", Duration.ofDays(7)))) {
            compensateReservation(event.userId(), event.itemId(), event.reason());
        }
    }

    private void compensateReservation(Long userId, Long itemId, String reason) {
        redis.execute(compensateScript,
                List.of(STOCK + itemId, USERS + itemId, resultKey(userId, itemId)),
                userId.toString(), reason);
    }

    /** 秒杀请求只在缓存不存在时初始化，避免并发请求把已扣库存覆盖回数据库值。 */
    public void warmIfAbsent(Item item) {
        redis.opsForValue().setIfAbsent(STOCK + item.id(), String.valueOf(item.stock()));
    }

    /** 管理员主动预热时覆盖库存并清理购买标记，应用最新配置。 */
    public void forceWarm(Item item) {
        redis.opsForValue().set(STOCK + item.id(), String.valueOf(item.stock()));
        redis.delete(USERS + item.id());
    }

    private String resultKey(Long userId, Long itemId) {
        return "seckill:result:" + userId + ":" + itemId;
    }

    private void assertActive(Item item) {
        LocalDateTime now = LocalDateTime.now();
        if (now.isBefore(item.activityStartTime())) throw new IllegalStateException("活动尚未开始");
        if (now.isAfter(item.activityEndTime())) throw new IllegalStateException("活动已经结束");
    }
}
