package com.example.seckill.cloud.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Duration;

@Service
public class EmailCodeService {
    private final StringRedisTemplate redis;
    private final JavaMailSender sender;
    private final SecureRandom random = new SecureRandom();
    @Value("${spring.mail.username}") private String from;

    public EmailCodeService(StringRedisTemplate redis, JavaMailSender sender) {
        this.redis = redis;
        this.sender = sender;
    }

    public void send(String email, String purpose) {
        String normalized = email.trim().toLowerCase();
        String limitKey = "auth:send-limit:" + purpose + ":" + normalized;
        if (!Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(limitKey, "1", Duration.ofSeconds(60))))
            throw new IllegalStateException("发送过于频繁，请稍后再试");

        String code = String.format("%06d", random.nextInt(1_000_000));
        redis.opsForValue().set(codeKey(normalized, purpose), code, Duration.ofMinutes(5));
        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(from);
            message.setTo(normalized);
            message.setSubject("秒杀系统验证码");
            message.setText("你的验证码是 " + code + "，5 分钟内有效。");
            sender.send(message);
        } catch (RuntimeException exception) {
            redis.delete(limitKey);
            redis.delete(codeKey(normalized, purpose));
            throw new IllegalStateException("验证码发送失败，请检查邮件服务配置");
        }
    }

    public void require(String email, String purpose, String code) {
        String key = codeKey(email.trim().toLowerCase(), purpose);
        String expected = redis.opsForValue().get(key);
        if (expected == null || !expected.equals(code)) throw new IllegalArgumentException("邮箱验证码错误或已过期");
        redis.delete(key);
    }

    private String codeKey(String email, String purpose) {
        return "auth:email:" + purpose + ":" + email;
    }
}
