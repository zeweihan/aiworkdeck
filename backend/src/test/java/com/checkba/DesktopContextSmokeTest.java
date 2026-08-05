package com.checkba;

import com.checkba.service.ai.ToolRegistry;
import com.checkba.service.ai.tools.WebTools;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * desktop profile 上下文启动冒烟测试。
 *
 * 背景：PR #95 引入的 bean 循环依赖（ToolRegistry ← SubAgentTools →
 * SubAgentService → ToolRegistry，PR #98 用 @Lazy 修复）在单测全绿的情况下
 * 直到 CI 桌面冒烟测试才暴露——此前没有任何测试真正 refresh 过 Spring 容器。
 * 本测试把「启动即崩」类回归拦在 mvn test 阶段。
 *
 * 环境取自 CI 冒烟测试同款 desktop profile（零外部依赖启动）：
 * - 数据源覆盖为内存 H2，避免在开发机上写 ~/.aiworkdeck/local.mv.db；
 * - PgVector / Ollama embedding 不可达时走既有的快速回退路径，无需 mock。
 */
@SpringBootTest(properties = {
        // desktop profile 的 H2 文件库换成内存库（去掉 AUTO_SERVER，加 DB_CLOSE_DELAY 保活）
        "spring.datasource.url=jdbc:h2:mem:ctx-smoke;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;NON_KEYWORDS=VALUE;DB_CLOSE_DELAY=-1",
        // 授权状态目录隔离到临时目录，避免测试读到开发机真实的 ~/.aiworkdeck/license.json
        "security.license.dir=${java.io.tmpdir}/awd-ctx-smoke-license"
})
@ActiveProfiles("desktop")
class DesktopContextSmokeTest {

    /**
     * desktop profile 现在带 security.local-mode=true，LocalIdentityService 构造时
     * 会静态注册到 AuthController。surefire 同 JVM 跑全部测试，且 Spring 缓存上下文
     * 不会在切回其他上下文时重置静态字段——不清理的话，后续鉴权类测试
     * （IdorAuthIntegrationTest 等）的 getUserIdFromSession 会被本机用户身份劫持。
     */
    @org.junit.jupiter.api.AfterAll
    static void resetLocalIdentityStatic() {
        com.checkba.controller.AuthController.registerLocalIdentityService(null);
    }

    /** 真 WebTools 的 @PostConstruct 会起线程预热/下载 Playwright 浏览器，测试里挡掉 */
    @MockBean
    private WebTools webTools;

    @Autowired
    private ToolRegistry toolRegistry;

    @Test
    void contextLoadsWithDesktopProfile() {
        // 能注入进来即代表容器 refresh 成功（循环依赖等启动期问题会直接让本测试失败）
        assertFalse(toolRegistry.getAllSpecifications().isEmpty(),
                "ToolRegistry 应在容器启动后注册到内置工具");
    }
}
