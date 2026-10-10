// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.model.entity;

import jakarta.persistence.Column;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/** PG/MySQL 参考 DDL 与实体列名对拍：实体加列忘了改 DDL 就红。 */
class TrialBalanceDdlTest {

    private static String read(String name) throws Exception {
        try (InputStream in = TrialBalanceDdlTest.class.getResourceAsStream("/db/ddl/" + name)) {
            assertNotNull(in, name + " 不在 classpath");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String snake(String camel) {
        return camel.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase();
    }

    @Test
    void bothDialectsDeclareEveryEntityColumnAndUniqueUser() throws Exception {
        for (String file : new String[]{"trial_balance.postgresql.sql", "trial_balance.mysql.sql"}) {
            String ddl = read(file).replace("`", "");
            assertTrue(ddl.contains("CREATE TABLE IF NOT EXISTS trial_balance"), file);
            assertTrue(ddl.contains("uk_trial_balance_user"), file);
            for (Field f : TrialBalance.class.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                String col = snake(f.getName());
                assertTrue(ddl.matches("(?s).*\\n\\s+" + col + "\\s.*"), file + " 缺列 " + col);
                Column c = f.getAnnotation(Column.class);
                if (c != null && f.getType() == String.class && c.length() != 255) {
                    assertTrue(ddl.matches("(?s).*\\n\\s+" + col + "\\s+VARCHAR\\(" + c.length() + "\\).*"),
                            file + " 列 " + col + " 长度应为 " + c.length());
                }
            }
        }
    }
}
