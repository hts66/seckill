package com.example.seckill.service.impl;

import com.example.seckill.service.EmailCodeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Duration;

@Slf4j
@Service
@RequiredArgsConstructor
public class EmailCodeServiceImpl implements EmailCodeService {
    private static final Duration CODE_TTL = Duration.ofMinutes(5);
    private static final Duration SEND_INTERVAL = Duration.ofSeconds(60);
    private final StringRedisTemplate redisTemplate;
    private final JavaMailSender mailSender;
    private final SecureRandom random = new SecureRandom();

    @Value("${auth.mail.enabled:false}")
    private boolean mailEnabled;
    @Value("${spring.mail.username:no-reply@seckill.local}")
    private String from;

    @Override
    public void send(String email, String purpose) {
        String normalizedEmail = normalize(email);
        String limitKey = "auth:send-limit:" + purpose + ":" + normalizedEmail;
        Boolean firstRequest = redisTemplate.opsForValue().setIfAbsent(limitKey, "1", SEND_INTERVAL);
        if (!Boolean.TRUE.equals(firstRequest)) throw new IllegalStateException("发送过于频繁，请稍后再试");

        String code = String.format("%06d", random.nextInt(1_000_000));
        redisTemplate.opsForValue().set(codeKey(normalizedEmail, purpose), code, CODE_TTL);
        if (!mailEnabled) {
            log.warn("Mail is disabled. Development verification code for {} / {}: {}", normalizedEmail, purpose, code);
            return;
        }

        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(from);
            message.setTo(normalizedEmail);
            message.setSubject("秒杀商城验证码");
            message.setText("你的验证码是 " + code + "，5分钟内有效。请勿向他人透露。");
            mailSender.send(message);
        } catch (RuntimeException e) {
            redisTemplate.delete(limitKey);
            redisTemplate.delete(codeKey(normalizedEmail, purpose));
            throw new IllegalStateException("验证码发送失败，请检查邮件服务配置", e);
        }
    }

    @Override
    public boolean verifyAndConsume(String email, String purpose, String code) {
        String key = codeKey(normalize(email), purpose);
        String expected = redisTemplate.opsForValue().get(key);
        if (expected == null || code == null || !expected.equals(code.trim())) return false;
        redisTemplate.delete(key);
        return true;
    }

    private String codeKey(String email, String purpose) {
        return "auth:email:" + purpose + ":" + email;
    }

    private String normalize(String email) {
        return email == null ? "" : email.trim().toLowerCase();
    }
}
