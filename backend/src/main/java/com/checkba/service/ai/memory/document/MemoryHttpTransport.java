// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.service.ai.memory.document;

import java.util.Map;

public interface MemoryHttpTransport {
    record Reply(int status, String body) {}
    Reply send(String method, String url, Map<String, String> headers, String body);
}
