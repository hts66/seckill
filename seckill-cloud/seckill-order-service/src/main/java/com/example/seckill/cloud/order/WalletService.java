package com.example.seckill.cloud.order;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 虚拟零钱钱包（演示环境替代真实支付渠道）：
 *  - 首次访问自动开户并赠送初始余额；
 *  - 用户可在个人订单页随意设置余额（仅教学/演示用途）；
 *  - 下单支付走余额原子扣款，退款/取消按订单状态机原路退回；
 *  - 所有变动记 wallet_transactions 流水。
 */
@Service
public class WalletService {
    private static final Logger log = LoggerFactory.getLogger(WalletService.class);

    private final JdbcClient jdbc;
    private final BigDecimal initBalance;
    private final BigDecimal maxBalance;

    public WalletService(JdbcClient jdbc,
                         @Value("${app.wallet.init-balance:10000.00}") BigDecimal initBalance,
                         @Value("${app.wallet.max-balance:99999999.99}") BigDecimal maxBalance) {
        this.jdbc = jdbc;
        this.initBalance = initBalance;
        this.maxBalance = maxBalance;
    }

    public record WalletView(BigDecimal balance) {}
    public record PayResult(BigDecimal balance) {}
    public record TxView(Long id, String orderNo, String type, BigDecimal amount,
                         BigDecimal balanceAfter, String remark, LocalDateTime createdAt) {}

    /** 查余额；不存在则自动开户赠送初始金。 */
    public WalletView getOrCreate(Long userId) {
        return new WalletView(ensureWallet(userId));
    }

    public List<TxView> transactions(Long userId) {
        return jdbc.sql("SELECT id,order_no,type,amount,balance_after,remark,created_at " +
                        "FROM wallet_transactions WHERE user_id=:u ORDER BY id DESC LIMIT 100")
                .param("u", userId).query(TxView.class).list();
    }

    /** 演示功能：用户把余额直接改成任意值（0 ~ 上限），差额记 ADJUST 流水。 */
    @Transactional
    public WalletView setBalance(Long userId, BigDecimal target) {
        if (target == null || target.signum() < 0 || target.compareTo(maxBalance) > 0) {
            throw new IllegalArgumentException("余额必须在 0 ~ " + maxBalance.toPlainString() + " 之间");
        }
        BigDecimal current = ensureWallet(userId);
        BigDecimal delta = target.subtract(current);
        if (delta.signum() == 0) return new WalletView(current);
        int changed = jdbc.sql("UPDATE user_wallets SET balance=:b WHERE user_id=:u AND balance=:old")
                .param("b", target).param("u", userId).param("old", current).update();
        if (changed == 0) {
            // 并发修改：以库里现值再算一次，保证不丢更新
            current = balanceOf(userId);
            delta = target.subtract(current);
            jdbc.sql("UPDATE user_wallets SET balance=:b WHERE user_id=:u")
                    .param("b", target).param("u", userId).update();
        }
        recordTx(userId, null, "ADJUST", delta, target, "自助设置余额");
        return new WalletView(target);
    }

    /**
     * 余额支付：钱包原子扣款（余额不足 affected=0）→ 订单 CAS 待支付→已支付。
     * 订单翻转失败时立刻把钱退回去，任何异常都由事务回滚。
     */
    @Transactional
    public PayResult pay(Long userId, String orderNo) {
        OrderBrief order = jdbc.sql("SELECT order_no,user_id,amount,status FROM seckill_orders " +
                        "WHERE order_no=:n AND user_id=:u")
                .param("n", orderNo).param("u", userId).query(OrderBrief.class).optional()
                .orElseThrow(() -> new IllegalArgumentException("订单不存在或不属于当前用户"));
        if (order.status() != 0) throw new IllegalStateException("订单不是待支付状态");

        BigDecimal balance = ensureWallet(userId);
        if (balance.compareTo(order.amount()) < 0) {
            throw new IllegalStateException("零钱余额不足，当前余额 ￥" + balance.toPlainString()
                    + "，可在订单页自行设置余额后重试");
        }
        int deducted = jdbc.sql("UPDATE user_wallets SET balance=balance-:amt WHERE user_id=:u AND balance>=:amt")
                .param("amt", order.amount()).param("u", userId).update();
        if (deducted == 0) throw new IllegalStateException("零钱余额不足");

        int flipped = jdbc.sql("UPDATE seckill_orders SET status=1," +
                        "fulfillment_status=CASE WHEN receiver_name IS NULL THEN 0 ELSE 1 END,pay_time=NOW() " +
                        "WHERE order_no=:n AND user_id=:u AND status=0")
                .param("n", orderNo).param("u", userId).update();
        if (flipped == 0) {
            // 订单已被超时关单等路径改变：退款补偿，交由事务提交
            addBalance(userId, order.amount());
            recordTx(userId, orderNo, "REFUND", order.amount(), balanceOf(userId), "支付时订单已关闭，自动退回");
            throw new IllegalStateException("订单已关闭，零钱已退回");
        }
        BigDecimal after = balanceOf(userId);
        recordTx(userId, orderNo, "PAY", order.amount().negate(), after, "订单支付");
        log.info("wallet pay ok orderNo={} userId={} amount={} balance={}", orderNo, userId, order.amount(), after);
        return new PayResult(after);
    }

    /** 已支付订单退款：余额加回并记流水。由订单状态 CAS（1→5）成功后调用。 */
    @Transactional
    public void refund(Long userId, String orderNo, BigDecimal amount) {
        BigDecimal after = addBalance(userId, amount);
        recordTx(userId, orderNo, "REFUND", amount, after, "订单退款");
        log.info("wallet refund ok orderNo={} userId={} amount={} balance={}", orderNo, userId, amount, after);
    }

    private BigDecimal addBalance(Long userId, BigDecimal amount) {
        ensureWallet(userId);
        jdbc.sql("UPDATE user_wallets SET balance=balance+:amt WHERE user_id=:u")
                .param("amt", amount).param("u", userId).update();
        return balanceOf(userId);
    }

    private BigDecimal ensureWallet(Long userId) {
        BigDecimal balance = balanceOf(userId);
        if (balance != null) return balance;
        try {
            jdbc.sql("INSERT INTO user_wallets(user_id,balance) VALUES(:u,:b)")
                    .param("u", userId).param("b", initBalance).update();
            log.info("wallet opened userId={} initBalance={}", userId, initBalance);
            return initBalance;
        } catch (DuplicateKeyException concurrent) {
            return balanceOf(userId);
        }
    }

    private BigDecimal balanceOf(Long userId) {
        return jdbc.sql("SELECT balance FROM user_wallets WHERE user_id=:u")
                .param("u", userId).query(BigDecimal.class).optional().orElse(null);
    }

    private void recordTx(Long userId, String orderNo, String type, BigDecimal amount,
                          BigDecimal balanceAfter, String remark) {
        jdbc.sql("INSERT INTO wallet_transactions(user_id,order_no,type,amount,balance_after,remark) " +
                        "VALUES(:u,:n,:t,:a,:after,:r)")
                .param("u", userId).param("n", orderNo).param("t", type)
                .param("a", amount).param("after", balanceAfter).param("r", remark).update();
    }

    private record OrderBrief(String orderNo, Long userId, BigDecimal amount, Integer status) {}
}
