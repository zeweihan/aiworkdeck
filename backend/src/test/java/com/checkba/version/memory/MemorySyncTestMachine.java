package com.checkba.version.memory;

import com.checkba.model.entity.MemoryEntry;
import com.checkba.model.entity.MemoryRemote;
import com.checkba.repository.MemoryEntryRepository;
import com.checkba.repository.MemoryRemoteRepository;
import com.checkba.repository.ProjectFileRepository;
import com.checkba.repository.UserRepository;
import com.checkba.service.ai.memory.MemoryManager;
import com.checkba.storage.ProjectStorageResolver;
import com.checkba.storage.StorageProperties;
import org.springframework.scheduling.TaskScheduler;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 「一台机器」的测试替身：独立存储根（独立记忆仓库）+ 独立的 map 后备 DB，
 * 两台指向同一个 file:// 裸仓库即可模拟跨机器同步。save/delete 桩模拟 JPA 的
 * PrePersist/PreUpdate 时间戳语义（回灌预置的时间保留、更新时 updatedAt 前推），
 * 与真实实体行为一致——防乒乓不变式正是要在这种语义下成立。
 */
class MemorySyncTestMachine {

    final Map<Long, MemoryEntry> db = new LinkedHashMap<>();
    final MemoryRealm realm;
    final MemoryRepoService repo;
    final MemorySyncService sync;
    final MemoryEntryRepository entries;
    final MemoryRemoteRepository remotes;
    MemoryRemote cfg;
    private long nextId = 1;

    MemorySyncTestMachine(Path root, String remoteUrl, MemoryRealm realm) {
        this.realm = realm;
        StorageProperties props = new StorageProperties();
        props.getLocal().setRootPath(root.toAbsolutePath().toString());
        ProjectStorageResolver resolver = new ProjectStorageResolver(props, null);
        this.repo = new MemoryRepoService(resolver);

        entries = mock(MemoryEntryRepository.class);
        when(entries.findByProjectIdAndScopeIn(any(), any())).thenAnswer(inv -> {
            Long pid = inv.getArgument(0);
            java.util.Collection<String> scopes = inv.getArgument(1);
            List<MemoryEntry> out = new ArrayList<>();
            for (MemoryEntry e : db.values()) {
                if (pid.equals(e.getProjectId()) && e.getScope() != null
                        && scopes.contains(e.getScope())) {
                    out.add(e);
                }
            }
            return out;
        });
        when(entries.findByUserIdAndScopeIn(any(), any())).thenAnswer(inv -> {
            Long uid = inv.getArgument(0);
            java.util.Collection<String> scopes = inv.getArgument(1);
            List<MemoryEntry> out = new ArrayList<>();
            for (MemoryEntry e : db.values()) {
                if (uid.equals(e.getUserId()) && e.getScope() != null
                        && scopes.contains(e.getScope())) {
                    out.add(e);
                }
            }
            return out;
        });
        when(entries.save(any(MemoryEntry.class))).thenAnswer(inv -> {
            MemoryEntry e = inv.getArgument(0);
            if (e.getId() == null) {
                e.setId(nextId++);
                if (e.getCreatedAt() == null) e.setCreatedAt(LocalDateTime.now());
                if (e.getUpdatedAt() == null) e.setUpdatedAt(LocalDateTime.now());
            } else if (db.containsKey(e.getId())) {
                e.setUpdatedAt(LocalDateTime.now());
            }
            db.put(e.getId(), e);
            return e;
        });
        doAnswer(inv -> {
            MemoryEntry e = inv.getArgument(0);
            db.remove(e.getId());
            return null;
        }).when(entries).delete(any(MemoryEntry.class));

        remotes = mock(MemoryRemoteRepository.class);
        when(remotes.findByRepoKey(any())).thenAnswer(inv ->
                cfg != null && cfg.getRepoKey().equals(inv.getArgument(0))
                        ? Optional.of(cfg) : Optional.empty());
        when(remotes.save(any(MemoryRemote.class))).thenAnswer(inv -> inv.getArgument(0));
        when(remotes.findAll()).thenAnswer(inv ->
                cfg == null ? List.of() : List.of(cfg));

        MemoryManager manager = mock(MemoryManager.class);
        when(manager.saveMemory(any(MemoryEntry.class)))
                .thenAnswer(inv -> entries.save(inv.getArgument(0)));

        ProjectFileRepository files = mock(ProjectFileRepository.class);
        when(files.findByProjectId(any())).thenReturn(List.of());
        UserRepository users = mock(UserRepository.class);
        when(users.findByUsername(any())).thenReturn(Optional.empty());
        when(users.findById(any())).thenReturn(Optional.empty());

        sync = new MemorySyncService(repo, entries, remotes, manager, files, users,
                mock(TaskScheduler.class));

        if (remoteUrl != null) {
            cfg = new MemoryRemote();
            cfg.setRepoKey(realm.repoKey());
            cfg.setUrl(remoteUrl);
            cfg.setPendingUpload(false);
            cfg.setCreatedAt(LocalDateTime.now());
        }
    }

    Map<String, Object> syncNow() {
        return sync.syncNow(realm);
    }

    MemoryEntry addEntry(String scope, String key, String value) {
        MemoryEntry e = new MemoryEntry();
        e.setScope(scope);
        e.setMemoryType("fact");
        e.setMemoryKey(key);
        e.setMemoryValue(value);
        e.setImportanceScore(0.5);
        e.setIsProtected(false);
        if (realm.kind() == MemoryRealm.Kind.PROJECT) {
            e.setProjectId(realm.ownerId());
        } else {
            e.setUserId(realm.ownerId());
        }
        e.setCreatedAt(LocalDateTime.now());
        e.setUpdatedAt(LocalDateTime.now());
        return entries.save(e);
    }

    MemoryEntry byUid(String uid) {
        for (MemoryEntry e : db.values()) {
            if (uid.equals(e.getUid())) return e;
        }
        return null;
    }

    MemoryEntry byKey(String key) {
        for (MemoryEntry e : db.values()) {
            if (key.equals(e.getMemoryKey())) return e;
        }
        return null;
    }
}
