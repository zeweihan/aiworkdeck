package com.checkba.service.ai.memory;

import com.checkba.model.entity.MemoryEntry;
import com.checkba.service.ai.ChatModelFactory;
import com.checkba.service.ai.context.LegalInfoProtector;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * MemCell 自动提取器
 * 借鉴 EverMemOS 的 MemCell 概念，使用 LLM 从对话中自动提取原子级记忆单元
 * 
 * MemCell 特点：
 * 1. 原子性：每个记忆单元具有完整的语义，可独立理解
 * 2. 结构化：包含类型、关键词、内容、重要性等元数据
 * 3. 自动提取：使用 LLM 边界检测，无需手动保存
 */
@Service
public class MemCellExtractor {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(MemCellExtractor.class);

    private final ChatModelFactory chatModelFactory;
    private final LegalInfoProtector legalInfoProtector;
    private final MemoryManager memoryManager;
    // 记忆抽取走辅助模型（便宜档）并落账：这类后台调用此前既没换便宜模型也没记账
    private final com.checkba.service.ai.AuxModelResolver auxModelResolver;
    private final com.checkba.service.ai.TokenUsageService tokenUsageService;

    public MemCellExtractor(ChatModelFactory chatModelFactory, LegalInfoProtector legalInfoProtector,
                            MemoryManager memoryManager,
                            com.checkba.service.ai.AuxModelResolver auxModelResolver,
                            com.checkba.service.ai.TokenUsageService tokenUsageService) {
        this.chatModelFactory = chatModelFactory;
        this.legalInfoProtector = legalInfoProtector;
        this.memoryManager = memoryManager;
        this.auxModelResolver = auxModelResolver;
        this.tokenUsageService = tokenUsageService;
    }

    /**
     * MemCell 提取提示词
     * 指导 LLM 从对话中识别和提取原子级记忆单元
     */
    private static final String MEMCELL_EXTRACTION_PROMPT = """
        你是一个智能记忆提取助手，需要从法律项目对话中提取关键信息作为原子记忆单元（MemCell）。
        
        ## MemCell 定义
        原子记忆单元是具有完整语义、可独立理解的信息片段。每个 MemCell 应该：
        - 包含完整的上下文，无需参考其他内容即可理解
        - 具有明确的类型分类
        - 对项目有长期价值
        
        ## 记忆类型（必须使用以下之一）
        - DECISION: 决策 - 用户或AI做出的重要决定
        - CONCLUSION: 结论 - 核查结论、分析结果
        - FACT: 事实 - 项目相关的客观事实
        - REFERENCE: 法律引用 - 法条、法规引用
        - PREFERENCE: 偏好 - 用户的工作偏好、习惯
        
        ## 提取规则
        1. 每个 MemCell 必须是原子级的（不可再分）
        2. 保留精确的法律引用（法条编号、日期、金额）
        3. 保留当事人完整信息
        4. 对于法律关键信息，标记为 protected=true
        5. 最多提取 10 个最重要的 MemCell
        
        ## 输出格式（严格按此 JSON 格式输出）
        ```json
        {
          "memcells": [
            {
              "type": "DECISION|CONCLUSION|FACT|REFERENCE|PREFERENCE",
              "key": "简短标题（最多15字）",
              "value": "完整的记忆内容",
              "importance": 0.0-1.0,
              "protected": true|false
            }
          ]
        }
        ```
        
        ## 重要性评分标准
        - 1.0: 法律引用、关键金额、关键日期
        - 0.9: 核查结论、重要决策
        - 0.8: 项目关键事实
        - 0.7: 一般性决定
        - 0.6 及以下: 次要信息
        
        如果对话中没有值得提取的信息，返回空数组：{"memcells": []}
        
        ## 对话内容
        %s
        """;

    /**
     * 从对话消息中提取 MemCell
     * 
     * @param projectId 项目ID
     * @param conversationId 对话ID
     * @param messages 对话消息列表
     * @return 提取的 MemCell 列表
     */
    public List<MemoryEntry> extractMemCells(Long projectId, String conversationId,
                                              List<ChatMessage> messages) {
        return extractMemCellsInternal(projectId, conversationId, messages).entries();
    }

    /**
     * 与 {@link #extractMemCells} 相同的抽取流程，额外带上"这一轮 LLM 响应是否解析失败"的信号，
     * 供 {@link #extractAndSave} 把它体现在返回值里。此前 LLM JSON 解析失败与「模型确认无可提取信息」
     * 都会静默退化成同一个空列表，调用方（记忆管线）无从区分——见审计条目。
     */
    private ExtractionOutcome extractMemCellsInternal(Long projectId, String conversationId,
                                                        List<ChatMessage> messages) {
        log.info("MemCell extraction started: projectId={}, conversationId={}, messageCount={}",
                projectId, conversationId, messages.size());

        if (messages == null || messages.isEmpty()) {
            return new ExtractionOutcome(Collections.emptyList(), false);
        }

        // 1. 准备对话内容
        String conversationContent = buildConversationContent(messages);

        // 2. 并行获取法律关键信息（用于补充和验证）
        List<LegalInfoProtector.ProtectedSegment> legalSegments =
                legalInfoProtector.markProtectedInfo(conversationContent);

        // 3. 使用 LLM 提取 MemCell（projectId/conversationId 一路带下去做记账归属）
        LlmOutcome llmOutcome = extractWithLLM(conversationContent, projectId, conversationId);
        List<MemCellData> llmExtracted = llmOutcome.cells();

        // 4. 合并法律关键信息到提取结果
        List<MemCellData> merged = mergeWithLegalInfo(llmExtracted, legalSegments, conversationContent);

        // 5. 转换为 MemoryEntry 并去重
        List<MemoryEntry> entries = convertToMemoryEntries(merged, projectId, conversationId);

        log.info("MemCell extraction completed: extracted {} cells (LLM: {}, Legal: {})",
                entries.size(), llmExtracted.size(),
                merged.size() - llmExtracted.size());

        return new ExtractionOutcome(entries, llmOutcome.parseFailed());
    }

    /** {@link #extractMemCellsInternal} 的返回包装：抽取到的记忆 + LLM 响应是否解析失败。 */
    private record ExtractionOutcome(List<MemoryEntry> entries, boolean llmParseFailed) {}

    /** {@link #extractWithLLM} 的返回包装：解析出的 MemCell + 是否因解析失败而放弃。 */
    private record LlmOutcome(List<MemCellData> cells, boolean parseFailed) {
        static LlmOutcome ok(List<MemCellData> cells) {
            return new LlmOutcome(cells, false);
        }
        static LlmOutcome failed() {
            return new LlmOutcome(Collections.emptyList(), true);
        }
    }

    /**
     * 构建对话内容字符串
     */
    private String buildConversationContent(List<ChatMessage> messages) {
        StringBuilder sb = new StringBuilder();
        int messageCount = 0;
        
        for (ChatMessage msg : messages) {
            String role = "";
            String content = "";
            
            if (msg instanceof UserMessage um) {
                role = "用户";
                // 安全提取：多模态消息 singleText() 会抛异常
                content = um.contents().stream()
                        .filter(dev.langchain4j.data.message.TextContent.class::isInstance)
                        .map(c -> ((dev.langchain4j.data.message.TextContent) c).text())
                        .collect(java.util.stream.Collectors.joining(" "));
            } else if (msg instanceof AiMessage am) {
                role = "助理";
                content = am.text();
            }
            
            if (content != null && !content.isEmpty()) {
                sb.append("[").append(role).append("]: ").append(content).append("\n\n");
                messageCount++;
            }
        }
        
        // 限制输入长度（避免超过 LLM 上下文限制）
        String result = sb.toString();
        if (result.length() > 30000) {
            result = result.substring(0, 30000) + "\n\n[内容已截断...]";
        }
        
        log.debug("Built conversation content: {} messages, {} chars", messageCount, result.length());
        return result;
    }

    /**
     * 使用 LLM 提取 MemCell（辅助模型档，token 落 token_usage）
     */
    private LlmOutcome extractWithLLM(String conversationContent, Long projectId, String conversationId) {
        String responseText;
        try {
            ChatLanguageModel model = chatModelFactory.getAuxChatModel();

            String prompt = String.format(MEMCELL_EXTRACTION_PROMPT, conversationContent);

            var response = model.generate(
                    SystemMessage.from("你是一个专业的法律信息提取助手。"),
                    UserMessage.from(prompt)
            );
            recordUsage(response, projectId, conversationId);

            responseText = response.content().text();
            log.debug("LLM extraction response length: {}", responseText.length());
        } catch (Exception e) {
            log.error("LLM MemCell extraction failed: {}", e.getMessage(), e);
            return LlmOutcome.failed();
        }

        try {
            return LlmOutcome.ok(parseMemCellResponse(responseText));
        } catch (MemCellParseException e) {
            // 与"模型明确认为无可提取信息"（parseMemCellResponse 正常返回空列表）区分开：
            // 这里是响应解析失败，必须在结果里看得出来，不能悄悄退化成同一个空列表。
            log.warn("MemCell JSON 解析失败，本轮不计入「确认无可提取信息」: {}", e.getMessage(), e);
            return LlmOutcome.failed();
        }
    }

    /**
     * 抽取调用的 token 记账。userId 取 {@link com.checkba.service.ai.PlatformAiUserScope}——
     * 本类跑在记忆管线的独立线程池上、签名里没有 userId，而那条入口已建立作用域。
     * 记账失败绝不影响抽取结果。
     */
    private void recordUsage(dev.langchain4j.model.output.Response<AiMessage> response,
                            Long projectId, String conversationId) {
        if (response == null || response.tokenUsage() == null) {
            return;
        }
        try {
            tokenUsageService.recordUsage(projectId,
                    com.checkba.service.ai.PlatformAiUserScope.current(),
                    auxModelResolver.auxModelId(), response.tokenUsage(), conversationId);
        } catch (Exception e) {
            log.warn("MemCell 抽取 token 记账失败（不影响抽取结果）: {}", e.getMessage());
        }
    }

    /**
     * 解析 LLM 返回的 MemCell JSON。
     *
     * <p>解析失败一律抛 {@link MemCellParseException}，绝不返回空列表——空列表只保留给
     * "memcells 是合法空数组"这一种确认为空的情形。此前无论是找不到 JSON、JSON 本身解析出错，
     * 还是 memcells 字段缺失/形态不对（如被截断成对象），全部吞进同一个 catch 里退化成空列表，
     * 调用方（乃至最终的记忆管线）拿到的结果与"这轮对话确实没有可记的东西"完全无法区分。
     *
     * <p>包内可见：供测试直接验证"解析失败"与"确认为空"两条路径互不相同，不必真的打一次 LLM。
     */
    List<MemCellData> parseMemCellResponse(String responseText) {
        // 提取 JSON 块
        Pattern jsonPattern = Pattern.compile("```json\\s*([\\s\\S]*?)\\s*```");
        Matcher matcher = jsonPattern.matcher(responseText);

        String jsonStr = null;
        if (matcher.find()) {
            jsonStr = matcher.group(1);
        } else {
            // 尝试直接解析（如果响应本身就是 JSON）
            int start = responseText.indexOf("{");
            int end = responseText.lastIndexOf("}");
            if (start >= 0 && end > start) {
                jsonStr = responseText.substring(start, end + 1);
            }
        }

        if (jsonStr == null || jsonStr.isEmpty()) {
            // 响应里压根没有花括号包裹的内容：不是"提取到空数组"，是根本没法解析，两者不能等价
            throw new MemCellParseException("LLM 响应中未找到 JSON 内容");
        }

        cn.hutool.json.JSONObject json;
        cn.hutool.json.JSONArray memcellsArray;
        try {
            // 使用简单的 JSON 解析（避免引入额外依赖）
            json = cn.hutool.json.JSONUtil.parseObj(jsonStr);
            memcellsArray = json.getJSONArray("memcells");
        } catch (Exception e) {
            throw new MemCellParseException("MemCell JSON 解析失败: " + e.getMessage(), e);
        }

        if (memcellsArray == null) {
            // memcells 键缺失，或值不是数组形态（例如被截断成 "memcells": {...} 对象）——
            // 同样是解析失败，不能等同于模型显式返回的 {"memcells": []}
            throw new MemCellParseException("memcells 字段缺失或不是数组（可能是截断响应）");
        }

        List<MemCellData> cells = new ArrayList<>();
        for (int i = 0; i < memcellsArray.size(); i++) {
            cn.hutool.json.JSONObject cellObj = memcellsArray.getJSONObject(i);
            MemCellData cell = new MemCellData();
            cell.setType(cellObj.getStr("type", "FACT"));
            cell.setKey(cellObj.getStr("key", ""));
            cell.setValue(cellObj.getStr("value", ""));
            cell.setImportance(cellObj.getDouble("importance", 0.7));
            cell.setProtected(cellObj.getBool("protected", false));

            if (!cell.getValue().isEmpty()) {
                cells.add(cell);
            }
        }

        log.debug("Parsed {} MemCells from LLM response", cells.size());
        return cells;
    }

    /** MemCell JSON 解析失败的信号异常：与"LLM 明确返回空数组"区分开，不是普通的运行时异常。 */
    static final class MemCellParseException extends RuntimeException {
        MemCellParseException(String message) {
            super(message);
        }

        MemCellParseException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * 合并法律关键信息到提取结果
     * 确保法律关键信息不会遗漏
     */
    private List<MemCellData> mergeWithLegalInfo(List<MemCellData> llmCells, 
                                                   List<LegalInfoProtector.ProtectedSegment> legalSegments,
                                                   String content) {
        List<MemCellData> merged = new ArrayList<>(llmCells);
        Set<String> existingValues = new HashSet<>();
        
        // 收集已提取的内容，用于去重
        for (MemCellData cell : llmCells) {
            existingValues.add(cell.getValue().toLowerCase().replaceAll("\\s+", ""));
        }
        
        // 添加法律关键信息（如果未被 LLM 提取）
        for (LegalInfoProtector.ProtectedSegment segment : legalSegments) {
            String normalizedContent = segment.getContent().toLowerCase().replaceAll("\\s+", "");
            
            // 检查是否已存在
            boolean exists = existingValues.stream()
                    .anyMatch(v -> v.contains(normalizedContent) || normalizedContent.contains(v));
            
            if (!exists && segment.getLevel() == LegalInfoProtector.ProtectionLevel.CRITICAL) {
                MemCellData cell = new MemCellData();
                cell.setType("REFERENCE");
                cell.setKey(segment.getType());
                cell.setValue(segment.getContent());
                cell.setImportance(1.0);
                cell.setProtected(true);
                
                merged.add(cell);
                existingValues.add(normalizedContent);
            }
        }
        
        return merged;
    }

    /**
     * 转换为 MemoryEntry
     */
    private List<MemoryEntry> convertToMemoryEntries(List<MemCellData> cells, 
                                                       Long projectId, 
                                                       String conversationId) {
        List<MemoryEntry> entries = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        
        for (MemCellData cell : cells) {
            // 去重
            String fingerprint = cell.getType() + ":" + cell.getValue().hashCode();
            if (seen.contains(fingerprint)) {
                continue;
            }
            seen.add(fingerprint);
            
            // 转换类型
            String memoryType = switch (cell.getType().toUpperCase()) {
                case "DECISION" -> MemoryEntry.MemoryType.DECISION;
                case "CONCLUSION" -> MemoryEntry.MemoryType.CONCLUSION;
                case "FACT" -> MemoryEntry.MemoryType.FACT;
                case "REFERENCE" -> MemoryEntry.MemoryType.REFERENCE;
                case "PREFERENCE" -> MemoryEntry.MemoryType.PREFERENCE;
                default -> MemoryEntry.MemoryType.FACT;
            };
            
            MemoryEntry entry = MemoryEntry.builder()
                    .projectId(projectId)
                    .conversationId(conversationId)
                    .memoryType(memoryType)
                    .memoryKey(cell.getKey())
                    .memoryValue(cell.getValue())
                    .importanceScore(cell.getImportance())
                    .isProtected(cell.isProtected())
                    .build();
            
            entries.add(entry);
        }
        
        return entries;
    }

    /**
     * 批量保存提取的 MemCell（写入前做向量相似度去重，见 MemoryManager.saveMemoryDeduplicated）
     */
    public int saveMemCells(List<MemoryEntry> memCells) {
        int saved = 0;
        for (MemoryEntry entry : memCells) {
            try {
                memoryManager.saveMemoryDeduplicated(entry);
                saved++;
            } catch (Exception e) {
                log.error("Failed to save MemCell: {}", e.getMessage());
            }
        }
        log.info("Saved {} MemCells to memory", saved);
        return saved;
    }

    /**
     * 一站式提取并保存 MemCell。
     *
     * @return 实际保存的条数；{@code 0} = 正常跑完但确实没有可提取/可保存的内容；
     *         {@code -1} = 本轮 LLM 响应解析失败（不是"确认为空"，调用方不应当作与 0 相同的信号处理）。
     */
    public int extractAndSave(Long projectId, String conversationId, List<ChatMessage> messages) {
        ExtractionOutcome outcome = extractMemCellsInternal(projectId, conversationId, messages);
        int saved = saveMemCells(outcome.entries());
        return saved == 0 && outcome.llmParseFailed() ? -1 : saved;
    }

    /**
     * MemCell 数据类
     */
    /**
     * MemCell 数据类
     */
    public static class MemCellData {
        private String type;
        private String key;
        private String value;
        private double importance;
        private boolean isProtected;

        public MemCellData() {
            this.type = "FACT";
            this.key = "";
            this.value = "";
            this.importance = 0.7;
            this.isProtected = false;
        }

        public MemCellData(String type, String key, String value, double importance, boolean isProtected) {
            this.type = type;
            this.key = key;
            this.value = value;
            this.importance = importance;
            this.isProtected = isProtected;
        }

        public String getType() { return type; }
        public void setType(String type) { this.type = type; }
        public String getKey() { return key; }
        public void setKey(String key) { this.key = key; }
        public String getValue() { return value; }
        public void setValue(String value) { this.value = value; }
        public double getImportance() { return importance; }
        public void setImportance(double importance) { this.importance = importance; }
        public boolean isProtected() { return isProtected; }
        public void setProtected(boolean isProtected) { this.isProtected = isProtected; }
    }
}

