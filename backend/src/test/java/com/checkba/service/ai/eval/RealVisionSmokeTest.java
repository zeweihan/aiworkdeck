package com.checkba.service.ai.eval;

import com.checkba.service.ai.AllowedModels;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.output.Response;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.time.Duration;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 图片视觉直送的真实链路冒烟（默认跳过；设置 OPENROUTER_API_KEY 后启用）。
 *
 * <p><b>为什么需要它</b>：视觉这条路上有一串只有真机才暴露的东西——base64 data URI 的拼法、
 * mimeType 归一化、{@code DetailLevel} 档位、以及「上游到底认不认这个形状」。
 * 单测里模型是桩的，这些全都测不到；而失败形态是一句英文 400 或者「模型开始胡说」，
 * 都很难归因。这里画一张写着已知字样的图，问模型上面写了什么，答对才算这条链是通的。
 *
 * <p>同时反向验证「纯文本模型确实读不了图」，也就是降级路径存在的理由：
 * 那条断言只要求它**没有**答出图上的字，不要求它报错——不同上游对纯文本模型收到 image 块的
 * 处理不一样（有的 400、有的直接忽略），我们的降级设计正是为了不依赖这个不确定行为。
 *
 * <p>运行：{@code OPENROUTER_API_KEY=sk-or-... mvn test -Dtest=RealVisionSmokeTest}
 * 可选 {@code AI_VISION_SMOKE_MODEL}（默认 qwen/qwen3.7-flash——白名单里最便宜的视觉模型，境内可跑）。
 */
@DisplayName("图片视觉直送冒烟（真实 LLM，默认跳过）")
@EnabledIfEnvironmentVariable(named = "OPENROUTER_API_KEY", matches = ".+")
class RealVisionSmokeTest {

    /**
     * 图上写的字。
     *
     * <p><b>刻意用「词 + 无歧义数字」而不是随机字符串。</b>第一版用的是 KX7Q4M，模型回了
     * KX704MM——它显然看见了图（6 个字符对了 5 个），只是把 Q 认成 0、把 M 认重了一次。
     * 那种失败什么都证明不了：管道是通的，红的是字形歧义。所以避开所有易混字形
     * （O/0、I/1、Q、5/S、8/B），并借一个真实单词的语言先验让识别稳定——
     * 而模型在**没看见图**的情况下同样不可能凭空说出这个词，测试的判别力没有下降。
     */
    private static final String SECRET = "WALNUT47";

    private static ChatLanguageModel model(String modelId) {
        return OpenAiChatModel.builder()
                .apiKey(System.getenv("OPENROUTER_API_KEY"))
                .baseUrl(envOr("OPENROUTER_BASE_URL", "https://openrouter.ai/api/v1"))
                .modelName(modelId)
                .temperature(0.0)
                .timeout(Duration.ofSeconds(120))
                .build();
    }

    private static String envOr(String key, String fallback) {
        String v = System.getenv(key);
        return (v == null || v.isBlank()) ? fallback : v;
    }

    /** 画一张白底黑字的 PNG，返回 base64。用 Java2D 而不是塞一个二进制 fixture 进仓。 */
    private static String secretImageBase64() throws Exception {
        BufferedImage img = new BufferedImage(720, 240, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, 720, 240);
        g.setColor(Color.BLACK);
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 96));
        g.drawString(SECRET, 40, 150);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return Base64.getEncoder().encodeToString(out.toByteArray());
    }

    /** 比对前归一化：模型爱加空格、连字符、引号，这些与「看没看见图」无关。 */
    private static String normalize(String s) {
        return s == null ? "" : s.toUpperCase().replaceAll("[^A-Z0-9]", "");
    }

    private static UserMessage ask(String base64) {
        return UserMessage.from(List.of(
                TextContent.from("图片里印着一个词和两个数字，原样回答它，不要有任何别的文字。"),
                // DetailLevel 必须显式 HIGH：不带档位的重载会硬塞 LOW，
                // 低分辨率下这行字就糊了——这正是要在真机上验的东西
                ImageContent.from(base64, "image/png", ImageContent.DetailLevel.HIGH)));
    }

    @Test
    @DisplayName("视觉模型能读出图上的字——base64 data URI + HIGH detail 这条形状被上游接受")
    void visionModelReadsTheImage() throws Exception {
        String modelId = envOr("AI_VISION_SMOKE_MODEL", AllowedModels.QWEN_3_7_FLASH.getModelId());
        assertTrue(AllowedModels.supportsVision(modelId),
                "用例前提：" + modelId + " 必须是白名单里标了 vision 的模型");

        Response<dev.langchain4j.data.message.AiMessage> response =
                model(modelId).generate(List.of(ask(secretImageBase64())));
        String answer = response.content().text();

        assertTrue(normalize(answer).contains(SECRET),
                "视觉模型没读出图上的 " + SECRET + "，实际回答：" + answer
                        + "。可能的原因：base64/mimeType 拼错、DetailLevel 掉回 LOW、"
                        + "或上游改了 image_url 的形状。");
    }

    @Test
    @DisplayName("端到端：真实 ContextAssemblerService 组装出来的消息栈，真实视觉模型能读出图")
    void assembledStackIsReadableByVisionModel() throws Exception {
        String modelId = envOr("AI_VISION_SMOKE_MODEL", AllowedModels.QWEN_3_7_FLASH.getModelId());

        // 上一条用例验的是 langchain4j 的线格式；这一条验的是**我们自己那段组装代码**——
        // isVisionCandidate 的判图、loadVisionAttachment 的读盘与 base64、mimeType 归一化、
        // UserMessage 里文本与图片的排布。桩掉的只有「历史/记忆/技能」这些与图片无关的周边。
        byte[] png = Base64.getDecoder().decode(secretImageBase64());

        com.checkba.service.ProjectFileService fileService =
                mock(com.checkba.service.ProjectFileService.class);
        com.checkba.model.entity.ProjectFile file = new com.checkba.model.entity.ProjectFile();
        file.setId(9001L);
        file.setProjectId(88L);
        file.setName("secret.png");
        when(fileService.getFile(9001L)).thenReturn(file);
        when(fileService.getFileBytes(9001L)).thenReturn(png);

        com.checkba.service.ai.ChatModelFactory factory =
                mock(com.checkba.service.ai.ChatModelFactory.class);
        when(factory.effectiveModelSupportsVision(any())).thenReturn(true);

        com.checkba.service.ai.ContextAssemblerService assembler = assembler(factory, fileService);

        com.checkba.controller.ai.AiAgentController.ContextItem item =
                new com.checkba.controller.ai.AiAgentController.ContextItem();
        item.setId("9001");
        item.setName("secret.png");
        item.setFileType("image");

        List<dev.langchain4j.data.message.ChatMessage> messages = assembler.assemble(
                "conv-vision-smoke", "图片里印着一个词和两个数字，原样回答它，不要有任何别的文字。",
                List.of(item), null, null, null, "88",
                com.checkba.model.ai.AgentMode.ASK, 1L, modelId);

        // 只把「system + 末位用户消息」发出去：中间的历史是空的，且这条用例要验的是图片
        String answer = model(modelId)
                .generate(List.of(messages.get(0), messages.get(messages.size() - 1)))
                .content().text();

        assertTrue(normalize(answer).contains(SECRET),
                "组装后的消息栈里模型读不出 " + SECRET + "，实际回答：" + answer
                        + "。说明问题出在我们的组装代码而不是线格式（上一条用例会告诉你线格式是好的）。");
    }

    private static com.checkba.service.ai.ContextAssemblerService assembler(
            com.checkba.service.ai.ChatModelFactory factory,
            com.checkba.service.ProjectFileService fileService) {
        com.checkba.service.ProjectAiMessageService messageService =
                mock(com.checkba.service.ProjectAiMessageService.class);
        when(messageService.listByConversationId(anyString())).thenReturn(List.of());
        com.checkba.service.ai.skill.SkillRouter skillRouter =
                mock(com.checkba.service.ai.skill.SkillRouter.class);
        when(skillRouter.match(anyString())).thenReturn(java.util.Optional.empty());
        com.checkba.service.ai.memory.MemoryManager memoryManager =
                mock(com.checkba.service.ai.memory.MemoryManager.class);
        when(memoryManager.getProjectMemory(anyLong())).thenReturn(java.util.Optional.empty());
        when(memoryManager.retrieveMemories(anyLong(), anyString(), any(), anyInt())).thenReturn(List.of());
        when(memoryManager.retrieveUserMemories(anyLong(), anyInt())).thenReturn(List.of());
        com.checkba.service.ai.context.ContextCompressor compressor =
                mock(com.checkba.service.ai.context.ContextCompressor.class);
        when(compressor.needsCompression(any(), any())).thenReturn(false);

        return new com.checkba.service.ai.ContextAssemblerService(
                mock(com.checkba.service.ai.tools.LegalTools.class), messageService,
                mock(com.checkba.service.ai.context.FileContextLoader.class),
                new com.checkba.config.AiContextProperties(), skillRouter,
                new com.checkba.service.ai.ClientCapabilityService(),
                new com.checkba.service.ai.InlineContentCache(),
                memoryManager, compressor,
                mock(com.checkba.service.AppLanguageService.class), factory, fileService);
    }

    @Test
    @DisplayName("纯文本模型读不出图上的字——降级路径存在的理由")
    void textOnlyModelCannotReadTheImage() throws Exception {
        String modelId = AllowedModels.DEEPSEEK_V4_FLASH.getModelId();
        assertFalse(AllowedModels.supportsVision(modelId), "用例前提：这条必须是纯文本模型");

        String answer;
        try {
            answer = model(modelId).generate(List.of(ask(secretImageBase64()))).content().text();
        } catch (Exception e) {
            // 上游直接拒收 image 块也是合格结果：它同样证明「不能把图发给这类模型」
            return;
        }
        assertFalse(normalize(answer).contains(SECRET),
                "纯文本模型居然读出了 " + SECRET + "——那么它的 vision 位标错了，"
                        + "去 AllowedModels 把它改成 true（并跑一次 AllowedModelsLiveContractTest 对拍）");
    }
}
