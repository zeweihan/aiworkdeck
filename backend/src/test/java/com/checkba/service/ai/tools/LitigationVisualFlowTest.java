package com.checkba.service.ai.tools;

import com.checkba.service.ai.LitigationVisualService;
import com.checkba.service.ai.context.ProjectContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 出图流程的确定性守卫。
 *
 * <p>守的是真机上出过的三件事（AGENT 模式，面板拼的 kickoff prompt）：
 * <ol>
 *   <li>模型把语义地图 write_file 存进项目、之后 read_file 读回来报"文件不存在"；</li>
 *   <li>同一轮里 litigation_checkpoint 被连调三次；</li>
 *   <li>用户确认后只更新了地图、忘了调 litigation_render（另一轮又连渲染两次）。</li>
 * </ol>
 *
 * <p>根因是 skill 按轮生效：用户那句"确认"里没有触发词，SkillRouter 当轮不再注入
 * 诉讼可视化的 prompt，指引恰好在最关键的一步消失。修法是把"下一步做什么"写进
 * <b>工具返回文本</b>——工具结果留在对话历史里，不随 skill 失活而消失。
 * 这些字符串因此是契约，不是文案，所以在这里钉住。
 */
class LitigationVisualFlowTest {

    private static LitigationVisualTools tools;
    private static LitigationVisualService svc;

    private static final String MAP = """
            {"layout":"numbered_point_timeline","title_text":"测试时间轴",
             "events":[{"label":"甲乙签订借款合同"},{"label":"乙未按期还款"}]}
            """;

    @BeforeAll
    static void setUp() {
        svc = new LitigationVisualService();
        ReflectionTestUtils.setField(svc, "configuredDir", "");
        ReflectionTestUtils.setField(svc, "configuredPython", "");
        ReflectionTestUtils.setField(svc, "configuredGraphvizDir", "");
        // 只测 checkpoint 那一段：它除了 litviz 不碰任何协作方，其余依赖留空即可。
        tools = new LitigationVisualTools(svc, null, null, null, null, null);
    }

    @AfterEach
    void clearContext() {
        ProjectContextHolder.clear();
    }

    private static void requireRuntime() {
        assumeTrue(svc.unavailableReason() == null, "跳过：" + svc.unavailableReason());
    }

    @Test
    @DisplayName("三问后面挂着确定性的下一步：内联传参、不许 write_file/read_file、确认后必须 render")
    void checkpointCarriesNextSteps() {
        requireRuntime();
        ProjectContextHolder.setConversationId("conv-flow-1");

        String out = tools.litigation_checkpoint(MAP, null);

        assertFalse(out.startsWith("生成确认问题失败"), out);
        assertTrue(out.contains("不要发给用户"), "指引块必须明确标注不转述——否则模型会把它原样发出去");
        assertTrue(out.contains("write_file") && out.contains("read_file"),
                "必须明确禁止把语义地图落成项目文件（真机上就是这么走岔的）");
        assertTrue(out.contains("litigation_render"), "确认后要调哪个工具必须写死在这里");
    }

    @Test
    @DisplayName("同一轮重复调 checkpoint 不重新生成问题，并明说停下等回复")
    void checkpointIsIdempotentWithinATurn() {
        requireRuntime();
        ProjectContextHolder.setConversationId("conv-flow-2");

        String first = tools.litigation_checkpoint(MAP, null);
        String second = tools.litigation_checkpoint(MAP, null);
        String third = tools.litigation_checkpoint(MAP, null);

        String questions = first.substring(0, first.indexOf("──────────"));
        assertTrue(second.startsWith(questions), "重复调用必须返回同一份三问，不是新问一遍");
        assertTrue(second.contains("重复调用第 2 次"), second);
        assertTrue(third.contains("重复调用第 3 次"), third);
        assertTrue(second.contains("停止调用 litigation_checkpoint"), second);
    }

    @Test
    @DisplayName("换一份地图就是新的一轮确认，不会被上一轮的幂等挡住")
    void differentMapStartsANewCheckpoint() {
        requireRuntime();
        ProjectContextHolder.setConversationId("conv-flow-3");

        tools.litigation_checkpoint(MAP, null);
        String other = tools.litigation_checkpoint(
                MAP.replace("乙未按期还款", "乙于2023年5月左右部分还款"), null);

        assertFalse(other.contains("重复调用"), "地图变了就是重新问，不能当成重复调用短路掉");
    }

    @Test
    @DisplayName("产物身份标记必须唯一——撞号会让「打开 .drawio」取到同一张图的 .svg")
    void artifactMarkersAreUnique() {
        // 一次出图的四五个文件在同一个循环里登记，只用毫秒时间戳是撞得上的；
        // 后端下载按 wpsFileId 查不到唯一记录时取 findFirst()，撞号即取错文件。
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 500; i++) {
            assertTrue(seen.add(LitigationVisualTools.newMarker(
                    LitigationVisualTools.MARKER_ARTIFACT, 7L)), "第 " + i + " 个标记撞号了");
        }
        assertTrue(seen.iterator().next().startsWith(LitigationVisualTools.MARKER_ARTIFACT));
    }

    @Test
    @DisplayName("语义地图写坏时给人话，不是 Python traceback")
    void malformedMapGetsAHumanMessage() {
        String out = tools.litigation_checkpoint("{ not json", null);
        assertTrue(out.startsWith("语义地图不是合法 JSON"), out);
    }

    @Test
    @DisplayName("拿不到 conversationId 时退回无状态，不抛异常")
    void worksWithoutConversationId() {
        requireRuntime();
        String out = tools.litigation_checkpoint(MAP, null);
        assertFalse(out.startsWith("生成确认问题失败"), out);
        assertTrue(out.contains("litigation_render"));
    }

    @Test
    @DisplayName("三问本身仍由脚本生成，包装层一个字不改")
    void questionsComeFromTheEngineVerbatim() throws Exception {
        requireRuntime();
        ProjectContextHolder.setConversationId("conv-flow-4");

        java.nio.file.Path tmp = Files.createTempFile("litviz-flow-", ".json");
        Files.writeString(tmp, MAP, StandardCharsets.UTF_8);
        try {
            LitigationVisualService.Result r = svc.checkpoint(tmp, null);
            assumeTrue(r.ok(), "引擎不可用");
            String engine = r.raw().getStr("questions", "");
            assertTrue(tools.litigation_checkpoint(MAP, null).startsWith(engine),
                    "三问必须是脚本原样返回的那一份");
        } finally {
            Files.deleteIfExists(tmp);
        }
    }
}
