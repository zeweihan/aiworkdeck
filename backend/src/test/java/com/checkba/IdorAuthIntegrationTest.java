package com.checkba;

import com.checkba.service.ai.tools.WebTools;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 越权（IDOR）边界的运行时端到端集成测试。
 *
 * 真实走完 HTTP → 控制器 → AuthController 会话 → ProjectMemberService 成员校验 → H2 数据库 全栈，
 * 验证"同一登录用户无法跨项目访问他人资源"在运行时确实生效——把静态审查升级为可复现的回归。
 *
 * 环境同 DesktopContextSmokeTest：内存 H2（MODE=PostgreSQL），零外部依赖。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:idor-e2e;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;NON_KEYWORDS=VALUE;DB_CLOSE_DELAY=-1"
})
@AutoConfigureMockMvc
@ActiveProfiles("desktop")
class IdorAuthIntegrationTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper om;

    /** 真 WebTools 的 @PostConstruct 会起线程预热 Playwright，测试里挡掉 */
    @MockBean
    private WebTools webTools;

    private String register(String username) throws Exception {
        String body = om.writeValueAsString(Map.of(
                "username", username, "password", "pw123456", "displayName", username));
        MvcResult r = mvc.perform(post("/api/auth/register").contentType(APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn();
        JsonNode json = om.readTree(r.getResponse().getContentAsString());
        String sid = json.path("data").path("sessionId").asText();
        assertTrue(sid != null && !sid.isEmpty(), "注册应返回 sessionId：" + r.getResponse().getContentAsString());
        return sid;
    }

    @Test
    void crossProjectAccessIsDeniedButOwnerAllowed() throws Exception {
        String alice = register("alice_" + System.nanoTime());
        String bob = register("bob_" + System.nanoTime());

        // alice 创建一个空白项目
        String createBody = om.writeValueAsString(Map.of("projectType", "BLANK", "name", "Alice机密项目"));
        MvcResult cr = mvc.perform(post("/api/projects").header("X-Session-Id", alice)
                        .contentType(APPLICATION_JSON).content(createBody))
                .andExpect(status().isOk()).andReturn();
        long projectId = om.readTree(cr.getResponse().getContentAsString()).path("id").asLong();
        assertTrue(projectId > 0, "创建项目应返回 id：" + cr.getResponse().getContentAsString());

        // bob（非成员）不能读 alice 的项目元数据
        mvc.perform(get("/api/projects/" + projectId).header("X-Session-Id", bob))
                .andExpect(jsonPath("$.code").value(1))
                .andExpect(jsonPath("$.message").value(containsString("无权")));

        // bob 不能读成员名单
        mvc.perform(get("/api/projects/" + projectId + "/members").header("X-Session-Id", bob))
                .andExpect(jsonPath("$.code").value(1));

        // bob 不能列尽调清单
        mvc.perform(get("/api/dd/projects/" + projectId).header("X-Session-Id", bob))
                .andExpect(jsonPath("$.code").value(1));

        // alice（创建者/成员）可以正常读自己的项目
        mvc.perform(get("/api/projects/" + projectId).header("X-Session-Id", alice))
                .andExpect(jsonPath("$.id").value(projectId));
    }
}
