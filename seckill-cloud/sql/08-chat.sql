-- 订单客服单聊：一个订单一条会话，消息持久化，WebSocket 实时推送。
-- sender_type: 0-用户 1-客服 ; status: 0-进行中 1-已关闭
USE seckill_order;

CREATE TABLE IF NOT EXISTS chat_conversation(
 id BIGINT PRIMARY KEY AUTO_INCREMENT,
 order_no VARCHAR(64) NOT NULL COMMENT '关联订单号，一个订单最多一条会话',
 user_id BIGINT NOT NULL,
 user_email VARCHAR(128) COMMENT '下单用户邮箱，客服列表展示用',
 status TINYINT NOT NULL DEFAULT 0,
 unread_user INT NOT NULL DEFAULT 0 COMMENT '用户未读数（客服回复后累加）',
 unread_admin INT NOT NULL DEFAULT 0 COMMENT '客服未读数（用户发言后累加）',
 last_content VARCHAR(1000),
 last_at DATETIME,
 created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 UNIQUE KEY uk_chat_order(order_no),
 KEY idx_chat_user(user_id),
 KEY idx_chat_last(last_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS chat_message(
 id BIGINT PRIMARY KEY AUTO_INCREMENT,
 conversation_id BIGINT NOT NULL,
 sender_id BIGINT NOT NULL,
 sender_type TINYINT NOT NULL COMMENT '0-用户 1-客服 2-系统',
 content VARCHAR(1000) NOT NULL,
 created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 KEY idx_chat_msg_conv(conversation_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
