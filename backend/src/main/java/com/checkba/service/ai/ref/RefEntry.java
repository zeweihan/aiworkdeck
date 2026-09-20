// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.service.ai.ref;

/**
 * 参考文件清单里的一项。
 *
 * @param ref       不透明引用串，形如 open:&lt;paneId&gt; / desk:&lt;deviceId&gt;:&lt;projectKey&gt;:&lt;path&gt; /
 *                  cloud:&lt;fileId&gt; / case:&lt;remoteProjectId&gt;:&lt;path&gt; / git:&lt;repoLinkId&gt;:&lt;path&gt;；
 *                  模型只复制、不构造
 * @param source    来源前缀（open / desk / cloud / case / git）
 * @param name      文件名
 * @param path      项目内相对路径（打开文档为文档名）
 * @param host      附加说明（宿主、设备名等），可为 null
 * @param updatedAt 最后修改时间，可为 null
 * @param openable  桌面端能否代为打开（仅 desk 来源有意义），可为 null
 */
public record RefEntry(String ref, String source, String name, String path, String host, String updatedAt,
                       Boolean openable) {
}
