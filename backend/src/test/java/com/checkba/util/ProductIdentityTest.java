// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.checkba.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 出站产品标识的两条契约（可溯源性设计规范附录 A9 / B3）。
 *
 * <p><b>格式契约</b>：{@link ProductIdentity#userAgent(String)} 的形状是
 * {@code AIWorkDeck/<version> (<component>)}，{@link ProductIdentity#applicationName()} 是
 * {@code AI WorkDeck <version>}。这两个串对外可见（服务端日志、文档属性），是黑盒可判别的胎记，
 * 形状漂了就对不上号。
 *
 * <p><b>字面量契约</b>：出站 UA 只许从 {@link ProductIdentity} 派生，不许在调用处再拼字面量。
 * 收敛前散着 {@code checkba-browser/1.0} / {@code AI-WorkDeck} / {@code AI-WorkDeck-Capability-Installer}
 * 三种写法，谁也说不清一共有几处。这条靠扫源码守住——反射看不见「有人又抄了一个字面量进来」。
 *
 * <p>白名单只有两处，都是<b>刻意模仿浏览器</b>的 UA，不是产品标识：
 * {@code WebTools}（Playwright 抓页，冒充 Chrome 才拿得到正常页面）与
 * {@code CninfoAnnouncementService}（巨潮反爬）。把它们换成产品 UA 是功能改动，不是整洁化。
 */
class ProductIdentityTest {

    // ---------------------------------------------------------------- 格式契约

    private static final String VERSION_PROPERTY = "awd.app.version";

    @Test
    @DisplayName("userAgent / applicationName 的形状固定，版本号可被系统属性覆盖")
    void identityStringsHaveFixedShape() {
        String before = System.getProperty(VERSION_PROPERTY);
        try {
            System.clearProperty(VERSION_PROPERTY);
            assertTrue(ProductIdentity.userAgent("x").matches("^AIWorkDeck/[^ ]+ \\(x\\)$"),
                    "UA 形状必须是 AIWorkDeck/<version> (<component>)，实际: " + ProductIdentity.userAgent("x"));

            System.setProperty(VERSION_PROPERTY, "1.2.3");
            assertEquals("AIWorkDeck/1.2.3 (x)", ProductIdentity.userAgent("x"));
            assertEquals("AI WorkDeck 1.2.3", ProductIdentity.applicationName());
        } finally {
            if (before == null) System.clearProperty(VERSION_PROPERTY);
            else System.setProperty(VERSION_PROPERTY, before);
        }
    }

    // -------------------------------------------------------------- 字面量契约

    /** surefire 的工作目录是 backend/。 */
    private static final Path MAIN_JAVA = Path.of("src/main/java");

    /** 收敛掉的旧字面量：再出现就是有人绕过了 ProductIdentity。 */
    private static final List<String> RETIRED_LITERALS =
            List.of("checkba-browser/1.0", "\"AI-WorkDeck\"", "AI-WorkDeck-Capability");

    /** 只有这两个文件许用自己的 USER_AGENT 常量——它们冒充的是浏览器，不是我们的产品。 */
    private static final Set<String> BROWSER_UA_FILES =
            Set.of("WebTools.java", "CninfoAnnouncementService.java");

    /** 抓 "User-Agent" 后面那个实参（同一行内），{@code .header(...)} 与 {@code singletonMap(...)} 两种写法都吃。 */
    private static final Pattern UA_ARGUMENT = Pattern.compile("\"User-Agent\"\\s*,\\s*([^\\r\\n]+)");

    private static List<Path> mainSources() throws IOException {
        assertTrue(Files.isDirectory(MAIN_JAVA),
                "测试工作目录须为 backend/，找不到 " + MAIN_JAVA.toAbsolutePath());
        try (Stream<Path> walk = Files.walk(MAIN_JAVA)) {
            List<Path> files = walk.filter(p -> p.toString().endsWith(".java")).sorted().toList();
            // 空断言护栏：扫描本身塌掉时（路径错、过滤写反）不许静静地全绿
            assertTrue(files.size() > 300, "只扫到 " + files.size() + " 个源文件，扫描八成塌了");
            return files;
        }
    }

    @Test
    @DisplayName("收敛掉的旧 UA 字面量不许再出现在 backend 主源码里")
    void retiredUserAgentLiteralsAreGone() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (Path f : mainSources()) {
            String src = Files.readString(f, StandardCharsets.UTF_8);
            for (String literal : RETIRED_LITERALS) {
                if (src.contains(literal)) offenders.add(f + " 含 " + literal);
            }
        }
        assertTrue(offenders.isEmpty(),
                "出站 UA 必须走 ProductIdentity.userAgent(...)，别再拼字面量:\n  "
                        + String.join("\n  ", offenders));
    }

    @Test
    @DisplayName("每一处 User-Agent 实参要么来自 ProductIdentity，要么是两个浏览器伪装文件的 USER_AGENT")
    void everyUserAgentComesFromProductIdentity() throws IOException {
        List<String> offenders = new ArrayList<>();
        int fromProductIdentity = 0;
        int fromBrowserDisguise = 0;

        for (Path f : mainSources()) {
            String name = f.getFileName().toString();
            if (name.equals("ProductIdentity.java")) continue; // 只是 javadoc 里提到 User-Agent
            Matcher m = UA_ARGUMENT.matcher(Files.readString(f, StandardCharsets.UTF_8));
            while (m.find()) {
                String arg = m.group(1).trim();
                if (arg.startsWith("ProductIdentity.userAgent(")) {
                    fromProductIdentity++;
                } else if (arg.startsWith("USER_AGENT") && BROWSER_UA_FILES.contains(name)) {
                    fromBrowserDisguise++;
                } else {
                    offenders.add(f + " -> " + arg);
                }
            }
        }

        assertTrue(offenders.isEmpty(),
                "出站 User-Agent 只许从 ProductIdentity.userAgent(...) 取；"
                        + "浏览器伪装 UA 只许出现在 " + BROWSER_UA_FILES + ":\n  "
                        + String.join("\n  ", offenders));
        // 空断言护栏：正则一旦匹配不上，上面的 offenders 也会是空的
        assertTrue(fromProductIdentity >= 3,
                "只认出 " + fromProductIdentity + " 处产品 UA，至少应有 3 处"
                        + "（browser-proxy / capability-installer / ai-gateway），正则八成没匹配上");
        assertTrue(fromBrowserDisguise >= 4,
                "只认出 " + fromBrowserDisguise + " 处浏览器伪装 UA，至少应有 4 处"
                        + "（WebTools 1 处 + CninfoAnnouncementService 3 处），正则八成没匹配上");
    }
}
