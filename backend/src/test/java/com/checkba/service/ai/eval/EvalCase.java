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
    /** 非 null 时该用例进入真实 LLM 冒烟集（RealLlmSmokeTest） */
    public Smoke smoke;
    /** 预录的模型输出，按轮次回放 */
    public List<Turn> turns = new ArrayList<>();
    /** 工具桩输出：resolvedName -> 回放给模型的工具输出（缺省 "OK (eval stub)"） */
    public Map<String, String> toolStubs = new HashMap<>();
    /** 断言 */
    public Expect expect = new Expect();

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
        /** 会话文件夹重命名（<title> 协议）应包含的子串（null = 不断言） */
        public String renamedTitleContains;
        /** 应在某次 LLM 调用的上下文中出现的子串（断言编排器回喂了某条系统提醒；空 = 不断言） */
        public List<String> promptContains = new ArrayList<>();
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
