-- 虚拟钱包：替代沙箱支付的演示方案。用户余额可自行设置，下单用余额支付，取消/退款原路退回。
USE seckill_order;

-- 自愈：早期版本主键误命名为 id，与代码 user_id 不一致；无业务数据直接重建。
SET @wrong := (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA='seckill_order' AND TABLE_NAME='user_wallets' AND COLUMN_NAME='id');
SET @sql := IF(@wrong>0, 'DROP TABLE user_wallets', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

CREATE TABLE IF NOT EXISTS user_wallets(
 user_id BIGINT PRIMARY KEY COMMENT '与用户 id 相同',
 balance DECIMAL(12,2) NOT NULL DEFAULT 0,
 updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 余额变动流水：PAY 支付扣款(负) / REFUND 退款入账(正) / ADJUST 自助设置(差值，可正可负)
CREATE TABLE IF NOT EXISTS wallet_transactions(
 id BIGINT PRIMARY KEY AUTO_INCREMENT,
 user_id BIGINT NOT NULL,
 order_no VARCHAR(64),
 type VARCHAR(16) NOT NULL,
 amount DECIMAL(12,2) NOT NULL COMMENT '带符号变动额',
 balance_after DECIMAL(12,2) NOT NULL,
 remark VARCHAR(255),
 created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 KEY idx_wallet_txn_user(user_id,id),
 KEY idx_wallet_txn_order(order_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 订单增加确认收货时间（状态机终态：fulfillment_status=3）
SET @col := (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA='seckill_order' AND TABLE_NAME='seckill_orders' AND COLUMN_NAME='finish_time');
SET @sql := IF(@col=0,
  'ALTER TABLE seckill_orders ADD finish_time DATETIME NULL AFTER shipping_time',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
