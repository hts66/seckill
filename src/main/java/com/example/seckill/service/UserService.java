package com.example.seckill.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.example.seckill.dto.RegisterRequest;
import com.example.seckill.entity.User;

public interface UserService extends IService<User> {
    User authenticatePassword(String email, String password, String ipAddress);
    User authenticateCode(String email, String ipAddress);
    User register(RegisterRequest request, String ipAddress);
    void resetPassword(String email, String newPassword);
    User findByEmail(String email);
}
