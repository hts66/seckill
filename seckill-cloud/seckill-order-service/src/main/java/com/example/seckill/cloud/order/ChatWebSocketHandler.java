package com.example.seckill.cloud.order;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 客服聊天 WebSocket 处理器（L1 高并发加固版）：
 *   1) WS 线程不做消息落库——只生成雪花 ID、入有界队列后立即广播，落库由 {@link ChatPersistence} 批量完成；
 *   2) 单用户连接数上限，防止单账号刷连接；
 *   3) 发消息按用户限流（{@link ChatRateLimiter}）；
 *   4) 心跳超时的僵尸连接定时关闭；
 *   5) 房间容器并发安全，广播为锁内快照、锁外发送，支持同账号多端在线。
 */
@Component
public class ChatWebSocketHandler extends TextWebSocketHandler {
    private static final Logger log = LoggerFactory.getLogger(ChatWebSocketHandler.class);
    private static final String ADMIN_HALL = "admin:hall";

    private final ChatService chatService;
    private final ChatPersistence persistence;
    private final ChatRateLimiter rateLimiter;
    private final ChatInstance instance;
    private final ObjectMapper objectMapper;
    private final int maxConnectionsPerUser;
    private final long idleTimeoutMillis;

    private final Map<String, Set<WebSocketSession>> rooms = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> sessionRooms = new ConcurrentHashMap<>();
    private final Map<String, WebSocketSession> decorators = new ConcurrentHashMap<>();
    /** userId -> 该用户在线的 sessionId 集合（连接数限制）。 */
    private final Map<Long, Set<String>> userConnections = new ConcurrentHashMap<>();
    /** sessionId -> 最近一次活动时间（心跳/消息刷新）。 */
    private final Map<String, Long> lastActive = new ConcurrentHashMap<>();

    public ChatWebSocketHandler(ChatService chatService, ChatPersistence persistence,
                               ChatRateLimiter rateLimiter, ChatInstance instance, ObjectMapper objectMapper,
                               @Value("${app.chat.max-connections-per-user:4}") int maxConnectionsPerUser,
                               @Value("${app.chat.idle-timeout-seconds:90}") int idleTimeoutSeconds) {
        this.chatService = chatService;
        this.persistence = persistence;
        this.rateLimiter = rateLimiter;
        this.instance = instance;
        this.objectMapper = objectMapper;
        this.maxConnectionsPerUser = maxConnectionsPerUser;
        this.idleTimeoutMillis = idleTimeoutSeconds * 1000L;
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
        lastActive.put(session.getId(), System.currentTimeMillis());

        if (userId != null) {
            Set<String> mine = userConnections.computeIfAbsent(userId, k -> ConcurrentHashMap.newKeySet());
            if (mine.size() >= maxConnectionsPerUser) {
                sendError(self, "连接数过多，已限制多端同时在线（最多 " + maxConnectionsPerUser + " 个）");
                closeQuietly(session);
                return;
            }
            mine.add(session.getId());
        }

        if (roleValue == 1) {
            joinRoom(self, ADMIN_HALL);
        } else if (userId != null) {
            // 用户自动加入自己的全部会话房间，保证多端实时收消息（轻量主键查询，走聊天独立连接池）
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
        lastActive.put(session.getId(), System.currentTimeMillis());

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
        if (!rateLimiter.tryAcquire(userId)) throw new IllegalArgumentException("发言太频繁，请稍后再试");

        ChatDtos.Conversation conv;
        int senderType;
        if (role == 1) {
            if (in.conversationId() == null) throw new IllegalArgumentException("缺少会话");
            conv = chatService.findConversationById(in.conversationId());
            if (conv == null) throw new IllegalArgumentException("会话不存在");
            senderType = 1;
        } else {
            if (in.orderNo() == null || in.orderNo().isBlank()) throw new IllegalArgumentException("缺少订单号");
            // 会话创建是每会话一次的轻量写，走聊天独立池，不碰秒杀主连接池
            conv = chatService.getOrCreateConversation(userId, email, in.orderNo().trim());
            joinRoom(self, convRoom(conv.id()));
            senderType = 0;
        }

        ChatDtos.PendingMessage pending =
                chatService.buildPendingMessage(conv.id(), userId, senderType, in.content());
        // 有界队列背压：落库队列满时拒收，避免内存被刷爆
        if (!persistence.enqueue(pending)) {
            sendError(self, "消息系统繁忙，请稍后再发");
            return;
        }
        String time = persistence.format(LocalDateTime.now());
        broadcast(convRoom(conv.id()),
                Map.of("type", "message", "conversationId", conv.id(), "message", pending.toView(time)));
        // 其它实例上的同会话连接与所有客服大厅，由落库后的 fanout 扇出刷新（约 200ms）
    }

    private void handleSubscribe(WebSocketSession self, int role, Long conversationId) {
        if (role != 1) throw new IllegalArgumentException("无权限");
        if (conversationId == null) throw new IllegalArgumentException("缺少会话");
        ChatDtos.Conversation conv = chatService.findConversationById(conversationId);
        if (conv == null) throw new IllegalArgumentException("会话不存在");
        joinRoom(self, convRoom(conversationId));
        persistence.markReadAsync(conversationId, true);
    }

    private void handleRead(Long userId, int role, ChatDtos.Incoming in) {
        if (userId == null) return;
        if (role == 1 && in.conversationId() != null) {
            persistence.markReadAsync(in.conversationId(), true);
        } else if (role != 1 && in.conversationId() != null) {
            persistence.markReadAsync(in.conversationId(), false);
        } else if (role != 1 && in.orderNo() != null && !in.orderNo().isBlank()) {
            ChatDtos.Conversation c = chatService.findConversationByOrder(in.orderNo().trim(), userId);
            if (c != null) persistence.markReadAsync(c.id(), false);
        }
    }

    /**
     * 接收集群扇出（队列名来自本实例独占队列 Bean）：
     *  - BATCH：非本机发起的消息推给本机会话房间；会话快照所有实例都刷新客服大厅（含发起方回环）；
     *  - CHANGED：已读等变更，回查 MySQL 后刷新本机客服大厅。
     */
    @RabbitListener(queues = "#{@chatFanoutQueue.name}")
    public void onFanout(ChatFanoutEnvelope env) {
        boolean fromSelf = instance.id().equals(env.origin());
        if (env.messages() != null && !fromSelf) {
            for (ChatDtos.ChatMessage m : env.messages()) {
                broadcast(convRoom(m.conversationId()),
                        Map.of("type", "message", "conversationId", m.conversationId(), "message", m));
            }
        }
        if (env.conversations() != null) {
            for (ChatDtos.Conversation c : env.conversations()) {
                broadcast(ADMIN_HALL, Map.of("type", "conversation", "conversation", c));
            }
        }
        if (env.changedConvIds() != null) {
            for (Long convId : env.changedConvIds()) {
                ChatDtos.Conversation fresh = chatService.findConversationById(convId);
                if (fresh != null) broadcast(ADMIN_HALL, Map.of("type", "conversation", "conversation", fresh));
            }
        }
    }

    /** 定时清理心跳超时的僵尸连接，释放连接数名额与内存。 */
    @Scheduled(fixedDelayString = "${app.chat.idle-scan-ms:30000}")
    public void evictIdleConnections() {
        long now = System.currentTimeMillis();
        for (WebSocketSession s : decorators.values()) {
            Long t = lastActive.get(s.getId());
            if (t != null && now - t > idleTimeoutMillis) {
                log.info("聊天连接空闲超时关闭 sessionId={}", s.getId());
                closeQuietly(s);
            }
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        decorators.remove(session.getId());
        lastActive.remove(session.getId());
        leaveAll(session);
        Long userId = (Long) session.getAttributes().get(ChatHandshakeInterceptor.ATTR_USER_ID);
        if (userId != null) {
            Set<String> mine = userConnections.get(userId);
            if (mine != null) {
                mine.remove(session.getId());
                if (mine.isEmpty()) userConnections.remove(userId);
            }
        }
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

    /** 锁内取快照、锁外发送，避免广播 IO 放大锁持有时间。 */
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
        for (WebSocketSession s : set.toArray(new WebSocketSession[0])) {
            if (s.isOpen()) {
                try {
                    s.sendMessage(text);
                } catch (Exception ignored) {
                    // 单个会话失败不影响其它接收方
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

    private void closeQuietly(WebSocketSession session) {
        try {
            session.close(CloseStatus.POLICY_VIOLATION);
        } catch (Exception ignored) {
        }
    }
}
