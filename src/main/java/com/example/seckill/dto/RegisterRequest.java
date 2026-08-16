package com.example.seckill.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class RegisterRequest {
    @NotBlank(message = "邮箱不能为空")
    @Email(message = "邮箱格式不正确")
    private String email;
    @NotBlank(message = "密码不能为空")
    @Size(min = 8, max = 64, message = "密码长度应为8到64位")
    @Pattern(regexp = "^(?=.*[A-Za-z])(?=.*\\d).+$", message = "密码必须同时包含字母和数字")
    private String password;
    @NotBlank(message = "用户名不能为空")
    @Size(min = 2, max = 20, message = "用户名长度应为2到20位")
    private String username;
    @NotBlank(message = "邮箱验证码不能为空")
    private String code;
    @NotBlank(message = "图形验证码不能为空")
    private String captcha;
    @NotBlank(message = "图形验证码标识不能为空")
    private String captchaKey;
}
