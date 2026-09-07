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
        for (String path : new String[]{"prompts/system_prompt.md", "prompts/system_prompt.en.md"}) {
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
