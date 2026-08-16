-- Back up the database before running this migration.
USE seckill;

ALTER TABLE users
    MODIFY COLUMN email VARCHAR(100) NOT NULL COMMENT '登录邮箱',
    MODIFY COLUMN password VARCHAR(255) NOT NULL COMMENT 'BCrypt密码摘要',
    MODIFY COLUMN username VARCHAR(50) NOT NULL COMMENT '用户昵称',
    MODIFY COLUMN avatar VARCHAR(500) DEFAULT NULL COMMENT '头像地址',
    ADD COLUMN role TINYINT NOT NULL DEFAULT 0 COMMENT '0-普通用户，1-管理员' AFTER avatar,
    MODIFY COLUMN status TINYINT NOT NULL DEFAULT 1 COMMENT '0-禁用，1-正常，2-已注销',
    ADD COLUMN email_verified TINYINT(1) NOT NULL DEFAULT 0 COMMENT '邮箱是否验证' AFTER status,
    ADD COLUMN token_version INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '令牌版本' AFTER email_verified,
    ADD COLUMN password_changed_at DATETIME DEFAULT NULL COMMENT '最后修改密码时间' AFTER token_version,
    ADD COLUMN last_login_at DATETIME DEFAULT NULL COMMENT '最后登录时间' AFTER password_changed_at,
    ADD COLUMN last_login_ip VARCHAR(45) DEFAULT NULL COMMENT '最后登录IP' AFTER last_login_at,
    ADD COLUMN deleted_at DATETIME DEFAULT NULL COMMENT '账号注销时间' AFTER updated_at;

UPDATE users SET email = LOWER(TRIM(email));
UPDATE users SET email_verified = 1 WHERE email_verified = 0;
UPDATE users SET role = 1 WHERE email = 'admin@qq.com';

ALTER TABLE users
    ADD INDEX idx_users_status_role (status, role),
    ADD INDEX idx_users_created_at (created_at);
