package com.checkba;

import com.checkba.model.entity.AgentRunRecord;
import com.checkba.model.entity.ProjectAiMessage;
import com.checkba.repository.AgentRunRecordRepository;
import com.checkba.repository.ProjectAiMessageRepository;
import com.checkba.repository.UserRepository;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.LocalDateTime;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * GET /api/projects/{projectId}/conversations 的端到端护栏。
 *
 * 单元测试全在 mock 之上，三件事只有走完真 HTTP 才验得到：before 参数的字符串 →
 * LocalDateTime 绑定、「本页游标能否原样当下一页入参」的闭环、以及列表层不再按 userId
 * 过滤这条可见性语义变更。
 *
 * 失败一律是 HTTP 200 + {code:1,message}（GlobalExceptionHandler:69-77 的全站口径），
 * 所以每个请求都断言 status().isOk()，成败看 code 字段。
 *
 * 环境同 IdorAuthIntegrationTest：内存 H2（MODE=PostgreSQL）。desktop profile 自带的
 * security.local-mode=true 会把所有请求解析成同一个本机用户，「跨用户」前提不复存在，故显式关闭。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:project-conversations-e2e;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;NON_KEYWORDS=VALUE;DB_CLOSE_DELAY=-1",
        "security.local-mode=false"
})
@AutoConfigureMockMvc
@ActiveProfiles("desktop")
class ProjectConversationsEndpointIntegrationTest {

    private static final LocalDateTime BASE = LocalDateTime.of(2026, 8, 8, 10, 0, 12);

    @Autowired
    private MockMvc mvc;
    @Autowired
    private ObjectMapper om;
    @Autowired
    private ProjectAiMessageRepository messageRepository;
    @Autowired
    private AgentRunRecordRepository runRecordRepository;
    @Autowired
    private UserRepository userRepository;

    /** 真 WebTools 的 @PostConstruct 会起线程预热 Playwright，测试里挡掉 */
    @MockBean
    private WebTools webTools;

    private String register(String username) throws Exception {
        String body = om.writeValueAsString(Map.of(
                "username", username, "password", "pw123456", "displayName", username));
        MvcResult r = mvc.perform(post("/api/auth/register").contentType(APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn();
        return om.readTree(r.getResponse().getContentAsByteArray()).path("data").path("sessionId").asText();
    }

    private long createProject(String sessionId, String name) throws Exception {
        String body = om.writeValueAsString(Map.of("projectType", "BLANK", "name", name));
        MvcResult r = mvc.perform(post("/api/projects").header("X-Session-Id", sessionId)
                        .contentType(APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn();
        return om.readTree(r.getResponse().getContentAsByteArray()).path("id").asLong();
    }

    private void msg(Long projectId, Long userId, String conversationId,
                     String role, String content, String title, LocalDateTime createdAt) {
        ProjectAiMessage m = new ProjectAiMessage();
        m.setProjectId(projectId);
        m.setUserId(userId);
        m.setConversationId(conversationId);
        m.setRole(role);
        m.setContent(content);
        m.setConversationTitle(title);
        m.setCreatedAt(createdAt);
        messageRepository.save(m);
    }

    private JsonNode getConversations(long projectId, String sessionId, String query) throws Exception {
        MockHttpServletRequestBuilder request =
                get("/api/projects/" + projectId + "/conversations" + query);
        if (sessionId != null) {
            request = request.header("X-Session-Id", sessionId);
        }
        // 失败也走 HTTP 200 + {code:1}，所以这里恒断言 isOk()
        MvcResult r = mvc.perform(request).andExpect(status().isOk()).andReturn();
        return om.readTree(r.getResponse().getContentAsByteArray());
    }

    /** 把一页的两个游标字段拼成下一页的 query。 */
    private String nextQuery(JsonNode page) {
        return "?limit=1&before=" + page.path("data").path("nextBefore").asText()
                + "&beforeId=" + page.path("data").path("nextBeforeId").asText();
    }

    @Test
    void 列表层跨用户可见_复合游标翻页不丢条_未登录与非成员被拒() throws Exception {
        String aliceName = "alice_" + System.nanoTime();
        String aliceSid = register(aliceName);
        String bobName = "bob_" + System.nanoTime();
        String bobSid = register(bobName);
        long projectId = createProject(aliceSid, "股东会核查案卷");
        long aliceId = userRepository.findByUsername(aliceName).orElseThrow().getId();
        long bobId = userRepository.findByUsername(bobName).orElseThrow().getId();

        // alice 的项目里四个会话：c-new 由 bob 发起（bob 不是成员，但消息在库里），
        // c-tie-a / c-tie-b 最后活跃时间完全相同 —— 单字段游标会在这里丢一条。
        msg(projectId, aliceId, "c-old", "USER", "股东会通知的届次对不对", "股东会材料核查", BASE);
        msg(projectId, aliceId, "c-old", "ASSISTANT", "已核对通知与决议的届次", null, BASE.plusMinutes(1));
        msg(projectId, bobId, "c-new", "USER", "帮我起草一份股权转让协议", null, BASE.plusHours(1));
        msg(projectId, aliceId, "c-tie-a", "USER", "同一时刻落库的甲", null, BASE.plusHours(2));
        msg(projectId, aliceId, "c-tie-b", "USER", "同一时刻落库的乙", null, BASE.plusHours(2));

        AgentRunRecord running = new AgentRunRecord();
        running.setConversationId("c-new");
        running.setStatus("RUNNING");
        running.setProjectId(projectId);
        running.setUpdatedAt(BASE.plusHours(1));
        runRecordRepository.save(running);

        // 1) 不带 session：必须是「未登录」，不许静默返回空数组
        JsonNode anon = getConversations(projectId, null, "");
        assertEquals(1, anon.path("code").asInt(), "无 session 必须走失败信封：" + anon);
        assertEquals("未登录", anon.path("message").asText());

        // 2) bob 不是这个项目的成员：被 hasReadPermission 挡掉
        JsonNode outsider = getConversations(projectId, bobSid, "");
        assertEquals(1, outsider.path("code").asInt(), "非成员必须被拒：" + outsider);
        assertEquals("无权访问该项目", outsider.path("message").asText());

        // 3) 第一页：最近活跃且 conversationId 更大的 c-tie-b
        JsonNode page1 = getConversations(projectId, aliceSid, "?limit=1");
        assertEquals(0, page1.path("code").asInt(), "成员必须放行：" + page1);
        assertEquals("c-tie-b", page1.path("data").path("conversations").get(0)
                .path("conversationId").asText());
        assertEquals("2026-08-08T12:00:12", page1.path("data").path("nextBefore").asText());
        assertEquals("c-tie-b", page1.path("data").path("nextBeforeId").asText());

        // 4) 第二页：把两个游标字段原样传回去。这一步同时验证 @DateTimeFormat 绑定，
        //    以及「与游标同一时刻的另一个会话不能被丢掉」。
        JsonNode page2 = getConversations(projectId, aliceSid, nextQuery(page1));
        assertEquals(0, page2.path("code").asInt(), "游标参数应能被绑定：" + page2);
        assertEquals("c-tie-a", page2.path("data").path("conversations").get(0)
                        .path("conversationId").asText(),
                "同一时刻落库的另一个会话必须还在 —— 单字段游标会把它永久丢掉");

        // 5) 第三页：bob 发起的会话，项目成员也看得到；运行状态来自表
        JsonNode page3 = getConversations(projectId, aliceSid, nextQuery(page2));
        JsonNode third = page3.path("data").path("conversations").get(0);
        assertEquals("c-new", third.path("conversationId").asText());
        assertEquals(bobId, third.path("ownerUserId").asLong(),
                "列表层不按 userId 过滤：别人发起的会话，项目成员也看得到");
        assertEquals(bobName, third.path("ownerName").asText(), "发起人显示名取 displayName");
        assertEquals("RUNNING", third.path("runStatus").asText(),
                "runStatus 来自 agent_run_record 表，不是 AgentRunStateService 的内存 Map");
        assertTrue(third.path("content").isMissingNode(), "列表层一行正文都不下发");

        // 6) 第四页：最后一条，两个游标字段都归 null
        JsonNode page4 = getConversations(projectId, aliceSid, nextQuery(page3));
        JsonNode fourth = page4.path("data").path("conversations").get(0);
        assertEquals("c-old", fourth.path("conversationId").asText());
        assertEquals("股东会材料核查", fourth.path("title").asText());
        assertEquals("已核对通知与决议的届次", fourth.path("lastMessage").asText(),
                "预览走服务端 extractPreview，前端不再清洗");
        assertTrue(page4.path("data").path("nextBefore").isNull(), "没有下一页时 nextBefore 为 null");
        assertTrue(page4.path("data").path("nextBeforeId").isNull(), "nextBeforeId 同样归 null");
    }
}
