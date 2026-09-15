# 文件存储配置指南

## 概述

本项目支持两种文件存储方式：
1. **本地文件系统存储**（默认）：适合开发环境和小规模部署
2. **对象存储服务**（OSS/S3）：适合生产环境，支持阿里云OSS、AWS S3、MinIO等

## 当前存储情况

### 文件存储位置
- **开发环境**：`data/wps-files/{fileId}.docx`
- **数据库**：仅存储文件元数据（ProjectFile实体），包含`filePath`字段

### 存储架构
- 数据库存储文件元数据（文件名、大小、类型、路径等）
- 实际文件存储在文件系统或对象存储中
- 通过统一的`StorageService`接口管理文件操作

## StorageService 契约红线：读就是读，别在读路径造文件

`StorageService.load(key)` 是**纯读**：文件不存在必须抛 `StorageException`。

`LocalFileStorageService.load` 曾在文件不存在时**从 `docs/template.docx` 复制一份**并当成
正常结果返回。于是一份正文丢失的文档（换存储位置、同步失败、只恢复了数据库、
localRoot 项目里被外部删掉）会被读成一份**空白模板**：用户打开看到空文档、AI 读到的是
模板内容、下载拿到的是模板，全程零报错；自动保存再把这份空白盖回去，原件就真的没了。
同一行为在 `OssStorageService.load` 里从来没有（那边一直是抛），所以桌面端伪造、云端报错，
同一份代码两种行为。

新建文档确实需要物化一个初始文件，那是**另一件事**，走
`StorageService.createFromTemplate(key)`（幂等，已存在则不动；对象存储侧默认空实现，
保持其既有行为）。唯一调用方是 `ProjectFileService.createFile`。

**新增存储实现或新增调用点时**：读路径一律 `load`，创建路径一律 `createFromTemplate`，
不要再让读操作带副作用。回归用例 `MissingFileIsNotFabricatedTest`。

## 配置方式

### 1. 本地文件系统存储（默认）

在 `application.yml` 中配置：

```yaml
storage:
  type: local
  local:
    # 存储根目录（相对路径或绝对路径）
    root-path: data/wps-files
    # 模板文件路径（用于新建文档）
    template-path: docs/template.docx
```

**生产环境建议使用绝对路径**：

```yaml
storage:
  type: local
  local:
    root-path: /data/checkba/wps-files
    template-path: /data/checkba/docs/template.docx
```

### 2. 对象存储服务

#### 2.1 阿里云OSS

**步骤1：添加依赖**

在 `pom.xml` 中取消注释：

```xml
<dependency>
    <groupId>com.aliyun.oss</groupId>
    <artifactId>aliyun-sdk-oss</artifactId>
    <version>3.17.1</version>
</dependency>
```

**步骤2：配置访问凭证**

在阿里云控制台创建AccessKey，获取：
- AccessKey ID
- AccessKey Secret

**步骤3：创建存储桶（Bucket）**

在阿里云OSS控制台创建存储桶，记录：
- 存储桶名称
- 区域（Region），例如：`oss-cn-hangzhou`

**步骤4：配置application.yml**

```yaml
storage:
  type: oss
  oss:
    provider: aliyun
    access-key-id: YOUR_ACCESS_KEY_ID
    access-key-secret: YOUR_ACCESS_KEY_SECRET
    bucket-name: your-bucket-name
    region: oss-cn-hangzhou
    path-prefix: wps-files/  # 可选，文件存储路径前缀
    use-https: true
    # cdn-domain: your-cdn-domain.com  # 可选，配置CDN加速
```

**步骤5：实现OSS上传逻辑**

在 `OssStorageService.java` 中实现 `saveToAliyunOss` 等方法：

```java
private String saveToAliyunOss(String objectKey, InputStream inputStream) throws Exception {
    String endpoint = "https://" + storageProperties.getOss().getRegion() + ".aliyuncs.com";
    OSS ossClient = new OSSClientBuilder().build(
        endpoint,
        storageProperties.getOss().getAccessKeyId(),
        storageProperties.getOss().getAccessKeySecret()
    );
    
    try {
        ossClient.putObject(
            storageProperties.getOss().getBucketName(),
            objectKey,
            inputStream
        );
        return objectKey;
    } finally {
        ossClient.shutdown();
    }
}
```

#### 2.2 AWS S3

**步骤1：添加依赖**

```xml
<dependency>
    <groupId>software.amazon.awssdk</groupId>
    <artifactId>s3</artifactId>
    <version>2.20.0</version>
</dependency>
```

**步骤2：配置**

```yaml
storage:
  type: oss
  oss:
    provider: aws
    access-key-id: YOUR_AWS_ACCESS_KEY_ID
    access-key-secret: YOUR_AWS_SECRET_ACCESS_KEY
    bucket-name: your-bucket-name
    region: us-east-1
    path-prefix: wps-files/
    use-https: true
```

#### 2.3 MinIO（私有部署）

**步骤1：添加依赖**

```xml
<dependency>
    <groupId>io.minio</groupId>
    <artifactId>minio</artifactId>
    <version>8.5.7</version>
</dependency>
```

**步骤2：配置**

```yaml
storage:
  type: oss
  oss:
    provider: minio
    access-key-id: YOUR_MINIO_ACCESS_KEY
    access-key-secret: YOUR_MINIO_SECRET_KEY
    bucket-name: your-bucket-name
    endpoint: minio.example.com:9000  # MinIO服务器地址
    path-prefix: wps-files/
    use-https: false  # 根据MinIO配置
```

## 生产环境配置建议

### 使用环境变量

生产环境建议使用环境变量配置敏感信息，避免将密钥写入配置文件：

```yaml
storage:
  type: oss
  oss:
    provider: aliyun
    access-key-id: ${OSS_ACCESS_KEY_ID}
    access-key-secret: ${OSS_ACCESS_KEY_SECRET}
    bucket-name: ${OSS_BUCKET_NAME}
    region: ${OSS_REGION:oss-cn-hangzhou}
    cdn-domain: ${OSS_CDN_DOMAIN:}
```

### 配置CDN加速

如果使用对象存储，建议配置CDN加速文件访问：

```yaml
storage:
  type: oss
  oss:
    # ... 其他配置
    cdn-domain: cdn.example.com  # CDN域名
```

配置CDN后，文件访问URL将自动使用CDN域名，提升访问速度。

### 存储桶权限配置

**阿里云OSS**：
- 建议使用私有存储桶，通过预签名URL访问
- 配置CORS规则，允许前端访问
- 设置生命周期规则，自动归档旧文件

**AWS S3**：
- 使用IAM策略控制访问权限
- 配置Bucket Policy
- 启用版本控制（可选）

## 迁移指南

### 从本地存储迁移到对象存储

1. **备份现有文件**
   ```bash
   tar -czf wps-files-backup.tar.gz data/wps-files/
   ```

2. **配置对象存储**
   - 按照上述步骤配置对象存储服务
   - 测试上传/下载功能

3. **数据迁移脚本**
   创建迁移脚本，将本地文件上传到对象存储：
   ```java
   // 示例：遍历本地文件并上传到OSS
   File localDir = new File("data/wps-files");
   for (File file : localDir.listFiles()) {
       String fileId = file.getName().replace(".docx", "");
       try (InputStream is = new FileInputStream(file)) {
           storageService.save(fileId, is);
       }
   }
   ```

4. **切换配置**
   - 修改 `application.yml` 中的 `storage.type` 为 `oss`
   - 重启应用

5. **验证**
   - 测试文件上传/下载功能
   - 检查数据库中的文件路径是否正确更新

### 从对象存储迁移到本地存储

1. **下载所有文件**
   使用对象存储工具或SDK下载所有文件到本地

2. **修改配置**
   ```yaml
   storage:
     type: local
   ```

3. **重启应用**

## 最佳实践

1. **数据库与存储分离**
   - 数据库只存储文件元数据
   - 实际文件存储在文件系统或对象存储中
   - 通过`filePath`字段关联

2. **文件命名规范**
   - 使用`fileId`作为文件名，避免冲突
   - 保持文件扩展名（如`.docx`）

3. **存储路径设计**
   - 对象存储使用路径前缀组织文件（如`wps-files/`）
   - 避免在根目录直接存储大量文件

4. **访问控制**
   - 对象存储使用私有存储桶
   - 通过应用服务器生成预签名URL访问
   - 不要在前端直接暴露访问密钥

5. **备份策略**
   - 定期备份数据库
   - 对象存储启用版本控制
   - 配置跨区域复制（可选）

6. **监控与日志**
   - 记录文件上传/下载操作
   - 监控存储使用量
   - 设置存储空间告警

## 故障排查

### 文件上传失败
- 检查存储服务配置是否正确
- 检查访问密钥是否有效
- 检查存储桶权限设置
- 查看应用日志

### 文件下载失败
- 检查文件是否存在
- 检查访问URL是否有效
- 检查网络连接
- 检查CORS配置（对象存储）

### 存储空间不足
- 清理过期文件
- 配置生命周期规则自动归档
- 扩容存储空间

## 相关文件

- `StorageService.java` - 存储服务接口
- `LocalFileStorageService.java` - 本地存储实现
- `OssStorageService.java` - 对象存储实现
- `StorageProperties.java` - 存储配置属性
- `FileController.java` - 文件上传/下载接口

