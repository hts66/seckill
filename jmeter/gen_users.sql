-- 批量生成1000测试用户 + CSV
-- 步骤1: 使用本地数据库客户端和环境变量中的凭据执行此脚本。
-- 步骤2: 文件 jmeter/users.csv 已自动生成至 /tmp/users.csv

USE seckill;

DROP PROCEDURE IF EXISTS gen_test_users;

DELIMITER //
CREATE PROCEDURE gen_test_users()
BEGIN
    DECLARE i INT DEFAULT 1;
    DECLARE pwd VARCHAR(255) DEFAULT '$2a$10$N.zmdr9k7uOCQb376NoUnuTJ8iAt6Z5EHsM8lE9lBOsl7iAt6Z5Eh';
    WHILE i <= 1000 DO
        INSERT IGNORE INTO users (email, password, username, status)
        VALUES (CONCAT('test', i, '@test.com'), pwd, CONCAT('user', i), 1);
        SET i = i + 1;
    END WHILE;
END//
DELIMITER ;

CALL gen_test_users();

SELECT COUNT(*) AS total_users FROM users WHERE email LIKE '%@test.com';
