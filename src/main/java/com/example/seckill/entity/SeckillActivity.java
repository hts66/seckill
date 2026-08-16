package com.example.seckill.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("seckill_activities")
public class SeckillActivity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String name;
    private LocalDateTime previewTime;   // 预热展示时间（用户可看到但不可抢）
    private LocalDateTime startTime;     // 开抢时间
    private LocalDateTime endTime;
    private Integer status;       // 0-未开始 1-预热中 2-进行中 3-已结束
    private String description;
    private LocalDateTime createdAt;
}
