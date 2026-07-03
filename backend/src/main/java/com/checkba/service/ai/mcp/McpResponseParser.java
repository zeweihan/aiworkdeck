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

        // Case 1: SSE / StreamableHTTP (starts with "data:")
        if (trimmedResponse.startsWith("data:")) {
            log.debug("Detected SSE response format");
            StringBuilder fullContent = new StringBuilder();
            String[] lines = responseBody.split("\n");
            for (String line : lines) {
                line = line.trim();
                if (line.startsWith("data:")) {
                    String dataContent = line.substring(5).trim();
                    if (!dataContent.isEmpty() && !"[DONE]".equals(dataContent)) {
                        fullContent.append(dataContent);
                    }
                }
            }
            // 聚合后的 data 内容若是 JSON，继续走下方 JSON 解析；否则原样返回
            String accumulated = fullContent.toString();
            if (accumulated.startsWith("{") || accumulated.startsWith("[")) {
                trimmedResponse = accumulated;
            } else {
                return accumulated;
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
}
