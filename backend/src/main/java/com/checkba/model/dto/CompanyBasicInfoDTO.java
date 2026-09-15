// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.model.dto;

import java.util.List;
import java.util.Map;

public class CompanyBasicInfoDTO {
    // 主键（如果来自本地镜像数据）
    private Long id;

    // 公司角色：LISTED / TARGET 等
    private String role;

    // 基础信息
    private String name;
    private String stockCode;
    private String fullName;
    private String shortName;
    private String board;
    private String totalShares;
    private String latestClosePrice;
    
    // 标的公司特有
    private String registeredAddress;
    private String registeredCapital;
    private String equityStructureRemark;

    // 列表数据
    private List<Map<String, String>> top10Shareholders;
    private List<Map<String, String>> executives;
    private List<Map<String, String>> shareholders;
    
    // Getters and Setters

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getStockCode() {
        return stockCode;
    }

    public void setStockCode(String stockCode) {
        this.stockCode = stockCode;
    }

    public String getFullName() {
        return fullName;
    }

    public void setFullName(String fullName) {
        this.fullName = fullName;
    }

    public String getShortName() {
        return shortName;
    }

    public void setShortName(String shortName) {
        this.shortName = shortName;
    }

    public String getBoard() {
        return board;
    }

    public void setBoard(String board) {
        this.board = board;
    }

    public String getTotalShares() {
        return totalShares;
    }

    public void setTotalShares(String totalShares) {
        this.totalShares = totalShares;
    }

    public String getLatestClosePrice() {
        return latestClosePrice;
    }

    public void setLatestClosePrice(String latestClosePrice) {
        this.latestClosePrice = latestClosePrice;
    }

    public String getRegisteredAddress() {
        return registeredAddress;
    }

    public void setRegisteredAddress(String registeredAddress) {
        this.registeredAddress = registeredAddress;
    }

    public String getRegisteredCapital() {
        return registeredCapital;
    }

    public void setRegisteredCapital(String registeredCapital) {
        this.registeredCapital = registeredCapital;
    }

    public String getEquityStructureRemark() {
        return equityStructureRemark;
    }

    public void setEquityStructureRemark(String equityStructureRemark) {
        this.equityStructureRemark = equityStructureRemark;
    }

    public List<Map<String, String>> getTop10Shareholders() {
        return top10Shareholders;
    }

    public void setTop10Shareholders(List<Map<String, String>> top10Shareholders) {
        this.top10Shareholders = top10Shareholders;
    }

    public List<Map<String, String>> getExecutives() {
        return executives;
    }

    public void setExecutives(List<Map<String, String>> executives) {
        this.executives = executives;
    }

    public List<Map<String, String>> getShareholders() {
        return shareholders;
    }

    public void setShareholders(List<Map<String, String>> shareholders) {
        this.shareholders = shareholders;
    }
}
