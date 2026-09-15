// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.service.evidence;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UlidTest {

    @Test
    void shape() {
        String u = Ulid.next();
        assertEquals(26, u.length());
        assertTrue(u.matches("[0-9A-HJKMNP-TV-Z]{26}"), u);
    }

    @Test
    void monotonicWithinMillisIsNotRequiredButUnique() {
        Set<String> s = new HashSet<>();
        for (int i = 0; i < 10000; i++) assertTrue(s.add(Ulid.next()));
    }

    @Test
    void linkKeyShapeFitsWordBookmarkLimit() {
        String key = "EVID_" + Ulid.next();
        assertEquals(31, key.length());
        assertTrue(key.matches("[A-Za-z0-9_]+"));
    }
}
