package com.checkba;

import com.atlassian.oai.validator.OpenApiInteractionValidator;
import com.atlassian.oai.validator.report.LevelResolver;
import com.atlassian.oai.validator.report.ValidationReport;
import com.checkba.service.ai.tools.WebTools;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static com.atlassian.oai.validator.mockmvc.OpenApiValidationMatchers.openApi;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * API 契约测试：手机端调用的六个端点，真实响应必须合 src/main/resources/openapi/mobile-v1.yaml。
 * 只校验响应（请求级别 IGNORE，multipart 请求校验在该库里不稳）。
 * 环境配方同 MobileRelayEndpointIntegrationTest。移动仓 contract/api/ 钉的是这份 YAML 的副本。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:mobile-api-contract;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;NON_KEYWORDS=VALUE;DB_CLOSE_DELAY=-1",
        "security.local-mode=false",
        "storage.local.root-path=${java.io.tmpdir}/mobile-api-contract-store"
})
@AutoConfigureMockMvc
@ActiveProfiles("desktop")
class MobileApiContractTest {

    private static OpenApiInteractionValidator validator;

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper om;
    @MockBean private WebTools webTools;

    @BeforeAll
    static void loadSpec() throws Exception {
        String spec = new String(
                MobileApiContractTest.class.getResourceAsStream("/openapi/mobile-v1.yaml").readAllBytes(),
                StandardCharsets.UTF_8);
        validator = OpenApiInteractionValidator.createForInlineApiSpecification(spec)
                .withLevelResolver(LevelResolver.create()
                        .withLevel("validation.request", ValidationReport.Level.IGNORE)
                        .build())
                .build();
    }

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
    void handsetEndpointsMatchSpec() throws Exception {
        String sid = register("contract_" + System.nanoTime());
        String mediaId = "0a1b2c3d-2222-4333-8444-555566667777";

        // 未登录信封也在契约里
        mvc.perform(get("/api/mobile/projects"))
                .andExpect(status().isOk())
                .andExpect(openApi().isValid(validator));

        // 桌面推目录，让 GET /projects 有内容
        mvc.perform(put("/api/mobile/projects").header("X-Session-Id", sid)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"deviceId":"dev-c","deviceName":"Mac","projects":[{"key":"1","name":"契约项目"}]}"""))
                .andExpect(status().isOk());

        mvc.perform(get("/api/mobile/projects").header("X-Session-Id", sid))
                .andExpect(status().isOk())
                .andExpect(openApi().isValid(validator));

        MockMultipartFile file = new MockMultipartFile(
                "file", "现场影像-20260902-170000-0a1b.jpg", "application/octet-stream", "JPEG".getBytes());
        mvc.perform(multipart("/api/mobile/media").file(file)
                        .param("deviceId", "dev-c").param("projectKey", "1")
                        .param("clientMediaId", mediaId)
                        .param("fileName", "现场影像-20260902-170000-0a1b.jpg")
                        .param("mediaType", "image")
                        .param("capturedAt", "2026-09-02T17:00:00Z")
                        .header("X-Session-Id", sid))
                .andExpect(status().isOk())
                .andExpect(openApi().isValid(validator));

        mvc.perform(get("/api/mobile/media/status").param("clientMediaIds", mediaId)
                        .header("X-Session-Id", sid))
                .andExpect(status().isOk())
                .andExpect(openApi().isValid(validator));

        mvc.perform(get("/api/mobile/media/usage").header("X-Session-Id", sid))
                .andExpect(status().isOk())
                .andExpect(openApi().isValid(validator));
    }

    @Test
    void smsLoginEnvelopesMatchSpec() throws Exception {
        // 不真发短信：非法号码走 code 1 分支，信封形状仍受契约约束
        mvc.perform(post("/api/auth/sms-login/send-code").contentType(APPLICATION_JSON)
                        .content("{\"phone\":\"123\"}"))
                .andExpect(status().isOk())
                .andExpect(openApi().isValid(validator));

        mvc.perform(post("/api/auth/sms-login/verify").contentType(APPLICATION_JSON)
                        .content("{\"phone\":\"13800000000\",\"code\":\"000000\"}"))
                .andExpect(status().isOk())
                .andExpect(openApi().isValid(validator));
    }
}
