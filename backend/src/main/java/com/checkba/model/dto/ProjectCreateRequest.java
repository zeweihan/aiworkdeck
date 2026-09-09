// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.model.dto;

/**
 * 创建项目入参 DTO
 *
 * 与前端新建项目表单一一对应。
 */
public class ProjectCreateRequest {

    private String projectType;

    private String listedCompanyName;

    private String targetCompanyName;

    /**
     * 项目名称（可选），前端暂未显式传递时由后端自动生成
     */
    private String name;

    /**
     * 上市公司通过外部服务查询到的基础信息
     */
    private CompanyBasicInfoDTO listedCompanyInfo;

    /**
     * 标的公司通过外部服务查询到的基础信息
     */
    private CompanyBasicInfoDTO targetCompanyInfo;

    public String getProjectType() {
        return projectType;
    }

    public void setProjectType(String projectType) {
        this.projectType = projectType;
    }

    public String getListedCompanyName() {
        return listedCompanyName;
    }

    public void setListedCompanyName(String listedCompanyName) {
        this.listedCompanyName = listedCompanyName;
    }

    public String getTargetCompanyName() {
        return targetCompanyName;
    }

    public void setTargetCompanyName(String targetCompanyName) {
        this.targetCompanyName = targetCompanyName;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public CompanyBasicInfoDTO getListedCompanyInfo() {
        return listedCompanyInfo;
    }

    public void setListedCompanyInfo(CompanyBasicInfoDTO listedCompanyInfo) {
        this.listedCompanyInfo = listedCompanyInfo;
    }

    public CompanyBasicInfoDTO getTargetCompanyInfo() {
        return targetCompanyInfo;
    }

    public void setTargetCompanyInfo(CompanyBasicInfoDTO targetCompanyInfo) {
        this.targetCompanyInfo = targetCompanyInfo;
    }
}
