package com.example.seckill.cloud.order;

import java.util.List;

/**
 * 聊天跨实例扇出消息：
 *   BATCH  —— 一批消息已落库：messages 广播到各实例的会话房间，conversations 刷新各实例的客服大厅；
 *   CHANGED —— 已读清零等低频变更：只携带会话 id，各实例回查 MySQL 后刷新客服大厅。
 * 发起实例通过 origin 判断：会话消息本机已即时广播，收到自己发的 BATCH 时只刷大厅、不重复推房间。
 */
public record ChatFanoutEnvelope(String origin,
                                 List<ChatDtos.ChatMessage> messages,
                                 List<ChatDtos.Conversation> conversations,
                                 List<Long> changedConvIds) {

    public static ChatFanoutEnvelope batch(String origin,
                                           List<ChatDtos.ChatMessage> messages,
                                           List<ChatDtos.Conversation> conversations) {
        return new ChatFanoutEnvelope(origin, messages, conversations, null);
    }

    public static ChatFanoutEnvelope changed(String origin, List<Long> convIds) {
        return new ChatFanoutEnvelope(origin, null, null, convIds);
    }
}
