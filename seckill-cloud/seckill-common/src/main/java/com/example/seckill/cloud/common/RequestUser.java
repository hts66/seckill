package com.example.seckill.cloud.common;

import jakarta.servlet.http.HttpServletRequest;

public record RequestUser(Long id, String email, int role) {
    public static RequestUser require(HttpServletRequest request) {
        String id = request.getHeader(SecurityHeaders.USER_ID);
        if (id == null || id.isBlank()) throw new IllegalStateException("请先登录");
        return new RequestUser(Long.valueOf(id), request.getHeader(SecurityHeaders.USER_EMAIL),
                Integer.parseInt(request.getHeader(SecurityHeaders.USER_ROLE)));
    }
    public void requireAdmin() {
        if (role != 1) throw new IllegalStateException("没有管理权限");
    }
}
