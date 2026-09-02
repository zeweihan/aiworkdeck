package com.checkba.service.ai;

import dev.langchain4j.model.output.Response;
import dev.langchain4j.data.message.AiMessage;

import java.util.UUID;

/**
 * 负责将 LLM 的流式回调转换为前端 SSE 协议事件。
 * 并收集最终完整的回复用于存储和计费。
 */
public class AgentStreamHandler implements ReasoningStreamingHandler {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(AgentStreamHandler.class);

    private final SseEmitterService sseEmitterService;
    private final String conversationId;
    private final TokenUsageService tokenUsageService;
    private final String projectId;
    private final Long userId;
    private final String modelId;
    // 调用方在本轮开始时记下的 SSE 连接代次，close() 收尾时原样带回
    // （见 SseEmitterService.close 的注释：防止误杀期间重连建立的新连接）
    private final long connectionEpoch;

    private final StringBuilder fullContentBuilder = new StringBuilder();
    private boolean isBubbleStarted = false;
    private String currentBubbleId;

    private final StringBuilder buffer = new StringBuilder();

    private static final int MAX_BUFFER_SIZE = 50; // Buffer for XML tag detection

    // ==================== 终态幂等 + 无活动看门狗（治"跑一半停了"F-01/F-03） ====================
    // 终态只允许进入一次：看门狗超时与真实回调可能竞争，double-terminal 会重复关流/重复清理
    private final java.util.concurrent.atomic.AtomicBoolean terminated =
            new java.util.concurrent.atomic.AtomicBoolean(false);
    // 是否已有 token 流出（重试决策依据：零 token 的失败轮可安全重放，不会给用户看重复内容）
    private volatile boolean streamedAnyToken = false;
    // 是否已有思考增量流出（思考型模型）。刻意与 streamedAnyToken 分开：
    //  - 看门狗选时限时两者任一为真都算「流已开始」，改用停滞时限（思考几分钟是正常的）；
    //  - 编排器判「可安全重放」仍只看 streamedAnyToken——思考文本重放一遍用户只是再看一次
    //    思考卡，正文重放才会出现重复内容。
    private volatile boolean streamedAnyReasoning = false;
    // 最近一次流活动时间（onNext / onReasoning / onKeepAlive 刷新），看门狗据此判定"流停滞"
    private volatile long lastActivityNanos = System.nanoTime();
    private volatile java.util.concurrent.ScheduledFuture<?> watchdogFuture;

    // 守护线程调度器：进程退出不被它拖住；全局单线程足够（只做轻量检查）
    private static final java.util.concurrent.ScheduledExecutorService WATCHDOG =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "llm-stream-watchdog");
                t.setDaemon(true);
                return t;
            });

    public boolean hasStreamedTokens() {
        return streamedAnyToken;
    }

    /**
     * 启动流式看门狗：无任何 token 到达即主动以超时错误终止本轮
     * （走 onError 路径，可被编排器的瞬时错误重试接住）。
     * 背景：langchain4j 0.36 的单一 timeout 是整通调用墙钟上限；把它调大之后，
     * "流悄悄断了但既不 onComplete 也不 onError"的场景需要这层兜底，否则会话永久 RUNNING。
     *
     * <p><b>两条时限是分开的，别合成一个值</b>：
     * <ul>
     *   <li>{@code firstTokenSeconds} —— 一个 token 都还没到过。流式端点上零字节静默这么久基本等于已经死了，
     *       没必要陪着等满整个停滞时限（用户体感就是"点了发送什么都没有"）。这条只在
     *       {@link #streamedAnyToken} 为 false 时生效，而这恰好就是编排器判定"可安全重放"的条件，
     *       所以误杀的代价上限是白跑一轮，不会让用户看到重复或半截内容。</li>
     *   <li>{@code inactivitySeconds} —— 已经在吐了但中途断供。这条必须给足：模型生成长工具参数时
     *       中间静默几十秒是正常的，砍短了会误杀正常轮次。</li>
     * </ul>
     */
    public void armInactivityWatchdog(int firstTokenSeconds, int inactivitySeconds) {
        lastActivityNanos = System.nanoTime();
        watchdogFuture = WATCHDOG.scheduleWithFixedDelay(() -> {
            if (terminated.get()) return;
            long idleSec = (System.nanoTime() - lastActivityNanos) / 1_000_000_000L;
            boolean started = streamedAnyToken || streamedAnyReasoning;
            int limitSec = started ? inactivitySeconds : firstTokenSeconds;
            if (idleSec >= limitSec) {
                log.warn("Stream {} for {}s (limit {}s) for {}, terminating round via watchdog",
                        started ? "stalled" : "produced no token", idleSec, limitSec, conversationId);
                onError(new java.util.concurrent.TimeoutException(started
                        ? com.checkba.service.LangText.of(
                                "流式响应停滞超过 " + limitSec + " 秒",
                                "Streaming response stalled for more than " + limitSec + " seconds")
                        : com.checkba.service.LangText.of(
                                "模型 " + limitSec + " 秒内没有返回任何内容",
                                "Model returned nothing within " + limitSec + " seconds")));
            }
        }, 5, 5, java.util.concurrent.TimeUnit.SECONDS);
    }

    private void cancelWatchdog() {
        java.util.concurrent.ScheduledFuture<?> f = watchdogFuture;
        if (f != null) f.cancel(false);
    }

    public AgentStreamHandler(SseEmitterService sseEmitterService, String conversationId, TokenUsageService tokenUsageService, String projectId, Long userId, String modelId, long connectionEpoch) {
        this.sseEmitterService = sseEmitterService;
        this.conversationId = conversationId;
        this.tokenUsageService = tokenUsageService;
        this.projectId = projectId;
        this.userId = userId;
        this.modelId = modelId;
        this.connectionEpoch = connectionEpoch;
    }

    // Callback for each token generated (for real-time tracking)
    private java.util.function.Consumer<String> onToken;
    private java.util.function.Consumer<String> onEditorStream;

    public void setOnToken(java.util.function.Consumer<String> onToken) {
        this.onToken = onToken;
    }

    public void setOnEditorStream(java.util.function.Consumer<String> onEditorStream) {
        this.onEditorStream = onEditorStream;
    }

    @Override
    public void onNext(String token) {
        log.trace("Token for {}: [{}]", conversationId, token);
        // 终态后到达的迟到 token 丢弃（看门狗已终止本轮时，底层流可能还在吐）
        if (terminated.get()) return;
        lastActivityNanos = System.nanoTime();
        if (token != null && !token.isEmpty()) streamedAnyToken = true;
        if (token != null) {
            // Notify token callback for real-time state tracking
            if (onToken != null) {
                onToken.accept(token);
            }
            
            // Process for editor filtered stream
            processEditorStream(token);
            
            processBuffer(token);
        }
    }
    
    /**
     * 思考增量（dev-board#364）：原样转发成 SSE {@code reasoning_delta}，前端实时渲染进思考卡。
     *
     * <p>刻意不进 {@link #fullContentBuilder}、不进编辑器流、不过标签解析：思考文本不是模型正文，
     * 不落库、不回喂模型（契约 D：模型只看 content），也不该被写进文档。
     */
    @Override
    public void onReasoning(String reasoningDelta) {
        if (terminated.get() || reasoningDelta == null || reasoningDelta.isEmpty()) return;
        lastActivityNanos = System.nanoTime();
        streamedAnyReasoning = true;
        sseEmitterService.send(conversationId, "reasoning_delta",
                "{\"content\":\"" + escapeJson(reasoningDelta) + "\"}");
    }

    /** 传输层保活注释：只刷新看门狗，不产生任何事件。 */
    @Override
    public void onKeepAlive() {
        if (terminated.get()) return;
        lastActivityNanos = System.nanoTime();
    }

    public boolean hasStreamedReasoning() {
        return streamedAnyReasoning;
    }

    // ==================== Editor Stream Filtering Logic（过滤后实时写入编辑器文档；SSE 事件名双轨 doc_stream_data/wps_stream_data，见 AgentOrchestrator） ====================
    
    // Buffer for editor stream parser to handle split tags
    private final StringBuilder editorStreamBuffer = new StringBuilder();
    // Set of tags that should be hidden from the editor stream (but content might be hidden too?)
    // Protocol:
    // <thinking>...</thinking> -> Hide ALL
    // <process>...</process> -> Hide ALL
    // <tool_code>...</tool_code> -> Hide ALL
    // <tool_output>...</tool_output> -> Hide ALL
    // <question>...</question> -> Hide ALL
    // <walkthrough>...</walkthrough> -> Hide ALL
    // <title>...</title> -> Hide ALL
    // <artifact>...</artifact> -> Hide ALL
    // <final>Content</final> -> Hide TAGS, Show CONTENT
    
    // We maintain a stack of open hidden tags.
    // If stack is empty, we are in "Display Mode" (mostly). 
    // But we still need to strip <final> and </final> tags themselves.
    
    private boolean isInsideHiddenTag = false;
    private String currentHiddenTagName = null;
    
    // Tags that should hide their content completely
    private static final java.util.Set<String> HIDDEN_CONTENT_TAGS = java.util.Set.of(
        "thinking", "process", "tool_code", "tool_output", 
        "question", "walkthrough", "title", "artifact",
        "bubble_type" // Also hide bubble control tags
    );
    
    private void processEditorStream(String token) {
        if (onEditorStream == null) return;
        
        editorStreamBuffer.append(token);
        
        while (editorStreamBuffer.length() > 0) {
            // If we are NOT inside a hidden tag, we look for start of ANY tag
            if (!isInsideHiddenTag) {
                int ltIndex = editorStreamBuffer.indexOf("<");
                if (ltIndex == -1) {
                    // No tags in buffer, safe to emit all
                    String text = editorStreamBuffer.toString();
                    emitEditorText(text);
                    editorStreamBuffer.setLength(0);
                    return;
                } else {
                    // Valid text before the tag
                    if (ltIndex > 0) {
                        emitEditorText(editorStreamBuffer.substring(0, ltIndex));
                        editorStreamBuffer.delete(0, ltIndex);
                        // Now buffer starts with '<'
                    }
                    
                    // Check if we have enough chars to identify the tag
                    // Need at least "<x" or "</x"
                    if (editorStreamBuffer.length() < 2) {
                        return; // Wait for more data
                    }
                    
                    // Determine if it's a start tag or end tag
                    boolean isEndTag = editorStreamBuffer.charAt(1) == '/';
                    
                    // Try to find the closing '>'
                    int gtIndex = editorStreamBuffer.indexOf(">");
                    if (gtIndex == -1) {
                        // Tag not fully received yet
                        // Safety cap: if buffer gets too huge without '>', force flush?
                         if (editorStreamBuffer.length() > 1000) {
                             // Something wrong, just flush to avoid memory issues, though it might break protocol.
                             // But for the editor stream, better to show garbage than crash.
                             emitEditorText(editorStreamBuffer.toString());
                             editorStreamBuffer.setLength(0);
                         }
                        return; // Wait for more data
                    }
                    
                    // We have a full tag: <...>
                    String fullTag = editorStreamBuffer.substring(0, gtIndex + 1);
                    String tagName = extractTagName(fullTag);
                    
                    if (HIDDEN_CONTENT_TAGS.contains(tagName)) {
                        if (!isEndTag) {
                            // Start of a hidden block
                            isInsideHiddenTag = true;
                            currentHiddenTagName = tagName;
                        }
                        // If it's an end tag of a hidden block, typically we shouldn't see it if we are not inside one?
                        // Unless it's unbalanced. Just ignore/strip it.
                    } else if ("final".equals(tagName)) {
                        // <final> or </final> -> Just strip the tag, content is allowed
                    } else {
                        // Unknown tag (maybe <b> or markdown formatting?)
                        // If it's not a protocol tag, we might want to keep it?
                        // But System Prompt says "Output RAW XML tags directly". 
                        // It usually means specific control tags. 
                        // Ideally, we should strip ALL xml-like tags that look like protocol, 
                        // but keep things that might be part of the document (though Markdown doc shouldn't have HTML).
                        // Safety: Strip it if it looks like our protocol tags. 
                        // Let's rely on HIDDEN_CONTENT_TAGS + final.
                        // If it's truly unknown (e.g. <br>), let's pass it through?
                        // Actually, for a .docx, raw HTML tags might appear as text.
                        // Let's pass unknown tags through as text.
                        if (!"final".equals(tagName) && !HIDDEN_CONTENT_TAGS.contains(tagName)) {
                            emitEditorText(fullTag); 
                        }
                    }
                    
                    // Remove the processed tag from buffer
                    editorStreamBuffer.delete(0, gtIndex + 1);
                }
            } else {
                // Inside Hidden Tag -> Look for the specific closing tag </tagName>
                // OR self-closing />? (Protocol uses full tags mostly, except bubble_type/artifact sometimes?)
                // Assuming standard </name>
                
                String closeTag = "</" + currentHiddenTagName + ">";
                int closeIndex = editorStreamBuffer.indexOf(closeTag);
                
                if (closeIndex == -1) {
                    // Check for self-closing if strictly required? 
                    // <bubble_type ... />
                    if ("bubble_type".equals(currentHiddenTagName)) {
                         int selfClose = editorStreamBuffer.indexOf("/>");
                         if (selfClose != -1) {
                             editorStreamBuffer.delete(0, selfClose + 2);
                             isInsideHiddenTag = false;
                             currentHiddenTagName = null;
                             return;
                         }
                    }
                    
                    // Not found, discard all buffer content (it's hidden!)
                    // BUT be careful about partial tags at the end.
                    // We can safely discard everything UP TO the last '<' to be safe?
                    // Or just keep a small window?
                    // To be safe: discard everything except the last few chars that might start the closing tag.
                    if (editorStreamBuffer.length() > closeTag.length() * 2) {
                        editorStreamBuffer.delete(0, editorStreamBuffer.length() - closeTag.length());
                    }
                    return; // Wait for more data
                } else {
                    // Found closing tag!
                    // Discard everything up to and including the closing tag
                    editorStreamBuffer.delete(0, closeIndex + closeTag.length());
                    isInsideHiddenTag = false;
                    currentHiddenTagName = null;
                }
            }
        }
    }
    
    private String extractTagName(String tag) {
        // Remove <, </, > and attributes
        String content = tag.startsWith("</") ? tag.substring(2) : tag.substring(1);
        if (content.endsWith(">")) content = content.substring(0, content.length() - 1);
        
        // Handle <name attr="..."> or <name>
        int spaceIdx = content.indexOf(' ');
        if (spaceIdx != -1) {
            return content.substring(0, spaceIdx);
        }
        return content;
    }

    private void emitEditorText(String text) {
        if (onEditorStream != null && text != null && !text.isEmpty()) {
            onEditorStream.accept(text);
        }
    }
    
    private void processBuffer(String token) {
        // Accumulate full response for final saving
        fullContentBuilder.append(token);
        
        buffer.append(token);
        
        String content = buffer.toString();
        
        // 1. Check for complete <bubble_type ... /> tag
        if (content.contains("<bubble_type")) {
             int start = content.indexOf("<bubble_type");
             int end = content.indexOf("/>", start);
             
             if (end != -1) {
                  // Captured full tag
                  String tag = content.substring(start, end + 2);
                  
                  // Extract mode
                  String mode = "chat"; 
                  if (tag.contains("mode=\"execution\"")) mode = "execution";
                  else if (tag.contains("mode=\"plan\"")) mode = "plan";
                  else if (tag.contains("mode=\"chat\"")) mode = "chat";
                  
                  if (!isBubbleStarted) {
                      startBubble(mode);
                  }
                  
                  // Flush content BEFORE the tag if any
                  if (start > 0) {
                      emitText(content.substring(0, start));
                  }
                  
                  // Remove tag from buffer
                  buffer.delete(0, end + 2);
                  
                  // Flush remainder immediately
                  if (buffer.length() > 0) {
                      emitText(buffer.toString());
                      buffer.setLength(0);
                  }
                  return;
             }
        }
        
        // 2. Check for complete <artifact ...>...</artifact> tag
        // We need to support content inside artifact, so it has start and end tag
        if (content.contains("<artifact") && content.contains("</artifact>")) {
             int start = content.indexOf("<artifact");
             int end = content.indexOf("</artifact>", start);
             
             if (end != -1) {
                  // Captured full artifact block
                  int endTagLen = 11; // </artifact>
                  String rawArtifact = content.substring(start, end + endTagLen);
                  
                  // Parse type
                  // <artifact type="task_list">...</artifact>
                  String type = "task_list"; // default
                  int typeStart = rawArtifact.indexOf("type=\"");
                  if (typeStart != -1) {
                      int typeEnd = rawArtifact.indexOf("\"", typeStart + 6);
                      if (typeEnd != -1) {
                          type = rawArtifact.substring(typeStart + 6, typeEnd);
                      }
                  }
                  
                  // Extract content
                  // Find end of opening tag
                  int openTagEnd = rawArtifact.indexOf(">");
                  String innerContent = "";
                  if (openTagEnd != -1) {
                       innerContent = rawArtifact.substring(openTagEnd + 1, rawArtifact.length() - endTagLen);
                  }
                  
                  // Emit Artifact Event
                  // We treat this as a "create" operation
                  String artifactId = UUID.randomUUID().toString();
                  // Clean content a bit? keep newlines
                  String jsonContent = escapeJson(innerContent);
                  
                  // Spec v1.7 Artifact Event Structure
                  String artifactEvent = String.format(
                      "{\"operation\":\"create\", \"id\":\"%s\", \"type\":\"%s\", \"status\":\"draft\", \"data\":{\"content\":\"%s\"}}",
                      artifactId, type, jsonContent
                  );
                  sseEmitterService.send(conversationId, "artifact", artifactEvent);
                  
                  // Flush text before artifact
                  if (start > 0) emitText(content.substring(0, start));
                  
                  // Remove artifact from buffer
                  buffer.delete(0, end + endTagLen);
                  
                  // Flush remainder
                  if (buffer.length() > 0) {
                      emitText(buffer.toString());
                      buffer.setLength(0);
                  }
                  return;
             }
        }

        // 3. Check for potential start of ANY tag (<)
        int firstLT = buffer.indexOf("<");
        
        if (firstLT == -1) {
            // No tag start present. Safe to flush EVERYTHING.
            if (!isBubbleStarted) startBubble("chat");
            emitText(buffer.toString());
            buffer.setLength(0);
            return;
        }
        
        // 4. Flush text before tag
        if (firstLT > 0) {
            if (!isBubbleStarted) startBubble("chat");
            emitText(buffer.substring(0, firstLT));
            buffer.delete(0, firstLT);
            // Buffer now starts with '<'
        }
        
        // 5. Buffer starts with <. Check if it matches known prefixes.
        String currentBuffer = buffer.toString();
        boolean potentialMatch = false;
        
        // Check alignment with <bubble_type
        if (checkPrefix(currentBuffer, "<bubble_type")) potentialMatch = true;
        // Check alignment with <artifact
        else if (checkPrefix(currentBuffer, "<artifact")) potentialMatch = true;
        // Check alignment with </artifact (if split across chunks)
        else if (checkPrefix(currentBuffer, "</artifact")) potentialMatch = true;
        
        if (!potentialMatch) {
            // Not a control tag, just text (e.g. <div>)
            if (!isBubbleStarted) startBubble("chat");
            emitText(currentBuffer);
            buffer.setLength(0);
            return;
        }

        // 6. It matches a prefix. Wait for more data?
        // If buffer is too huge, we might have to flush even if incomplete to avoid OOM or hang
        // Artifacts can be large, so we might need a larger buffer OR a streaming state machine for artifacts.
        // For now, let's bump buffer size just for artifacts or assume they fit in memory? 
        // Actually, if artifact is huge, waiting for </artifact> in a single String buffer is risky.
        // But user asked for "show TDlist", usually small. 
        // Let's cap at larger limit (e.g. 2KB) or implement streaming artifact?
        // For MVP, lets just bump header buffer detection to 50, but we allow buffer to grow for artifact?
        // Risky. But let's keep it simple for now as requested.
        
        if (buffer.length() > 2000) {
             // Too large, probably not a control tag or we can't buffer it all. Flush.
             if (!isBubbleStarted) startBubble("chat");
             emitText(buffer.toString());
             // We lost the parsing capability for this large chunk, but avoided crash.
             buffer.setLength(0); 
        }
    }
    
    private boolean checkPrefix(String buffer, String prefix) {
        int len = Math.min(buffer.length(), prefix.length());
        return buffer.substring(0, len).equals(prefix.substring(0, len));
    }

    private void emitText(String text) {
        if (text == null || text.isEmpty()) return;
        sseEmitterService.send(conversationId, "text_delta", "{\"content\":\"" + escapeJson(text) + "\"}");
    }

    @Override
    public void onComplete(Response<AiMessage> response) {
        // 终态幂等：看门狗可能已抢先终止本轮
        if (!terminated.compareAndSet(false, true)) return;
        cancelWatchdog();
        // Flush remaining buffer
        if (buffer.length() > 0) {
            emitText(buffer.toString());
        }
        
        // Flush remaining editor stream buffer
        if (editorStreamBuffer.length() > 0) {
            // If we are left with something in buffer, it might be incomplete tag or content
            // Emit it if not inside hidden tag
            if (!isInsideHiddenTag) {
                emitEditorText(editorStreamBuffer.toString());
            }
        }
        
        // Emit Token Usage to Frontend
        if (response.tokenUsage() != null) {
            dev.langchain4j.model.output.TokenUsage usage = response.tokenUsage();
            int promptTokens = usage.inputTokenCount() != null ? usage.inputTokenCount() : 0;
            int completionTokens = usage.outputTokenCount() != null ? usage.outputTokenCount() : 0;
            int totalTokens = usage.totalTokenCount() != null ? usage.totalTokenCount() : (promptTokens + completionTokens);
            
            String usageJson = String.format(
                "{\"promptTokens\":%d,\"completionTokens\":%d,\"totalTokens\":%d}",
                promptTokens, completionTokens, totalTokens
            );
            sseEmitterService.send(conversationId, "token_usage", usageJson);
        }
        
        // Record Usage
        if (response.tokenUsage() != null) {
            tokenUsageService.recordUsage(
                Long.parseLong(projectId), userId, modelId, response.tokenUsage(), conversationId
            );
        }
        
        // Callback if supplied (for Loop)
        // 注意：如果有回调，说明可能还有后续循环（工具调用），不要在这里发送 bubble_end
        // bubble_end 应该在整个循环真正结束时由 AgentOrchestrator 发送
        if (onCompleteCallback != null) {
            log.info("Response completed for {}. Full content:\n{}", conversationId, fullContentBuilder.toString());
            onCompleteCallback.accept(response);
        } else {
            // 没有回调，说明是简单的单次响应，发送 bubble_end
            sseEmitterService.send(conversationId, "bubble_end", "{\"status\":\"finished\"}");
        }
    }
    
    // Callback Interface
    private java.util.function.Consumer<Response<AiMessage>> onCompleteCallback;
    public void setOnComplete(java.util.function.Consumer<Response<AiMessage>> callback) {
        this.onCompleteCallback = callback;
    }

    // 错误回调（由 AgentOrchestrator 注入，用于在流式出错时清理编排器状态并关闭 emitter）
    private java.util.function.Consumer<Throwable> onErrorCallback;
    public void setOnError(java.util.function.Consumer<Throwable> callback) {
        this.onErrorCallback = callback;
    }

    @Override
    public void onError(Throwable error) {
        // 终态幂等：真实回调与看门狗超时可能竞争，只允许第一个进入
        if (!terminated.compareAndSet(false, true)) return;
        cancelWatchdog();
        log.error("Stream error for {}", conversationId, error);
        // 有编排器回调时把错误处置完全交给它：瞬时错误可能走自动重试，
        // 此时不能先发 error 事件（前端会渲染"执行中断"），是否发由回调决定。
        if (onErrorCallback != null) {
            onErrorCallback.accept(error);
        } else {
            // 无回调（单次响应）：保持旧行为——发 error 并关流，
            // 否则 SSE 连接会挂到 30 分钟超时、前端永久显示加载态。
            sseEmitterService.send(conversationId, "error", "Stream Error: " + error.getMessage());
            sseEmitterService.close(conversationId, connectionEpoch);
        }
    }
    
    private void startBubble(String type) {
        this.isBubbleStarted = true;
        this.currentBubbleId = UUID.randomUUID().toString();
        // Send bubble_start
        sseEmitterService.send(conversationId, "bubble_start", "{\"bubbleId\":\"" + currentBubbleId + "\", \"type\":\"" + type + "\"}");
    }
    
    /**
     * SSE 载荷是手工拼的 JSON 串，这里必须把 JSON 规范要求的字符全转义掉。
     *
     * <p>此前只处理了 {@code \ " \n \r}：模型正文里出现一个真制表符（写 Makefile /
     * Go / 缩进代码块时是常态）就会拼出非法 JSON，前端 text_delta 解析失败后回落成
     * 「把整段 {"content":"..."} 信封当正文渲染」，artifact 事件则被整条丢弃。
     * U+0000..U+001F 全区间都要转义，规范如此。
     */
    static String escapeJson(String raw) {
        if (raw == null) return "";
        StringBuilder sb = new StringBuilder(raw.length() + 16);
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '"' -> sb.append("\\\"");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }
}
