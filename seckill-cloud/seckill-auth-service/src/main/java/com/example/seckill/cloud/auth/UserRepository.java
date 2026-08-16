package com.example.seckill.cloud.auth;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public class UserRepository {
    private final JdbcClient jdbc;
    public UserRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    public Optional<UserAccount> findByEmail(String email) {
        return jdbc.sql("SELECT id,email,password,username,avatar,role,status,email_verified,token_version,deleted_at FROM users WHERE email=:email AND deleted_at IS NULL")
                .param("email", normalize(email)).query(UserAccount.class).optional();
    }
    public Optional<UserAccount> findById(Long id) {
        return jdbc.sql("SELECT id,email,password,username,avatar,role,status,email_verified,token_version,deleted_at FROM users WHERE id=:id AND deleted_at IS NULL")
                .param("id", id).query(UserAccount.class).optional();
    }
    public UserAccount create(String email, String password, String username, String ip) {
        jdbc.sql("INSERT INTO users(email,password,username,role,status,email_verified,token_version,password_changed_at,last_login_at,last_login_ip) VALUES(:email,:password,:username,0,1,1,0,NOW(),NOW(),:ip)")
                .param("email", normalize(email)).param("password", password).param("username", username.trim()).param("ip", ip).update();
        return findByEmail(email).orElseThrow();
    }
    public void recordLogin(Long id, String ip) {
        jdbc.sql("UPDATE users SET last_login_at=NOW(),last_login_ip=:ip WHERE id=:id").param("ip", ip).param("id", id).update();
    }
    public void upgradePassword(Long id, String password) {
        jdbc.sql("UPDATE users SET password=:password,password_changed_at=NOW() WHERE id=:id").param("password", password).param("id", id).update();
    }
    public void resetPassword(Long id, String password) {
        jdbc.sql("UPDATE users SET password=:password,password_changed_at=NOW(),token_version=token_version+1 WHERE id=:id")
                .param("password", password).param("id", id).update();
    }
    private String normalize(String email) { return email == null ? "" : email.trim().toLowerCase(); }
}
