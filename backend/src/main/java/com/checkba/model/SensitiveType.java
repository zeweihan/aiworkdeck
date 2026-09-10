// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.model;

import lombok.Getter;

import java.util.regex.Pattern;

/**
 * Enum representing sensitive data types with their patterns and masking strategies
 */
@Getter
public enum SensitiveType {
    PHONE(
        "PHONE",
        "手机号",
        "(?<!\\d)1[3-9]\\d{9}(?!\\d)",
        "138****1234",
        "保留前3后4位"
    ),
    
    ID_CARD(
        "ID_CARD",
        "身份证号",
        "(?<![0-9Xx])(?:\\d{17}[0-9Xx]|\\d{15})(?![0-9Xx])",
        "3301**********1234",
        "保留前6后4位"
    ),
    
    EMAIL(
        "EMAIL",
        "邮箱",
        "(?<![A-Za-z0-9._%+-])[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,63}(?![A-Za-z0-9.-])",
        "a***@example.com",
        "保留首字母和域名"
    ),
    
    BANK_CARD(
        "BANK_CARD",
        "银行卡号",
        "(?<!\\d)\\d{16,19}(?!\\d)",
        "6222******8888",
        "保留前6后4位"
    ),
    
    COMPANY(
        "COMPANY", "公司名称",
        "[\\p{IsHan}A-Za-z0-9（）()·&]{2,70}?(?:股份有限公司|有限责任公司|有限責任公司|有限公司|有限合伙企业|有限合夥企業|合伙企业|分公司|律师事务所|律師事務所|会计师事务所|會計師事務所)",
        "[公司]", "公司全称后缀识别；简称可手工补充"
    ),

    /**
     * 已下线自动检测（dev-board#531，维护者 2026-09-09 拍板），只保留枚举值本身。
     *
     * <p>这条模式是 [一-龥]{2,4}——中文文书里几乎每个词都是 2~4 个汉字，「甲方」「北京市」
     * 「有限公司」「被告」统统命中，整篇被涂成 甲*／被*／有**司。中文姓名没有身份证的
     * mod-11-2、银行卡的 Luhn 那样的客观校验位，纯正则分不出「张三」和「本条」，收紧不了。
     *
     * <p>产品口径：<b>法律文书里漏涂比误涂安全</b>。文书必须逐字可引，把正文改坏的代价比漏一个
     * 名字更大，而漏涂还有人工复核兜底。姓名改由用户在脱敏面板的「要涂黑的姓名/词语」里手填
     * （自定义词，逐字面量匹配）。
     *
     * <p>枚举值保留是为了兼容：存量记录里可能存着这个 code，老客户端也可能还会传它——
     * 解析得出来，但 {@link #isAutoDetect()} 为 false，检测端一律跳过，不产生任何改动。
     */
    CHINESE_NAME(
        "CHINESE_NAME",
        "中文姓名",
        "(?:(?:姓名|联系人|法定代表人|负责人|经办人|委托代理人|代理人)[：:\\h]*|(?:原告|被告|甲方|乙方)[：:]\\h*)(?<value>[\\p{IsHan}]{2,4})(?=$|[^\\p{IsHan}]|先生|女士)",
        "张**",
        "保留姓氏",
        false
    ),
    
    FIXED_PHONE(
        "FIXED_PHONE",
        "固定电话",
        "(?<!\\d)0\\d{2,3}-?\\d{7,8}(?!\\d)",
        "010-****5678",
        "保留区号和后4位"
    ),
    
    ADDRESS(
        "ADDRESS",
        "地址",
        "(?:地址|住所地|住所|住址|注册地址|联系地址)[：:\\h]+(?<value>[^\\r\\n，。；;]{6,80})",
        "[地址]",
        "完整遮蔽地址字段"
    ),
    
    PASSWORD(
        "PASSWORD",
        "密码",
        "(?i)(?:密码|口令|password|passwd|pwd)[：:\\h]+(?<value>[^\\s，。；;]{1,128})",
        "******",
        "完全遮蔽"
    ),
    
    CAR_LICENSE(
        "CAR_LICENSE",
        "车牌号",
        "[京津沪渝冀豫云辽黑湘皖鲁新苏浙赣鄂桂甘晋蒙陕吉闽贵粤青藏川宁琼使领][A-Z][A-Z0-9]{5}",
        "浙A***23",
        "保留前2后2位"
    ),
    
    IPV4(
        "IPV4",
        "IPv4地址",
        "((25[0-5]|2[0-4]\\d|[01]?\\d\\d?)\\.){3}(25[0-5]|2[0-4]\\d|[01]?\\d\\d?)",
        "192.168.*.*",
        "保留前两段"
    ),
    
    IPV6(
        "IPV6",
        "IPv6地址",
        "(([0-9a-fA-F]{1,4}:){7}[0-9a-fA-F]{1,4})",
        "2001:0db8:****:****:****:****:****:****",
        "保留前两段"
    );

    private final String code;
    private final String label;
    private final Pattern pattern;
    private final String example;
    private final String description;
    /**
     * 是否参与自动检测。false 的类型不会出现在 /api/sensitive/options 的可勾选清单里，
     * 即便调用方硬传它的 code，检测端也一律跳过（见 SensitiveService）。
     */
    private final boolean autoDetect;

    SensitiveType(String code, String label, String regex, String example, String description) {
        this(code, label, regex, example, description, true);
    }

    SensitiveType(String code, String label, String regex, String example, String description,
                  boolean autoDetect) {
        this.code = code;
        this.label = label;
        this.pattern = Pattern.compile(regex);
        this.example = example;
        this.description = description;
        this.autoDetect = autoDetect;
    }

    /** 参与自动检测的类型（面板可勾选的那些）。 */
    public static java.util.List<SensitiveType> autoDetectTypes() {
        return java.util.Arrays.stream(values()).filter(SensitiveType::isAutoDetect).toList();
    }

    /**
     * Mask the original string based on the type's strategy
     */
    /**
     * 这个候选串是不是真的像它被判定的那类信息。
     *
     * <p>纯正则分不开「18 位立案号」与「18 位身份证号」——它们长得一模一样。
     * 身份证有 GB 11643 的 mod-11-2 校验位、银行卡有 Luhn 校验位，
     * 用它们能把绝大多数误判挡掉；而**校验不过的号本来就不是有效的身份证/银行卡**，
     * 给它打码是误伤，不是保护。法律文书必须逐字可引，改坏正文的代价不比漏打码小。
     *
     * <p>其余类型没有可用的校验位，一律放行（行为不变）。
     */
    public boolean isPlausible(String candidate) {
        if (candidate == null || candidate.isEmpty()) return false;
        return switch (this) {
            case ID_CARD -> isPlausibleIdCard(candidate);
            case BANK_CARD -> candidate.matches("[1-9][0-9]{15,18}") && luhnValid(candidate);
            case CHINESE_NAME -> isPlausibleName(candidate);
            case ADDRESS -> candidate.matches(".*[省市区县路街道村镇号室].*");
            default -> true;
        };
    }

    private static boolean isPlausibleName(String value) {
        if (java.util.Set.of("公司", "双方", "当事人", "法定代表", "负责人", "联系人", "有限公司", "合同", "申请人").contains(value)) return false;
        String surnames = "赵钱孙李周吴郑王冯陈褚卫蒋沈韩杨朱秦尤许何吕施张孔曹严华金魏陶姜戚谢邹喻柏水窦章云苏潘葛奚范彭郎鲁韦昌马苗凤花方俞任袁柳鲍史唐费廉岑薛雷贺倪汤滕殷罗毕郝邬安常乐于时傅皮卞齐康伍余元卜顾孟平黄穆萧尹姚邵汪毛禹狄米贝明臧计伏成戴谈宋茅庞熊纪舒屈项祝董梁杜阮蓝闵席季麻强贾路娄危江童颜郭梅盛林刁钟徐邱骆高夏蔡田樊胡凌霍万支柯管卢莫房解应宗丁宣邓郁单杭洪包诸左石崔吉龚程邢裴陆荣翁荀羊甄封储靳汲邴糜松井段富巫乌焦巴弓牧隗山谷车侯宓蓬全班仰秋仲伊宫宁仇栾甘厉戎祖武符刘景詹束龙叶幸司黎印宿白怀蒲邰从鄂索咸赖卓蔺屠蒙池乔阴胥能苍双闻莘党翟谭贡劳逄姬申扶堵冉宰郦雍桑桂濮牛寿通边扈燕冀浦尚农温庄晏柴瞿阎连茹习艾鱼容向古易慎戈廖庾终暨居衡步都耿满弘匡国文寇广禄阙东欧殳沃利蔚越夔隆师巩厍聂晁勾敖融冷訾辛阚那简饶空曾毋沙乜养鞠须丰巢关蒯相查后荆红游竺权逯盖益桓公";
        return surnames.indexOf(value.charAt(0)) >= 0;
    }

    /** 18 位查 mod-11-2 校验位与出生日期；15 位没有校验位，只查出生日期。 */
    private static boolean isPlausibleIdCard(String value) {
        if (value.length() == 15) {
            return isRealDate("19" + value.substring(6, 12));
        }
        if (value.length() != 18) return false;
        if (!isRealDate(value.substring(6, 14))) return false;
        int[] weights = {7, 9, 10, 5, 8, 4, 2, 1, 6, 3, 7, 9, 10, 5, 8, 4, 2};
        char[] codes = {'1', '0', 'X', '9', '8', '7', '6', '5', '4', '3', '2'};
        int sum = 0;
        for (int i = 0; i < 17; i++) {
            char c = value.charAt(i);
            if (c < '0' || c > '9') return false;
            sum += (c - '0') * weights[i];
        }
        return Character.toUpperCase(value.charAt(17)) == codes[sum % 11];
    }

    /** yyyyMMdd 是不是一个真实存在的日期（顺带挡掉 2026 年之后与 1900 年之前的荒谬值）。 */
    private static boolean isRealDate(String yyyyMMdd) {
        try {
            java.time.LocalDate d = java.time.LocalDate.parse(
                    yyyyMMdd, java.time.format.DateTimeFormatter.BASIC_ISO_DATE);
            int year = d.getYear();
            return year >= 1900 && !d.isAfter(java.time.LocalDate.now());
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean luhnValid(String value) {
        int sum = 0;
        boolean doubleIt = false;
        for (int i = value.length() - 1; i >= 0; i--) {
            char c = value.charAt(i);
            if (c < '0' || c > '9') return false;
            int d = c - '0';
            if (doubleIt) {
                d *= 2;
                if (d > 9) d -= 9;
            }
            sum += d;
            doubleIt = !doubleIt;
        }
        return sum % 10 == 0;
    }

    public String mask(String original) {
        if (original == null || original.isEmpty()) {
            return original;
        }
        
        int len = original.length();
        
        switch (this) {
            case COMPANY:
                return "[公司]";
            case PHONE:
                if (len == 11) return original.substring(0, 3) + "****" + original.substring(7);
                break;
                
            case ID_CARD:
                if (len > 10) return original.substring(0, 6) + "********" + original.substring(len - 4);
                break;
                
            case EMAIL:
                int atIndex = original.indexOf("@");
                if (atIndex >= 1) return original.substring(0, 1) + "***" + original.substring(atIndex);
                break;
                
            case BANK_CARD:
                if (len > 10) return original.substring(0, 6) + "******" + original.substring(len - 4);
                break;
                
            case CHINESE_NAME:
                if (len == 2) return original.substring(0, 1) + "*";
                if (len > 2) return original.substring(0, 1) + "*".repeat(len - 1);
                break;
                
            case FIXED_PHONE:
                // Handle formats like 010-12345678 or 01012345678
                String cleaned = original.replace("-", "");
                if (cleaned.length() >= 8) {
                    String areaCode = cleaned.substring(0, Math.min(4, cleaned.length() - 7));
                    String suffix = cleaned.substring(cleaned.length() - 4);
                    return areaCode + "-****" + suffix;
                }
                break;
                
            case ADDRESS:
                return "[地址]";
                
            case PASSWORD:
                return "******";
                
            case CAR_LICENSE:
                if (len == 7) return original.substring(0, 2) + "***" + original.substring(5);
                break;
                
            case IPV4:
                String[] parts = original.split("\\.");
                if (parts.length == 4) return parts[0] + "." + parts[1] + ".*.*";
                break;
                
            case IPV6:
                String[] segments = original.split(":");
                if (segments.length >= 2) {
                    return segments[0] + ":" + segments[1] + ":****:****:****:****:****:****";
                }
                break;
        }
        
        return original; // Fallback
    }

    /**
     * Get SensitiveType by code
     */
    public static SensitiveType fromCode(String code) {
        for (SensitiveType type : values()) {
            if (type.code.equals(code)) {
                return type;
            }
        }
        return null;
    }
}
