package com.example.seckill.cloud.order;

import com.example.seckill.cloud.common.SecurityHeaders;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.Map;

/**
 * WebSocket 握手拦截器：网关已完成 JWT 校验并注入 X-User-* 头，
 * 这里把用户身份放进 WebSocket session 属性，供 Handler 使用。
 * 缺少身份头说明未经网关/未登录，拒绝握手。
 */
@Component
public class ChatHandshakeInterceptor implements HandshakeInterceptor {

    public static final String ATTR_USER_ID = "chatUserId";
    public static final String ATTR_ROLE = "chatRole";
    public static final String ATTR_EMAIL = "chatEmail";

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Map<String, Object> attributes) {
        if (request instanceof ServletServerHttpRequest servletRequest) {
            HttpServletRequest req = servletRequest.getServletRequest();
            String userId = req.getHeader(SecurityHeaders.USER_ID);
            String role = req.getHeader(SecurityHeaders.USER_ROLE);
            if (userId == null || userId.isBlank()) {
                response.setStatusCode(HttpStatus.UNAUTHORIZED);
                return false;
            }
            attributes.put(ATTR_USER_ID, Long.valueOf(userId));
            attributes.put(ATTR_ROLE, role == null || role.isBlank() ? 0 : Integer.parseInt(role));
            attributes.put(ATTR_EMAIL, req.getHeader(SecurityHeaders.USER_EMAIL));
            return true;
        }
        response.setStatusCode(HttpStatus.UNAUTHORIZED);
        return false;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler wsHandler, Exception exception) {
    }
}
