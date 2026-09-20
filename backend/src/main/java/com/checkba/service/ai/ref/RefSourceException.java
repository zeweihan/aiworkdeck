// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.service.ai.ref;

/**
 * 面向模型的、可直接转述给用户的失败原因（例如「设备离线」「窗格没有连着」）。
 * ReferenceSourceService 把它转成「错误：&lt;message&gt;」回喂模型，所以 message 必须是说人话的完整句子。
 */
public class RefSourceException extends RuntimeException {

    public RefSourceException(String message) {
        super(message);
    }
}
