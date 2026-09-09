// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.controller;

import com.checkba.service.pack.ModelPresence;
import com.checkba.service.pack.NativePackService;
import com.checkba.service.pack.OptionalComponents;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 可选组件只读接口（设计 §3.2 / §4.1）。四个 Python 服务运行时 pack 的注册表是
 * 面板、自动补下跳过名单、工具侧 component_required 三处共用的唯一事实来源。
 */
class PackControllerOptionalComponentsTest {

    @Test
    @DisplayName("四个可选组件的 id / 服务名 / 模型 id 与文案键固定，顺序即面板卡片顺序")
    void registryIsStable() {
        List<OptionalComponents.Entry> all = OptionalComponents.ALL;
        assertEquals(4, all.size());
        assertEquals(List.of("pptx-runtime", "mineru-runtime", "kokoro-runtime", "asr-runtime"),
                all.stream().map(OptionalComponents.Entry::packId).toList());
        assertEquals(List.of("pptx-service", "mineru-service", "kokoro-service", "asr-service"),
                all.stream().map(OptionalComponents.Entry::service).toList());
        assertNull(all.get(0).modelId(), "pptx 没有模型");
        assertEquals("mineru-models", all.get(1).modelId());
        assertEquals("kokoro-models", all.get(2).modelId());
        assertEquals("asr-models", all.get(3).modelId());
        assertTrue(all.get(0).featureKeys().contains("pdfToWordLayout"));
    }

    @Test
    @DisplayName("端点回四条，state/installed/模型状态/体积都从服务拿，且一次网络请求都不发")
    void listsFourComponentsWithoutNetwork() {
        NativePackService packs = mock(NativePackService.class);
        ModelPresence models = mock(ModelPresence.class);
        NativePackService.PackStatus ready = new NativePackService.PackStatus();
        ready.setState(NativePackService.STATE_READY);
        ready.setInstalledVersion("1.0.0");
        when(packs.status(anyString())).thenReturn(new NativePackService.PackStatus());
        when(packs.status("kokoro-runtime")).thenReturn(ready);
        when(packs.knownSizes(anyString())).thenReturn(new NativePackService.Sizes(0, 0));
        when(packs.knownSizes("kokoro-runtime")).thenReturn(new NativePackService.Sizes(200_000_000L, 760_000_000L));
        when(models.installed("kokoro-models")).thenReturn(true);

        List<Map<String, Object>> out = PackController.optionalComponentViews(packs, models);

        assertEquals(4, out.size());
        Map<String, Object> kokoro = out.get(2);
        assertEquals("kokoro-runtime", kokoro.get("packId"));
        assertEquals("kokoro-service", kokoro.get("service"));
        assertEquals(Boolean.TRUE, kokoro.get("installed"));
        assertEquals("1.0.0", kokoro.get("installedVersion"));
        assertEquals(200_000_000L, kokoro.get("downloadBytes"));
        assertEquals(760_000_000L, kokoro.get("unpackedBytes"));
        assertEquals(Boolean.TRUE, kokoro.get("modelInstalled"));
        assertEquals(314_572_800L, kokoro.get("modelBytes"));
        assertEquals(Boolean.FALSE, out.get(0).get("installed"));
        assertEquals(0L, out.get(0).get("modelBytes"), "pptx 无模型，体积 0");
        // 本端点不许发网络请求：镜像不可达时 20s 超时 × 两个源会把首次登录面板拖死
        verify(packs, never()).info(anyString());
    }
}
