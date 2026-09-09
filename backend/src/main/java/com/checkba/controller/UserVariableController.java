// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.controller;

import com.checkba.model.entity.UserVariable;
import com.checkba.service.UserVariableService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/variables/user")
public class UserVariableController {

    @Autowired
    private UserVariableService service;

    @GetMapping
    public ResponseEntity<List<UserVariable>> getUserVariables(
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId
    ) {
        Long userId = AuthController.getUserIdFromSession(sessionId);
        if (userId == null) {
            return ResponseEntity.status(401).build();
        }
        return ResponseEntity.ok(service.getVariablesByUser(userId));
    }

    @PostMapping
    public ResponseEntity<UserVariable> saveUserVariable(
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId,
            @RequestBody UserVariable variable
    ) {
        Long userId = AuthController.getUserIdFromSession(sessionId);
        if (userId == null) {
            return ResponseEntity.status(401).build();
        }
        return ResponseEntity.ok(service.createOrUpdateVariable(userId, variable));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteUserVariable(
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId,
            @PathVariable Long id
    ) {
        Long userId = AuthController.getUserIdFromSession(sessionId);
        if (userId == null) {
            return ResponseEntity.status(401).build();
        }
        service.deleteVariable(userId, id);
        return ResponseEntity.ok().build();
    }
}

