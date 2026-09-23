// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.service.ai.eval;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * 离线评测用例模型（见 docs/AI_EVAL.md）。
 *
 * 每个用例描述：给定用户输入 + 预录的模型输出（turns），
 * 编排器应当产生怎样的工具分发序列与输出结构（expect）。
 * 用例存放在 src/test/resources/ai-eval/cases/*.json（每个文件是一个用例数组）。
 */
public class EvalCase {

    /** 唯一 ID，用作测试名 */
    public String id;
    /** 人类可读标题 */
    public String title;
    /** 分类：drafting / revision / legal-research / pptx / chat / memory / files / artifacts / skill / question */
    public String category;
    /** 协议标注（仅文档用途）：xml / native / mixed */
    public String protocol;
    /** Agent 模式：AGENT（默认）/ PLAN / ASK */
    public String mode = "AGENT";
    /** 用户输入 */
    public String userInput;
    /**
     * 可选：本轮的活跃文档（编辑器里打开着的那一份）。
     * 决定编排器下发哪一套编辑原语（dev-board#729 ①：docx 不下发 sheet_* / slide_*）。
     */
    public ActiveDocument activeDocument;
    /**
     * 可选：本轮会话的客户端能力（lowa / office / none，值同 chat 请求的 clientCapability）。
     * 缺省 null = 不登记 = 沿用 LOWA（存量主前端），office_* 不下发。
     * 写 "office" 才能断言 Office/WPS 任务窗格那一族的可见性（dev-board#818）。
     */
    public String clientCapability;
    /** 可选：Office 会话的宿主（word / excel / powerpoint），仅 clientCapability=office 时有意义，缺省 word */
    public String officeHost;
    /**
     * 可选：本用例强制打开工具渐进披露（dev-board#810）。
     *
     * <p>缺省 false = 与生产默认一致（开关关着），存量 64 条用例一条都不受影响。
     * 写成用例字段而不是只靠 {@code -Dai.tools.progressive-disclosure.enabled=true}，
     * 是为了让「查目录 → 下一轮工具回来」这条链<b>进默认的 mvn test</b>：
     * 只能靠命令行开关跑的验证，等于没有护栏。
     */
    public boolean progressiveDisclosure;
    /** 非 null 时该用例进入真实 LLM 冒烟集（RealLlmSmokeTest） */
    public Smoke smoke;
    /** 预录的模型输出，按轮次回放 */
    public List<Turn> turns = new ArrayList<>();
    /** 工具桩输出：resolvedName -> 回放给模型的工具输出（缺省 "OK (eval stub)"） */
    public Map<String, String> toolStubs = new HashMap<>();
    /** 断言 */
    public Expect expect = new Expect();

    /** 活跃文档（映射成 AiAgentController.ContextItem 塞进 chat 请求） */
    public static class ActiveDocument {
        /** 文件 ID（字符串，与真实请求一致） */
        public String id = "1001";
        public String name;
        /** 扩展名（无点号）；留空时按 name 的后缀判类型 */
        public String fileType;
    }

    /** 一轮预录模型输出：text（XML 协议整段文本）或 toolCalls（原生 function calling），二选一 */
    public static class Turn {
        public String text;
        public List<NativeCall> toolCalls;
    }

    /** 一次原生 function calling 请求 */
    public static class NativeCall {
        public String name;
        public Map<String, Object> arguments = new HashMap<>();
    }

    /** 真实 LLM 冒烟断言：首个工具调用应属于集合（空集合 = 期望不调用任何工具） */
    public static class Smoke {
        public List<String> anyOfFirstTools = new ArrayList<>();
    }

    public static class Expect {
        /**
         * 期望的工具分发序列（按顺序逐个匹配 resolvedName）。
         * null = 不断言工具序列；[] = 断言没有任何工具调用。
         */
        public List<ExpectedToolCall> toolCalls;
        /** 最终保存的 ASSISTANT 消息应包含的子串（输出结构标签断言） */
        public List<String> structureContains = new ArrayList<>();
        /** 期望保存的 artifact（null = 不断言） */
        public Artifact artifact;
        /**
         * 最后一个 bubble_end 事件的 status：
         * finished / paused / awaiting_approval（implementation_plan 停机待审批）/
         * awaiting_input（模型 &lt;question&gt; 反问，停机等用户回答；对应
         * AgentRunStateService.RunStatus.AWAITING_INPUT）。
         * 断言用 contains，别写成前缀匹配。
         */
        public String bubbleEndStatus = "finished";
        /** 每次 LLM 调用是否携带工具规格（null = 不断言；ASK 模式应为 false） */
        public Boolean toolsOffered;
        /** 每次携带工具的 LLM 调用中，可见工具应包含的名字（Skill 裁剪断言；空 = 不断言） */
        public List<String> offeredToolsInclude = new ArrayList<>();
        /** 每次携带工具的 LLM 调用中，可见工具应排除的名字（Skill 裁剪断言；空 = 不断言） */
        public List<String> offeredToolsExclude = new ArrayList<>();
        /**
         * 只对**第一次**携带工具的调用断言排除（dev-board#729 ①）。
         * 用于「起跑时按活跃文档类型裁掉，中途新建/切换文档后又放回来」这种<b>逐轮变化</b>的形态——
         * 全轮次的 offeredToolsExclude 在这里必然自相矛盾。
         */
        public List<String> offeredToolsExcludeFirstCall = new ArrayList<>();
        /** 只对**最后一次**携带工具的调用断言包含；与上一条配对使用。 */
        public List<String> offeredToolsIncludeLastCall = new ArrayList<>();
        /** 会话文件夹重命名（<title> 协议）应包含的子串（null = 不断言） */
        public String renamedTitleContains;
        /** 应在某次 LLM 调用的上下文中出现的子串（断言编排器回喂了某条系统提醒；空 = 不断言） */
        public List<String> promptContains = new ArrayList<>();
        /**
         * 允许本轮出现「调了一个本会话里不存在或不可见的工具」的分发（默认 false = 不允许）。
         *
         * <p>默认不允许是刻意的：一次 Tool not found 就是白烧一整轮模型往返，
         * 用例里出现它，要么是提示在教用不了的工具，要么是可见性裁剪与提示对不上。
         * 只有专门在验错误回路的用例才该把它打开。
         */
        public boolean allowUnresolvedTools = false;
        /**
         * 本轮应为哪个文件建过检查点（活跃文档的 id，字符串；null = 不断言）。
         *
         * <p>钉的是「写入类工具 ⇒ 有快照可退」这条产品承诺：编排器只对
         * {@code @ToolMeta(fileEffect="MODIFIED")} 的工具建检查点，漏标注解的写入原语
         * 会让 {@code doc_restore_checkpoint} 无从恢复（dev-board 审计 B-02）。
         */
        public String checkpointForFileId;
    }

    /** artifact 落盘断言 */
    public static class Artifact {
        /** 类型标注（文档用途）：task_list / implementation_plan */
        public String type;
        /** saveArtifactFile 收到的文件名应包含的子串 */
        public String filenameContains;
    }

    public static class ExpectedToolCall {
        /** 期望的工具名（别名解析后，如 search_laws -> search_web） */
        public String name;
        /** 参数断言：JSON 参数中 key 对应值（String.valueOf）应包含的子串 */
        public Map<String, String> argsContain = new HashMap<>();
    }

    /** 加载全部用例（文件名排序，校验 id 唯一） */
    public static List<EvalCase> loadAll() {
        Path dir = casesDir();
        ObjectMapper mapper = new ObjectMapper();
        List<EvalCase> cases = new ArrayList<>();
        try (Stream<Path> files = Files.list(dir)) {
            for (Path p : files.filter(f -> f.getFileName().toString().endsWith(".json")).sorted().toList()) {
                try {
                    cases.addAll(mapper.readValue(p.toFile(), new TypeReference<List<EvalCase>>() {
                    }));
                } catch (IOException e) {
                    throw new IllegalStateException("评测用例文件解析失败: " + p, e);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        if (cases.isEmpty()) {
            throw new IllegalStateException("在 " + dir + " 中没有找到任何评测用例");
        }
        Set<String> ids = new HashSet<>();
        for (EvalCase c : cases) {
            if (c.id == null || c.id.isBlank()) {
                throw new IllegalStateException("存在缺少 id 的评测用例（title=" + c.title + "）");
            }
            if (!ids.add(c.id)) {
                throw new IllegalStateException("评测用例 id 重复: " + c.id);
            }
            if (c.userInput == null || c.turns.isEmpty()) {
                throw new IllegalStateException("用例 " + c.id + " 缺少 userInput 或 turns");
            }
        }
        return cases;
    }

    static Path casesDir() {
        for (String candidate : List.of(
                "src/test/resources/ai-eval/cases",
                "backend/src/test/resources/ai-eval/cases")) {
            Path p = Path.of(candidate);
            if (Files.isDirectory(p)) {
                return p;
            }
        }
        throw new IllegalStateException(
                "找不到 ai-eval 用例目录（期望 src/test/resources/ai-eval/cases，工作目录应为 backend/）");
    }
}
