package com.example.seckill.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

@Data
@TableName("products")
public class Product {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TableId(type = IdType.AUTO)
    private Long id;
    private String name;
    private String title;
    private String description;
    private String images;           // JSON数组字符串，存储MinIO URL列表
    private BigDecimal price;
    private Integer stock;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    /**
     * 获取图片URL列表（解析JSON）- 不映射到数据库
     */
    public List<String> getImageList() {
        if (images == null || images.isBlank()) return Collections.emptyList();
        try {
            return MAPPER.readValue(images, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    /**
     * 设置图片URL列表（序列化为JSON）
     */
    public void setImageList(List<String> urls) {
        if (urls == null || urls.isEmpty()) {
            this.images = "[]";
        } else {
            try {
                this.images = MAPPER.writeValueAsString(urls);
            } catch (Exception e) {
                this.images = "[]";
            }
        }
    }
}
