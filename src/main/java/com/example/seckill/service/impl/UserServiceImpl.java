package com.example.seckill.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.example.seckill.dto.RegisterRequest;
import com.example.seckill.entity.User;
import com.example.seckill.mapper.UserMapper;
import com.example.seckill.service.UserService;
import com.example.seckill.utils.Md5Util;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements UserService {
    private final PasswordEncoder passwordEncoder;

    @Override
    @Transactional
    public User authenticatePassword(String email, String password, String ipAddress) {
        User user = requireActiveUser(email);
        if (!matchesAndUpgradePassword(user, password)) throw new IllegalArgumentException("邮箱或密码错误");
        recordLogin(user, ipAddress);
        return user;
    }

    @Override
    @Transactional
    public User authenticateCode(String email, String ipAddress) {
        User user = requireActiveUser(email);
        recordLogin(user, ipAddress);
        return user;
    }

    @Override
    @Transactional
    public User register(RegisterRequest request, String ipAddress) {
        String email = normalizeEmail(request.getEmail());
        if (findByEmail(email) != null) throw new IllegalArgumentException("该邮箱已注册");
        User user = new User();
        user.setEmail(email);
        user.setPassword(passwordEncoder.encode(request.getPassword()));
        user.setUsername(request.getUsername().trim());
        user.setRole(0);
        user.setStatus(1);
        user.setEmailVerified(true);
        user.setTokenVersion(0);
        user.setPasswordChangedAt(LocalDateTime.now());
        user.setLastLoginAt(LocalDateTime.now());
        user.setLastLoginIp(ipAddress);
        save(user);
        return user;
    }

    @Override
    @Transactional
    public void resetPassword(String email, String newPassword) {
        User user = requireActiveUser(email);
        user.setPassword(passwordEncoder.encode(newPassword));
        user.setPasswordChangedAt(LocalDateTime.now());
        user.setTokenVersion((user.getTokenVersion() == null ? 0 : user.getTokenVersion()) + 1);
        updateById(user);
    }

    @Override
    public User findByEmail(String email) {
        return getOne(new LambdaQueryWrapper<User>()
                .eq(User::getEmail, normalizeEmail(email))
                .isNull(User::getDeletedAt));
    }

    private User requireActiveUser(String email) {
        User user = findByEmail(email);
        if (user == null) throw new IllegalArgumentException("邮箱或密码错误");
        if (user.getStatus() == null || user.getStatus() != 1) throw new IllegalStateException("账号当前不可用");
        return user;
    }

    private boolean matchesAndUpgradePassword(User user, String rawPassword) {
        String stored = user.getPassword();
        if (stored != null && (stored.startsWith("$2a$") || stored.startsWith("$2b$") || stored.startsWith("$2y$"))) {
            return passwordEncoder.matches(rawPassword, stored);
        }
        if (stored != null && stored.equalsIgnoreCase(Md5Util.doubleMd5(rawPassword))) {
            user.setPassword(passwordEncoder.encode(rawPassword));
            user.setPasswordChangedAt(LocalDateTime.now());
            updateById(user);
            return true;
        }
        return false;
    }

    private void recordLogin(User user, String ipAddress) {
        user.setLastLoginAt(LocalDateTime.now());
        user.setLastLoginIp(ipAddress);
        updateById(user);
    }

    private String normalizeEmail(String email) {
        return email == null ? "" : email.trim().toLowerCase();
    }
}
