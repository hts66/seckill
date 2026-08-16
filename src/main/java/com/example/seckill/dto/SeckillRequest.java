package com.example.seckill.dto;

import lombok.Data;

@Data
public class SeckillRequest {
    private Long itemId;        // 秒杀商品ID
    private String pathKey;     // 动态秒杀路径（接口隐藏）
    private String captcha;     // 验证码
    private String captchaKey;  // 验证码key
}
