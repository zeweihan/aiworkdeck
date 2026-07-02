package com.checkba.service.ai;

import com.checkba.controller.ai.AiAgentController;
import com.checkba.model.ai.AgentMode;
import com.checkba.model.entity.ProjectAiMessage;
import com.checkba.service.ProjectAiMessageService;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.agent.tool.ToolSpecifications;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.core.io.ClassPathResource;
import org.springframework.util.StreamUtils;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.lang.reflect.Method;
import java.util.concurrent.CompletableFuture;

/**
 * Agent 核心编排器 ("The Brain").
 * 负责：
 * 1. 组装上下文 (Context Assembly)
 * 2. 调用 LLM (Thinking)
 * 3. 处理流式响应 (Streaming)
 * 4. 执行工具 (Tool Execution)
 * 5. 维护循环 (Loop)
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
    private final com.checkba.service.ai.tools.LegalTools legalTools; 
    private final com.checkba.service.ai.tools.FileTools fileTools;
    private final com.checkba.service.ai.tools.WebTools webTools;
    private final com.checkba.service.ai.tools.PythonTools pythonTools;
    private final com.checkba.service.ai.tools.MemoryTools memoryTools;
    private final com.checkba.service.ai.tools.WpsTools wpsTools;
    private final com.checkba.service.ai.tools.PptxTools pptxTools;
    private final com.checkba.service.ai.tools.PptxEditTools pptxEditTools;
    private final com.checkba.service.ProjectFileService projectFileService;
    private final PluginService pluginService;
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

    // Correct signature accepting projectId, modelId, and userId for progress tracking
    private String executeNativeTool(dev.langchain4j.agent.tool.ToolExecutionRequest req, Long projectId, String conversationId, String modelId, Long userId) {
        String toolName = req.name();
        String args = req.arguments();
        
        try {
            if ("read_document".equals(toolName)) {
                String fileId = extractArg(args, "fileId");
                return legalTools.read_document(fileId);
            } else if ("search_web".equals(toolName)) {
                 String query = extractArg(args, "query");
                 return webTools.search_web(query);
            } else if ("browse_url".equals(toolName)) {
                 String url = extractArg(args, "url");
                 return webTools.browse_url(url);
            } else if ("law_search".equals(toolName)) {
                 String query = extractArg(args, "query");
                 return legalTools.law_search(query);
            } else if ("law_search_keyword".equals(toolName)) {
                 String title = extractArg(args, "title");
                 String fulltext = extractArg(args, "fulltext");
                 return legalTools.law_search_keyword(title, fulltext);
            } else if ("law_recognition".equals(toolName)) {
                 String text = extractArg(args, "text");
                 return legalTools.law_recognition(text);
            } else if ("get_law_article".equals(toolName)) {
                 String title = extractArg(args, "title");
                 String number = extractArg(args, "number");
                 return legalTools.get_law_article(title, number);
            } else if ("write_docx".equals(toolName)) {
                 // write_docx logic - support both naming conventions
                 String fileName = extractArg(args, "name");
                 if (fileName == null || fileName.isEmpty()) fileName = extractArg(args, "fileName");
                 String markdown = extractArg(args, "markdown_content");
                 if (markdown == null || markdown.isEmpty()) markdown = extractArg(args, "markdownContent");
                 String result = fileTools.write_docx(fileName, markdown, projectId);
                 
                 // Trigger UI Refresh if success
                 if (result != null && !result.startsWith("Error")) {
                     sseEmitterService.send(conversationId, "client_action", "{\"action\":\"refresh_files\"}");
                 }
                 return result;
            } else if ("run_python".equals(toolName)) {
                 String code = extractArg(args, "code");
                 return pythonTools.run_python(code);
            } else if ("write_file".equals(toolName)) {
                 String filename = extractArg(args, "filename");
                 String content = extractArg(args, "content");
                 return fileTools.write_file(filename, content, projectId);
            } else if ("list_files".equals(toolName)) {
                 String subPath = extractArg(args, "subPath");
                 return fileTools.list_files(projectId, subPath);
            } else if ("search_project_files".equals(toolName)) {
                 String pattern = extractArg(args, "fileNamePattern");
                 String dir = extractArg(args, "dirPath");
                 return fileTools.search_project_files(pattern, dir);
            } else if ("delete_file".equals(toolName)) {
                 String path = extractArg(args, "filePath");
                 return fileTools.delete_file(path);
            } else if ("move_file".equals(toolName)) {
                 String src = extractArg(args, "sourcePath");
                 String dst = extractArg(args, "destPath");
                 return fileTools.move_file(src, dst);
            } else if ("read_file".equals(toolName)) {
                 String path = extractArg(args, "filePath");
                 return fileTools.read_file(path);
            // ==================== 记忆工具 ====================
            } else if ("save_memory".equals(toolName)) {
                 String type = extractArg(args, "type");
                 String key = extractArg(args, "key");
                 String value = extractArg(args, "value");
                 boolean isProtected = "true".equalsIgnoreCase(extractArg(args, "isProtected"));
                 return memoryTools.save_memory(type, key, value, isProtected);
            } else if ("query_memory".equals(toolName)) {
                 String query = extractArg(args, "query");
                 String type = extractArg(args, "type");
                 if (type == null || type.isEmpty()) type = "all";
                 return memoryTools.query_memory(query, type);
            } else if ("get_project_context".equals(toolName)) {
                 return memoryTools.get_project_context();
            } else if ("update_project_info".equals(toolName)) {
                 String field = extractArg(args, "field");
                 String value = extractArg(args, "value");
                 return memoryTools.update_project_info(field, value);
            } else if ("search_knowledge_base".equals(toolName)) {
                 String query = extractArg(args, "query");
                 int limit = 5;
                 try {
                     limit = Integer.parseInt(extractArg(args, "limit"));
                 } catch (Exception e) { /* use default */ }
                 return memoryTools.search_knowledge_base(query, limit);
            } else if ("get_conversation_summary".equals(toolName)) {
                 return memoryTools.get_conversation_summary();
            } 
            // ==================== WPS 工具 ====================
            else if ("wps_list_project_files".equals(toolName)) {
                 return wpsTools.wps_list_project_files(projectId);
            } else if ("wps_open_file".equals(toolName)) {
                 Long fileId = Long.parseLong(extractArg(args, "fileId"));
                 return wpsTools.wps_open_file(fileId);
            } else if ("wps_get_selection".equals(toolName)) {
                 return wpsTools.wps_get_selection();
            } else if ("wps_set_selection".equals(toolName)) {
                 Integer start = Integer.parseInt(extractArg(args, "start"));
                 Integer end = Integer.parseInt(extractArg(args, "end"));
                 return wpsTools.wps_set_selection(start, end);
            } else if ("wps_replace_selection".equals(toolName)) {
                 String text = extractArg(args, "text");
                 return wpsTools.wps_replace_selection(text);
            } else if ("wps_goto".equals(toolName)) {
                 String type = extractArg(args, "type");
                 String target = extractArg(args, "target");
                 return wpsTools.wps_goto(type, target);
            } else if ("wps_find_text".equals(toolName)) {
                 String keyword = extractArg(args, "keyword");
                 Boolean matchCase = "true".equalsIgnoreCase(extractArg(args, "matchCase"));
                 return wpsTools.wps_find_text(keyword, matchCase);
            } else if ("wps_find_replace".equals(toolName)) {
                 String findText = extractArg(args, "findText");
                 String replaceText = extractArg(args, "replaceText");
                 String replaceAllStr = extractArg(args, "replaceAll");
                 Boolean replaceAll = replaceAllStr.isEmpty() || "true".equalsIgnoreCase(replaceAllStr);
                 return wpsTools.wps_find_replace(findText, replaceText, replaceAll);
            } else if ("wps_insert_at_cursor".equals(toolName)) {
                 String text = extractArg(args, "text");
                 return wpsTools.wps_insert_at_cursor(text);
            } else if ("wps_get_paragraph".equals(toolName)) {
                 Integer paragraphIndex = Integer.parseInt(extractArg(args, "paragraphIndex"));
                 return wpsTools.wps_get_paragraph(paragraphIndex);
            } else if ("wps_modify_paragraph".equals(toolName)) {
                 Integer paragraphIndex = Integer.parseInt(extractArg(args, "paragraphIndex"));
                 String newText = extractArg(args, "newText");
                 return wpsTools.wps_modify_paragraph(paragraphIndex, newText);
            } else if ("wps_get_outline".equals(toolName)) {
                 return wpsTools.wps_get_outline();
            } else if ("wps_insert_under_heading".equals(toolName)) {
                 String headingText = extractArg(args, "headingText");
                 String content = extractArg(args, "content");
                 return wpsTools.wps_insert_under_heading(headingText, content);
            } else if ("wps_search_related_docs".equals(toolName)) {
                 String keyword = extractArg(args, "keyword");
                 return wpsTools.wps_search_related_docs(keyword, projectId);
            } else if ("wps_replace_nth_match".equals(toolName)) {
                 String findText = extractArg(args, "findText");
                 String replaceText = extractArg(args, "replaceText");
                 Integer matchIndex = Integer.parseInt(extractArg(args, "matchIndex"));
                 return wpsTools.wps_replace_nth_match(findText, replaceText, matchIndex);
            } else if ("wps_delete_match".equals(toolName)) {
                 String findText = extractArg(args, "findText");
                 Integer matchIndex = Integer.parseInt(extractArg(args, "matchIndex"));
                 return wpsTools.wps_delete_match(findText, matchIndex);
            } else if ("wps_delete_text".equals(toolName)) {
                 String text = extractArg(args, "text");
                 Boolean deleteAll = "true".equalsIgnoreCase(extractArg(args, "deleteAll"));
                 return wpsTools.wps_delete_text(text, deleteAll);
            } else if ("wps_debug_revisions".equals(toolName)) {
                 return wpsTools.wps_debug_revisions();
            // ==================== 拟人式原语（LibreOffice 编辑器） ====================
            } else if ("wps_get_document_text".equals(toolName)) {
                 Integer startParagraph = safeParseInt(extractArg(args, "startParagraph"), "startParagraph");
                 Integer maxParagraphs = safeParseInt(extractArg(args, "maxParagraphs"), "maxParagraphs");
                 return wpsTools.wps_get_document_text(startParagraph, maxParagraphs);
            } else if ("wps_get_cursor_context".equals(toolName)) {
                 return wpsTools.wps_get_cursor_context();
            } else if ("wps_select_anchor".equals(toolName)) {
                 return wpsTools.wps_select_anchor(extractArg(args, "anchorId"));
            } else if ("wps_select_paragraph".equals(toolName)) {
                 return wpsTools.wps_select_paragraph(safeParseInt(extractArg(args, "index"), "index"));
            } else if ("wps_collapse_cursor".equals(toolName)) {
                 return wpsTools.wps_collapse_cursor(extractArg(args, "to"));
            } else if ("wps_replace_at_anchor".equals(toolName)) {
                 return wpsTools.wps_replace_at_anchor(extractArg(args, "anchorId"), extractArg(args, "newText"));
            } else if ("wps_delete_selection".equals(toolName)) {
                 return wpsTools.wps_delete_selection();
            } else if ("wps_format_selection".equals(toolName)) {
                 return wpsTools.wps_format_selection(
                         safeParseBool(extractArg(args, "bold")),
                         safeParseBool(extractArg(args, "italic")),
                         safeParseBool(extractArg(args, "underline")),
                         safeParseBool(extractArg(args, "strikeout")),
                         extractArg(args, "highlight"),
                         extractArg(args, "color"),
                         safeParseDouble(extractArg(args, "fontSize")),
                         extractArg(args, "fontName"));
            } else if ("wps_set_paragraph_format".equals(toolName)) {
                 return wpsTools.wps_set_paragraph_format(
                         extractArg(args, "alignment"),
                         safeParseInt(extractArg(args, "headingLevel"), "headingLevel"));
            } else if ("wps_undo".equals(toolName)) {
                 return wpsTools.wps_undo(safeParseInt(extractArg(args, "steps"), "steps"));
            } else if ("wps_redo".equals(toolName)) {
                 return wpsTools.wps_redo(safeParseInt(extractArg(args, "steps"), "steps"));
            // ==================== PPTX 文件管理工具 ====================
            } else if ("list_project_folders".equals(toolName)) {
                 return pptxTools.list_project_folders(projectId);
            } else if ("pptx_list_files".equals(toolName)) {
                 return pptxTools.pptx_list_files(projectId);
            } else if ("pptx_search_files".equals(toolName)) {
                 String keyword = extractArg(args, "keyword");
                 return pptxTools.pptx_search_files(projectId, keyword);
            } else if ("pptx_open_file".equals(toolName)) {
                 Long fileId = Long.parseLong(extractArg(args, "fileId"));
                 return pptxTools.pptx_open_file(fileId);
            } else if ("pptx_check_service".equals(toolName)) {
                 return pptxTools.pptx_check_service();
            } else if ("pptx_generate".equals(toolName)) {
                 String topic = extractArg(args, "topic");
                 String parentIdStr = extractArg(args, "parentId");
                 // 处理 parentId=null/None 的情况（LLM 可能生成字符串 "null" 或 Python 的 "None"）
                 Long parentId = (parentIdStr != null && !parentIdStr.isEmpty() 
                     && !"null".equalsIgnoreCase(parentIdStr) && !"none".equalsIgnoreCase(parentIdStr)) 
                     ? Long.parseLong(parentIdStr) : null;
                 String fileName = extractArg(args, "fileName");
                 String style = extractArg(args, "style");
                 String language = extractArg(args, "language");
                 return pptxTools.pptx_generate(topic, projectId, parentId, fileName, style, language, modelId);
            } else if ("pptx_generate_outline".equals(toolName)) {
                 String topic = extractArg(args, "topic");
                 String language = extractArg(args, "language");
                 return pptxTools.pptx_generate_outline(topic, language, modelId);
            // ==================== PPTX 编辑工具 ====================
            } else if ("pptx_get_presentation_info".equals(toolName)) {
                 return pptxEditTools.pptx_get_presentation_info();
            } else if ("pptx_get_slide_content".equals(toolName)) {
                 Integer slideIndex = Integer.parseInt(extractArg(args, "slideIndex"));
                 return pptxEditTools.pptx_get_slide_content(slideIndex);
            } else if ("pptx_get_selection".equals(toolName)) {
                 return pptxEditTools.pptx_get_selection();
            } else if ("pptx_modify_slide_text".equals(toolName)) {
                 Integer slideIndex = Integer.parseInt(extractArg(args, "slideIndex"));
                 Integer shapeIndex = Integer.parseInt(extractArg(args, "shapeIndex"));
                 String newText = extractArg(args, "newText");
                 return pptxEditTools.pptx_modify_slide_text(slideIndex, shapeIndex, newText);
            } else if ("pptx_insert_text".equals(toolName)) {
                 Integer slideIndex = Integer.parseInt(extractArg(args, "slideIndex"));
                 Integer shapeIndex = Integer.parseInt(extractArg(args, "shapeIndex"));
                 String text = extractArg(args, "text");
                 String position = extractArg(args, "position");
                 return pptxEditTools.pptx_insert_text(slideIndex, shapeIndex, text, position);
            } else if ("pptx_mark_delete_text".equals(toolName)) {
                 Integer slideIndex = Integer.parseInt(extractArg(args, "slideIndex"));
                 Integer shapeIndex = Integer.parseInt(extractArg(args, "shapeIndex"));
                 String textToDelete = extractArg(args, "textToDelete");
                 return pptxEditTools.pptx_mark_delete_text(slideIndex, shapeIndex, textToDelete);
            } else if ("pptx_save".equals(toolName)) {
                 return pptxEditTools.pptx_save();
            } else {
                // Check if it's a dynamic plugin tool
                Object toolObject = pluginService.getPluginTools().get(toolName);
                if (toolObject != null) {
                    log.info("Executing dynamic plugin tool: {}", toolName);
                    return executeDynamicTool(toolObject, req, projectId, conversationId);
                }
            }
        } catch (Exception e) {
            return "Error executing tool: " + e.getMessage();
        }
        return "Tool not found or arguments invalid.";
    }

    private String executeDynamicTool(Object toolObject, dev.langchain4j.agent.tool.ToolExecutionRequest req, Long projectId, String conversationId) {
        try {
            // Use reflection or a more sophisticated dispatcher.
            // For now, let's find the method with @Tool that matches name.
            for (Method method : toolObject.getClass().getDeclaredMethods()) {
                if (method.isAnnotationPresent(dev.langchain4j.agent.tool.Tool.class)) {
                    String name = method.getName(); 
                    // Actually LangChain4j uses method name as tool name if @Tool value is a description.
                    
                    if (method.getName().equals(req.name())) {
                        // Very naive arg mapping (only supports Map/String/JSONObject)
                        // In reality, we should use ToolSpecifications to map args.
                        // Simple version: if it takes Map or String
                        Class<?>[] params = method.getParameterTypes();
                        if (params.length == 0) return (String) method.invoke(toolObject);
                        
                        // Extract args as Map
                        cn.hutool.json.JSONObject obj = cn.hutool.json.JSONUtil.parseObj(req.arguments());
                        Object[] args = new Object[params.length];
                        
                        // Just a prototype mapping - assuming first param might be Map or String
                        if (params[0].equals(Map.class)) args[0] = obj.toBean(Map.class);
                        else if (params[0].equals(String.class)) args[0] = obj.getStr(method.getParameters()[0].getName());
                        
                        return (String) method.invoke(toolObject, args);
                    }
                }
            }
        } catch (Exception e) {
            log.error("Dynamic tool execution failed", e);
            return "Error: " + e.getMessage();
        }
        return "Error: Tool method not found in plugin.";
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
                agentMode
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

                // Execute Native Tools
                for (dev.langchain4j.agent.tool.ToolExecutionRequest req : aiMessage.toolExecutionRequests()) {
                    String result = executeNativeTool(req, Long.parseLong(projectId), conversationId, modelId, userId);
                    messages.add(dev.langchain4j.data.message.ToolExecutionResultMessage.from(req, result));
                    
                    // Determine status for history and display
                    String nativeToolStatus = (result != null && !result.startsWith("Error")) ? "SUCCESS" : "FAILURE";
                    
                    // Log for history persistence (include status attribute)
                    executionLog.append(String.format("<process name=\"%s\"><tool_code>%s(%s)</tool_code><tool_output status=\"%s\">%s</tool_output></process>\n",
                        req.name(), req.name(), req.arguments(), nativeToolStatus, result));

                    // --- Notify File Change (Native Tools) ---
                    if ("SUCCESS".equals(nativeToolStatus)) {
                         String toolName = req.name();
                         if ("write_file".equals(toolName)) {
                             notifyFileChange(conversationId, extractArg(req.arguments(), "filename"), "ADDED");
                         } else if ("write_docx".equals(toolName)) {
                             notifyFileChange(conversationId, extractArg(req.arguments(), "fileName"), "ADDED");
                         } else if ("pptx_generate".equals(toolName)) {
                             notifyFileChange(conversationId, extractArg(req.arguments(), "fileName"), "ADDED");
                         } else if (toolName.startsWith("wps_modify") || toolName.startsWith("wps_replace") || toolName.startsWith("wps_insert")) {
                            // Ideally we need to know WHICH file. 
                            // But usually Agent works on the "active" file or opened file.
                            // For now, we might not have the filename easily for modifications unless we track open state.
                            // However, the requirement says "Modified Files".
                            // If we don't have the filename, maybe we skip or use "Current File"?
                            // Let's assume the frontend knows the active file, OR we just say "Current File"
                            // Actually, better: if we can't get the name, maybe just don't list it or list "Current Document"?
                         }
                    }
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
            if (content.contains("<tool_code>") || content.contains("<code>")) {
                log.info("Detected XML Tool Code in content. Parsing...");
                
                // 提取LLM选择的process name，用于历史记录保存时保持一致性
                String llmProcessName = null;
                java.util.regex.Pattern processNamePattern = java.util.regex.Pattern.compile("<process[^>]*name=\"([^\"]*)\"[^>]*>");
                java.util.regex.Matcher processNameMatcher = processNamePattern.matcher(content);
                if (processNameMatcher.find()) {
                    llmProcessName = processNameMatcher.group(1);
                    log.info("Extracted LLM process name: {}", llmProcessName);
                }
                
                // Regex to match either <tool_code>...</tool_code> or <code>...</code>
                // Uses backreference \1 to match the opening tag name, and (?s) for DOTALL mode to match newlines
                java.util.regex.Pattern toolPattern = java.util.regex.Pattern.compile("(?s)<(tool_code|code)>(.*?)</\\1>");
                java.util.regex.Matcher matcher = toolPattern.matcher(content);
                
                boolean toolExecuted = false;
                
                while (matcher.find()) {
                    String code = matcher.group(2).trim(); // e.g., legal_tools.search_web(query="...") or print(legal_tools...)
                    log.info("Parsed Tool Code: {}", code);
                    
                    // Notify Frontend
                    // String escapedCode = "Executing: " + code.replace("\"", "\\\"");
                    // sseEmitterService.send(conversationId, "step_update", "{\"status\":\"loading\", \"message\":\"" + escapedCode + "\"}");
                    
                    String result = "Error executing tool code: " + code;
                    try {
                        // VERY Basic Parsing for specific known tools
                        // Expecting: legal_tools.search_web(query="...")
                        // Or: default_api.search_web(...)
                        
                        // PRIORITY 1: run_python - Check first to avoid false matches on tools inside Python code
                        if (code.startsWith("run_python(") || code.contains("run_python(code=")) {
                             // Extract Python code from run_python(code='...' or code="...")
                             String pythonCode = extractPythonCodeArg(code);
                             if (pythonCode != null && !pythonCode.isEmpty()) {
                                 log.info("Executing Python code via PythonTools, code length: {}", pythonCode.length());
                                 result = pythonTools.run_python(pythonCode);
                             } else {
                                 result = "Error: Could not extract Python code from run_python call.";
                             }
                        } else if (code.contains("search_web") || code.contains("search_laws")) {
                             String query = extractStringArg(code, "query");
                             // Fallback: search_laws -> search_web
                             result = webTools.search_web(query);
                        } else if (code.contains("law_search_keyword")) {
                             String title = extractStringArg(code, "title");
                             String fulltext = extractStringArg(code, "fulltext");
                             result = legalTools.law_search_keyword(title, fulltext);
                        } else if (code.contains("law_recognition")) {
                             String text = extractStringArg(code, "text");
                             result = legalTools.law_recognition(text);
                        } else if (code.contains("law_search")) {
                             String query = extractStringArg(code, "query");
                             result = legalTools.law_search(query);
                        } else if (code.contains("get_law_article")) {
                             String title = extractStringArg(code, "title");
                             String number = extractStringArg(code, "number");
                             result = legalTools.get_law_article(title, number);
                        } else if (code.contains("browse_url")) {
                             String url = extractStringArg(code, "url");
                             result = webTools.browse_url(url);
                        } else if (code.contains("read_document")) {
                             String fileId = extractStringArg(code, "fileId");
                             // fallback if fileId arg name differs
                             if (fileId.isEmpty()) fileId = extractStringArg(code, "id"); 
                             result = legalTools.read_document(fileId);
                        } else if (code.contains("write_docx")) {
                             // Extract "name" first (as per system_prompt.md), fallback to "fileName"
                             String fileName = extractStringArg(code, "name");
                             if (fileName.isEmpty()) fileName = extractStringArg(code, "fileName");
                             // Try to extract markdown content with various parameter names
                             String fileContent = extractStringArg(code, "markdown_content");
                             if (fileContent.isEmpty()) fileContent = extractStringArg(code, "markdownContent");
                             if (fileContent.isEmpty()) fileContent = extractStringArg(code, "content");
                             
                             result = fileTools.write_docx(fileName, fileContent, Long.parseLong(projectId));
                             
                             // Trigger UI Refresh if success
                             if (result != null && !result.startsWith("Error")) {
                                 sseEmitterService.send(conversationId, "client_action", "{\"action\":\"refresh_files\"}");
                                 notifyFileChange(conversationId, fileName, "ADDED");
                             }
                        } else if (code.contains("write_file")) {
                             String fileName = extractStringArg(code, "fileName");
                             if (fileName.isEmpty()) fileName = extractStringArg(code, "filename");
                             String fileContent = extractStringArg(code, "content");
                             result = fileTools.write_file(fileName, fileContent, Long.parseLong(projectId));
                             if (!result.startsWith("Error")) notifyFileChange(conversationId, fileName, "ADDED");
                        } else if (code.contains("list_files")) {
                             String subPath = extractStringArg(code, "subPath");
                             if (subPath.isEmpty()) subPath = "."; 
                             result = fileTools.list_files(Long.parseLong(projectId), subPath);
                        } else if (code.contains("search_project_files")) {
                             String pattern = extractStringArg(code, "fileNamePattern");
                             String dir = extractStringArg(code, "dirPath");
                             result = fileTools.search_project_files(pattern, dir);
                        } else if (code.contains("read_file")) {
                             String path = extractStringArg(code, "filePath");
                             // fallback
                             if (path.isEmpty()) path = extractStringArg(code, "path");
                             result = fileTools.read_file(path);
                        } else if (code.contains("delete_file")) {
                             String path = extractStringArg(code, "filePath");
                             result = fileTools.delete_file(path);
                        } else if (code.contains("move_file")) {
                             String src = extractStringArg(code, "sourcePath");
                             String dst = extractStringArg(code, "destPath");
                             result = fileTools.move_file(src, dst);
                        // ==================== 记忆工具 (Legacy XML) ====================
                        } else if (code.contains("save_memory")) {
                             String type = extractStringArg(code, "type");
                             String key = extractStringArg(code, "key");
                             String value = extractStringArg(code, "value");
                             boolean isProtected = "true".equalsIgnoreCase(extractStringArg(code, "isProtected"));
                             result = memoryTools.save_memory(type, key, value, isProtected);
                        } else if (code.contains("query_memory")) {
                             String query = extractStringArg(code, "query");
                             String type = extractStringArg(code, "type");
                             if (type.isEmpty()) type = "all";
                             result = memoryTools.query_memory(query, type);
                        } else if (code.contains("get_project_context")) {
                             result = memoryTools.get_project_context();
                        } else if (code.contains("update_project_info")) {
                             String field = extractStringArg(code, "field");
                             String value = extractStringArg(code, "value");
                             result = memoryTools.update_project_info(field, value);
                        } else if (code.contains("search_knowledge_base")) {
                             String query = extractStringArg(code, "query");
                             int limit = 5;
                             try {
                                 String limitStr = extractStringArg(code, "limit");
                                 if (!limitStr.isEmpty()) limit = Integer.parseInt(limitStr);
                             } catch (Exception e) { /* use default */ }
                             result = memoryTools.search_knowledge_base(query, limit);
                        } else if (code.contains("get_conversation_summary")) {
                             result = memoryTools.get_conversation_summary();
                        // ==================== PPTX 工具 (Legacy XML) ====================
                        } else if (code.contains("pptx_check_service")) {
                             result = pptxTools.pptx_check_service();
                        } else if (code.contains("pptx_generate_outline")) {
                             String topic = extractStringArg(code, "topic");
                             String language = extractStringArg(code, "language");
                             result = pptxTools.pptx_generate_outline(topic, language, modelId);
                        } else if (code.contains("pptx_generate")) {
                             String topic = extractStringArg(code, "topic");
                             String parentIdStr = extractStringArg(code, "parentId");
                             // 处理 parentId=null/None 的情况（LLM 可能生成字符串 "null" 或 Python 的 "None"）
                             Long parentId = (parentIdStr != null && !parentIdStr.isEmpty() 
                                 && !"null".equalsIgnoreCase(parentIdStr) && !"none".equalsIgnoreCase(parentIdStr)) 
                                 ? Long.parseLong(parentIdStr) : null;
                             String fileName = extractStringArg(code, "fileName");
                             String style = extractStringArg(code, "style");
                             String language = extractStringArg(code, "language");
                             result = pptxTools.pptx_generate(topic, Long.parseLong(projectId), parentId, fileName, style, language, modelId);
                             if (!result.startsWith("Error")) notifyFileChange(conversationId, fileName, "ADDED");
                        // ==================== PPTX 文件管理工具 ====================
                        } else if (code.contains("list_project_folders")) {
                             result = pptxTools.list_project_folders(Long.parseLong(projectId));
                        } else if (code.contains("pptx_list_files")) {
                             result = pptxTools.pptx_list_files(Long.parseLong(projectId));
                        } else if (code.contains("pptx_search_files")) {
                             String keyword = extractStringArg(code, "keyword");
                             result = pptxTools.pptx_search_files(Long.parseLong(projectId), keyword);
                        } else if (code.contains("pptx_open_file")) {
                             String fileIdStr = extractStringArg(code, "fileId");
                             Long fileId = safeParseLong(fileIdStr, "fileId");
                             if (fileId == null) {
                                 result = "Error: fileId 参数无效或为空";
                             } else {
                                 result = pptxTools.pptx_open_file(fileId);
                             }
                        // ==================== 智能 PPT 修改 ====================
                        } else if (code.contains("pptx_smart_modify")) {
                             String fileIdStr = extractStringArg(code, "fileId");
                             String pageIndexStr = extractStringArg(code, "pageIndex");
                             String modifyInstruction = extractStringArg(code, "modifyInstruction");
                             Long fileId = safeParseLong(fileIdStr, "fileId");
                             Integer pageIndex = safeParseInt(pageIndexStr, "pageIndex");
                             if (fileId == null || pageIndex == null) {
                                 result = "Error: 参数解析失败。fileId=" + fileIdStr + ", pageIndex=" + pageIndexStr + 
                                        "。请确保使用正确的格式，如: pptx_smart_modify(fileId=10, pageIndex=1, modifyInstruction=\"...\")";
                             } else if (modifyInstruction == null || modifyInstruction.isEmpty()) {
                                 result = "Error: modifyInstruction 参数不能为空";
                             } else {
                                 // 传递 modelId 以便纯图片页面使用正确的图片生成模型
                                 result = pptxTools.pptx_smart_modify(fileId, pageIndex, modifyInstruction, modelId);
                             }
                        // ==================== PPTX 编辑工具 ====================
                        } else if (code.contains("pptx_edit_page")) {
                             String serviceProjectId = extractStringArg(code, "serviceProjectId");
                             String pageIdStr = extractStringArg(code, "pageId");
                             String editInstruction = extractStringArg(code, "editInstruction");
                             result = pptxTools.pptx_edit_page(serviceProjectId, pageIdStr, editInstruction);
                        } else if (code.contains("pptx_get_project_pages")) {
                             String serviceProjectId = extractStringArg(code, "serviceProjectId");
                             result = pptxTools.pptx_get_project_pages(serviceProjectId);
                        } else if (code.contains("pptx_get_page_screenshot")) {
                             String fileIdStr = extractStringArg(code, "fileId");
                             String pageIndexStr = extractStringArg(code, "pageIndex");
                             Long fileId = safeParseLong(fileIdStr, "fileId");
                             Integer pageIndex = safeParseInt(pageIndexStr, "pageIndex");
                             if (fileId == null || pageIndex == null) {
                                 result = "Error: 参数解析失败。fileId=" + fileIdStr + ", pageIndex=" + pageIndexStr;
                             } else {
                                 result = pptxTools.pptx_get_page_screenshot(fileId, pageIndex);
                             }
                        } else if (code.contains("pptx_refine_outline")) {
                             String serviceProjectId = extractStringArg(code, "serviceProjectId");
                             String userRequirement = extractStringArg(code, "userRequirement");
                             String language = extractStringArg(code, "language");
                             result = pptxTools.pptx_refine_outline(serviceProjectId, userRequirement, language);
                        } else if (code.contains("pptx_export_editable")) {
                             String serviceProjectId = extractStringArg(code, "serviceProjectId");
                             String filename = extractStringArg(code, "filename");
                             String exportModelId = extractStringArg(code, "modelId");
                             result = pptxTools.pptx_export_editable(serviceProjectId, filename, exportModelId);
                        // ==================== PPTX 编辑工具 (WPS WebOffice API) ====================
                        } else if (code.contains("pptx_get_presentation_info")) {
                             result = pptxEditTools.pptx_get_presentation_info();
                        } else if (code.contains("pptx_get_slide_content")) {
                             String slideIndexStr = extractStringArg(code, "slideIndex");
                             Integer slideIndex = Integer.parseInt(slideIndexStr);
                             result = pptxEditTools.pptx_get_slide_content(slideIndex);
                        } else if (code.contains("pptx_get_selection")) {
                             result = pptxEditTools.pptx_get_selection();
                        } else if (code.contains("pptx_modify_slide_text")) {
                             String slideIndexStr = extractStringArg(code, "slideIndex");
                             String shapeIndexStr = extractStringArg(code, "shapeIndex");
                             String newText = extractStringArg(code, "newText");
                             Integer slideIndex = Integer.parseInt(slideIndexStr);
                             Integer shapeIndex = Integer.parseInt(shapeIndexStr);
                             result = pptxEditTools.pptx_modify_slide_text(slideIndex, shapeIndex, newText);
                        } else if (code.contains("pptx_insert_text")) {
                             String slideIndexStr = extractStringArg(code, "slideIndex");
                             String shapeIndexStr = extractStringArg(code, "shapeIndex");
                             String text = extractStringArg(code, "text");
                             String position = extractStringArg(code, "position");
                             Integer slideIndex = Integer.parseInt(slideIndexStr);
                             Integer shapeIndex = Integer.parseInt(shapeIndexStr);
                             result = pptxEditTools.pptx_insert_text(slideIndex, shapeIndex, text, position);
                        } else if (code.contains("pptx_mark_delete_text")) {
                             String slideIndexStr = extractStringArg(code, "slideIndex");
                             String shapeIndexStr = extractStringArg(code, "shapeIndex");
                             String textToDelete = extractStringArg(code, "textToDelete");
                             Integer slideIndex = Integer.parseInt(slideIndexStr);
                             Integer shapeIndex = Integer.parseInt(shapeIndexStr);
                             result = pptxEditTools.pptx_mark_delete_text(slideIndex, shapeIndex, textToDelete);
                        } else if (code.contains("pptx_save")) {
                             result = pptxEditTools.pptx_save();
                        // ==================== WPS 通用工具 ====================
                        } else if (code.contains("wps_list_project_files")) {
                             result = wpsTools.wps_list_project_files(Long.parseLong(projectId));
                        } else if (code.contains("wps_start_stream")) {
                             String fileIdStr = extractStringArg(code, "fileId");
                             String fileNameArg = extractStringArg(code, "fileName");
                             // Allow null fileId (for new files)
                             Long fileId = safeParseLong(fileIdStr, "fileId");
                             // projectId 来自当前上下文
                             result = wpsTools.wps_start_stream(fileId, fileNameArg, Long.parseLong(projectId));
                        } else if (code.contains("wps_open_file")) {
                             String fileIdStr = extractStringArg(code, "fileId");
                             Long fileId = Long.parseLong(fileIdStr);
                             result = wpsTools.wps_open_file(fileId);
                        } else if (code.contains("wps_get_selection")) {
                             result = wpsTools.wps_get_selection();
                        } else if (code.contains("wps_find_text")) {
                             String keyword = extractStringArg(code, "keyword");
                             result = wpsTools.wps_find_text(keyword, false);
                        } else if (code.contains("wps_find_replace")) {
                             String findText = extractStringArg(code, "findText");
                             String replaceText = extractStringArg(code, "replaceText");
                             result = wpsTools.wps_find_replace(findText, replaceText, true);
                             if (!result.startsWith("Error")) notifyFileChange(conversationId, "Current Document", "MODIFIED");
                        } else if (code.contains("wps_get_outline")) {
                             result = wpsTools.wps_get_outline();
                        } else if (code.contains("wps_set_selection")) {
                             String startStr = extractStringArg(code, "start");
                             String endStr = extractStringArg(code, "end");
                             Integer start = safeParseInt(startStr, "start");
                             Integer end = safeParseInt(endStr, "end");
                             if (start == null || end == null) {
                                  result = "Error: Invalid start/end parameters for set_selection";
                             } else {
                                  result = wpsTools.wps_set_selection(start, end);
                             }
                        } else if (code.contains("wps_replace_selection")) {
                             String text = extractStringArg(code, "text");
                             result = wpsTools.wps_replace_selection(text);
                        } else if (code.contains("wps_goto")) {
                             String type = extractStringArg(code, "type");
                             String target = extractStringArg(code, "target");
                             result = wpsTools.wps_goto(type, target);
                        } else if (code.contains("wps_insert_at_cursor")) {
                             String text = extractStringArg(code, "text");
                             result = wpsTools.wps_insert_at_cursor(text);
                        } else if (code.contains("wps_get_paragraph")) {
                             String indexStr = extractStringArg(code, "paragraphIndex");
                             Integer idx = 1;
                             try { idx = Integer.parseInt(indexStr); } catch (Exception e) {}
                             result = wpsTools.wps_get_paragraph(idx);
                        } else if (code.contains("wps_modify_paragraph")) {
                             String indexStr = extractStringArg(code, "paragraphIndex");
                             String newText = extractStringArg(code, "newText");
                             Integer idx = 1;
                             try { idx = Integer.parseInt(indexStr); } catch (Exception e) {}
                             result = wpsTools.wps_modify_paragraph(idx, newText);
                             if (!result.startsWith("Error")) notifyFileChange(conversationId, "Current Document", "MODIFIED"); // Can't easily get filename
                        } else if (code.contains("wps_insert_under_heading")) {
                             String headingText = extractStringArg(code, "headingText");
                             String insertContent = extractStringArg(code, "content");
                             result = wpsTools.wps_insert_under_heading(headingText, insertContent);
                        } else if (code.contains("wps_replace_nth_match")) {
                             String findText = extractStringArg(code, "findText");
                             String replaceText = extractStringArg(code, "replaceText");
                             String matchIndexStr = extractStringArg(code, "matchIndex");
                             Integer matchIndex = 1;
                             try { matchIndex = Integer.parseInt(matchIndexStr); } catch (Exception e) {}
                             result = wpsTools.wps_replace_nth_match(findText, replaceText, matchIndex);
                        } else if (code.contains("wps_delete_match")) {
                             String findText = extractStringArg(code, "findText");
                             String matchIndexStr = extractStringArg(code, "matchIndex");
                             Integer matchIndex = 1;
                             try { matchIndex = Integer.parseInt(matchIndexStr); } catch (Exception e) {}
                             result = wpsTools.wps_delete_match(findText, matchIndex);
                        } else if (code.contains("wps_delete_text")) {
                             String text = extractStringArg(code, "text");
                             String deleteAllStr = extractStringArg(code, "deleteAll");
                             Boolean deleteAll = deleteAllStr.isEmpty() || "true".equalsIgnoreCase(deleteAllStr);
                             result = wpsTools.wps_delete_text(text, deleteAll);
                        } else if (code.contains("wps_debug_revisions")) {
                             result = wpsTools.wps_debug_revisions();

                         } else {

                            // Try to check if it's a dynamic plugin tool (e.g., plugin_name.method_name or just method_name)
                            String dynamicToolName = parseToolName(code);
                            Object toolObject = pluginService.getPluginTools().get(dynamicToolName);
                            if (toolObject != null) {
                                log.info("Executing dynamic plugin tool via XML: {}", dynamicToolName);
                                // For simplicity, we create a ToolExecutionRequest for the executeDynamicTool method
                                dev.langchain4j.agent.tool.ToolExecutionRequest req = dev.langchain4j.agent.tool.ToolExecutionRequest.builder()
                                        .name(dynamicToolName)
                                        .arguments(extractArgsAsJson(code))
                                        .build();
                                result = executeDynamicTool(toolObject, req, Long.parseLong(projectId), conversationId);
                            } else {
                                // Try to evaluate generalized python-like string?
                                // For safety, only allow listed tools.
                                result = "Unknown tool in custom parser: " + code;
                            }
                         }
                        
                    } catch (Exception e) {
                        result = "Tool Execution Failed: " + e.getMessage();
                    }
                    
                    // Add Result to History
                    // Since this isn't a native tool role, we append a SYSTEM/USER message with the result
                    // formatted as <tool_output> so the model recognizes it.
                    String statusPrefix = result.startsWith("Error") ? "FAILURE" : "SUCCESS";
                    
                    // Enhancement for Write Tools: Append explicit success for file creation/modification
                    // The model often sees JSON IDs (wps_file_id) and thinks it failed or needs to do more.
                    if ("SUCCESS".equals(statusPrefix) && (code.contains("write_docx") || code.contains("write_file") || code.contains("write_"))) {
                         result += "\n\n(System Note: File operation completed successfully.)";
                    }
                    
                    String toolOutputMsg = String.format("<process><tool_output>%s</tool_output></process>", result);
                    
                    // Explicitly tell the model to EVALUATE - with strict anti-over-execution instructions
                    String feedbackMsg = String.format("[System Tool Execution Log]\nTool: %s\nStatus: %s\nOutput: %s\n\n(CRITICAL INSTRUCTION: The tool executed successfully. Now compare with the ORIGINAL user request. If the SPECIFIC task the user asked for is complete, output `<final>` IMMEDIATELY. DO NOT perform additional operations unless the user EXPLICITLY requested them. For example, if user asked to 'delete the 3rd z' and you deleted it, you are DONE - do not delete other z's.)", 
                        code, statusPrefix, result);
                        
                    messages.add(dev.langchain4j.data.message.UserMessage.from(feedbackMsg));
                    
                    // Log for history persistence (include status attribute)
                    // 优先使用LLM选择的process name，保持与实时流式一致
                    String processNameForLog = (llmProcessName != null && !llmProcessName.isEmpty()) 
                        ? llmProcessName 
                        : getToolDisplayName(code);
                    executionLog.append(String.format("<process name=\"%s\"><tool_code>%s</tool_code><tool_output status=\"%s\">%s</tool_output></process>\n",
                        processNameForLog, code, statusPrefix, result));
                    
                    // Emit explicit tool_output for frontend parser with status attribute
                    // NOTE: Do NOT wrap in <process> - the tool_output belongs to the existing process
                    // that contained the tool_code. Wrapping in <process> would create a NEW process
                    // and the frontend wouldn't find the tool item to update.
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
            // Agent 和 Plan 模式：传递工具规格
            // Combine all tool specifications
            List<ToolSpecification> allTools = new ArrayList<>();
            allTools.addAll(ToolSpecifications.toolSpecificationsFrom(legalTools));
            allTools.addAll(ToolSpecifications.toolSpecificationsFrom(webTools));
            allTools.addAll(ToolSpecifications.toolSpecificationsFrom(pythonTools));
            allTools.addAll(ToolSpecifications.toolSpecificationsFrom(memoryTools));
            allTools.addAll(ToolSpecifications.toolSpecificationsFrom(fileTools));
            allTools.addAll(ToolSpecifications.toolSpecificationsFrom(wpsTools));
            allTools.addAll(ToolSpecifications.toolSpecificationsFrom(pptxTools));
            allTools.addAll(ToolSpecifications.toolSpecificationsFrom(pptxEditTools));
            
            // Add Dynamic Plugin Tools
            allTools.addAll(pluginService.getToolSpecifications());
            
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
    
    // Naive extraction from code string like: function(key="value")

    /**
     * 安全解析 Long 类型参数
     */
    private Long safeParseLong(String value, String argName) {
        if (value == null || value.isEmpty()) {
            log.warn("Empty value for argument: {}", argName);
            return null;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            log.warn("Failed to parse {} as Long: {}", argName, value);
            return null;
        }
    }

    /**
     * 安全解析 Integer 类型参数
     */
    private Integer safeParseInt(String value, String argName) {
        if (value == null || value.isEmpty()) {
            log.warn("Empty value for argument: {}", argName);
            return null;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            log.warn("Failed to parse {} as Integer: {}", argName, value);
            return null;
        }
    }

    /**
     * 可空 Boolean 解析（缺省参数保持 null，不误传 false）
     */
    private Boolean safeParseBool(String value) {
        if (value == null || value.isEmpty()) return null;
        return Boolean.valueOf(value.trim());
    }

    /**
     * 可空 Double 解析
     */
    private Double safeParseDouble(String value) {
        if (value == null || value.isEmpty()) return null;
        try {
            return Double.parseDouble(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String extractStringArg(String code, String key) {
        // Extract string argument from code like: function(key="value") or function(key="multi\nline\nvalue")
        // Must handle multi-line content (e.g., markdown_content for write_docx)
        // ALSO handles Python triple-quoted strings: key=""" or key='''
        // NEW: Also handles JSON format: function({"key":"value"})
        try {
            // PRIORITY 0: Check for JSON format (e.g., function({"key":"value"}))
            // This handles cases where LLM generates: wps_find_replace({"findText":"...", "replaceText":"..."})
            int jsonStart = code.indexOf("({");
            int jsonEnd = code.lastIndexOf("})");
            if (jsonStart != -1 && jsonEnd != -1 && jsonEnd > jsonStart) {
                String jsonStr = code.substring(jsonStart + 1, jsonEnd + 1); // Extract {...}
                cn.hutool.json.JSONObject json = cn.hutool.json.JSONUtil.parseObj(jsonStr);
                if (json.containsKey(key)) {
                    return json.getStr(key, "");
                }
            }
            
            // PRIORITY 0.5: Check for <ctrl46> delimiter format
            // LLM sometimes uses <ctrl46> as string delimiter: key:<ctrl46>value<ctrl46>
            // Common pattern: pptx_generate_outline{language:<ctrl46>zh<ctrl46>,topic:<ctrl46>...<ctrl46>}
            String ctrlDelimiter = "<ctrl46>";
            int ctrlKeyStart = code.indexOf(key + ":" + ctrlDelimiter);
            if (ctrlKeyStart == -1) {
                // Also try with equals sign: key=<ctrl46>value<ctrl46>
                ctrlKeyStart = code.indexOf(key + "=" + ctrlDelimiter);
            }
            if (ctrlKeyStart != -1) {
                // Found <ctrl46> delimiter
                String prefix = ctrlKeyStart == code.indexOf(key + ":") ? key + ":" + ctrlDelimiter : key + "=" + ctrlDelimiter;
                int valueStart = ctrlKeyStart + prefix.length();
                int valueEnd = code.indexOf(ctrlDelimiter, valueStart);
                if (valueEnd != -1) {
                    String value = code.substring(valueStart, valueEnd);
                    log.debug("Extracted {} from <ctrl46> format: {}", key, value.length() > 50 ? value.substring(0, 50) + "..." : value);
                    return value;
                }
                // If no closing delimiter, take until next comma or closing brace
                int commaEnd = code.indexOf(",", valueStart);
                int braceEnd = code.indexOf("}", valueStart);
                int end = Math.min(commaEnd != -1 ? commaEnd : Integer.MAX_VALUE, braceEnd != -1 ? braceEnd : Integer.MAX_VALUE);
                if (end != Integer.MAX_VALUE) {
                    return code.substring(valueStart, end).trim();
                }
                return code.substring(valueStart).trim();
            }
            
            // PRIORITY 1: Check for triple-quoted string first (Python style)
            // Pattern: key=""" or key='''
            int tripleDoubleStart = code.indexOf(key + "=\"\"\"");
            int tripleSingleStart = code.indexOf(key + "='''");
            
            if (tripleDoubleStart != -1) {
                // Found triple double quotes
                int valueStart = tripleDoubleStart + key.length() + 4; // skip key="""
                int valueEnd = code.indexOf("\"\"\"", valueStart);
                if (valueEnd != -1) {
                    return code.substring(valueStart, valueEnd);
                }
                // If no closing """, take everything till the end (shouldn't happen)
                return code.substring(valueStart);
            }
            
            if (tripleSingleStart != -1) {
                // Found triple single quotes
                int valueStart = tripleSingleStart + key.length() + 4; // skip key='''
                int valueEnd = code.indexOf("'''", valueStart);
                if (valueEnd != -1) {
                    return code.substring(valueStart, valueEnd);
                }
                return code.substring(valueStart);
            }
            
            // PRIORITY 2: Single quoted string (original logic)
            // Find the start of key="
            int keyStart = code.indexOf(key + "=\"");
            char quoteChar = '"';
            if (keyStart == -1) {
                // Try single quotes
                keyStart = code.indexOf(key + "='");
                quoteChar = '\'';
            }
            
            // PRIORITY 3: Unquoted value (e.g., fileId=10, pageIndex=1)
            // 如果没有找到带引号的参数，尝试解析不带引号的参数（如整数）
            if (keyStart == -1) {
                int unquotedStart = code.indexOf(key + "=");
                if (unquotedStart != -1) {
                    int valueStart = unquotedStart + key.length() + 1;
                    // 检查值开头不是引号（避免误匹配带引号的情况）
                    if (valueStart < code.length()) {
                        char firstChar = code.charAt(valueStart);
                        if (firstChar != '"' && firstChar != '\'') {
                            int valueEnd = valueStart;
                            // 查找结束位置：逗号、右括号、空格或换行
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
            
            // Move to the opening quote
            int valueStart = keyStart + key.length() + 2; // skip key="
            if (valueStart >= code.length()) return "";
            
            // Find the closing quote by scanning character by character
            // Handle escaped quotes
            StringBuilder value = new StringBuilder();
            boolean escaped = false;
            for (int i = valueStart; i < code.length(); i++) {
                char c = code.charAt(i);
                if (escaped) {
                    // Handle common escape sequences
                    if (c == 'n') value.append('\n');
                    else if (c == 't') value.append('\t');
                    else if (c == 'r') value.append('\r');
                    else value.append(c); // handles \\, \", \'
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == quoteChar) {
                    // Found the closing quote
                    return value.toString();
                } else {
                    value.append(c);
                }
            }
            // If we reach here, no closing quote was found - return what we have
            return value.toString();
        } catch (Exception e) {
            log.warn("Failed to extract arg {} from code {}", key, code.length() > 200 ? code.substring(0, 200) + "..." : code);
        }
        return "";
    }
    
    /**
     * Extract Python code from run_python(code='...') or run_python(code="...")
     * Handles multi-line code and escaped quotes.
     */
    private String extractPythonCodeArg(String input) {
        if (input == null) return "";
        
        try {
            // Find the start of run_python(code=
            int codeStart = input.indexOf("run_python(code=");
            if (codeStart == -1) {
                // Try alternative format: run_python(code =
                codeStart = input.indexOf("run_python(code =");
            }
            if (codeStart == -1) return "";
            
            // Move to after "code="
            int quoteStart = input.indexOf('=', codeStart) + 1;
            
            // Skip whitespace
            while (quoteStart < input.length() && Character.isWhitespace(input.charAt(quoteStart))) {
                quoteStart++;
            }
            
            if (quoteStart >= input.length()) return "";
            
            char quoteChar = input.charAt(quoteStart);
            if (quoteChar != '\'' && quoteChar != '"') return "";
            
            // Find the matching closing quote, accounting for escaped quotes
            int quoteEnd = quoteStart + 1;
            boolean escaped = false;
            while (quoteEnd < input.length()) {
                char c = input.charAt(quoteEnd);
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == quoteChar) {
                    // Check if we're at the end: should be followed by ) or whitespace then )
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
            
            if (quoteEnd >= input.length()) return "";
            
            String extracted = input.substring(quoteStart + 1, quoteEnd);
            
            // Unescape common escape sequences
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

    /**
     * 将工具代码转换为用户友好的中文显示名称。
     * 这确保历史对话加载时显示的工具名称与实时流式时一致。
     */
    private String getToolDisplayName(String code) {
        if (code == null || code.isEmpty()) return "工具执行";
        
        // PKULaw 法律工具
        if (code.contains("get_law_article")) return "查询法条";
        if (code.contains("law_search_keyword")) return "关键词搜索法规";
        if (code.contains("law_recognition")) return "法条识别与溯源";
        if (code.contains("law_search")) return "语义搜索法规";
        
        // 网络搜索工具
        if (code.contains("search_web")) return "网络搜索";
        if (code.contains("browse_url")) return "浏览网页";
        
        // 文档工具
        if (code.contains("read_document")) return "读取文档";
        if (code.contains("write_docx")) return "生成Word文档";
        if (code.contains("write_file")) return "写入文件";
        if (code.contains("read_file")) return "读取文件";
        if (code.contains("list_files")) return "列出文件";
        if (code.contains("search_project_files")) return "搜索项目文件";
        if (code.contains("delete_file")) return "删除文件";
        if (code.contains("move_file")) return "移动文件";
        
        // Python 工具
        if (code.contains("run_python")) return "执行Python代码";
        
        // 知识库工具
        if (code.contains("add_memory")) return "添加记忆";
        if (code.contains("query_knowledge_base")) return "查询知识库";
        
        // PPTX 工具
        if (code.contains("pptx_generate(")) return "生成PPT演示文稿";
        if (code.contains("pptx_generate_outline")) return "生成PPT大纲";
        if (code.contains("pptx_check_service")) return "检查PPT服务";
        
        return "工具执行";
    }
    private String parseToolName(String code) {
        if (code == null) return "";
        // e.g., my_plugin.my_tool(arg=...) -> my_tool
        // or my_tool(arg=...) -> my_tool
        int dot = code.indexOf('.');
        int paren = code.indexOf('(');
        if (paren == -1) return code.trim();
        
        if (dot != -1 && dot < paren) {
            return code.substring(dot + 1, paren).trim();
        }
        return code.substring(0, paren).trim();
    }

    private String extractArgsAsJson(String code) {
        // Convert method(k1="v1", k2=123, k3=true) to {"k1":"v1", "k2":123, "k3":true}
        try {
            int start = code.indexOf('(');
            int end = code.lastIndexOf(')');
            if (start == -1 || end == -1 || end <= start) return "{}";
            
            String argsStr = code.substring(start + 1, end).trim();
            if (argsStr.isEmpty()) return "{}";
            
            cn.hutool.json.JSONObject json = new cn.hutool.json.JSONObject();
            
            // 匹配带双引号的字符串值: key="value"
            java.util.regex.Pattern stringPattern = java.util.regex.Pattern.compile("(\\w+)\\s*=\\s*\"([^\"]*)\"");
            java.util.regex.Matcher stringMatcher = stringPattern.matcher(argsStr);
            while (stringMatcher.find()) {
                json.set(stringMatcher.group(1), stringMatcher.group(2));
            }
            
            // 匹配不带引号的数字或布尔值: key=123, key=true, key=false
            java.util.regex.Pattern unquotedPattern = java.util.regex.Pattern.compile("(\\w+)\\s*=\\s*([^,\"\\)]+)");
            java.util.regex.Matcher unquotedMatcher = unquotedPattern.matcher(argsStr);
            while (unquotedMatcher.find()) {
                String key = unquotedMatcher.group(1).trim();
                String value = unquotedMatcher.group(2).trim();
                
                // 跳过已经通过字符串模式匹配的键
                if (json.containsKey(key)) {
                    continue;
                }
                
                // 尝试解析为数字或布尔值
                if ("true".equalsIgnoreCase(value) || "True".equals(value)) {
                    json.set(key, true);
                } else if ("false".equalsIgnoreCase(value) || "False".equals(value)) {
                    json.set(key, false);
                } else {
                    try {
                        // 尝试解析为整数
                        json.set(key, Integer.parseInt(value));
                    } catch (NumberFormatException e1) {
                        try {
                            // 尝试解析为浮点数
                            json.set(key, Double.parseDouble(value));
                        } catch (NumberFormatException e2) {
                            // 保留为字符串
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
