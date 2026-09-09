package com.example.seckill.cloud.activity;

import com.example.seckill.cloud.api.*;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Service
public class SeckillEngine {
    private static final Logger log = LoggerFactory.getLogger(SeckillEngine.class);
    private static final String STOCK = "seckill:stock:";
    private static final String USERS = "seckill:users:";
    /** 回执迟到多久后交给兜底任务接管，以及同一条消息最多发几次。 */
    private static final long UNCONFIRMED_AGE_MS = 30_000L;
    private static final int MAX_PUBLISH_ATTEMPTS = 3;
    private final JdbcClient jdbc;
    private final StringRedisTemplate redis;
    private final RabbitTemplate rabbit;
    /** confirm 回调跑在 AMQP IO 线程上，补偿与重发必须转交出来，避免拖慢整条连接。 */
    private final ScheduledExecutorService confirmWorker = Executors.newScheduledThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "seckill-confirm");
        thread.setDaemon(true);
        return thread;
    });
    private final DefaultRedisScript<Long> deductScript = new DefaultRedisScript<>("""
            local stock = redis.call('GET', KEYS[1])
            if not stock or tonumber(stock) <= 0 then return -1 end
            if redis.call('SISMEMBER', KEYS[2], ARGV[1]) == 1 then return -2 end
            redis.call('DECR', KEYS[1])
            redis.call('SADD', KEYS[2], ARGV[1])
            return 1
            """, Long.class);
    private final DefaultRedisScript<Long> compensateScript = new DefaultRedisScript<>("""
            local current = redis.call('GET', KEYS[3])
            if current and string.sub(current, 1, 8) == 'SUCCESS:' then return 0 end
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

    @PreDestroy
    void shutdown() {
        confirmWorker.shutdown();
    }

    public record Item(Long id, Long activityId, Long productId, String productName,
                       String productTitle, String productImages, BigDecimal originalPrice,
                       BigDecimal seckillPrice, Integer stock, Integer limitPerUser,
                       String activityName, String activityDescription,
                       LocalDateTime activityPreviewTime,
                       LocalDateTime activityStartTime, LocalDateTime activityEndTime) {}

    private static final String ITEM_SELECT = """
            SELECT i.id,i.activity_id,i.product_id,i.product_name,i.product_title,i.product_images,
                   i.original_price,i.seckill_price,i.stock,i.limit_per_user,
                   a.name activity_name,a.description activity_description,
                   a.preview_time activity_preview_time,
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
        // PENDING 必须先写：否则消费者可能已经写完 SUCCESS，被这里的 PENDING 覆盖。
        redis.opsForValue().set(resultKey, "PENDING", Duration.ofHours(2));
        SeckillCorrelation correlation = new SeckillCorrelation(
                new SeckillOrderCreatedEvent(eventId, userId, itemId, item.activityId(),
                        // 地址属于抢购成功后的履约流程，秒杀消息不依赖地址。
                        item.seckillPrice(), null, LocalDateTime.now()),
                userId, itemId, 1);
        try {
            // 只发送不等待回执：confirm 由 AMQP IO 线程送达并在回调里处理，请求线程立刻返回。
            // 前端本来就在轮询 result，投递失败会在下一次轮询变成 status=4。
            publish(correlation);
        } catch (RuntimeException sendFailure) {
            // 发送本身抛异常（channel 检出超时、连接不可用）说明消息肯定没进 broker，
            // 既不会有回执也不会进 getUnconfirmed，必须就地补偿，否则库存永远悬着。
            log.error("Seckill order message send failed eventId={}: {}", eventId, sendFailure.toString());
            compensateReservation(userId, itemId, "系统繁忙，请重试");
            return new OrderResultView(4, null, "系统繁忙，请重试");
        }
        return OrderResultView.pending();
    }

    /**
     * 发送秒杀订单消息并挂上回执回调，不阻塞请求线程。
     *
     * <p>ack 且未 return 表示消息已落盘进队列，必被消费，无需处理。其余情况交给
     * {@link #handleUndelivered} 判断是重发还是补偿。
     */
    private void publish(SeckillCorrelation correlation) {
        rabbit.convertAndSend(RabbitTopology.EXCHANGE, RabbitTopology.ORDER_KEY,
                correlation.event, correlation);
        correlation.getFuture().whenComplete((confirm, error) -> {
            if (error == null && confirm != null && confirm.isAck() && correlation.getReturned() == null) return;
            String reason = error != null ? error.toString()
                    : confirm == null ? "no confirm" : String.valueOf(confirm.getReason());
            // 回调跑在 AMQP IO 线程上，Redis 与重发都必须转交出去，避免拖慢整条连接的收发。
            confirmWorker.execute(() -> handleUndelivered(correlation, correlation.getReturned() != null, reason));
        });
    }

    /**
     * 投递未确认成功时的处置。
     *
     * <p>basic.return 是唯一的确定信号：broker 收下了但路由不到任何队列，消息不会被任何消费者
     * 处理，可以安全补偿。单纯的 nack 则是歧义的——它既可能是 broker 真的拒收，也可能是连接中断时
     * Spring 为未决 ack 生成的合成 nack，而后者消息可能已经落盘。此时补偿会退回库存，之后消费者又
     * 把订单建出来，造成超卖。消费者按 eventId 幂等（唯一键 uk_event 兜底），因此重发无害、补偿有害，
     * 优先重发，重试耗尽才补偿。
     */
    private void handleUndelivered(SeckillCorrelation correlation, boolean unroutable, String reason) {
        if (unroutable) {
            log.error("Seckill order message unroutable, compensating eventId={}, reason={}",
                    correlation.event.eventId(), reason);
            compensateReservation(correlation.userId, correlation.itemId, "订单消息发送失败，请重试");
            return;
        }
        if (correlation.attempt >= MAX_PUBLISH_ATTEMPTS) {
            log.error("Seckill order message unconfirmed after {} attempts, compensating eventId={}, reason={}",
                    MAX_PUBLISH_ATTEMPTS, correlation.event.eventId(), reason);
            compensateReservation(correlation.userId, correlation.itemId, "订单消息发送失败，请重试");
            return;
        }
        log.warn("Seckill order message unconfirmed, republishing eventId={}, attempt={}, reason={}",
                correlation.event.eventId(), correlation.attempt + 1, reason);
        republish(correlation, 500L * correlation.attempt);
    }

    /** 用同一个 eventId 重发；发送本身失败（broker 不可达）时退回补偿，避免 reservation 永远悬着。 */
    private void republish(SeckillCorrelation previous, long delayMs) {
        SeckillCorrelation next = new SeckillCorrelation(
                previous.event, previous.userId, previous.itemId, previous.attempt + 1);
        confirmWorker.schedule(() -> {
            try {
                publish(next);
            } catch (RuntimeException sendFailure) {
                log.error("Republish failed eventId={}", next.event.eventId(), sendFailure);
                compensateReservation(next.userId, next.itemId, "订单消息发送失败，请重试");
            }
        }, delayMs, TimeUnit.MILLISECONDS);
    }

    /**
     * 回执始终没到达的兜底：broker 触发流控或整体卡住时，channel 仍然打开，future 既不 ack 也不 nack，
     * 上面的回调永远不会触发。这里扫出超过 {@value #UNCONFIRMED_AGE_MS} 毫秒仍未确认的消息接管处理。
     */
    @Scheduled(fixedDelay = UNCONFIRMED_AGE_MS)
    void sweepUnconfirmed() {
        // 没有待确认消息时返回 null，不是空集合。
        Collection<CorrelationData> unconfirmed = rabbit.getUnconfirmed(UNCONFIRMED_AGE_MS);
        if (unconfirmed == null) return;
        for (CorrelationData data : unconfirmed) {
            if (!(data instanceof SeckillCorrelation stale)) continue;
            // 已不是 PENDING 说明消费者早已处理，这条其实送达了，放过。
            if (!"PENDING".equals(redis.opsForValue().get(resultKey(stale.userId, stale.itemId)))) continue;
            handleUndelivered(stale, false, "confirm timed out");
        }
    }

    /** 把 event 和重发次数带在 CorrelationData 上，回调与兜底任务都能拿到上下文。 */
    private static final class SeckillCorrelation extends CorrelationData {
        private final SeckillOrderCreatedEvent event;
        private final Long userId;
        private final Long itemId;
        private final int attempt;

        private SeckillCorrelation(SeckillOrderCreatedEvent event, Long userId, Long itemId, int attempt) {
            super(event.eventId());
            this.event = event;
            this.userId = userId;
            this.itemId = itemId;
            this.attempt = attempt;
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
