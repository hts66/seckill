package com.example.seckill.cloud.order;

import com.example.seckill.cloud.common.ApiResponse;
import com.example.seckill.cloud.common.RequestUser;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** 客服聊天的历史消息与会话列表接口；实时收发走 WebSocket /ws/chat。 */
@RestController
public class ChatController {

    private final ChatService chatService;
    private final ChatPersistence persistence;

    public ChatController(ChatService chatService, ChatPersistence persistence) {
        this.chatService = chatService;
        this.persistence = persistence;
    }

    // ---------------- 用户端 ----------------

    /** 拉取某订单聊天历史（游标分页；会话不存在时返回空，不报错）。 */
    @GetMapping("/api/chat/orders/{orderNo}/messages")
    ApiResponse<ChatDtos.OrderChat> orderMessages(
            @PathVariable String orderNo,
            @RequestParam(required = false) Long beforeId,
            @RequestParam(required = false, defaultValue = "30") int size,
            HttpServletRequest request) {
        RequestUser user = RequestUser.require(request);
        return ApiResponse.ok(chatService.userOrderChat(orderNo, user.id(), beforeId, size));
    }

    /** 用户进入聊天窗口，异步清零自己的未读。 */
    @PostMapping("/api/chat/orders/{orderNo}/read")
    ApiResponse<Void> orderRead(@PathVariable String orderNo, HttpServletRequest request) {
        RequestUser user = RequestUser.require(request);
        ChatDtos.Conversation c = chatService.findConversationByOrder(orderNo, user.id());
        if (c != null) persistence.markReadAsync(c.id(), false);
        return ApiResponse.ok(null);
    }

    /** 用户各订单的未读消息数（orderNo -> count），用于订单页角标。 */
    @GetMapping("/api/chat/unread-counts")
    ApiResponse<java.util.Map<String, Integer>> unreadCounts(HttpServletRequest request) {
        RequestUser user = RequestUser.require(request);
        return ApiResponse.ok(chatService.userUnreadCounts(user.id()));
    }

    // ---------------- 客服端（管理员） ----------------

    @GetMapping("/api/admin/chat/conversations")
    ApiResponse<List<ChatDtos.Conversation>> adminConversations(HttpServletRequest request) {
        RequestUser.require(request).requireAdmin();
        return ApiResponse.ok(chatService.listAdminConversations());
    }

    @GetMapping("/api/admin/chat/conversations/{id}/messages")
    ApiResponse<List<ChatDtos.ChatMessage>> adminMessages(
            @PathVariable Long id,
            @RequestParam(required = false) Long beforeId,
            @RequestParam(required = false, defaultValue = "30") int size,
            HttpServletRequest request) {
        RequestUser.require(request).requireAdmin();
        return ApiResponse.ok(chatService.listMessages(id, beforeId, size));
    }

    @PostMapping("/api/admin/chat/conversations/{id}/read")
    ApiResponse<Void> adminRead(@PathVariable Long id, HttpServletRequest request) {
        RequestUser.require(request).requireAdmin();
        persistence.markReadAsync(id, true);
        return ApiResponse.ok(null);
    }
}
