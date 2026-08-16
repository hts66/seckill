package com.example.seckill.controller;

import com.example.seckill.dto.CreateProductRequest;
import com.example.seckill.dto.Response;
import com.example.seckill.entity.Product;
import com.example.seckill.service.FileStorageService;
import com.example.seckill.service.ProductService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 商品管理 + 图片上传
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/products")
@RequiredArgsConstructor
public class ProductController {

    private final ProductService productService;
    private final FileStorageService fileStorageService;

    // ==================== 图片上传 ====================

    /**
     * 上传单张图片到 MinIO
     * @return { url: "http://..." }
     */
    @PostMapping("/upload")
    public Response<Map<String, String>> uploadImage(@RequestParam("file") MultipartFile file) {
        try {
            if (file.isEmpty()) {
                return Response.error(400, "文件不能为空");
            }
            // 校验文件类型
            String contentType = file.getContentType();
            if (contentType == null || !contentType.startsWith("image/")) {
                return Response.error(400, "只能上传图片文件");
            }
            String url = fileStorageService.upload(file);
            return Response.success(Map.of("url", url));
        } catch (Exception e) {
            log.error("图片上传失败", e);
            return Response.error("上传失败: " + e.getMessage());
        }
    }

    /**
     * 批量上传多张图片
     * @return { urls: ["http://...", ...] }
     */
    @PostMapping("/upload/batch")
    public Response<Map<String, Object>> uploadImages(@RequestParam("files") List<MultipartFile> files) {
        try {
            List<String> urls = new ArrayList<>();
            for (MultipartFile file : files) {
                if (!file.isEmpty()) {
                    String url = fileStorageService.upload(file);
                    urls.add(url);
                }
            }
            return Response.success(Map.of("urls", urls));
        } catch (Exception e) {
            log.error("批量上传失败", e);
            return Response.error("上传失败: " + e.getMessage());
        }
    }

    // ==================== 商品 CRUD ====================

    /**
     * 创建商品
     */
    @PostMapping
    public Response<Product> create(@Valid @RequestBody CreateProductRequest request) {
        try {
            Product product = productService.createProduct(request);
            return Response.success(product);
        } catch (Exception e) {
            log.error("创建商品失败", e);
            return Response.error(e.getMessage());
        }
    }

    /**
     * 更新商品（自动清理不再使用的旧图片）
     */
    @PutMapping("/{id}")
    public Response<Product> update(@PathVariable Long id, @Valid @RequestBody CreateProductRequest request) {
        try {
            // 获取旧图片列表，用于对比清理
            Product oldProduct = productService.getById(id);
            List<String> oldImages = oldProduct != null ? oldProduct.getImageList() : List.of();
            List<String> newImages = request.getImages() != null ? request.getImages() : List.of();

            Product product = productService.updateProduct(id, request);

            // 删除不再使用的旧图片
            for (String oldUrl : oldImages) {
                if (!newImages.contains(oldUrl)) {
                    fileStorageService.delete(oldUrl);
                }
            }
            return Response.success(product);
        } catch (Exception e) {
            log.error("更新商品失败", e);
            return Response.error(e.getMessage());
        }
    }

    /**
     * 获取所有商品列表
     */
    @GetMapping
    public Response<List<Product>> list() {
        return Response.success(productService.getAllProducts());
    }

    /**
     * 获取单个商品
     */
    @GetMapping("/{id}")
    public Response<Product> getById(@PathVariable Long id) {
        Product product = productService.getById(id);
        if (product == null) {
            return Response.error(404, "商品不存在");
        }
        return Response.success(product);
    }

    /**
     * 删除商品（同时清理 MinIO 中的图片）
     */
    @DeleteMapping("/{id}")
    public Response<String> delete(@PathVariable Long id) {
        try {
            // 删除前先清理 MinIO 图片
            Product product = productService.getById(id);
            if (product != null) {
                List<String> imageUrls = product.getImageList();
                for (String url : imageUrls) {
                    fileStorageService.delete(url);
                }
            }
            productService.removeById(id);
            return Response.success("删除成功");
        } catch (Exception e) {
            return Response.error(e.getMessage());
        }
    }

    /**
     * 删除已上传的图片（用户取消上传 / 移除预览图时调用）
     */
    @DeleteMapping("/upload")
    public Response<String> deleteUploadedImage(@RequestBody Map<String, String> body) {
        try {
            String url = body.get("url");
            if (url == null || url.isBlank()) {
                return Response.error(400, "url 不能为空");
            }
            fileStorageService.delete(url);
            return Response.success("已删除");
        } catch (Exception e) {
            log.error("删除上传图片失败", e);
            return Response.error("删除失败: " + e.getMessage());
        }
    }
}
