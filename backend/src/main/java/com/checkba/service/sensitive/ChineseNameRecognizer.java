// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.checkba.service.sensitive;

import com.checkba.model.SensitiveType;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.regex.Pattern;

/** Local surname candidates need a field, a title/action, or a delimited name/list context. */
final class ChineseNameRecognizer {
    record Span(int start, int end) {}
    private static final Pattern PREFIX = Pattern.compile(
            "(?:委托诉讼代理人|委托代理人|法定代表人|被申请人|申请人|被上诉人|上诉人|"
            + "联系人|负责人|经办人|代理人|签字人|签署人|承办人|审判长|审判员|书记员|"
            + "总经理|董事长|经理|律师|证人|原告|被告|甲方|乙方|丙方|姓名|签名|签字|"
            + "出席人员|参会人员|股东|由|与|和|及|向|联系|通知|感谢|交给|委托)"
            + "\\h*(?:[（(](?:签字|签名)[）)]\\h*)?[:：]?\\h*(?:\\r?\\n\\h*)?");
    private static final Pattern DELIMITED = Pattern.compile("(?<![\\p{IsHan}A-Za-z0-9_])(?=[\\p{IsHan}])");
    private static final Pattern LIST_HEADER = Pattern.compile("(?:姓名|名单|人员|签名|签字|股东|联系人|原告|被告)[:：]");
    private static final Pattern PERSON_ACTION = Pattern.compile(
            "(?:先生|女士|律师|法官|经理|董事|教授|医生|同志|书记|老师|同学|"
            + "表示|认为|确认|签署|签订|签收|提交|收到|支付|收取|委托|负责|同意|拒绝|"
            + "出席|参加|申请|请求|陈述|主张|承诺|担任|作证|共同|向(?:法院|本院|仲裁)|"
            + "已(?:确认|签署|签收|提交|收到)|的(?:证言|签名|签字|身份证|手机号)|"
            + "[，,]\\h*(?:[男女](?=[，,。\\s]|$)|[12][0-9]{3}年))");
    private static final Pattern COORDINATED_PERSON = Pattern.compile("(?:与|和|及|、)\\h*([\\p{IsHan}]{2,4})");
    private static final Pattern AFTER_NAME = Pattern.compile(
            "(?:先生|女士|律师|法官|经理|董事|教授|医生|同志|书记|老师|同学|"
            + "表示|认为|确认|签署|签订|签收|提交|收到|支付|收取|委托|负责|同意|拒绝|"
            + "出席|参加|申请|请求|陈述|主张|承诺|担任|作证|共同|系|诉|与|和|及|向|于|在|将|已|的|为|是|"
            + "[男女](?=[，,。\\s]|$)|[，,]\\h*(?:[男女](?=[，,。\\s]|$)|[12][0-9]{3}年))");
    private static final Pattern NON_NAME = Pattern.compile(
            "(?:公司|企业|集团|有限|股份|银行|法院|医院|事务所|代表|联系|负责|经办|签字|签名|"
            + "原告|被告|甲方|乙方|丙方|双方|当事|申请|请求|陈述|所有|应当|应于|应予|"
            + "履行|合同|协议|权利|义务|规定|通知|文件|时间|日期|地址|姓名|信息|内容|"
            + "填写|待定|暂无|不详|未知|不得|可以|常规|任意|向前|推进|质量|发展|"
            + "费用|金额|成本|价格|资金|金钱|余额|余款|项目|事项|管理|机关|条件|方式|公告|"
            + "情况|服务|使用|安排|通过|履约|成交|成功|成果|应收|应付|查验|查询|"
            + "先生|女士|律师|法官|经理|董事|教授|医生|书记|老师|同学|"
            + "王府井|马鞍山|张家口|景德镇|秦皇岛|石家庄|唐山|郑州|梅州|乐山|昆山)"
            + "|[已将应向由与及和的于在系为是男女省市区县镇村路街]$");
    private static final Pattern ADDRESS = Pattern.compile(
            "(?:注册地址|联系地址|地址|住址|住所地?|所在地)[：:\\h]*(?:为|是)?[^\\r\\n，。；;]{2,100}");
    private static final Pattern NON_PERSON_SUFFIX = Pattern.compile(
            "(?:省|市|区|县|镇|村|路|街|公司|集团|有限|股份|科技|实业|银行|医院|法院|事务所)");
    private static final Pattern PERSON_BEFORE_COMPANY = Pattern.compile("^(?:原告|被告|申请人|被申请人)([\\p{IsHan}]{2,4})(?:诉|与|向)");

    static int organizationStart(String value) {
        var party = PERSON_BEFORE_COMPANY.matcher(value);
        return party.find() && SensitiveType.CHINESE_NAME.isPlausible(party.group(1)) ? party.end() : 0;
    }

    static BitSet protectedRanges(String text) {
        BitSet protectedText = new BitSet(text.length());
        var company = SensitiveType.COMPANY.getPattern().matcher(text);
        while (company.find()) {
            int start = company.start() + organizationStart(company.group());
            protectedText.set(start, company.end());
        }
        var address = ADDRESS.matcher(text);
        while (address.find()) protectedText.set(address.start(), address.end());
        return protectedText;
    }

    static boolean allowsOccurrence(String text, int start, int end, BitSet protectedText) {
        int next = protectedText.nextSetBit(start);
        return (next < 0 || next >= end)
                && !NON_PERSON_SUFFIX.matcher(text).region(end, text.length()).lookingAt()
                && (end == text.length() || Character.UnicodeScript.of(text.charAt(end)) != Character.UnicodeScript.HAN
                    || AFTER_NAME.matcher(text).region(end, text.length()).lookingAt());
    }

    static List<Span> find(String text, BitSet protectedText) {
        BitSet starts = new BitSet(text.length());
        BitSet fieldStarts = new BitSet(text.length());
        var prefix = PREFIX.matcher(text);
        while (prefix.find()) {
            starts.set(prefix.end());
            if (!prefix.group().strip().matches("[由与和及向]")) fieldStarts.set(prefix.end());
        }
        var delimited = DELIMITED.matcher(text);
        while (delimited.find()) starts.set(delimited.start());
        List<Span> result = new ArrayList<>();
        for (int start = starts.nextSetBit(0); start >= 0; start = starts.nextSetBit(start + 1)) {
            int limit = start;
            while (limit < text.length() && limit - start < 4
                    && Character.UnicodeScript.of(text.charAt(limit)) == Character.UnicodeScript.HAN) limit++;
            for (int end = start + 2; end <= limit; end++) {
                String value = text.substring(start, end);
                if (!SensitiveType.CHINESE_NAME.isPlausible(value) || NON_NAME.matcher(value).find()) continue;
                boolean boundary = end == text.length() || !Character.isLetterOrDigit(text.charAt(end));
                boolean contextAfter = AFTER_NAME.matcher(text).region(end, text.length()).lookingAt();
                if (!boundary && !contextAfter) continue;
                boolean personAction = PERSON_ACTION.matcher(text).region(end, text.length()).lookingAt();
                var peer = COORDINATED_PERSON.matcher(text).region(end, text.length());
                if (!personAction && peer.lookingAt()) {
                    // Check each possible peer length so the next action cannot be swallowed into its name.
                    int peerStart = peer.start(1);
                    for (int peerEnd = peerStart + 2; peerEnd <= peer.end(1); peerEnd++) {
                        String name = text.substring(peerStart, peerEnd);
                        if (SensitiveType.CHINESE_NAME.isPlausible(name) && !NON_NAME.matcher(name).find()
                                && PERSON_ACTION.matcher(text).region(peerEnd, text.length()).lookingAt()) personAction = true;
                    }
                }
                if (!fieldStarts.get(start) && !personAction) {
                    int lineStart = text.lastIndexOf('\n', start - 1) + 1;
                    int lineEnd = text.indexOf('\n', end);
                    if (lineEnd < 0) lineEnd = text.length();
                    boolean alone = text.substring(lineStart, start).isBlank()
                            && text.substring(end, lineEnd).matches("[\\h。.!！?？]*");
                    boolean list = LIST_HEADER.matcher(text).region(Math.max(lineStart, start - 500), start).find();
                    if (!alone && !list) continue;
                }
                if (!allowsOccurrence(text, start, end, protectedText)) continue;
                result.add(new Span(start, end));
                break;
            }
        }
        return result;
    }
}
