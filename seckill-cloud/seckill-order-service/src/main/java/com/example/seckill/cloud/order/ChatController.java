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

    public ChatController(ChatService chatService) {
        this.chatService = chatService;
    }

    // ---------------- 用户端 ----------------

    /** 拉取某订单的聊天历史（会话不存在时返回空，不报错）。 */
    @GetMapping("/api/chat/orders/{orderNo}/messages")
    ApiResponse<ChatDtos.OrderChat> orderMessages(@PathVariable String orderNo, HttpServletRequest request) {
        RequestUser user = RequestUser.require(request);
        return ApiResponse.ok(chatService.userOrderChat(orderNo, user.id()));
    }

    /** 用户进入聊天窗口，清零自己的未读数。 */
    @PostMapping("/api/chat/orders/{orderNo}/read")
    ApiResponse<Void> orderRead(@PathVariable String orderNo, HttpServletRequest request) {
        RequestUser user = RequestUser.require(request);
        ChatDtos.Conversation c = chatService.findConversationByOrder(orderNo, user.id());
        if (c != null) chatService.markReadByUser(c.id(), user.id());
        return ApiResponse.ok(null);
    }

    // ---------------- 客服端（管理员） ----------------

    @GetMapping("/api/admin/chat/conversations")
    ApiResponse<List<ChatDtos.Conversation>> adminConversations(HttpServletRequest request) {
        RequestUser.require(request).requireAdmin();
        return ApiResponse.ok(chatService.listAdminConversations());
    }

    @GetMapping("/api/admin/chat/conversations/{id}/messages")
    ApiResponse<List<ChatDtos.ChatMessage>> adminMessages(@PathVariable Long id, HttpServletRequest request) {
        RequestUser.require(request).requireAdmin();
        return ApiResponse.ok(chatService.listMessages(id));
    }

    @PostMapping("/api/admin/chat/conversations/{id}/read")
    ApiResponse<Void> adminRead(@PathVariable Long id, HttpServletRequest request) {
        RequestUser.require(request).requireAdmin();
        chatService.markReadByAdmin(id);
        return ApiResponse.ok(null);
    }
}
