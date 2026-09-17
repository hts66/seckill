package com.example.seckill.cloud.order;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.ThreadLocalRandom;

/**
 * 聊天消息雪花 ID：异步落库时必须在入库前就拿到全局唯一 ID（前端按它去重、排序），
 * 不能再依赖数据库自增。多实例部署时务必给每个实例配置不同的 app.chat.worker-id。
 *
 * 结构：41 位毫秒时间差(相对 2024-01-01) | 5 位 workerId | 7 位序列号
 */
@Component
public class ChatIdGenerator {
    private static final long EPOCH = 1704067200000L; // 2024-01-01 00:00:00 UTC
    private static final long WORKER_BITS = 5L;
    private static final long SEQ_BITS = 7L;
    private static final long MAX_WORKER = ~(-1L << WORKER_BITS); // 31
    private static final long MAX_SEQ = ~(-1L << SEQ_BITS);       // 127

    private final long workerId;
    private long sequence = 0;
    private long lastMillis = -1;

    public ChatIdGenerator(@Value("${app.chat.worker-id:-1}") long configuredWorkerId) {
        // 未配置时随机一个，单机演示够用；集群多实例必须显式配置 0~31 且互不相同
        this.workerId = (configuredWorkerId >= 0 ? configuredWorkerId
                : ThreadLocalRandom.current().nextInt(0, (int) MAX_WORKER + 1)) & MAX_WORKER;
    }

    public synchronized long next() {
        long now = System.currentTimeMillis();
        if (now < lastMillis) {
            // 时钟回拨：聊天 ID 允许短暂等待，不直接抛错打断用户发送
            try {
                Thread.sleep(lastMillis - now);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            now = System.currentTimeMillis();
        }
        if (now == lastMillis) {
            sequence = (sequence + 1) & MAX_SEQ;
            if (sequence == 0) {
                // 单毫秒 128 个序号用尽，等到下一毫秒
                try { Thread.sleep(1); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                now = System.currentTimeMillis();
            }
        } else {
            sequence = 0;
        }
        lastMillis = now;
        return ((now - EPOCH) << (WORKER_BITS + SEQ_BITS)) | (workerId << SEQ_BITS) | sequence;
    }
}
