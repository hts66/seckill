package com.example.seckill.cloud.order;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.core.simple.JdbcClient.StatementSpec;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 订单状态机收口：用户取消、管理员取消/退款、超时自动关单、确认收货。
 * 状态约定：status 0 待支付 / 1 已支付 / 2 用户取消 / 3 超时取消 / 5 已退款；
 * fulfillment_status 0 待处理 / 1 待发货 / 2 已发货 / 3 已完成(已收货)。
 * 库存回补只跟随「待支付 → 取消」发生；退款只跟随「已支付未发货 → 已退款」发生，全部靠 CAS 保证幂等。
 */
@Service
public class OrderLifecycleService {
    private static final Logger log = LoggerFactory.getLogger(OrderLifecycleService.class);

    private final JdbcClient jdbc;
    private final WalletService wallet;
    private final StockReleaser stockReleaser;
    private final long payTimeoutSeconds;

    public OrderLifecycleService(JdbcClient jdbc, WalletService wallet, StockReleaser stockReleaser,
                                 @Value("${app.order.pay-timeout-seconds:900}") long payTimeoutSeconds) {
        this.jdbc = jdbc;
        this.wallet = wallet;
        this.stockReleaser = stockReleaser;
        this.payTimeoutSeconds = payTimeoutSeconds;
    }

    /** 用户取消/退款合一：待支付直接取消并回补库存；已支付未发货则零钱原路退回。 */
    @Transactional
    public String cancelByUser(Long userId, String orderNo) {
        OrderBrief order = requireOrder("SELECT order_no,user_id,item_id,amount,status,fulfillment_status " +
                "FROM seckill_orders WHERE order_no=:n AND user_id=:u", orderNo, userId);
        return cancel(order);
    }

    /** 管理员版：不校验归属。 */
    @Transactional
    public String cancelByAdmin(String orderNo) {
        OrderBrief order = requireOrder("SELECT order_no,user_id,item_id,amount,status,fulfillment_status " +
                "FROM seckill_orders WHERE order_no=:n", orderNo, null);
        return cancel(order);
    }

    private String cancel(OrderBrief order) {
        if (order.status() == 0) {
            int changed = jdbc.sql("UPDATE seckill_orders SET status=2,cancel_time=NOW() " +
                            "WHERE order_no=:n AND status=0")
                    .param("n", order.orderNo()).update();
            if (changed == 0) throw new IllegalStateException("订单状态已变化，请刷新后重试");
            long remaining = stockReleaser.release(order.itemId(), order.userId());
            log.info("order cancelled orderNo={} userId={} stockReleased={}", order.orderNo(), order.userId(), remaining);
            return "取消成功";
        }
        if (order.status() != 1) throw new IllegalStateException("订单已取消或已退款，无需再次操作");
        if (order.fulfillmentStatus() >= 2) throw new IllegalStateException("订单已发货，不能退款");

        // 先退钱再翻状态；状态 CAS 失败（并发/已发货）时事务回滚，退款一并撤销
        wallet.refund(order.userId(), order.orderNo(), order.amount());
        int changed = jdbc.sql("UPDATE seckill_orders SET status=5,cancel_time=NOW() " +
                        "WHERE order_no=:n AND status=1 AND fulfillment_status<2")
                .param("n", order.orderNo()).update();
        if (changed == 0) throw new IllegalStateException("订单状态已变化，退款未执行");
        return "退款成功，款项已退回零钱余额";
    }

    /** 延迟消息到期：仍待支付则超时关单并回补库存；已支付则忽略，天然幂等。 */
    @Transactional
    public void autoClose(OrderCloseMessage message) {
        int changed = jdbc.sql("UPDATE seckill_orders SET status=3,cancel_time=NOW() " +
                        "WHERE order_no=:n AND status=0")
                .param("n", message.orderNo()).update();
        if (changed == 0) return;
        long remaining = stockReleaser.release(message.itemId(), message.userId());
        log.info("order auto-closed by timeout orderNo={} userId={} stockReleased={}",
                message.orderNo(), message.userId(), remaining);
    }

    /** 用户确认收货：已发货 → 已完成。 */
    @Transactional
    public void confirmReceipt(Long userId, String orderNo) {
        int changed = jdbc.sql("UPDATE seckill_orders SET fulfillment_status=3,finish_time=NOW() " +
                        "WHERE order_no=:n AND user_id=:u AND status=1 AND fulfillment_status=2")
                .param("n", orderNo).param("u", userId).update();
        if (changed == 0) throw new IllegalStateException("订单未发货或已确认收货");
        log.info("order receipt confirmed orderNo={} userId={}", orderNo, userId);
    }

    /**
     * MQ 消息丢失兜底：每 5 分钟扫一次超过支付时限仍是待支付的订单直接关单。
     * 多实例并发扫描也安全——CAS 保证只有一个实例实际关单并回补库存。
     */
    @Scheduled(fixedDelay = 300_000, initialDelay = 60_000)
    public void sweepTimeoutOrders() {
        List<OrderCloseMessage> expired = jdbc.sql("SELECT order_no,user_id,item_id FROM seckill_orders " +
                        "WHERE status=0 AND created_at < :deadline ORDER BY id LIMIT 100")
                .param("deadline", LocalDateTime.now().minusSeconds(payTimeoutSeconds))
                .query((rs, i) -> new OrderCloseMessage(
                        rs.getString("order_no"), rs.getLong("user_id"), rs.getLong("item_id")))
                .list();
        for (OrderCloseMessage m : expired) {
            try {
                autoClose(m);
            } catch (RuntimeException e) {
                log.warn("sweep auto-close failed orderNo={}: {}", m.orderNo(), e.toString());
            }
        }
    }

    private OrderBrief requireOrder(String sql, String orderNo, Long userId) {
        JdbcClient.StatementSpec spec = jdbc.sql(sql).param("n", orderNo);
        if (userId != null) spec = spec.param("u", userId);
        return spec.query(OrderBrief.class).optional()
                .orElseThrow(() -> new IllegalArgumentException("订单不存在"));
    }

    private record OrderBrief(String orderNo, Long userId, Long itemId, BigDecimal amount,
                              Integer status, Integer fulfillmentStatus) {}
}
