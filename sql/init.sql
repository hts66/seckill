-- 创建数据库
CREATE DATABASE IF NOT EXISTS seckill DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE seckill;

-- 1. 用户表
CREATE TABLE users (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    email VARCHAR(100) NOT NULL UNIQUE,
    password VARCHAR(255) NOT NULL,
    username VARCHAR(50) NOT NULL,
    phone VARCHAR(20) DEFAULT NULL,
    avatar VARCHAR(500) DEFAULT NULL,
    role TINYINT NOT NULL DEFAULT 0 COMMENT '0-普通用户，1-管理员',
    status TINYINT DEFAULT 1 COMMENT '1-正常 0-禁用',
    email_verified TINYINT(1) NOT NULL DEFAULT 0 COMMENT '邮箱是否验证',
    token_version INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '令牌版本',
    password_changed_at DATETIME DEFAULT NULL,
    last_login_at DATETIME DEFAULT NULL,
    last_login_ip VARCHAR(45) DEFAULT NULL,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted_at DATETIME DEFAULT NULL,
    INDEX idx_email (email),
    INDEX idx_users_status_role (status, role)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户表';

-- 2. 商品表（普通商品）
CREATE TABLE products (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    name VARCHAR(200) NOT NULL COMMENT '商品名称',
    title VARCHAR(200) DEFAULT NULL COMMENT '商品标题/标语',
    description VARCHAR(2000) DEFAULT NULL COMMENT '商品描述',
    images TEXT DEFAULT NULL COMMENT '商品图片(JSON数组，MinIO URL列表)',
    price DECIMAL(10,2) NOT NULL COMMENT '原价',
    stock INT DEFAULT 0 COMMENT '普通库存',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商品表';

-- 3. 秒杀活动表
CREATE TABLE seckill_activities (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    name VARCHAR(200) NOT NULL COMMENT '活动名称',
    preview_time DATETIME NOT NULL COMMENT '预热展示时间（用户可看到倒计时）',
    start_time DATETIME NOT NULL COMMENT '开抢时间（秒杀开始）',
    end_time DATETIME NOT NULL COMMENT '结束时间',
    status TINYINT DEFAULT 0 COMMENT '0-未开始 1-进行中 2-已结束',
    description VARCHAR(500) DEFAULT NULL,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_time (preview_time, start_time, end_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='秒杀活动表';

-- 4. 秒杀商品表（活动关联商品）
CREATE TABLE seckill_items (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    activity_id BIGINT NOT NULL COMMENT '活动ID',
    product_id BIGINT NOT NULL COMMENT '商品ID',
    seckill_price DECIMAL(10,2) NOT NULL COMMENT '秒杀价格',
    stock INT NOT NULL COMMENT '秒杀库存',
    limit_per_user INT DEFAULT 1 COMMENT '每人限购数量',
    status TINYINT DEFAULT 1 COMMENT '0-下架 1-上架',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_activity (activity_id),
    INDEX idx_product (product_id),
    FOREIGN KEY (activity_id) REFERENCES seckill_activities(id) ON DELETE CASCADE,
    FOREIGN KEY (product_id) REFERENCES products(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='秒杀商品表';

-- 5. 秒杀订单表
CREATE TABLE seckill_orders (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL COMMENT '用户ID',
    item_id BIGINT NOT NULL COMMENT '秒杀商品ID',
    order_no VARCHAR(64) NOT NULL UNIQUE COMMENT '订单号',
    amount DECIMAL(10,2) NOT NULL COMMENT '实付金额',
    status TINYINT DEFAULT 0 COMMENT '0-待支付 1-已支付 2-已取消 3-超时取消',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    pay_time DATETIME DEFAULT NULL,
    cancel_time DATETIME DEFAULT NULL,
    UNIQUE KEY uk_user_item (user_id, item_id) COMMENT '防止一人多买',
    INDEX idx_user (user_id),
    INDEX idx_order_no (order_no),
    INDEX idx_item (item_id),
    FOREIGN KEY (item_id) REFERENCES seckill_items(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='秒杀订单表';

-- ============================================================
-- 測試数据
-- ============================================================

-- 测试用户（密码都是 123456 的 BCrypt 加密结果）
INSERT INTO users (email, password, username) VALUES
('test@qq.com', '$2a$10$rgyZJWHTIGrQUWTAUaLgDu/1IjryomnC.8KDGliBszMMy70a2/u0K', '测试用户'),
('admin@qq.com', '$2a$10$rgyZJWHTIGrQUWTAUaLgDu/1IjryomnC.8KDGliBszMMy70a2/u0K', '管理员');

UPDATE users SET role = 1, email_verified = 1 WHERE email = 'admin@qq.com';
UPDATE users SET email_verified = 1 WHERE email = 'test@qq.com';

-- 测试商品
INSERT INTO products (name, title, description, price, stock) VALUES
('iPhone 16 Pro Max', '旗舰直降', 'Apple 旗舰手机 256GB', 9999.00, 100),
('AirPods Pro 3', '耳机白菜价', 'Apple 降噪耳机', 1999.00, 200),
('MacBook Pro 14', '性能怪兽', 'M4 Pro 芯片 16GB+512GB', 14999.00, 50),
('iPad Air M4', '学习办公利器', '11寸 Liquid Retina 显示屏', 4799.00, 80),
('Apple Watch Ultra 3', '户外探险必备', '钛金属表壳 49mm', 6499.00, 60);

-- 测试秒杀活动（5分钟后展示预热，10分钟后开抢，持续2小时）
INSERT INTO seckill_activities (name, preview_time, start_time, end_time, status, description) VALUES
('618 数码狂欢节', DATE_ADD(NOW(), INTERVAL 5 MINUTE), DATE_ADD(NOW(), INTERVAL 10 MINUTE), DATE_ADD(NOW(), INTERVAL 2 HOUR), 0, '限时秒杀，手慢无！');

-- 测试秒杀商品（原价骨折价）
INSERT INTO seckill_items (activity_id, product_id, seckill_price, stock, limit_per_user, status) VALUES
(1, 1, 1.00,  10, 1, 1),  -- iPhone 1元秒杀
(1, 2, 0.01,  20, 1, 1),  -- AirPods 1分秒杀
(1, 3, 999.00, 5, 1, 1),  -- MacBook 999秒杀
(1, 4, 99.00,  15, 1, 1), -- iPad 99秒殺
(1, 5, 199.00, 10, 1, 1); -- Watch 199秒杀
