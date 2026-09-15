// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.checkba.util;

/**
 * 产品标识的唯一来源（可溯源性设计规范附录 B3 / B4）。
 *
 * <p>两处对外可见的字符串都从这里派生，别在调用处再拼字面量：
 * <ul>
 *   <li>出站 HTTP User-Agent：{@code AIWorkDeck/<version> (<component>)}，见 {@link #userAgent(String)}；</li>
 *   <li>写进文档 core/extended properties 的 Application：{@code AI WorkDeck <version>}，见 {@link #applicationName()}。</li>
 * </ul>
 *
 * <p>版本号单一来源是 desktop/package.json：桌面壳启动后端时经 {@code AWD_APP_VERSION} 注入
 * （与 application.yml 的 telemetry.app-version 同源），开发态没有注入就是 {@code dev}。
 * 这里刻意不走 Spring 注入——调用点里有不受容器管理的对象（如手工 new 的模型客户端），
 * 静态读环境变量对所有调用点一视同仁。测试可用系统属性 {@code awd.app.version} 覆盖。
 *
 * <p>红线：只写产品名与版本，绝不携带用户身份、机器信息或任何可识别个人的内容。
 */
public final class ProductIdentity {

    /** 面向人的产品名（文档属性用）。 */
    public static final String PRODUCT_NAME = "AI WorkDeck";

    /** User-Agent 里的产品记号（RFC 9110 product token 不能带空格）。 */
    public static final String UA_PRODUCT = "AIWorkDeck";

    private static final String VERSION_PROPERTY = "awd.app.version";
    private static final String VERSION_ENV = "AWD_APP_VERSION";
    private static final String DEFAULT_VERSION = "dev";

    private ProductIdentity() {}

    /** 应用版本号；优先系统属性（测试用），其次 AWD_APP_VERSION，都没有则 dev。 */
    public static String version() {
        String v = System.getProperty(VERSION_PROPERTY);
        if (v == null || v.isBlank()) v = System.getenv(VERSION_ENV);
        if (v == null || v.isBlank()) return DEFAULT_VERSION;
        return v.trim();
    }

    /** 出站请求的 User-Agent：{@code AIWorkDeck/0.36.0 (browser-proxy)}。component 用小写连字符短词。 */
    public static String userAgent(String component) {
        if (component == null || component.isBlank()) {
            throw new IllegalArgumentException("component must not be blank");
        }
        return UA_PRODUCT + "/" + version() + " (" + component.trim() + ")";
    }

    /** 文档 Application 属性：{@code AI WorkDeck 0.36.0}。 */
    public static String applicationName() {
        return PRODUCT_NAME + " " + version();
    }
}
