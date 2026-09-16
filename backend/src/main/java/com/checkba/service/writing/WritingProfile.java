// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.checkba.service.writing;

import java.util.List;
import com.checkba.service.writing.WritingTypes.*;

/** Domain rules never fetch files, contact providers or write to the editor. */
public interface WritingProfile {
    String id();
    String label();
    int score(Context context, List<Source> sources);
    List<Fact> facts(Context context, List<Source> sources);
    List<Advice> deterministic(Context context, List<Fact> facts);
    String instructions(Context context, List<Fact> facts);
    List<String> validate(Context context, List<Fact> facts, Advice advice);
}
