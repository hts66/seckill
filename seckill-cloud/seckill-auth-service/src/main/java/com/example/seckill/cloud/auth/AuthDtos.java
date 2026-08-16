package com.example.seckill.cloud.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public final class AuthDtos {
    private AuthDtos() {}
    public record Login(@Email @NotBlank String email, @NotBlank String password,
                        @NotBlank String captcha, @NotBlank String captchaKey) {}
    public record CodeLogin(@Email @NotBlank String email, @NotBlank String code,
                            @NotBlank String captcha, @NotBlank String captchaKey) {}
    public record Register(@Email @NotBlank String email,
                           @Size(min=8,max=64) @Pattern(regexp="^(?=.*[A-Za-z])(?=.*\\d).+$") String password,
                           @Size(min=2,max=20) String username, @NotBlank String code,
                           @NotBlank String captcha, @NotBlank String captchaKey) {}
    public record SendCode(@Email @NotBlank String email, @Pattern(regexp="register|login|reset") String purpose,
                           @NotBlank String captcha, @NotBlank String captchaKey) {}
    public record ResetPassword(@Email @NotBlank String email,
                                @Size(min=8,max=64) @Pattern(regexp="^(?=.*[A-Za-z])(?=.*\\d).+$") String newPassword,
                                @NotBlank String code, @NotBlank String captcha, @NotBlank String captchaKey) {}
    public record Refresh(@NotBlank String refreshToken) {}
    public record UserView(Long id, String email, String username, String avatar, int role) {}
    public record Tokens(String token, String refreshToken, UserView user) {}
}
