// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.model.dto;

import lombok.Data;

@Data
public class CompanySearchRequest {
    /**
     * 项目类型，仅作为透传占位
     */
    private String projectType;

    /**
     * 公司角色：LISTED / TARGET
     */
    private String role;

    /**
     * 搜索关键字：可以是公司名称，也可以是股票代码
     */
    private String name;

    public String getProjectType() { return projectType; }
    public void setProjectType(String projectType) { this.projectType = projectType; }
    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
}

