package com.example.seckill.cloud.auth;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;

@Service
public class CaptchaService {
    private final StringRedisTemplate redis;
    private final SecureRandom random = new SecureRandom();
    private static final String CHARS="23456789ABCDEFGHJKLMNPQRSTUVWXYZ", PREFIX="auth:captcha:";
    public CaptchaService(StringRedisTemplate redis) { this.redis=redis; }
    public Map<String,String> generate() {
        StringBuilder code=new StringBuilder(); for(int i=0;i<4;i++) code.append(CHARS.charAt(random.nextInt(CHARS.length())));
        String key=UUID.randomUUID().toString(); redis.opsForValue().set(PREFIX+key,code.toString(), Duration.ofMinutes(2));
        BufferedImage image=new BufferedImage(132,44,BufferedImage.TYPE_INT_RGB); Graphics2D g=image.createGraphics();
        g.setColor(new Color(246,247,251));g.fillRect(0,0,132,44);g.setFont(new Font(Font.MONOSPACED,Font.BOLD,27));
        for(int i=0;i<4;i++){g.setColor(i%2==0?new Color(13,27,42):new Color(232,78,39));g.drawString(String.valueOf(code.charAt(i)),15+i*27,32);}g.dispose();
        try(ByteArrayOutputStream out=new ByteArrayOutputStream()){ImageIO.write(image,"png",out);return Map.of("key",key,"image","data:image/png;base64,"+Base64.getEncoder().encodeToString(out.toByteArray()));}
        catch(Exception e){redis.delete(PREFIX+key);throw new IllegalStateException("生成图形验证码失败");}
    }
    public void require(String key,String input){String redisKey=PREFIX+key;String expected=redis.opsForValue().get(redisKey);redis.delete(redisKey);if(expected==null||input==null||!expected.equalsIgnoreCase(input.trim()))throw new IllegalArgumentException("图形验证码错误或已过期");}
}
