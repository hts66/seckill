package com.example.seckill.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.example.seckill.dto.CreateProductRequest;
import com.example.seckill.entity.Product;

import java.util.List;

public interface ProductService extends IService<Product> {

    /**
     * 创建商品（含图片列表）
     */
    Product createProduct(CreateProductRequest request);

    /**
     * 更新商品
     */
    Product updateProduct(Long id, CreateProductRequest request);

    /**
     * 获取所有商品（含解析后的图片列表）
     */
    List<Product> getAllProducts();
}
