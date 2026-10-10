// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.checkba.service.mobile;

import com.checkba.model.entity.*;
import com.checkba.repository.*;
import com.checkba.service.LangText;
import com.checkba.storage.StorageServiceFactory;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.*;

/** Account-wide identities with device-local routing aliases. Names never determine identity. */
@Service
public class ProjectCatalogService {
    private final ProjectRepository projects;
    private final ProjectFileRepository files;
    private final MobileProjectDirRepository directories;
    private final AddinProjectLinkRepository links;
    private final MobileProjectManifestRepository manifests;
    private final MobileRelayStoreService relay;
    private final StorageServiceFactory storage;
    private final ObjectMapper mapper = new ObjectMapper();

    public ProjectCatalogService(ProjectRepository projects, ProjectFileRepository files,
            MobileProjectDirRepository directories, AddinProjectLinkRepository links,
            MobileProjectManifestRepository manifests, MobileRelayStoreService relay, StorageServiceFactory storage) {
        this.projects = projects; this.files = files; this.directories = directories;
        this.links = links; this.manifests = manifests; this.relay = relay; this.storage = storage;
    }

    public static String validUid(String value) {
        if (value == null || !value.matches("[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}")) return null;
        return UUID.fromString(value).toString();
    }
    static String legacyUid(Long userId, String deviceId, String key) {
        return UUID.nameUUIDFromBytes(("awd-project:" + userId + ":" + deviceId + ":" + key)
                .getBytes(StandardCharsets.UTF_8)).toString();
    }

    @Transactional
    public List<Map<String, Object>> catalog(Long userId) {
        List<Project> owned = projects.findByUserIdOrderByCreatedAtDesc(userId);
        Map<Long, Project> byId = new HashMap<>();
        for (Project p : owned) {
            ensureProjectUid(p);
            byId.put(p.getId(), p);
        }
        Map<Long, AddinProjectLink> shadows = new HashMap<>();
        for (AddinProjectLink link : links.findByUserId(userId)) shadows.put(link.getCloudProjectId(), link);
        List<MobileProjectDir> dirs = directories.findByUserIdOrderByUpdatedAtDesc(userId);
        Map<String, String> aliases = new HashMap<>();
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        for (MobileProjectDir row : dirs) {
            Project cloud = row.getCloudProjectId() == null ? null : byId.get(row.getCloudProjectId());
            String uid = cloud != null && !shadows.containsKey(cloud.getId()) ? cloud.getUid() : validUid(row.getProjectUid());
            if (uid == null) uid = legacyUid(userId, row.getDeviceId(), row.getProjectKey());
            aliases.put(row.getDeviceId() + ":" + row.getProjectKey(), uid);
            Map<String, Object> location = new LinkedHashMap<>();
            location.put("kind", "desktop"); location.put("deviceId", row.getDeviceId());
            location.put("deviceName", row.getDeviceName() == null ? "" : row.getDeviceName());
            location.put("key", row.getProjectKey()); location.put("online", relay.isDeviceOnline(userId, row.getDeviceId()));
            add(out, uid, cloud == null ? row.getName() : cloud.getName(), location);
        }
        for (Project project : owned) {
            AddinProjectLink shadow = shadows.get(project.getId());
            String uid = shadow == null ? project.getUid() : aliases.getOrDefault(
                    shadow.getDeviceId() + ":" + shadow.getProjectKey(),
                    legacyUid(userId, shadow.getDeviceId(), shadow.getProjectKey()));
            // Shadow is a location of the same project, not a second selectable project.
            Map<String, Object> location = new LinkedHashMap<>();
            location.put("kind", "cloud"); location.put("deviceId", "cloud");
            location.put("key", project.getId().toString()); location.put("cloudProjectId", project.getId());
            location.put("online", true);
            if (shadow != null) location.put("archiveOnly", true);
            add(out, uid, project.getName(), location);
        }
        return new ArrayList<>(out.values());
    }
    @SuppressWarnings("unchecked")
    private static void add(Map<String, Map<String, Object>> out, String uid, String name, Map<String, Object> location) {
        Map<String, Object> item = out.computeIfAbsent(uid, ignored -> {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("projectUid", uid); value.put("name", name); value.put("locations", new ArrayList<>()); return value;
        });
        ((List<Map<String, Object>>) item.get("locations")).add(location);
    }
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> locations(Long userId, String uid) {
        return catalog(userId).stream().filter(p -> p.get("projectUid").equals(uid)).findFirst()
                .map(p -> (List<Map<String, Object>>) p.get("locations"))
                .orElseThrow(() -> new IllegalArgumentException(LangText.of("项目不存在", "Project not found")));
    }

    @Transactional
    public Map<String, Object> fileList(Long userId, String uid) {
        List<Map<String, Object>> locations = locations(userId, uid);
        Map<String, Map<String, Object>> entries = new LinkedHashMap<>();
        boolean cached = false, truncated = false;
        // Cloud copies win if a stable file identity also occurs in a desktop snapshot.
        for (Map<String, Object> location : locations) {
            if (!"cloud".equals(location.get("kind"))) continue;
            Long projectId = ((Number) location.get("cloudProjectId")).longValue();
            List<ProjectFile> tree = files.findByProjectIdAndIsDeletedFalseOrderBySortOrderAsc(projectId);
            Map<Long, ProjectFile> byId = new HashMap<>();
            for (ProjectFile f : tree) byId.put(f.getId(), f);
            for (ProjectFile file : tree) {
                if (Boolean.TRUE.equals(file.getIsFolder())) continue;
                ensureFileUid(file);
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("id", file.getId().toString()); entry.put("uid", file.getUid());
                entry.put("name", file.getName()); entry.put("path", relativePath(file, byId));
                long size = file.getFileSize() == null ? 0 : file.getFileSize();
                var blob = storage.getStorageService();
                if (file.getFilePath() != null && blob.exists(file.getFilePath())) size = blob.getSize(file.getFilePath());
                entry.put("size", size);
                entry.put("source", "cloud"); entry.put("deviceId", "cloud"); entry.put("projectKey", projectId.toString());
                entries.putIfAbsent(file.getUid(), entry);
            }
        }
        for (Map<String, Object> location : locations) {
            if (!"desktop".equals(location.get("kind"))) continue;
            Optional<MobileProjectManifest> snapshot = manifests.findByUserIdAndDeviceIdAndProjectKey(userId,
                    (String) location.get("deviceId"), (String) location.get("key"));
            if (snapshot.isEmpty()) { truncated = true; continue; }
            cached = true;
            try {
                var data = mapper.readTree(snapshot.get().getPayloadJson());
                truncated |= data.path("truncated").asBoolean();
                for (var f : data.path("files")) {
                    Map<String, Object> entry = mapper.convertValue(f, new TypeReference<>() {});
                    entry.put("source", "desktop"); entry.put("deviceId", location.get("deviceId"));
                    entry.put("projectKey", location.get("key")); entry.put("online", location.get("online"));
                    String fileUid = validUid(f.path("uid").asText());
                    String identity = fileUid != null ? fileUid : location.get("deviceId") + ":" + location.get("key") + ":" + f.path("id").asText();
                    entries.putIfAbsent(identity, entry);
                }
            } catch (IOException e) { throw new IllegalStateException("Invalid catalogue snapshot", e); }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("files", new ArrayList<>(entries.values())); result.put("count", entries.size());
        result.put("totalCount", truncated ? null : entries.size()); result.put("truncated", truncated);
        result.put("source", "catalog"); result.put("cached", cached); return result;
    }
    @Transactional
    public void rememberFiles(Long userId, String deviceId, String key, String payload) {
        MobileProjectManifest row = manifests.findByUserIdAndDeviceIdAndProjectKey(userId, deviceId, key)
                .orElseGet(MobileProjectManifest::new);
        row.setUserId(userId); row.setDeviceId(deviceId); row.setProjectKey(key);
        row.setPayloadJson(payload); row.setUpdatedAt(LocalDateTime.now()); manifests.save(row);
    }
    @Transactional
    public String ensureProjectUid(Project project) {
        if (validUid(project.getUid()) == null) {
            projects.assignCatalogUidIfUnchanged(project.getId(), project.getUid(), UUID.randomUUID().toString());
            project.setUid(projects.readCatalogUid(project.getId()));
        }
        return project.getUid();
    }
    @Transactional
    public String ensureFileUid(ProjectFile file) {
        if (validUid(file.getUid()) == null) {
            files.assignCatalogUidIfUnchanged(file.getId(), file.getUid(), UUID.randomUUID().toString());
            file.setUid(files.readCatalogUid(file.getId()));
        }
        return file.getUid();
    }
    @Transactional
    public void preserveImportedFileUid(ProjectFile file, String sourceUid) {
        String uid = validUid(sourceUid);
        if (uid == null || uid.equals(file.getUid())) return;
        files.assignCatalogUidIfUnchanged(file.getId(), file.getUid(), uid);
        file.setUid(files.readCatalogUid(file.getId()));
    }

    private static String relativePath(ProjectFile file, Map<Long, ProjectFile> tree) {
        Deque<String> parts = new ArrayDeque<>(); parts.addFirst(file.getName());
        Long parent = file.getParentId(); Set<Long> seen = new HashSet<>();
        while (parent != null && seen.add(parent)) {
            ProjectFile f = tree.get(parent); if (f == null) break; parts.addFirst(f.getName()); parent = f.getParentId();
        }
        return String.join("/", parts);
    }
    @SuppressWarnings("unchecked")
    public String cloudProjectUid(Long userId, Long projectId) {
        for (Map<String,Object> project : catalog(userId)) {
            for (Map<String,Object> location : (List<Map<String,Object>>)project.get("locations")) {
                if (projectId.equals(location.get("cloudProjectId"))) return (String)project.get("projectUid");
            }
        }
        throw new IllegalArgumentException("Project not found");
    }
    public record Content(InputStream stream, long size) {}
    @Transactional
    public Content content(Long userId, String projectUid, String fileUid) {
        for (Map<String, Object> location : locations(userId, projectUid)) {
            if (!"cloud".equals(location.get("kind"))) continue;
            Long projectId = ((Number) location.get("cloudProjectId")).longValue();
            for (ProjectFile file : files.findByProjectIdAndIsDeletedFalseOrderBySortOrderAsc(projectId)) {
                if (!Boolean.TRUE.equals(file.getIsFolder()) && fileUid.equals(file.getUid())) {
                    try {
                        var resource = storage.getStorageService().load(file.getFilePath());
                        return new Content(resource.getInputStream(), resource.contentLength());
                    } catch (IOException e) { throw new IllegalArgumentException(LangText.of("文件内容不存在", "File content not found"), e); }
                }
            }
        }
        throw new IllegalArgumentException(LangText.of("文件不存在", "File not found"));
    }
}
