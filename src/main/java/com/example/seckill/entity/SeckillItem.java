package com.example.seckill.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("seckill_items")
public class SeckillItem {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long activityId;
    private Long productId;
    private BigDecimal seckillPrice;
    private Integer stock;
    private Integer limitPerUser;
    private Integer status;        // 0-下架 1-上架
    private LocalDateTime createdAt;

    // ---- 非数据库字段，联表查询用 ----
    @TableField(exist = false)
    private String productName;

    @TableField(exist = false)
    private String productTitle;

    @TableField(exist = false)
    private String productImages;     // JSON数组字符串

    @TableField(exist = false)
    private BigDecimal originalPrice;

    @TableField(exist = false)
    private String activityName;

    @TableField(exist = false)
    private LocalDateTime activityPreviewTime;

    @TableField(exist = false)
    private LocalDateTime activityStartTime;

    @TableField(exist = false)
    private LocalDateTime activityEndTime;
}
