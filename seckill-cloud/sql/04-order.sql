CREATE DATABASE IF NOT EXISTS seckill_order DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE seckill_order;

CREATE TABLE IF NOT EXISTS user_addresses (
 id BIGINT PRIMARY KEY AUTO_INCREMENT,user_id BIGINT NOT NULL,receiver_name VARCHAR(50) NOT NULL,
 receiver_phone VARCHAR(20) NOT NULL,province VARCHAR(50) NOT NULL,city VARCHAR(50) NOT NULL,
 district VARCHAR(50) NOT NULL,detail VARCHAR(255) NOT NULL,is_default TINYINT(1) NOT NULL DEFAULT 0,
 created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
 KEY idx_address_user(user_id),KEY idx_address_user_default(user_id,is_default)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS seckill_orders(
 id BIGINT PRIMARY KEY AUTO_INCREMENT,event_id VARCHAR(64) NOT NULL,user_id BIGINT NOT NULL,
 item_id BIGINT NOT NULL,activity_id BIGINT NOT NULL,order_no VARCHAR(64) NOT NULL,
 amount DECIMAL(10,2) NOT NULL,status TINYINT NOT NULL DEFAULT 0,
 fulfillment_status TINYINT NOT NULL DEFAULT 0,address_id BIGINT,
 receiver_name VARCHAR(50),receiver_phone VARCHAR(20),receiver_province VARCHAR(50),
 receiver_city VARCHAR(50),receiver_district VARCHAR(50),receiver_detail VARCHAR(255),
 created_at DATETIME DEFAULT CURRENT_TIMESTAMP,pay_time DATETIME,cancel_time DATETIME,shipping_time DATETIME,
 UNIQUE KEY uk_event(event_id),UNIQUE KEY uk_user_item(user_id,item_id),
 UNIQUE KEY uk_order_no(order_no),KEY idx_user(user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
