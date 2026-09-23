// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.service.ai;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * {@code ask_user} 工具的一次提问（dev-board#868）：参数校验、事件载荷与落库标记的<b>唯一</b>口径。
 *
 * <p>三处消费同一个对象，任何一处各写一份都会漂移：
 * <ul>
 *   <li>{@code AskUserTools.ask_user}：只做校验，失败回一句 {@code Error:} 让模型改参数重发；</li>
 *   <li>{@code AgentOrchestrator}：工具成功后<b>按参数</b>重建本对象（不读工具输出——回放评测里
 *       工具返回的是桩输出），发 SSE {@code ask_user} 事件、把 {@link #toMarkup()} 流给前端并落库，
 *       然后停机等回答；</li>
 *   <li>前端：实时走事件，历史回灌与 Office/WPS 任务窗格走 {@link #toMarkup()} 那段
 *       {@code <question>} 标记（插件的解析器本来就认 {@code <question>}/{@code <option>}，
 *       多出来的属性它忽略，于是插件端不改一行也能显示问题与单选按钮）。</li>
 * </ul>
 *
 * <p>用户的回答以 {@code <ask_user_answer id="...">} 开头的一条普通用户消息到达（前端
 * {@code utils/askUserAnswer.mjs} 拼装），没有阻塞等待、没有长轮询——与 {@code <question>}
 * 反问停机同一套 AWAITING_INPUT 语义。
 */
public record AskUserQuestion(String id, String question, String header,
                              List<Option> options, boolean multiSelect) {

    /** 工具名（{@code AskUserTools} 的方法名必须与它一致，ToolDeclarationContractTest 钉着）。 */
    public static final String TOOL_NAME = "ask_user";
    /** SSE 事件名。 */
    public static final String SSE_EVENT = "ask_user";
    /** 事件载荷版本：字段只加不改；要改语义时升这个数，前端按它分支。 */
    public static final int SSE_VERSION = 1;
    /** 落库标记里区分「ask_user 工具提的问」与「模型自己写的 <question> 标签」的属性值。 */
    public static final String MARKUP_KIND = "ask_user";
    /** 回答消息的开头标签（前端拼、后端据此认出「这是对提问的回答」）。 */
    public static final String ANSWER_TAG = "ask_user_answer";

    static final int MIN_OPTIONS = 2;
    static final int MAX_OPTIONS = 4;
    static final int MAX_QUESTION_CHARS = 2000;
    static final int MAX_LABEL_CHARS = 60;
    static final int MAX_DESCRIPTION_CHARS = 200;
    static final int MAX_HEADER_CHARS = 16;

    /**
     * 前端恒加一个「其他」自由输入。模型自己再写一个「其他」会出现两个同义入口，
     * 这里静默丢掉（而不是报错让模型白烧一轮）。
     */
    private static final Set<String> OTHER_LABELS = Set.of("其他", "其它", "其他（请说明）", "other", "others",
            "other (please specify)", "something else");

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public record Option(String label, String description) {
    }

    /** 一个进程内唯一、不可猜的短 id（只用于把回答对应到提问，不承担鉴权）。 */
    public static String newId() {
        return "ask-" + Long.toString(System.currentTimeMillis(), 36)
                + Integer.toString(RANDOM.nextInt(36 * 36 * 36 * 36), 36);
    }

    /**
     * 按工具参数构造并校验。校验不过抛 {@link IllegalArgumentException}，message 是可以原样
     * 回给模型的英文说明（模型据此改参数重发）。
     *
     * @param optionsRaw JSON 数组（元素为字符串或 {@code {label, description}}）；也容忍
     *                   模型把数组直接放进参数（ToolRegistry 转成的 JSON 文本）。可空 = 开放式提问。
     */
    public static AskUserQuestion of(String question, String optionsRaw, Boolean multiSelect,
                                     String header, String id) {
        String q = clean(question);
        if (q.isEmpty()) {
            throw new IllegalArgumentException("'question' is required: write the one thing you need the user to decide.");
        }
        if (q.length() > MAX_QUESTION_CHARS) {
            throw new IllegalArgumentException("'question' is too long (max " + MAX_QUESTION_CHARS
                    + " characters). Ask ONE short question; put background in the option descriptions.");
        }
        List<Option> opts = parseOptions(optionsRaw);
        if (opts.size() == 1) {
            throw new IllegalArgumentException("Give either 2-4 options or none. A single option is not a choice "
                    + "(the UI always adds an 'Other' free-text answer).");
        }
        if (opts.size() > MAX_OPTIONS) {
            throw new IllegalArgumentException("Too many options (" + opts.size() + "); give at most " + MAX_OPTIONS
                    + " distinct interpretations. The UI already adds an 'Other' free-text answer.");
        }
        String h = clean(header);
        if (h.length() > MAX_HEADER_CHARS) {
            h = h.substring(0, MAX_HEADER_CHARS);
        }
        boolean multi = Boolean.TRUE.equals(multiSelect) && !opts.isEmpty();
        return new AskUserQuestion(id, q, h, List.copyOf(opts), multi);
    }

    /**
     * 按工具调用的参数 JSON（原生 function call 的 arguments，或 XML 兜底映射出的命名参数）构造。
     * 编排器在工具已经校验通过之后调用，所以这里只可能在参数被桩掉的回放里失败。
     */
    public static AskUserQuestion fromArgsJson(String argsJson, String id) {
        cn.hutool.json.JSONObject args;
        try {
            args = argsJson == null || argsJson.isBlank()
                    ? new cn.hutool.json.JSONObject()
                    : cn.hutool.json.JSONUtil.parseObj(argsJson);
        } catch (Exception e) {
            throw new IllegalArgumentException("ask_user arguments are not a JSON object", e);
        }
        Object options = args.get("options");
        Object multi = args.get("multi_select");
        Boolean multiSelect = multi instanceof Boolean b ? b
                : multi != null && "true".equalsIgnoreCase(String.valueOf(multi).trim());
        return of(args.getStr("question"), options == null ? null : String.valueOf(options),
                multiSelect, args.getStr("header"), id);
    }

    private static List<Option> parseOptions(String raw) {
        List<Option> out = new ArrayList<>();
        String text = raw == null ? "" : raw.trim();
        if (text.isEmpty() || "null".equalsIgnoreCase(text) || "[]".equals(text)) {
            return out;
        }
        if (!text.startsWith("[")) {
            throw new IllegalArgumentException("'options' must be a JSON array such as "
                    + "[{\"label\":\"...\",\"description\":\"...\"}, ...] or [\"...\", \"...\"].");
        }
        cn.hutool.json.JSONArray arr;
        try {
            arr = cn.hutool.json.JSONUtil.parseArray(text);
        } catch (Exception e) {
            throw new IllegalArgumentException("'options' is not valid JSON: " + e.getMessage());
        }
        Set<String> seen = new LinkedHashSet<>();
        for (Object item : arr) {
            String label;
            String description = "";
            if (item instanceof cn.hutool.json.JSONObject obj) {
                label = clean(firstNonBlank(obj.getStr("label"), obj.getStr("text"), obj.getStr("title")));
                description = clean(obj.getStr("description"));
            } else {
                label = clean(item == null ? "" : String.valueOf(item));
            }
            label = label.replace('\n', ' ').replace('\r', ' ');
            if (label.isEmpty() || OTHER_LABELS.contains(label.toLowerCase(Locale.ROOT))) {
                continue;
            }
            if (label.length() > MAX_LABEL_CHARS) {
                throw new IllegalArgumentException("Option label too long: '" + label.substring(0, 20)
                        + "...' (max " + MAX_LABEL_CHARS + " characters). Keep the label short and move "
                        + "details into 'description'.");
            }
            if (!seen.add(label)) {
                throw new IllegalArgumentException("Option labels must be distinct; '" + label + "' appears twice.");
            }
            if (description.length() > MAX_DESCRIPTION_CHARS) {
                description = description.substring(0, MAX_DESCRIPTION_CHARS);
            }
            out.add(new Option(label, description));
        }
        return out;
    }

    /**
     * 落库与文本流里的形态：
     * {@code <question kind="ask_user" id=".." header=".." multi="true">问题<option description="..">选项</option></question>}。
     *
     * <p>属性值做实体转义（前端 {@code decodeAttr} 还原），正文与选项文字只中和协议标签形状
     * （{@link AgentTagProtocol#escape}）——合同里的 {@code <甲方>} 这类占位符要原样显示。
     */
    public String toMarkup() {
        StringBuilder sb = new StringBuilder();
        sb.append("\n<question kind=\"").append(MARKUP_KIND).append('"');
        if (id != null && !id.isBlank()) sb.append(" id=\"").append(attr(id)).append('"');
        if (header != null && !header.isBlank()) sb.append(" header=\"").append(attr(header)).append('"');
        if (multiSelect) sb.append(" multi=\"true\"");
        sb.append('>').append(AgentTagProtocol.escape(question));
        for (Option o : options) {
            sb.append("\n<option");
            if (o.description() != null && !o.description().isBlank()) {
                sb.append(" description=\"").append(attr(o.description())).append('"');
            }
            sb.append('>').append(AgentTagProtocol.escape(o.label())).append("</option>");
        }
        sb.append("\n</question>\n");
        return sb.toString();
    }

    /** SSE {@code ask_user} 事件载荷（字符串形态——SseEmitterService 不替调用方序列化）。 */
    public String toEventJson() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("v", SSE_VERSION);
        payload.put("id", id);
        payload.put("question", question);
        payload.put("header", header == null ? "" : header);
        List<Map<String, String>> opts = new ArrayList<>();
        for (Option o : options) {
            Map<String, String> m = new LinkedHashMap<>();
            m.put("label", o.label());
            m.put("description", o.description() == null ? "" : o.description());
            opts.add(m);
        }
        payload.put("options", opts);
        payload.put("multiSelect", multiSelect);
        try {
            return MAPPER.writeValueAsString(payload);
        } catch (Exception e) {
            // 纯 String/Boolean/List 的 Map 序列化不会失败；万一失败也不能让停机路径抛出去
            return "{\"v\":" + SSE_VERSION + ",\"id\":\"" + attr(id) + "\"}";
        }
    }

    /** 用户消息是不是对一次 ask_user 提问的回答（只看开头，前端拼的消息恒以该标签开头）。 */
    public static boolean isAnswerMessage(String userMessage) {
        return userMessage != null && userMessage.stripLeading().startsWith("<" + ANSWER_TAG);
    }

    private static String attr(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\n", " ").replace("\r", " ");
    }

    private static String clean(String s) {
        return s == null ? "" : s.strip();
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) return v;
        }
        return "";
    }
}
