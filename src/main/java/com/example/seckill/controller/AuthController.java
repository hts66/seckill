package com.example.seckill.controller;

import com.example.seckill.dto.*;
import com.example.seckill.entity.User;
import com.example.seckill.service.AuthTokenService;
import com.example.seckill.service.CaptchaService;
import com.example.seckill.service.EmailCodeService;
import com.example.seckill.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {
    private final UserService userService;
    private final CaptchaService captchaService;
    private final EmailCodeService emailCodeService;
    private final AuthTokenService authTokenService;

    @PostMapping("/login")
    public Response<LoginResponse> login(@Valid @RequestBody LoginRequest request, HttpServletRequest httpRequest) {
        try {
            requireCaptcha(request.getCaptchaKey(), request.getCaptcha());
            User user = userService.authenticatePassword(request.getEmail(), request.getPassword(), clientIp(httpRequest));
            return Response.success(authTokenService.issue(user));
        } catch (Exception e) {
            return Response.error(401, e.getMessage());
        }
    }

    @PostMapping("/login/code")
    public Response<LoginResponse> loginWithCode(@Valid @RequestBody CodeLoginRequest request,
                                                  HttpServletRequest httpRequest) {
        try {
            requireCaptcha(request.getCaptchaKey(), request.getCaptcha());
            requireEmailCode(request.getEmail(), "login", request.getCode());
            User user = userService.authenticateCode(request.getEmail(), clientIp(httpRequest));
            return Response.success(authTokenService.issue(user));
        } catch (Exception e) {
            return Response.error(401, e.getMessage());
        }
    }

    @PostMapping("/register")
    public Response<LoginResponse> register(@Valid @RequestBody RegisterRequest request,
                                             HttpServletRequest httpRequest) {
        try {
            requireCaptcha(request.getCaptchaKey(), request.getCaptcha());
            requireEmailCode(request.getEmail(), "register", request.getCode());
            User user = userService.register(request, clientIp(httpRequest));
            return Response.success("注册成功", authTokenService.issue(user));
        } catch (Exception e) {
            return Response.error(400, e.getMessage());
        }
    }

    @PostMapping("/send-code")
    public Response<Void> sendCode(@Valid @RequestBody SendEmailCodeRequest request) {
        try {
            requireCaptcha(request.getCaptchaKey(), request.getCaptcha());
            User existing = userService.findByEmail(request.getEmail());
            if ("register".equals(request.getPurpose()) && existing != null) {
                return Response.error(400, "该邮箱已注册");
            }
            if (("login".equals(request.getPurpose()) || "reset".equals(request.getPurpose())) && existing == null) {
                return Response.success("如果邮箱已注册，验证码将发送到该邮箱", null);
            }
            emailCodeService.send(request.getEmail(), request.getPurpose());
            return Response.success("验证码已发送", null);
        } catch (Exception e) {
            return Response.error(400, e.getMessage());
        }
    }

    @PostMapping("/reset-password")
    public Response<Void> resetPassword(@Valid @RequestBody ResetPasswordRequest request) {
        try {
            requireCaptcha(request.getCaptchaKey(), request.getCaptcha());
            requireEmailCode(request.getEmail(), "reset", request.getCode());
            userService.resetPassword(request.getEmail(), request.getNewPassword());
            return Response.success("密码已重置，请重新登录", null);
        } catch (Exception e) {
            return Response.error(400, e.getMessage());
        }
    }

    @PostMapping("/refresh")
    public Response<LoginResponse> refresh(@Valid @RequestBody RefreshTokenRequest request) {
        try {
            return Response.success(authTokenService.refresh(request.getRefreshToken()));
        } catch (Exception e) {
            return Response.error(401, "登录状态已过期，请重新登录");
        }
    }

    @PostMapping("/logout")
    public Response<Void> logout(@RequestBody(required = false) RefreshTokenRequest request) {
        authTokenService.revoke(request == null ? null : request.getRefreshToken());
        return Response.success("已安全退出", null);
    }

    private void requireCaptcha(String key, String value) {
        if (!captchaService.verifyAndConsume(key, value)) throw new IllegalArgumentException("图形验证码错误或已过期");
    }

    private void requireEmailCode(String email, String purpose, String code) {
        if (!emailCodeService.verifyAndConsume(email, purpose, code)) throw new IllegalArgumentException("邮箱验证码错误或已过期");
    }

    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        return forwarded == null || forwarded.isBlank() ? request.getRemoteAddr() : forwarded.split(",")[0].trim();
    }
}
