package com.checkba.service.evidence.webverify;

import java.util.List;
import java.util.Locale;

/**
 * 网核站点枚举（dev-board#100 P3）。
 *
 * <p><b>这里刻意不放任何 URL、登录参数或抓取逻辑</b>：2026-08-21 维护者拍板「网核只留接口，
 * 不做自动逐站爬取，不碰验证码与合规风险」（spec §6 / §7 第 1 条）。枚举只是「这份材料来自哪个站」
 * 的标识，用于落盘文件名、locator 与后续按站点归类；真正的取数由外部工具在本产品之外完成，
 * 用户手工把导出的 zip 交进来。
 *
 * <p>{@link #aliases()} 只服务于「从 manifest 的 site 字段或文件名里认出站点」这一件事，
 * 认不出就是 {@link #OTHER}，不猜。
 */
public enum WebVerifySite {

    /** 国家企业信用信息公示系统。 */
    CREDIT_PUBLICITY("credit_publicity", "企业信用信息公示", List.of("国家企业信用信息公示系统", "企业信用信息公示系统", "信用公示", "工商公示", "gsxt")),

    /** 中国裁判文书网。 */
    JUDGMENT_DOCS("judgment_docs", "裁判文书", List.of("中国裁判文书网", "裁判文书网", "裁判文书", "wenshu")),

    /** 失信被执行人（中国执行信息公开网）。 */
    DISHONEST_EXECUTEE("dishonest_executee", "失信被执行人", List.of("失信被执行人", "失信人", "执行信息公开", "shixin")),

    /** 被执行人（与失信被执行人分开：一个是有无未结执行案件，一个是有无失信记录）。 */
    EXECUTEE("executee", "被执行人", List.of("被执行人", "执行案件", "zhixing")),

    /** 环保行政处罚。 */
    ENV_PENALTY("env_penalty", "环保处罚", List.of("环保处罚", "环境处罚", "环保行政处罚", "生态环境处罚")),

    /** 行政处罚（环保以外的一般行政处罚）。 */
    ADMIN_PENALTY("admin_penalty", "行政处罚", List.of("行政处罚", "信用中国")),

    /** 知识产权（商标/专利/著作权）检索。 */
    INTELLECTUAL_PROPERTY("intellectual_property", "知识产权", List.of("知识产权", "商标", "专利", "著作权")),

    /** 认不出站点时的兜底。落盘照常，只是站点标识为「其他」。 */
    OTHER("other", "其他", List.of());

    private final String code;
    private final String label;
    private final List<String> aliases;

    WebVerifySite(String code, String label, List<String> aliases) {
        this.code = code;
        this.label = label;
        this.aliases = aliases;
    }

    /** 机器可读标识（manifest 的 site 字段、REST 的 sites 参数用这个）。 */
    public String code() {
        return code;
    }

    /** 中文短名，同时是落盘文件名的站点段。 */
    public String label() {
        return label;
    }

    public List<String> aliases() {
        return aliases;
    }

    /**
     * 从 manifest 的 site 字段或文件名片段里认站点：先比 code / 枚举名 / 中文短名（全等，忽略大小写），
     * 再比别名（子串命中）。认不出返回 {@link #OTHER}；入参为空返回 null（表示「没写」，与「写了但不认识」不同）。
     */
    public static WebVerifySite parse(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.isEmpty()) return null;
        String lower = s.toLowerCase(Locale.ROOT);
        for (WebVerifySite site : values()) {
            if (site.code.equals(lower) || site.name().toLowerCase(Locale.ROOT).equals(lower)
                    || site.label.equals(s)) {
                return site;
            }
        }
        for (WebVerifySite site : values()) {
            for (String alias : site.aliases) {
                if (s.contains(alias) || lower.contains(alias.toLowerCase(Locale.ROOT))) return site;
            }
        }
        return OTHER;
    }
}
