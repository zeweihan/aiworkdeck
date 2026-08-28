package com.checkba;

import com.checkba.service.ai.tools.WebTools;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * /api/mobile/* 全组端点的运行时集成测试：真实走 HTTP → 控制器参数绑定 →
 * MobileRelayStoreService → H2。重点锁两类只有真 MVC 才暴露的缝：
 * multipart/RequestParam 字段名与 iOS 客户端约定一致、PUT 体的嵌套 DTO 绑定。
 * 环境同 IdorAuthIntegrationTest（server 语义，local-mode 关）。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:mobile-relay-e2e;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;NON_KEYWORDS=VALUE;DB_CLOSE_DELAY=-1",
        "security.local-mode=false",
        "storage.local.root-path=${java.io.tmpdir}/mobile-relay-e2e-store"
})
@AutoConfigureMockMvc
@ActiveProfiles("desktop")
class MobileRelayEndpointIntegrationTest {

    private static final String MEDIA_ID = "0a1b2c3d-1111-4222-8333-444455556666";

    @Autowired
    private MockMvc mvc;
    @Autowired
    private ObjectMapper om;

    @MockBean
    private WebTools webTools;

    private String register(String username) throws Exception {
        String body = om.writeValueAsString(Map.of(
                "username", username, "password", "pw123456", "displayName", username));
        MvcResult r = mvc.perform(post("/api/auth/register").contentType(APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn();
        JsonNode json = om.readTree(r.getResponse().getContentAsString());
        String sid = json.path("data").path("sessionId").asText();
        assertFalse(sid.isEmpty(), "注册应返回 sessionId：" + r.getResponse().getContentAsString());
        return sid;
    }

    @Test
    void fullRelayRoundTrip() throws Exception {
        String desktop = register("desktop_" + System.nanoTime());

        // 未带凭据：全站 4010 信封（HTTP 200）——客户端按这个形态判鉴权失败
        mvc.perform(get("/api/mobile/projects"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(4010));
        mvc.perform(get("/api/mobile/devices"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(4010));

        // 1. 桌面推目录（嵌套 DTO 绑定）
        String dir = """
                {"deviceId":"dev-a","deviceName":"Mac","projects":[
                  {"key":"42","name":"金冠纾困"},{"key":"43","name":"probe"}]}""";
        mvc.perform(put("/api/mobile/projects").header("X-Session-Id", desktop)
                        .contentType(APPLICATION_JSON).content(dir))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.count").value(2));

        // 2. 手机读目录（同一账号）：裸数组
        mvc.perform(get("/api/mobile/projects").header("X-Session-Id", desktop))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].deviceId").value("dev-a"));

        // 3. 手机传影像：multipart 字段名与 iOS 约定一字不差
        MockMultipartFile file = new MockMultipartFile(
                "file", "现场影像-20260820-092011-0a1b.jpg",
                "application/octet-stream", "JPEG-BYTES".getBytes());
        mvc.perform(multipart("/api/mobile/media").file(file)
                        .param("deviceId", "dev-a")
                        .param("projectKey", "42")
                        .param("clientMediaId", MEDIA_ID)
                        .param("fileName", "现场影像-20260820-092011-0a1b.jpg")
                        .param("mediaType", "image")
                        .param("capturedAt", "2026-08-20T09:20:11Z")
                        .header("X-Session-Id", desktop))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.delivered").value(false));

        // 4. 桌面取件
        MvcResult inbox = mvc.perform(get("/api/mobile/inbox").param("deviceId", "dev-a")
                        .header("X-Session-Id", desktop))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].projectKey").value("42"))
                .andReturn();
        long itemId = om.readTree(inbox.getResponse().getContentAsString()).get(0).path("id").asLong();

        // 4.5 插件端设备清单（dev-board#250）：inbox 轮询已经打过心跳，dev-a 应显示在线，
        // 且带上第 1 步推送的两个项目
        mvc.perform(get("/api/mobile/devices").header("X-Session-Id", desktop))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].deviceId").value("dev-a"))
                .andExpect(jsonPath("$[0].online").value(true))
                .andExpect(jsonPath("$[0].projects.length()").value(2));

        // 5. 内容字节 + Content-Type（客户端靠它区分字节与信封）
        MvcResult content = mvc.perform(get("/api/mobile/inbox/" + itemId + "/content")
                        .header("X-Session-Id", desktop))
                .andExpect(status().isOk()).andReturn();
        assertEquals("JPEG-BYTES", content.getResponse().getContentAsString());
        assertTrue(content.getResponse().getContentType().startsWith("application/octet-stream"));

        // 6. ACK 后：取件清空、status=已投递
        mvc.perform(post("/api/mobile/inbox/" + itemId + "/ack").header("X-Session-Id", desktop))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0));
        mvc.perform(get("/api/mobile/inbox").param("deviceId", "dev-a").header("X-Session-Id", desktop))
                .andExpect(jsonPath("$.length()").value(0));
        mvc.perform(get("/api/mobile/media/status").param("clientMediaIds", MEDIA_ID)
                        .header("X-Session-Id", desktop))
                .andExpect(jsonPath("$[0].delivered").value(true));

        // 7. 越权：另一个账号看不到、也 ACK 不了
        String stranger = register("stranger_" + System.nanoTime());
        mvc.perform(get("/api/mobile/projects").header("X-Session-Id", stranger))
                .andExpect(jsonPath("$.length()").value(0));
        mvc.perform(post("/api/mobile/inbox/" + itemId + "/ack").header("X-Session-Id", stranger))
                .andExpect(jsonPath("$.code").value(org.hamcrest.Matchers.not(0)));
    }
}
