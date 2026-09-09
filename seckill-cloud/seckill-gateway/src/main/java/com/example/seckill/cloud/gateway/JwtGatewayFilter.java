package com.example.seckill.cloud.gateway;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidationException;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.List;

@Component
// 必须排在 WebSocket/HTTP 路由过滤器(Ordered.LOWEST_PRECEDENCE - 1)之前执行，
// 否则 WebSocket 升级请求会在鉴权头(X-User-* / X-Internal-Token)注入前就被转发到下游，导致 403。
// 注意：Spring Cloud Gateway 的 FilteringWebHandler 只按 `instanceof Ordered` 取顺序，
// 不识别 @Order 注解，因此这里必须实现 Ordered 接口而非使用 @Order。
public class JwtGatewayFilter implements GlobalFilter, Ordered {
    private static final Logger log = LoggerFactory.getLogger(JwtGatewayFilter.class);
    private static final List<String> PUBLIC_PREFIXES = List.of(
            "/api/auth/", "/api/captcha", "/api/products", "/api/seckill/items", "/api/seckill/upcoming",
            "/actuator/health", "/actuator/info");

    private final JwtDecoder jwtDecoder;
    private final String internalToken;

    public JwtGatewayFilter(JwtDecoder jwtDecoder, @Value("${security.internal-token}") String internalToken) {
        this.jwtDecoder = jwtDecoder;
        this.internalToken = internalToken;
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getURI().getPath();
        String authorization = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        boolean publicPath = PUBLIC_PREFIXES.stream().anyMatch(path::startsWith);

        // 浏览器原生 WebSocket 无法自定义 Authorization 头，聊天握手的 token 放在 query 参数 ?token= 上
        boolean wsHandshake = path.startsWith("/ws/");
        String token = null;
        if (wsHandshake) {
            token = exchange.getRequest().getQueryParams().getFirst("token");
        } else if (authorization != null && authorization.startsWith("Bearer ")) {
            token = authorization.substring(7);
        }

        if (token == null || token.isBlank()) {
            if (publicPath) {
                return chain.filter(sanitize(exchange, null));
            }
            return error(exchange, 401, wsHandshake ? "聊天登录已过期，请刷新后重试" : "请先登录");
        }

        try {
            Jwt jwt = jwtDecoder.decode(token);
            if (!"access".equals(jwt.getClaimAsString("type"))) {
                return error(exchange, 401, "访问令牌无效");
            }

            // Nimbus 通常把 JWT JSON 中的整数解析成 Long，不能直接强制转换成 Integer。
            Number role = jwt.getClaim("role");
            if ((path.startsWith("/api/admin") || path.startsWith("/api/seckill/admin"))
                    && (role == null || role.intValue() != 1)) {
                return error(exchange, 403, "没有管理权限");
            }
            return chain.filter(sanitize(exchange, jwt));
        } catch (JwtValidationException e) {
            boolean expired = e.getErrors().stream()
                    .anyMatch(item -> item.getDescription().toLowerCase().contains("expired"));
            log.warn("JWT validation failed for {}: {}", path, e.getMessage());
            return error(exchange, 401, expired ? "登录状态已过期" : "登录令牌校验失败，请重新登录");
        } catch (Exception e) {
            log.warn("JWT authentication failed for {}: {}", path, e.toString());
            return error(exchange, 401, "登录令牌无效，请重新登录");
        }
    }

    private ServerWebExchange sanitize(ServerWebExchange exchange, Jwt jwt) {
        var request = exchange.getRequest().mutate()
                .headers(headers -> {
                    headers.remove("X-User-Id");
                    headers.remove("X-User-Email");
                    headers.remove("X-User-Role");
                    headers.remove("X-Internal-Token");
                });
        request.header("X-Internal-Token", internalToken);
        if (jwt != null) {
            request.header("X-User-Id", jwt.getSubject());
            request.header("X-User-Email", jwt.getClaimAsString("email"));
            Number role = jwt.getClaim("role");
            request.header("X-User-Role", role == null ? "0" : String.valueOf(role.intValue()));
        }
        return exchange.mutate().request(request.build()).build();
    }

    private Mono<Void> error(ServerWebExchange exchange, int code, String message) {
        exchange.getResponse().setStatusCode(HttpStatus.valueOf(code));
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        DataBuffer buffer = exchange.getResponse().bufferFactory().wrap(
                ("{\"code\":" + code + ",\"message\":\"" + message + "\",\"data\":null}")
                        .getBytes(StandardCharsets.UTF_8));
        return exchange.getResponse().writeWith(Mono.just(buffer));
    }
}
