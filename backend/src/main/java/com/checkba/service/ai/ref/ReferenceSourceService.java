// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.service.ai.ref;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BiFunction;

/**
 * 参考来源分派（dev-board#717-720）：ref_* 工具的唯一后端入口。
 *
 * <ul>
 *   <li>list：按固定顺序 open → desk → cloud → case → git 合并各来源的候选，总数 ≤100；
 *       某个来源失败只并列一行说明，不让整个清单失败。</li>
 *   <li>read / edit / open：按 ref 的 "scheme:" 前缀分派；{@link RefSourceException} 转成
 *       「错误：…」回喂模型（失败判据认「错误」前缀）。</li>
 * </ul>
 *
 * <p>红线：参考材料的文字只在内存里过一遍——不落盘、不计费，日志只记来源、长度、耗时，绝不记正文。
 * 返回值永不为空白（空白工具输出会被 ToolExecutionResultMessage.ensureNotBlank 掀翻整轮）。
 */
@Service
@Slf4j
public class ReferenceSourceService {

    static final int MAX_CHARS = 200_000;
    static final List<String> ORDER = List.of("open", "desk", "cloud", "case", "git");
    static final int MAX_ENTRIES = 100;

    static final String NOTHING_FOUND = "没有找到匹配的文件。可以请用户手动上传，或换个关键词。";
    static final String UNKNOWN_REF = "错误：无法识别的引用，请先用 ref_list 获取 ref。";

    private final Map<String, RefSource> byScheme = new LinkedHashMap<>();

    public ReferenceSourceService(List<RefSource> sources) {
        for (String s : ORDER) {
            for (RefSource src : sources) {
                if (s.equals(src.scheme())) {
                    byScheme.put(s, src);
                }
            }
        }
    }

    public String list(RefQuery q, String sourceFilter) {
        String filter = normaliseSource(sourceFilter);
        if (filter != null && !ORDER.contains(filter)) {
            return "错误：source 只能是 open/desk/cloud/case/git 之一（留空表示全部来源）。";
        }
        StringBuilder sb = new StringBuilder();
        int n = 0;
        boolean more = false;
        for (RefSource src : byScheme.values()) {
            if (filter != null && !src.scheme().equals(filter)) {
                continue;
            }
            if (!src.available(q)) {
                continue;
            }
            try {
                for (RefEntry e : src.list(q)) {
                    if (n >= MAX_ENTRIES) {
                        more = true;
                        break;
                    }
                    sb.append(e.ref()).append(" | ").append(e.source()).append(" | ").append(e.path());
                    if (e.host() != null && !e.host().isBlank()) {
                        sb.append(" | ").append(e.host());
                    }
                    if (Boolean.TRUE.equals(e.openable())) {
                        sb.append(" | openable");
                    }
                    sb.append('\n');
                    n++;
                }
            } catch (RefSourceException ex) {
                sb.append('[').append(src.scheme()).append("] 不可用：").append(ex.getMessage()).append('\n');
            } catch (RuntimeException ex) {
                // 非预期异常的 message 可能夹带上游响应片段，日志只记类型
                log.warn("ref_list source={} failed: {}", src.scheme(), ex.getClass().getName());
                sb.append('[').append(src.scheme()).append("] 不可用：暂时无法访问").append('\n');
            }
        }
        if (n == 0 && sb.isEmpty()) {
            return NOTHING_FOUND;
        }
        if (more) {
            sb.append("（仅列出前 ").append(MAX_ENTRIES).append(" 条，可用 query 缩小范围。）");
        }
        return sb.toString().trim();
    }

    public String read(RefQuery q, String ref, String locator) {
        return route(q, ref, (src, body) -> {
            long start = System.nanoTime();
            String text = src.read(q, body, locator);
            log.info("ref_read source={} chars={} ms={}", src.scheme(), text == null ? 0 : text.length(),
                    (System.nanoTime() - start) / 1_000_000);
            return text == null || text.isBlank() ? "该文件没有可读取的文字。" : cap(text);
        });
    }

    public String edit(RefQuery q, String ref, String command, Map<String, Object> args) {
        return route(q, ref, (src, body) -> nonBlank(src.edit(q, body, command, args)));
    }

    public String open(RefQuery q, String ref) {
        return route(q, ref, (src, body) -> nonBlank(src.open(q, body)));
    }

    private String route(RefQuery q, String ref, BiFunction<RefSource, String, String> fn) {
        String trimmed = ref == null ? "" : ref.trim();
        int i = trimmed.indexOf(':');
        RefSource src = i <= 0 ? null : byScheme.get(trimmed.substring(0, i).toLowerCase(Locale.ROOT));
        if (src == null) {
            return UNKNOWN_REF;
        }
        if (!src.available(q)) {
            return "错误：" + src.scheme() + " 来源在当前环境不可用，请换一个来源，或请用户手动上传文件。";
        }
        try {
            return fn.apply(src, trimmed.substring(i + 1));
        } catch (RefSourceException e) {
            return "错误：" + e.getMessage();
        } catch (RuntimeException e) {
            log.warn("ref source={} failed: {}", src.scheme(), e.getClass().getName());
            return "错误：该来源暂时无法访问，可稍后重试，或请用户手动上传文件。";
        }
    }

    /** 设计文档写 desktop、工具描述写 desk：两种写法都认；空白表示不过滤。 */
    private static String normaliseSource(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String s = raw.trim().toLowerCase(Locale.ROOT);
        return "desktop".equals(s) ? "desk" : s;
    }

    private static String nonBlank(String result) {
        return result == null || result.isBlank()
                ? "错误：来源没有返回结果，无法确认操作是否完成，请让用户在目标文档里查看。"
                : result;
    }

    static String cap(String text) {
        if (text.length() <= MAX_CHARS) {
            return text;
        }
        // 不把代理对切成半个字符（生僻字、emoji 落在边界上时会变成乱码）
        int end = Character.isHighSurrogate(text.charAt(MAX_CHARS - 1)) ? MAX_CHARS - 1 : MAX_CHARS;
        return text.substring(0, end) + "\n...(截断)";
    }
}
