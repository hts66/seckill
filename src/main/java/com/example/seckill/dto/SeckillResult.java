package com.example.seckill.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SeckillResult {
    private Long orderId;
    private String orderNo;
    private String productName;
    private BigDecimal amount;
    private LocalDateTime createTime;

    // 秒杀状态：0-排队中 1-抢到 2-已抢完 3-已买过 4-活动未开始 5-活动已结束
    private Integer status;
}
