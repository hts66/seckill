package com.example.seckill.cloud.order;

/**
 * 订单超时关单延迟消息：建单成功后投递到 TTL 延迟队列，
 * 死信到期后由 OrderCloseConsumer 消费；消息丢失时还有定时扫表兜底。
 */
public record OrderCloseMessage(String orderNo, Long userId, Long itemId) {}
