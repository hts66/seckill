package com.example.seckill.cloud.order;

import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 客服聊天的业务逻辑（全部走聊天独立连接池，不占用秒杀主连接池）：
 * 会话的创建/查询仍是同步轻量查询；消息本体只构建雪花对象，落库由 {@link ChatPersistence} 异步批量完成。
 */
@Service
public class ChatService {
    private final ChatDataSourceManager ds;
    private final ChatIdGenerator idGenerator;

    public ChatService(ChatDataSourceManager ds, ChatIdGenerator idGenerator) {
        this.ds = ds;
        this.idGenerator = idGenerator;
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
        Integer cnt = ds.jdbcClient().sql("SELECT COUNT(1) FROM seckill_orders WHERE order_no=:orderNo AND user_id=:userId")
                .param("orderNo", orderNo).param("userId", userId)
                .query(Integer.class).single();
        if (cnt == null || cnt == 0) throw new IllegalArgumentException("订单不存在");
    }

    public ChatDtos.Conversation findConversationByOrder(String orderNo, Long userId) {
        return ds.jdbcClient().sql("SELECT " + CONV_COLS + " FROM chat_conversation WHERE order_no=:orderNo AND user_id=:userId")
                .param("orderNo", orderNo).param("userId", userId)
                .query(ChatDtos.Conversation.class).optional().orElse(null);
    }

    public ChatDtos.Conversation findConversationById(Long id) {
        return ds.jdbcClient().sql("SELECT " + CONV_COLS + " FROM chat_conversation WHERE id=:id")
                .param("id", id).query(ChatDtos.Conversation.class).optional().orElse(null);
    }

    /** 用户首次发言时自动建会话（同一订单唯一；并发首发时靠唯一键兜底后回查）。 */
    public ChatDtos.Conversation getOrCreateConversation(Long userId, String email, String orderNo) {
        requireOrderOwner(orderNo, userId);
        ChatDtos.Conversation existing = findConversationByOrder(orderNo, userId);
        if (existing != null) return existing;
        try {
            ds.jdbcClient().sql("INSERT INTO chat_conversation(order_no,user_id,user_email,last_at) VALUES(:orderNo,:userId,:email,NOW())")
                    .param("orderNo", orderNo).param("userId", userId).param("email", email)
                    .update();
        } catch (org.springframework.dao.DuplicateKeyException dup) {
            // 多端同时发首条消息，另一连接已建好
            ChatDtos.Conversation race = findConversationByOrder(orderNo, userId);
            if (race != null) return race;
            throw dup;
        }
        return findConversationByOrder(orderNo, userId);
    }

    public List<ChatDtos.Conversation> listUserConversations(Long userId) {
        return ds.jdbcClient().sql("SELECT " + CONV_COLS + " FROM chat_conversation WHERE user_id=:userId ORDER BY last_at DESC, id DESC")
                .param("userId", userId).query(ChatDtos.Conversation.class).list();
    }

    /** 用户各订单的未读消息数（orderNo -> count），只返回 >0 的。 */
    public java.util.Map<String, Integer> userUnreadCounts(Long userId) {
        return ds.jdbcClient().sql(
                "SELECT order_no, unread_user FROM chat_conversation WHERE user_id=:userId AND unread_user>0")
                .param("userId", userId)
                .query((rs, rowNum) -> java.util.Map.entry(rs.getString("order_no"), rs.getInt("unread_user")))
                .list()
                .stream()
                .collect(java.util.stream.Collectors.toMap(java.util.Map.Entry::getKey, java.util.Map.Entry::getValue));
    }

    public List<ChatDtos.Conversation> listAdminConversations() {
        return ds.jdbcClient().sql("SELECT " + CONV_COLS + " FROM chat_conversation ORDER BY (last_at IS NULL), last_at DESC, id DESC")
                .query(ChatDtos.Conversation.class).list();
    }

    /** 历史消息游标分页：beforeId 为空取最新 size 条，否则取更早的，返回时仍按时间正序。 */
    public List<ChatDtos.ChatMessage> listMessages(Long conversationId, Long beforeId, int size) {
        int limit = Math.min(Math.max(size, 1), 100);
        String sql = "SELECT " + MSG_COLS + " FROM chat_message WHERE conversation_id=:id";
        if (beforeId != null) sql += " AND id<:beforeId";
        sql += " ORDER BY id DESC LIMIT " + limit;
        var spec = ds.jdbcClient().sql(sql).param("id", conversationId);
        if (beforeId != null) spec = spec.param("beforeId", beforeId);
        List<ChatDtos.ChatMessage> desc = spec.query(ChatDtos.ChatMessage.class).list();
        java.util.Collections.reverse(desc);
        return desc;
    }

    /** 用户按订单拉取历史；会话还不存在时返回空，不报错。 */
    public ChatDtos.OrderChat userOrderChat(String orderNo, Long userId, Long beforeId, int size) {
        requireOrderOwner(orderNo, userId);
        ChatDtos.Conversation c = findConversationByOrder(orderNo, userId);
        if (c == null) return new ChatDtos.OrderChat(null, List.of());
        return new ChatDtos.OrderChat(c.id(), listMessages(c.id(), beforeId, size));
    }

    /**
     * 构建一条待落库消息：生成雪花 ID（前端据此去重/排序），不做任何 DB 写入；
     * 调用方拿到后立即广播并交给 {@link ChatPersistence#enqueue}。
     */
    public ChatDtos.PendingMessage buildPendingMessage(long conversationId, long senderId, int senderType, String content) {
        String trimmed = content == null ? "" : content.trim();
        if (trimmed.isEmpty()) throw new IllegalArgumentException("消息内容不能为空");
        if (trimmed.length() > 1000) throw new IllegalArgumentException("消息内容过长");
        return new ChatDtos.PendingMessage(
                idGenerator.next(), conversationId, senderId, senderType, trimmed, LocalDateTime.now());
    }
}
