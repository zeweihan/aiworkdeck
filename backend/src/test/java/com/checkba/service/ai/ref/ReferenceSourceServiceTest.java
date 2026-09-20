// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.service.ai.ref;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ReferenceSourceService：ref 前缀分派、固定来源顺序、逐来源失败并列、统一截断与空文本兜底。
 * 工具输出永不为空串（ToolExecutionResultMessage.ensureNotBlank 地雷），这里每条路径都断言非空白。
 */
class ReferenceSourceServiceTest {

    static RefSource fake(String scheme, List<RefEntry> entries, String text, RuntimeException listErr) {
        return new RefSource() {
            public String scheme() { return scheme; }
            public List<RefEntry> list(RefQuery q) { if (listErr != null) throw listErr; return entries; }
            public String read(RefQuery q, String body, String locator) { return text; }
        };
    }

    final RefQuery q = new RefQuery(7L, 11L, "conv-a", null);

    @Test
    void listMergesInFixedOrderAndReportsFailures() {
        var svc = new ReferenceSourceService(List.of(
                fake("cloud", List.of(new RefEntry("cloud:5", "cloud", "C.docx", "C.docx", null, null, null)), "", null),
                fake("desk", List.of(), "", new RefSourceException("设备离线")),
                fake("open", List.of(new RefEntry("open:B", "open", "B.docx", "B.docx", "word", null, null)), "", null)));
        String out = svc.list(q, null);
        assertThat(out.indexOf("open:B")).isLessThan(out.indexOf("cloud:5"));
        assertThat(out).contains("[desk] 不可用：设备离线");
    }

    @Test
    void sourceFilterLimitsToOne() {
        var svc = new ReferenceSourceService(List.of(
                fake("open", List.of(new RefEntry("open:B", "open", "B", "B", null, null, null)), "", null),
                fake("cloud", List.of(new RefEntry("cloud:5", "cloud", "C", "C", null, null, null)), "", null)));
        assertThat(svc.list(q, "cloud")).contains("cloud:5").doesNotContain("open:B");
    }

    @Test
    void sourceFilterAcceptsDesktopAliasAndRejectsUnknown() {
        var svc = new ReferenceSourceService(List.of(
                fake("desk", List.of(new RefEntry("desk:d1:p1:a.docx", "desk", "a.docx", "a.docx", null, null, true)), "", null),
                fake("cloud", List.of(new RefEntry("cloud:5", "cloud", "C", "C", null, null, null)), "", null)));
        // 设计文档写的是 desktop，工具描述写的是 desk：两种写法都要认
        assertThat(svc.list(q, "desktop")).contains("desk:d1:p1:a.docx").doesNotContain("cloud:5");
        assertThat(svc.list(q, " DESK ")).contains("desk:d1:p1:a.docx");
        // 拼错的来源不能被当成「没有找到文件」——那会让模型以为文件不存在
        assertThat(svc.list(q, "dropbox")).startsWith("错误：").contains("open/desk/cloud/case/git");
    }

    @Test
    void openableAndHostAreRenderedInTheLine() {
        var svc = new ReferenceSourceService(List.of(
                fake("desk", List.of(new RefEntry("desk:d1:p1:a.docx", "desk", "a.docx", "合同/a.docx", "MacBook", null, true)), "", null)));
        String out = svc.list(q, null);
        assertThat(out).contains("desk:d1:p1:a.docx | desk | 合同/a.docx | MacBook | openable");
    }

    @Test
    void unavailableSourceIsSkippedInListAndReportedOnRead() {
        RefSource offline = new RefSource() {
            public String scheme() { return "case"; }
            public boolean available(RefQuery rq) { return false; }
            public List<RefEntry> list(RefQuery rq) { throw new AssertionError("不可用的来源不该被列出"); }
            public String read(RefQuery rq, String body, String locator) { throw new AssertionError("不可用的来源不该被读取"); }
        };
        var svc = new ReferenceSourceService(List.of(
                offline,
                fake("cloud", List.of(new RefEntry("cloud:5", "cloud", "C", "C", null, null, null)), "", null)));
        assertThat(svc.list(q, null)).contains("cloud:5").doesNotContain("[case]");
        assertThat(svc.read(q, "case:3:a.docx", null)).startsWith("错误：").contains("不可用");
    }

    @Test
    void listIsCappedAtOneHundredEntriesWithANote() {
        List<RefEntry> many = new ArrayList<>();
        for (int i = 0; i < 150; i++) {
            many.add(new RefEntry("cloud:" + i, "cloud", "f" + i, "f" + i, null, null, null));
        }
        var svc = new ReferenceSourceService(List.of(fake("cloud", many, "", null)));
        String out = svc.list(q, null);
        assertThat(out).contains("cloud:99").doesNotContain("cloud:100 ");
        assertThat(out.lines().filter(l -> l.startsWith("cloud:")).count()).isEqualTo(100);
        assertThat(out).contains("100");
    }

    @Test
    void emptyListIsNeverBlank() {
        var svc = new ReferenceSourceService(List.of(fake("open", List.of(), "", null)));
        assertThat(svc.list(q, null)).isNotBlank();
        // 一个来源都没登记（例如早期部署）也不能返回空串
        assertThat(new ReferenceSourceService(List.of()).list(q, null)).isNotBlank();
    }

    @Test
    void readDispatchesAndCaps() {
        String big = "x".repeat(200_010);
        var svc = new ReferenceSourceService(List.of(fake("cloud", List.of(), big, null)));
        String out = svc.read(q, "cloud:5", null);
        assertThat(out).endsWith("...(截断)");
        assertThat(out.length()).isLessThan(200_100);
    }

    @Test
    void readPassesBodyAndLocatorToTheSource() {
        AtomicReference<String> seen = new AtomicReference<>();
        RefSource open = new RefSource() {
            public String scheme() { return "open"; }
            public List<RefEntry> list(RefQuery rq) { return List.of(); }
            public String read(RefQuery rq, String body, String locator) {
                seen.set(body + "|" + locator + "|" + rq.conversationId());
                return "第三页正文";
            }
        };
        var svc = new ReferenceSourceService(List.of(open));
        assertThat(svc.read(q, "open:pane-9", "page:3")).isEqualTo("第三页正文");
        // body 里再有冒号也原样交给来源（desk:<设备>:<项目>:<路径> 就是这种形态）
        svc.read(q, "open:a:b:c", null);
        assertThat(seen.get()).isEqualTo("a:b:c|null|conv-a");
    }

    @Test
    void unknownSchemeAndBlankTextHaveMessages() {
        var svc = new ReferenceSourceService(List.of(fake("cloud", List.of(), "  ", null)));
        assertThat(svc.read(q, "zzz:1", null)).contains("ref_list");
        assertThat(svc.read(q, null, null)).contains("ref_list");
        assertThat(svc.read(q, "no-colon", null)).contains("ref_list");
        assertThat(svc.read(q, "cloud:5", null)).contains("没有可读取的文字");
    }

    @Test
    void sourceFailuresBecomeActionableErrors() {
        RefSource failing = new RefSource() {
            public String scheme() { return "desk"; }
            public List<RefEntry> list(RefQuery rq) { return List.of(); }
            public String read(RefQuery rq, String body, String locator) {
                throw new RefSourceException("设备《办公室 iMac》离线，请打开桌面端或手动上传文件");
            }
        };
        RefSource crashing = new RefSource() {
            public String scheme() { return "cloud"; }
            public List<RefEntry> list(RefQuery rq) { return List.of(); }
            public String read(RefQuery rq, String body, String locator) { throw new IllegalStateException("boom"); }
        };
        var svc = new ReferenceSourceService(List.of(failing, crashing));
        assertThat(svc.read(q, "desk:d1:p1:a.docx", null))
                .isEqualTo("错误：设备《办公室 iMac》离线，请打开桌面端或手动上传文件");
        // 非预期异常也不许掀翻整轮：转成一句带「错误：」前缀的可行动文案（失败判据认「错误」前缀）
        assertThat(svc.read(q, "cloud:5", null)).startsWith("错误：").isNotBlank();
    }

    @Test
    void editOnReadOnlySourceIsRefused() {
        var svc = new ReferenceSourceService(List.of(fake("cloud", List.of(), "t", null)));
        assertThat(svc.edit(q, "cloud:5", "replace_text", Map.of())).startsWith("错误：").contains("没有打开");
    }

    @Test
    void openOnNonDesktopSourceIsRefused() {
        var svc = new ReferenceSourceService(List.of(fake("cloud", List.of(), "t", null)));
        assertThat(svc.open(q, "cloud:5")).startsWith("错误：").contains("桌面端");
    }

    @Test
    void editAndOpenDispatchToTheSource() {
        RefSource open = new RefSource() {
            public String scheme() { return "open"; }
            public List<RefEntry> list(RefQuery rq) { return List.of(); }
            public String read(RefQuery rq, String body, String locator) { return "t"; }
            public String edit(RefQuery rq, String body, String command, Map<String, Object> args) {
                return "edited " + body + " " + command + " " + args.get("find");
            }
        };
        RefSource desk = new RefSource() {
            public String scheme() { return "desk"; }
            public List<RefEntry> list(RefQuery rq) { return List.of(); }
            public String read(RefQuery rq, String body, String locator) { return "t"; }
            public String open(RefQuery rq, String body) { return "opened " + body; }
        };
        var svc = new ReferenceSourceService(List.of(open, desk));
        assertThat(svc.edit(q, "open:pane-9", "replace_text", Map.of("find", "甲方"))).isEqualTo("edited pane-9 replace_text 甲方");
        assertThat(svc.open(q, "desk:d1:p1:a.docx")).isEqualTo("opened d1:p1:a.docx");
    }

    @Test
    void blankEditResultIsNeverReturned() {
        RefSource open = new RefSource() {
            public String scheme() { return "open"; }
            public List<RefEntry> list(RefQuery rq) { return List.of(); }
            public String read(RefQuery rq, String body, String locator) { return "t"; }
            public String edit(RefQuery rq, String body, String command, Map<String, Object> args) { return " "; }
            public String open(RefQuery rq, String body) { return null; }
        };
        var svc = new ReferenceSourceService(List.of(open));
        assertThat(svc.edit(q, "open:pane-9", "replace_text", Map.of())).isNotBlank();
    }

    @Test
    void capLeavesShortTextAlone() {
        assertThat(ReferenceSourceService.cap("短文")).isEqualTo("短文");
        assertThat(ReferenceSourceService.cap("y".repeat(200_000))).hasSize(200_000);
    }
}
