// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.service.pack;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 四个「可选组件」的唯一注册表（设计 §3.1 / §4.1）。
 *
 * <p>它同时是三处的判据：首次登录面板与组件管理页的卡片（{@code /api/packs/optional-components}）、
 * {@link PackAutoInstaller} 的跳过名单（这四个包<b>绝不</b>自动补下，用户要求提示后再下）、
 * 以及工具侧 {@code component_required} 的 payload。写成三份必然漂移。
 *
 * <p>modelBytes 是给面板显示「含模型约多大」的估计值，与 desktop/main/services/model-manager.js
 * 的 sizeHint 同源（3 GB / 300 MB / 1.5 GB）；运行时体积不写死，从 manifest 读。
 */
public final class OptionalComponents {

    /** 一个可选组件：pack、它带起来的服务、配套模型（可空）与解锁的功能文案键。 */
    public record Entry(String packId, String service, String modelId, long modelBytes, List<String> featureKeys) {}

    public static final List<Entry> ALL = List.of(
            new Entry("pptx-runtime", "pptx-service", null, 0L,
                    List.of("pptxGenerate", "pptxFormat", "pdfToWordLayout", "scannedOcrEntry")),
            new Entry("mineru-runtime", "mineru-service", "mineru-models", 3L * 1024 * 1024 * 1024,
                    List.of("scannedPdfToWord", "ocrParse")),
            new Entry("kokoro-runtime", "kokoro-service", "kokoro-models", 300L * 1024 * 1024,
                    List.of("ttsPanel")),
            new Entry("asr-runtime", "asr-service", "asr-models", 1536L * 1024 * 1024,
                    List.of("localTranscription")));

    public static final Set<String> PACK_IDS =
            Set.copyOf(new LinkedHashSet<>(ALL.stream().map(Entry::packId).toList()));

    public static boolean isOptionalRuntime(String packId) {
        return packId != null && PACK_IDS.contains(packId);
    }

    public static Entry byPackId(String packId) {
        return ALL.stream().filter(e -> e.packId().equals(packId)).findFirst().orElse(null);
    }

    /** 按服务名反查（工具侧只知道自己打不通哪个服务）。 */
    public static Entry byService(String service) {
        return ALL.stream().filter(e -> e.service().equals(service)).findFirst().orElse(null);
    }

    private OptionalComponents() {}
}
