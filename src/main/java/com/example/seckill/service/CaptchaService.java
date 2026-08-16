package com.example.seckill.service;

import java.util.Map;

public interface CaptchaService {
    Map<String, String> generate();
    boolean verifyAndConsume(String key, String input);
}
