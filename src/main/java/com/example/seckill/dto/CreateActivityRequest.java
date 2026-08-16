package com.example.seckill.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 创建秒杀活动请求（关联商品构建秒杀项）
 */
@Data
public class CreateActivityRequest {
    private String name;
    private String description;
    private LocalDateTime previewTime;   // 预热展示时间
    private LocalDateTime startTime;     // 开抢时间
    private LocalDateTime endTime;       // 结束时间

    /**
     * 秒杀项配置
     */
    @Data
    public static class ItemConfig {
        private Long productId;
        private BigDecimal seckillPrice;
        private Integer stock;
        private Integer limitPerUser;
    }

    private java.util.List<ItemConfig> items;  // 活动包含的秒杀商品
}
