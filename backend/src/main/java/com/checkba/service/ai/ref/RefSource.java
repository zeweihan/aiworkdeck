// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.service.ai.ref;

import java.util.List;
import java.util.Map;

/**
 * 参考材料的一个来源（dev-board#717-720）：其他打开着的文档、桌面端项目、云端项目、官方案件库、git 仓库。
 * 由 {@link ReferenceSourceService} 按 ref 前缀分派。
 *
 * <p>D 决策：只有打开着的文档（open）可写，其余来源一律只读——{@link #edit} 默认拒绝。
 * 失败原因用 {@link RefSourceException} 抛出，message 会原样转述给模型与用户。
 */
public interface RefSource {

    /** ref 前缀：open / desk / cloud / case / git */
    String scheme();

    /** 该来源在本上下文是否可用（例如国际站没有案件库）；不可用时 list 跳过、read 报「来源不可用」。 */
    default boolean available(RefQuery q) {
        return true;
    }

    List<RefEntry> list(RefQuery q);

    /** body = ref 去掉 "scheme:" 之后的部分；返回纯文本（未截断，由 service 统一截断）。 */
    String read(RefQuery q, String body, String locator);

    /** 只有 open 来源实现；其他来源默认拒绝。 */
    default String edit(RefQuery q, String body, String command, Map<String, Object> args) {
        throw new RefSourceException("该文件没有打开，不能直接修改。请用户先打开它，并在该文档里打开 AI WorkDeck 窗格。");
    }

    /** 只有 desk 来源实现。 */
    default String open(RefQuery q, String body) {
        throw new RefSourceException("只有桌面端项目里的文件可以代为打开。");
    }

    /**
     * 未打开的来源（desk / cloud / case / git）抽出的是纯文本、没有页的概念：
     * 带 locator 时明说返回的是全文，而不是悄悄忽略——否则模型会把全文当成「第 3 页」来引用。
     *
     * <p>一个字都没抽出来时原样返回空白：给空白加上「以下为全文」的抬头，等于告诉模型
     * 「这份文件的全文就是空的」。空白由 {@link ReferenceSourceService} 统一换成
     * 「该文件没有可读取的文字。」，工具输出因此仍然非空。
     */
    static String withLocatorNote(String locator, String text) {
        if (text == null || text.isBlank()) {
            return text;
        }
        return locator == null || locator.isBlank() ? text : "未打开的文件无法按页定位，以下为全文。\n\n" + text;
    }
}
