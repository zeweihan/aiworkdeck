package com.checkba.version;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ChangeDescriptionTest {

    @Test
    void singleFileNamesIt() {
        String s = WorkSessionService.describeChanges(List.of(
                new FileChange("重要协议/股权转让协议.docx", FileChange.Type.MODIFY)));
        assertEquals("修改了《股权转让协议》", s);
    }

    @Test
    void multipleFilesNameFirstAndCount() {
        String s = WorkSessionService.describeChanges(List.of(
                new FileChange("重要协议/股权转让协议.docx", FileChange.Type.MODIFY),
                new FileChange("法律意见书.docx", FileChange.Type.MODIFY),
                new FileChange("附件.docx", FileChange.Type.ADD)));
        assertEquals("修改了《股权转让协议》等 3 份文件", s);
    }

    @Test
    void manifestIsNotUserVisible() {
        String s = WorkSessionService.describeChanges(List.of(
                new FileChange(".awd/tree.json", FileChange.Type.MODIFY),
                new FileChange("合同.docx", FileChange.Type.MODIFY)));
        assertEquals("修改了《合同》", s, "清单文件不得出现在律师看到的描述里");
    }

    @Test
    void onlyManifestChangedFallsBackToGenericWording() {
        String s = WorkSessionService.describeChanges(List.of(
                new FileChange(".awd/tree.json", FileChange.Type.MODIFY)));
        assertEquals("整理了文件结构", s);
    }
}
