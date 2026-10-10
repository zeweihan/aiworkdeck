// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.checkba.controller;
import com.checkba.service.mobile.ProjectCatalogService;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.*;
import org.springframework.core.io.InputStreamResource;
import java.util.*;

@RestController
@RequestMapping("/api/mobile/catalog")
public class ProjectCatalogController {
    private final ProjectCatalogService catalog;
    public ProjectCatalogController(ProjectCatalogService catalog) { this.catalog = catalog; }
    private Long user(String session) {
        Long id = AuthController.getUserIdFromSession(session);
        if (id == null) throw new IllegalArgumentException("请先登录"); return id;
    }
    @GetMapping public List<Map<String, Object>> list(@RequestHeader(value="X-Session-Id", required=false) String session) {
        return catalog.catalog(user(session));
    }
    @GetMapping("/{uid}/files") public Map<String, Object> files(@PathVariable String uid,
            @RequestHeader(value="X-Session-Id", required=false) String session) {
        return catalog.fileList(user(session), uid);
    }
    @GetMapping("/{uid}/files/{fileUid}/content") public ResponseEntity<InputStreamResource> content(
            @PathVariable String uid, @PathVariable String fileUid,
            @RequestHeader(value="X-Session-Id", required=false) String session) {
        var content = catalog.content(user(session), uid, fileUid);
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_OCTET_STREAM).contentLength(content.size())
                .body(new InputStreamResource(content.stream()));
    }
}
