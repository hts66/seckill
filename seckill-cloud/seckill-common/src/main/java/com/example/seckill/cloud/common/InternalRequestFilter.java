package com.example.seckill.cloud.common;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@Component
public class InternalRequestFilter extends OncePerRequestFilter {
    private final byte[] expected;

    public InternalRequestFilter(@Value("${security.internal-token}") String expected) {
        this.expected = expected.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (request.getRequestURI().startsWith("/actuator/")) {
            chain.doFilter(request, response);
            return;
        }
        String actual = request.getHeader(SecurityHeaders.INTERNAL_TOKEN);
        if (actual == null || !MessageDigest.isEqual(expected, actual.getBytes(StandardCharsets.UTF_8))) {
            response.setStatus(403);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"code\":403,\"message\":\"请通过网关访问\",\"data\":null}");
            return;
        }
        chain.doFilter(request, response);
    }
}
