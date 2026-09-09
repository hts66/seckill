package com.example.seckill.cloud.order;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** 客服聊天的持久化逻辑：会话与消息落库、未读数维护。 */
@Service
public class ChatService {
    private final JdbcClient jdbc;

    public ChatService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    private static final String CONV_COLS =
            "id,order_no,user_id,user_email,status,unread_user,unread_admin,last_content," +
            "DATE_FORMAT(last_at,'%Y-%m-%d %H:%i:%s') AS last_at," +
            "DATE_FORMAT(created_at,'%Y-%m-%d %H:%i:%s') AS created_at";

    private static final String MSG_COLS =
            "id,conversation_id,sender_id,sender_type,content," +
            "DATE_FORMAT(created_at,'%Y-%m-%d %H:%i:%s') AS created_at";

    /** 校验订单存在且属于该用户。 */
    public void requireOrderOwner(String orderNo, Long userId) {
        Integer cnt = jdbc.sql("SELECT COUNT(1) FROM seckill_orders WHERE order_no=:orderNo AND user_id=:userId")
                .param("orderNo", orderNo).param("userId", userId)
                .query(Integer.class).single();
        if (cnt == null || cnt == 0) throw new IllegalArgumentException("订单不存在");
    }

    public ChatDtos.Conversation findConversationByOrder(String orderNo, Long userId) {
        return jdbc.sql("SELECT " + CONV_COLS + " FROM chat_conversation WHERE order_no=:orderNo AND user_id=:userId")
                .param("orderNo", orderNo).param("userId", userId)
                .query(ChatDtos.Conversation.class).optional().orElse(null);
    }

    public ChatDtos.Conversation findConversationById(Long id) {
        return jdbc.sql("SELECT " + CONV_COLS + " FROM chat_conversation WHERE id=:id")
                .param("id", id).query(ChatDtos.Conversation.class).optional().orElse(null);
    }

    /** 用户首次发言时自动建会话（同一订单唯一，幂等）。 */
    @Transactional
    public ChatDtos.Conversation getOrCreateConversation(Long userId, String email, String orderNo) {
        requireOrderOwner(orderNo, userId);
        ChatDtos.Conversation existing = findConversationByOrder(orderNo, userId);
        if (existing != null) return existing;
        jdbc.sql("INSERT INTO chat_conversation(order_no,user_id,user_email,last_at) VALUES(:orderNo,:userId,:email,NOW())")
                .param("orderNo", orderNo).param("userId", userId).param("email", email)
                .update();
        return findConversationByOrder(orderNo, userId);
    }

    public List<ChatDtos.Conversation> listUserConversations(Long userId) {
        return jdbc.sql("SELECT " + CONV_COLS + " FROM chat_conversation WHERE user_id=:userId ORDER BY last_at DESC, id DESC")
                .param("userId", userId).query(ChatDtos.Conversation.class).list();
    }

    public List<ChatDtos.Conversation> listAdminConversations() {
        return jdbc.sql("SELECT " + CONV_COLS + " FROM chat_conversation ORDER BY (last_at IS NULL), last_at DESC, id DESC")
                .query(ChatDtos.Conversation.class).list();
    }

    public List<ChatDtos.ChatMessage> listMessages(Long conversationId) {
        return jdbc.sql("SELECT " + MSG_COLS + " FROM chat_message WHERE conversation_id=:id ORDER BY id ASC")
                .param("id", conversationId).query(ChatDtos.ChatMessage.class).list();
    }

    /** 用户按订单拉取历史；会话还不存在时返回空。 */
    public ChatDtos.OrderChat userOrderChat(String orderNo, Long userId) {
        requireOrderOwner(orderNo, userId);
        ChatDtos.Conversation c = findConversationByOrder(orderNo, userId);
        if (c == null) return new ChatDtos.OrderChat(null, List.of());
        return new ChatDtos.OrderChat(c.id(), listMessages(c.id()));
    }

    /** 落库一条消息、刷新会话摘要并累加对方未读。senderType: 0 用户 1 客服。 */
    @Transactional
    public ChatDtos.ChatMessage appendMessage(Long conversationId, Long senderId, int senderType, String content) {
        String trimmed = content == null ? "" : content.trim();
        if (trimmed.isEmpty()) throw new IllegalArgumentException("消息内容不能为空");
        if (trimmed.length() > 1000) throw new IllegalArgumentException("消息内容过长");
        jdbc.sql("INSERT INTO chat_message(conversation_id,sender_id,sender_type,content) VALUES(:cid,:sid,:st,:content)")
                .param("cid", conversationId).param("sid", senderId)
                .param("st", senderType).param("content", trimmed).update();
        ChatDtos.ChatMessage msg = jdbc.sql(
                        "SELECT " + MSG_COLS + " FROM chat_message WHERE conversation_id=:cid ORDER BY id DESC LIMIT 1")
                .param("cid", conversationId).query(ChatDtos.ChatMessage.class).single();
        // 客服发言累加用户未读，用户发言累加客服未读
        String unreadCol = senderType == 1 ? "unread_user" : "unread_admin";
        String summary = trimmed.length() > 200 ? trimmed.substring(0, 200) : trimmed;
        jdbc.sql("UPDATE chat_conversation SET last_content=:content, last_at=NOW(), " + unreadCol + "=" + unreadCol + "+1 WHERE id=:id")
                .param("content", summary).param("id", conversationId).update();
        return msg;
    }

    @Transactional
    public void markReadByUser(Long conversationId, Long userId) {
        jdbc.sql("UPDATE chat_conversation SET unread_user=0 WHERE id=:id AND user_id=:userId")
                .param("id", conversationId).param("userId", userId).update();
    }

    @Transactional
    public void markReadByAdmin(Long conversationId) {
        jdbc.sql("UPDATE chat_conversation SET unread_admin=0 WHERE id=:id")
                .param("id", conversationId).update();
    }
}
