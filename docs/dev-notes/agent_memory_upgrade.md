# AI Agent 记忆与上下文处理升级计划

## 一、升级背景与目标

### 1.1 背景分析

当前系统的 AI Agent 在上下文处理方面存在以下问题：

1. **上下文长度限制**：
   - 多轮对话后，历史消息累积导致上下文过长
   - 超出模型 token 限制时，简单截断可能丢失关键信息
   - 目前通过 `ContextAssemblerService` 组装上下文，但缺乏智能压缩

2. **记忆能力不足**：
   - 只有简单的 `MemoryTools`（add_memory/query_knowledge_base）
   - 缺乏结构化的长期记忆存储
   - 跨会话记忆能力有限

3. **法律领域特殊需求**：
   - 法律文书中的关键信息（日期、金额、当事人）绝对不能丢失
   - 法条引用必须准确完整
   - 项目上下文（交易结构、关联方关系）需要长期保持

### 1.2 升级目标

1. **智能上下文管理**：
   - 实现上下文自动压缩，在保留关键信息的前提下减少 token 使用
   - 针对法律领域设计特殊的压缩策略，确保关键信息不丢失

2. **结构化记忆系统**：
   - 项目级记忆：项目基本信息、交易结构、关联方等
   - 会话级记忆：当前对话的关键决策和结论
   - 用户级记忆：用户偏好、常用表达等

3. **知识库增强**：
   - 项目文档的向量化检索
   - 核查结论和底稿的结构化存储与召回

---

## 二、技术方案概述

### 2.1 整体架构

```
┌─────────────────────────────────────────────────────────────────────────┐
│                           AI Agent 记忆层                                │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                          │
│  ┌──────────────────┐  ┌──────────────────┐  ┌──────────────────────┐   │
│  │   短期记忆        │  │   中期记忆        │  │   长期记忆            │   │
│  │   (Session)      │  │   (Conversation) │  │   (Project/User)     │   │
│  │                  │  │                  │  │                      │   │
│  │  • 当前对话消息   │  │  • 对话摘要       │  │  • 项目核心信息       │   │
│  │  • 工具调用结果   │  │  • 关键决策点     │  │  • 用户偏好          │   │
│  │  • 临时上下文     │  │  • 重要结论       │  │  • 知识库索引        │   │
│  │                  │  │  • 待办事项       │  │  • 历史交互模式       │   │
│  └────────┬─────────┘  └────────┬─────────┘  └──────────┬───────────┘   │
│           │                     │                        │               │
│           └─────────────────────┼────────────────────────┘               │
│                                 │                                        │
│                    ┌────────────▼────────────┐                          │
│                    │   上下文组装器           │                          │
│                    │   ContextAssembler       │                          │
│                    │                          │                          │
│                    │  • 记忆选择与融合         │                          │
│                    │  • 上下文压缩             │                          │
│                    │  • Token 预算管理         │                          │
│                    │  • 法律信息优先保护       │                          │
│                    └────────────┬────────────┘                          │
│                                 │                                        │
│                    ┌────────────▼────────────┐                          │
│                    │   最终上下文              │                          │
│                    │   → 发送给 LLM           │                          │
│                    └─────────────────────────┘                          │
│                                                                          │
└─────────────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────────────┐
│                           存储层                                         │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                          │
│  ┌──────────────────┐  ┌──────────────────┐  ┌──────────────────────┐   │
│  │   PostgreSQL     │  │   向量数据库       │  │   Redis (可选)        │   │
│  │                  │  │   (Milvus/PG)    │  │                      │   │
│  │  • 消息历史      │  │                  │  │  • 会话状态缓存      │   │
│  │  • 记忆条目      │  │  • 文档向量       │  │  • 热点记忆         │   │
│  │  • 摘要存储      │  │  • 记忆向量       │  │  • Token 计数缓存   │   │
│  │  • 项目配置      │  │  • 语义检索       │  │                      │   │
│  └──────────────────┘  └──────────────────┘  └──────────────────────┘   │
│                                                                          │
└─────────────────────────────────────────────────────────────────────────┘
```

### 2.2 核心组件

1. **MemoryManager（记忆管理器）**
   - 统一管理三层记忆的读写
   - 负责记忆的创建、更新、检索、淘汰

2. **ContextCompressor（上下文压缩器）**
   - 实现智能摘要生成
   - 法律信息保护机制
   - Token 预算分配

3. **MemoryRetriever（记忆检索器）**
   - 基于相关性检索相关记忆
   - 支持混合检索（关键词 + 向量）

4. **LegalInfoExtractor（法律信息提取器）**
   - 识别并标记法律关键信息
   - 确保这些信息在压缩中被保护

---

## 三、记忆系统设计

### 3.1 记忆类型定义

#### 3.1.1 短期记忆（Session Memory）

存储当前对话的即时信息，生命周期为单次对话会话：

```java
public class SessionMemory {
    private String conversationId;
    private List<ChatMessage> recentMessages;      // 最近 N 条消息
    private Map<String, Object> toolResults;       // 工具调用结果
    private Set<String> mentionedEntities;         // 提及的实体
    private String currentTaskContext;             // 当前任务上下文
    private int totalTokens;                       // 当前 token 计数
}
```

#### 3.1.2 中期记忆（Conversation Memory）

存储对话级别的重要信息，跨消息但同一对话内有效：

```java
public class ConversationMemory {
    private String conversationId;
    private String conversationSummary;            // 对话摘要
    private List<KeyDecision> keyDecisions;        // 关键决策点
    private List<ImportantConclusion> conclusions; // 重要结论
    private List<PendingTask> pendingTasks;        // 待办事项
    private LocalDateTime lastUpdated;
}

public class KeyDecision {
    private String decision;           // 决策内容
    private String reasoning;          // 决策理由
    private String userConfirmation;   // 用户确认状态
    private LocalDateTime timestamp;
}
```

#### 3.1.3 长期记忆（Long-term Memory）

项目级和用户级的持久化记忆：

```java
// 项目记忆
public class ProjectMemory {
    private Long projectId;
    private ProjectCoreInfo coreInfo;              // 项目核心信息
    private List<TransactionParty> parties;        // 交易各方
    private Map<String, String> keyVariables;      // 关键变量
    private List<LegalReference> legalRefs;        // 法律引用
    private List<CheckConclusion> checkConclusions;// 核查结论
}

public class ProjectCoreInfo {
    private String projectName;
    private String projectType;                    // 重大资产重组/再融资等
    private String listedCompany;                  // 上市公司
    private String targetCompany;                  // 标的公司
    private String transactionStructure;           // 交易结构描述
    private BigDecimal transactionAmount;          // 交易金额
    private LocalDate keyDates;                    // 关键日期
}

// 用户记忆
public class UserMemory {
    private Long userId;
    private Map<String, String> preferences;       // 用户偏好
    private List<String> frequentPhrases;          // 常用表达
    private Map<String, Integer> toolUsageStats;   // 工具使用统计
}
```

### 3.2 数据库设计

#### 3.2.1 记忆表结构

```sql
-- 对话摘要表
CREATE TABLE conversation_summary (
    id BIGSERIAL PRIMARY KEY,
    conversation_id VARCHAR(100) NOT NULL,
    project_id BIGINT,
    user_id BIGINT,
    summary_text TEXT NOT NULL,
    key_points JSONB,                    -- 关键点列表
    legal_references JSONB,              -- 法律引用
    mentioned_entities JSONB,            -- 提及的实体
    token_count INTEGER,
    created_at TIMESTAMP DEFAULT NOW(),
    updated_at TIMESTAMP DEFAULT NOW(),
    UNIQUE(conversation_id)
);

-- 结构化记忆表
CREATE TABLE memory_entry (
    id BIGSERIAL PRIMARY KEY,
    project_id BIGINT,
    user_id BIGINT,
    conversation_id VARCHAR(100),
    memory_type VARCHAR(50) NOT NULL,    -- decision/conclusion/fact/preference
    memory_key VARCHAR(200),
    memory_value TEXT NOT NULL,
    metadata JSONB,
    importance_score FLOAT DEFAULT 0.5,  -- 重要性分数 0-1
    is_protected BOOLEAN DEFAULT FALSE,  -- 是否受保护（法律关键信息）
    expires_at TIMESTAMP,                -- 过期时间（可选）
    created_at TIMESTAMP DEFAULT NOW(),
    INDEX idx_memory_project (project_id),
    INDEX idx_memory_type (memory_type),
    INDEX idx_memory_key (memory_key)
);

-- 记忆向量表（用于语义检索）
CREATE TABLE memory_embedding (
    id BIGSERIAL PRIMARY KEY,
    memory_entry_id BIGINT REFERENCES memory_entry(id),
    embedding VECTOR(1536),              -- OpenAI embedding 维度
    created_at TIMESTAMP DEFAULT NOW()
);

-- 创建向量索引（使用 pgvector）
CREATE INDEX ON memory_embedding USING ivfflat (embedding vector_cosine_ops);
```

### 3.3 记忆生命周期管理

```
┌─────────────────────────────────────────────────────────────────┐
│                      记忆生命周期                                │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│   用户消息 ──┬──▶ 提取实体/关键词 ──▶ 更新短期记忆               │
│              │                                                   │
│              └──▶ 检查记忆触发条件 ──▶ 是否需要摘要？            │
│                         │                                        │
│                         ▼ 是                                     │
│              ┌──────────────────────┐                           │
│              │  生成对话摘要         │                           │
│              │  • 压缩历史消息       │                           │
│              │  • 提取关键决策       │                           │
│              │  • 保护法律信息       │                           │
│              └──────────┬───────────┘                           │
│                         │                                        │
│                         ▼                                        │
│              ┌──────────────────────┐                           │
│              │  更新中期记忆         │                           │
│              │  • 存储摘要           │                           │
│              │  • 更新关键点列表     │                           │
│              └──────────┬───────────┘                           │
│                         │                                        │
│                         ▼                                        │
│              ┌──────────────────────┐                           │
│              │  评估长期记忆更新     │                           │
│              │  • 新的项目信息？     │                           │
│              │  • 用户偏好变化？     │                           │
│              │  • 重要结论确认？     │                           │
│              └──────────────────────┘                           │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

---

## 四、上下文压缩策略

### 4.1 压缩时机

触发上下文压缩的条件：

1. **Token 阈值触发**：当上下文 token 数超过模型限制的 70%
2. **消息数量触发**：当对话消息数超过 20 条
3. **时间间隔触发**：对话间隔超过 30 分钟后继续

### 4.2 压缩算法

#### 4.2.1 分层压缩策略

```java
public class ContextCompressor {
    
    private final int MAX_CONTEXT_TOKENS = 100000; // 以 GPT-4 128K 为例
    private final int SYSTEM_PROMPT_RESERVE = 8000;
    private final int RESPONSE_RESERVE = 4000;
    private final int AVAILABLE_TOKENS = MAX_CONTEXT_TOKENS - SYSTEM_PROMPT_RESERVE - RESPONSE_RESERVE;
    
    public CompressedContext compress(List<ChatMessage> messages, 
                                       ProjectMemory projectMemory,
                                       ConversationMemory convMemory) {
        
        int currentTokens = countTokens(messages);
        
        if (currentTokens <= AVAILABLE_TOKENS) {
            // 无需压缩
            return new CompressedContext(messages, projectMemory.toCoreContext());
        }
        
        // 第一层：移除冗余信息
        messages = removeRedundancy(messages);
        
        // 第二层：摘要旧消息，保留最近消息
        if (countTokens(messages) > AVAILABLE_TOKENS) {
            messages = summarizeOldMessages(messages, convMemory);
        }
        
        // 第三层：压缩工具调用结果
        if (countTokens(messages) > AVAILABLE_TOKENS) {
            messages = compressToolResults(messages);
        }
        
        // 第四层：激进压缩（保留核心）
        if (countTokens(messages) > AVAILABLE_TOKENS) {
            messages = aggressiveCompress(messages, projectMemory);
        }
        
        return new CompressedContext(messages, projectMemory.toCoreContext());
    }
}
```

#### 4.2.2 法律信息保护机制

```java
public class LegalInfoProtector {
    
    // 需要保护的法律信息类型
    private static final List<String> PROTECTED_PATTERNS = Arrays.asList(
        "《.*?》",                          // 法律法规名称
        "第[一二三四五六七八九十百千]+条",   // 法条编号
        "\\d{4}年\\d{1,2}月\\d{1,2}日",     // 日期
        "[\\d,]+\\.?\\d*[万亿元]",          // 金额
        "统一社会信用代码[：:].+",          // 信用代码
        "(甲方|乙方|丙方|丁方)[：:].*"       // 当事人
    );
    
    /**
     * 标记受保护的信息
     */
    public ProtectedContent markProtectedInfo(String content) {
        List<ProtectedSegment> segments = new ArrayList<>();
        
        for (String pattern : PROTECTED_PATTERNS) {
            Matcher matcher = Pattern.compile(pattern).matcher(content);
            while (matcher.find()) {
                segments.add(new ProtectedSegment(
                    matcher.start(),
                    matcher.end(),
                    matcher.group(),
                    ProtectionLevel.CRITICAL
                ));
            }
        }
        
        return new ProtectedContent(content, segments);
    }
    
    /**
     * 安全压缩：确保受保护信息不被丢失
     */
    public String safeCompress(String content, int targetLength) {
        ProtectedContent protected = markProtectedInfo(content);
        
        // 计算受保护内容的长度
        int protectedLength = protected.getSegments().stream()
            .mapToInt(s -> s.getContent().length())
            .sum();
        
        if (protectedLength >= targetLength) {
            // 受保护内容已超过目标长度，只保留受保护内容
            return protected.getSegments().stream()
                .map(ProtectedSegment::getContent)
                .collect(Collectors.joining("\n"));
        }
        
        // 压缩非保护内容
        int availableForOther = targetLength - protectedLength;
        return compressWithProtection(content, protected.getSegments(), availableForOther);
    }
}
```

### 4.3 摘要生成

使用 LLM 生成智能摘要，针对法律领域定制 prompt：

```java
public class ConversationSummarizer {
    
    private static final String SUMMARY_PROMPT = """
        你是一个法律项目助理，需要为以下对话生成摘要。
        
        ## 要求
        1. 保留所有法律引用（法律法规名称、条款编号）
        2. 保留所有关键数字（日期、金额、比例、期限）
        3. 保留所有当事人信息（公司名称、自然人姓名）
        4. 保留所有重要决策和结论
        5. 摘要长度控制在 500 字以内
        
        ## 输出格式
        ```
        【项目概况】
        简述项目基本情况
        
        【关键信息】
        - 当事人: ...
        - 交易金额: ...
        - 关键日期: ...
        
        【讨论要点】
        1. ...
        2. ...
        
        【决策/结论】
        - ...
        
        【待办事项】
        - ...
        ```
        
        ## 对话内容
        %s
        """;
    
    public ConversationSummary generateSummary(List<ChatMessage> messages) {
        String content = formatMessages(messages);
        String prompt = String.format(SUMMARY_PROMPT, content);
        
        String summaryText = llmClient.generate(prompt);
        
        return parseSummary(summaryText);
    }
}
```

---

## 五、实现步骤

### 第一阶段：基础记忆能力（2周）

#### 5.1 Week 1：数据模型与存储

1. **创建数据库表**
   - conversation_summary 表
   - memory_entry 表
   - memory_embedding 表（如使用 pgvector）

2. **实现实体类**
   - SessionMemory
   - ConversationMemory
   - ProjectMemory
   - MemoryEntry

3. **实现 Repository 层**
   - MemoryEntryRepository
   - ConversationSummaryRepository

#### 5.2 Week 2：记忆管理服务

1. **MemoryManagerService**
   ```java
   public interface MemoryManager {
       void saveMemory(MemoryEntry entry);
       List<MemoryEntry> retrieveMemories(Long projectId, String query, int limit);
       void updateConversationSummary(String conversationId, String summary);
       ConversationSummary getConversationSummary(String conversationId);
       ProjectMemory getProjectMemory(Long projectId);
   }
   ```

2. **集成到 ContextAssemblerService**
   - 在组装上下文时加入记忆检索
   - 支持记忆与当前对话的融合

### 第二阶段：上下文压缩（2周）

#### 5.3 Week 3：压缩器实现

1. **ContextCompressor**
   - 实现分层压缩策略
   - Token 计数与预算管理

2. **LegalInfoProtector**
   - 法律信息识别
   - 保护机制实现

3. **ConversationSummarizer**
   - 摘要生成 prompt 设计
   - 摘要解析与存储

#### 5.4 Week 4：压缩集成与测试

1. **集成到 AgentOrchestrator**
   - 在 runLoop 中加入压缩检查
   - 自动触发压缩

2. **测试与调优**
   - 不同长度对话测试
   - 法律信息保留率测试
   - 压缩前后质量对比

### 第三阶段：高级记忆功能（2周）

#### 5.5 Week 5：向量检索

1. **向量化存储**
   - 集成 embedding 模型
   - 实现记忆向量化

2. **语义检索**
   - 实现相似度检索
   - 混合检索（关键词 + 向量）

#### 5.6 Week 6：项目级记忆

1. **ProjectMemoryExtractor**
   - 从对话中自动提取项目信息
   - 更新项目记忆

2. **记忆工具增强**
   - 增强 MemoryTools
   - 支持结构化记忆查询

### 第四阶段：优化与稳定（1周）

#### 5.7 Week 7：优化

1. **性能优化**
   - 缓存热点记忆
   - 异步压缩

2. **监控与可观测性**
   - 记忆使用统计
   - 压缩效果监控

---

## 六、MemoryTools 增强设计

### 6.1 现有工具分析

当前 `MemoryTools.java` 提供：
- `add_memory(key, value)` - 添加记忆
- `query_knowledge_base(query)` - 查询知识库

### 6.2 增强后的工具定义

```java
package com.checkba.service.ai.tools;

import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.P;

@Component
@RequiredArgsConstructor
public class MemoryTools {

    private final MemoryManager memoryManager;
    private final ProjectMemoryExtractor projectMemoryExtractor;

    /**
     * 保存结构化记忆
     */
    @Tool(value = "保存重要信息到项目记忆中，包括关键决策、结论、事实等")
    public String save_memory(
        @P("记忆类型: decision(决策)/conclusion(结论)/fact(事实)/reference(引用)") String type,
        @P("记忆标题或关键词") String key,
        @P("记忆内容") String value,
        @P("是否为法律关键信息，需要特别保护") boolean isProtected
    ) {
        MemoryEntry entry = MemoryEntry.builder()
            .projectId(ProjectContextHolder.getProjectId())
            .memoryType(type)
            .memoryKey(key)
            .memoryValue(value)
            .isProtected(isProtected)
            .importanceScore(isProtected ? 1.0 : 0.7)
            .build();
        
        memoryManager.saveMemory(entry);
        return "记忆已保存: " + key;
    }

    /**
     * 查询项目记忆
     */
    @Tool(value = "查询项目相关的记忆信息，支持按类型和关键词检索")
    public String query_memory(
        @P("查询关键词") String query,
        @P("记忆类型过滤(可选): decision/conclusion/fact/reference/all") String type
    ) {
        Long projectId = ProjectContextHolder.getProjectId();
        List<MemoryEntry> memories = memoryManager.retrieveMemories(
            projectId, query, type.equals("all") ? null : type, 10
        );
        
        if (memories.isEmpty()) {
            return "未找到相关记忆";
        }
        
        return formatMemories(memories);
    }

    /**
     * 获取项目核心信息
     */
    @Tool(value = "获取当前项目的核心信息，包括项目类型、交易结构、当事人等")
    public String get_project_context() {
        Long projectId = ProjectContextHolder.getProjectId();
        ProjectMemory pm = memoryManager.getProjectMemory(projectId);
        
        if (pm == null) {
            return "项目记忆尚未建立";
        }
        
        return pm.toContextString();
    }

    /**
     * 更新项目信息
     */
    @Tool(value = "更新项目的核心信息，如交易金额、关键日期等")
    public String update_project_info(
        @P("要更新的字段: transactionAmount/keyDate/transactionStructure/parties") String field,
        @P("新的值") String value
    ) {
        Long projectId = ProjectContextHolder.getProjectId();
        projectMemoryExtractor.updateProjectField(projectId, field, value);
        return "项目信息已更新: " + field + " = " + value;
    }

    /**
     * 查询知识库（向量检索）
     */
    @Tool(value = "在项目知识库中进行语义搜索，查找相关的文档、核查结论或历史记录")
    public String search_knowledge_base(
        @P("搜索查询") String query,
        @P("返回结果数量，默认5") int limit
    ) {
        Long projectId = ProjectContextHolder.getProjectId();
        List<KnowledgeItem> results = memoryManager.semanticSearch(projectId, query, limit);
        
        return formatKnowledgeResults(results);
    }

    /**
     * 获取对话摘要
     */
    @Tool(value = "获取当前或指定对话的历史摘要")
    public String get_conversation_summary(
        @P("对话ID，留空则获取当前对话") String conversationId
    ) {
        if (conversationId == null || conversationId.isEmpty()) {
            conversationId = ConversationContextHolder.getConversationId();
        }
        
        ConversationSummary summary = memoryManager.getConversationSummary(conversationId);
        
        if (summary == null) {
            return "该对话暂无摘要";
        }
        
        return summary.toFormattedString();
    }
}
```

---

## 七、ContextAssemblerService 增强

### 7.1 现有实现分析

当前 `ContextAssemblerService` 负责：
- 组装系统提示词
- 加载历史消息
- 注入项目上下文

### 7.2 增强设计

```java
@Service
@RequiredArgsConstructor
public class ContextAssemblerService {

    private final ProjectAiMessageService messageService;
    private final MemoryManager memoryManager;
    private final ContextCompressor contextCompressor;
    private final LegalInfoProtector legalInfoProtector;
    
    // Token 预算配置
    private static final int MAX_CONTEXT_TOKENS = 100000;
    private static final int SYSTEM_PROMPT_BUDGET = 8000;
    private static final int MEMORY_BUDGET = 5000;
    private static final int HISTORY_BUDGET = 80000;
    private static final int RESPONSE_RESERVE = 7000;

    public List<ChatMessage> assembleContext(
            String conversationId,
            Long projectId,
            String userMessage,
            String modelId) {
        
        List<ChatMessage> messages = new ArrayList<>();
        
        // 1. 系统提示词（固定预算）
        String systemPrompt = loadSystemPrompt(projectId);
        messages.add(new SystemMessage(systemPrompt));
        
        // 2. 项目核心记忆（固定预算）
        ProjectMemory projectMemory = memoryManager.getProjectMemory(projectId);
        if (projectMemory != null) {
            messages.add(new SystemMessage("[项目上下文]\n" + projectMemory.toCoreContext()));
        }
        
        // 3. 相关长期记忆（动态检索）
        List<MemoryEntry> relevantMemories = memoryManager.retrieveMemories(
            projectId, userMessage, null, 5
        );
        if (!relevantMemories.isEmpty()) {
            String memoryContext = formatMemoriesForContext(relevantMemories);
            messages.add(new SystemMessage("[相关记忆]\n" + memoryContext));
        }
        
        // 4. 对话历史（可能需要压缩）
        List<ProjectAiMessage> history = messageService.listByConversationId(conversationId);
        
        int currentTokens = countTokens(messages);
        int availableForHistory = MAX_CONTEXT_TOKENS - currentTokens - RESPONSE_RESERVE;
        
        List<ChatMessage> historyMessages = convertToMessages(history);
        int historyTokens = countTokens(historyMessages);
        
        if (historyTokens > availableForHistory) {
            // 需要压缩
            historyMessages = contextCompressor.compress(
                historyMessages, 
                projectMemory,
                memoryManager.getConversationSummary(conversationId),
                availableForHistory
            );
        }
        
        messages.addAll(historyMessages);
        
        // 5. 当前用户消息
        messages.add(new UserMessage(userMessage));
        
        // 记录 token 使用情况
        logTokenUsage(conversationId, messages);
        
        return messages;
    }
    
    /**
     * 对话结束后触发的记忆更新
     */
    public void postConversationUpdate(String conversationId, List<ChatMessage> messages) {
        // 检查是否需要生成新摘要
        if (shouldGenerateSummary(messages)) {
            String summary = contextCompressor.generateSummary(messages);
            memoryManager.updateConversationSummary(conversationId, summary);
        }
        
        // 提取并存储重要记忆
        extractAndSaveMemories(conversationId, messages);
    }
}
```

---

## 八、法律领域特殊考虑

### 8.1 需要保护的信息类型

| 类别 | 示例 | 保护级别 |
|------|------|----------|
| 法律法规引用 | 《公司法》第十六条 | Critical |
| 日期 | 2024年1月15日 | Critical |
| 金额 | 10.5亿元 | Critical |
| 当事人 | 甲方：XX有限公司 | Critical |
| 证件号码 | 统一社会信用代码：91... | High |
| 合同编号 | 合同编号：HT-2024-001 | High |
| 核查结论 | 经核查，标的资产权属清晰 | Medium |
| 交易结构 | 发行股份购买资产 | Medium |

### 8.2 压缩策略调整

针对法律领域，压缩策略需要调整：

1. **优先级排序**：法律关键信息 > 决策结论 > 讨论过程 > 寒暄内容
2. **保护机制**：标记为 `isProtected` 的内容不参与压缩
3. **引用完整性**：法条引用必须完整，不能截断

### 8.3 错误处理

```java
public class LegalSafetyCheck {
    
    /**
     * 验证压缩后的内容是否安全
     */
    public CompressionValidation validate(String original, String compressed) {
        List<String> originalLegalRefs = extractLegalReferences(original);
        List<String> compressedLegalRefs = extractLegalReferences(compressed);
        
        List<String> missing = originalLegalRefs.stream()
            .filter(ref -> !compressedLegalRefs.contains(ref))
            .collect(Collectors.toList());
        
        if (!missing.isEmpty()) {
            return CompressionValidation.failed(
                "压缩后丢失了以下法律引用: " + String.join(", ", missing)
            );
        }
        
        // 验证金额是否保留
        List<String> originalAmounts = extractAmounts(original);
        List<String> compressedAmounts = extractAmounts(compressed);
        
        // ... 其他验证
        
        return CompressionValidation.success();
    }
}
```

---

## 九、监控与可观测性

### 9.1 记忆系统指标

```java
@Component
public class MemoryMetrics {
    
    private final MeterRegistry registry;
    
    // 记忆条目数量
    private final AtomicLong totalMemoryEntries = new AtomicLong();
    
    // 压缩次数
    private final Counter compressionCounter;
    
    // 压缩比率
    private final DistributionSummary compressionRatio;
    
    // 记忆检索延迟
    private final Timer retrievalTimer;
    
    public void recordCompression(int originalTokens, int compressedTokens) {
        compressionCounter.increment();
        double ratio = (double) compressedTokens / originalTokens;
        compressionRatio.record(ratio);
    }
    
    public void recordRetrieval(long durationMs, int resultsCount) {
        retrievalTimer.record(durationMs, TimeUnit.MILLISECONDS);
    }
}
```

### 9.2 日志记录

```java
// 记忆操作日志
log.info("Memory saved: project={}, type={}, key={}, protected={}", 
    projectId, entry.getMemoryType(), entry.getMemoryKey(), entry.isProtected());

// 压缩日志
log.info("Context compressed: conversation={}, original={} tokens, compressed={} tokens, ratio={}%",
    conversationId, originalTokens, compressedTokens, 
    String.format("%.1f", (double) compressedTokens / originalTokens * 100));

// 法律信息保护日志
log.info("Legal info protected: {} references, {} amounts, {} dates",
    protectedRefs.size(), protectedAmounts.size(), protectedDates.size());
```

---

## 十、与 EverMemOS 的对比与借鉴

### 10.1 EverMemOS 核心思想

根据 [EverMemOS](https://github.com/EverMind-AI/EverMemOS) 项目：

1. **Episode Memory（情景记忆）**：存储对话片段
2. **Event Log（事件日志）**：结构化的事件记录
3. **Foresight（前瞻）**：基于历史的预测
4. **Profile（画像）**：用户/实体画像

### 10.2 我们的适配

| EverMemOS 概念 | 我们的实现 | 说明 |
|---------------|-----------|------|
| Episode | ConversationSummary | 对话摘要 |
| Event Log | MemoryEntry (type=decision/conclusion) | 结构化记忆 |
| Foresight | - | 暂不实现，法律领域需谨慎预测 |
| Profile | ProjectMemory + UserMemory | 项目和用户画像 |

### 10.3 为什么不直接使用 EverMemOS

1. **语言差异**：EverMemOS 是 Python 实现，我们后端是 Java/Spring Boot
2. **领域适配**：法律领域需要特殊的信息保护机制
3. **集成成本**：独立部署增加运维复杂度
4. **定制需求**：需要与现有项目、核查、文书系统深度集成

但我们借鉴了其核心思想：**分层记忆 + 智能检索 + 上下文压缩**。

---

## 十一、验收标准

### 11.1 功能验收

- [ ] 能够自动压缩超长对话历史
- [ ] 压缩后保留所有法律关键信息
- [ ] 支持项目级记忆的存储和检索
- [ ] 支持对话摘要的自动生成
- [ ] 增强版 MemoryTools 正常工作
- [ ] 语义检索返回相关结果

### 11.2 性能验收

- [ ] 压缩操作 < 3秒
- [ ] 记忆检索 < 500ms
- [ ] 支持 100+ 轮对话的压缩
- [ ] 压缩率达到 50% 以上（非法律关键信息）

### 11.3 质量验收

- [ ] 法律引用保留率 100%
- [ ] 关键日期保留率 100%
- [ ] 关键金额保留率 100%
- [ ] 压缩后对话质量无明显下降

---

## 十二、风险与注意事项

### 12.1 技术风险

| 风险 | 影响 | 缓解措施 |
|------|------|----------|
| 压缩导致信息丢失 | 法律判断错误 | 法律信息保护机制 + 验证检查 |
| 向量检索不准确 | 召回无关记忆 | 混合检索 + 相关性阈值 |
| Token 计数不准确 | 超出模型限制 | 使用 tiktoken 精确计数 |
| 并发更新冲突 | 记忆不一致 | 乐观锁 + 事务控制 |

### 12.2 法律领域风险

1. **信息遗漏**：压缩时可能遗漏关键法律信息
   - 缓解：严格的保护标记 + 压缩后验证

2. **引用不完整**：法条引用被截断
   - 缓解：法律引用作为整体，不可分割

3. **上下文混淆**：不同项目的记忆混淆
   - 缓解：严格的项目隔离 + projectId 检查

### 12.3 性能风险

1. **压缩延迟**：LLM 生成摘要较慢
   - 缓解：异步压缩 + 预热压缩

2. **向量检索慢**：记忆量大时检索变慢
   - 缓解：向量索引优化 + 分区

---

## 十三、参考资料

1. EverMemOS 项目：https://github.com/EverMind-AI/EverMemOS
2. LangChain Memory：https://python.langchain.com/docs/modules/memory/
3. LangChain4j 文档：https://docs.langchain4j.dev/
4. 项目现有文档：
   - `/docs/ai_agent_dev.md` - AI Agent 架构与开发规范
   - `/docs/s0-prd.md` - 产品需求文档
5. 项目现有代码：
   - `ContextAssemblerService.java` - 上下文组装服务
   - `MemoryTools.java` - 现有记忆工具
   - `ProjectAiMessageService.java` - 消息服务





