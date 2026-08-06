package com.checkba.version.memory;

import com.checkba.storage.ProjectStorageResolver;
import com.checkba.version.FileChange;
import com.checkba.version.VersionException;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.ResetCommand;
import org.eclipse.jgit.api.Status;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.transport.PushResult;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.RemoteRefUpdate;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.eclipse.jgit.treewalk.AbstractTreeIterator;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.eclipse.jgit.treewalk.EmptyTreeIterator;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 记忆仓库的 Git 薄封装，按 repoKey（user-{id}-memory / project-{id}-memory）寻址。
 *
 * 与 ProjectRepoService 的关系：**刻意不共用**。项目文档仓库那套（工作段/稿/合并窗口/
 * 清单）契约已经布满护栏与地雷，把 gitDir/锁签名改成 repoKey 泛化会churn整个 version 包
 * 的调用点、换不来任何行为收益；记忆仓库需要的原语只有十来个薄 JGit 调用，且合并策略
 * 完全不同（逐文件 LWW 全自动，从不停留在 MERGING）。这里按 spec「按最小改动选择」
 * 单独实现，项目仓库契约零触碰。
 *
 * 布局照抄项目仓库：gitDir = {globalRoot}/repos/{repoKey}.git（裸库），
 * workTree = {globalRoot}/repos/memory-worktrees/{repoKey}（内部物化区，
 * 不在任何项目文件夹下，律师永远看不到）。工作树是**可弃的物化**：每轮同步开始都会
 * reset --hard 到 HEAD——所有内容真相在 DB 与 git 历史里，工作树只是中转。
 */
@Service
public class MemoryRepoService {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(MemoryRepoService.class);

    static final String MAIN_BRANCH = "master";
    private static final String ORIGIN_MASTER = "refs/remotes/origin/master";

    private final ProjectStorageResolver storageResolver;

    public MemoryRepoService(ProjectStorageResolver storageResolver) {
        this.storageResolver = storageResolver;
    }

    public Path gitDir(String repoKey) {
        return storageResolver.globalRoot().resolve("repos").resolve(repoKey + ".git");
    }

    public Path workTree(String repoKey) {
        return storageResolver.globalRoot().resolve("repos").resolve("memory-worktrees").resolve(repoKey);
    }

    public boolean isInitialized(String repoKey) {
        return Files.isDirectory(gitDir(repoKey).resolve("objects"));
    }

    public Repository open(String repoKey) {
        try {
            return new FileRepositoryBuilder()
                    .setGitDir(gitDir(repoKey).toFile())
                    .setWorkTree(workTree(repoKey).toFile())
                    .setMustExist(true)
                    .build();
        } catch (Exception e) {
            throw new VersionException("打开记忆仓库失败: " + repoKey, e);
        }
    }

    /**
     * 建仓（不落任何提交，HEAD 指向未出生的 master）。不落初始提交的理由与
     * ProjectRepoService.initEmptyForReceive 相同：两台机器各自落初始提交会造出
     * 两条无关历史（unrelated histories），永远合不上；空仓等第一笔导出/首次接收。
     * 幂等。
     */
    public void init(String repoKey) {
        if (isInitialized(repoKey)) return;
        try {
            Files.createDirectories(workTree(repoKey));
            Files.createDirectories(gitDir(repoKey).getParent());
            Repository repo = new FileRepositoryBuilder()
                    .setGitDir(gitDir(repoKey).toFile())
                    .setWorkTree(workTree(repoKey).toFile())
                    .build();
            repo.create();
            org.eclipse.jgit.lib.RefUpdate head = repo.updateRef(Constants.HEAD);
            head.link("refs/heads/" + MAIN_BRANCH);
            repo.close();
            log.info("记忆仓库已建立: {}", repoKey);
        } catch (Exception e) {
            throw new VersionException("初始化记忆仓库失败: " + repoKey, e);
        }
    }

    /** 工作区整体提交；无变更返回 null。未出生的 master 上会产生根提交。 */
    public String commitAll(String repoKey, String message, String authorName, String authorEmail) {
        try (Repository repo = open(repoKey); Git git = new Git(repo)) {
            git.add().addFilepattern(".").call();
            git.add().addFilepattern(".").setUpdate(true).call();
            Status status = git.status().call();
            if (status.isClean()) return null;
            RevCommit c = git.commit()
                    .setMessage(message)
                    .setAuthor(authorName, authorEmail)
                    .call();
            return c.getName();
        } catch (Exception e) {
            throw new VersionException("记忆仓库提交失败: " + repoKey, e);
        }
    }

    /** 解析 ref 为完整 sha；不存在返回 null。 */
    public String resolveRef(String repoKey, String ref) {
        try (Repository repo = open(repoKey)) {
            ObjectId id = repo.resolve(ref);
            return id == null ? null : id.getName();
        } catch (Exception e) {
            throw new VersionException("解析记忆版本失败: " + repoKey + " ref=" + ref, e);
        }
    }

    public boolean isAncestor(String repoKey, String ancestorRef, String descendantRef) {
        try (Repository repo = open(repoKey); RevWalk walk = new RevWalk(repo)) {
            ObjectId a = repo.resolve(ancestorRef);
            ObjectId d = repo.resolve(descendantRef);
            if (a == null || d == null) return false;
            return walk.isMergedInto(walk.parseCommit(a), walk.parseCommit(d));
        } catch (Exception e) {
            throw new VersionException("比较记忆版本先后失败: " + repoKey, e);
        }
    }

    /** 合并基线；任一 ref 不可解析或没有公共祖先返回 null（不抛，调用方按空树处理）。 */
    public String mergeBase(String repoKey, String refA, String refB) {
        try (Repository repo = open(repoKey); RevWalk walk = new RevWalk(repo)) {
            ObjectId a = repo.resolve(refA);
            ObjectId b = repo.resolve(refB);
            if (a == null || b == null) return null;
            walk.setRevFilter(org.eclipse.jgit.revwalk.filter.RevFilter.MERGE_BASE);
            walk.markStart(walk.parseCommit(a));
            walk.markStart(walk.parseCommit(b));
            RevCommit base = walk.next();
            return base == null ? null : base.getName();
        } catch (Exception e) {
            throw new VersionException("计算记忆合并基线失败: " + repoKey, e);
        }
    }

    /** fromRef 为 null 表示与空树比较（根提交/无基线的场景）。 */
    public List<FileChange> diffNameStatus(String repoKey, String fromRef, String toRef) {
        try (Repository repo = open(repoKey); Git git = new Git(repo);
             RevWalk walk = new RevWalk(repo)) {
            ObjectId from = fromRef == null ? null : repo.resolve(fromRef);
            ObjectId to = repo.resolve(toRef);
            List<FileChange> out = new ArrayList<>();
            if (to == null) return out;

            AbstractTreeIterator fromTree;
            CanonicalTreeParser toTree = new CanonicalTreeParser();
            try (ObjectReader reader = repo.newObjectReader()) {
                if (from == null) {
                    fromTree = new EmptyTreeIterator();
                } else {
                    CanonicalTreeParser t = new CanonicalTreeParser();
                    t.reset(reader, walk.parseCommit(from).getTree());
                    fromTree = t;
                }
                toTree.reset(reader, walk.parseCommit(to).getTree());
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
            }
            return out;
        } catch (Exception e) {
            throw new VersionException("读取记忆变更清单失败: " + repoKey, e);
        }
    }

    /** 某版里某相对路径的完整字节；不存在返回 null。 */
    public byte[] readBlobAtCommit(String repoKey, String ref, String relPath) {
        try (Repository repo = open(repoKey); RevWalk walk = new RevWalk(repo)) {
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
            throw new VersionException("读取记忆历史文件失败: " + repoKey, e);
        }
    }

    /** 某版的全部文件路径。ref 解析不出返回空列表。 */
    public List<String> listPaths(String repoKey, String ref) {
        try (Repository repo = open(repoKey); RevWalk walk = new RevWalk(repo)) {
            ObjectId id = repo.resolve(ref);
            if (id == null) return List.of();
            RevCommit commit = walk.parseCommit(id);
            List<String> out = new ArrayList<>();
            try (TreeWalk tw = new TreeWalk(repo)) {
                tw.addTree(commit.getTree());
                tw.setRecursive(true);
                while (tw.next()) out.add(tw.getPathString());
            }
            return out;
        } catch (Exception e) {
            throw new VersionException("读取记忆文件列表失败: " + repoKey, e);
        }
    }

    /**
     * master（连同工作区/索引）硬重置到 targetRef。用于两处：① 每轮同步开始把可弃的
     * 工作树物化到 HEAD；② 快进（本地是远端祖先/本地未出生时直接对齐远端）。
     * 只在「目标包含本地全部历史」的前提下调用（调用方已用 isAncestor 判过），
     * 不构成历史重写。未出生的 master 上 JGit 的 ResetCommand 同样适用（直接落 ref）。
     */
    public void hardResetTo(String repoKey, String targetRef) {
        try (Repository repo = open(repoKey); Git git = new Git(repo)) {
            ObjectId target = repo.resolve(targetRef);
            if (target == null) throw new VersionException("目标版本不存在: " + targetRef);
            git.reset().setMode(ResetCommand.ResetType.HARD).setRef(target.getName()).call();
        } catch (VersionException e) {
            throw e;
        } catch (Exception e) {
            throw new VersionException("重置记忆工作区失败: " + repoKey, e);
        }
    }

    /**
     * 双亲合并提交：工作区已经是裁决后的最终内容（LWW 逐文件选边完成），整体 add 后
     * 写 MERGE_HEAD = secondParentSha 再提交——JGit 的 CommitCommand 会读出 MERGE_HEAD
     * 作为第二父（与 ProjectRepoService.commitMergeResolution 同一机制），提交后自动清理。
     */
    public String commitMergeWithSecondParent(String repoKey, String secondParentSha,
                                              String message, String authorName, String authorEmail) {
        try (Repository repo = open(repoKey); Git git = new Git(repo)) {
            git.add().addFilepattern(".").call();
            git.add().addFilepattern(".").setUpdate(true).call();
            repo.writeMergeHeads(List.of(ObjectId.fromString(secondParentSha)));
            RevCommit c = git.commit()
                    .setMessage(message)
                    .setAuthor(authorName, authorEmail)
                    .call();
            return c.getName();
        } catch (Exception e) {
            throw new VersionException("记忆合并提交失败: " + repoKey, e);
        }
    }

    /**
     * 从远端抓 master 到 refs/remotes/origin/master；返回抓完后的 origin/master sha，
     * 远端空仓（从未有人推过）返回 null。remote 直接用 URL，不落 git config——
     * URL/凭据的唯一真源是 memory_remote 表，改配置即刻生效。
     */
    public String fetchFromRemote(String repoKey, String url, String username, String secret) {
        try (Repository repo = open(repoKey); Git git = new Git(repo)) {
            git.fetch()
                    .setRemote(url)
                    .setCredentialsProvider(new UsernamePasswordCredentialsProvider(
                            username == null ? "" : username, secret == null ? "" : secret))
                    .setRefSpecs(new RefSpec("+refs/heads/" + MAIN_BRANCH + ":" + ORIGIN_MASTER))
                    .setTimeout(60)
                    .call();
            return resolveRef(repoKey, ORIGIN_MASTER);
        } catch (org.eclipse.jgit.api.errors.TransportException e) {
            if (isEmptyRemoteFetchFailure(e)) return null;
            throw new VersionException("拉取记忆仓库失败: " + repoKey, e);
        } catch (Exception e) {
            throw new VersionException("拉取记忆仓库失败: " + repoKey, e);
        }
    }

    /** 判别「远端空仓」的 fetch 失败形态，口径同 ProjectRepoService.isEmptyRemoteFetchFailure。 */
    private boolean isEmptyRemoteFetchFailure(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            String msg = t.getMessage();
            if (msg != null && msg.contains("does not have") && msg.contains("available for fetch")) {
                return true;
            }
        }
        return false;
    }

    public record PushOutcome(boolean pushed, boolean rejected, String message) {}

    /** 推 master（非强制）。被拒走返回值不走异常——被拒即「远端被别的机器推进」，是正常路径。 */
    public PushOutcome pushToRemote(String repoKey, String url, String username, String secret) {
        try (Repository repo = open(repoKey); Git git = new Git(repo)) {
            Iterable<PushResult> results = git.push()
                    .setRemote(url)
                    .setCredentialsProvider(new UsernamePasswordCredentialsProvider(
                            username == null ? "" : username, secret == null ? "" : secret))
                    .setRefSpecs(new RefSpec("refs/heads/" + MAIN_BRANCH + ":refs/heads/" + MAIN_BRANCH))
                    .setTimeout(60)
                    .call();
            boolean rejected = false;
            StringBuilder msg = new StringBuilder();
            for (PushResult r : results) {
                for (RemoteRefUpdate u : r.getRemoteUpdates()) {
                    switch (u.getStatus()) {
                        case OK, UP_TO_DATE -> { }
                        default -> {
                            rejected = true;
                            msg.append(u.getRemoteName()).append(':').append(u.getStatus()).append(' ');
                        }
                    }
                }
            }
            return new PushOutcome(!rejected, rejected, msg.toString().trim());
        } catch (Exception e) {
            throw new VersionException("推送记忆仓库失败: " + repoKey, e);
        }
    }

    /** 本地已知的 origin/master sha（不联网）。 */
    public String remoteMasterSha(String repoKey) {
        return resolveRef(repoKey, ORIGIN_MASTER);
    }
}
