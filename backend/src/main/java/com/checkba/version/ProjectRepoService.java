package com.checkba.version;

import com.checkba.storage.StorageProperties;
import org.eclipse.jgit.api.CreateBranchCommand;
import org.eclipse.jgit.api.DiffCommand;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.MergeCommand;
import org.eclipse.jgit.api.MergeResult;
import org.eclipse.jgit.api.ResetCommand;
import org.eclipse.jgit.api.Status;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.RepositoryState;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevObject;
import org.eclipse.jgit.revwalk.RevTag;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.treewalk.AbstractTreeIterator;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.eclipse.jgit.treewalk.EmptyTreeIterator;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.treewalk.filter.PathFilter;
import org.eclipse.jgit.treewalk.filter.TreeFilter;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
            Map<String, String> milestones = milestonesIn(repo);
            for (RevCommit c : git.log().add(start).setMaxCount(limit).call()) {
                out.add(toEntry(c, milestones));
            }
            return out;
        } catch (Exception e) {
            throw new VersionException("读取历史失败: project=" + projectId, e);
        }
    }

    /**
     * 与 {@link #log} 相同，但只保留改动过 relPath 的提交（单文件历史）。
     *
     * <p>刻意不用 JGit 的 {@code addPath}（也就是 git 的默认历史简化）：那条路径会把
     * 「相对第一父提交 TREESAME」的合并提交整条剪掉，而「结束本次工作」的合并恰恰是
     * NO_FF 合并、对工作分支这一父天然 TREESAME——律师命名的工作段节点会在单文件
     * 历史里全部消失，只剩自动存档。这里改为自己走全量历史，逐条按「相对第一父提交
     * 的 diff」判断是否触及该文件；对合并节点来说这份 diff 正是这段工作对该文件的
     * 净变化，也正是律师想看的那一条。单项目仓库很小、limit 上限 100，开销可接受。
     */
    public List<VersionEntry> logForPath(long projectId, String ref, String relPath, int limit) {
        List<VersionEntry> out = new ArrayList<>();
        try (Repository repo = open(projectId); Git git = new Git(repo);
             RevWalk walk = new RevWalk(repo)) {
            ObjectId start = repo.resolve(ref);
            if (start == null) return out;
            Map<String, String> milestones = milestonesIn(repo);
            TreeFilter pathFilter = PathFilter.create(relPath);
            for (RevCommit c : git.log().add(start).setMaxCount(limit).call()) {
                RevCommit commit = walk.parseCommit(c.getId());
                // 根提交没有父版本 → 与空树比较，它自己带进来的文件也算「触及」。
                ObjectId firstParent = commit.getParentCount() == 0
                        ? null : commit.getParent(0).getId();
                if (!diffEntries(repo, git, walk, firstParent, commit.getId(), pathFilter).isEmpty()) {
                    out.add(toEntry(c, milestones));
                }
            }
            return out;
        } catch (Exception e) {
            throw new VersionException("读取历史失败: project=" + projectId, e);
        }
    }

    private VersionEntry toEntry(RevCommit c, Map<String, String> milestones) {
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
                parents,
                milestones.get(c.getName()));
    }

    private String extractTrailer(String fullMessage, String prefix) {
        for (String line : fullMessage.split("\n")) {
            String t = line.trim();
            if (t.startsWith(prefix)) return t.substring(prefix.length()).trim();
        }
        return null;
    }

    /** 工作区相对 HEAD 的未提交变更。只读——add 到暂存区但不提交。 */
    public List<FileChange> pendingChanges(long projectId) {
        List<FileChange> out = new ArrayList<>();
        try (Repository repo = open(projectId); Git git = new Git(repo)) {
            git.add().addFilepattern(".").call();
            git.add().addFilepattern(".").setUpdate(true).call();
            Status st = git.status().call();
            for (String p : st.getAdded()) out.add(new FileChange(p, FileChange.Type.ADD));
            for (String p : st.getChanged()) out.add(new FileChange(p, FileChange.Type.MODIFY));
            for (String p : st.getRemoved()) out.add(new FileChange(p, FileChange.Type.DELETE));
            return out;
        } catch (Exception e) {
            throw new VersionException("读取未提交变更失败: project=" + projectId, e);
        }
    }

    public List<FileChange> diffNameStatus(long projectId, String fromRef, String toRef) {
        try (Repository repo = open(projectId); Git git = new Git(repo);
             RevWalk walk = new RevWalk(repo)) {
            ObjectId from = repo.resolve(fromRef);
            ObjectId to = repo.resolve(toRef);
            return diffEntries(repo, git, walk, from, to, null);
        } catch (Exception e) {
            throw new VersionException("读取变更清单失败: project=" + projectId, e);
        }
    }

    /**
     * 两个提交之间的 name-status 变更清单。{@code from} 为 null 时与空树比较——
     * 根提交没有父版本（形如 sha^ 的 ref resolve 不出来），必须让它自己的文件以
     * ADD 呈现，而不是静默返回空列表。{@code pathFilter} 为 null 表示不过滤。
     * 调用方负责仓库/walk 的生命周期（logForPath 会在一次打开里调很多轮）。
     */
    private List<FileChange> diffEntries(Repository repo, Git git, RevWalk walk,
                                         ObjectId from, ObjectId to, TreeFilter pathFilter)
            throws Exception {
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

            DiffCommand diff = git.diff().setOldTree(fromTree).setNewTree(toTree);
            if (pathFilter != null) diff.setPathFilter(pathFilter);
            for (DiffEntry d : diff.call()) {
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

    private static final String MAIN_BRANCH = "master";

    public String mainBranch() { return MAIN_BRANCH; }

    /** 解析 ref 为完整 sha；不存在返回 null（不抛异常，调用方自己判断）。 */
    public String resolveRef(long projectId, String ref) {
        try (Repository repo = open(projectId)) {
            ObjectId id = repo.resolve(ref);
            return id == null ? null : id.getName();
        } catch (Exception e) {
            throw new VersionException("解析版本失败: project=" + projectId + " ref=" + ref, e);
        }
    }

    public void createBranch(long projectId, String name, String startPointRef) {
        try (Repository repo = open(projectId); Git git = new Git(repo)) {
            git.branchCreate()
               .setName(name)
               .setStartPoint(startPointRef)
               .setUpstreamMode(CreateBranchCommand.SetupUpstreamMode.NOTRACK)
               .call();
        } catch (Exception e) {
            throw new VersionException("创建分支失败: " + name, e);
        }
    }

    public void checkoutBranch(long projectId, String name) {
        try (Repository repo = open(projectId); Git git = new Git(repo)) {
            git.checkout().setName(name).call();
        } catch (Exception e) {
            throw new VersionException("切换分支失败: " + name, e);
        }
    }

    public String currentBranch(long projectId) {
        try (Repository repo = open(projectId)) {
            return repo.getBranch();
        } catch (Exception e) {
            throw new VersionException("读取当前分支失败: project=" + projectId, e);
        }
    }

    public List<String> listBranches(long projectId) {
        List<String> out = new ArrayList<>();
        try (Repository repo = open(projectId); Git git = new Git(repo)) {
            for (Ref r : git.branchList().call()) {
                out.add(Repository.shortenRefName(r.getName()));
            }
            return out;
        } catch (Exception e) {
            throw new VersionException("读取分支列表失败: project=" + projectId, e);
        }
    }

    public void deleteBranch(long projectId, String name, boolean force) {
        try (Repository repo = open(projectId); Git git = new Git(repo)) {
            git.branchDelete().setBranchNames(name).setForce(force).call();
        } catch (Exception e) {
            throw new VersionException("删除分支失败: " + name, e);
        }
    }

    /**
     * 把 branchName 合并进当前分支。
     * 冲突时把工作区硬重置回合并前的 HEAD——spec 第七节要求合并失败后两份稿件都还在，
     * 稿件分支本身未被触碰，所以只需还原当前分支的工作区。
     *
     * 强制禁用快进（setFastForward(NO_FF)）：单人场景下主线在工作期间几乎不变，
     * 合并默认就是可快进的——而快进只挪 ref，不产生提交，调用方传入的 message
     * （标题 + kind=session 尾注）会无处可去，时间线上就出不来这个工作段的命名
     * 节点。禁用快进后，任何真实的合并都会走「产生合并提交」这条路径，工作段
     * 永远有自己的节点。这正是 git --no-ff 的用途。
     *
     * JGit 的 MergeCommand 没有 setAuthor/setCommitter，真正产生新提交的三方合并
     * 如果让 setCommit(true) 自动建提交，作者会退化成 new PersonIdent(repo)——
     * 读不到 git config 时再退化成 JVM user.name，署名就不是操作者本人了。
     * 这里改用 setCommit(false)：JGit 只把合并结果准备到工作区/索引，MERGE_HEAD
     * 仍留在磁盘上；随后手工调用 git.commit() 并显式 setAuthor，JGit 的
     * CommitCommand 会从 MERGE_HEAD 读出另一父提交、连同当前 HEAD 一起写成
     * 双亲的合并提交，提交后自动清理 MERGE_HEAD/MERGE_MSG。
     * 「已是最新」（ALREADY_UP_TO_DATE，工作段期间主线和分支都没有任何新提交）
     * 不受 setCommit(false) 影响——JGit 内部这条路径完全绕开 commit 标志，
     * 不产生新提交，此处直接沿用 JGit 给出的结果，不必也不应手工再建提交。
     */
    public MergeOutcome merge(long projectId, String branchName, String message,
                              String authorName, String authorEmail) {
        return mergeCore(projectId, branchName, message, authorName, authorEmail, true);
    }

    /**
     * 与 {@link #merge} 同形（NO_FF、setCommit(false)、干净路径手工署名提交），
     * 唯一区别：冲突时**不** reset——仓库留在 MERGING 态，索引记录冲突路径，
     * 供上层三选一（{@link #abortMerge} 无损中止 / 手工裁决后 {@link #commitMergeResolution}
     * 产出双亲裁决提交）。干净路径与 merge() 完全等价，行为不应分叉，两者共用
     * {@link #mergeCore}，仅冲突时是否 reset --hard 这一个开关不同。
     */
    public MergeOutcome mergeKeepingConflicts(long projectId, String branchName, String message,
                                              String authorName, String authorEmail) {
        return mergeCore(projectId, branchName, message, authorName, authorEmail, false);
    }

    /**
     * merge()/mergeKeepingConflicts() 共用的核心逻辑，唯一差异是冲突时要不要
     * reset --hard 回到合并前状态——resetOnConflict 为 true 对应 merge() 的
     * 「失败即还原」，为 false 对应 mergeKeepingConflicts() 的「留在 MERGING 态待裁决」。
     */
    private MergeOutcome mergeCore(long projectId, String branchName, String message,
                                   String authorName, String authorEmail, boolean resetOnConflict) {
        try (Repository repo = open(projectId); Git git = new Git(repo)) {
            ObjectId target = repo.resolve(branchName);
            if (target == null) throw new VersionException("分支不存在: " + branchName);

            String fullMessage = message + "\n\n" + KIND_TRAILER + "session";
            MergeResult r = git.merge()
                    .include(target)
                    .setMessage(fullMessage)
                    .setFastForward(MergeCommand.FastForwardMode.NO_FF)
                    .setCommit(false)
                    .call();

            MergeResult.MergeStatus st = r.getMergeStatus();
            if (st.isSuccessful()) {
                String mergeSha;
                if (st == MergeResult.MergeStatus.ALREADY_UP_TO_DATE) {
                    mergeSha = r.getNewHead() == null ? null : r.getNewHead().getName();
                } else {
                    RevCommit mergeCommit = git.commit()
                            .setMessage(fullMessage)
                            .setAuthor(authorName, authorEmail)
                            .call();
                    mergeSha = mergeCommit.getName();
                }
                return new MergeOutcome(true, false, Collections.emptyList(), mergeSha);
            }

            List<String> conflicts = r.getConflicts() == null
                    ? Collections.emptyList()
                    : new ArrayList<>(r.getConflicts().keySet());
            if (resetOnConflict) {
                git.reset().setMode(ResetCommand.ResetType.HARD).setRef("HEAD").call();
            }
            return new MergeOutcome(false, false, conflicts, null);
        } catch (VersionException e) {
            throw e;
        } catch (Exception e) {
            throw new VersionException("合并失败: " + branchName, e);
        }
    }

    /** 仓库是否处于保留冲突态的合并中（MERGING）或冲突已全部标记解决但尚未提交（MERGING_RESOLVED）。 */
    public boolean repositoryMerging(long projectId) {
        try (Repository repo = open(projectId)) {
            RepositoryState st = repo.getRepositoryState();
            return st == RepositoryState.MERGING || st == RepositoryState.MERGING_RESOLVED;
        } catch (Exception e) {
            throw new VersionException("读取仓库状态失败: project=" + projectId, e);
        }
    }

    /** MERGING 态下另一父提交（被合并分支的 tip）的 sha；不在合并中时返回 null。 */
    public String mergeHeadRef(long projectId) {
        try (Repository repo = open(projectId)) {
            ObjectId id = repo.resolve("MERGE_HEAD");
            return id == null ? null : id.getName();
        } catch (Exception e) {
            throw new VersionException("读取合并头失败: project=" + projectId, e);
        }
    }

    /**
     * 无损中止一次保留冲突态的合并：reset --hard HEAD，工作区/索引回到合并前状态，
     * MERGE_HEAD 随之清除、RepositoryState 回到 SAFE。只 reset 到 HEAD，不碰任何
     * 提交——历史永不重写。非 MERGING/MERGING_RESOLVED 态调用是真正的 no-op（先查
     * RepositoryState 直接 return）——SAFE 态下 reset --hard 会把 autosave 防抖窗口里
     * 尚未提交的工作区改动一并销毁，崩溃恢复路径可能在任意状态下盲调此方法，绝不能
     * 借口「幂等」而真的执行一次破坏性 reset。
     */
    public void abortMerge(long projectId) {
        try (Repository repo = open(projectId); Git git = new Git(repo)) {
            RepositoryState st = repo.getRepositoryState();
            if (st != RepositoryState.MERGING && st != RepositoryState.MERGING_RESOLVED) return;
            git.reset().setMode(ResetCommand.ResetType.HARD).setRef("HEAD").call();
        } catch (Exception e) {
            throw new VersionException("中止合并失败: project=" + projectId, e);
        }
    }

    /**
     * 冲突裁决后提交：MERGING/MERGING_RESOLVED 态下把工作区（律师裁决后的最终内容）
     * 整体 add（含 update，覆盖被裁决删除的路径）后手工署名提交。MERGE_HEAD 仍在
     * 磁盘上，JGit 的 CommitCommand 会自动读出它作为第二父提交、连同当前 HEAD 一起
     * 写成双亲的合并提交（第 1 期 Task 4 修署名时已验证过的机制，见 {@link #merge}）。
     * 提交后 JGit 自动清理 MERGE_HEAD/MERGE_MSG，RepositoryState 回到 SAFE。
     * 非 MERGING 态调用是编程错误（没有可裁决的合并），抛技术档异常。
     */
    public String commitMergeResolution(long projectId, String message,
                                        String authorName, String authorEmail) {
        try (Repository repo = open(projectId); Git git = new Git(repo)) {
            RepositoryState st = repo.getRepositoryState();
            if (st != RepositoryState.MERGING && st != RepositoryState.MERGING_RESOLVED) {
                throw new VersionException("当前不在合并冲突状态: project=" + projectId);
            }
            git.add().addFilepattern(".").call();
            git.add().addFilepattern(".").setUpdate(true).call();

            String fullMessage = message + "\n\n" + KIND_TRAILER + "session";
            RevCommit c = git.commit()
                    .setMessage(fullMessage)
                    .setAuthor(authorName, authorEmail)
                    .call();
            return c.getName();
        } catch (VersionException e) {
            throw e;
        } catch (Exception e) {
            throw new VersionException("裁决提交失败: project=" + projectId, e);
        }
    }

    private static final String MILESTONE_TAG_PREFIX = "refs/tags/awd/milestone/";

    /** 标记重要版本：附注标签，名字放 tag message。同一版本重打则覆盖旧名。 */
    public void tagMilestone(long projectId, String sha, String name) {
        try (Repository repo = open(projectId); Git git = new Git(repo);
             RevWalk walk = new RevWalk(repo)) {
            ObjectId id = repo.resolve(sha);
            // 律师点的是时间线上的节点，正常不会不存在；真出现（并发 GC/脏客户端）
            // 时这句话对他有意义、也不含任何内部标识，走 userFacing 直接回显。
            if (id == null) throw VersionException.userFacing("这一版已经不存在了");
            git.tag()
               .setObjectId(walk.parseCommit(id))
               .setName("awd/milestone/" + sha.substring(0, Math.min(12, sha.length())))
               .setMessage(name)
               .setAnnotated(true)
               .setForceUpdate(true)
               .call();
        } catch (VersionException e) { throw e; }
        catch (Exception e) { throw new VersionException("标记重要版本失败", e); }
    }

    /** 全部重要版本：完整 sha → 里程碑名。 */
    public Map<String, String> listMilestones(long projectId) {
        try (Repository repo = open(projectId)) {
            return milestonesIn(repo);
        } catch (Exception e) {
            throw new VersionException("读取重要版本失败: project=" + projectId, e);
        }
    }

    /** log() 与 listMilestones() 共用的读取逻辑，接收已打开的 Repository，避免重复开仓库。 */
    private Map<String, String> milestonesIn(Repository repo) throws IOException {
        Map<String, String> out = new HashMap<>();
        try (RevWalk walk = new RevWalk(repo)) {
            for (Ref ref : repo.getRefDatabase().getRefsByPrefix(MILESTONE_TAG_PREFIX)) {
                try {
                    RevObject obj = walk.parseAny(ref.getObjectId());
                    if (obj instanceof RevTag tag) {
                        out.put(walk.peel(tag).getName(), tag.getFullMessage().strip());
                    }
                } catch (Exception e) {
                    log.warn("解析里程碑失败: {}", ref.getName(), e);
                }
            }
        }
        return out;
    }

    /**
     * 重打包并清理不可达对象。
     * 只动不可达对象（失败的合并、已丢弃工作段的悬空提交）——
     * 可达历史一个不动，这是「历史永不重写」的一部分。
     */
    public void gc(long projectId) {
        try (Repository repo = open(projectId); Git git = new Git(repo)) {
            git.gc().call();
        } catch (Exception e) {
            log.warn("仓库维护失败: project={}", projectId, e);
        }
    }
}
