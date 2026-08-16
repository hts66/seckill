package com.example.seckill.service.impl;

import com.example.seckill.service.CaptchaService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class CaptchaServiceImpl implements CaptchaService {
    private static final String CHARS = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ";
    private static final String KEY_PREFIX = "auth:captcha:";
    private static final Duration TTL = Duration.ofMinutes(2);
    private final SecureRandom random = new SecureRandom();
    private final StringRedisTemplate redisTemplate;

    @Override
    public Map<String, String> generate() {
        String code = randomCode(4);
        String key = UUID.randomUUID().toString();
        redisTemplate.opsForValue().set(KEY_PREFIX + key, code, TTL);

        BufferedImage image = new BufferedImage(132, 44, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            graphics.setColor(new Color(246, 247, 251));
            graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
            for (int i = 0; i < 7; i++) {
                graphics.setColor(new Color(30 + random.nextInt(80), 60 + random.nextInt(80), 100 + random.nextInt(100), 90));
                graphics.drawLine(random.nextInt(132), random.nextInt(44), random.nextInt(132), random.nextInt(44));
            }
            graphics.setFont(new Font(Font.MONOSPACED, Font.BOLD, 27));
            for (int i = 0; i < code.length(); i++) {
                graphics.setColor(i % 2 == 0 ? new Color(13, 27, 42) : new Color(232, 78, 39));
                graphics.rotate(Math.toRadians(random.nextInt(13) - 6), 24 + i * 27, 27);
                graphics.drawString(String.valueOf(code.charAt(i)), 15 + i * 27, 32);
                graphics.setTransform(new java.awt.geom.AffineTransform());
            }
        } finally {
            graphics.dispose();
        }

        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            ImageIO.write(image, "png", output);
            Map<String, String> result = new LinkedHashMap<>();
            result.put("key", key);
            result.put("image", "data:image/png;base64," + Base64.getEncoder().encodeToString(output.toByteArray()));
            return result;
        } catch (Exception e) {
            redisTemplate.delete(KEY_PREFIX + key);
            throw new IllegalStateException("生成图形验证码失败", e);
        }
    }

    @Override
    public boolean verifyAndConsume(String key, String input) {
        if (key == null || input == null) return false;
        String redisKey = KEY_PREFIX + key;
        String expected = redisTemplate.opsForValue().get(redisKey);
        redisTemplate.delete(redisKey);
        return expected != null && expected.equalsIgnoreCase(input.trim());
    }

    private String randomCode(int length) {
        StringBuilder result = new StringBuilder(length);
        for (int i = 0; i < length; i++) result.append(CHARS.charAt(random.nextInt(CHARS.length())));
        return result.toString();
    }
}
