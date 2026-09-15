// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.plugin.api;

/** 插件级键值设置（键自动加前缀 plugin.&lt;id&gt;.）与项目样式画像。 */
public interface Settings {
    String get(String key);
    void set(String key, String value);
    /** 项目样式画像 JSON（解析顺序见 SPEC §3.4）；没有任何画像时返回 null。 */
    String projectStyleProfileJson(long projectId);
}
