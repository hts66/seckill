package com.example.seckill.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.example.seckill.dto.CreateProductRequest;
import com.example.seckill.entity.Product;
import com.example.seckill.mapper.ProductMapper;
import com.example.seckill.service.ProductService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProductServiceImpl extends ServiceImpl<ProductMapper, Product>
        implements ProductService {

    @Override
    @Transactional
    public Product createProduct(CreateProductRequest request) {
        Product product = new Product();
        product.setName(request.getName());
        product.setTitle(request.getTitle());
        product.setDescription(request.getDescription());
        product.setImageList(request.getImages());
        product.setPrice(request.getPrice());
        product.setStock(request.getStock());

        save(product);
        log.info("商品创建成功 id={} name={}", product.getId(), product.getName());
        return product;
    }

    @Override
    @Transactional
    public Product updateProduct(Long id, CreateProductRequest request) {
        Product product = getById(id);
        if (product == null) {
            throw new RuntimeException("商品不存在");
        }

        product.setName(request.getName());
        product.setTitle(request.getTitle());
        product.setDescription(request.getDescription());
        product.setImageList(request.getImages());
        product.setPrice(request.getPrice());
        product.setStock(request.getStock());

        updateById(product);
        log.info("商品更新成功 id={}", id);
        return product;
    }

    @Override
    public List<Product> getAllProducts() {
        return list();
    }
}
