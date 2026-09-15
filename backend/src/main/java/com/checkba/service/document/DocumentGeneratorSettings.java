// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.checkba.service.document;

import com.checkba.service.SystemSettingService;
import org.springframework.stereotype.Service;

/**
 * 文档 Generator 元数据开关（可溯源性设计规范附录 B4）。
 *
 * <p>语义：开启时，我们生成或另存的 OOXML 文件在 {@code docProps/app.xml} 里带上
 * {@code <Application>AI WorkDeck &lt;version&gt;</Application>}。只写产品名与版本，
 * 不含任何用户身份信息。关掉之后一个字段都不写（有些律所交付前要清除文档元数据）。
 *
 * <p>写法照 {@code TelemetrySettings}：一个 SystemSettingService 键，缺省 true。
 */
@Service
public class DocumentGeneratorSettings {

    public static final String KEY_ENABLED = "document.generator.enabled";

    private final SystemSettingService settings;

    public DocumentGeneratorSettings(SystemSettingService settings) {
        this.settings = settings;
    }

    public boolean enabled() {
        return Boolean.parseBoolean(settings.get(KEY_ENABLED, "true"));
    }

    public void setEnabled(boolean v) {
        settings.set(KEY_ENABLED, Boolean.toString(v));
    }
}
