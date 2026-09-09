// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.service;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONUtil;
import com.checkba.model.dto.CompanyBasicInfoDTO;
import com.checkba.model.dto.CompanySearchRequest;
import com.checkba.model.entity.CompanyMirror;
import com.checkba.repository.CompanyMirrorRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 公司镜像 & 我的客户 模块相关服务
 */
@Service
@RequiredArgsConstructor
public class CompanyMirrorService {

    private static final String SOURCE_QICHACHA = "QICHACHA";

    private final CompanyMirrorRepository companyMirrorRepository;

    /**
     * 根据企查查返回的公司信息保存或更新镜像数据
     */
    public void saveFromExternal(CompanyBasicInfoDTO dto, CompanySearchRequest request) {
        if (dto == null || !StringUtils.hasText(request.getRole())) {
            return;
        }

        String role = request.getRole();
        String stockCode = dto.getStockCode();
        String name = StringUtils.hasText(dto.getFullName()) ? dto.getFullName() : dto.getName();

        CompanyMirror entity = null;

        // 优先按股票代码匹配（上市公司）
        if (StringUtils.hasText(stockCode)) {
            entity = companyMirrorRepository.findFirstByRoleAndStockCode(role, stockCode).orElse(null);
        }

        // 回退到按名称匹配
        if (entity == null && StringUtils.hasText(name)) {
            entity = companyMirrorRepository.findFirstByRoleAndName(role, name).orElse(null);
        }

        if (entity == null) {
            entity = new CompanyMirror();
            entity.setRole(role);
            entity.setCreatedAt(LocalDateTime.now());
        }

        entity.setName(name);
        entity.setStockCode(stockCode);
        entity.setFullName(dto.getFullName());
        entity.setShortName(dto.getShortName());
        entity.setBoard(dto.getBoard());
        entity.setTotalShares(dto.getTotalShares());
        entity.setLatestClosePrice(dto.getLatestClosePrice());
        entity.setRegisteredAddress(dto.getRegisteredAddress());
        entity.setRegisteredCapital(dto.getRegisteredCapital());
        entity.setEquityStructureRemark(dto.getEquityStructureRemark());

        // 列表字段序列化为 JSON，方便后续扩展
        entity.setTop10ShareholdersJson(
                dto.getTop10Shareholders() == null ? null : JSONUtil.toJsonStr(dto.getTop10Shareholders()));
        entity.setExecutivesJson(
                dto.getExecutives() == null ? null : JSONUtil.toJsonStr(dto.getExecutives()));
        entity.setShareholdersJson(
                dto.getShareholders() == null ? null : JSONUtil.toJsonStr(dto.getShareholders()));

        entity.setSource(SOURCE_QICHACHA);
        entity.setUpdatedAt(LocalDateTime.now());

        companyMirrorRepository.save(entity);
    }

    /**
     * 查询“我的客户”公司列表
     */
    public List<CompanyBasicInfoDTO> listCompanies(String role) {
        List<CompanyMirror> entities;
        if (StringUtils.hasText(role)) {
            entities = companyMirrorRepository.findByRoleOrderByUpdatedAtDesc(role);
        } else {
            entities = companyMirrorRepository.findAll()
                    .stream()
                    .sorted((a, b) -> {
                        LocalDateTime t1 = a.getUpdatedAt() != null ? a.getUpdatedAt() : a.getCreatedAt();
                        LocalDateTime t2 = b.getUpdatedAt() != null ? b.getUpdatedAt() : b.getCreatedAt();
                        if (t1 == null && t2 == null) return 0;
                        if (t1 == null) return 1;
                        if (t2 == null) return -1;
                        return t2.compareTo(t1);
                    })
                    .collect(Collectors.toList());
        }

        return entities.stream().map(this::toDto).collect(Collectors.toList());
    }

    private CompanyBasicInfoDTO toDto(CompanyMirror entity) {
        CompanyBasicInfoDTO dto = new CompanyBasicInfoDTO();
        dto.setId(entity.getId());
        dto.setRole(entity.getRole());

        dto.setName(entity.getName());
        dto.setStockCode(entity.getStockCode());
        dto.setFullName(entity.getFullName());
        dto.setShortName(entity.getShortName());
        dto.setBoard(entity.getBoard());
        dto.setTotalShares(entity.getTotalShares());
        dto.setLatestClosePrice(entity.getLatestClosePrice());
        dto.setRegisteredAddress(entity.getRegisteredAddress());
        dto.setRegisteredCapital(entity.getRegisteredCapital());
        dto.setEquityStructureRemark(entity.getEquityStructureRemark());

        if (StringUtils.hasText(entity.getTop10ShareholdersJson())) {
            JSONArray arr = JSONUtil.parseArray(entity.getTop10ShareholdersJson());
            // Hutool JSONArray -> List<Map<String, String>>
            @SuppressWarnings("unchecked")
            List<Map<String, String>> list = (List<Map<String, String>>) (List<?>) arr.toList(Map.class);
            dto.setTop10Shareholders(list);
        }
        if (StringUtils.hasText(entity.getExecutivesJson())) {
            JSONArray arr = JSONUtil.parseArray(entity.getExecutivesJson());
            @SuppressWarnings("unchecked")
            List<Map<String, String>> list = (List<Map<String, String>>) (List<?>) arr.toList(Map.class);
            dto.setExecutives(list);
        }
        if (StringUtils.hasText(entity.getShareholdersJson())) {
            JSONArray arr = JSONUtil.parseArray(entity.getShareholdersJson());
            @SuppressWarnings("unchecked")
            List<Map<String, String>> list = (List<Map<String, String>>) (List<?>) arr.toList(Map.class);
            dto.setShareholders(list);
        }
        return dto;
    }
}


