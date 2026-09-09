package com.example.seckill.cloud.order;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 客服聊天 WebSocket 处理器。
 * 房间模型：
 *   conv:{id}    —— 某订单会话的房间，用户（连接时自动加入自己的全部会话）+ 订阅的客服在其中收发消息；
 *   admin:hall   —— 在线客服大厅，只收“会话列表变更”轻量通知（新会话、未读、最后一条消息）。
 * 容器全部使用并发结构，支持同一账号手机/电脑多端同时在线。
 */
@Component
public class ChatWebSocketHandler extends TextWebSocketHandler {
    private static final Logger log = LoggerFactory.getLogger(ChatWebSocketHandler.class);
    private static final String ADMIN_HALL = "admin:hall";

    private final ChatService chatService;
    private final ObjectMapper objectMapper;

    /** roomId -> 在线会话（线程安全 Set）。 */
    private final Map<String, Set<WebSocketSession>> rooms = new ConcurrentHashMap<>();
    /** sessionId -> 该会话加入过的房间。 */
    private final Map<String, Set<String>> sessionRooms = new ConcurrentHashMap<>();
    /** sessionId -> 发送装饰器（保证同一会话并发发送的线程安全）。 */
    private final Map<String, WebSocketSession> decorators = new ConcurrentHashMap<>();

    public ChatWebSocketHandler(ChatService chatService, ObjectMapper objectMapper) {
        this.chatService = chatService;
        this.objectMapper = objectMapper;
    }

    private static String convRoom(Long conversationId) {
        return "conv:" + conversationId;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        Long userId = (Long) session.getAttributes().get(ChatHandshakeInterceptor.ATTR_USER_ID);
        Integer role = (Integer) session.getAttributes().get(ChatHandshakeInterceptor.ATTR_ROLE);
        int roleValue = role == null ? 0 : role;
        WebSocketSession self = new ConcurrentWebSocketSessionDecorator(session, 10_000, 64 * 1024);
        decorators.put(session.getId(), self);

        if (roleValue == 1) {
            // 客服进入大厅，接收会话列表变更
            joinRoom(self, ADMIN_HALL);
        } else if (userId != null) {
            // 用户自动加入自己的全部会话房间，保证多端实时收消息
            try {
                for (ChatDtos.Conversation c : chatService.listUserConversations(userId)) {
                    joinRoom(self, convRoom(c.id()));
                }
            } catch (Exception e) {
                log.warn("加载用户会话房间失败 userId={}: {}", userId, e.toString());
            }
        }
        send(self, Map.of("type", "connected", "role", roleValue));
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        WebSocketSession self = decorators.get(session.getId());
        if (self == null) self = session;
        Long userId = (Long) session.getAttributes().get(ChatHandshakeInterceptor.ATTR_USER_ID);
        Integer roleAttr = (Integer) session.getAttributes().get(ChatHandshakeInterceptor.ATTR_ROLE);
        int role = roleAttr == null ? 0 : roleAttr;
        String email = (String) session.getAttributes().get(ChatHandshakeInterceptor.ATTR_EMAIL);

        ChatDtos.Incoming in;
        try {
            in = objectMapper.readValue(message.getPayload(), ChatDtos.Incoming.class);
        } catch (Exception e) {
            sendError(self, "无法解析消息");
            return;
        }
        String type = in.type() == null ? "" : in.type();
        try {
            switch (type) {
                case "ping" -> send(self, Map.of("type", "pong"));
                case "chat" -> handleChat(self, userId, role, email, in);
                case "subscribe" -> handleSubscribe(self, role, in.conversationId());
                case "read" -> handleRead(userId, role, in);
                default -> sendError(self, "未知消息类型");
            }
        } catch (IllegalArgumentException e) {
            sendError(self, e.getMessage());
        } catch (Exception e) {
            log.warn("处理聊天消息失败: {}", e.toString());
            sendError(self, "消息处理失败");
        }
    }

    private void handleChat(WebSocketSession self, Long userId, int role, String email, ChatDtos.Incoming in) {
        if (userId == null) throw new IllegalArgumentException("请先登录");
        ChatDtos.Conversation conv;
        int senderType;
        if (role == 1) {
            // 客服发言：必须指定会话
            if (in.conversationId() == null) throw new IllegalArgumentException("缺少会话");
            conv = chatService.findConversationById(in.conversationId());
            if (conv == null) throw new IllegalArgumentException("会话不存在");
            senderType = 1;
        } else {
            // 用户发言：按订单号获取/创建会话
            if (in.orderNo() == null || in.orderNo().isBlank()) throw new IllegalArgumentException("缺少订单号");
            conv = chatService.getOrCreateConversation(userId, email, in.orderNo().trim());
            joinRoom(self, convRoom(conv.id()));
            senderType = 0;
        }
        ChatDtos.ChatMessage msg = chatService.appendMessage(conv.id(), userId, senderType, in.content());
        broadcast(convRoom(conv.id()), Map.of("type", "message", "conversationId", conv.id(), "message", msg));
        // 通知客服大厅刷新会话列表（新会话 / 未读 / 最后一条消息）
        ChatDtos.Conversation fresh = chatService.findConversationById(conv.id());
        if (fresh != null) broadcast(ADMIN_HALL, Map.of("type", "conversation", "conversation", fresh));
    }

    private void handleSubscribe(WebSocketSession self, int role, Long conversationId) {
        if (role != 1) throw new IllegalArgumentException("无权限");
        if (conversationId == null) throw new IllegalArgumentException("缺少会话");
        ChatDtos.Conversation conv = chatService.findConversationById(conversationId);
        if (conv == null) throw new IllegalArgumentException("会话不存在");
        joinRoom(self, convRoom(conversationId));
        chatService.markReadByAdmin(conversationId);
        ChatDtos.Conversation fresh = chatService.findConversationById(conversationId);
        broadcast(ADMIN_HALL, Map.of("type", "conversation", "conversation", fresh == null ? conv : fresh));
    }

    private void handleRead(Long userId, int role, ChatDtos.Incoming in) {
        if (userId == null) return;
        if (role == 1) {
            if (in.conversationId() != null) chatService.markReadByAdmin(in.conversationId());
        } else if (in.conversationId() != null) {
            chatService.markReadByUser(in.conversationId(), userId);
        } else if (in.orderNo() != null && !in.orderNo().isBlank()) {
            ChatDtos.Conversation c = chatService.findConversationByOrder(in.orderNo().trim(), userId);
            if (c != null) chatService.markReadByUser(c.id(), userId);
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        decorators.remove(session.getId());
        leaveAll(session);
    }

    // ---------------- 房间与发送 ----------------

    private void joinRoom(WebSocketSession session, String room) {
        rooms.computeIfAbsent(room, k -> ConcurrentHashMap.newKeySet()).add(session);
        sessionRooms.computeIfAbsent(session.getId(), k -> ConcurrentHashMap.newKeySet()).add(room);
    }

    private void leaveAll(WebSocketSession session) {
        Set<String> mine = sessionRooms.remove(session.getId());
        if (mine == null) return;
        for (String room : mine) {
            Set<WebSocketSession> set = rooms.get(room);
            if (set != null) set.removeIf(s -> s.getId().equals(session.getId()));
        }
    }

    private void broadcast(String room, Object payload) {
        Set<WebSocketSession> set = rooms.get(room);
        if (set == null || set.isEmpty()) return;
        String json;
        try {
            json = objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            return;
        }
        TextMessage text = new TextMessage(json);
        for (WebSocketSession s : set) {
            if (s.isOpen()) {
                try {
                    s.sendMessage(text);
                } catch (Exception ignored) {
                    // 单个会话发送失败不影响其它会话
                }
            }
        }
    }

    private void send(WebSocketSession session, Object payload) {
        if (!session.isOpen()) return;
        try {
            session.sendMessage(new TextMessage(objectMapper.writeValueAsString(payload)));
        } catch (Exception ignored) {
        }
    }

    private void sendError(WebSocketSession session, String message) {
        Map<String, Object> body = new HashMap<>();
        body.put("type", "error");
        body.put("message", message);
        send(session, body);
    }
}
