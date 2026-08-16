package com.example.seckill.controller;

import com.example.seckill.dto.Response;
import com.example.seckill.service.CaptchaService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/captcha")
@RequiredArgsConstructor
public class CaptchaController {
    private final CaptchaService captchaService;

    @GetMapping
    public Response<Map<String, String>> captcha() {
        return Response.success(captchaService.generate());
    }
}
