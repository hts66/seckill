package com.example.seckill.cloud.order;

import java.util.List;

/** 客服聊天相关的数据传输对象。 */
public final class ChatDtos {
    private ChatDtos() {}

    /** 一条聊天消息（时间已在 SQL 里格式化为字符串，避免前端处理 LocalDateTime 数组）。 */
    public record ChatMessage(Long id, Long conversationId, Long senderId, Integer senderType,
                              String content, String createdAt) {}

    /** 会话列表项。 */
    public record Conversation(Long id, String orderNo, Long userId, String userEmail,
                               Integer status, Integer unreadUser, Integer unreadAdmin,
                               String lastContent, String lastAt, String createdAt) {}

    /** 用户按订单拉取历史时的返回：会话可能还不存在，此时 conversationId 为 null。 */
    public record OrderChat(Long conversationId, List<ChatMessage> messages) {}

    /** WebSocket 上行消息。 */
    public record Incoming(String type, Long conversationId, String orderNo, String content) {}
}
