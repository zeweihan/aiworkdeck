package com.checkba.service.ai.mcp;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * MCP 响应解析器：把远端返回的三种格式统一解析为给 LLM 消费的文本。
 *
 * 支持的格式（均为 PKULaw 网关线上实际出现过的形态）：
 * 1. SSE / StreamableHTTP：以 "data:" 开头的事件流，data 内容可能是 JSON 或纯文本
 * 2. JSON 数组：直接返回结果列表
 * 3. JSON 对象：
 *    - 业务网关封装 { "code": "ok", "data": ... }
 *    - 标准 JSON-RPC { "result": { "content": [{ "type": "text", "text": ... }] } }
 *    - JSON-RPC 错误 { "error": ... }
 */
public final class McpResponseParser {

    private static final Logger log = LoggerFactory.getLogger(McpResponseParser.class);

    private McpResponseParser() {
    }

    public static String parse(String responseBody) {
        String trimmedResponse = responseBody.trim();

        // Case 1: SSE / StreamableHTTP
        //
        // 判据是「正文里有 data: 行」，不是「第一行以 data: 开头」——真实的
        // StreamableHTTP 流第一行往往是 `event: message`，旧判据认不出来，
        // 整段带 event:/空行的协议原文就顺着 Case 4 交给了模型。
        if (looksLikeSse(trimmedResponse)) {
            log.debug("Detected SSE response format");
            String payload = pickSsePayload(responseBody);
            if (payload.startsWith("{") || payload.startsWith("[")) {
                trimmedResponse = payload;
            } else {
                return payload;
            }
        }

        // Case 2: JSON Array [...]
        if (trimmedResponse.startsWith("[")) {
            log.debug("Detected JSON Array response format");
            JSONArray jsonArray = JSONUtil.parseArray(trimmedResponse);
            return jsonArray.toStringPretty();
        }

        // Case 3: JSON Object {...}
        if (trimmedResponse.startsWith("{")) {
            log.debug("Detected JSON Object response format");
            JSONObject jsonResponse = JSONUtil.parseObj(trimmedResponse);

            // Sub-case 3a: 业务网关封装 { "code": "ok", "data": ... }
            if (jsonResponse.containsKey("code") && "ok".equalsIgnoreCase(jsonResponse.getStr("code"))) {
                if (jsonResponse.containsKey("data")) {
                    Object data = jsonResponse.get("data");
                    return (data instanceof cn.hutool.json.JSON)
                            ? ((cn.hutool.json.JSON) data).toStringPretty() : data.toString();
                }
            }

            // Sub-case 3b: 标准 MCP JSON-RPC 响应 (result/content)
            if (jsonResponse.containsKey("result")) {
                JSONObject result = jsonResponse.getJSONObject("result");
                if (result.containsKey("content")) {
                    JSONArray content = result.getJSONArray("content");
                    StringBuilder sb = new StringBuilder();
                    for (Object item : content) {
                        if (item instanceof JSONObject) {
                            JSONObject itemJson = (JSONObject) item;
                            if ("text".equals(itemJson.getStr("type"))) {
                                sb.append(itemJson.getStr("text"));
                            }
                        }
                    }
                    return sb.toString();
                }
                return result.toString();
            }

            // Sub-case 3c: JSON-RPC 错误
            if (jsonResponse.containsKey("error")) {
                return "Error from MCP server: " + jsonResponse.get("error");
            }

            // Fallback: 整个对象美化输出
            return jsonResponse.toStringPretty();
        }

        // Case 4: Unknown format
        log.warn("Unknown response format: {}", trimmedResponse);
        return trimmedResponse;
    }

    /** 正文里出现任意一行 data:（SSE 事件流的唯一必需字段）即按 SSE 处理。 */
    private static boolean looksLikeSse(String body) {
        for (String line : body.split("\n")) {
            if (line.trim().startsWith("data:")) {
                return true;
            }
        }
        return false;
    }

    /**
     * 从 SSE 事件流里挑出真正要交给模型的那一份 data。
     *
     * <p>旧实现把所有 data: 行**无分隔符地拼在一起**。一条正常的 MCP 流是
     * 「若干条 notifications/progress + 一条真正的 result」，拼完就是
     * {@code {...progress...}{...result...}} 这种非法 JSON；而 hutool 的
     * {@code JSONUtil.parseObj} 对它**不报错**，只解析第一个对象、丢掉其余
     * （已实测：{@code parseObj("{\"a\":1}{\"b\":2}")} 返回 {@code {"a":1}}）。
     * 于是模型收到的是一条进度通知，正文一个字都没有，且全程零错误信号——
     * 表现就是「查法条没返回」。
     *
     * <p>现在按 SSE 规范分帧：空行分事件，事件内多条 data: 用 \n 拼（规范如此，
     * 也是多行 JSON 载荷唯一正确的拼法）。然后优先挑出 JSON-RPC 响应
     * （带 result 或 error 的那条），挑不到才退回「最后一条非空事件」，
     * 再退回旧的全量拼接以保住既有的纯文本用法。
     */
    private static String pickSsePayload(String responseBody) {
        java.util.List<String> events = new java.util.ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String rawLine : responseBody.split("\n")) {
            String line = rawLine.trim();
            if (line.isEmpty()) {
                if (current.length() > 0) {
                    events.add(current.toString());
                    current.setLength(0);
                }
                continue;
            }
            if (!line.startsWith("data:")) {
                continue; // event: / id: / retry: / 注释行都不是载荷
            }
            String dataContent = line.substring(5).trim();
            if (dataContent.isEmpty() || "[DONE]".equals(dataContent)) {
                continue;
            }
            if (current.length() > 0) {
                current.append('\n'); // 同一事件的多条 data 行，规范要求用换行拼
            }
            current.append(dataContent);
        }
        if (current.length() > 0) {
            events.add(current.toString());
        }
        if (events.isEmpty()) {
            return "";
        }

        // 优先：真正的 JSON-RPC 响应（有 result 或 error），跳过 notifications/* 之类的中间事件
        for (int i = events.size() - 1; i >= 0; i--) {
            String e = events.get(i);
            if (!e.startsWith("{")) {
                continue;
            }
            try {
                JSONObject obj = JSONUtil.parseObj(e);
                if (obj.containsKey("result") || obj.containsKey("error")
                        || (obj.containsKey("code") && obj.containsKey("data"))) {
                    return e;
                }
            } catch (Exception ignore) {
                // 不是合法 JSON 就不算候选，继续往前找
            }
        }

        // 退路一：最后一条事件（单事件流与纯文本流都落在这里，行为与改造前一致）
        String last = events.get(events.size() - 1);
        if (events.size() == 1) {
            return last;
        }
        // 退路二：多事件但没有一条像响应——保住旧的全量拼接语义（纯文本分片场景）
        boolean anyJson = events.stream().anyMatch(e -> e.startsWith("{") || e.startsWith("["));
        return anyJson ? last : String.join("", events);
    }
}
