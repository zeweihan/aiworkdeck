package com.checkba.service.ai;

import com.checkba.controller.ai.AiAgentController;
import com.checkba.model.ai.AgentMode;
import com.checkba.model.entity.ProjectAiMessage;
import com.checkba.service.ProjectAiMessageService;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import dev.langchain4j.agent.tool.ToolSpecification;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;

/**
 * Agent 核心编排器（编排层）。
 * 只负责循环控制与流程编排：
 * 1. 组装上下文（委托 ContextAssemblerService）
 * 2. 调用 LLM（委托 ChatModelFactory）
 * 3. 处理流式响应（委托 AgentStreamHandler）
 * 4. 分发工具（委托 ToolRegistry / XmlToolCallParser——编排器不感知任何具体工具）
 * 5. 维护循环、取消与增量持久化
 * 6. 循环结束后触发记忆写入管线（委托 MemoryPipelineService）
 */
@Service
@RequiredArgsConstructor
public class AgentOrchestrator {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(AgentOrchestrator.class);

    // 取消状态管理：存储被取消的会话ID
    private final Set<String> cancelledConversations = ConcurrentHashMap.newKeySet();
    // 存储当前活跃会话的已生成内容（用于取消时保存部分内容）
    private final Map<String, StringBuilder> activeStreamContent = new ConcurrentHashMap<>();

    private final ChatModelFactory chatModelFactory;
    private final ProjectAiMessageService messageService;
    private final SseEmitterService sseEmitterService;
    private final TokenUsageService tokenUsageService;
    private final ContextAssemblerService contextAssemblerService;
    private final ToolRegistry toolRegistry;
    private final XmlToolCallParser xmlToolCallParser;
    private final com.checkba.service.ai.memory.MemoryPipelineService memoryPipelineService;
    private final com.checkba.service.ProjectFileService projectFileService;
    private final WpsActionService wpsActionService;
    private final ConversationFileChangeService conversationFileChangeService;

    // ==================== 取消功能相关方法 ====================

    /**
     * 标记会话为已取消
     */
    public void setCancelled(String conversationId) {
        log.info("Cancelling conversation: {}", conversationId);
        cancelledConversations.add(conversationId);
    }

    /**
     * 检查会话是否被取消
     */
    public boolean isCancelled(String conversationId) {
        return cancelledConversations.contains(conversationId);
    }

    /**
     * 清理取消状态
     */
    private void clearCancelledState(String conversationId) {
        cancelledConversations.remove(conversationId);
        activeStreamContent.remove(conversationId);
    }

    /**
     * 处理取消：保存已生成的部分内容
     */
    private void handleCancellation(String conversationId, String projectId, Long userId) {
        log.info("Handling cancellation for conversation: {}", conversationId);
        
        // 获取已生成的部分内容
        StringBuilder contentBuilder = activeStreamContent.get(conversationId);
        String partialContent = contentBuilder != null ? contentBuilder.toString() : "";
        
        // 如果有部分内容，保存并标记为已中断
        if (!partialContent.isEmpty()) {
            String contentToSave = partialContent + "\n\n[已中断]";
            messageService.saveMessage(projectId, userId, conversationId, "ASSISTANT", contentToSave);
            log.info("Saved partial content ({} chars) for cancelled conversation: {}", partialContent.length(), conversationId);
        }
        
        // 发送取消事件
        sseEmitterService.send(conversationId, "cancelled", "{\"message\":\"用户已停止生成\"}");
        sseEmitterService.close(conversationId);
        
        // 清理状态
        clearCancelledState(conversationId);
    }
    
    /**
     * 获取指定会话的当前恢复快照 (用于断线重连)
     * 返回目前正在生成的流式内容
     */
    public String getRecoverySnapshot(String conversationId) {
        StringBuilder sb = activeStreamContent.get(conversationId);
        if (sb != null && sb.length() > 0) {
            return sb.toString();
        }
        return null;
    }

    // ==================== 工具分发（统一走 ToolRegistry，编排器不感知具体工具） ====================

    /**
     * 分发一次工具调用并处理声明式副作用（文件变更通知、文件树刷新）。
     */
    private ToolRegistry.ToolResult dispatchTool(String toolName, String argsJson,
                                                 Long projectId, String conversationId,
                                                 Long userId, String modelId) {
        com.checkba.service.ai.tools.ToolContext ctx =
                new com.checkba.service.ai.tools.ToolContext(projectId, conversationId, userId, modelId);
        ToolRegistry.ToolResult result = toolRegistry.execute(toolName, argsJson, ctx);
        applyToolSideEffects(result, argsJson, conversationId);
        return result;
    }

    /**
     * 根据 @ToolMeta 元数据处理工具副作用（取代原先散落在手写分发链里的硬编码通知）。
     */
    private void applyToolSideEffects(ToolRegistry.ToolResult result, String argsJson, String conversationId) {
        if (!result.success() || result.tool() == null || result.tool().meta() == null) {
            return;
        }
        com.checkba.service.ai.tools.ToolMeta meta = result.tool().meta();
        if (meta.refreshFiles()) {
            sseEmitterService.send(conversationId, "client_action", "{\"action\":\"refresh_files\"}");
        }
        if (!meta.fileEffect().isEmpty()) {
            String fileName = meta.fileArg().isEmpty() ? null : extractArg(argsJson, meta.fileArg());
            if (fileName == null || fileName.isEmpty()) {
                fileName = "Current Document";
            }
            notifyFileChange(conversationId, fileName, meta.fileEffect());
        }
    }


    /**
     * 处理用户消息 (入口)
     */
    @Async("taskExecutor") // Run in separate thread
    public void handleUserMessage(AiAgentController.AgentChatRequest request, Long userId) {
        String conversationId = request.getConversationId();
        String projectId = String.valueOf(request.getProjectId());
        AgentMode agentMode = request.getAgentMode(); // 获取 Agent 模式
        
        // 初始化取消状态和内容收集器
        cancelledConversations.remove(conversationId);
        activeStreamContent.put(conversationId, new StringBuilder());
        
        try {
            log.info("Agent Loop Started: conv={}, model={}, mode={}, msg={}", conversationId, request.getModel(), agentMode, request.getMessage());
            
            // 1. 保存用户消息 (Save only user message first; assistant saved after stream completes)
            messageService.saveMessage(
                projectId, userId, conversationId, "USER", request.getMessage()
            );
            
            // 1.1 首次对话时异步生成对话标题
            List<com.checkba.model.entity.ProjectAiMessage> existingMsgs = messageService.listByConversationId(conversationId);
            if (existingMsgs.size() <= 1) { // Only the user message we just saved
                final String convId = conversationId;
                final String userMsg = request.getMessage();
                CompletableFuture.runAsync(() -> {
                    try {
                        log.info("Generating conversation title for: {}", convId);
                        // Use a lightweight model for title generation
                        dev.langchain4j.model.chat.ChatLanguageModel titleModel = chatModelFactory.getChatModel("google/gemini-2.0-flash-exp:free");
                        String title = messageService.generateConversationTitle(userMsg, titleModel);
                        messageService.updateConversationTitle(convId, title);
                        log.info("Conversation title generated: {} -> {}", convId, title);
                        // Notify frontend of title update
                        sseEmitterService.send(convId, "title_update", "{\"title\":\"" + title.replace("\"", "\\\"").replace("\n", " ") + "\"}");
                    } catch (Exception e) {
                        log.warn("Failed to generate conversation title for {}", convId, e);
                    }
                });
            }
            
            // 2. Build Context & History Message Stack (Spec v1.8)
            log.info("Assembling full message context for conversation: {}", conversationId);
            // TODO: Get taskListId/planId from session if available
            String taskListId = null; 
            String planId = null;
            
            java.util.List<dev.langchain4j.data.message.ChatMessage> messages = contextAssemblerService.assemble(
                conversationId, 
                request.getMessage(), 
                request.getContextItems() != null ? request.getContextItems() : 
                    convertFileIdsToContextItems(request.getFileIds()),
                request.getActiveContext(), // NEW: Pass active document context
                taskListId,
                planId,
                projectId,
                agentMode,
                userId,
                request.getModel()
            );
            
            log.info("Message assembly complete. Total messages: {}", messages.size());
            log.debug("Detailed Message Stack:");
            for (dev.langchain4j.data.message.ChatMessage m : messages) {
                log.debug("  - Role: {}, Content length: {}", m.type(), m.text().length());
            }

            // 3. 获取流式模型
            log.info("Getting streaming model: {}", request.getModel());
            StreamingChatLanguageModel model = chatModelFactory.getStreamingChatModel(request.getModel());
            
            if (model == null) {
                throw new RuntimeException("Could not create streaming model for ID: " + request.getModel());
            }

            // 4. Start Loop
            log.info("Starting runLoop for conversation: {}, mode: {}", conversationId, agentMode);
            // Track tool executions for history persistence
            StringBuilder executionLog = new StringBuilder();
            runLoop(model, messages, conversationId, projectId, userId, request.getModel(), 0, executionLog, agentMode);
            
        } catch (Exception e) {
            log.error("Agent Loop Error for conversation: " + conversationId, e);
            sseEmitterService.send(conversationId, "error", "Internal Error: " + e.getMessage());
            sseEmitterService.close(conversationId);
        }
    }

    private void runLoop(StreamingChatLanguageModel model, 
                         java.util.List<dev.langchain4j.data.message.ChatMessage> messages, 
                         String conversationId, String projectId, Long userId, String modelId, int depth,
                         StringBuilder executionLog, AgentMode agentMode) {
        
        // 检查是否被取消
        if (isCancelled(conversationId)) {
            log.info("Conversation {} was cancelled, stopping loop at depth {}", conversationId, depth);
            handleCancellation(conversationId, projectId, userId);
            return;
        }
        
        if (depth > 10) {
            sseEmitterService.send(conversationId, "error", "Max recursion depth reached");
            sseEmitterService.close(conversationId);
            clearCancelledState(conversationId);
            return;
        }
        
        // Ask 模式限制递归深度为 1（不允许工具调用后的循环）
        if (agentMode == AgentMode.ASK && depth > 0) {
            log.info("Ask mode: stopping loop at depth {}", depth);
            sseEmitterService.send(conversationId, "bubble_end", "{}");
            sseEmitterService.close(conversationId);
            clearCancelledState(conversationId);
            return;
        }
        
        // 设置当前会话 ID 到 WpsActionService，以便 WPS 工具可以发送 SSE 事件
        wpsActionService.setCurrentConversationId(conversationId);

        AgentStreamHandler handler = new AgentStreamHandler(
            sseEmitterService, 
            conversationId, 
            tokenUsageService, 
            projectId, 
            userId, 
            modelId
        );
        

        // 实时更新当前生成的内容 (用于断线重连恢复)
        handler.setOnToken(token -> {
            StringBuilder sb = activeStreamContent.get(conversationId);
            if (sb != null) {
                sb.append(token);
            }
        });

        // WPS Real-time Streaming Interception (Filtered)
        handler.setOnWpsStream(token -> {
            if (wpsActionService.isStreamingMode(conversationId)) {
                sseEmitterService.send(conversationId, "wps_stream_data", java.util.Map.of("content", token));
            }
        });
        
        // Callback for Loop
        handler.setOnComplete(response -> {
          try {
            // Unconditionally turn off streaming mode when generation ends
            wpsActionService.setStreamingMode(conversationId, false);

            // 检查是否被取消
            if (isCancelled(conversationId)) {
                log.info("Conversation {} was cancelled during streaming", conversationId);
                handleCancellation(conversationId, projectId, userId);
                return;
            }
            
            // 确保在回调线程中也能访问 conversationId（解决 ThreadLocal 线程隔离问题）
            wpsActionService.setCurrentConversationId(conversationId);
            
            dev.langchain4j.data.message.AiMessage aiMessage = response.content();
            messages.add(aiMessage);
            
            // 更新已生成内容（用于取消时保存）
            String aiContent = aiMessage.text();
            if (aiContent != null) {
                StringBuilder contentBuilder = activeStreamContent.get(conversationId);
                if (contentBuilder != null) {
                    contentBuilder.append(aiContent);
                }
            }
            
            // 1. Check for Native Tool Requests (Priority 1)
            if (aiMessage.hasToolExecutionRequests()) {
                log.info("Detected Native Tool Requests: {}", aiMessage.toolExecutionRequests());
                sseEmitterService.send(conversationId, "step_update", "{\"status\":\"loading\", \"message\":\"Executing tools...\"}");

                // Execute Native Tools (统一分发，无需感知具体工具)
                for (dev.langchain4j.agent.tool.ToolExecutionRequest req : aiMessage.toolExecutionRequests()) {
                    ToolRegistry.ToolResult toolResult = dispatchTool(req.name(), req.arguments(),
                            Long.parseLong(projectId), conversationId, userId, modelId);
                    String result = toolResult.output();
                    messages.add(dev.langchain4j.data.message.ToolExecutionResultMessage.from(req, result));

                    // Determine status for history and display
                    String nativeToolStatus = toolResult.success() ? "SUCCESS" : "FAILURE";

                    // Log for history persistence (include status attribute)
                    executionLog.append(String.format("<process name=\"%s\"><tool_code>%s(%s)</tool_code><tool_output status=\"%s\">%s</tool_output></process>\n",
                        req.name(), req.name(), req.arguments(), nativeToolStatus, result));
                }

                sseEmitterService.send(conversationId, "step_update", "{\"status\":\"done\", \"message\":\"Tools executed.\"}");
                
                // 增量保存：在工具执行后立即保存AI消息和工具输出，防止对话中断导致上下文丢失
                String intermediateContent = (aiContent != null ? aiContent : "") + "\n" + executionLog.toString();
                messageService.saveMessage(projectId, userId, conversationId, "ASSISTANT", intermediateContent);
                log.info("Intermediate save after native tool execution for conversation: {}", conversationId);
                
                runLoop(model, messages, conversationId, projectId, userId, modelId, depth + 1, executionLog, agentMode);
                return;
            } 
            
            String content = aiMessage.text();
            if (content == null) content = "";

            // 2. Check for XML Tool Requests (Fallback for Root Bubble Protocol)
            // Pattern: <tool_code>legal_tools.method(args)</tool_code> OR <code>...</code>
            // We need to parse this manually because we forced XML output in System Prompt.
            if (xmlToolCallParser.containsToolCall(content)) {
                log.info("Detected XML Tool Code in content. Parsing...");

                // 提取LLM选择的process name，用于历史记录保存时保持一致性
                String llmProcessName = xmlToolCallParser.extractProcessName(content).orElse(null);

                boolean toolExecuted = false;

                for (XmlToolCallParser.ParsedCall call : xmlToolCallParser.parse(content)) {
                    String code = call.rawCode();
                    log.info("Parsed Tool Code: {}", code);

                    ToolRegistry.ToolResult toolResult = dispatchTool(call.toolName(), call.argsJson(),
                            Long.parseLong(projectId), conversationId, userId, modelId);
                    String result = toolResult.found()
                            ? toolResult.output()
                            : "Unknown tool in custom parser: " + code;

                    // Add Result to History
                    String statusPrefix = (toolResult.found() && toolResult.success()) ? "SUCCESS" : "FAILURE";

                    // Enhancement for Write Tools: Append explicit success for file creation/modification
                    // The model often sees JSON IDs (wps_file_id) and thinks it failed or needs to do more.
                    if ("SUCCESS".equals(statusPrefix) && call.toolName().startsWith("write_")) {
                         result += "\n\n(System Note: File operation completed successfully.)";
                    }

                    // Explicitly tell the model to EVALUATE - with strict anti-over-execution instructions
                    String feedbackMsg = String.format("[System Tool Execution Log]\nTool: %s\nStatus: %s\nOutput: %s\n\n(CRITICAL INSTRUCTION: The tool executed successfully. Now compare with the ORIGINAL user request. If the SPECIFIC task the user asked for is complete, output `<final>` IMMEDIATELY. DO NOT perform additional operations unless the user EXPLICITLY requested them. For example, if user asked to 'delete the 3rd z' and you deleted it, you are DONE - do not delete other z's.)",
                        code, statusPrefix, result);

                    messages.add(dev.langchain4j.data.message.UserMessage.from(feedbackMsg));

                    // Log for history persistence (include status attribute)
                    // 优先使用LLM选择的process name，否则用工具元数据里的中文显示名
                    String processNameForLog = (llmProcessName != null && !llmProcessName.isEmpty())
                        ? llmProcessName
                        : (toolResult.tool() != null ? toolResult.tool().displayName() : "工具执行");
                    executionLog.append(String.format("<process name=\"%s\"><tool_code>%s</tool_code><tool_output status=\"%s\">%s</tool_output></process>\n",
                        processNameForLog, code, statusPrefix, result));

                    // Emit explicit tool_output for frontend parser with status attribute
                    // NOTE: Do NOT wrap in <process> - the tool_output belongs to the existing process
                    // that contained the tool_code.
                    String toolOutputXml = String.format("<tool_output status=\"%s\">%s</tool_output>",
                        statusPrefix, result);
                    sseEmitterService.send(conversationId, "text_delta", "{\"content\":\"" + toolOutputXml.replace("\"", "\\\"").replace("\n", "\\n") + "\"}");

                    toolExecuted = true;
                }

                if (toolExecuted) {
                     // 增量保存：在XML工具执行后立即保存AI消息和工具输出，防止对话中断导致上下文丢失
                     String intermediateXmlContent = content + "\n" + executionLog.toString();
                     messageService.saveMessage(projectId, userId, conversationId, "ASSISTANT", intermediateXmlContent);
                     log.info("Intermediate save after XML tool execution for conversation: {}", conversationId);

                     // Recurse with executionLog
                     runLoop(model, messages, conversationId, projectId, userId, modelId, depth + 1, executionLog, agentMode);
                     return;
                }
            }

            // 3. Check for Artifacts
            // - Task List: Do NOT stop loop anymore (User Requirement). Backend maintains it or just logs it.
            // - Implementation Plan: STOP LOOP for approval.
            
            // FIRST: Strip any markdown code block wrappers that LLM may have added
            String cleanedContent = content;
            cleanedContent = cleanedContent.replaceAll("^```(?:xml|html|markdown)?\\s*\\n?", "");
            cleanedContent = cleanedContent.replaceAll("\\n?```\\s*$", "");
            cleanedContent = cleanedContent.replaceAll("```(?:xml|html|markdown)?\\s*\\n", "");
            cleanedContent = cleanedContent.replaceAll("\\n```", "");
            
            if (cleanedContent.contains("<artifact") && (cleanedContent.contains("type=\"implementation_plan\"") || cleanedContent.contains("type=\"task_list\""))) {
                // Parse full artifact
                String type = "unknown";
                if (cleanedContent.contains("type=\"implementation_plan\"")) type = "implementation_plan";
                else if (cleanedContent.contains("type=\"task_list\"")) type = "task_list";
                
                // Extract name attribute if present
                String artifactName = null;
                java.util.regex.Pattern namePattern = java.util.regex.Pattern.compile("<artifact[^>]*name=\"([^\"]+)\"[^>]*>");
                java.util.regex.Matcher nameMatcher = namePattern.matcher(cleanedContent);
                if (nameMatcher.find()) {
                    artifactName = nameMatcher.group(1).trim();
                    // Sanitize for filename (max 30 chars, remove special chars)
                    artifactName = artifactName.replaceAll("[/\\\\:*?\"<>|]", "_");
                    if (artifactName.length() > 30) artifactName = artifactName.substring(0, 30);
                }
                
                // Extract Content inside tags
                String artifactContent = "";
                java.util.regex.Pattern p = java.util.regex.Pattern.compile("<artifact[^>]*>([\\s\\S]*?)</artifact>");
                java.util.regex.Matcher m = p.matcher(cleanedContent);
                if (m.find()) {
                    artifactContent = m.group(1).trim();
                } else {
                     // Fallback: Try to extract everything after the opening artifact tag
                     int start = cleanedContent.indexOf(">" , cleanedContent.indexOf("<artifact"));
                     int end = cleanedContent.indexOf("</artifact>");
                     if (start > 0 && end > start) {
                         artifactContent = cleanedContent.substring(start + 1, end).trim();
                     } else {
                         artifactContent = cleanedContent; // Last resort fallback
                     }
                }
                
                // Determine filename: prefer extracted name, fallback to default
                String filename;
                if (artifactName != null && !artifactName.isEmpty()) {
                    filename = artifactName + ".md";
                } else {
                    filename = (type.equals("task_list") ? "Task List" : "Plan") + ".md";
                }
                
                log.info("Artifact detected: type={}, name={}, contentLength={}", type, filename, artifactContent.length());
                
                try {
                     projectFileService.saveArtifactFile(Long.valueOf(projectId), conversationId, filename, artifactContent, userId);
                     log.info("Artifact Saved: path=AI Assistant Files/{}/{}", conversationId, filename);
                } catch (Exception e) {
                     log.error("Failed to save artifact file", e);
                }

                if (type.equals("implementation_plan")) {
                    log.info("Detected Implementation Plan. STOPPING LOOP for user approval.");
                    // Save assistant message with execution log prepended
                    String fullContent = executionLog.length() > 0 ? executionLog.toString() + content : content;
                    messageService.saveMessage(projectId, userId, conversationId, "ASSISTANT", fullContent);
                    // 发送 bubble_end 表示当前响应结束（等待用户审批）
                    sseEmitterService.send(conversationId, "bubble_end", "{\"status\":\"awaiting_approval\"}");
                    sseEmitterService.close(conversationId);
                    clearCancelledState(conversationId);
                    return; // Stop and wait for user action
                }
            }
            
            // 3.1 Check for Title (Update Conversation Title)
            // Pattern: <title>Title Content</title>
            if (content.contains("<title>")) {
                java.util.regex.Pattern pTitle = java.util.regex.Pattern.compile("<title>([\\s\\S]*?)</title>");
                java.util.regex.Matcher mTitle = pTitle.matcher(content);
                if (mTitle.find()) {
                    String newTitle = mTitle.group(1).trim();
                    if (!newTitle.isEmpty()) {
                        // Truncate to 30 chars for folder safety
                        if (newTitle.length() > 30) newTitle = newTitle.substring(0, 30);
                        
                        log.info("Updating Conversation Title to: {}", newTitle);
                        try {
                            // Update Folder Name in "AI Assistant Files"
                            projectFileService.renameConversationFolder(conversationId, newTitle, userId);
                        } catch (Exception e) {
                             log.warn("Failed to update conversation folder title", e);
                        }
                    }
                }
            }

            // 4. Default: Loop Finished
            log.info("Agent Loop Finished for {}", conversationId);
            if (!content.isEmpty()) {
                // Prepend execution log for history persistence
                String fullContent = executionLog.length() > 0 ? executionLog.toString() + content : content;
                messageService.saveMessage(projectId, userId, conversationId, "ASSISTANT", fullContent);
            }
            // 触发记忆写入管线（异步：对话摘要 / 项目记忆 / MemCell 原子记忆提取）
            try {
                memoryPipelineService.onConversationTurnCompleted(
                        conversationId, projectId, userId, new java.util.ArrayList<>(messages));
            } catch (Exception memEx) {
                log.warn("Failed to trigger memory pipeline for {}", conversationId, memEx);
            }
            // 发送 bubble_end 表示整个循环真正结束
            sseEmitterService.send(conversationId, "bubble_end", "{\"status\":\"finished\"}");
            sseEmitterService.close(conversationId);
            // 清理取消状态
            clearCancelledState(conversationId);
            activeStreamContent.remove(conversationId); // CLEANUP
          } catch (Exception e) {
            // 确保异常时也能正确结束 bubble，避免前端一直显示加载状态
            log.error("Error in onComplete callback for conversation: " + conversationId, e);
            sseEmitterService.send(conversationId, "error", "Callback Error: " + e.getMessage());
            sseEmitterService.close(conversationId);
            clearCancelledState(conversationId);
            activeStreamContent.remove(conversationId); // CLEANUP
          }
        });
        
        // Execute Generation with Tools
        // Ask 模式：不传递工具，禁止工具调用
        if (agentMode == AgentMode.ASK) {
            log.info("Ask mode: generating without tools");
            model.generate(messages, handler);
        } else {
            // Agent 和 Plan 模式：传递工具规格（内置 + 插件，统一来自注册表）
            List<ToolSpecification> allTools = toolRegistry.getAllSpecifications();
            model.generate(messages, allTools, handler);
        }
    }

    // =================================================================================
    // Helper to notify frontend of file changes (Added/Modified)
    // =================================================================================
    private void notifyFileChange(String conversationId, String fileName, String changeType) {
        try {
            // Determine pure filename if path is given
            String name = fileName;
            if (name.contains("/") || name.contains("\\")) {
                java.nio.file.Path p = java.nio.file.Paths.get(name);
                name = p.getFileName().toString();
            }
            
            // Send SSE event to frontend
            String json = String.format("{\"fileName\":\"%s\", \"changeType\":\"%s\"}", 
                name.replace("\"", "\\\""), changeType);
            sseEmitterService.send(conversationId, "file_change", json);
            
            // Persist to database for history retrieval
            conversationFileChangeService.saveFileChange(conversationId, name, changeType);
        } catch (Exception e) {
            log.warn("Failed to notify file change", e);
        }
    }

    // Simple naive JSON extractor for single String arg tools
    private String extractArg(String jsonArgs, String key) {
        if (jsonArgs == null) return "";
        // using hutool or jackson is better. 
        // e.g. {"fileId": "123"}
        try {
            cn.hutool.json.JSONObject obj = cn.hutool.json.JSONUtil.parseObj(jsonArgs);
            return obj.getStr(key);
        } catch (Exception e) {
            return jsonArgs; // fallback
        }
    }
    


    /**
     * Clean XML control tags from LLM output before saving to DB.
     * These tags are for streaming display only and should not be persisted.
     */
    private String cleanXmlTags(String content) {
        if (content == null) return "";
        
        // Remove markdown code block wrappers that LLM sometimes outputs
        // ```xml, ```html, ``` etc.
        String cleaned = content.replaceAll("^```(?:xml|html|markdown)?\\s*\\n?", "");
        cleaned = cleaned.replaceAll("\\n?```\\s*$", "");
        cleaned = cleaned.replaceAll("```(?:xml|html|markdown)?\\s*\\n", "");
        cleaned = cleaned.replaceAll("\\n```", "");
        
        // Remove bubble_type tags: <bubble_type mode="..." />
        cleaned = cleaned.replaceAll("<bubble_type[^>]*/?>", "");
        
        // Remove artifact tags but KEEP the content inside
        // <artifact type="...">content</artifact> -> content
        cleaned = cleaned.replaceAll("<artifact[^>]*>", "");
        cleaned = cleaned.replaceAll("</artifact>", "");
        
        // Remove task_update tags: <task_update id="..." status="..." />
        cleaned = cleaned.replaceAll("<task_update[^>]*/?>", "");
        
        // Remove tool_code, tool_use tags
        cleaned = cleaned.replaceAll("<tool_code[^>]*>[\\s\\S]*?</tool_code>", "");
        cleaned = cleaned.replaceAll("<tool_use[^>]*>[\\s\\S]*?</tool_use>", "");
        cleaned = cleaned.replaceAll("<tool_code[^>]*/?>", "");
        cleaned = cleaned.replaceAll("<tool_use[^>]*/?>", "");
        
        // Keep <final> tag content but remove the tags themselves
        // <final>content</final> -> content
        cleaned = cleaned.replaceAll("<final>", "");
        cleaned = cleaned.replaceAll("</final>", "");
        
        // Clean up multiple consecutive newlines
        cleaned = cleaned.replaceAll("\\n{3,}", "\n\n");
        
        return cleaned.trim();
    }

    private java.util.List<com.checkba.controller.ai.AiAgentController.ContextItem> convertFileIdsToContextItems(java.util.List<String> fileIds) {
        if (fileIds == null) return null;
        return fileIds.stream().map(id -> {
            com.checkba.controller.ai.AiAgentController.ContextItem item = new com.checkba.controller.ai.AiAgentController.ContextItem();
            item.setId(id);
            item.setIsDir(false);
            return item;
        }).collect(java.util.stream.Collectors.toList());
    }


}
