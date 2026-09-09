// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.service;

import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 股票代码解析服务
 *
 * 说明：
 * - 用于根据 A 股证券代码反查公司简称，用于后续再去企查查按名称查询；
 * - 当前实现基于新浪行情公开接口，不依赖额外密钥；
 * - 后续如需切换到更稳定的数据源，可以在不影响调用方的前提下替换实现。
 */
@Service
public class StockCodeService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(StockCodeService.class);

    /**
     * 根据 6 位股票代码解析公司名称（简称）
     *
     * @param stockCode 例如 600010、000001 等
     * @return 解析出的公司名称；解析失败时返回 null
     */
    public String resolveCompanyName(String stockCode) {
        if (!StringUtils.hasText(stockCode)) {
            return null;
        }
        String code = stockCode.trim();
        // 仅处理标准 6 位数字代码
        if (!code.matches("\\d{6}")) {
            return null;
        }

        // 简单判断交易所前缀：6 开头视为上交所，其余常见代码视为深交所
        String prefix = code.startsWith("6") ? "sh" : "sz";
        String url = "https://hq.sinajs.cn/list=" + prefix + code;

        try {
            HttpResponse resp = HttpRequest.get(url)
                    // 该接口通常要求带一个 Referer，避免被判为爬虫
                    .header("Referer", "https://finance.sina.com.cn")
                    .timeout(5000)
                    .execute();

            if (resp.getStatus() != 200) {
                log.warn("StockCodeService resolveCompanyName http status not ok, code={}, status={}", code, resp.getStatus());
                return null;
            }

            String body = resp.body();
            // 响应格式示例：var hq_str_sh600010="包钢股份,1.23,1.24,...";
            int firstQuote = body.indexOf('"');
            int secondQuote = body.indexOf('"', firstQuote + 1);
            if (firstQuote < 0 || secondQuote <= firstQuote + 1) {
                log.warn("StockCodeService resolveCompanyName parse quote failed, code={}, body={}", code, body);
                return null;
            }

            String inside = body.substring(firstQuote + 1, secondQuote);
            if (!StringUtils.hasText(inside)) {
                return null;
            }

            String[] parts = inside.split(",");
            if (parts.length == 0) {
                return null;
            }

            String name = parts[0].trim();
            return StringUtils.hasText(name) ? name : null;
        } catch (Exception e) {
            log.error("StockCodeService resolveCompanyName error, code={}", stockCode, e);
            return null;
        }
    }
}


