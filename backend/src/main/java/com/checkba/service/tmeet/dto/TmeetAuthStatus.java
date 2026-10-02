// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.service.tmeet.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TmeetAuthStatus {
    private boolean loggedIn;
    private String openId;
    private String userName;
    private String accessTokenExpiry;
    private String refreshTokenExpiry;
    private String message;
    private boolean cliAvailable;
    private String cliPath;
}
