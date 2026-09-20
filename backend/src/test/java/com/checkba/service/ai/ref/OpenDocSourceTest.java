// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.service.ai.ref;

import com.checkba.service.addin.PaneRegistry;
import com.checkba.service.ai.OfficeBridgeService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * OpenDocSource（dev-board#717）：经同账号的另一个插件窗格读、改那份打开着的文档。
 *
 * 要守住的东西：
 * 1. 清单里没有发起方自己（改自己走 office_*，不走 ref_edit）；
 * 2. 读取下发 read_for_reference 且带上发起方 origin（B 窗格据此记来源）；
 * 3. 窗格回的错误原样变成可转述的失败原因，不被当成正文；
 * 4. 读取超时不能沿用写入命令那句「请不要直接重试这条写入命令」——那对读取是误导；
 * 5. 与发起方共用一条会话的窗格（含发起方自己）既不列也不下发——下发按 conversationId 走，
 *    这种目标寻址不到，读回来的会是另一份文档的正文，且沿途无人报错。
 */
class OpenDocSourceTest {

    final PaneRegistry registry = new PaneRegistry();
    final OfficeBridgeService bridge = mock(OfficeBridgeService.class);
    final OpenDocSource src = new OpenDocSource(registry, bridge, new ObjectMapper());
    final RefQuery q = new RefQuery(7L, 11L, "conv-a", null);

    OpenDocSourceTest() {
        registry.heartbeat(7L, new PaneRegistry.PaneInfo("A", 7L, "word", "office", "A.docx", 11L, "conv-a", 0));
        registry.heartbeat(7L, new PaneRegistry.PaneInfo("B", 7L, "powerpoint", "office", "B.pptx", 11L, "conv-b", 0));
    }

    @Test
    void schemeIsOpen() {
        assertThat(src.scheme()).isEqualTo("open");
    }

    @Test
    void listExcludesCallerPaneAndFiltersByQuery() {
        assertThat(src.list(q)).extracting(RefEntry::ref).containsExactly("open:B");
        assertThat(src.list(new RefQuery(7L, 11L, "conv-a", "zzz"))).isEmpty();
        assertThat(src.list(new RefQuery(7L, 11L, "conv-a", "b.PPT"))).extracting(RefEntry::ref)
                .containsExactly("open:B");
    }

    @Test
    void listEntryCarriesDocNameAndHostSoTheModelPicksTheRightCommands() {
        RefEntry e = src.list(q).get(0);
        assertThat(e.source()).isEqualTo("open");
        assertThat(e.name()).isEqualTo("B.pptx");
        assertThat(e.path()).isEqualTo("B.pptx");
        assertThat(e.host()).contains("powerpoint").contains("office");
    }

    @Test
    void otherUsersPanesAreNotListed() {
        assertThat(src.list(new RefQuery(8L, 11L, "conv-x", null))).isEmpty();
    }

    @Test
    void readSendsReadForReferenceWithOrigin() {
        when(bridge.executeOnPane(any(), eq("read_for_reference"), any(), any()))
                .thenReturn("{\"text\":\"第3页内容\",\"truncated\":false,\"totalChars\":5}");
        assertThat(src.read(q, "B", "slide:3")).isEqualTo("第3页内容");
        verify(bridge).executeOnPane(argThat(p -> p.paneId().equals("B")), eq("read_for_reference"),
                eq(Map.of("locator", "slide:3")),
                argThat(o -> o.docName().equals("A.docx") && o.paneId().equals("A")
                        && o.conversationId().equals("conv-a")));
    }

    @Test
    void readWithoutLocatorSendsEmptyArgs() {
        when(bridge.executeOnPane(any(), eq("read_for_reference"), any(), any())).thenReturn("{\"text\":\"全文\"}");
        assertThat(src.read(q, "B", "  ")).isEqualTo("全文");
        verify(bridge).executeOnPane(any(), eq("read_for_reference"), eq(Map.of()), any());
    }

    @Test
    void readErrorBecomesRefSourceException() {
        when(bridge.executeOnPane(any(), any(), any(), any())).thenReturn("{\"error\":\"本机 Word 不支持按页读取\"}");
        assertThatThrownBy(() -> src.read(q, "B", "page:3")).isInstanceOf(RefSourceException.class)
                .hasMessageContaining("按页读取");
    }

    @Test
    void readNullDataIsEmptyTextNotTheWordNull() {
        when(bridge.executeOnPane(any(), any(), any(), any())).thenReturn("null");
        assertThat(src.read(q, "B", null)).isEmpty();
    }

    @Test
    void readTimeoutDoesNotTellTheModelNotToRetryAWrite() {
        when(bridge.executeOnPane(any(), any(), any(), any())).thenReturn(
                "{\"error\":\"" + OfficeBridgeService.TIMEOUT_PREFIX + "：插件未在 30 秒内返回结果。注意：命令已经下发，宿主端**可能已经执行成功**，"
                        + "只是回执没回来。请不要直接重试这条写入命令（会写入两遍）\"}");
        assertThatThrownBy(() -> src.read(q, "B", null)).isInstanceOf(RefSourceException.class)
                .hasMessageContaining("B.pptx")
                .hasMessageNotContaining("写入");
    }

    @Test
    void unknownPaneIsReportedAsClosed() {
        assertThatThrownBy(() -> src.read(q, "Z", null)).isInstanceOf(RefSourceException.class)
                .hasMessageContaining("已经关闭");
        verify(bridge, never()).executeOnPane(any(), any(), any(), any());
    }

    @Test
    void blankPaneIdIsAnUnrecognisedRef() {
        assertThatThrownBy(() -> src.read(q, " ", null)).isInstanceOf(RefSourceException.class)
                .hasMessageContaining("ref_list");
    }

    @Test
    void editPassesCommandThrough() {
        when(bridge.executeOnPane(any(), eq("replace_text"), any(), any())).thenReturn("{\"replaced\":2}");
        assertThat(src.edit(q, "B", "replace_text", Map.of("find", "甲", "replace", "乙"))).contains("replaced");
        verify(bridge).executeOnPane(argThat(p -> p.paneId().equals("B")), eq("replace_text"),
                eq(Map.of("find", "甲", "replace", "乙")),
                argThat(o -> o.docName().equals("A.docx") && o.conversationId().equals("conv-a")));
    }

    @Test
    void editErrorBecomesRefSourceException() {
        when(bridge.executeOnPane(any(), any(), any(), any()))
                .thenReturn("{\"error\":\"本机 Word 版本无法标记修订，已拒绝跨文档修改\"}");
        assertThatThrownBy(() -> src.edit(q, "B", "replace_text", Map.of())).isInstanceOf(RefSourceException.class)
                .hasMessageContaining("无法标记修订");
    }

    @Test
    void cannotEditSelf() {
        assertThatThrownBy(() -> src.edit(q, "A", "replace_text", Map.of())).isInstanceOf(RefSourceException.class)
                .hasMessageContaining("office_");
        verify(bridge, never()).executeOnPane(any(), any(), any(), any());
    }

    @Test
    void cannotReadSelf() {
        // 写入一直有这道闸，读取原先没有：读自己会被当成一次正常跨文档读取下发出去
        assertThatThrownBy(() -> src.read(q, "A", null)).isInstanceOf(RefSourceException.class)
                .hasMessageContaining("office_");
        verify(bridge, never()).executeOnPane(any(), any(), any(), any());
    }

    /**
     * 两个窗格撞在同一条会话上（插件旧版本按「项目+宿主」存会话 id，同项目的两份 Word 就是同一条）：
     * 下发按 conversationId 走，这种目标根本寻址不到——命令落到当时占着这条会话的那个窗格，
     * 读回来的是**另一份文档**的正文却顶着目标文档的名字。宁可拒绝，也不许悄悄读错。
     */
    @Test
    void twinPaneOnTheSameConversationIsNeitherListedNorRead() {
        registry.heartbeat(7L, new PaneRegistry.PaneInfo("A2", 7L, "word", "office", "A2.docx", 11L, "conv-a", 0));

        assertThat(src.list(q)).extracting(RefEntry::ref).containsExactly("open:B");
        assertThatThrownBy(() -> src.read(q, "A2", null)).isInstanceOf(RefSourceException.class)
                .hasMessageContaining("A2.docx").hasMessageContaining("新对话");
        assertThatThrownBy(() -> src.edit(q, "A2", "replace_text", Map.of()))
                .isInstanceOf(RefSourceException.class).hasMessageContaining("新对话");
        verify(bridge, never()).executeOnPane(any(), any(), any(), any());
    }

    /**
     * 撞会话的两个窗格都不是发起方（同项目里两份**未保存**的新文档算出同一个会话键）：
     * 与发起方的会话对不上，可 conversationId 仍然指不到某一个窗格——谁占着 emitter 谁执行。
     * 这一条与发起方无关，只看目标那条会话上挂着几个窗格。
     */
    @Test
    void twinPanesOnAThirdConversationAreNeitherListedNorAddressable() {
        registry.heartbeat(7L, new PaneRegistry.PaneInfo("N1", 7L, "word", "office", "文档1", 11L, "conv-x", 0));
        registry.heartbeat(7L, new PaneRegistry.PaneInfo("N2", 7L, "word", "office", "文档2", 11L, "conv-x", 0));

        assertThat(src.list(q)).extracting(RefEntry::ref).containsExactly("open:B");
        assertThatThrownBy(() -> src.read(q, "N1", null)).isInstanceOf(RefSourceException.class)
                .hasMessageContaining("文档1").hasMessageContaining("新对话");
        assertThatThrownBy(() -> src.edit(q, "N2", "replace_text", Map.of()))
                .isInstanceOf(RefSourceException.class).hasMessageContaining("新对话");
        verify(bridge, never()).executeOnPane(any(), any(), any(), any());
    }

    /**
     * 发起方自己那条会话上挂着两个窗格时，「发起方是哪一份文档」没有答案：
     * 宁可署通称，也不能把另一份文档的名字写进目标文档的修订记录与横幅。
     */
    @Test
    void ambiguousCallerIsNeverAttributedToTheWrongDocument() {
        registry.heartbeat(7L, new PaneRegistry.PaneInfo("A2", 7L, "word", "office", "A2.docx", 11L, "conv-a", 0));
        when(bridge.executeOnPane(any(), any(), any(), any())).thenReturn("{\"replaced\":1}");

        src.edit(q, "B", "replace_text", Map.of());
        verify(bridge).executeOnPane(any(), eq("replace_text"), any(),
                argThat(o -> !"A.docx".equals(o.docName()) && !"A2.docx".equals(o.docName())
                        && o.docName() != null && !o.docName().isBlank()
                        && o.conversationId().equals("conv-a")));
    }

    @Test
    void unregisteredCallerStillSendsAnOriginSoTheTargetForcesTracking() {
        RefQuery stranger = new RefQuery(7L, 11L, "conv-unregistered", null);
        when(bridge.executeOnPane(any(), any(), any(), any())).thenReturn("{\"replaced\":1}");
        src.edit(stranger, "B", "replace_text", Map.of());
        verify(bridge).executeOnPane(any(), eq("replace_text"), any(),
                argThat(o -> o != null && o.conversationId().equals("conv-unregistered")
                        && o.docName() != null && !o.docName().isBlank()));
    }
}
