// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.service.ai.tools;

import dev.langchain4j.agent.tool.Tool;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

class NumberingRemovalContractTest {
    @Test
    void removalToolExplainsBulletsAndRequiresReadback() throws Exception {
        var method = DocumentEditTools.class.getDeclaredMethod("doc_set_numbering", String.class, Integer.class);
        String description = String.join("", method.getAnnotation(Tool.class).value());
        assertTrue(description.contains("清除自动编号和项目符号"));
        assertTrue(description.contains("headingLevel=0 不会清除列表"));
        assertTrue(description.contains("paragraph.isNumbered=false"));
    }

    @Test
    void bothPromptLanguagesExposeRemovalAndVerification() throws Exception {
        // doc_* 工具表自 dev-board#809（K29）起在按客户端能力拼装的 LOWA 片段里，不在基底 prompt
        for (String path : new String[]{"prompts/tools-lowa.md", "prompts/tools-lowa.en.md"}) {
            try (var in = getClass().getClassLoader().getResourceAsStream(path)) {
                assertNotNull(in);
                String prompt = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                assertTrue(prompt.contains("`doc_set_numbering(preset, level)`"));
                assertTrue(prompt.contains("`doc_get_formatting()`"));
                assertTrue(prompt.contains("paragraph.isNumbered=false"));
            }
        }
    }
}
