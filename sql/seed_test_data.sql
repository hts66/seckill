USE seckill;

-- Clear old data
DELETE FROM seckill_orders;
DELETE FROM seckill_items;
DELETE FROM seckill_activities;
ALTER TABLE seckill_activities AUTO_INCREMENT = 1;
ALTER TABLE seckill_items AUTO_INCREMENT = 1;

-- Products (skip if exist)
INSERT INTO products (id, name, title, description, price, stock) VALUES
(1, 'iPhone 16 Pro Max', 'qijian', 'Apple qijian 256GB', 9999.00, 100),
(2, 'AirPods Pro 3', 'erji', 'Apple jianzao', 1999.00, 200),
(3, 'MacBook Pro 14', 'xingneng', 'M4 Pro 16GB+512GB', 14999.00, 50)
ON DUPLICATE KEY UPDATE title=VALUES(title);

-- Activity (preview started 10min ago, seckill starts in 5min)
INSERT INTO seckill_activities (id, name, preview_time, start_time, end_time, status, description) VALUES
(1, '618 Test', DATE_ADD(NOW(), INTERVAL -10 MINUTE), DATE_ADD(NOW(), INTERVAL 5 MINUTE), DATE_ADD(NOW(), INTERVAL 2 HOUR), 0, 'flash sale!');

-- Seckill items
INSERT INTO seckill_items (activity_id, product_id, seckill_price, stock, limit_per_user, status) VALUES
(1, 1, 1.00, 10, 1, 1),
(1, 2, 0.01, 20, 1, 1),
(1, 3, 999.00, 5, 1, 1);

-- Verify
SELECT id, name, preview_time, start_time, end_time, status FROM seckill_activities;
SELECT si.id, si.activity_id, p.name, si.seckill_price, si.stock FROM seckill_items si JOIN products p ON si.product_id = p.id;
