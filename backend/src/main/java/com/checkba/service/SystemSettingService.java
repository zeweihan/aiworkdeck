// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.service;

import com.checkba.model.entity.SystemSetting;
import com.checkba.repository.SystemSettingRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 系统配置服务：
 * - 提供基于 key 的读取 / 写入能力
 * - 供后台管理界面和业务代码复用
 */
@Service
@RequiredArgsConstructor
public class SystemSettingService {

    private final SystemSettingRepository systemSettingRepository;

    /**
     * 根据 key 获取配置值，不存在时返回默认值。
     */
    public String get(String key, String defaultValue) {
        Optional<SystemSetting> settingOpt = systemSettingRepository.findByKey(key);
        return settingOpt.map(SystemSetting::getValue).orElse(defaultValue);
    }

    /**
     * 批量获取某一组 key 的配置，返回 key -> value 映射。
     */
    public Map<String, String> getMany(Map<String, String> keysWithDefault) {
        Map<String, String> result = new HashMap<>();
        if (keysWithDefault == null || keysWithDefault.isEmpty()) {
            return result;
        }
        List<SystemSetting> all = systemSettingRepository.findAll();
        Map<String, String> existing = new HashMap<>();
        for (SystemSetting s : all) {
            existing.put(s.getKey(), s.getValue());
        }
        for (Map.Entry<String, String> entry : keysWithDefault.entrySet()) {
            String key = entry.getKey();
            String def = entry.getValue();
            result.put(key, existing.getOrDefault(key, def));
        }
        return result;
    }

    /**
     * 设置 / 更新单个 key 的值
     */
    @Transactional
    public void set(String key, String value) {
        SystemSetting setting = systemSettingRepository.findByKey(key).orElseGet(SystemSetting::new);
        setting.setKey(key);
        setting.setValue(value);
        systemSettingRepository.save(setting);
    }

    /**
     * 批量写入配置
     */
    @Transactional
    public void setMany(Map<String, String> values) {
        if (values == null || values.isEmpty()) {
            return;
        }
        for (Map.Entry<String, String> entry : values.entrySet()) {
            set(entry.getKey(), entry.getValue());
        }
    }
}


