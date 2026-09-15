// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.checkba.util;

import com.checkba.service.document.DocumentGeneratorSettings;
import org.apache.poi.ooxml.POIXMLDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 往我们生成的 OOXML 文件里写 extended properties 的 Application（可溯源性设计规范附录 B4）。
 *
 * <p>写的是 OOXML 标准字段 {@code docProps/app.xml} 的 {@code <Application>}，Word / WPS / Pages
 * 全都读得懂并原样保留，右键属性即可见——对用户完全透明，不改正文一个字节。
 *
 * <p>红线：<b>只写 Application</b>。Company / Manager / creator / lastModifiedBy 这些可能携带
 * 用户身份的字段一个都不碰（POI 默认就是空，这里也不去填）。值统一从
 * {@link ProductIdentity#applicationName()} 派生，调用处不许再拼字面量。
 *
 * <p>开关是 {@code document.generator.enabled}（缺省开）：有些律所对文档元数据敏感，
 * 交付前要 scrub，关掉之后这里一个字段都不写。
 */
public final class DocumentGeneratorStamp {

    private static final Logger log = LoggerFactory.getLogger(DocumentGeneratorStamp.class);

    private DocumentGeneratorStamp() {}

    /**
     * 在 write 之前调用一次。{@code enabled} 为 false 或 doc 为 null 时什么都不做。
     *
     * <p>写元数据失败绝不能连累文档本身的落盘——这条链路上游是脱敏输出、会议纪要、
     * 时间轴导出这些真业务产物，为了一个可选的标识把文件写坏是本末倒置。
     */
    public static void apply(POIXMLDocument doc, boolean enabled) {
        if (doc == null || !enabled) return;
        try {
            doc.getProperties().getExtendedProperties().getUnderlyingProperties()
                    .setApplication(ProductIdentity.applicationName());
        } catch (Exception e) {
            log.debug("写入文档 Application 元数据失败，跳过（不影响文档本身）", e);
        }
    }

    /**
     * 调用处的一行式重载：直接把注入进来的开关服务递过来。
     *
     * <p>{@code settings} 为 null 时按缺省的「开」处理——只有脱离 Spring 容器手工 new 出来的
     * 单测对象才会是 null（{@code @Autowired(required = false)} 字段没被填），产品运行时永远有 bean。
     */
    public static void apply(POIXMLDocument doc, DocumentGeneratorSettings settings) {
        apply(doc, enabled(settings));
    }

    /** 开关求值，语义同上（null 即缺省的「开」）。给拿不到注入字段的静态方法用。 */
    public static boolean enabled(DocumentGeneratorSettings settings) {
        return settings == null || settings.enabled();
    }
}
