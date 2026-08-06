package com.checkba.service.telemetry;

import com.checkba.service.ai.ChatModelFactory;
import dev.langchain4j.model.chat.ChatLanguageModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 法律事项类型 AI 兜底分类（设计 §5.6）。
 *
 * 触发条件（全部满足才运行）：会话首轮、未命中 skill（skill 命中时 SkillRouter
 * 直接产出 matter.classified）、「分享匿名使用统计」开关开启（分类消耗用户 token，
 * 开关关闭时不得产生成本）。
 *
 * 隐私：用户消息只在内存中送入模型做单标签分类，标签落库，原文不留副本、
 * 不进事件、不上传。分类失败/输出不在枚举内一律归「其他法律事务」。
 */
@Slf4j
@Service
public class MatterClassifierService {

    private static final String PROMPT_PREFIX = """
            你是法律事务分类器。判断下面这条用户消息属于哪一类法律事项，只输出类别名本身，不要任何解释。
            可选类别：公司治理、资本市场证券、并购交易、争议解决、合同审查起草、合规监管、知识产权、劳动人事、破产重整、其他法律事务、非法律事务。
            用户消息：
            """;

    private final ChatModelFactory chatModelFactory;
    private final TelemetryService telemetryService;
    private final TelemetrySettings settings;
    private final String classifierModel;

    /** 已分类会话（进程内去重即可：重启后重复分类的概率与代价都可忽略） */
    private final Set<String> classified = ConcurrentHashMap.newKeySet();

    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "matter-classifier");
        t.setDaemon(true);
        return t;
    });

    public MatterClassifierService(ChatModelFactory chatModelFactory,
                                   TelemetryService telemetryService,
                                   TelemetrySettings settings,
                                   @Value("${telemetry.classifier-model:deepseek/deepseek-v4-flash}") String classifierModel) {
        this.chatModelFactory = chatModelFactory;
        this.telemetryService = telemetryService;
        this.settings = settings;
        this.classifierModel = classifierModel;
    }

    /**
     * 异步分类；skillMatched=true 或非首轮或开关关闭时 no-op。
     * 绝不抛异常、绝不阻塞调用方。
     */
    public void classifyAsync(String conversationId, String firstUserMessage, boolean skillMatched) {
        try {
            if (conversationId == null || firstUserMessage == null || firstUserMessage.isBlank()) return;
            if (skillMatched) return;
            if (!settings.rollupEnabled()) return;
            if (!classified.add(conversationId)) return;
            String snippet = firstUserMessage.length() > 500
                    ? firstUserMessage.substring(0, 500) : firstUserMessage;
            executor.execute(() -> classify(conversationId, snippet));
        } catch (Exception ignored) {
        }
    }

    private void classify(String conversationId, String message) {
        try {
            ChatLanguageModel model = chatModelFactory.getChatModel(classifierModel);
            String raw = model.generate(PROMPT_PREFIX + message);
            MatterCategory category = MatterCategory.parse(raw);
            telemetryService.recordConv("matter.classified", conversationId,
                    Map.of("category", category.display(), "source", "ai"));
        } catch (Exception e) {
            log.debug("事项分类失败（忽略）: {}", e.toString());
        }
    }

    /** 测试用：等待异步队列排空 */
    void flush() throws Exception {
        executor.submit(() -> {}).get();
    }
}
