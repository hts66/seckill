package com.example.seckill.utils;

import org.springframework.util.DigestUtils;

import java.nio.charset.StandardCharsets;

/**
 * MD5 加密工具
 * 双重 MD5：前端MD5一次 → 后端再MD5一次(salt) → 存入数据库
 */
public class Md5Util {

    private static final String SALT = "seckillSalt@2024#!";

    /**
     * 前端 MD5（模拟浏览器端第一次加密）
     * 前端提交: MD5(原始密码)
     */
    public static String frontendMd5(String rawPassword) {
        return DigestUtils.md5DigestAsHex(rawPassword.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 后端 MD5（服务端第二次加密 + salt）
     * 存入数据库: MD5(前端MD5结果 + salt)
     */
    public static String backendMd5(String frontendMd5Password) {
        return DigestUtils.md5DigestAsHex(
                (frontendMd5Password + SALT).getBytes(StandardCharsets.UTF_8)
        );
    }

    /**
     * 完整双重加密：原始密码 → 前端MD5 → 后端MD5(salt)
     */
    public static String doubleMd5(String rawPassword) {
        return backendMd5(frontendMd5(rawPassword));
    }

    /**
     * 验证密码（登录用）
     * 前端已做一次 MD5，后端只需再做一次 MD5(salt) 与数据库比对
     * @param frontendPassword  前端传来的密码（已是 MD5(原始密码)）
     * @param dbPassword        数据库中的密码（MD5(前端MD5 + salt)）
     */
    public static boolean verify(String frontendPassword, String dbPassword) {
        return backendMd5(frontendPassword).equals(dbPassword);
    }
}
