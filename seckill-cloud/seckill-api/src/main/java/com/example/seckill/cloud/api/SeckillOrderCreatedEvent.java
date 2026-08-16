package com.example.seckill.cloud.api;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

public record SeckillOrderCreatedEvent(String eventId, Long userId, Long itemId, Long activityId,
                                       BigDecimal amount, Long addressId, LocalDateTime requestedAt) implements Serializable {}
