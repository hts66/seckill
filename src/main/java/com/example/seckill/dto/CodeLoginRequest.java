package com.example.seckill.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class CodeLoginRequest {
    @NotBlank(message = "邮箱不能为空")
    @Email(message = "邮箱格式不正确")
    private String email;
    @NotBlank(message = "邮箱验证码不能为空")
    private String code;
    @NotBlank(message = "图形验证码不能为空")
    private String captcha;
    @NotBlank(message = "图形验证码标识不能为空")
    private String captchaKey;
}
