-- 支付流水表：与订单解耦，先落 PAYING 再发起支付，回调/查单后按状态机更新。
-- status: 0-PAYING 1-PAID 2-CLOSED 3-REFUNDED
USE seckill_order;

CREATE TABLE IF NOT EXISTS payments(
 id BIGINT PRIMARY KEY AUTO_INCREMENT,
 out_trade_no VARCHAR(64) NOT NULL COMMENT '商户支付流水号，同一订单重复点击支付会产生新流水',
 order_no VARCHAR(64) NOT NULL,
 user_id BIGINT NOT NULL,
 amount DECIMAL(10,2) NOT NULL COMMENT '下单时金额快照，回调必须与之相等',
 channel VARCHAR(20) NOT NULL DEFAULT 'ALIPAY_SANDBOX',
 trade_no VARCHAR(64) COMMENT '支付宝交易号',
 status TINYINT NOT NULL DEFAULT 0,
 created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 paid_at DATETIME,
 closed_at DATETIME,
 refunded_at DATETIME,
 UNIQUE KEY uk_out_trade_no(out_trade_no),
 KEY idx_payment_order(order_no),
 KEY idx_payment_pending(status,created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
