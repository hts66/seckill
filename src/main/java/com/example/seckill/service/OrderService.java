package com.example.seckill.service;

import com.example.seckill.entity.SeckillOrder;

import java.util.List;

public interface OrderService {
    List<SeckillOrder> getUserOrders(Long userId);
    SeckillOrder getByOrderNo(String orderNo);
    boolean payOrder(Long userId, String orderNo);
    boolean cancelOrder(Long userId, String orderNo);
}
