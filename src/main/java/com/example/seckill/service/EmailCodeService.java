package com.example.seckill.service;

public interface EmailCodeService {
    void send(String email, String purpose);
    boolean verifyAndConsume(String email, String purpose, String code);
}
