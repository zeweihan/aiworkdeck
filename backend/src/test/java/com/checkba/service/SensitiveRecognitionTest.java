// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.service;

import com.checkba.model.SensitiveType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SensitiveRecognitionTest {
    @TempDir Path dir;
    private final SensitiveService service = new SensitiveService();

    @Test
    void allStrategiesPreserveContractProse() throws Exception {
        String text = "本合同由甲乙双方协商订立。双方应当按照约定履行义务，合同金额为100万元。\n"
                + "依照公司法，公司应当依法履行信息披露义务。订单编号 OrderAbc12345。";
        Path source = dir.resolve("contract.txt");
        Files.writeString(source, text);
        String result = service.processFile(source.toString(),
                Arrays.stream(SensitiveType.values()).map(SensitiveType::getCode).toList());
        assertEquals(text, Files.readString(Path.of(result)));
    }

    @Test
    void companyNameIsMaskedWithoutSwallowingTheSentence() {
        String result = service.replaceSensitiveData("甲方：北京星河科技有限公司与上海远航实业有限公司签订合同。", "COMPANY");
        assertFalse(result.contains("星河"));
        assertFalse(result.contains("远航"));
        assertTrue(result.startsWith("甲方："));
        assertTrue(result.endsWith("签订合同。"));
        assertTrue(result.contains("与"));
    }

    @Test
    void namesAreRedactedOnlyWhenExplicitlyProvided() {
        String result = service.maskCustomWords("联系人：张三。法定代表人：欧阳明。双方应当履行合同。", List.of("张三", "欧阳明"));
        assertFalse(result.contains("张三"));
        assertFalse(result.contains("欧阳明"));
        assertTrue(result.contains("双方应当履行合同。"));
    }

    @Test
    void passwordRequiresFieldLabel() {
        assertEquals("订单编号 OrderAbc12345", service.replaceSensitiveData("订单编号 OrderAbc12345", "PASSWORD"));
        assertEquals("密码：******", service.replaceSensitiveData("密码：Abc12345!", "PASSWORD"));
    }

    @Test
    void unknownBinaryFormatIsRejected() throws Exception {
        Path file = dir.resolve("legacy.doc");
        Files.write(file, new byte[]{0, 1, 2, 3});
        assertThrows(IllegalArgumentException.class, () -> service.processFile(file.toString(), List.of("PHONE")));
    }

    @Test
    void traditionalAndEnglishCompanyNamesAreRecognized() {
        String result = service.replaceSensitiveData("甲方：星河有限責任公司。Supplier: Acme Technology Co., Ltd. agreed.", "COMPANY");
        assertFalse(result.contains("星河"));
        assertFalse(result.contains("Acme"));
        assertTrue(result.startsWith("甲方："));
        assertTrue(result.endsWith(" agreed."));
    }

    @Test
    void roleWordsWithoutAFieldSeparatorAreNotPersonalNames() {
        String text = "乙方应当。被告申请。原告请求。";
        assertEquals(text, service.replaceSensitiveData(text, "CHINESE_NAME"));
    }

    @Test
    void shortAddressesAndSingleCharacterEmailsCannotFallBackToOriginal() {
        assertFalse(service.replaceSensitiveData("地址：北京市海淀区1号", "ADDRESS").contains("海淀区1号"));
        assertFalse(service.replaceSensitiveData("邮箱a@example.com", "EMAIL").contains("a@example.com"));
    }
}
