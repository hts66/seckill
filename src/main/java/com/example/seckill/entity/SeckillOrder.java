package com.example.seckill.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("seckill_orders")
public class SeckillOrder {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private Long itemId;
    private String orderNo;
    private BigDecimal amount;
    private Integer status;        // 0-待支付 1-已支付 2-已取消 3-超时取消
    private LocalDateTime createdAt;
    private LocalDateTime payTime;
    private LocalDateTime cancelTime;

    // ---- 非数据库字段 ----
    @TableField(exist = false)
    private String productName;

    @TableField(exist = false)
    private BigDecimal seckillPrice;
}
