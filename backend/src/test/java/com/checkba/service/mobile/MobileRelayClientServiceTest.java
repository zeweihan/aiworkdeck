package com.checkba.service.mobile;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 桌面侧手机同步客户端的纯函数契约：
 * - 落盘文件名 = 原名 + clientMediaId 前 8 位（跨轮重试的幂等锚点，路径字符剥掉）；
 * - 归档日期取值顺序 capturedAt → createdAt → 今天。
 * HTTP 编排部分由本地双实例全链路验证（见 spec 验证一节），不在单测里桩。
 */
class MobileRelayClientServiceTest {

    private static final String MEDIA_ID = "0a1b2c3d-1111-4222-8333-444455556666";

    @Test
    @DisplayName("落盘文件名：扩展名前插 clientMediaId 前 8 位；路径字符剥掉；无扩展名追加在尾")
    void landedFileNameContract() {
        assertEquals("IMG_0001-0a1b2c3d.jpg",
                MobileRelayClientService.landedFileName("IMG_0001.jpg", MEDIA_ID));
        assertEquals("passwd-0a1b2c3d",
                MobileRelayClientService.landedFileName("../../etc/passwd", MEDIA_ID));
        assertEquals("media-00000000",
                MobileRelayClientService.landedFileName("", "x"));
        // 同一件影像重试两轮生成同一个名字——幂等的根据
        assertEquals(MobileRelayClientService.landedFileName("a.mov", MEDIA_ID),
                MobileRelayClientService.landedFileName("a.mov", MEDIA_ID));
    }

    @Test
    @DisplayName("归档日期：capturedAt 优先，缺了用 createdAt，都没有用今天")
    void captureDateContract() throws Exception {
        ObjectMapper m = new ObjectMapper();
        assertEquals("2026-08-19", MobileRelayClientService.captureDate(
                m.readTree("{\"capturedAt\":\"2026-08-19T21:02:03\",\"createdAt\":\"2026-08-20T01:00:00\"}")));
        assertEquals("2026-08-20", MobileRelayClientService.captureDate(
                m.readTree("{\"createdAt\":\"2026-08-20T01:00:00\"}")));
        assertEquals(LocalDate.now().toString(), MobileRelayClientService.captureDate(
                m.readTree("{\"capturedAt\":\"not-a-date\"}")));
    }
}
