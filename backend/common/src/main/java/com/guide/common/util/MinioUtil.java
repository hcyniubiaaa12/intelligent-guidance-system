package com.guide.common.util;

import com.guide.common.config.MinioProperties;
import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.http.Method;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.concurrent.TimeUnit;

/**
 * MinIO 工具类：桶存在判断、创建桶、上传、删除、预签名 URL。
 */
@Slf4j
@Component
public class MinioUtil {

    private final MinioClient client;

    @Getter
    private final String defaultBucket;

    public MinioUtil(MinioProperties properties) {
        this.client = MinioClient.builder()
                .endpoint(properties.getEndpoint())
                .credentials(properties.getAccessKey(), properties.getSecretKey())
                .build();
        this.defaultBucket = properties.getBucket();
    }

    /** 判断桶是否存在 */
    public boolean bucketExists(String bucket) {
        try {
            return client.bucketExists(BucketExistsArgs.builder().bucket(bucket).build());
        } catch (Exception e) {
            throw new RuntimeException("判断桶是否存在失败: " + bucket, e);
        }
    }

    /** 桶不存在则创建 */
    public void createBucketIfAbsent(String bucket) {
        if (!bucketExists(bucket)) {
            try {
                client.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
                log.info("created bucket: {}", bucket);
            } catch (Exception e) {
                throw new RuntimeException("创建桶失败: " + bucket, e);
            }
        }
    }

    /** 上传文件（桶不存在自动创建） */
    public void upload(String bucket, String objectKey, InputStream stream, long size, String contentType) {
        try {
            createBucketIfAbsent(bucket);
            client.putObject(PutObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectKey)
                    .stream(stream, size, -1)
                    .contentType(contentType)
                    .build());
        } catch (Exception e) {
            throw new RuntimeException("上传文件失败: " + bucket + "/" + objectKey, e);
        }
    }

    /** 上传到默认桶 */
    public void upload(String objectKey, InputStream stream, long size, String contentType) {
        upload(defaultBucket, objectKey, stream, size, contentType);
    }

    /**
     * 下载文件（返回流，**调用方负责关闭**）。
     *
     * <p>入库流水线靠它取原文：解析重试与重新入库读的都是 MinIO 里的原文件
     * （系统没有「替换文件」这个操作，见链路 B）。
     */
    public InputStream download(String bucket, String objectKey) {
        try {
            return client.getObject(GetObjectArgs.builder().bucket(bucket).object(objectKey).build());
        } catch (Exception e) {
            throw new RuntimeException("下载文件失败: " + bucket + "/" + objectKey, e);
        }
    }

    /** 从默认桶下载文件（调用方负责关闭流） */
    public InputStream download(String objectKey) {
        return download(defaultBucket, objectKey);
    }

    /** 删除文件 */
    public void remove(String bucket, String objectKey) {
        try {
            client.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(objectKey).build());
        } catch (Exception e) {
            throw new RuntimeException("删除文件失败: " + bucket + "/" + objectKey, e);
        }
    }

    /** 从默认桶删除文件 */
    public void remove(String objectKey) {
        remove(defaultBucket, objectKey);
    }

    /** 获取预签名访问 URL（默认 1 小时有效） */
    public String presignedUrl(String bucket, String objectKey) {
        try {
            return client.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                    .method(Method.GET)
                    .bucket(bucket)
                    .object(objectKey)
                    .expiry(1, TimeUnit.HOURS)
                    .build());
        } catch (Exception e) {
            throw new RuntimeException("获取预签名 URL 失败: " + bucket + "/" + objectKey, e);
        }
    }
}
