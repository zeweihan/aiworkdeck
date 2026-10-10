// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.checkba.service.ai.tools;

import com.checkba.model.entity.ProjectFile;
import com.checkba.service.ProjectFileService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Destination policy for new AI files only. Never applies to edits, moves or PDF derivatives. */
public final class GeneratedFileLocation {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Map<String, String> PARENTS = Map.of(
            "write_file", "parentFolderId", "write_docx", "parentFolderId",
            "doc_start_stream", "parentFolderId", "sheet_create_file", "parentFolderId",
            "pptx_generate", "parentId");
    private GeneratedFileLocation() {}

    public static String parentParameter(String toolName, Object existingFileId) {
        if ("doc_start_stream".equals(toolName) && existingFileId != null) return null;
        return PARENTS.get(toolName);
    }

    /** Capture once at run start. A stale/foreign/temporary active tab supplies no default. */
    public static Long initialParent(ProjectFileService files, Long projectId, Long activeFileId) {
        if (projectId == null || activeFileId == null) return null;
        ProjectFile file = files.findFile(activeFileId).orElse(null);
        if (!belongsTo(file, projectId) || Boolean.TRUE.equals(file.getIsFolder())) return null;
        Long parent = file.getParentId();
        if (parent == null) return null;
        return visibleFolderTree(files, projectId, parent) ? parent : null;
    }

    // Same internal names as frontend fileTreeBuild.HIDDEN_SYSTEM_FOLDER_NAMES.
    private static boolean visibleFolderTree(ProjectFileService files, Long projectId, Long parent) {
        var seen = new java.util.HashSet<Long>();
        while (parent != null) {
            if (!seen.add(parent)) return false;
            ProjectFile folder = files.findFile(parent).orElse(null);
            if (!validFolder(folder, projectId) || ".stagezone".equals(folder.getName())
                    || "__staging_area__".equals(folder.getName())) return false;
            parent = folder.getParentId();
        }
        return true;
    }

    /** 0 explicitly means project root; null inherits the frozen run default. */
    public static Long resolve(ProjectFileService files, Long projectId, Long requested, Long defaultParent) {
        Long target = requested == null ? defaultParent : requested;
        if (target == null || target == 0) return null;
        if (target < 0 || !validFolder(files.findFile(target).orElse(null), projectId)
                || requested == null && !visibleFolderTree(files, projectId, target)) {
            throw new IllegalArgumentException("目标文件夹不存在、已删除或不属于本项目：" + target
                    + "。请重新选择文件夹；传 0 可明确保存到项目根目录。");
        }
        return target;
    }

    private static boolean belongsTo(ProjectFile file, Long projectId) {
        return projectId != null && file != null && file.getId() != null
                && Objects.equals(projectId, file.getProjectId()) && !Boolean.TRUE.equals(file.getIsDeleted());
    }

    private static boolean validFolder(ProjectFile file, Long projectId) {
        return belongsTo(file, projectId) && Boolean.TRUE.equals(file.getIsFolder());
    }

    /** Structured result carries the database identity and actual path, including conflict renames. */
    public static String createdResult(ProjectFile file, String message) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "success"); result.put("db_id", file.getId());
        result.put("file_name", file.getName()); result.put("parent_id", file.getParentId());
        String prefix = "projects/" + file.getProjectId() + "/";
        String key = file.getFilePath();
        result.put("file_path", key != null && key.startsWith(prefix) && !("/" + key + "/").contains("/../")
                ? key.substring(prefix.length()) : null);
        result.put("wps_file_id", file.getWpsFileId());
        if (message != null) result.put("message", message);
        try { return JSON.writeValueAsString(result); }
        catch (Exception impossible) { throw new IllegalStateException(impossible); }
    }

    public static Long createdId(String output) {
        if (output == null) return null;
        try {
            var result = JSON.readTree(output);
            var id = result.path("db_id");
            return "success".equals(result.path("status").asText()) && id.isIntegralNumber()
                    && id.canConvertToLong() && id.asLong() > 0 ? id.asLong() : null;
        } catch (Exception malformed) { return null; }
    }

    /** Never resolve a result by filename or accept a generated id from another project. */
    public static ProjectFile createdFile(ProjectFileService files, Long projectId, String toolName, String output) {
        if (!PARENTS.containsKey(toolName)) return null;
        Long id = createdId(output);
        if (id == null) return null;
        ProjectFile file = files.findFile(id).orElse(null);
        return belongsTo(file, projectId) && !Boolean.TRUE.equals(file.getIsFolder()) ? file : null;
    }
}
