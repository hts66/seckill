package com.example.seckill.cloud.auth;

import com.example.seckill.cloud.common.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;

@RestController
public class AuthController {
    private final UserRepository users;private final CaptchaService captcha;private final EmailCodeService codes;private final JwtTokenService tokens;
    private final BCryptPasswordEncoder encoder=new BCryptPasswordEncoder();
    public AuthController(UserRepository users,CaptchaService captcha,EmailCodeService codes,JwtTokenService tokens){this.users=users;this.captcha=captcha;this.codes=codes;this.tokens=tokens;}
    @GetMapping("/api/captcha") public ApiResponse<Map<String,String>> captcha(){return ApiResponse.ok(captcha.generate());}
    @PostMapping("/api/auth/login") public ApiResponse<AuthDtos.Tokens> login(@Valid @RequestBody AuthDtos.Login r,HttpServletRequest request){captcha.require(r.captchaKey(),r.captcha());UserAccount u=active(r.email());
        if(!matches(u,r.password()))throw new IllegalArgumentException("邮箱或密码错误");users.recordLogin(u.id(),ip(request));return ApiResponse.ok(tokens.issue(users.findById(u.id()).orElseThrow()));}
    @PostMapping("/api/auth/login/code") public ApiResponse<AuthDtos.Tokens> codeLogin(@Valid @RequestBody AuthDtos.CodeLogin r,HttpServletRequest request){captcha.require(r.captchaKey(),r.captcha());codes.require(r.email(),"login",r.code());UserAccount u=active(r.email());users.recordLogin(u.id(),ip(request));return ApiResponse.ok(tokens.issue(u));}
    @PostMapping("/api/auth/register") public ApiResponse<AuthDtos.Tokens> register(@Valid @RequestBody AuthDtos.Register r,HttpServletRequest request){captcha.require(r.captchaKey(),r.captcha());codes.require(r.email(),"register",r.code());if(users.findByEmail(r.email()).isPresent())throw new IllegalArgumentException("该邮箱已注册");return ApiResponse.ok("注册成功",tokens.issue(users.create(r.email(),encoder.encode(r.password()),r.username(),ip(request))));}
    @PostMapping("/api/auth/send-code") public ApiResponse<Void> send(@Valid @RequestBody AuthDtos.SendCode r){captcha.require(r.captchaKey(),r.captcha());boolean exists=users.findByEmail(r.email()).isPresent();if("register".equals(r.purpose())&&exists)throw new IllegalArgumentException("该邮箱已注册");if(!"register".equals(r.purpose())&&!exists)return ApiResponse.ok("如果邮箱已注册，验证码将发送到该邮箱",null);codes.send(r.email(),r.purpose());return ApiResponse.ok("验证码已发送",null);}
    @PostMapping("/api/auth/reset-password") public ApiResponse<Void> reset(@Valid @RequestBody AuthDtos.ResetPassword r){captcha.require(r.captchaKey(),r.captcha());codes.require(r.email(),"reset",r.code());UserAccount u=active(r.email());users.resetPassword(u.id(),encoder.encode(r.newPassword()));return ApiResponse.ok("密码已重置",null);}
    @PostMapping("/api/auth/refresh") public ApiResponse<AuthDtos.Tokens> refresh(@Valid @RequestBody AuthDtos.Refresh r){return ApiResponse.ok(tokens.refresh(r.refreshToken()));}
    @PostMapping("/api/auth/logout") public ApiResponse<Void> logout(@RequestBody(required=false) AuthDtos.Refresh r){if(r!=null)tokens.revoke(r.refreshToken());return ApiResponse.ok("已安全退出",null);}
    private UserAccount active(String email){UserAccount u=users.findByEmail(email).orElseThrow(()->new IllegalArgumentException("邮箱或密码错误"));if(u.status()!=1)throw new IllegalStateException("账号当前不可用");return u;}
    private boolean matches(UserAccount u,String raw){if(u.password().startsWith("$2"))return encoder.matches(raw,u.password());String legacy=md5(md5(raw)+"seckill_salt_2024");if(MessageDigest.isEqual(legacy.getBytes(),u.password().getBytes())){users.upgradePassword(u.id(),encoder.encode(raw));return true;}return false;}
    private String md5(String s){try{return HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(s.getBytes()));}catch(Exception e){throw new IllegalStateException(e);}}
    private String ip(HttpServletRequest r){String f=r.getHeader("X-Forwarded-For");return f==null?r.getRemoteAddr():f.split(",")[0].trim();}
}
