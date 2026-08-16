package com.example.seckill.service;

import io.minio.*;
import io.minio.http.Method;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * MinIO 文件存储服务
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FileStorageService {

    private final MinioClient minioClient;
    private final com.example.seckill.config.MinioConfig minioConfig;

    /**
     * 上传单张图片，返回可直接访问的 URL（预签名，7天有效）
     */
    public String upload(MultipartFile file) throws Exception {
        String bucket = minioConfig.getBucket();
        ensureBucket(bucket);

        // 生成唯一文件名
        String originalName = file.getOriginalFilename();
        String ext = "";
        if (originalName != null && originalName.contains(".")) {
            ext = originalName.substring(originalName.lastIndexOf("."));
        }
        String objectName = "products/" + UUID.randomUUID().toString() + ext;

        // 上传到 MinIO
        minioClient.putObject(
                PutObjectArgs.builder()
                        .bucket(bucket)
                        .object(objectName)
                        .stream(file.getInputStream(), file.getSize(), -1)
                        .contentType(file.getContentType())
                        .build()
        );

        // 生成预签名 URL（7天有效，确保浏览器可访问）
        String presignedUrl = minioClient.getPresignedObjectUrl(
                GetPresignedObjectUrlArgs.builder()
                        .method(Method.GET)
                        .bucket(bucket)
                        .object(objectName)
                        .expiry(7, TimeUnit.DAYS)
                        .build()
        );

        log.info("图片上传成功 object={} size={}", objectName, file.getSize());
        return presignedUrl;
    }

    /**
     * 删除文件
     */
    public void delete(String presignedUrl) {
        try {
            String bucket = minioConfig.getBucket();
            String objectName = extractObjectName(presignedUrl);
            if (objectName != null) {
                minioClient.removeObject(
                        RemoveObjectArgs.builder().bucket(bucket).object(objectName).build()
                );
                log.info("图片已删除: {}", objectName);
            }
        } catch (Exception e) {
            log.warn("删除 MinIO 文件失败 url={}: {}", presignedUrl, e.getMessage());
        }
    }

    /**
     * 批量删除文件
     */
    public void deleteBatch(List<String> urls) {
        if (urls == null || urls.isEmpty()) return;
        for (String url : urls) {
            delete(url);
        }
    }

    /**
     * 从预签名 URL 中提取 object 名称
     */
    public String extractObjectName(String presignedUrl) {
        if (presignedUrl == null) return null;
        String bucket = minioConfig.getBucket();
        String endpoint = minioConfig.getEndpoint();
        String prefix = endpoint + "/" + bucket + "/";
        if (presignedUrl.startsWith(prefix)) {
            String path = presignedUrl.substring(prefix.length());
            return path.contains("?") ? path.substring(0, path.indexOf("?")) : path;
        }
        return null;
    }

    /**
     * 确保 bucket 存在并设为公开可读
     */
    private void ensureBucket(String bucket) throws Exception {
        boolean exists = minioClient.bucketExists(
                BucketExistsArgs.builder().bucket(bucket).build()
        );
        if (!exists) {
            minioClient.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
            log.info("MinIO bucket 已创建: {}", bucket);
        }

        // 设置 bucket 为公开可读（允许浏览器直接访问图片）
        try {
            String policy = """
            {
              "Version": "2012-10-17",
              "Statement": [
                {
                  "Effect": "Allow",
                  "Principal": {"AWS": ["*"]},
                  "Action": ["s3:GetObject"],
                  "Resource": ["arn:aws:s3:::%s/*"]
                }
              ]
            }
            """.formatted(bucket);
            minioClient.setBucketPolicy(
                    SetBucketPolicyArgs.builder().bucket(bucket).config(policy).build()
            );
            log.info("MinIO bucket 已设为公开可读: {}", bucket);
        } catch (Exception e) {
            log.warn("设置 bucket 公开读策略失败（文件仍可通过预签名URL访问）: {}", e.getMessage());
        }
    }
}
