package com.example.seckill.cloud.product;

import com.example.seckill.cloud.common.ApiResponse;
import com.example.seckill.cloud.common.RequestUser;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.minio.*;
import jakarta.annotation.PostConstruct;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.net.URI;
import java.util.*;

@RestController
public class ProductController {
    private final JdbcClient jdbc;
    private final MinioClient minio;
    private final ObjectMapper objectMapper;
    private final String bucket;
    private final String publicUrl;

    public ProductController(JdbcClient jdbc, MinioClient minio, ObjectMapper objectMapper,
                             @Value("${minio.bucket:seckill}") String bucket,
                             @Value("${minio.public-url:${minio.endpoint}}") String publicUrl) {
        this.jdbc = jdbc;
        this.minio = minio;
        this.objectMapper = objectMapper;
        this.bucket = bucket;
        this.publicUrl = publicUrl.replaceAll("/+$", "");
    }

    @PostConstruct
    void initializeBucket() throws Exception {
        ensureBucket();
    }

    private record ProductRow(Long id, String name, String title, String description,
                              String images, BigDecimal price, Integer stock) {}

    /** images 保留给服务间快照，imageList 用于兼容现有管理端。 */
    public record Product(Long id, String name, String title, String description, String images,
                          List<String> imageList, BigDecimal price, Integer stock) {}

    public record SaveProduct(@NotBlank String name, String title, String description,
                              List<String> images,
                              @NotNull @DecimalMin("0.01") BigDecimal price,
                              @NotNull @Min(0) Integer stock) {}

    @GetMapping({"/api/products", "/api/admin/products"})
    public ApiResponse<List<Product>> list() {
        return ApiResponse.ok(jdbc.sql("SELECT id,name,title,description,images,price,stock FROM products ORDER BY id DESC")
                .query(ProductRow.class).list().stream().map(this::view).toList());
    }

    @GetMapping({"/api/products/{id}", "/api/admin/products/{id}"})
    public ApiResponse<Product> one(@PathVariable Long id) {
        ProductRow row = jdbc.sql("SELECT id,name,title,description,images,price,stock FROM products WHERE id=:id")
                .param("id", id).query(ProductRow.class).optional()
                .orElseThrow(() -> new IllegalArgumentException("商品不存在"));
        return ApiResponse.ok(view(row));
    }

    @PostMapping("/api/admin/products")
    public ApiResponse<Product> create(@Valid @RequestBody SaveProduct request, HttpServletRequest servletRequest) {
        admin(servletRequest);
        jdbc.sql("INSERT INTO products(name,title,description,images,price,stock) VALUES(:n,:t,:d,:i,:p,:s)")
                .param("n", request.name()).param("t", request.title()).param("d", request.description())
                .param("i", json(request.images())).param("p", request.price()).param("s", request.stock()).update();
        return one(jdbc.sql("SELECT LAST_INSERT_ID()").query(Long.class).single());
    }

    @PutMapping("/api/admin/products/{id}")
    public ApiResponse<Product> update(@PathVariable Long id, @Valid @RequestBody SaveProduct request,
                                       HttpServletRequest servletRequest) {
        admin(servletRequest);
        int changed = jdbc.sql("UPDATE products SET name=:n,title=:t,description=:d,images=:i,price=:p,stock=:s WHERE id=:id")
                .param("n", request.name()).param("t", request.title()).param("d", request.description())
                .param("i", json(request.images())).param("p", request.price()).param("s", request.stock())
                .param("id", id).update();
        if (changed == 0) throw new IllegalArgumentException("商品不存在");
        return one(id);
    }

    @DeleteMapping("/api/admin/products/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id, HttpServletRequest request) {
        admin(request);
        Product product = one(id).data();
        product.imageList().forEach(this::deleteObjectQuietly);
        jdbc.sql("DELETE FROM products WHERE id=:id").param("id", id).update();
        return ApiResponse.ok("删除成功", null);
    }

    @PostMapping("/api/admin/products/upload")
    public ApiResponse<Map<String, String>> upload(@RequestParam MultipartFile file,
                                                   HttpServletRequest request) throws Exception {
        admin(request);
        return ApiResponse.ok(Map.of("url", store(file)));
    }

    @PostMapping("/api/admin/products/upload/batch")
    public ApiResponse<Map<String, Object>> uploadBatch(@RequestParam("files") List<MultipartFile> files,
                                                        HttpServletRequest request) throws Exception {
        admin(request);
        List<String> urls = new ArrayList<>();
        for (MultipartFile file : files) if (!file.isEmpty()) urls.add(store(file));
        return ApiResponse.ok(Map.of("urls", urls));
    }

    @DeleteMapping("/api/admin/products/upload")
    public ApiResponse<Void> deleteUpload(@RequestBody Map<String, String> body, HttpServletRequest request) {
        admin(request);
        String url = body.get("url");
        if (url == null || url.isBlank()) throw new IllegalArgumentException("url 不能为空");
        deleteObject(url);
        return ApiResponse.ok("已删除", null);
    }

    private String store(MultipartFile file) throws Exception {
        if (file.isEmpty() || file.getContentType() == null || !file.getContentType().startsWith("image/"))
            throw new IllegalArgumentException("只能上传图片");
        ensureBucket();
        String filename = Optional.ofNullable(file.getOriginalFilename()).orElse("");
        String extension = filename.contains(".") ? filename.substring(filename.lastIndexOf('.')) : "";
        String object = "products/" + UUID.randomUUID() + extension;
        minio.putObject(PutObjectArgs.builder().bucket(bucket).object(object)
                .stream(file.getInputStream(), file.getSize(), -1).contentType(file.getContentType()).build());
        return publicUrl + "/" + bucket + "/" + object;
    }

    private void ensureBucket() throws Exception {
        if (!minio.bucketExists(BucketExistsArgs.builder().bucket(bucket).build()))
            minio.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
        // 商品图片需要由浏览器直接展示：只开放匿名读取，不开放上传、覆盖或删除。
        String policy = """
                {"Version":"2012-10-17","Statement":[{"Effect":"Allow","Principal":{"AWS":["*"]},
                "Action":["s3:GetObject"],"Resource":["arn:aws:s3:::%s/*"]}]}
                """.formatted(bucket);
        minio.setBucketPolicy(SetBucketPolicyArgs.builder().bucket(bucket).config(policy).build());
    }

    private Product view(ProductRow row) {
        List<String> imageList;
        try {
            imageList = row.images() == null || row.images().isBlank()
                    ? List.of() : objectMapper.readValue(row.images(), new TypeReference<>() {});
        } catch (Exception ignored) {
            imageList = List.of();
        }
        return new Product(row.id(), row.name(), row.title(), row.description(), row.images(),
                imageList, row.price(), row.stock());
    }

    private String json(List<String> images) {
        try {
            return objectMapper.writeValueAsString(images == null ? List.of() : images);
        } catch (Exception e) {
            throw new IllegalArgumentException("图片地址格式错误");
        }
    }

    private void deleteObjectQuietly(String url) {
        try { deleteObject(url); } catch (RuntimeException ignored) { }
    }

    private void deleteObject(String url) {
        try {
            URI expected = URI.create(publicUrl);
            URI actual = URI.create(url);
            if (!Objects.equals(expected.getHost(), actual.getHost()) || expected.getPort() != actual.getPort())
                throw new IllegalArgumentException("只能删除本项目 MinIO 中的文件");
            String prefix = "/" + bucket + "/";
            if (!actual.getPath().startsWith(prefix)) throw new IllegalArgumentException("图片地址不属于当前存储桶");
            minio.removeObject(RemoveObjectArgs.builder().bucket(bucket)
                    .object(actual.getPath().substring(prefix.length())).build());
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("删除图片失败", e);
        }
    }

    private void admin(HttpServletRequest request) {
        RequestUser.require(request).requireAdmin();
    }
}
