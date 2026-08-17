package com.checkba.service.ai;

import com.checkba.service.ai.tools.AgentToolComponent;
import dev.langchain4j.agent.tool.Tool;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AssignableTypeFilter;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 工具人性化名称的两侧对账：后端每个已注册工具，前端 {@code utils/toolDisplayNames.js} 里
 * 都得有一行。
 *
 * <p><b>为什么需要这条护栏</b>：用户在过程卡上看到的工具名<b>不是</b>后端
 * {@code @ToolMeta(displayName)}——工具元数据不随 SSE 下发，前端只能自备一份 zh/en 映射，
 * 按工具代号查。漏一行的后果不是报错，而是律师用户看到 {@code doc get outline} 这种
 * snake_case 兜底串（前端 toolDisplayName 的降级路径）。而后端加工具是最日常的动作，
 * 「记得去前端补一行」这种约定靠注释留不住——所以在这里钉死。
 *
 * <p>反向（前端有、后端没有）只告警不失败：后端下线一个工具时留几行死映射无害，
 * 而失败会逼着下线工具的人顺手改前端，反倒制造无关改动。
 *
 * <p>注册口径与 {@link ToolRegistry} 一致：扫 {@link AgentToolComponent} 实现类里的
 * {@code @Tool} 方法。插件工具（运行期从 jar 装）不在其内，也不该在其内——它们的名字
 * 由插件自己带，前端走兜底展示。
 */
class ToolDisplayNameCoverageTest {

    /** 从后端源码树定位前端那份映射：两侧在同一个仓库里，backend/ 的兄弟目录。 */
    private static final Path FRONTEND_MAP =
            Path.of("..", "frontend", "src", "utils", "toolDisplayNames.js");

    /** NAMES 表的一行：`  tool_code: { zh: '…', en: '…' },` */
    private static final Pattern MAP_ENTRY = Pattern.compile("^\\s{2}([a-z][a-z0-9_]*)\\s*:\\s*\\{", Pattern.MULTILINE);

    @Test
    @DisplayName("后端每个已注册工具在前端 toolDisplayNames.js 里都有名字")
    void everyRegisteredToolHasAFrontendName() throws IOException {
        // 单独跑 backend 模块（没有 frontend 目录）时跳过，而不是红：这条护栏是仓库级的
        assumeTrue(Files.isRegularFile(FRONTEND_MAP),
                "找不到 " + FRONTEND_MAP.toAbsolutePath().normalize() + "，跳过两侧对账");

        Set<String> backend = registeredToolNames();
        Set<String> frontend = frontendMappedNames();

        assertFalse(backend.isEmpty(), "一个工具都没扫到，说明扫描口径坏了，不是真的没有工具");
        assertFalse(frontend.isEmpty(), "前端映射一行都没解析出来，检查 MAP_ENTRY 正则是否跟文件形态脱节");

        List<String> missing = backend.stream().filter(name -> !frontend.contains(name)).sorted().toList();
        assertTrue(missing.isEmpty(),
                "这些工具在 frontend/src/utils/toolDisplayNames.js 里没有名字，过程卡会显示 "
                        + "snake_case 兜底串。补上 zh/en 两列即可：" + missing);
    }

    /**
     * 每行都得有 zh 与 en 两列。{@code toolDisplayName} 是
     * {@code isEnglish() ? entry.en : entry.zh}——缺一列不会报错，会把
     * {@code undefined} 画到过程卡上。和 locale 键对拍是同一类防静默失败的检查。
     */
    @Test
    @DisplayName("前端映射每行都有 zh 与 en 两列且都非空")
    void everyFrontendEntryHasBothLanguages() throws IOException {
        assumeTrue(Files.isRegularFile(FRONTEND_MAP), "找不到前端映射，跳过");

        String src = Files.readString(FRONTEND_MAP, StandardCharsets.UTF_8);
        Pattern row = Pattern.compile("^\\s{2}([a-z][a-z0-9_]*)\\s*:\\s*\\{([^}]*)}", Pattern.MULTILINE);
        List<String> broken = new ArrayList<>();
        Matcher m = row.matcher(src);
        while (m.find()) {
            String body = m.group(2);
            boolean zh = Pattern.compile("\\bzh\\s*:\\s*['\"][^'\"]").matcher(body).find();
            boolean en = Pattern.compile("\\ben\\s*:\\s*['\"][^'\"]").matcher(body).find();
            if (!zh || !en) {
                broken.add(m.group(1) + (zh ? "" : " 缺 zh") + (en ? "" : " 缺 en"));
            }
        }
        assertTrue(broken.isEmpty(),
                "这些行缺语言列，界面上会显示 undefined（toolDisplayName 直接取 entry.en/entry.zh）："
                        + broken);
    }

    @Test
    @DisplayName("前端映射里没有后端已不存在的死行（只告警，不失败）")
    void reportsStaleFrontendEntries() throws IOException {
        assumeTrue(Files.isRegularFile(FRONTEND_MAP), "找不到前端映射，跳过");

        Set<String> backend = registeredToolNames();
        Set<String> frontend = frontendMappedNames();
        // 规模打进日志：护栏失败时能立刻分辨「漏补一行」和「扫描口径坏了」
        System.out.println("[工具名对账] 后端已注册 " + backend.size()
                + " 个工具，前端映射 " + frontend.size() + " 行");
        List<String> stale = frontend.stream()
                .filter(name -> !backend.contains(name)).sorted().toList();
        if (!stale.isEmpty()) {
            // 无害（死映射不会被查到），但攒多了会让人误以为这些工具还在
            System.out.println("[提示] 前端映射里有 " + stale.size()
                    + " 行对应的后端工具已不存在，可顺手清理：" + stale);
        }
    }

    // ---------- 后端侧：按 ToolRegistry 的口径扫 ----------

    private static Set<String> registeredToolNames() {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AssignableTypeFilter(AgentToolComponent.class));

        Set<String> names = new LinkedHashSet<>();
        for (var candidate : scanner.findCandidateComponents("com.checkba")) {
            Class<?> type;
            try {
                type = Class.forName(candidate.getBeanClassName());
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException("扫到工具组件但加载不了：" + candidate.getBeanClassName(), e);
            }
            if (!isProductionClass(type)) continue;
            for (Method method : type.getMethods()) {
                if (method.isAnnotationPresent(Tool.class)) {
                    names.add(method.getName());
                }
            }
        }
        return names;
    }

    /**
     * 只认主源码编译出来的类。测试 classpath 上也有一批 {@link AgentToolComponent} 桩件
     * （eval harness 与各种 *_probe / echo 工具），它们不是产品工具，前端当然没有名字——
     * 不排掉的话这条护栏第一次跑就会拿桩件报一串假缺口。
     */
    private static boolean isProductionClass(Class<?> type) {
        var source = type.getProtectionDomain().getCodeSource();
        if (source == null || source.getLocation() == null) return true; // 取不到来源时宁可纳入
        String location = source.getLocation().getPath();
        return !location.contains("test-classes");
    }

    // ---------- 前端侧：解析 NAMES 表的键 ----------

    private static Set<String> frontendMappedNames() throws IOException {
        String src = Files.readString(FRONTEND_MAP, StandardCharsets.UTF_8);
        Set<String> names = new LinkedHashSet<>();
        Matcher m = MAP_ENTRY.matcher(src);
        List<String> all = new ArrayList<>();
        while (m.find()) {
            all.add(m.group(1));
        }
        names.addAll(all);
        return names;
    }
}
