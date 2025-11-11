package com.example.gatewayservice.filter; // 注意包名

import com.example.gatewayservice.util.JwtUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@Component
public class AuthGlobalFilter implements GlobalFilter, Ordered {

    @Autowired
    private JwtUtil jwtUtil;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getURI().getPath();

        // 1. 白名单：登录接口，直接放行
        if (path.contains("/auth/login")) {
            return chain.filter(exchange);
        }

        // (可选) 放行 Prometheus 抓取
        if (path.contains("/actuator")) {
            return chain.filter(exchange);
        }

        // 2. 检查 Token
        String token = exchange.getRequest().getHeaders().getFirst("Authorization");
        if (token == null || !token.startsWith("Bearer ")) {
            exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
            return exchange.getResponse().setComplete(); // 拒绝
        }

        // 3. 验证 Token
        String jwt = token.substring(7); // 截掉 "Bearer "
        if (!jwtUtil.isTokenValid(jwt)) {
            exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
            return exchange.getResponse().setComplete(); // 拒绝
        }

        // 4. 【核心】将用户信息“注入”到下游请求中
        String userId = jwtUtil.getUserIdFromToken(jwt);

        // 修改原始请求，添加一个安全的 Header
        ServerHttpRequest mutatedRequest = exchange.getRequest().mutate()
                .header("X-User-Id", userId)
                .build();

        ServerWebExchange mutatedExchange = exchange.mutate().request(mutatedRequest).build();

        // 5. 放行（使用被修改过的请求）
        return chain.filter(mutatedExchange);
    }

    @Override
    public int getOrder() {
        return -1; // 保证这个过滤器在所有路由过滤器之前执行
    }
}