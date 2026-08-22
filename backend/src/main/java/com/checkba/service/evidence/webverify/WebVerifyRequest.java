package com.checkba.service.evidence.webverify;

import java.util.List;

/**
 * 一次网核请求（dev-board#100 P3）。
 *
 * @param partyName                主体名（必填），同时是落盘目录 {@code _网核/<主体>/} 的那一段
 * @param unifiedSocialCreditCode  统一社会信用代码，可空（同名主体多时用来消歧，离线适配器只做记录）
 * @param sites                    要核查的站点；空或 null = 不筛，包里有什么收什么
 * @param archiveBytes             外部工具导出的 zip 字节。离线适配器
 *                                 （{@link ManualWebVerifyProvider}）必须有它；
 *                                 将来若有联网适配器则会忽略本字段自行取数——本仓不实现联网适配器
 */
public record WebVerifyRequest(String partyName,
                               String unifiedSocialCreditCode,
                               List<WebVerifySite> sites,
                               byte[] archiveBytes) {

    public WebVerifyRequest {
        sites = sites == null ? List.of() : List.copyOf(sites);
    }
}
