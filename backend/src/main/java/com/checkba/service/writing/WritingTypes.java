// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.checkba.service.writing;

import java.util.List;

/** Shared profile inputs are already project-authorized; source text is untrusted data. */
public final class WritingTypes {
    private WritingTypes() {}
    public record Context(String documentType, String title, String section, String before, String after,
                          String stance, String cutoffDate) {}
    public record Source(String id, Long fileId, String name, String version, String locator, String text) {}
    /** Status describes attribution (e.g. DOCUMENT_STATES/CONTRACT_TERM/CLAIM/MISSING/CONFLICT), never AI certainty. */
    public record Fact(String id, String kind, String label, String value, String role, String status,
                       String sourceId, String quote) {}
    /** text is the insertion suffix, not the whole paragraph or a silent replacement. */
    public record Advice(String text, String kind, List<String> factIds, List<String> sourceIds, String explanation) {}
    public static String clean(String value) { return value == null ? "" : value.strip(); }
}
