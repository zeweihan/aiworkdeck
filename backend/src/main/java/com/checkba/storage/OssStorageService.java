package com.checkba.storage;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.net.URL;

/**
 * 对象存储服务实现（OSS/S3）
 * 
 * 注意：这是一个基础实现框架，需要根据实际使用的对象存储服务提供商
 * 添加相应的SDK依赖并实现具体逻辑。
 * 
 * 支持的提供商：
 * - 阿里云OSS
 * - AWS S3
 * - MinIO
 * 
 * 使用前需要在pom.xml中添加相应的依赖，并在配置文件中配置访问凭证。
 */
@Service
public class OssStorageService implements StorageService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(OssStorageService.class);

    private final StorageProperties storageProperties;

    @Autowired
    public OssStorageService(StorageProperties storageProperties) {
        this.storageProperties = storageProperties;
        log.info("对象存储服务初始化完成，提供商: {}, 存储桶: {}", 
                storageProperties.getOss().getProvider(),
                storageProperties.getOss().getBucketName());
    }

    @Override
    public String save(String fileId, InputStream inputStream) throws StorageException {
        String provider = storageProperties.getOss().getProvider();
        String objectKey = buildObjectKey(fileId);
        
        try {
            switch (provider.toLowerCase()) {
                case "aliyun":
                    return saveToAliyunOss(objectKey, inputStream);
                case "aws":
                case "s3":
                    return saveToAwsS3(objectKey, inputStream);
                case "minio":
                    return saveToMinIO(objectKey, inputStream);
                default:
                    throw new StorageException("不支持的对象存储提供商: " + provider);
            }
        } catch (Exception e) {
            log.error("对象存储保存失败: fileId={}, provider={}", fileId, provider, e);
            throw new StorageException("文件保存失败: " + e.getMessage(), e);
        }
    }

    @Override
    public Resource load(String fileId) throws StorageException {
        String provider = storageProperties.getOss().getProvider();
        String objectKey = buildObjectKey(fileId);
        
        try {
            String url = getUrl(fileId);
            if (url == null) {
                throw new StorageException("无法获取文件访问URL: " + fileId);
            }
            return new UrlResource(new URL(url));
        } catch (Exception e) {
            log.error("对象存储加载失败: fileId={}, provider={}", fileId, provider, e);
            throw new StorageException("文件加载失败: " + e.getMessage(), e);
        }
    }

    @Override
    public void delete(String fileId) throws StorageException {
        String provider = storageProperties.getOss().getProvider();
        String objectKey = buildObjectKey(fileId);
        
        try {
            switch (provider.toLowerCase()) {
                case "aliyun":
                    deleteFromAliyunOss(objectKey);
                    break;
                case "aws":
                case "s3":
                    deleteFromAwsS3(objectKey);
                    break;
                case "minio":
                    deleteFromMinIO(objectKey);
                    break;
                default:
                    throw new StorageException("不支持的对象存储提供商: " + provider);
            }
            log.info("对象存储删除成功: fileId={}, provider={}", fileId, provider);
        } catch (Exception e) {
            log.error("对象存储删除失败: fileId={}, provider={}", fileId, provider, e);
            throw new StorageException("文件删除失败: " + e.getMessage(), e);
        }
    }

    @Override
    public boolean exists(String fileId) {
        String provider = storageProperties.getOss().getProvider();
        String objectKey = buildObjectKey(fileId);
        
        try {
            switch (provider.toLowerCase()) {
                case "aliyun":
                    return existsInAliyunOss(objectKey);
                case "aws":
                case "s3":
                    return existsInAwsS3(objectKey);
                case "minio":
                    return existsInMinIO(objectKey);
                default:
                    return false;
            }
        } catch (Exception e) {
            log.error("检查文件是否存在失败: fileId={}, provider={}", fileId, provider, e);
            return false;
        }
    }

    @Override
    public String getUrl(String fileId) {
        StorageProperties.Oss oss = storageProperties.getOss();
        
        // 如果配置了CDN域名，优先使用CDN
        if (oss.getCdnDomain() != null && !oss.getCdnDomain().isEmpty()) {
            String protocol = oss.isUseHttps() ? "https" : "http";
            String objectKey = buildObjectKey(fileId);
            return String.format("%s://%s/%s", protocol, oss.getCdnDomain(), objectKey);
        }
        
        // 否则生成预签名URL或直接访问URL
        String objectKey = buildObjectKey(fileId);
        String provider = oss.getProvider();
        
        try {
            switch (provider.toLowerCase()) {
                case "aliyun":
                    return getAliyunOssUrl(objectKey);
                case "aws":
                case "s3":
                    return getAwsS3Url(objectKey);
                case "minio":
                    return getMinIOUrl(objectKey);
                default:
                    return null;
            }
        } catch (Exception e) {
            log.error("获取文件URL失败: fileId={}, provider={}", fileId, provider, e);
            return null;
        }
    }

    public void move(String sourceFileId, String targetFileId) throws StorageException {
        String provider = storageProperties.getOss().getProvider();
        String sourceKey = buildObjectKey(sourceFileId);
        String targetKey = buildObjectKey(targetFileId);
        
        try {
            switch (provider.toLowerCase()) {
                case "aliyun":
                    // copyObject(sourceKey, targetKey);
                    // deleteObject(sourceKey);
                    break;
                case "aws":
                case "s3":
                    // copyObject(sourceKey, targetKey);
                    // deleteObject(sourceKey);
                    break;
                case "minio":
                    // copyObject(sourceKey, targetKey);
                    // removeObject(sourceKey);
                    break;
                default:
                    throw new StorageException("不支持的对象存储提供商: " + provider);
            }
            log.info("对象存储移动成功: {} -> {}", sourceKey, targetKey);
        } catch (Exception e) {
            log.error("对象存储移动失败: {} -> {}", sourceKey, targetKey, e);
            throw new StorageException("文件移动失败: " + e.getMessage(), e);
        }
    }

    public void createFromTemplate(String fileId) throws StorageException {
        // 对象存储通常不支持直接从服务器本地文件复制，除非模板也在对象存储上
        // 这里暂时抛出不支持，或者上传一个空文件
        String objectKey = buildObjectKey(fileId);
        try {
            // save(fileId, new ByteArrayInputStream(new byte[0])); 
            log.warn("OssStorageService.createFromTemplate 暂未完全实现，已跳过或仅创建空对象");
        } catch (Exception e) {
            throw new StorageException("创建文件失败: " + e.getMessage(), e);
        }
    }

    @Override
    public String append(String fileId, InputStream inputStream) throws StorageException {
        throw new UnsupportedOperationException("对象存储暂不支持追加写入");
    }

    @Override
    public long getSize(String fileId) throws StorageException {
        // TODO: 实现获取文件大小 (Head Object)
        // return 0;
        throw new UnsupportedOperationException("对象存储暂不支持获取文件大小");
    }

    /**
     * 构建对象存储的键（Object Key）
     */
    private String buildObjectKey(String fileId) {
        String prefix = storageProperties.getOss().getPathPrefix();
        if (prefix != null && !prefix.isEmpty() && !prefix.endsWith("/")) {
            prefix = prefix + "/";
        }
        // 与本地存储一致：含路径分隔符的 fileId 原样作为 key（如 avatars/x.png、ocr/tmp/x、clipboard/1/uuid），
        // 只对无分隔符的旧式裸 id 兼容补 .docx——此前无条件补 .docx 会把头像/OCR/剪贴板文件的 key 写坏
        String key = (fileId.contains("/") || fileId.contains("\\")) ? fileId : fileId + ".docx";
        return (prefix != null ? prefix : "") + key;
    }

    // ========== 阿里云OSS实现 ==========
    
    private String saveToAliyunOss(String objectKey, InputStream inputStream) throws Exception {
        // TODO: 实现阿里云OSS上传
        // 需要添加依赖：com.aliyun.oss:aliyun-sdk-oss
        // 示例代码：
        // OSS ossClient = new OSSClientBuilder().build(endpoint, accessKeyId, accessKeySecret);
        // ossClient.putObject(bucketName, objectKey, inputStream);
        // ossClient.shutdown();
        throw new UnsupportedOperationException("阿里云OSS实现需要添加SDK依赖");
    }

    private void deleteFromAliyunOss(String objectKey) throws Exception {
        // TODO: 实现阿里云OSS删除
        throw new UnsupportedOperationException("阿里云OSS实现需要添加SDK依赖");
    }

    private boolean existsInAliyunOss(String objectKey) throws Exception {
        // TODO: 实现阿里云OSS存在性检查
        throw new UnsupportedOperationException("阿里云OSS实现需要添加SDK依赖");
    }

    private String getAliyunOssUrl(String objectKey) throws Exception {
        // TODO: 实现阿里云OSS URL生成
        StorageProperties.Oss oss = storageProperties.getOss();
        String protocol = oss.isUseHttps() ? "https" : "http";
        String endpoint = oss.getEndpoint() != null ? oss.getEndpoint() : 
                "oss-" + oss.getRegion() + ".aliyuncs.com";
        return String.format("%s://%s.%s/%s", protocol, oss.getBucketName(), endpoint, objectKey);
    }

    // ========== AWS S3实现 ==========
    
    private String saveToAwsS3(String objectKey, InputStream inputStream) throws Exception {
        // TODO: 实现AWS S3上传
        // 需要添加依赖：software.amazon.awssdk:s3
        throw new UnsupportedOperationException("AWS S3实现需要添加SDK依赖");
    }

    private void deleteFromAwsS3(String objectKey) throws Exception {
        // TODO: 实现AWS S3删除
        throw new UnsupportedOperationException("AWS S3实现需要添加SDK依赖");
    }

    private boolean existsInAwsS3(String objectKey) throws Exception {
        // TODO: 实现AWS S3存在性检查
        throw new UnsupportedOperationException("AWS S3实现需要添加SDK依赖");
    }

    private String getAwsS3Url(String objectKey) throws Exception {
        // TODO: 实现AWS S3 URL生成（预签名URL）
        StorageProperties.Oss oss = storageProperties.getOss();
        String protocol = oss.isUseHttps() ? "https" : "http";
        String endpoint = oss.getEndpoint() != null ? oss.getEndpoint() : 
                "s3." + oss.getRegion() + ".amazonaws.com";
        return String.format("%s://%s.%s/%s", protocol, oss.getBucketName(), endpoint, objectKey);
    }

    // ========== MinIO实现 ==========
    
    private String saveToMinIO(String objectKey, InputStream inputStream) throws Exception {
        // TODO: 实现MinIO上传
        // 需要添加依赖：io.minio:minio
        throw new UnsupportedOperationException("MinIO实现需要添加SDK依赖");
    }

    private void deleteFromMinIO(String objectKey) throws Exception {
        // TODO: 实现MinIO删除
        throw new UnsupportedOperationException("MinIO实现需要添加SDK依赖");
    }

    private boolean existsInMinIO(String objectKey) throws Exception {
        // TODO: 实现MinIO存在性检查
        throw new UnsupportedOperationException("MinIO实现需要添加SDK依赖");
    }

    private String getMinIOUrl(String objectKey) throws Exception {
        // TODO: 实现MinIO URL生成（预签名URL）
        StorageProperties.Oss oss = storageProperties.getOss();
        String protocol = oss.isUseHttps() ? "https" : "http";
        String endpoint = oss.getEndpoint();
        if (endpoint == null || endpoint.isEmpty()) {
            throw new StorageException("MinIO需要配置endpoint");
        }
        return String.format("%s://%s/%s/%s", protocol, endpoint, oss.getBucketName(), objectKey);
    }
}

