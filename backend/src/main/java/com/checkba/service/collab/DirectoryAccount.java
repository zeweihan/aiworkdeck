// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.service.collab;

/**
 * 名录回的账户事实，**四个字段就是全部**：官网侧刻意不回邮箱、头像、角色、团队名与
 * 律所名——案件库只需要 {@code phone} 做账号归一认领，多回的每一样都是泄露。
 */
public record DirectoryAccount(String accountId, String username, String displayName, String phone) {}
