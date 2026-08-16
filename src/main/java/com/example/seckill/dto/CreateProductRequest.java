package com.example.seckill.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * 创建/编辑商品请求
 */
@Data
public class CreateProductRequest {
    @NotBlank(message = "商品名称不能为空")
    private String name;

    private String title;
    private String description;
    private List<String> images;       // 图片URL列表（已上传到MinIO的URL）

    @NotNull(message = "价格不能为空")
    @DecimalMin(value = "0.01", message = "价格必须大于0")
    private BigDecimal price;

    @NotNull(message = "库存不能为空")
    @Min(value = 1, message = "库存至少为1")
    private Integer stock;
}
