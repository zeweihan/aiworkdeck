// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.checkba.service.mobile;

import com.checkba.repository.ProjectFileRepository;
import com.checkba.model.entity.ProjectFile;
import com.checkba.storage.StorageServiceFactory;
import org.springframework.util.StringUtils;
import com.checkba.repository.ProjectRepository;
import com.checkba.repository.UserRepository;
import com.checkba.service.LangText;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.Map;

/** Persistent cloud storage is distinct from disposable relay storage. */
@Service
public class CloudStorageQuotaService {
    public static final long RELAY_BYTES = 200L * 1024 * 1024;
    public static final long PRO_BYTES = 3L * 1024 * 1024 * 1024;
    private final MobileBillingService billing;
    private final ProjectFileRepository files;
    private final ProjectRepository projects;
    private final UserRepository users;
    private final boolean enabled;
    private final StorageServiceFactory storage;
    @jakarta.persistence.PersistenceContext
    private jakarta.persistence.EntityManager entityManager;

    public CloudStorageQuotaService(MobileBillingService billing, ProjectFileRepository files,
            ProjectRepository projects, UserRepository users, StorageServiceFactory storage,
            @Value("${storage.subscription-quota.enabled:false}") boolean enabled) {
        this.billing = billing;
        this.files = files;
        this.projects = projects;
        this.users = users;
        this.enabled = enabled;
        this.storage = storage;
    }

    public Map<String, Object> usage(Long userId) {
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("persistentUsedBytes", usedBytes(userId));
        try { result.put("persistentQuotaBytes", persistentQuota(userId)); }
        catch (MobileBillingFailureException e) {
            result.put("persistentQuotaBytes", null);
            result.put("persistentQuotaAvailable", false);
        }
        return result;
    }

    public long persistentQuota(Long userId) {
        Map<String, Object> subscription;
        try {
            subscription = billing.balance(userId).subscription();
        } catch (MobileBillingFailureException e) {
            if (e.getKind() == MobileBillingKind.NOT_CONNECTED || e.getKind() == MobileBillingKind.NOT_FOUND
                    || e.getKind() == MobileBillingKind.DISABLED) return 0;
            throw e; // An unavailable account service is not evidence of a free account.
        }
        if (subscription == null || !Boolean.TRUE.equals(subscription.get("active"))) return 0;
        try {
            if (!Instant.parse(String.valueOf(subscription.get("expiresAt"))).isAfter(Instant.now())) return 0;
        } catch (RuntimeException e) { return 0; }
        return subscription.get("cloudStorageBytes") instanceof Number n
                ? Math.max(0, Math.min(PRO_BYTES, n.longValue())) : 0;
    }

    public boolean isEnabled() { return enabled; }

    /** Rollback callbacks run after the old lock is released; never delete a replacement's bytes. */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public void removeRolledBackFile(Long projectId, String path) {
        var project = projects.findById(projectId);
        if (project.isPresent()) {
            users.lockStorageOwner(project.get().getUserId()).orElseThrow(() -> new IllegalArgumentException("User not found"));
            if (files.findByProjectId(projectId).stream().anyMatch(file ->
                    path.equals(file.getFilePath()) || path.equals(file.getWpsFileId()))) return;
        }
        storage.getStorageService().delete(path);
    }

    /** Physical bytes outrank editable metadata. Missing live uploads retain their reservation. */
    public long measuredBytes(ProjectFile file) {
        String key = StringUtils.hasText(file.getFilePath()) ? file.getFilePath() : file.getWpsFileId();
        if (StringUtils.hasText(key) && storage.getStorageService().exists(key)) {
            return Math.max(0, storage.getStorageService().getSize(key));
        }
        return Boolean.TRUE.equals(file.getIsDeleted()) ? 0 : Math.max(0, file.getFileSize() == null ? 0 : file.getFileSize());
    }

    private long usedBytes(Long userId) {
        long total = 0;
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (ProjectFile file : files.findCloudFilesByOwner(userId)) {
            // A row loaded for authorization before acquiring the lock may still have its old path.
            if (entityManager != null && org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()
                    && entityManager.contains(file)) entityManager.refresh(file);
            String key = StringUtils.hasText(file.getFilePath()) ? file.getFilePath() : file.getWpsFileId();
            if (StringUtils.hasText(key) && !seen.add(key)) continue;
            total = Math.addExact(total, measuredBytes(file));
        }
        return total;
    }

    /** Must share the caller's write transaction; refresh occurs after the owner lock, not before it. */
    @Transactional
    public void lockProjectForWrite(Long projectId) {
        if (!enabled) return;
        var project = projects.findById(projectId).orElseThrow(() -> new IllegalArgumentException("Project not found"));
        users.lockStorageOwner(project.getUserId()).orElseThrow(() -> new IllegalArgumentException("User not found"));
    }

    @Transactional
    public void lockFileForWrite(ProjectFile file) {
        if (!enabled) return;
        lockProjectForWrite(file.getProjectId());
        if (entityManager != null && entityManager.contains(file)) entityManager.refresh(file);
    }

    /** Replacements only require their actual growth; append always requires every incoming byte. */
    @Transactional
    public void requireFileCapacity(ProjectFile file, long incomingBytes, boolean append) {
        if (!enabled) return;
        if (incomingBytes < 0) throw new IllegalArgumentException("Invalid upload size");
        lockFileForWrite(file);
        requireProjectCapacity(file.getProjectId(), append ? incomingBytes : Math.max(0, incomingBytes - measuredBytes(file)));
    }

    /** Explicit persistent publication, also used by the cloud catalogue. */
    @Transactional
    public void requirePersistentCapacity(Long userId, long additionalBytes) {
        if (additionalBytes <= 0) return;
        users.lockStorageOwner(userId).orElseThrow(() -> new IllegalArgumentException("User not found"));
        checkCapacity(userId, additionalBytes);
    }

    private void checkCapacity(Long userId, long additionalBytes) {
        long quota = persistentQuota(userId);
        long used = usedBytes(userId);
        if (used > quota || additionalBytes > quota - used) {
            throw new IllegalArgumentException(LangText.of(
                    "持久云空间不足：Pro 订阅包含 3GB，临时中转空间单独计算；现有文件仍可下载或删除",
                    "Persistent cloud storage is full. Pro includes 3GB; relay storage is separate. Existing files can still be downloaded or deleted."));
        }
    }

    /** Only the official cloud profile applies subscription quotas to ordinary project writes. */
    @Transactional
    public void requireProjectCapacity(Long projectId, long additionalBytes) {
        if (!enabled) return;
        lockProjectForWrite(projectId);
        if (additionalBytes <= 0) return;
        var project = projects.findById(projectId).orElseThrow(() -> new IllegalArgumentException("Project not found"));
        checkCapacity(project.getUserId(), additionalBytes);
    }

    /** Template materialization has no client-declared size: verify actual bytes before commit. */
    @Transactional
    public void requireCurrentProjectUsage(Long projectId) {
        if (!enabled) return;
        lockProjectForWrite(projectId);
        var project = projects.findById(projectId).orElseThrow(() -> new IllegalArgumentException("Project not found"));
        checkCapacity(project.getUserId(), 0);
    }
}
