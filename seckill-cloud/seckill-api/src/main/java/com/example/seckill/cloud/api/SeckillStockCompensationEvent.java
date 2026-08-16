package com.example.seckill.cloud.api;
import java.io.Serializable;
public record SeckillStockCompensationEvent(String eventId,Long userId,Long itemId,String reason) implements Serializable{}
