// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.aiworkdeck.plugins.tmeet;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.Duration;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class TmeetCliTest {
    @TempDir Path dir;
    @Test void unavailableExecutableHasNoRawPathInError() {
        var runner = new TmeetCli.NativeRunner(dir.resolve("private-unavailable").toString());
        var error = assertThrows(TmeetCli.Failure.class, () -> runner.run(List.of("--version"), Duration.ofSeconds(1)));
        assertEquals(TmeetCli.MISSING, error.getMessage()); assertFalse(error.getMessage().contains("private-unavailable"));
    }
    @org.junit.jupiter.api.condition.DisabledOnOs(org.junit.jupiter.api.condition.OS.WINDOWS)
    @Test void nonzeroExitAndTimeoutAreHandledWithoutRealCli() throws Exception {
        Path script = dir.resolve("fake-cli");
        Files.writeString(script, "#!/bin/sh\nif [ \"$1\" = fail ]; then echo sensitive >&2; exit 8; fi\nsleep 10\n");
        assertTrue(script.toFile().setExecutable(true));
        var runner = new TmeetCli.NativeRunner(script.toString());
        assertEquals(8, runner.run(List.of("fail"), Duration.ofSeconds(1)).exitCode());
        long start = System.nanoTime();
        assertThrows(TmeetCli.Failure.class, () -> runner.run(List.of("slow"), Duration.ofMillis(80)));
        assertTrue(Duration.ofNanos(System.nanoTime() - start).toSeconds() < 3);
    }
}
