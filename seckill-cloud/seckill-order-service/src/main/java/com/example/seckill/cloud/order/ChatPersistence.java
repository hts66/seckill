package com.example.seckill.cloud.order;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 聊天消息异步落库：WebSocket 线程只生成雪花 ID 并入队（纳秒级），
 * 本组件定时把队列里的消息批量 INSERT，并按会话聚合更新未读数/最后一条摘要。
 * 落库成功后经 RabbitMQ fanout 通知【所有实例】（含本机）推送实时帧：
 * 发起实例的会话房间已在发送瞬间即时广播，收到自己发的扇出消息时只刷客服大厅。
 * 有界队列作为背压保护——队列满时发送方收到“系统繁忙”，而不是把内存撑爆。
 */
@Component
public class ChatPersistence {
    private static final Logger log = LoggerFactory.getLogger(ChatPersistence.class);
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final ChatDataSourceManager ds;
    private final ChatService chatService;
    private final ChatInstance instance;
    private final RabbitTemplate rabbit;
    private final BlockingQueue<ChatDtos.PendingMessage> queue;

    /** 已读清零这类低频写也隔离到独立小线程池，不占用 Web 工作线程。 */
    private final ThreadPoolExecutor dbExecutor = new ThreadPoolExecutor(
            1, 2, 30L, TimeUnit.SECONDS,
            new java.util.concurrent.LinkedBlockingQueue<>(256),
            r -> {
                Thread t = new Thread(r, "chat-db");
                t.setDaemon(true);
                return t;
            },
            new ThreadPoolExecutor.DiscardOldestPolicy());

    public ChatPersistence(ChatDataSourceManager ds, ChatService chatService, ChatInstance instance,
                           RabbitTemplate rabbit,
                           @Value("${app.chat.queue-capacity:10000}") int capacity) {
        this.ds = ds;
        this.chatService = chatService;
        this.instance = instance;
        this.rabbit = rabbit;
        this.queue = new ArrayBlockingQueue<>(capacity);
    }

    /** 入队，队列满返回 false（调用方据此提示系统繁忙）。 */
    public boolean enqueue(ChatDtos.PendingMessage m) {
        return queue.offer(m);
    }

    public String format(java.time.LocalDateTime time) {
        return FMT.format(time);
    }

    /**
     * 批量刷盘：每 200ms 或队列积攒到阈值时触发。
     * 消息批量 INSERT；同一会话的未读/摘要只做一次聚合 UPDATE；随后扇出给集群所有实例。
     */
    @Scheduled(fixedDelayString = "${app.chat.flush-interval-ms:200}")
    public void flush() {
        List<ChatDtos.PendingMessage> batch = new ArrayList<>();
        queue.drainTo(batch, 200);
        if (batch.isEmpty()) return;
        try {
            insertBatch(batch);
            List<Long> convIds = updateConversations(batch);
            fanoutBatch(batch, convIds);
        } catch (Exception e) {
            // 这批丢失会影响历史记录，但不能让定时任务彻底死掉；生产可在此补发/落本地文件
            log.error("聊天消息批量落库失败，本批 {} 条: {}", batch.size(), e.toString());
        }
    }

    private void insertBatch(List<ChatDtos.PendingMessage> batch) {
        ds.jdbcTemplate().batchUpdate(
                "INSERT INTO chat_message(id,conversation_id,sender_id,sender_type,content,created_at) " +
                        "VALUES(?,?,?,?,?,?)",
                new BatchPreparedStatementSetter() {
                    @Override
                    public void setValues(PreparedStatement ps, int i) throws SQLException {
                        ChatDtos.PendingMessage m = batch.get(i);
                        ps.setLong(1, m.id());
                        ps.setLong(2, m.conversationId());
                        ps.setLong(3, m.senderId());
                        ps.setInt(4, m.senderType());
                        ps.setString(5, m.content());
                        ps.setTimestamp(6, Timestamp.valueOf(m.createdAt()));
                    }

                    @Override
                    public int getBatchSize() {
                        return batch.size();
                    }
                });
    }

    /** 按会话聚合：一次批次里同一会话只更新一次（最后内容 + 未读增量）。返回受影响的会话 id。 */
    private List<Long> updateConversations(List<ChatDtos.PendingMessage> batch) {
        // 保持插入顺序，最后一条作为摘要
        Map<Long, ChatDtos.PendingMessage> last = new LinkedHashMap<>();
        Map<Long, int[]> unread = new LinkedHashMap<>(); // [userMsgCount, adminMsgCount]
        for (ChatDtos.PendingMessage m : batch) {
            last.put(m.conversationId(), m);
            int[] u = unread.computeIfAbsent(m.conversationId(), k -> new int[2]);
            if (m.senderType() == 1) u[1]++; else u[0]++;
        }
        for (Map.Entry<Long, ChatDtos.PendingMessage> e : last.entrySet()) {
            Long convId = e.getKey();
            ChatDtos.PendingMessage m = e.getValue();
            int[] u = unread.get(convId);
            ds.jdbcClient().sql("UPDATE chat_conversation SET last_content=:content, last_at=:at, " +
                            "unread_admin=unread_admin+:ua, unread_user=unread_user+:uu WHERE id=:id")
                    .param("content", m.content())
                    .param("at", m.createdAt())
                    .param("ua", u[0])
                    .param("uu", u[1])
                    .param("id", convId)
                    .update();
        }
        return new ArrayList<>(last.keySet());
    }

    /** 扇出本批消息视图 + 最新会话快照给集群所有实例（推送失败只影响实时性，历史已在 MySQL）。 */
    private void fanoutBatch(List<ChatDtos.PendingMessage> batch, List<Long> convIds) {
        try {
            List<ChatDtos.ChatMessage> views = new ArrayList<>(batch.size());
            for (ChatDtos.PendingMessage m : batch) {
                views.add(m.toView(FMT.format(m.createdAt())));
            }
            List<ChatDtos.Conversation> snapshots = new ArrayList<>(convIds.size());
            for (Long convId : convIds) {
                ChatDtos.Conversation fresh = chatService.findConversationById(convId);
                if (fresh != null) snapshots.add(fresh);
            }
            rabbit.convertAndSend(ChatTopology.FANOUT_EXCHANGE, "",
                    ChatFanoutEnvelope.batch(instance.id(), views, snapshots));
        } catch (Exception e) {
            log.warn("聊天消息扇出失败（不影响落库）: {}", e.toString());
        }
    }

    /** 用户打开会话清零“客服未读”；客服打开清零“用户未读”。异步执行避免阻塞 HTTP 线程。 */
    public void markReadAsync(long conversationId, boolean byAdmin) {
        dbExecutor.execute(() -> {
            try {
                if (byAdmin) {
                    ds.jdbcClient().sql("UPDATE chat_conversation SET unread_admin=0 WHERE id=:id")
                            .param("id", conversationId).update();
                } else {
                    ds.jdbcClient().sql("UPDATE chat_conversation SET unread_user=0 WHERE id=:id")
                            .param("id", conversationId).update();
                }
                fanoutChanged(List.of(conversationId));
            } catch (Exception e) {
                log.warn("聊天已读清零失败 conv={}: {}", conversationId, e.toString());
            }
        });
    }

    /** 已读等会话变更扇出：让其它实例上的客服大厅同步未读数（各实例自行回查 MySQL）。 */
    private void fanoutChanged(List<Long> convIds) {
        try {
            rabbit.convertAndSend(ChatTopology.FANOUT_EXCHANGE, "",
                    ChatFanoutEnvelope.changed(instance.id(), convIds));
        } catch (Exception e) {
            log.warn("聊天会话变更扇出失败: {}", e.toString());
        }
    }
}
