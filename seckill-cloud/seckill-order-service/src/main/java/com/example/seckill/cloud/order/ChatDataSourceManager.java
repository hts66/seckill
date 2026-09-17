package com.example.seckill.cloud.order;

import com.zaxxer.hikari.HikariDataSource;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * 聊天专用数据库连接池：与下单/支付/MQ 消费共用的主 Hikari 池隔离，
 * 大促时客服消息洪峰不会抢占秒杀链路的连接。
 * 直接持有数据源而不注册成 Spring DataSource Bean，避免触发主数据源自动配置退避。
 */
@Component
public class ChatDataSourceManager {

    private final HikariDataSource dataSource;
    private final JdbcTemplate jdbcTemplate;
    private final JdbcClient jdbcClient;

    public ChatDataSourceManager(
            @Value("${spring.datasource.url}") String url,
            @Value("${spring.datasource.username}") String username,
            @Value("${spring.datasource.password}") String password) {
        HikariDataSource ds = new HikariDataSource();
        ds.setJdbcUrl(url);
        ds.setUsername(username);
        ds.setPassword(password);
        ds.setPoolName("chat-hikari");
        // 聊天是轻量短查询，池不需要大；与主池（maximum-pool-size:30）严格隔离
        ds.setMaximumPoolSize(8);
        ds.setMinimumIdle(1);
        ds.setConnectionTimeout(2000);
        this.dataSource = ds;
        this.jdbcTemplate = new JdbcTemplate(ds);
        this.jdbcClient = JdbcClient.create(jdbcTemplate);
    }

    public JdbcTemplate jdbcTemplate() {
        return jdbcTemplate;
    }

    public JdbcClient jdbcClient() {
        return jdbcClient;
    }

    @PreDestroy
    public void close() {
        dataSource.close();
    }
}
