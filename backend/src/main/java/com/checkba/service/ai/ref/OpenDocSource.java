// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.service.ai.ref;

import com.checkba.service.addin.PaneRegistry;
import com.checkba.service.ai.OfficeBridgeService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * 其他打开着的文档（dev-board#717），ref 形如 {@code open:<paneId>}。
 *
 * <p>发起方 A 永远不直接碰 B 的文件字节：读、写都经 {@link OfficeBridgeService#executeOnPane}
 * 下发到 B 自己的窗格，由 B 窗格在宿主里执行。读取走窗格命令 {@code read_for_reference}
 * （args {@code {locator}}，回 {@code {text}}）；写入把 command/args 原样下发，
 * B 窗格见到 origin 就强制标修订、记修订记录——这是 D 决策里唯一可写的来源。
 *
 * <p>红线：只转发不留存，这里不记任何文档文字。
 */
@Component
@RequiredArgsConstructor
public class OpenDocSource implements RefSource {

    private static final String READ_COMMAND = "read_for_reference";

    private final PaneRegistry registry;
    private final OfficeBridgeService bridge;
    private final ObjectMapper mapper;

    @Override
    public String scheme() {
        return "open";
    }

    @Override
    public List<RefEntry> list(RefQuery q) {
        String self = selfPane(q).map(PaneRegistry.PaneInfo::paneId).orElse(null);
        String kw = q.query() == null ? "" : q.query().trim().toLowerCase(Locale.ROOT);
        List<RefEntry> out = new ArrayList<>();
        for (PaneRegistry.PaneInfo p : registry.list(q.userId(), self)) {
            // 寻址不到的窗格（见 requireAddressable）列出来只会让模型读回错文档
            if (unaddressable(q, p)) {
                continue;
            }
            String name = p.docName() == null || p.docName().isBlank() ? "(未命名)" : p.docName();
            if (!kw.isEmpty() && !name.toLowerCase(Locale.ROOT).contains(kw)) {
                continue;
            }
            // host 让模型知道该用哪一族命令（replace_text / excel_* / ppt_*）
            out.add(new RefEntry("open:" + p.paneId(), "open", name, name, hostLabel(p), null, null));
        }
        return out;
    }

    @Override
    public String read(RefQuery q, String body, String locator) {
        PaneRegistry.PaneInfo t = target(q, body);
        requireAddressable(q, t);
        Map<String, Object> args = new HashMap<>();
        if (locator != null && !locator.isBlank()) {
            args.put("locator", locator.trim());
        }
        String json = bridge.executeOnPane(t, READ_COMMAND, args, origin(q));
        Map<?, ?> m;
        try {
            m = parse(json);
        } catch (RefSourceException e) {
            // 通用超时文案是写给写入命令的（「请不要直接重试这条写入命令」），对读取是误导
            if (e.getMessage() != null && e.getMessage().startsWith(OfficeBridgeService.TIMEOUT_PREFIX)) {
                throw new RefSourceException(docLabel(t) + "的 AI WorkDeck 窗格没有按时返回内容，可稍后重试；"
                        + "若反复超时，请用户确认该文档的窗格仍然打开着。");
            }
            throw e;
        }
        Object text = m.get("text");
        return text == null ? "" : String.valueOf(text);
    }

    @Override
    public String edit(RefQuery q, String body, String command, Map<String, Object> args) {
        PaneRegistry.PaneInfo t = target(q, body);
        requireAddressable(q, t);
        String json = bridge.executeOnPane(t, command, args == null ? Map.of() : args, origin(q));
        parse(json); // 窗格回错时抛出，由 ReferenceSourceService 转成「错误：…」
        return json;
    }

    private PaneRegistry.PaneInfo target(RefQuery q, String paneId) {
        if (paneId == null || paneId.isBlank()) {
            throw new RefSourceException("无法识别的引用，请先用 ref_list 获取 ref。");
        }
        return registry.find(q.userId(), paneId.trim()).orElseThrow(() -> new RefSourceException(
                "这个文档的窗格已经关闭或超过 90 秒没有响应，请在该文档里重新打开 AI WorkDeck 窗格。"));
    }

    private Optional<PaneRegistry.PaneInfo> selfPane(RefQuery q) {
        return registry.paneOfConversation(q.userId(), q.conversationId());
    }

    private static boolean sharesCallerConversation(RefQuery q, PaneRegistry.PaneInfo p) {
        return q.conversationId() != null && q.conversationId().equals(p.conversationId());
    }

    /**
     * 目标必须能被单独寻址，否则宁可拒绝（dev-board#717）。
     *
     * <p>下发走的是 conversationId：{@link OfficeBridgeService#executeOnPane} 把 client_action
     * 推给目标窗格<b>当前会话</b>那条 SSE。所以「两个窗格、同一条会话」在通道上根本分不开——
     * 命令落到当时占着这条会话的那个窗格，读回来的是<b>另一份文档</b>的正文，却顶着目标文档的
     * 名字交给模型，全链路没有一处会报错。这是最贵的一种错：用错的条款去改合同，没人看得出来。
     *
     * <p>撞会话的成因有三种，都在这里一并挡住：目标就是发起方自己（改自己走 office_*）、
     * 目标与发起方撞在一条会话上、目标与<b>另一个第三方窗格</b>撞在一条会话上——最后这种
     * 与发起方无关，只看目标那条会话上挂着几个窗格。插件侧的会话键按「项目+宿主+文档」分，
     * 撞了说明其中一个还没换到新键（两份未保存的新文档就是这样），让用户在那个窗格里
     * 点「新对话」即可分开。
     */
    private void requireAddressable(RefQuery q, PaneRegistry.PaneInfo t) {
        if (sharesCallerConversation(q, t)) {
            String self = selfPane(q).map(PaneRegistry.PaneInfo::paneId).orElse(null);
            if (t.paneId().equals(self)) {
                throw new RefSourceException("这是当前文档本身，请直接用 office_* 工具读取或修改。");
            }
            throw new RefSourceException(twinPaneMessage(t, "当前窗格"));
        }
        if (sharedByAnotherPane(q, t)) {
            throw new RefSourceException(twinPaneMessage(t, "另一个窗格"));
        }
    }

    /** 这个窗格现在能不能被单独寻址（清单与下发用同一判据）。 */
    private boolean unaddressable(RefQuery q, PaneRegistry.PaneInfo p) {
        return sharesCallerConversation(q, p) || sharedByAnotherPane(q, p);
    }

    /** 目标那条会话上还挂着别的窗格：谁抢到 emitter 谁执行，目标寻址不到。 */
    private boolean sharedByAnotherPane(RefQuery q, PaneRegistry.PaneInfo p) {
        return registry.panesOfConversation(q.userId(), p.conversationId()).size() > 1;
    }

    private static String twinPaneMessage(PaneRegistry.PaneInfo t, String other) {
        return docLabel(t) + "的窗格与" + other + "共用同一条会话，命令没法单独发给它，"
                + "读回来的可能是另一份文档。请让用户在该文档的窗格里点「新对话」后重试。";
    }

    /**
     * 发起方信息。发起方窗格没登记上（心跳还没到）、或发起方那条会话上挂着不止一个窗格
     * （{@link PaneRegistry#paneOfConversation} 那时不猜）也照样给 origin：B 窗格靠它判定
     * 跨文档写入、强制标修订，缺了它写入就会被当成 B 自己的操作。认不准发起方是哪一份文档时
     * 用通称「另一个文档」——总比把另一份文档的名字署到修订记录上强。
     */
    private OfficeBridgeService.CrossPaneOrigin origin(RefQuery q) {
        Optional<PaneRegistry.PaneInfo> self = selfPane(q);
        String docName = self.map(PaneRegistry.PaneInfo::docName).filter(n -> !n.isBlank()).orElse("另一个文档");
        return new OfficeBridgeService.CrossPaneOrigin(
                self.map(PaneRegistry.PaneInfo::paneId).orElse(null), docName, q.conversationId());
    }

    private static String hostLabel(PaneRegistry.PaneInfo p) {
        String family = p.family() == null || p.family().isBlank() ? null : p.family();
        String host = p.host() == null || p.host().isBlank() ? null : p.host();
        if (family == null) {
            return host;
        }
        return host == null ? family : family + "/" + host;
    }

    private static String docLabel(PaneRegistry.PaneInfo t) {
        return t.docName() == null || t.docName().isBlank() ? "目标文档" : "《" + t.docName() + "》";
    }

    /** 桥接返回值：{"error":…} 抛出；对象原样；null 视为空结果；其他标量当文本。 */
    private Map<?, ?> parse(String json) {
        Object v;
        try {
            v = mapper.readValue(json, Object.class);
        } catch (Exception e) {
            return Map.of("text", json == null ? "" : json);
        }
        if (v == null) {
            return Map.of();
        }
        if (v instanceof Map<?, ?> m) {
            if (m.containsKey("error")) {
                Object err = m.get("error");
                String msg = err == null || String.valueOf(err).isBlank()
                        ? "目标文档的窗格执行失败（未提供错误信息）。" : String.valueOf(err);
                throw new RefSourceException(msg);
            }
            return m;
        }
        return Map.of("text", String.valueOf(v));
    }
}
