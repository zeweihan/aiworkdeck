// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.checkba.service;

import com.checkba.service.sensitive.SensitiveTextEngine;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import java.util.List;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;

class SensitiveChineseNameRecognitionTest {
    @TempDir Path dir;
    static Stream<Arguments> surnameContexts() {
        return List.of("张小明", "李小红", "王立新", "陈安平", "欧阳文静", "司马文博", "上官云",
                "诸葛文清", "尉迟正", "司徒海", "令狐安", "第五明", "长孙平", "单晓光", "曾志远",
                "解明", "仇云", "肖文清", "覃明", "区建华", "朴正宇", "佟小宁")
                .stream().flatMap(name -> List.of("联系人：%s。", "法定代表人%s，男。", "请联系%s律师。",
                        "由%s签署本合同。", "%s的证言已收到。")
                        .stream().map(template -> Arguments.of(template.formatted(name), name)));
    }

    @ParameterizedTest
    @MethodSource("surnameContexts")
    void surnameAndContextCombinations(String text, String name) {
        detectsNamesWithoutCustomInput(text, name);
    }
    @ParameterizedTest
    @CsvSource(value = {
        "联系人：张三。|张三", "法定代表人李明，男。|李明", "负责人：王建国。|王建国",
        "委托诉讼代理人：欧阳明。|欧阳明", "经办人：司马懿。|司马懿",
        "姓名：上官婉儿。|上官婉儿", "签名：诸葛亮。|诸葛亮", "证人：尉迟恭。|尉迟恭",
        "原告张三诉被告李四。|张三", "原告张三诉被告李四。|李四",
        "请联系王建国律师。|王建国", "张三先生确认收到。|张三", "李小红女士已签收。|李小红",
        "由陈小明签署本合同。|陈小明", "赵丽向法院提交材料。|赵丽", "王伟表示同意。|王伟",
        "出席人员：张三、李四、欧阳娜娜。|欧阳娜娜", "姓名：单田芳。|单田芳",
        "联系人：曾小贤。|曾小贤", "签字人：王为民。|王为民", "张三|张三",
        "证人李华作证。|李华", "张三，男，1990年出生。|张三", "王建国，1988年出生。|王建国",
        "张三和李四共同签订本合同。|张三", "张三和李四共同签订本合同。|李四"
    }, delimiter = '|')
    void detectsNamesWithoutCustomInput(String text, String name) {
        SensitiveTextEngine engine = new SensitiveTextEngine(List.of("CHINESE_NAME"), true, List.of(), List.of());
        engine.learn(text);
        String masked = engine.apply(text);
        assertFalse(masked.contains(name), masked);
        assertTrue(engine.recovery().containsValue(name), engine.recovery().toString());
        assertEquals(text, SensitiveTextEngine.apply(masked, SensitiveTextEngine.restoreEdits(masked, engine.recovery())));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "本合同由甲方北京市朝阳区某科技有限公司与乙方签订，被告应于判决生效之日起十日内履行。",
        "双方应当履行合同。乙方应当。被告申请。原告请求。",
        "姓名：待填写。联系人：暂无。负责人：待定。",
        "甲方：有限公司。乙方：公司。原告：申请人。",
        "法定代表人签字。负责人签名。联系人信息。姓名不得为空。",
        "王府井、马鞍山、张家口、唐山市、北京市。",
        "负责通知，申请受理，陈述意见，向前推进，高质量发展。",
        "公司名称：张三科技有限公司。住址：李明路12号。",
        "股东：北京星河科技有限公司。原告：上海市人民法院。",
        "所有权、合同法、常规、说明、时间、文件、通知、以及。",
        "费用支付完毕。金额已确认。时间安排合理。项目负责人签字。",
        "费用：100元。金额：200元。管理费用、工作计划、成果、活动、风险。",
        "金钱的使用应当合理。与金钱有关。高额的费用已支付。余款将于周五结清。"
    })
    void preservesOrdinaryProseAndNonPersonFields(String text) {
        SensitiveTextEngine engine = new SensitiveTextEngine(List.of("CHINESE_NAME"), true, List.of(), List.of());
        engine.learn(text);
        assertEquals(text, engine.apply(text));
    }

    @Test
    void learnsNamesAcrossParagraphsWithoutReplacingCompanyOrAddressFragments() {
        String text = "联系人：李明。\n随后李明确认收到。李明科技有限公司的地址为李明路12号。";
        SensitiveTextEngine engine = new SensitiveTextEngine(List.of("CHINESE_NAME"), true, List.of(), List.of());
        engine.learn(text);
        String masked = engine.apply(text);
        assertEquals(2, engine.counts().get("CHINESE_NAME"));
        assertEquals(1, engine.recovery().size());
        assertTrue(masked.contains("李明科技有限公司的地址为李明路12号。"));
        assertEquals(text, SensitiveTextEngine.apply(masked, SensitiveTextEngine.restoreEdits(masked, engine.recovery())));
    }

    @Test
    void exclusionsAndDisabledStrategyRemainEffective() {
        String text = "联系人：张三。张三已确认。";
        for (SensitiveTextEngine engine : List.of(
                new SensitiveTextEngine(List.of("CHINESE_NAME"), true, List.of(), List.of("张三")),
                new SensitiveTextEngine(List.of("PHONE"), true, List.of(), List.of()))) {
            engine.learn(text);
            assertEquals(text, engine.apply(text));
        }
    }

    @Test
    void companyAndPersonRulesKeepTheConnectingVerb() {
        String text = "原告张三诉北京星河科技有限公司。";
        SensitiveTextEngine engine = new SensitiveTextEngine(List.of("CHINESE_NAME", "COMPANY"), true, List.of(), List.of());
        engine.learn(text);
        String masked = engine.apply(text);
        assertTrue(masked.startsWith("原告[[中文姓名"), masked);
        assertTrue(masked.contains("]]诉[[公司名称"), masked);
        assertEquals(1, engine.counts().get("CHINESE_NAME"));
        assertEquals(1, engine.counts().get("COMPANY"));
        assertEquals(text, SensitiveTextEngine.apply(masked, SensitiveTextEngine.restoreEdits(masked, engine.recovery())));
    }

    @Test
    void knownNameDoesNotReplaceThePrefixOfAnotherFullName() {
        String text = "联系人：张三。关于张三丰的材料另行提供。";
        SensitiveTextEngine engine = new SensitiveTextEngine(List.of("CHINESE_NAME"), true, List.of(), List.of());
        engine.learn(text);
        assertTrue(engine.apply(text).contains("张三丰的材料另行提供。"));
        assertEquals(1, engine.counts().get("CHINESE_NAME"));
    }

    @Test
    void docxNamesAcrossRunsAndParagraphsPreviewRedactAndRestore() throws Exception {
        Path source = dir.resolve("synthetic.docx");
        try (XWPFDocument doc = new XWPFDocument()) {
            var paragraph = doc.createParagraph();
            paragraph.createRun().setText("联系人：");
            paragraph.createRun().setText("欧阳");
            paragraph.createRun().setText("明。");
            doc.createParagraph().createRun().setText("随后欧阳明确认收到。普通条款保持完整。");
            try (var out = Files.newOutputStream(source)) { doc.write(out); }
        }
        SensitiveService service = new SensitiveService();
        var options = new SensitiveService.Options(List.of("CHINESE_NAME"), "TOKEN", List.of(), List.of(), "synthetic-password");
        var preview = service.previewFile(source.toString(), options);
        var masked = service.processFile(source.toString(), options);
        assertEquals(2, preview.counts().get("CHINESE_NAME"));
        assertEquals(preview.counts(), masked.counts());
        assertFalse(preview.text().contains("欧阳明"));
        var restored = service.restoreFile(masked.path(), masked.recoveryKit(), "synthetic-password");
        try (XWPFDocument doc = new XWPFDocument(Files.newInputStream(Path.of(restored.path())))) {
            assertEquals("联系人：欧阳明。", doc.getParagraphs().get(0).getText());
            assertEquals("随后欧阳明确认收到。普通条款保持完整。", doc.getParagraphs().get(1).getText());
        }
    }
}
