package com.checkba.service.site;

/**
 * 一个「站点」的全部对外地址（双主站设计 §2.2）。
 *
 * <p>站点分的是**商业与合规**（币种、支付通道、发票、适用法、备案、默认语言、数据落点），
 * 不是模型可用性——桌面端所有 OpenRouter 请求都从用户本机直连 openrouter.ai，
 * 能不能用某个模型由出口 IP 决定，站点无从干预。
 *
 * <p>设计文档：{@code docs/superpowers/specs/2026-08-08-dual-site-architecture.md}
 *
 * @param id                 站点标识（{@code cn} / {@code intl}）
 * @param displayName        展示名，用于错误提示与设置页
 * @param baseUrl            账户服务基址（解锁门在线校验、账户连接、平台 AI 通道密钥）
 * @param registryBaseUrl    插件与 Skill 注册表基址（子路径 {@code /plugins}、{@code /skills}）
 * @param telemetryIngestUrl 匿名统计上报基址
 * @param accountPageUrl     账户页地址，供前端「前往官网」类链接使用
 */
public record SiteProfile(
        String id,
        String displayName,
        String baseUrl,
        String registryBaseUrl,
        String telemetryIngestUrl,
        String accountPageUrl) {

    public String pluginRegistryUrl() {
        return registryBaseUrl + "/plugins";
    }

    public String skillRegistryUrl() {
        return registryBaseUrl + "/skills";
    }
}
