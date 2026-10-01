// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.checkba.service;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import java.util.Map;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static com.checkba.service.DocxComparisonFinalizerTest.*;

/** Exercises the complete finalizer, so a field cache refresh cannot silently move a comment. */
class DocxComparisonCoordinateTest {
    @Test void refreshedCacheControlsDoNotShiftFollowingComment() throws Exception {
        for (String control : List.of("tab", "br", "cr")) {
            String field = "<w:fldSimple w:instr=\"REF TargetA\">%s</w:fldSimple>";
            byte[] base = doc(p(field.formatted(run("A") + "<w:r><w:" + control + "/></w:r>" + run("B"))
                    + marked("1", "X") + run("YZ")), comment("1", "法务", "X上的批注"));
            byte[] revised = doc(p(field.formatted(run("AB")) + run("XYZ")), null);
            Document result = xml(DocxComparisonFinalizer.finalizeComparison(base, revised, revised), "word/document.xml");
            assertEquals(Map.of("0", "X"), anchored(result), "Cache " + control + " must not move X's comment to Y");
            assertEquals("ABXYZ", text(result));
        }
    }
}
