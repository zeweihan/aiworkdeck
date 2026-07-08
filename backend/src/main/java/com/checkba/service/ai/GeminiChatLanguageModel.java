package com.checkba.service.ai;

import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import dev.langchain4j.data.message.*;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.util.List;

/**
 * 基于 Google Gemini HTTP API 的 ChatLanguageModel 实现。
 *
 * 说明：
 * - 通过官方 REST 接口 /models/{model}:generateContent 调用
 * - 将 LangChain4j 的 ChatMessage 序列化为 Gemini 的 contents 结构
 */
public class GeminiChatLanguageModel implements ChatLanguageModel {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(GeminiChatLanguageModel.class);

    private final String apiBaseUrl;
    private final String modelName;
    private final String apiKey;
    private final Duration timeout;

    public GeminiChatLanguageModel(String apiBaseUrl, String modelName, String apiKey, Duration timeout) {
        this.apiBaseUrl = apiBaseUrl != null ? apiBaseUrl : "https://generativelanguage.googleapis.com/v1beta";
        this.modelName = modelName != null ? modelName : "gemini-1.5-pro";
        this.apiKey = apiKey;
        this.timeout = timeout != null ? timeout : Duration.ofSeconds(60);
    }

    @Override
    public Response<AiMessage> generate(List<ChatMessage> messages,
                                        List<dev.langchain4j.agent.tool.ToolSpecification> toolSpecifications) {
        // Gemini function-calling 尚未在本实现中支持：忽略工具规格、退化为纯文本生成，
        // 避免 langchain4j 默认实现直接抛 "Tools are not supported" 让 Gemini 子代理崩溃。
        if (toolSpecifications != null && !toolSpecifications.isEmpty()) {
            log.warn("GeminiChatLanguageModel 暂不支持工具调用，已忽略 {} 个工具规格，退化为纯文本生成", toolSpecifications.size());
        }
        return generate(messages);
    }

    @Override
    public Response<AiMessage> generate(List<ChatMessage> messages) {
        if (apiKey == null || apiKey.isEmpty()) {
            throw new IllegalStateException("Gemini API key is not configured (ai.model.gemini.api-key)");
        }

        try {
            String url = String.format("%s/models/%s:generateContent?key=%s",
                    apiBaseUrl, modelName, apiKey);

            JSONObject body = new JSONObject();
            JSONArray contents = new JSONArray();

            // 将 SYSTEM 消息合并为前缀，附加到下一条 USER 消息上，尽量保留指示信息
            StringBuilder systemPrefix = new StringBuilder();
            String cachedContentName = null;

            for (ChatMessage message : messages) {
                ChatMessageType type = message.type();
                
                if (type == ChatMessageType.SYSTEM) {
                    String text = message.text();
                    if (text != null) {
                        if (text.startsWith("GEMINI_CACHE_ID:")) {
                            cachedContentName = text.substring("GEMINI_CACHE_ID:".length()).trim();
                            continue; // Do not add to contents
                        }
                        
                        if (systemPrefix.length() > 0) {
                            systemPrefix.append("\n");
                        }
                        systemPrefix.append(text);
                    }
                    continue;
                }

                String role = (type == ChatMessageType.AI) ? "model" : "user";
                JSONObject contentObj = new JSONObject();
                contentObj.set("role", role);
                JSONArray parts = new JSONArray();

                if (message instanceof UserMessage) {
                    UserMessage userMsg = (UserMessage) message;
                     // Handle Multi-modal content
                    for (Content content : userMsg.contents()) {
                        if (content instanceof TextContent) {
                            JSONObject part = new JSONObject();
                            String text = ((TextContent) content).text();
                            
                            // Prepend system prompt to the FIRST text part of the FIRST user message (or effectively this logic)
                            // Here logic: if systemPrefix exists, prepend it to this text block
                            if (systemPrefix.length() > 0) {
                                text = systemPrefix + "\n\n" + text;
                                systemPrefix.setLength(0);
                            }
                            part.set("text", text);
                            parts.add(part);
                        } else if (content instanceof ImageContent) {
                            ImageContent img = (ImageContent) content;
                            JSONObject part = new JSONObject();
                            JSONObject inlineData = new JSONObject();
                            // ImageContent -> Image -> mimeType() / base64Data()
                            // Note: validation of image existence
                            if (img.image() != null) {
                                inlineData.set("mime_type", img.image().mimeType());
                                inlineData.set("data", img.image().base64Data());
                            }
                            part.set("inline_data", inlineData);
                            parts.add(part);
                        }
                    }
                } else {
                    // Fallback for AiMessage or simple text messages
                    String text = message.text();
                    if (text != null && !text.isEmpty()) {
                        JSONObject part = new JSONObject();
                         // Logic for system prefix fallback if it wasn't consumed by a UserMessage yet (rare but possible)
                         // Usually System is followed by User. If System -> AI, it's weird but let's just append.
                        if (systemPrefix.length() > 0 && type == ChatMessageType.USER) { 
                             // Should be caught by instanceof UserMessage usually, but safe fallback
                             text = systemPrefix + "\n\n" + text;
                             systemPrefix.setLength(0);
                        }
                        part.set("text", text);
                        parts.add(part);
                    }
                }

                if (!parts.isEmpty()) {
                    contentObj.set("parts", parts);
                    contents.add(contentObj);
                }
            }

            // 如果只有 SystemMessage (excluding cache id)，被上面逻辑吃掉了，这里做一次兜底
            if (contents.isEmpty() && systemPrefix.length() > 0) {
                JSONObject content = new JSONObject();
                content.set("role", "user");
                JSONArray parts = new JSONArray();
                JSONObject part = new JSONObject();
                part.set("text", systemPrefix.toString());
                parts.add(part);
                content.set("parts", parts);
                contents.add(content);
            }

            body.set("contents", contents);
            if (cachedContentName != null) {
                body.set("cachedContent", cachedContentName);
            }

            log.debug("Calling Gemini API: url={} body={}", url, body.toString());

            HttpResponse response = HttpRequest.post(url)
                    .header("Content-Type", "application/json")
                    .body(body.toString())
                    .timeout((int) timeout.toMillis())
                    .execute();

            String respBody = response.body();
            log.debug("Gemini raw response: {}", respBody);

            if (response.getStatus() / 100 != 2) {
                log.error("Gemini API error, status={} body={}", response.getStatus(), respBody);
                throw new RuntimeException("Gemini API error: " + response.getStatus() + " " + respBody);
            }

            JSONObject root = JSONUtil.parseObj(respBody);
            JSONArray candidates = root.getJSONArray("candidates");
            if (candidates == null || candidates.isEmpty()) {
                throw new RuntimeException("Gemini returned no candidates");
            }

            JSONObject first = candidates.getJSONObject(0);
            JSONObject content = first.getJSONObject("content");
            if (content == null) {
                throw new RuntimeException("Gemini response missing content");
            }

            JSONArray parts = content.getJSONArray("parts");
            if (parts == null || parts.isEmpty()) {
                throw new RuntimeException("Gemini response has empty parts");
            }

            StringBuilder textBuilder = new StringBuilder();
            for (int i = 0; i < parts.size(); i++) {
                JSONObject part = parts.getJSONObject(i);
                String t = part.getStr("text");
                if (t != null) {
                    textBuilder.append(t);
                }
            }

            String answer = textBuilder.toString().trim();
            return Response.from(AiMessage.aiMessage(answer));
        } catch (Exception e) {
            log.error("Error calling Gemini API", e);
            throw new RuntimeException("Error calling Gemini API: " + e.getMessage(), e);
        }
    }
}


