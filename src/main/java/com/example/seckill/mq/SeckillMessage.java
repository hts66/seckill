package com.example.seckill.mq;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 秒杀下单消息体（MQ 传输对象）
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SeckillMessage implements Serializable {
    private static final long serialVersionUID = 1L;

    private Long userId;
    private Long itemId;
    private Long timestamp;        // 发起秒杀时间戳（用于防重）
}
