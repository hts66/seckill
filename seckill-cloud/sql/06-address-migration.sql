USE seckill_order;

CREATE TABLE IF NOT EXISTS user_addresses (
 id BIGINT PRIMARY KEY AUTO_INCREMENT,
 user_id BIGINT NOT NULL,
 receiver_name VARCHAR(50) NOT NULL,
 receiver_phone VARCHAR(20) NOT NULL,
 province VARCHAR(50) NOT NULL,
 city VARCHAR(50) NOT NULL,
 district VARCHAR(50) NOT NULL,
 detail VARCHAR(255) NOT NULL,
 is_default TINYINT(1) NOT NULL DEFAULT 0,
 created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
 KEY idx_address_user(user_id),
 KEY idx_address_user_default(user_id,is_default)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

SET @schema_name = DATABASE();

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=@schema_name AND TABLE_NAME='seckill_orders' AND COLUMN_NAME='fulfillment_status')=0,
 'ALTER TABLE seckill_orders ADD COLUMN fulfillment_status TINYINT NOT NULL DEFAULT 0 AFTER status', 'SELECT 1'); PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=@schema_name AND TABLE_NAME='seckill_orders' AND COLUMN_NAME='shipping_time')=0,
 'ALTER TABLE seckill_orders ADD COLUMN shipping_time DATETIME NULL AFTER cancel_time', 'SELECT 1'); PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=@schema_name AND TABLE_NAME='seckill_orders' AND COLUMN_NAME='address_id')=0,
 'ALTER TABLE seckill_orders ADD COLUMN address_id BIGINT NULL AFTER status', 'SELECT 1'); PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=@schema_name AND TABLE_NAME='seckill_orders' AND COLUMN_NAME='receiver_name')=0,
 'ALTER TABLE seckill_orders ADD COLUMN receiver_name VARCHAR(50) NULL AFTER address_id', 'SELECT 1'); PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=@schema_name AND TABLE_NAME='seckill_orders' AND COLUMN_NAME='receiver_phone')=0,
 'ALTER TABLE seckill_orders ADD COLUMN receiver_phone VARCHAR(20) NULL AFTER receiver_name', 'SELECT 1'); PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=@schema_name AND TABLE_NAME='seckill_orders' AND COLUMN_NAME='receiver_province')=0,
 'ALTER TABLE seckill_orders ADD COLUMN receiver_province VARCHAR(50) NULL AFTER receiver_phone', 'SELECT 1'); PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=@schema_name AND TABLE_NAME='seckill_orders' AND COLUMN_NAME='receiver_city')=0,
 'ALTER TABLE seckill_orders ADD COLUMN receiver_city VARCHAR(50) NULL AFTER receiver_province', 'SELECT 1'); PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=@schema_name AND TABLE_NAME='seckill_orders' AND COLUMN_NAME='receiver_district')=0,
 'ALTER TABLE seckill_orders ADD COLUMN receiver_district VARCHAR(50) NULL AFTER receiver_city', 'SELECT 1'); PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=@schema_name AND TABLE_NAME='seckill_orders' AND COLUMN_NAME='receiver_detail')=0,
 'ALTER TABLE seckill_orders ADD COLUMN receiver_detail VARCHAR(255) NULL AFTER receiver_district', 'SELECT 1'); PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

UPDATE seckill_orders
   SET fulfillment_status=1
 WHERE id > 0 AND status=1 AND fulfillment_status=0 AND receiver_name IS NOT NULL
   AND receiver_phone IS NOT NULL AND receiver_detail IS NOT NULL;
