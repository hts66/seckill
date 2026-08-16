package com.example.seckill.cloud.auth;

import java.time.LocalDateTime;

public record UserAccount(Long id, String email, String password, String username, String avatar,
                          int role, int status, boolean emailVerified, int tokenVersion,
                          LocalDateTime deletedAt) {}
