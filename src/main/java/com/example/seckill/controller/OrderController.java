package com.example.seckill.controller;

import com.example.seckill.dto.Response;
import com.example.seckill.entity.SeckillOrder;
import com.example.seckill.service.OrderService;
import com.example.seckill.utils.JwtUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/orders")
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;
    private final JwtUtil jwtUtil;

    /**
     * 查询用户秒杀订单
     */
    @GetMapping
    public Response<List<SeckillOrder>> getUserOrders(
            @RequestHeader("Authorization") String auth) {
        try {
            Long userId = jwtUtil.validateAndGetUserId(extractToken(auth));
            if (userId == null) return Response.error(401, "未登录");
            return Response.success(orderService.getUserOrders(userId));
        } catch (Exception e) {
            return Response.error(e.getMessage());
        }
    }

    /**
     * 查询单个订单详情
     */
    @GetMapping("/{orderNo}")
    public Response<SeckillOrder> getOrder(@PathVariable String orderNo) {
        try {
            return Response.success(orderService.getByOrderNo(orderNo));
        } catch (Exception e) {
            return Response.error(e.getMessage());
        }
    }

    /**
     * 支付订单（模拟）
     */
    @PostMapping("/pay/{orderNo}")
    public Response<String> payOrder(
            @RequestHeader("Authorization") String auth,
            @PathVariable String orderNo) {
        try {
            Long userId = jwtUtil.validateAndGetUserId(extractToken(auth));
            if (userId == null) return Response.error(401, "未登录");
            orderService.payOrder(userId, orderNo);
            return Response.success("支付成功");
        } catch (Exception e) {
            return Response.error(e.getMessage());
        }
    }

    /**
     * 取消订单
     */
    @PostMapping("/cancel/{orderNo}")
    public Response<String> cancelOrder(
            @RequestHeader("Authorization") String auth,
            @PathVariable String orderNo) {
        try {
            Long userId = jwtUtil.validateAndGetUserId(extractToken(auth));
            if (userId == null) return Response.error(401, "未登录");
            orderService.cancelOrder(userId, orderNo);
            return Response.success("取消成功");
        } catch (Exception e) {
            return Response.error(e.getMessage());
        }
    }

    private String extractToken(String auth) {
        return (auth != null && auth.startsWith("Bearer ")) ? auth.substring(7) : null;
    }
}
