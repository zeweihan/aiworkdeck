// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.service.ai;

import com.checkba.service.ai.ClientCapabilityService.Capability;
import com.checkba.service.ai.ClientCapabilityService.OfficeHost;
import com.checkba.service.ai.tools.ToolMeta;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 系统提示按客户端能力分段之后的契约（dev-board#809 / K29）：
 * <b>片段里教的每一个工具，在那一档能力下都必须真的可见。</b>
 *
 * <p><b>这条测试要防的是什么</b>（2026-09-22 实测「发现 A」）：改动前基底 prompt 33357 字符
 * 在 {@code AGENT+lowa} 与 {@code AGENT+none} 下逐字节相同，里面整三节在教 doc_* / PPT 编辑 /
 * PDF 编辑，而这些工具在任务窗格会话与纯对话会话里 {@link ClientCapabilityService#isToolVisible}
 * 一个都不放行。代价不是报错，是<b>白烧一轮</b>：none 会话每次先调
 * {@code doc_list_project_files}，拿回 "Tool not found or arguments invalid." 才开始干活。
 * 教一个用不了的工具，在产品上和不教是两回事——模型会去试。
 *
 * <p><b>为什么扫源码而不是写死一张名单</b>：名单会腐烂。工具的宿主声明
 * （{@code @ToolMeta.requiresHost}）从真实的工具类里捞出来，与下面那份期望逐名对拍——
 * 有人新声明一个宿主依赖、或给某个工具改名（K27 正在做这件事），这条测试当场红，
 * 逼着改动的人回头看一眼片段有没有跟着改。
 */
class SystemPromptToolVisibilityContractTest {

    private static final Path TOOLS_DIR =
            Path.of("src/main/java/com/checkba/service/ai/tools");
    private static final Path PROMPTS_DIR =
            Path.of("src/main/resources/prompts");

    /** {@code @Tool} 注解下面那个 public 方法名 —— 与 ToolRegistry 的取名口径一致。 */
    private static final Pattern TOOL_METHOD = Pattern.compile(
            "public\\s+(?:static\\s+)?[\\w<>\\[\\],\\s.]+?\\s+([a-zA-Z_][a-zA-Z_0-9]*)\\s*\\(");

    /** 反引号里的标识符：`tool_name` 或 `tool_name(args)`。 */
    private static final Pattern BACKTICKED = Pattern.compile(
            "`([a-z][a-z0-9_]*)\\s*\\(|`([a-z][a-z0-9_]*)`");

    /**
     * 「有客户端执行器依赖」的工具名前缀族。片段里出现这几族的名字时，
     * 要求比别的名字更严：必须是<b>真实注册过</b>的工具（挡住改名与手误），
     * 而且必须在该片段那一档能力下可见。
     */
    private static final Pattern GATED_FAMILY = Pattern.compile(
            "^(doc_|sheet_|slide_|office_|pptx_|pdf_|text_|ref_)");

    /**
     * 基底 prompt 里出现在反引号里、但<b>不是工具名</b>的标识符——参数名、
     * Python 侧的 API 对象、Tushare 的接口名、`depth` 的三个取值、artifact 类型。
     *
     * <p>这是一份<b>钉住的名单</b>而不是一条模糊规则：基底里凡是反引号包着的小写标识符，
     * 要么是真工具（而且规格真下发），要么就得在这里点名。
     * 新写一个反引号标识符而没人分类，{@link BasePrompt#basePromptOnlyNamesRealOfferedTools}
     * 当场红，逼着写的人自己说清它是什么——{@code add_memory} / {@code query_knowledge_base}
     * 这两个从来不存在的「工具」就是这么在基底里躺了很久的（dev-board#809 / K29 顺手清掉）。
     */
    private static final Set<String> NON_TOOL_IDENTIFIERS = new TreeSet<>(List.of(
            // query_memory 的 depth 档位与参数名
            "depth", "quick", "hybrid", "deep",
            // dispatch_subtask 的参数名
            "task_description", "expected_output", "tool_scope",
            // run_python 里注入的后端 API 对象
            "default_api",
            // Tushare 的接口名（不是本仓的工具）
            "stock_basic", "top10_holders",
            // artifact 类型标注
            "implementation_plan"));

    /**
     * 今天声明了宿主依赖的全部工具（{@code @ToolMeta.requiresHost}）。
     * 它们没有 doc_/office_ 这类前缀，光看名字看不出挑不挑宿主——正因如此才必须钉住：
     * 少一个，片段里就可能教出一条 30 秒超时或直接 Tool not found 的死路。
     */
    private static final Map<String, ToolMeta.Host> EXPECTED_HOST_REQUIREMENTS = new TreeMap<>(Map.ofEntries(
            // PPT：打开 / 生成 / 改格式都要桌面前端（生成还要一个只存在于桌面端的配置界面）
            Map.entry("pptx_open_file", ToolMeta.Host.LOWA),
            Map.entry("pptx_generate", ToolMeta.Host.LOWA),
            Map.entry("pptx_apply_format", ToolMeta.Host.LOWA),
            // PDF：写入类收尾都会让编辑器打开/重载那份文件
            Map.entry("pdf_highlight", ToolMeta.Host.LOWA),
            Map.entry("pdf_annotate", ToolMeta.Host.LOWA),
            Map.entry("pdf_redact", ToolMeta.Host.LOWA),
            Map.entry("pdf_replace_text", ToolMeta.Host.LOWA),
            Map.entry("pdf_to_word", ToolMeta.Host.LOWA),
            // 纯文本：写入是纯后端的，但收尾要刷新用户打开的文本标签，任务窗格里没有去处
            Map.entry("text_write_file", ToolMeta.Host.LOWA),
            Map.entry("text_find_replace", ToolMeta.Host.LOWA),
            // 诉讼可视化：出图后在编辑器里打开
            Map.entry("litigation_timeline_render", ToolMeta.Host.LOWA),
            Map.entry("litigation_render", ToolMeta.Host.LOWA)));

    /** 片段文件 → 它服务的那一档会话。 */
    private record Fragment(String stem, Capability capability, OfficeHost host) {
        String zhFile() {
            return stem + ".md";
        }

        String enFile() {
            return stem + ".en.md";
        }
    }

    private static final List<Fragment> FRAGMENTS = List.of(
            new Fragment("tools-lowa", Capability.LOWA, OfficeHost.WORD),
            new Fragment("tools-office-word", Capability.OFFICE, OfficeHost.WORD),
            new Fragment("tools-office-excel", Capability.OFFICE, OfficeHost.EXCEL),
            new Fragment("tools-office-ppt", Capability.OFFICE, OfficeHost.POWERPOINT),
            new Fragment("tools-none", Capability.NONE, OfficeHost.WORD));

    // ---------------------------------------------------------------- 源码扫描

    private static String readSource(Path p) {
        try {
            return Files.readString(p, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("读不到 " + p.toAbsolutePath()
                    + "（工作目录应为 backend/）", e);
        }
    }

    /**
     * 一个已注册工具在本测试里需要知道的两件事。
     *
     * @param host  {@code @ToolMeta.requiresHost}，没声明是 {@link ToolMeta.Host#NONE}
     * @param offered {@code @ToolMeta.offerToModel}（dev-board#807 / K27 引入）。
     *                false = <b>只登记不下发</b>：工具还能被 resolve/execute 调到，
     *                但规格不进模型的工具清单，模型压根看不见它。写进提示里的名字
     *                必须是真下发的，否则就是在勾模型去调一个它清单里没有的名字。
     */
    private record ToolFacts(ToolMeta.Host host, boolean offered) {
    }

    /** 全部 {@code @Tool} 方法名 → 它的宿主声明与是否真下发。 */
    private static Map<String, ToolFacts> scanTools() {
        Map<String, ToolFacts> tools = new TreeMap<>();
        try (Stream<Path> files = Files.list(TOOLS_DIR)) {
            for (Path p : files.filter(f -> f.getFileName().toString().endsWith(".java")).sorted().toList()) {
                String src = readSource(p);
                Matcher tool = Pattern.compile("@Tool\\(").matcher(src);
                while (tool.find()) {
                    // 声明写在紧挨着的 @ToolMeta 上；只在「上一个 @ToolMeta 之后」的窗口里找，
                    // 免得把上一个工具的声明记到这个工具头上
                    String before = src.substring(Math.max(0, tool.start() - 1500), tool.start());
                    int metaAt = before.lastIndexOf("@ToolMeta(");
                    ToolMeta.Host host = ToolMeta.Host.NONE;
                    boolean offered = true;
                    if (metaAt >= 0) {
                        String meta = before.substring(metaAt);
                        Matcher hm = Pattern.compile("requiresHost\\s*=\\s*ToolMeta\\.Host\\.(\\w+)")
                                .matcher(meta);
                        if (hm.find()) {
                            host = ToolMeta.Host.valueOf(hm.group(1));
                        }
                        offered = !Pattern.compile("offerToModel\\s*=\\s*false").matcher(meta).find();
                    }
                    Matcher m = TOOL_METHOD.matcher(src.substring(tool.start(),
                            Math.min(src.length(), tool.start() + 8000)));
                    if (m.find()) {
                        tools.put(m.group(1), new ToolFacts(host, offered));
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return tools;
    }

    /** 装好宿主声明的能力服务 —— 与 ToolRegistry 的 @PostConstruct 推送等价。 */
    private static ClientCapabilityService capabilityServiceWith(Map<String, ToolFacts> tools) {
        ClientCapabilityService svc = new ClientCapabilityService();
        tools.forEach((name, facts) -> svc.declareHostRequirement(name, facts.host()));
        return svc;
    }

    private static String readPrompt(String fileName) {
        return readSource(PROMPTS_DIR.resolve(fileName));
    }

    /** 片段里所有反引号标识符（片段自己的说明性注释也算：它也会原样进系统提示）。 */
    private static Set<String> backtickedNames(String text) {
        Set<String> names = new TreeSet<>();
        Matcher m = BACKTICKED.matcher(text);
        while (m.find()) {
            names.add(m.group(1) != null ? m.group(1) : m.group(2));
        }
        return names;
    }

    // ---------------------------------------------------------------- 测试

    @Test
    @DisplayName("宿主声明清单没有漂移（新声明一个就必须回头看片段）")
    void declaredHostRequirementsAreStillTheOnesTheFragmentsWereWrittenAgainst() {
        Map<String, ToolMeta.Host> declared = new TreeMap<>();
        scanTools().forEach((name, facts) -> {
            if (facts.host() != ToolMeta.Host.NONE) {
                declared.put(name, facts.host());
            }
        });
        assertEquals(EXPECTED_HOST_REQUIREMENTS, declared,
                "@ToolMeta.requiresHost 的声明集合变了。这些工具没有 doc_/office_ 前缀，"
                        + "光看名字看不出挑宿主——请顺带核对 prompts/tools-*.md 里有没有教到它们。");
    }

    @Test
    @DisplayName("片段里教的每个工具，在那一档能力下都真的可见、而且规格真的下发（中英两版）")
    void everyToolNamedInAFragmentIsVisibleInThatSession() {
        Map<String, ToolFacts> tools = scanTools();
        ClientCapabilityService capabilities = capabilityServiceWith(tools);
        List<String> problems = new ArrayList<>();

        for (Fragment fragment : FRAGMENTS) {
            String conversationId = "conv-" + fragment.stem();
            capabilities.record(conversationId,
                    fragment.capability().name(), fragment.host().name());

            for (String fileName : List.of(fragment.zhFile(), fragment.enFile())) {
                String text = readPrompt(fileName);
                for (String name : backtickedNames(text)) {
                    boolean gated = GATED_FAMILY.matcher(name).find();
                    ToolFacts facts = tools.get(name);
                    if (gated && facts == null) {
                        problems.add(fileName + ": `" + name + "` 不是任何已注册的工具"
                                + "（改名了？手误？）");
                        continue;
                    }
                    if (facts == null) {
                        // 格式键名、op 名这类非工具标识符（如 `bold` / `set_run_format`）不参与断言
                        continue;
                    }
                    if (!facts.offered()) {
                        problems.add(fileName + ": `" + name
                                + "` 声明了 @ToolMeta(offerToModel = false)，规格根本不下发——"
                                + "模型的工具清单里没有它，写进提示只会勾它去调一个不存在的名字");
                        continue;
                    }
                    if (!capabilities.isToolVisible(name, conversationId)) {
                        problems.add(fileName + ": `" + name + "` 在 "
                                + fragment.capability() + "/" + fragment.host()
                                + " 会话里不可见——教了也只会换来 Tool not found");
                    }
                }
            }
        }
        assertTrue(problems.isEmpty(),
                "片段教了本会话用不了的工具：\n  " + String.join("\n  ", problems));
    }

    @Test
    @DisplayName("none 会话的片段里一个 doc_ 都不许出现（连「你没有它」也不行）")
    void theNoneFragmentNeverNamesAnEditorTool() {
        for (String fileName : List.of("tools-none.md", "tools-none.en.md")) {
            String text = readPrompt(fileName);
            for (String forbidden : List.of("doc_", "sheet_", "slide_", "office_")) {
                assertFalse(text.contains(forbidden),
                        fileName + " 里出现了 " + forbidden
                                + "。纯对话会话一个编辑器工具都没有，提到名字本身就会诱发调用——"
                                + "连反面提法（「你没有 doc_xxx」）也不要写。");
            }
        }
    }

    @Nested
    @DisplayName("基底 prompt 只留与客户端能力无关的内容")
    class BasePrompt {

        @Test
        @DisplayName("两版基底 prompt 都带着工具指引占位标记")
        void bothBasePromptsCarryThePlaceholder() {
            for (String fileName : List.of("system_prompt.md", "system_prompt.en.md")) {
                String text = readPrompt(fileName);
                assertEquals(1, countOf(text, ContextAssemblerService.TOOL_GUIDANCE_PLACEHOLDER),
                        fileName + " 必须恰好含一次 " + ContextAssemblerService.TOOL_GUIDANCE_PLACEHOLDER
                                + "——没有它，按能力分段的片段就拼不进去（只会 warn 后接到末尾）。");
            }
        }

        @Test
        @DisplayName("基底 prompt 里不再出现任何挑客户端的工具名")
        void basePromptNamesNoClientGatedTool() {
            Map<String, ToolFacts> tools = scanTools();
            List<String> problems = new ArrayList<>();
            for (String fileName : List.of("system_prompt.md", "system_prompt.en.md")) {
                String text = readPrompt(fileName);
                for (String prefix : List.of("doc_", "sheet_", "slide_", "office_", "pptx_", "pdf_", "ref_")) {
                    int at = text.indexOf(prefix);
                    if (at >= 0) {
                        problems.add(fileName + ": 出现了 " + prefix + "（"
                                + text.substring(at, Math.min(text.length(), at + 40)).replace('\n', ' ')
                                + "）");
                    }
                }
                tools.forEach((name, facts) -> {
                    if (facts.host() != ToolMeta.Host.NONE && text.contains(name)) {
                        problems.add(fileName + ": 出现了 " + name
                                + "（它声明了 requiresHost=" + facts.host() + "）");
                    }
                });
            }
            assertTrue(problems.isEmpty(),
                    "基底 prompt 是三档能力共用的，里面出现挑客户端的工具名就等于教 none / 任务窗格会话"
                            + "去调一个必然 Tool not found 的工具（实测「发现 A」）。"
                            + "把这段指引挪进 prompts/tools-*.md 对应的片段里：\n  "
                            + String.join("\n  ", problems));
        }

        @Test
        @DisplayName("基底 prompt 反引号里的名字：要么是真下发的工具，要么在非工具名单里点了名")
        void basePromptOnlyNamesRealOfferedTools() {
            Map<String, ToolFacts> tools = scanTools();
            List<String> problems = new ArrayList<>();
            for (String fileName : List.of("system_prompt.md", "system_prompt.en.md")) {
                String text = readPrompt(fileName);
                for (String name : backtickedNames(text)) {
                    if (NON_TOOL_IDENTIFIERS.contains(name)) {
                        continue;
                    }
                    ToolFacts facts = tools.get(name);
                    if (facts == null) {
                        problems.add(fileName + ": `" + name + "` 不是任何已注册的工具"
                                + "——是改名了、写错了，还是它本来就不是工具？"
                                + "（后者请加进 NON_TOOL_IDENTIFIERS 并说明它是什么）");
                    } else if (!facts.offered()) {
                        problems.add(fileName + ": `" + name
                                + "` 声明了 @ToolMeta(offerToModel = false)，规格不下发，"
                                + "模型的工具清单里没有它");
                    }
                }
            }
            assertTrue(problems.isEmpty(),
                    "基底 prompt 在教不存在或不下发的工具。教一个模型调不到的名字，代价是一整轮"
                            + "模型往返（dev-board#809 实测「发现 A」的形态）——"
                            + "`add_memory` / `query_knowledge_base` 这两个从来不存在的名字就这么躺了很久：\n  "
                            + String.join("\n  ", problems));
        }

        private int countOf(String haystack, String needle) {
            int n = 0;
            for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + needle.length())) {
                n++;
            }
            return n;
        }
    }

    @Test
    @DisplayName("每一种（能力 × 宿主）组合都能解析到一份存在的片段")
    void everyCapabilityAndHostResolvesToAFragmentThatExists() {
        Map<String, String> resolved = new LinkedHashMap<>();
        for (Capability capability : Capability.values()) {
            for (OfficeHost host : OfficeHost.values()) {
                String stem = ContextAssemblerService.toolGuidanceStem(capability, host);
                resolved.put(capability + "/" + host, stem);
                for (String suffix : List.of(".md", ".en.md")) {
                    assertTrue(Files.exists(PROMPTS_DIR.resolve(stem + suffix)),
                            capability + "/" + host + " 解析到片段 " + stem + suffix
                                    + "，但这个文件不存在");
                }
            }
        }
        // 非 OFFICE 的能力档不看宿主：同一能力的三个宿主必须解析到同一份片段
        assertEquals(resolved.get("LOWA/WORD"), resolved.get("LOWA/EXCEL"),
                "LOWA 会话不该因为 officeHost 而拿到不同片段");
        assertEquals(resolved.get("NONE/WORD"), resolved.get("NONE/POWERPOINT"),
                "NONE 会话不该因为 officeHost 而拿到不同片段");
    }

    @Test
    @DisplayName("片段属于稳定段：里面不许有每轮会变的东西")
    void fragmentsAreStableSoThePromptCacheKeepsHitting() {
        for (Fragment fragment : FRAGMENTS) {
            for (String fileName : List.of(fragment.zhFile(), fragment.enFile())) {
                String text = readPrompt(fileName);
                assertFalse(text.contains(ContextAssemblerService.SYSTEM_VOLATILE_SEPARATOR.trim()),
                        fileName + " 不该出现易变段分界标记");
                assertFalse(text.contains("[SYSTEM INJECTION]"),
                        fileName + " 是稳定前缀的一部分，不能塞每轮变化的注入内容——"
                                + "那会让提示缓存永久失效，而且不报错、只静默多花钱");
            }
        }
    }
}
