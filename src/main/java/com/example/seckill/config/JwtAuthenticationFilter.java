package com.example.seckill.config;

import com.example.seckill.entity.User;
import com.example.seckill.service.UserService;
import com.example.seckill.utils.JwtUtil;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {
    private final JwtUtil jwtUtil;
    private final UserService userService;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String authorization = request.getHeader("Authorization");
        if (authorization != null && authorization.startsWith("Bearer ")) {
            try {
                Claims claims = jwtUtil.parseToken(authorization.substring(7));
                if ("access".equals(claims.get("type", String.class))) {
                    User user = userService.getById(Long.parseLong(claims.getSubject()));
                    Integer tokenVersion = claims.get("tokenVersion", Integer.class);
                    int currentVersion = user == null || user.getTokenVersion() == null ? 0 : user.getTokenVersion();
                    if (user != null && user.getDeletedAt() == null && user.getStatus() != null && user.getStatus() == 1
                            && tokenVersion != null && tokenVersion == currentVersion) {
                        String role = user.getRole() != null && user.getRole() == 1 ? "ROLE_ADMIN" : "ROLE_USER";
                        var authentication = new UsernamePasswordAuthenticationToken(
                                user.getId(), null, List.of(new SimpleGrantedAuthority(role)));
                        authentication.setDetails(user);
                        SecurityContextHolder.getContext().setAuthentication(authentication);
                    }
                }
            } catch (Exception ignored) {
                SecurityContextHolder.clearContext();
            }
        }
        chain.doFilter(request, response);
    }
}
