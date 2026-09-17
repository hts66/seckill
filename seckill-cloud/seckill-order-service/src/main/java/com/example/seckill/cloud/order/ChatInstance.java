package com.example.seckill.cloud.order;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * order-service 实例标识：用于聊天 fanout 广播时识别消息来源，
 * 发起实例对自己房间内的消息已做即时广播，收到自己发的扇出消息时跳过，避免重复推送。
 * 多实例部署可通过 app.chat.instance-id 显式指定（K8s 里建议注入 Pod 名），不填则进程随机。
 */
@Component
public class ChatInstance {
    private final String id;

    public ChatInstance(@Value("${app.chat.instance-id:}") String configured) {
        this.id = (configured == null || configured.isBlank())
                ? UUID.randomUUID().toString().replace("-", "").substring(0, 8)
                : configured;
    }

    public String id() {
        return id;
    }
}
