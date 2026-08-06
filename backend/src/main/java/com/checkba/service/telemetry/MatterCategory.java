package com.checkba.service.telemetry;

/**
 * 法律事项类别枚举 v1（设计 §5.6）。行业报告的叙事单位：
 * 口径粗、稳定、可扩展；display 值即落库与上报的字符串。
 * 修改需同步官网仓看板与 skill.yml 的 category 取值。
 */
public enum MatterCategory {
    CORPORATE_GOVERNANCE("公司治理"),
    CAPITAL_MARKETS("资本市场证券"),
    M_AND_A("并购交易"),
    DISPUTE_RESOLUTION("争议解决"),
    CONTRACT("合同审查起草"),
    COMPLIANCE("合规监管"),
    IP("知识产权"),
    EMPLOYMENT("劳动人事"),
    INSOLVENCY("破产重整"),
    OTHER_LEGAL("其他法律事务"),
    NON_LEGAL("非法律事务");

    private final String display;

    MatterCategory(String display) {
        this.display = display;
    }

    public String display() {
        return display;
    }

    /** 宽松解析：匹配 display 或枚举名；解析失败归 OTHER_LEGAL */
    public static MatterCategory parse(String value) {
        if (value == null) return OTHER_LEGAL;
        String v = value.trim();
        for (MatterCategory c : values()) {
            if (c.display.equals(v) || c.name().equalsIgnoreCase(v)) return c;
        }
        return OTHER_LEGAL;
    }
}
