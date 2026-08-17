package com.checkba.service.optimizer;

import com.checkba.model.entity.FeedbackAttachment;
import com.checkba.model.entity.UserFeedback;
import com.checkba.service.ai.ChatModelFactory;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatLanguageModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.function.Function;

/**
 * 反馈分诊：把一条用户反馈判成「确认是 bug」「优化建议」「拿不准」「无信息量」。
 *
 * <p>两条判定在模型之前就定死，不交给它自由发挥：
 * <ul>
 *   <li><b>只有语音且没转写</b> → 一律 UNCLEAR 转人。模型看不见音频，让它猜等于瞎判。</li>
 *   <li><b>正文与转写都为空</b>（只有截图）→ 一律 UNCLEAR 转人。一张没有说明的图不构成可执行的缺陷描述。</li>
 * </ul>
 * 其余交给模型，但要求它对「能不能直接改代码」这件事自报置信度，低于阈值同样转人。
 */
@Slf4j
@Service
public class FeedbackTriageService {

    public static final String VERDICT_BUG = "BUG";
    public static final String VERDICT_SUGGESTION = "SUGGESTION";
    public static final String VERDICT_UNCLEAR = "UNCLEAR";
    public static final String VERDICT_NOISE = "NOISE";

    private final Function<String, ChatLanguageModel> modelSupplier;
    private final ObjectMapper mapper = new ObjectMapper();

    // 两个构造器（另一个是测试用的、可注入模型），Spring 必须被明确告知选哪个
    @org.springframework.beans.factory.annotation.Autowired
    public FeedbackTriageService(ChatModelFactory chatModelFactory) {
        this(chatModelFactory::getChatModel);
    }

    FeedbackTriageService(Function<String, ChatLanguageModel> modelSupplier) {
        this.modelSupplier = modelSupplier;
    }

    public record TriageResult(String verdict, double confidence, String title, String summary,
                               String severity, String reason, String raw) {
        public boolean isBug() {
            return VERDICT_BUG.equals(verdict);
        }
    }

    public TriageResult triage(UserFeedback fb, List<FeedbackAttachment> attachments, String modelId) {
        boolean hasAudio = attachments.stream().anyMatch(a -> FeedbackAttachment.TYPE_AUDIO.equals(a.getType()));
        boolean hasTranscript = fb.getVoiceTranscript() != null && !fb.getVoiceTranscript().isBlank();
        boolean hasText = fb.getText() != null && !fb.getText().isBlank();

        if (hasAudio && !hasTranscript && !hasText) {
            return new TriageResult(VERDICT_UNCLEAR, 0.0, "一段没有转写的语音反馈",
                    "用户录了语音但本机未配置转写服务，正文为空。", "unknown",
                    "语音没有转写文本，模型无法判断内容，必须由人听一遍。", "");
        }
        if (!hasText && !hasTranscript) {
            return new TriageResult(VERDICT_UNCLEAR, 0.0, "只有截图、没有文字说明的反馈",
                    "用户只附了截图，没有写任何文字。", "unknown",
                    "缺少可执行的缺陷描述，需要人看图判断。", "");
        }

        String prompt = buildPrompt(fb, attachments);
        String raw;
        try {
            raw = modelSupplier.apply(modelId).generate(prompt);
        } catch (Exception e) {
            log.warn("分诊调用模型失败 feedback#{}: {}", fb.getId(), e.toString());
            throw new IllegalStateException("分诊失败: " + e.getMessage(), e);
        }
        return parse(raw);
    }

    TriageResult parse(String raw) {
        String json = stripFence(raw);
        try {
            JsonNode n = mapper.readTree(json);
            String verdict = normalizeVerdict(text(n, "verdict"));
            double confidence = n.has("confidence") ? n.get("confidence").asDouble(0) : 0;
            if (confidence < 0) confidence = 0;
            if (confidence > 1) confidence = 1;
            return new TriageResult(verdict, confidence,
                    text(n, "title"), text(n, "summary"), text(n, "severity"), text(n, "reason"), raw);
        } catch (Exception e) {
            // 解析不了不等于「没问题」：降级成转人，绝不当成 NOISE 丢掉
            log.warn("分诊结果不是合法 JSON，降级转人: {}", e.toString());
            return new TriageResult(VERDICT_UNCLEAR, 0.0, "分诊结果无法解析",
                    "模型没有按约定返回 JSON。", "unknown",
                    "分诊输出解析失败，原样转人判断。", raw);
        }
    }

    private static String normalizeVerdict(String v) {
        String s = v == null ? "" : v.trim().toUpperCase();
        return switch (s) {
            case VERDICT_BUG, VERDICT_SUGGESTION, VERDICT_NOISE -> s;
            default -> VERDICT_UNCLEAR;
        };
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.get(field);
        return v == null || v.isNull() ? "" : v.asText("");
    }

    private static String stripFence(String raw) {
        String s = raw == null ? "" : raw.trim();
        if (s.startsWith("```")) {
            int nl = s.indexOf('\n');
            if (nl > 0) s = s.substring(nl + 1);
            int fence = s.lastIndexOf("```");
            if (fence >= 0) s = s.substring(0, fence);
        }
        // 有些模型会在 JSON 前后加寒暄，取最外层的一对花括号
        int start = s.indexOf('{');
        int end = s.lastIndexOf('}');
        return (start >= 0 && end > start) ? s.substring(start, end + 1) : s;
    }

    String buildPrompt(UserFeedback fb, List<FeedbackAttachment> attachments) {
        long images = attachments.stream().filter(a -> FeedbackAttachment.TYPE_IMAGE.equals(a.getType())).count();
        boolean hasAudio = attachments.stream().anyMatch(a -> FeedbackAttachment.TYPE_AUDIO.equals(a.getType()));

        StringBuilder sb = new StringBuilder();
        sb.append("你是 AI WorkDeck（面向法律行业的 AI 工作台，Java Spring 后端 + uni-app/Vue3 前端 + Electron 桌面壳）的维护者助手。\n")
                .append("下面是一条用户从桌面端右下角反馈浮窗提交的反馈。请判断它属于哪一类。\n\n")
                .append("判定口径：\n")
                .append("- BUG：描述了一个明确的、可复现或至少可定位的功能缺陷，且信息足以让人直接去改代码。\n")
                .append("- SUGGESTION：可用但希望更好（新功能、交互改进、性能期望），需要产品判断而不是修复。\n")
                .append("- UNCLEAR：读得懂但信息不足以定位，或需要产品/法律判断才能决定要不要做。\n")
                .append("- NOISE：测试内容、空话、与产品无关。\n\n")
                .append("confidence 是你对「现在就可以让编码 Agent 去改代码并开 PR」这件事的把握（0~1）。\n")
                .append("拿不准就压低它——误开一个 PR 的代价远高于多问一封邮件。\n\n")
                .append("只输出一个 JSON 对象，不要任何解释文字：\n")
                .append("{\"verdict\":\"BUG|SUGGESTION|UNCLEAR|NOISE\",\"confidence\":0.0,\"title\":\"一句话标题\",")
                .append("\"summary\":\"两三句话复述问题\",\"severity\":\"high|medium|low|unknown\",\"reason\":\"你这么判的依据\"}\n\n")
                .append("=== 反馈 #").append(fb.getId()).append(" ===\n")
                .append("用户自选类别: ").append(UserFeedback.KIND_IDEA.equals(fb.getKind()) ? "建议" : "报障").append('\n')
                .append("提交页面: ").append(nz(fb.getPage())).append('\n')
                .append("应用版本: ").append(nz(fb.getAppVersion())).append('\n')
                .append("运行平台: ").append(nz(fb.getPlatform())).append('\n')
                .append("附件: ").append(images).append(" 张图片")
                .append(hasAudio ? "，1 段语音" : "").append('\n');
        if (fb.getVoiceTranscript() != null && !fb.getVoiceTranscript().isBlank()) {
            sb.append("语音转写: ").append(fb.getVoiceTranscript()).append('\n');
        }
        sb.append("正文:\n").append(nz(fb.getText())).append('\n');
        String logTail = extractLogTail(fb.getContextJson());
        if (!logTail.isEmpty()) {
            sb.append("\n后端日志尾巴（截断）:\n").append(logTail).append('\n');
        }
        return sb.toString();
    }

    /** 日志只截取最后 3000 字给模型：分诊要的是「有没有异常栈」而不是通读日志。 */
    String extractLogTail(String contextJson) {
        if (contextJson == null || contextJson.isBlank()) return "";
        try {
            JsonNode n = mapper.readTree(contextJson);
            JsonNode tail = n.get("backendLogTail");
            if (tail == null || tail.isNull()) return "";
            String s = tail.asText("");
            return s.length() <= 3000 ? s : s.substring(s.length() - 3000);
        } catch (Exception e) {
            return "";
        }
    }

    private static String nz(String v) {
        return v == null ? "" : v;
    }
}
