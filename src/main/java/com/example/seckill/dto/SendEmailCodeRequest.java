package com.example.seckill.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

@Data
public class SendEmailCodeRequest {
    @NotBlank(message = "邮箱不能为空")
    @Email(message = "邮箱格式不正确")
    private String email;
    @NotBlank(message = "验证码用途不能为空")
    @Pattern(regexp = "register|login|reset", message = "验证码用途无效")
    private String purpose;
    @NotBlank(message = "图形验证码不能为空")
    private String captcha;
    @NotBlank(message = "图形验证码标识不能为空")
    private String captchaKey;
}
