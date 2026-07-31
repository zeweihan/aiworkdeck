package com.checkba.service;

import com.checkba.model.entity.ProjectFile;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * kick-off prompt 组装：触发词在首位、材料 fileId 列全、缺失材料显式声明、产出目录正确。
 */
class ShareholderMeetingPromptTest {

    private static ProjectFile file(long id, String name) {
        ProjectFile f = new ProjectFile();
        f.setId(id);
        f.setName(name);
        return f;
    }

    @Test
    void promptStartsWithTriggerAndListsMaterials() {
        Map<String, List<ProjectFile>> materials = new LinkedHashMap<>();
        materials.put("股东大会通知", List.of(file(11, "关于召开2026年第一次临时股东会的通知.pdf")));
        materials.put("董事会决议公告", List.of(file(12, "第三届董事会第七次会议决议公告.pdf")));
        materials.put("投票结果", List.of(file(13, "表决结果.xlsx"), file(14, "现场投票统计.docx")));
        materials.put("意见书模板或会前初稿", List.of(file(15, "法律意见书初稿.docx")));
        materials.put("其他材料", List.of());

        String prompt = ShareholderMeetingService.buildKickoffPrompt(
                "强瑞技术", "301128", "2026年第一次临时股东会",
                LocalDate.parse("2026-01-15"), materials, 40L, 50L);

        assertTrue(prompt.startsWith("股东大会核查"), "触发词必须在开头以命中 skill");
        assertTrue(prompt.contains("fileId=11"));
        assertTrue(prompt.contains("fileId=13"));
        assertTrue(prompt.contains("fileId=14"));
        assertTrue(prompt.contains("fileId=15"));
        assertTrue(prompt.contains("parentFolderId=40"));
        assertTrue(prompt.contains("parentFolderId=50"));
        assertTrue(prompt.contains("301128"));
        assertFalse(prompt.contains("【缺失材料】"), "材料齐全时不应有缺失声明");
    }

    @Test
    void promptDeclaresMissingCoreMaterials() {
        Map<String, List<ProjectFile>> materials = new LinkedHashMap<>();
        materials.put("股东大会通知", List.of());
        materials.put("董事会决议公告", List.of());
        materials.put("投票结果", List.of(file(13, "表决结果.xlsx")));
        materials.put("意见书模板或会前初稿", List.of());
        materials.put("其他材料", List.of());

        String prompt = ShareholderMeetingService.buildKickoffPrompt(
                "某公司", null, "2026年年度股东会", null, materials, 40L, 50L);

        assertTrue(prompt.contains("【缺失材料】"));
        assertTrue(prompt.contains("股东大会通知"));
        assertTrue(prompt.contains("董事会决议公告"));
        assertTrue(prompt.contains("未经交叉核对"));
        // 模板与其他材料是可选项，不列入缺失
        assertFalse(prompt.contains("【缺失材料】意见书模板"), "可选材料不应进缺失声明");
    }
}
