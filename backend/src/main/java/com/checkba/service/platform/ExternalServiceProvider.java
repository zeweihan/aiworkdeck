package com.checkba.service.platform;

import java.util.List;

/**
 * 外部服务的「谁来出钱出凭证」档位。
 *
 * <p>形态与 {@code TtsService} 已有的 {@code elevenlabs | local} 同构，不发明新的：
 * 设置键 {@code external.<service>.provider}，写入口仍是 {@code AdminConfigController}。
 */
public enum ExternalServiceProvider {
    /** 平台代采：走网关，成本折算 Credits 从账户余额扣。 */
    PLATFORM,
    /** 自备 Key：用户/团队自己的供应商凭证，直连上游。 */
    BYOK,
    /** 本地模型：不出本机、零成本。只有 asr 与 tts 有这一档。 */
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
    public static final String TTS = "tts";
    public static final String ASR = "asr";
    public static final String QICHACHA = "qichacha";
    public static final String TUSHARE = "tushare";
    public static final String PKULAW = "pkulaw";

    /**
     * 八家里除 AI 之外的七家。AI 不在这里——它走的是「凭证下发 + 桌面直连」那条通路
     * （{@code PlatformAiChannel}），不能改成网关代理，见设计文档 §3 通路 A。
     */
    public static final List<Descriptor> ALL = List.of(
            new Descriptor(SEARCH, false, List.of("external.bocha.apiKey")),
            new Descriptor(OCR, false, List.of(
                    "external.aliyunOcr.accessKeyId", "external.aliyunOcr.accessKeySecret")),
            new Descriptor(TTS, true, List.of("external.elevenlabs.apiKey")),
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
     * 解析设置值。
     *
     * <p><b>{@code elevenlabs} 是存量别名</b>：TTS 的档位键在本改造之前就存在，
     * 取值是 {@code elevenlabs | local}。语义上「用 ElevenLabs」就是「自备 Key」，
     * 所以映射到 BYOK——不映射的话，存量安装升级后会落到 else 分支被当成 platform，
     * 用户已经填好的 ElevenLabs Key 就静默失效了。
     */
    public static ExternalServiceProvider parse(String raw, ExternalServiceProvider fallback) {
        if (raw == null || raw.isBlank()) return fallback;
        return switch (raw.trim().toLowerCase()) {
            case "platform" -> PLATFORM;
            case "byok", "elevenlabs" -> BYOK;
            case "local" -> LOCAL;
            default -> fallback;
        };
    }

    public String settingValue() {
        return name().toLowerCase();
    }
}
