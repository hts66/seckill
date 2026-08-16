-- ============================================================
-- 数据库迁移脚本：为预热功能添加新字段
-- 执行方式：mysql -u hts -p < sql/migrate.sql
-- ============================================================
USE seckill;

-- 1. products 表：添加 title 字段，image 重命名为 images 并改为 TEXT
ALTER TABLE products
    ADD COLUMN title VARCHAR(200) DEFAULT NULL COMMENT '商品标题/标语' AFTER name,
    CHANGE COLUMN image images TEXT DEFAULT NULL COMMENT '商品图片(JSON数组，MinIO URL列表)',
    MODIFY COLUMN description VARCHAR(2000) DEFAULT NULL COMMENT '商品描述';

-- 2. seckill_activities 表：添加 preview_time 字段
ALTER TABLE seckill_activities
    ADD COLUMN preview_time DATETIME NOT NULL COMMENT '预热展示时间' AFTER name,
    MODIFY COLUMN status TINYINT DEFAULT 0 COMMENT '0-未开始 1-预热中 2-进行中 3-已结束';

-- 3. 更新现有测试数据（如果存在）
UPDATE seckill_activities
SET preview_time = start_time,
    status = 1
WHERE preview_time IS NULL OR preview_time = '0000-00-00 00:00:00';

-- 验证
SELECT 'products 表结构' AS info;
SHOW COLUMNS FROM products;
SELECT 'seckill_activities 表结构' AS info;
SHOW COLUMNS FROM seckill_activities;
