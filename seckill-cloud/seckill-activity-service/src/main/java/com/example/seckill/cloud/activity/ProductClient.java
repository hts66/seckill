package com.example.seckill.cloud.activity;
import com.example.seckill.cloud.common.*;import org.springframework.cloud.openfeign.FeignClient;import org.springframework.web.bind.annotation.*;import java.math.BigDecimal;
@FeignClient(name="seckill-product-service")public interface ProductClient{@GetMapping("/api/products/{id}")ApiResponse<ProductSnapshot>one(@PathVariable Long id,@RequestHeader(SecurityHeaders.INTERNAL_TOKEN)String internalToken);record ProductSnapshot(Long id,String name,String title,String description,String images,BigDecimal price,Integer stock){}}
