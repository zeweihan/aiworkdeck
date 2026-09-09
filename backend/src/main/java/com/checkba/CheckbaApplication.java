// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@org.springframework.scheduling.annotation.EnableAsync
@org.springframework.scheduling.annotation.EnableScheduling
public class CheckbaApplication {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(CheckbaApplication.class);

    public static void main(String[] args) {
        SpringApplication.run(CheckbaApplication.class, args);
        log.info("Checkba AI Agent Backend is running!");
    }

}

