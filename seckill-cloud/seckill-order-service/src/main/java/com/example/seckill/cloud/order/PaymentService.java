package com.example.seckill.cloud.order;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * 支付服务（模拟版）：
 * 暂不接入支付宝沙箱。点击「立即支付」直接把订单标记为已支付；
 * 取消/退款也简化为纯数据库状态流转，不做真实资金操作。
 * 后续如需恢复真实支付，重新引入 alipay-sdk 并在此类实现即可。
 */
@Service
public class PaymentService {
    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    private final JdbcClient jdbc;

    public PaymentService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** 模拟支付：仅允许本人待支付订单，直接把状态置为已支付。 */
    String pay(Long userId, String orderNo) {
        int changed = jdbc.sql("UPDATE seckill_orders SET status=1," +
                        "fulfillment_status=CASE WHEN receiver_name IS NULL THEN 0 ELSE 1 END,pay_time=NOW() " +
                        "WHERE order_no=:n AND user_id=:u AND status=0")
                .param("n", orderNo).param("u", userId).update();
        if (changed == 0) {
            throw new IllegalStateException("订单不存在、不属于当前用户或不在待支付状态");
        }
        log.info("模拟支付成功 orderNo={} userId={}", orderNo, userId);
        return "支付成功";
    }

    /**
     * 取消与退款合一（模拟）：
     * 待支付 → 直接取消；已支付未发货 → 直接标记为已退款，不做真实资金操作。
     */
    String cancelOrRefund(Long userId, String orderNo) {
        Integer status = jdbc.sql("SELECT status FROM seckill_orders WHERE order_no=:n AND user_id=:u")
                .param("n", orderNo).param("u", userId).query(Integer.class).optional()
                .orElseThrow(() -> new IllegalArgumentException("订单不存在"));
        if (status == 0) {
            jdbc.sql("UPDATE seckill_orders SET status=2,cancel_time=NOW() " +
                            "WHERE order_no=:n AND user_id=:u AND status=0")
                    .param("n", orderNo).param("u", userId).update();
            return "取消成功";
        }
        if (status != 1) throw new IllegalStateException("订单已取消或已退款，无需再次操作");

        Integer fulfillment = jdbc.sql("SELECT fulfillment_status FROM seckill_orders WHERE order_no=:n AND user_id=:u")
                .param("n", orderNo).param("u", userId).query(Integer.class).optional().orElse(0);
        if (fulfillment >= 2) throw new IllegalStateException("订单已发货，不能退款");

        jdbc.sql("UPDATE seckill_orders SET status=5,cancel_time=NOW() " +
                        "WHERE order_no=:n AND user_id=:u AND status=1")
                .param("n", orderNo).param("u", userId).update();
        return "退款成功";
    }

    /** 管理员代取消/退款：先按订单号定位归属用户，再复用用户侧取消退款逻辑。 */
    String adminCancelOrRefund(String orderNo) {
        Long userId = jdbc.sql("SELECT user_id FROM seckill_orders WHERE order_no=:n")
                .param("n", orderNo).query(Long.class).optional()
                .orElseThrow(() -> new IllegalArgumentException("订单不存在"));
        return cancelOrRefund(userId, orderNo);
    }
}
