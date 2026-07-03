package com.checkba.service.ai;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * XML 工具调用协议（Root Bubble Protocol 的 &lt;tool_code&gt; 兜底路径）解析器。
 *
 * 部分模型不支持原生 function calling，或在 system prompt 强制 XML 输出时以
 * <pre>&lt;tool_code&gt;tool_name(key="value", ...)&lt;/tool_code&gt;</pre>
 * 形式发起调用。本类负责把这种自由文本解析为规范的 (toolName, argsJson)，
 * 供 {@link ToolRegistry} 统一分发。
 *
 * 容错范围（历史行为保持）：
 * - Python 风格 key="v" / key='v' / 三引号多行字符串 / 转义字符
 * - JSON 风格 tool({"key":"value"})
 * - Gemini 偶发的 &lt;ctrl46&gt; 定界符
 * - 无引号数字参数 key=123
 * - run_python 的多行代码参数优先解析（避免代码内出现其他工具名造成误匹配）
 * - 最长工具名优先匹配（pptx_generate_outline 不被 pptx_generate 抢占）
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class XmlToolCallParser {

    private static final Pattern TOOL_CODE_PATTERN = Pattern.compile("(?s)<(tool_code|code)>(.*?)</\\1>");
    private static final Pattern PROCESS_NAME_PATTERN = Pattern.compile("<process[^>]*name=\"([^\"]*)\"[^>]*>");

    private final ToolRegistry toolRegistry;

    /**
     * 一次解析出的工具调用。
     *
     * @param toolName 解析出的工具名（可能未注册，由分发层判定）
     * @param argsJson 规范化后的 JSON 参数
     * @param rawCode  原始 tool_code 文本（用于日志与反馈消息）
     */
    public record ParsedCall(String toolName, String argsJson, String rawCode) {
    }

    /**
     * 内容中是否包含 XML 工具调用标签。
     */
    public boolean containsToolCall(String content) {
        return content != null && (content.contains("<tool_code>") || content.contains("<code>"));
    }

    /**
     * 提取 LLM 自选的 process 展示名（保持历史记录与实时流式一致）。
     */
    public Optional<String> extractProcessName(String content) {
        Matcher m = PROCESS_NAME_PATTERN.matcher(content);
        if (m.find() && m.group(1) != null && !m.group(1).isEmpty()) {
            return Optional.of(m.group(1));
        }
        return Optional.empty();
    }

    /**
     * 解析内容中的全部工具调用（按出现顺序）。
     */
    public List<ParsedCall> parse(String content) {
        List<ParsedCall> calls = new ArrayList<>();
        if (content == null) {
            return calls;
        }
        Matcher matcher = TOOL_CODE_PATTERN.matcher(content);
        while (matcher.find()) {
            String code = matcher.group(2).trim();
            if (code.isEmpty()) {
                continue;
            }
            calls.add(parseSingle(code));
        }
        return calls;
    }

    ParsedCall parseSingle(String code) {
        // 1. run_python 最优先：其 code 参数内经常包含其他工具名，必须整体提取
        if (code.startsWith("run_python(") || code.contains("run_python(code=")) {
            String pythonCode = extractPythonCodeArg(code);
            cn.hutool.json.JSONObject args = new cn.hutool.json.JSONObject();
            args.set("code", pythonCode);
            return new ParsedCall("run_python", args.toString(), code);
        }

        // 2. 解析调用名：优先取括号前的函数名（含 xx.yy 前缀时取 yy）
        String toolName = resolveToolName(code);

        // 3. JSON 风格整体参数：tool({"key":"value"})
        String jsonArgs = tryExtractJsonObjectArgs(code);
        if (jsonArgs != null) {
            return new ParsedCall(toolName, jsonArgs, code);
        }

        // 4. 按目标工具的参数名逐个提取（支持多行/转义/三引号/ctrl46/无引号值）
        cn.hutool.json.JSONObject args = new cn.hutool.json.JSONObject();
        Optional<ToolRegistry.RegisteredTool> tool = toolRegistry.resolve(
                ToolRegistry.TOOL_NAME_ALIASES.getOrDefault(toolName, toolName));
        if (tool.isPresent()) {
            for (java.lang.reflect.Parameter p : tool.get().method().getParameters()) {
                String paramName = p.getName();
                String value = extractStringArg(code, paramName);
                if (value.isEmpty()) {
                    for (String alias : ToolRegistry.aliasesFor(paramName)) {
                        value = extractStringArg(code, alias);
                        if (!value.isEmpty()) {
                            break;
                        }
                    }
                }
                if (!value.isEmpty()) {
                    args.set(paramName, value);
                }
            }
        } else {
            // 未注册工具（可能是插件或幻觉调用）：通用 key=value 提取，交由分发层判定
            args = cn.hutool.json.JSONUtil.parseObj(extractArgsAsJson(code));
        }
        return new ParsedCall(toolName, args.toString(), code);
    }

    /**
     * 解析工具名：括号前的标识符精确匹配注册表；失败则按最长工具名扫描
     * （兼容 print(legal_tools.search_web(...)) 之类的包裹写法）。
     */
    private String resolveToolName(String code) {
        String leading = parseLeadingName(code);
        String aliased = ToolRegistry.TOOL_NAME_ALIASES.getOrDefault(leading, leading);
        if (toolRegistry.hasTool(aliased)) {
            return leading;
        }
        List<String> namesLongestFirst = toolRegistry.toolNamesLongestFirst();
        for (String name : namesLongestFirst) {
            if (code.contains(name + "(")) {
                return name;
            }
        }
        // 别名工具没有注册实体也允许命中（如 search_laws）
        for (String alias : ToolRegistry.TOOL_NAME_ALIASES.keySet()) {
            if (code.contains(alias + "(")) {
                return alias;
            }
        }
        // 最后兜底：无括号写法（如 Gemini 的 tool{key:<ctrl46>v<ctrl46>}），纯 contains 匹配
        for (String name : namesLongestFirst) {
            if (code.contains(name)) {
                return name;
            }
        }
        return leading;
    }

    private String parseLeadingName(String code) {
        if (code == null) {
            return "";
        }
        int paren = code.indexOf('(');
        if (paren == -1) {
            return code.trim();
        }
        String head = code.substring(0, paren).trim();
        int dot = head.lastIndexOf('.');
        return dot != -1 ? head.substring(dot + 1).trim() : head;
    }

    /**
     * JSON 风格调用：tool({...}) → 直接返回 {...}；不是该风格返回 null。
     */
    private String tryExtractJsonObjectArgs(String code) {
        int jsonStart = code.indexOf("({");
        int jsonEnd = code.lastIndexOf("})");
        if (jsonStart == -1 || jsonEnd == -1 || jsonEnd <= jsonStart) {
            return null;
        }
        String jsonStr = code.substring(jsonStart + 1, jsonEnd + 1);
        try {
            return cn.hutool.json.JSONUtil.parseObj(jsonStr).toString();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 从代码文本中提取字符串参数值。
     * 处理顺序：ctrl46 定界符 → 三引号 → 单/双引号（含转义）→ 无引号值。
     */
    String extractStringArg(String code, String key) {
        try {
            // <ctrl46> 定界符格式：key:<ctrl46>value<ctrl46> 或 key=<ctrl46>value<ctrl46>
            String ctrlDelimiter = "<ctrl46>";
            for (String sep : new String[]{":", "="}) {
                int keyStart = code.indexOf(key + sep + ctrlDelimiter);
                if (keyStart != -1) {
                    int valueStart = keyStart + key.length() + sep.length() + ctrlDelimiter.length();
                    int valueEnd = code.indexOf(ctrlDelimiter, valueStart);
                    if (valueEnd != -1) {
                        return code.substring(valueStart, valueEnd);
                    }
                    int commaEnd = code.indexOf(",", valueStart);
                    int braceEnd = code.indexOf("}", valueStart);
                    int end = Math.min(commaEnd != -1 ? commaEnd : Integer.MAX_VALUE,
                            braceEnd != -1 ? braceEnd : Integer.MAX_VALUE);
                    return end != Integer.MAX_VALUE
                            ? code.substring(valueStart, end).trim()
                            : code.substring(valueStart).trim();
                }
            }

            // Python 三引号：key="""...""" 或 key='''...'''
            for (String quote : new String[]{"\"\"\"", "'''"}) {
                int tripleStart = code.indexOf(key + "=" + quote);
                if (tripleStart != -1) {
                    int valueStart = tripleStart + key.length() + 1 + quote.length();
                    int valueEnd = code.indexOf(quote, valueStart);
                    return valueEnd != -1 ? code.substring(valueStart, valueEnd) : code.substring(valueStart);
                }
            }

            // 单/双引号（逐字符扫描处理转义）
            int keyStart = code.indexOf(key + "=\"");
            char quoteChar = '"';
            if (keyStart == -1) {
                keyStart = code.indexOf(key + "='");
                quoteChar = '\'';
            }

            // 无引号值：key=123 / key=true
            if (keyStart == -1) {
                int unquotedStart = code.indexOf(key + "=");
                if (unquotedStart != -1) {
                    int valueStart = unquotedStart + key.length() + 1;
                    if (valueStart < code.length()) {
                        char firstChar = code.charAt(valueStart);
                        if (firstChar != '"' && firstChar != '\'') {
                            int valueEnd = valueStart;
                            while (valueEnd < code.length()) {
                                char c = code.charAt(valueEnd);
                                if (c == ',' || c == ')' || c == ' ' || c == '\n' || c == '\t') break;
                                valueEnd++;
                            }
                            if (valueEnd > valueStart) {
                                return code.substring(valueStart, valueEnd).trim();
                            }
                        }
                    }
                }
                return "";
            }

            int valueStart = keyStart + key.length() + 2;
            if (valueStart >= code.length()) {
                return "";
            }
            StringBuilder value = new StringBuilder();
            boolean escaped = false;
            for (int i = valueStart; i < code.length(); i++) {
                char c = code.charAt(i);
                if (escaped) {
                    if (c == 'n') value.append('\n');
                    else if (c == 't') value.append('\t');
                    else if (c == 'r') value.append('\r');
                    else value.append(c);
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == quoteChar) {
                    return value.toString();
                } else {
                    value.append(c);
                }
            }
            return value.toString();
        } catch (Exception e) {
            log.warn("Failed to extract arg {} from code {}", key,
                    code.length() > 200 ? code.substring(0, 200) + "..." : code);
            return "";
        }
    }

    /**
     * 提取 run_python(code='...') 的多行代码参数（处理转义与结尾判定）。
     */
    String extractPythonCodeArg(String input) {
        if (input == null) {
            return "";
        }
        try {
            int codeStart = input.indexOf("run_python(code=");
            if (codeStart == -1) {
                codeStart = input.indexOf("run_python(code =");
            }
            if (codeStart == -1) {
                // run_python("...") 无 code= 前缀时退化为第一个引号串
                return extractStringArg(input, "code");
            }
            int quoteStart = input.indexOf('=', codeStart) + 1;
            while (quoteStart < input.length() && Character.isWhitespace(input.charAt(quoteStart))) {
                quoteStart++;
            }
            if (quoteStart >= input.length()) {
                return "";
            }
            char quoteChar = input.charAt(quoteStart);
            if (quoteChar != '\'' && quoteChar != '"') {
                return "";
            }
            int quoteEnd = quoteStart + 1;
            boolean escaped = false;
            while (quoteEnd < input.length()) {
                char c = input.charAt(quoteEnd);
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == quoteChar) {
                    int afterQuote = quoteEnd + 1;
                    while (afterQuote < input.length() && Character.isWhitespace(input.charAt(afterQuote))) {
                        afterQuote++;
                    }
                    if (afterQuote >= input.length() || input.charAt(afterQuote) == ')') {
                        break;
                    }
                }
                quoteEnd++;
            }
            if (quoteEnd >= input.length()) {
                return "";
            }
            String extracted = input.substring(quoteStart + 1, quoteEnd);
            extracted = extracted.replace("\\n", "\n");
            extracted = extracted.replace("\\t", "\t");
            extracted = extracted.replace("\\'", "'");
            extracted = extracted.replace("\\\"", "\"");
            extracted = extracted.replace("\\\\", "\\");
            return extracted;
        } catch (Exception e) {
            log.warn("Failed to extract Python code from: {}", input, e);
            return "";
        }
    }

    /**
     * 通用参数提取：method(k1="v1", k2=123, k3=true) → {"k1":"v1","k2":123,"k3":true}。
     * 用于未注册工具（插件懒加载前/幻觉调用）的兜底。
     */
    String extractArgsAsJson(String code) {
        try {
            int start = code.indexOf('(');
            int end = code.lastIndexOf(')');
            if (start == -1 || end == -1 || end <= start) {
                return "{}";
            }
            String argsStr = code.substring(start + 1, end).trim();
            if (argsStr.isEmpty()) {
                return "{}";
            }
            cn.hutool.json.JSONObject json = new cn.hutool.json.JSONObject();

            Pattern stringPattern = Pattern.compile("(\\w+)\\s*=\\s*\"([^\"]*)\"");
            Matcher stringMatcher = stringPattern.matcher(argsStr);
            while (stringMatcher.find()) {
                json.set(stringMatcher.group(1), stringMatcher.group(2));
            }

            Pattern unquotedPattern = Pattern.compile("(\\w+)\\s*=\\s*([^,\"\\)]+)");
            Matcher unquotedMatcher = unquotedPattern.matcher(argsStr);
            while (unquotedMatcher.find()) {
                String key = unquotedMatcher.group(1).trim();
                String value = unquotedMatcher.group(2).trim();
                if (json.containsKey(key)) {
                    continue;
                }
                if ("true".equalsIgnoreCase(value)) {
                    json.set(key, true);
                } else if ("false".equalsIgnoreCase(value)) {
                    json.set(key, false);
                } else {
                    try {
                        json.set(key, Integer.parseInt(value));
                    } catch (NumberFormatException e1) {
                        try {
                            json.set(key, Double.parseDouble(value));
                        } catch (NumberFormatException e2) {
                            json.set(key, value);
                        }
                    }
                }
            }
            return json.toString();
        } catch (Exception e) {
            log.warn("Failed to parse tool args from code: {}", code, e);
            return "{}";
        }
    }
}
