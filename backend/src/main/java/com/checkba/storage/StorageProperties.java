// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.storage;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 存储服务配置属性
 */
@Component
@ConfigurationProperties(prefix = "storage")
public class StorageProperties {

    /**
     * 存储类型：local（本地文件系统）或 oss（对象存储）
     */
    private String type = "local";

    /**
     * 本地存储配置
     */
    private Local local = new Local();

    /**
     * 对象存储配置
     */
    private Oss oss = new Oss();

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public Local getLocal() {
        return local;
    }

    public void setLocal(Local local) {
        this.local = local;
    }

    public Oss getOss() {
        return oss;
    }

    public void setOss(Oss oss) {
        this.oss = oss;
    }

    public static class Local {
        /**
         * 本地存储根目录（相对于项目根目录或绝对路径）
         * 默认：data/wps-files
         */
        private String rootPath = "data/wps-files";

        /**
         * 模板文件路径（用于新建文档的初始内容）
         * 默认：docs/template.docx
         */
        private String templatePath = "docs/template.docx";

        public String getRootPath() {
            return rootPath;
        }

        public void setRootPath(String rootPath) {
            this.rootPath = rootPath;
        }

        public String getTemplatePath() {
            return templatePath;
        }

        public void setTemplatePath(String templatePath) {
            this.templatePath = templatePath;
        }
    }

    public static class Oss {
        /**
         * 对象存储类型：aliyun（阿里云OSS）、aws（AWS S3）、minio（MinIO）
         */
        private String provider = "aliyun";

        /**
         * 访问密钥ID
         */
        private String accessKeyId;

        /**
         * 访问密钥Secret
         */
        private String accessKeySecret;

        /**
         * 存储桶名称（Bucket）
         */
        private String bucketName;

        /**
         * 存储桶所在区域（Region）
         * 例如：oss-cn-hangzhou（阿里云）、us-east-1（AWS）
         */
        private String region;

        /**
         * 自定义端点（Endpoint），用于MinIO或私有部署的对象存储
         * 如果为空，则使用默认端点
         */
        private String endpoint;

        /**
         * 文件存储路径前缀（可选）
         * 例如：wps-files/ 或 project-files/
         */
        private String pathPrefix = "";

        /**
         * 是否使用HTTPS
         */
        private boolean useHttps = true;

        /**
         * CDN域名（可选，用于加速访问）
         * 如果配置了CDN，文件访问URL将使用CDN域名
         */
        private String cdnDomain;

        public String getProvider() {
            return provider;
        }

        public void setProvider(String provider) {
            this.provider = provider;
        }

        public String getAccessKeyId() {
            return accessKeyId;
        }

        public void setAccessKeyId(String accessKeyId) {
            this.accessKeyId = accessKeyId;
        }

        public String getAccessKeySecret() {
            return accessKeySecret;
        }

        public void setAccessKeySecret(String accessKeySecret) {
            this.accessKeySecret = accessKeySecret;
        }

        public String getBucketName() {
            return bucketName;
        }

        public void setBucketName(String bucketName) {
            this.bucketName = bucketName;
        }

        public String getRegion() {
            return region;
        }

        public void setRegion(String region) {
            this.region = region;
        }

        public String getEndpoint() {
            return endpoint;
        }

        public void setEndpoint(String endpoint) {
            this.endpoint = endpoint;
        }

        public String getPathPrefix() {
            return pathPrefix;
        }

        public void setPathPrefix(String pathPrefix) {
            this.pathPrefix = pathPrefix;
        }

        public boolean isUseHttps() {
            return useHttps;
        }

        public void setUseHttps(boolean useHttps) {
            this.useHttps = useHttps;
        }

        public String getCdnDomain() {
            return cdnDomain;
        }

        public void setCdnDomain(String cdnDomain) {
            this.cdnDomain = cdnDomain;
        }
    }
}
