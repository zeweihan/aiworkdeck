package com.checkba.service.evidence.webverify;

import java.util.List;

/**
 * 网核适配层 SPI（dev-board#100 P3，spec §7 拍板第 1 条：<b>只留接口</b>）。
 *
 * <p>本仓永远只有一个实现 {@link ManualWebVerifyProvider}——它不联网，只解外部工具导出的 zip。
 * 2026-08-21 维护者拍板：不做自动逐站爬取，不写任何登录、验证码、反爬相关代码；
 * 真要联网取数，由本产品之外的工具完成，用户手工把 zip 交进来。给这条 SPI 留位置，
 * 是为了将来接第三方合规数据服务时不用改导入/挂链那一侧的代码，<b>不是</b>为了以后自己去爬。
 */
public interface WebVerifyProvider {

    /** 适配器标识，会写进落盘文件的 {@code metaJson.provider}。 */
    String providerId();

    /**
     * 取一个主体的网核材料。离线适配器从 {@link WebVerifyRequest#archiveBytes()} 解包，
     * 不产生任何网络请求。
     *
     * @throws IllegalArgumentException 入参不合法、包结构不合法、超出规模上限（都要报清楚，不静默截断）
     */
    List<WebVerifyResult> verify(WebVerifyRequest req);
}
