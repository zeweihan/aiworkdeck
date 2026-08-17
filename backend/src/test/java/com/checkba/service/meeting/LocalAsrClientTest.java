package com.checkba.service.meeting;

import com.checkba.service.SystemSettingService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 本机转写探测的三态与文案。
 *
 * <p>用覆写 {@code getHealth} 的子类打桩：真发请求会让测试结果取决于本机装没装 asr-service，
 * 那样测试就不可信了（口径抄 {@code OllamaProbeServiceTest}）。
 */
class LocalAsrClientTest {

    /** 三个子串会被 api.js 判成掉线并清会话，所有面向用户的文案都不许命中。 */
    private static final List<String> LOGOUT_MARKERS = List.of("登录", "未授权", "请先");

    private static LocalAsrClient client(String healthBody) {
        SystemSettingService settings = mock(SystemSettingService.class);
        when(settings.get(eq(LocalAsrClient.SETTING_BASE_URL), anyString())).thenReturn("http://127.0.0.1:8890");
        return new LocalAsrClient(settings, "http://127.0.0.1:8890") {
            @Override
            String getHealth(String base) {
                return healthBody;
            }
        };
    }

    private static void assertNotMistakenForLogout(LocalAsrClient.ProbeResult r) {
        for (String forbidden : LOGOUT_MARKERS) {
            assertFalse(r.message().contains(forbidden), "message 命中「" + forbidden + "」：" + r.message());
            assertFalse(r.nextStep().contains(forbidden), "nextStep 命中「" + forbidden + "」：" + r.nextStep());
        }
    }

    @Test
    @DisplayName("服务在跑且模型已下 → READY")
    void ready() {
        LocalAsrClient.ProbeResult r = client(
                "{\"status\":\"ok\",\"model\":\"Systran/faster-whisper-medium\",\"modelReady\":true,\"diarization\":false}")
                .probe();

        assertEquals(LocalAsrClient.Status.READY, r.status());
        assertTrue(r.ready());
        assertEquals("Systran/faster-whisper-medium", r.model());
        assertFalse(r.diarization(), "本地档没有说话人分离，界面据此写明取舍");
        assertNotMistakenForLogout(r);
    }

    @Test
    @DisplayName("服务在跑但模型没下 → MODEL_MISSING（与 SERVICE_DOWN 分开，两者下一步完全不同）")
    void modelMissing() {
        LocalAsrClient.ProbeResult r = client(
                "{\"status\":\"ok\",\"model\":\"Systran/faster-whisper-medium\",\"modelReady\":false}").probe();

        assertEquals(LocalAsrClient.Status.MODEL_MISSING, r.status());
        assertFalse(r.ready());
        assertTrue(r.nextStep().contains("下载"), "下一步要指向下载模型：" + r.nextStep());
        assertNotMistakenForLogout(r);
    }

    @Test
    @DisplayName("连不上 → SERVICE_DOWN；下一步指向重启/组件管理，不是「下载模型」")
    void serviceDown() {
        LocalAsrClient.ProbeResult r = client(null).probe();

        assertEquals(LocalAsrClient.Status.SERVICE_DOWN, r.status());
        assertTrue(r.message().contains("没有运行"), r.message());
        assertFalse(r.nextStep().contains("下载模型"), "服务没起时让用户去下模型是错的指路：" + r.nextStep());
        assertNotMistakenForLogout(r);
    }

    @Test
    @DisplayName("响应不是 JSON 也当服务没起，不抛异常——探测挂在会议面板的装载路径上")
    void garbageResponseIsServiceDown() {
        LocalAsrClient.ProbeResult r = client("<html>502 Bad Gateway</html>").probe();

        assertEquals(LocalAsrClient.Status.SERVICE_DOWN, r.status());
        assertNotMistakenForLogout(r);
    }

    @Test
    @DisplayName("地址未注入时回落约定端口，不会拼出 http:///health 这种打不通的地址")
    void baseUrlFallsBackToConventionPort() {
        SystemSettingService settings = mock(SystemSettingService.class);
        when(settings.get(anyString(), anyString())).thenAnswer(inv -> inv.getArgument(1));

        assertEquals("http://127.0.0.1:8890", new LocalAsrClient(settings, "").baseUrl());
        assertEquals("http://127.0.0.1:9001", new LocalAsrClient(settings, "http://127.0.0.1:9001/").baseUrl(),
                "尾斜杠要去掉，否则拼出 //health");
    }
}
