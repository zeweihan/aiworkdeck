// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.service.ai.ref;

import com.checkba.model.entity.ProjectFile;
import com.checkba.service.ProjectFileService;
import com.checkba.service.ProjectMemberService;
import com.checkba.service.file.ProjectFileTextExtractor;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 云端项目文件（dev-board#718），ref 形如 {@code cloud:<fileId>}。只读（D 决策）。
 *
 * <p>list 只列当前会话项目里的文件；read 接受用户能读的任何项目里的文件——fileId 是模型抄来的参数，
 * 读之前一律按 {@link ProjectMemberService#hasReadPermission} 判权，判不过与「不存在」同一句话，
 * 不回显别人项目的文件名。正文走与 extract_file_text 同一个 {@link ProjectFileTextExtractor}。
 */
@Component
public class CloudProjectSource implements RefSource {

    static final String NOT_FOUND = "找不到这个文件，或你没有这个项目的读取权限。请用 ref_list 重新查找。";

    private final ProjectFileService fileService;
    private final ProjectMemberService members;
    private final ProjectFileTextExtractor extractor;

    public CloudProjectSource(ProjectFileService fileService, ProjectMemberService members,
                              ProjectFileTextExtractor extractor) {
        this.fileService = fileService;
        this.members = members;
        this.extractor = extractor;
    }

    @Override
    public String scheme() {
        return "cloud";
    }

    @Override
    public List<RefEntry> list(RefQuery q) {
        if (q.projectId() == null || q.userId() == null || !members.hasReadPermission(q.projectId(), q.userId())) {
            return List.of();
        }
        String keyword = q.query() == null || q.query().isBlank() ? null : q.query().trim();
        List<RefEntry> out = new ArrayList<>();
        for (Map.Entry<String, ProjectFile> e :
                fileService.listRelativePaths(q.projectId(), keyword, ReferenceSourceService.MAX_ENTRIES)) {
            ProjectFile pf = e.getValue();
            out.add(new RefEntry("cloud:" + pf.getId(), "cloud", pf.getName(), e.getKey(), null,
                    pf.getUpdatedAt() == null ? null : pf.getUpdatedAt().toString(), null));
        }
        return out;
    }

    @Override
    public String read(RefQuery q, String body, String locator) {
        ProjectFile pf = readableFile(q, body);
        String text;
        try {
            text = extractor.extract(pf);
        } catch (IOException e) {
            throw new RefSourceException(e.getMessage());
        }
        return RefSource.withLocatorNote(locator, text);
    }

    private ProjectFile readableFile(RefQuery q, String body) {
        long fileId;
        try {
            fileId = Long.parseLong(body == null ? "" : body.trim());
        } catch (NumberFormatException e) {
            throw new RefSourceException(NOT_FOUND);
        }
        ProjectFile pf;
        try {
            pf = fileService.getFile(fileId);
        } catch (IllegalArgumentException e) {
            throw new RefSourceException(NOT_FOUND);
        }
        if (pf == null || Boolean.TRUE.equals(pf.getIsDeleted()) || pf.getProjectId() == null
                || q.userId() == null || !members.hasReadPermission(pf.getProjectId(), q.userId())) {
            throw new RefSourceException(NOT_FOUND);
        }
        return pf;
    }
}
