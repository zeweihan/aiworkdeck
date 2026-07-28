package com.checkba.version;

import com.checkba.storage.StorageProperties;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.Status;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 每项目一个 Git 仓库的薄封装。只认识 Git 概念，不认识「工作段」——
 * 业务语义在 WorkSessionService。
 *
 * 仓库目录与工作区分离：
 *   gitDir   = {root}/repos/project-{id}.git
 *   workTree = {root}/projects/{id}
 * 这样 .git 不会出现在 data/projects/ 下被 RAG 扫描、压缩包导出、搜索误伤。
 */
@Service
public class ProjectRepoService {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(ProjectRepoService.class);

    private final Path storageRoot;

    public ProjectRepoService(StorageProperties storageProperties) {
        String rootPath = storageProperties.getLocal().getRootPath();
        Path root = Paths.get(rootPath);
        if (!root.isAbsolute()) {
            String userDir = System.getProperty("user.dir");
            Path base = Paths.get(userDir);
            if (userDir.endsWith("backend")) base = base.getParent();
            root = base.resolve(rootPath);
        }
        this.storageRoot = root;
    }

    public Path gitDir(long projectId) {
        return storageRoot.resolve("repos").resolve("project-" + projectId + ".git");
    }

    public Path workTree(long projectId) {
        return storageRoot.resolve("projects").resolve(String.valueOf(projectId));
    }

    public boolean isInitialized(long projectId) {
        return Files.isDirectory(gitDir(projectId).resolve("objects"));
    }

    public Repository open(long projectId) {
        try {
            return new FileRepositoryBuilder()
                    .setGitDir(gitDir(projectId).toFile())
                    .setWorkTree(workTree(projectId).toFile())
                    .setMustExist(true)
                    .build();
        } catch (IOException e) {
            throw new VersionException("打开版本记录失败: project=" + projectId, e);
        }
    }

    /** 建仓库并落一笔「初始版本」。已存在则直接返回。 */
    public void init(long projectId, String authorName, String authorEmail) {
        if (isInitialized(projectId)) return;
        try {
            Files.createDirectories(workTree(projectId));
            Files.createDirectories(gitDir(projectId).getParent());
            try (Repository repo = new FileRepositoryBuilder()
                    .setGitDir(gitDir(projectId).toFile())
                    .setWorkTree(workTree(projectId).toFile())
                    .build()) {
                repo.create(true);
                try (Git git = new Git(repo)) {
                    git.add().addFilepattern(".").call();
                    git.commit()
                       .setMessage("初始版本\n\nX-AWD-Kind: session")
                       .setAuthor(authorName, authorEmail)
                       .setAllowEmpty(true)
                       .call();
                }
            }
            log.info("版本记录已开启: project={}", projectId);
        } catch (Exception e) {
            throw new VersionException("开启版本记录失败: project=" + projectId, e);
        }
    }

    private static final String KIND_TRAILER = "X-AWD-Kind: ";
    private static final String NOTE_TRAILER = "X-AWD-Note: ";

    /**
     * 把工作区当前状态整体提交。无任何变更时返回 null（不产生空提交）。
     * kind 写入提交消息尾注，供时间线区分「自动存档」与「工作段」。
     */
    public String commitAll(long projectId, String message, String kind, String note,
                            String authorName, String authorEmail) {
        try (Repository repo = open(projectId); Git git = new Git(repo)) {
            git.add().addFilepattern(".").call();
            git.add().addFilepattern(".").setUpdate(true).call();
            Status status = git.status().call();
            if (status.isClean()) return null;

            StringBuilder msg = new StringBuilder(message).append("\n\n")
                    .append(KIND_TRAILER).append(kind);
            if (note != null && !note.isBlank()) {
                msg.append('\n').append(NOTE_TRAILER).append(note);
            }
            RevCommit c = git.commit()
                    .setMessage(msg.toString())
                    .setAuthor(authorName, authorEmail)
                    .call();
            return c.getName();
        } catch (Exception e) {
            throw new VersionException("提交失败: project=" + projectId, e);
        }
    }

    public List<VersionEntry> log(long projectId, String ref, int limit) {
        List<VersionEntry> out = new ArrayList<>();
        try (Repository repo = open(projectId); Git git = new Git(repo)) {
            ObjectId start = repo.resolve(ref);
            if (start == null) return out;
            for (RevCommit c : git.log().add(start).setMaxCount(limit).call()) {
                out.add(toEntry(c));
            }
            return out;
        } catch (Exception e) {
            throw new VersionException("读取历史失败: project=" + projectId, e);
        }
    }

    private VersionEntry toEntry(RevCommit c) {
        String full = c.getFullMessage();
        String kind = extractTrailer(full, KIND_TRAILER);
        String note = extractTrailer(full, NOTE_TRAILER);
        List<String> parents = new ArrayList<>();
        for (RevCommit p : c.getParents()) parents.add(p.getName());
        return new VersionEntry(
                c.getName(),
                c.getShortMessage(),
                c.getAuthorIdent().getName(),
                Instant.ofEpochSecond(c.getCommitTime()),
                kind == null ? "auto" : kind,
                note,
                parents);
    }

    private String extractTrailer(String fullMessage, String prefix) {
        for (String line : fullMessage.split("\n")) {
            String t = line.trim();
            if (t.startsWith(prefix)) return t.substring(prefix.length()).trim();
        }
        return null;
    }

    public List<FileChange> diffNameStatus(long projectId, String fromRef, String toRef) {
        List<FileChange> out = new ArrayList<>();
        try (Repository repo = open(projectId); Git git = new Git(repo);
             RevWalk walk = new RevWalk(repo)) {
            ObjectId from = repo.resolve(fromRef);
            ObjectId to = repo.resolve(toRef);
            if (from == null || to == null) return out;

            CanonicalTreeParser fromTree = new CanonicalTreeParser();
            CanonicalTreeParser toTree = new CanonicalTreeParser();
            fromTree.reset(repo.newObjectReader(), walk.parseCommit(from).getTree());
            toTree.reset(repo.newObjectReader(), walk.parseCommit(to).getTree());

            for (DiffEntry d : git.diff().setOldTree(fromTree).setNewTree(toTree).call()) {
                out.add(new FileChange(
                        d.getChangeType() == DiffEntry.ChangeType.DELETE
                                ? d.getOldPath() : d.getNewPath(),
                        switch (d.getChangeType()) {
                            case ADD, COPY -> FileChange.Type.ADD;
                            case DELETE -> FileChange.Type.DELETE;
                            case RENAME -> FileChange.Type.RENAME;
                            default -> FileChange.Type.MODIFY;
                        }));
            }
            return out;
        } catch (Exception e) {
            throw new VersionException("读取变更清单失败: project=" + projectId, e);
        }
    }

    /** 取某一版里某个相对路径的完整字节；该版中不存在该文件时返回 null。 */
    public byte[] readBlobAtCommit(long projectId, String ref, String relPath) {
        try (Repository repo = open(projectId); RevWalk walk = new RevWalk(repo)) {
            ObjectId commitId = repo.resolve(ref);
            if (commitId == null) return null;
            RevCommit commit = walk.parseCommit(commitId);
            try (TreeWalk tw = TreeWalk.forPath(repo, relPath, commit.getTree())) {
                if (tw == null) return null;
                ObjectLoader loader = repo.open(tw.getObjectId(0));
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                loader.copyTo(bos);
                return bos.toByteArray();
            }
        } catch (Exception e) {
            throw new VersionException("读取历史文件失败: project=" + projectId, e);
        }
    }
}
