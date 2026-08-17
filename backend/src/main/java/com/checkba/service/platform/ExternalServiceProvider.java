package com.checkba.service.platform;

import java.util.List;

/**
 * 外部服务的「谁来出钱出凭证」档位。
 *
 * <p>设置键 {@code external.<service>.provider}，写入口是 {@code AdminConfigController}。
 */
public enum ExternalServiceProvider {
    /** 平台代采：走网关，成本折算 Credits 从账户余额扣。 */
    PLATFORM,
    /** 自备 Key：用户/团队自己的供应商凭证，直连上游。 */
    BYOK,
    /** 本地模型：不出本机、零成本。只有 asr 有这一档。 */
    LOCAL;

    /** 一个服务在本产品里的完整描述：设置键、有没有 local 档、BYOK 凭证看哪些键。 */
    public record Descriptor(
            String service,
            boolean hasLocal,
            /** 判定「用户是不是已经自备了 Key」时要看的设置键，任一非空即算已配。 */
            List<String> byokCredentialKeys) {

        public String providerSettingKey() {
            return "external." + service + ".provider";
        }
    }

    public static final String SEARCH = "search";
    public static final String OCR = "ocr";
    public static final String ASR = "asr";
    public static final String QICHACHA = "qichacha";
    public static final String TUSHARE = "tushare";
    public static final String PKULAW = "pkulaw";

    /**
     * 走档位框架的六家。两个不在这里：
     *
     * <ul>
     *   <li><b>AI</b>：走「凭证下发 + 桌面直连」那条通路（{@code PlatformAiChannel}），
     *       不能改成网关代理，见设计文档 §3 通路 A。</li>
     *   <li><b>语音合成</b>：只剩本机 Kokoro 一条路，没有档可分。云端 ElevenLabs 那一档已整体移除
     *       （打包态本来就默认本机，且把它放进平台代采等于转售第三方语音合成）。</li>
     * </ul>
     */
    public static final List<Descriptor> ALL = List.of(
            new Descriptor(SEARCH, false, List.of("external.bocha.apiKey")),
            new Descriptor(OCR, false, List.of(
                    "external.aliyunOcr.accessKeyId", "external.aliyunOcr.accessKeySecret")),
            new Descriptor(ASR, true, List.of(
                    "meeting.asr.access-key-id", "meeting.asr.app-key", "meeting.oss.bucket")),
            new Descriptor(QICHACHA, false, List.of("external.qichacha.key", "external.qichacha.secret")),
            new Descriptor(TUSHARE, false, List.of("external.tushare.token")),
            new Descriptor(PKULAW, false, List.of("external.pkulaw.token")));

    public static Descriptor descriptor(String service) {
        return ALL.stream()
                .filter(d -> d.service().equals(service))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("未知的外部服务：" + service));
    }

    /**
     * 解析设置值。取值非法一律回 {@code fallback}，不抛异常——档位读取在请求路径上，
     * 一条脏数据不该把功能整个打死。
     */
    public static ExternalServiceProvider parse(String raw, ExternalServiceProvider fallback) {
        if (raw == null || raw.isBlank()) return fallback;
        return switch (raw.trim().toLowerCase()) {
            case "platform" -> PLATFORM;
            case "byok" -> BYOK;
            case "local" -> LOCAL;
            default -> fallback;
        };
    }

    public String settingValue() {
        return name().toLowerCase();
    }
}
