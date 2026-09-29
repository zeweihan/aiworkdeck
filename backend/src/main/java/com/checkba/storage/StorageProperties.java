// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.storage;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.unit.DataSize;

/**
 * 存储服务配置属性
 */
@Component
@ConfigurationProperties(prefix = "storage")
public class StorageProperties {

    /**
     * 存储类型：只支持 local（本地文件系统）
     */
    private String type = "local";

    /**
     * 本地存储配置
     */
    private Local local = new Local();

    /**
     * 单个项目的文件总量上限（dev-board#1038）。上传与本机导入共用这一道闸；
     * application.yml 的 multipart 单请求上限也引用这个值，二者同源。
     */
    private DataSize projectSizeLimit = DataSize.ofGigabytes(20);

    /**
     * 本机文件夹项目（Project.localRoot 非空）是否豁免项目总量闸。
     * 只在桌面 profile 打开：那是用户自己的磁盘，没有理由替他限量；托管项目与云端/案件库照旧受限。
     */
    private boolean exemptLocalFolderProjects = false;

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public DataSize getProjectSizeLimit() {
        return projectSizeLimit;
    }

    public void setProjectSizeLimit(DataSize projectSizeLimit) {
        this.projectSizeLimit = projectSizeLimit;
    }

    public boolean isExemptLocalFolderProjects() {
        return exemptLocalFolderProjects;
    }

    public void setExemptLocalFolderProjects(boolean exemptLocalFolderProjects) {
        this.exemptLocalFolderProjects = exemptLocalFolderProjects;
    }

    public Local getLocal() {
        return local;
    }

    public void setLocal(Local local) {
        this.local = local;
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
}
