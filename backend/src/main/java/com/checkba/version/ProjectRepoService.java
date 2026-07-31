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
import org.eclipse.jgit.lib.Constants;
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
import java.util.LinkedHashSet;
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

    /**
     * 工作区相对 HEAD 的未提交变更。只读——add 到暂存区但不提交。
     *
     * 合并窗口（MERGING/MERGING_RESOLVED）里**一次 add 都不做**：git add 对一条冲突
     * 路径的语义是「这个冲突我解决了」，它会把索引里的冲突记录抹掉、仓库从 MERGING
     * 变 MERGING_RESOLVED（JGit 探针实证）。而版本面板每次挂载都会拉一次 /status、
     * /status 的 changedCount 正是走这里——律师在三选一弹窗前刷新一下面板，就会让
     * 随后的裁决校验空转、带 {@code <<<<<<<} 标记的半成品被提交进主线且稿分支被删，
     * 不可逆。这一档下返回的是「还等着裁决的文件」，changedCount 在冲突窗口里的
     * 语义就是待裁决文件数，对律师同样说得通。
     */
    public List<FileChange> pendingChanges(long projectId) {
        List<FileChange> out = new ArrayList<>();
        try (Repository repo = open(projectId); Git git = new Git(repo)) {
            RepositoryState state = repo.getRepositoryState();
            if (state == RepositoryState.MERGING || state == RepositoryState.MERGING_RESOLVED) {
                for (String p : git.status().call().getConflicting().stream().sorted().toList()) {
                    out.add(new FileChange(p, FileChange.Type.MODIFY));
                }
                return out;
            }
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
            return blobAt(repo, walk, commitId, relPath);
        } catch (Exception e) {
            throw new VersionException("读取历史文件失败: project=" + projectId, e);
        }
    }

    /** readBlobAtCommit 的内核，接收已打开的 Repository/RevWalk（abortMerge 会逐路径调很多轮）。 */
    private byte[] blobAt(Repository repo, RevWalk walk, ObjectId commitId, String relPath)
            throws IOException {
        if (commitId == null) return null;
        RevCommit commit = walk.parseCommit(commitId);
        try (TreeWalk tw = TreeWalk.forPath(repo, relPath, commit.getTree())) {
            if (tw == null) return null;
            ObjectLoader loader = repo.open(tw.getObjectId(0));
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            loader.copyTo(bos);
            return bos.toByteArray();
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
        return mergeCore(projectId, branchName, message, authorName, authorEmail, true, true);
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
        return mergeCore(projectId, branchName, message, authorName, authorEmail, false, true);
    }

    /**
     * 与 {@link #mergeKeepingConflicts} 同形（NO_FF、冲突时不 reset），唯一区别：
     * **干净合并也不提交**，仓库停在 MERGED_NOT_COMMITTED（JGit 探针实证：对应的
     * {@code RepositoryState} 是 {@code MERGING_RESOLVED}，{@link #repositoryMerging}
     * 覆盖得到，MERGE_HEAD 仍在磁盘上），{@code mergeSha} 返回 null，由调用方在
     * 补齐工作区内容后用 {@link #commitMergeResolution} 落成同一个双亲提交。
     *
     * 存在的理由：采纳一稿的干净路径与冲突路径必须以同一种方式提交。让干净路径
     * 自动提交，落库的 {@code .awd/tree.json} 就是 Git 对两份 JSON 做的文本合并，
     * 而数据库随后才被清单并集改写——已提交的清单与数据库当场分叉，而且分叉不会
     * 有任何提交去弥合。不提交，就能让「先按数据库算出清单、再连同裁决结果一起
     * 收进采纳提交」这一条路同时服务两种情况。
     *
     * 「已是最新」（ALREADY_UP_TO_DATE）例外：JGit 这条路径根本不进入合并流程，
     * 不留 MERGE_HEAD、状态是 SAFE（探针实证），无从「稍后提交」——照旧返回它给出的
     * newHead 作为 mergeSha，调用方据此知道这里没有待提交的合并。
     */
    public MergeOutcome mergeNoCommit(long projectId, String branchName, String message,
                                      String authorName, String authorEmail) {
        return mergeCore(projectId, branchName, message, authorName, authorEmail, false, false);
    }

    /**
     * merge()/mergeKeepingConflicts()/mergeNoCommit() 共用的核心逻辑，两个开关：
     * resetOnConflict 为 true 对应 merge() 的「失败即还原」，为 false 对应保留
     * MERGING 态待裁决；commitOnClean 为 true 表示干净合并当场手工署名提交，
     * 为 false 表示留在 MERGED_NOT_COMMITTED 交给调用方（见 {@link #mergeNoCommit}）。
     */
    private MergeOutcome mergeCore(long projectId, String branchName, String message,
                                   String authorName, String authorEmail,
                                   boolean resetOnConflict, boolean commitOnClean) {
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
                } else if (commitOnClean) {
                    RevCommit mergeCommit = git.commit()
                            .setMessage(fullMessage)
                            .setAuthor(authorName, authorEmail)
                            .call();
                    mergeSha = mergeCommit.getName();
                } else {
                    mergeSha = null; // MERGED_NOT_COMMITTED，等调用方提交
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

    /**
     * 索引里仍带着冲突标记的路径，排序后返回；不在合并中时是空列表。
     *
     * 与 {@code MergeOutcome.conflictingPaths()} 的区别是它可以在任何时候重新读出来——
     * 合并冲突留在磁盘上（MERGING 态），进程重启、律师关掉页面再回来，都要能重新问出
     * 「还有哪些文件等着做选择」。只读：这里用 status 而不是 pendingChanges，不做任何 add。
     */
    public List<String> conflictingPaths(long projectId) {
        try (Repository repo = open(projectId); Git git = new Git(repo)) {
            return git.status().call().getConflicting().stream().sorted().toList();
        } catch (Exception e) {
            throw new VersionException("读取冲突文件失败: project=" + projectId, e);
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
     * 无损中止一次保留冲突态的合并：把**这次合并触及的那些路径**还原成 HEAD 的样子，
     * 然后清掉合并态（MERGE_HEAD/MERGE_MSG），RepositoryState 回到 SAFE。只还原文件，
     * 不碰任何提交——历史永不重写。
     *
     * 刻意**不用** {@code reset --hard HEAD}：裁决窗口期版本捕获整体关闭（见
     * {@link WorkSessionService#onChangeSignal} 的 MERGING 守卫），律师在这期间对**别的**
     * 文件做的编辑只在磁盘上、一笔都没进历史；全树 reset 会把它们连根销毁，而按钮旁边
     * 的提示还写着「你的两份稿件都还在」。这里只处理「冲突路径 ∪ HEAD↔MERGE_HEAD 的
     * 差异」——正是合并自己写进工作区的那些文件，窗口外的编辑分毫不动。
     *
     * 三步：索引按这些路径重置回 HEAD（清掉冲突暂存记录）→ 逐路径把 HEAD 的字节写回
     * 工作区（HEAD 里没有的、也就是稿独有的新增文件，从磁盘删掉）→ 清 MERGE_HEAD/
     * MERGE_MSG。非 MERGING/MERGING_RESOLVED 态调用是真正的 no-op（先查 RepositoryState
     * 直接 return）——崩溃恢复路径可能在任意状态下盲调此方法。
     */
    public void abortMerge(long projectId) {
        try (Repository repo = open(projectId); Git git = new Git(repo);
             RevWalk walk = new RevWalk(repo)) {
            RepositoryState st = repo.getRepositoryState();
            if (st != RepositoryState.MERGING && st != RepositoryState.MERGING_RESOLVED) return;

            ObjectId head = repo.resolve(Constants.HEAD);
            ObjectId mergeHead = repo.resolve(Constants.MERGE_HEAD);

            LinkedHashSet<String> paths = new LinkedHashSet<>(git.status().call().getConflicting());
            if (mergeHead != null) {
                for (FileChange c : diffEntries(repo, git, walk, head, mergeHead, null)) {
                    paths.add(c.path());
                }
            }

            if (!paths.isEmpty()) {
                ResetCommand reset = git.reset().setRef(Constants.HEAD);
                for (String p : paths) reset.addPath(p);
                reset.call();

                Path work = workTree(projectId);
                for (String p : paths) {
                    Path target = work.resolve(p);
                    byte[] bytes = blobAt(repo, walk, head, p);
                    if (bytes == null) {
                        Files.deleteIfExists(target);
                    } else {
                        Files.createDirectories(target.getParent());
                        Files.write(target, bytes);
                    }
                }
            }

            repo.writeMergeCommitMsg(null);
            repo.writeMergeHeads(null);
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

    // ==================== 远端（v2 云端协作） ====================

    private static final String ORIGIN = "origin";
    private static final String ORIGIN_MASTER = "refs/remotes/origin/master";
    private static final String MILESTONE_SPEC =
            "+refs/tags/awd/milestone/*:refs/tags/awd/milestone/*";

    /** 建/改 origin。幂等：已存在则改 URL。 */
    public void setRemoteOrigin(long projectId, String url) {
        try (Repository repo = open(projectId); Git git = new Git(repo)) {
            org.eclipse.jgit.transport.URIish uri = new org.eclipse.jgit.transport.URIish(url);
            if (repo.getConfig().getSubsections("remote").contains(ORIGIN)) {
                org.eclipse.jgit.api.RemoteSetUrlCommand cmd = git.remoteSetUrl();
                cmd.setRemoteName(ORIGIN);
                cmd.setRemoteUri(uri);
                cmd.call();
            } else {
                org.eclipse.jgit.api.RemoteAddCommand cmd = git.remoteAdd();
                cmd.setName(ORIGIN);
                cmd.setUri(uri);
                cmd.call();
            }
        } catch (Exception e) {
            throw new VersionException("配置云端地址失败: project=" + projectId, e);
        }
    }

    /** 读 origin URL；未配置返回 null。 */
    public String remoteOriginUrl(long projectId) {
        try (Repository repo = open(projectId)) {
            String url = repo.getConfig().getString("remote", ORIGIN, "url");
            return (url == null || url.isBlank()) ? null : url;
        } catch (Exception e) {
            throw new VersionException("读取云端地址失败: project=" + projectId, e);
        }
    }

    /** 抓 master + 里程碑标签；返回抓完后的 origin/master sha（远端空仓返回 null）。 */
    public String fetchFromOrigin(long projectId, String username, String token) {
        try (Repository repo = open(projectId); Git git = new Git(repo)) {
            git.fetch()
                    .setRemote(ORIGIN)
                    .setCredentialsProvider(
                            new org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider(
                                    username == null ? "" : username, token == null ? "" : token))
                    .setRefSpecs(new org.eclipse.jgit.transport.RefSpec(
                            "+refs/heads/master:" + ORIGIN_MASTER),
                            new org.eclipse.jgit.transport.RefSpec(MILESTONE_SPEC))
                    .setTimeout(60)
                    .call();
            return originMasterSha(projectId);
        } catch (org.eclipse.jgit.api.errors.TransportException e) {
            if (isEmptyRemoteFetchFailure(e)) {
                return null;
            }
            throw new VersionException("从云端更新失败: project=" + projectId, e);
        } catch (Exception e) {
            throw new VersionException("从云端更新失败: project=" + projectId, e);
        }
    }

    /**
     * 判别「远端裸仓从未有人推过、连 refs/heads/master 都不存在」这一 fetch 失败形态。
     * JGit 对显式 refspec fetch 空仓库固定抛
     * "Remote does not have refs/heads/master available for fetch."
     * （FetchProcess.expandSingle 经 JGitText.remoteDoesNotHaveSpec 生成，消息原样透传到
     * FetchCommand 包装后的 org.eclipse.jgit.api.errors.TransportException），
     * 与认证失败、网络不通等其它 TransportException 区分开——只有这一种返回 null，其余仍抛异常。
     */
    private boolean isEmptyRemoteFetchFailure(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            String msg = t.getMessage();
            if (msg != null && msg.contains("does not have") && msg.contains("available for fetch")) {
                return true;
            }
        }
        return false;
    }

    /** 本地已知的 origin/master（不联网）；没有返回 null。 */
    public String originMasterSha(long projectId) {
        return resolveRef(projectId, ORIGIN_MASTER);
    }

    public record PushOutcome(boolean pushed, boolean rejected, String message) {}

    /**
     * 推 master（非强制——被拒即「主线被别人推进」，走返回值不走异常）
     * + 里程碑标签（强制——重命名即覆盖是 tagMilestone 的既有语义，随行到云端）。
     */
    public PushOutcome pushMainlineToOrigin(long projectId, String username, String token) {
        try (Repository repo = open(projectId); Git git = new Git(repo)) {
            Iterable<org.eclipse.jgit.transport.PushResult> results = git.push()
                    .setRemote(ORIGIN)
                    .setCredentialsProvider(
                            new org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider(
                                    username == null ? "" : username, token == null ? "" : token))
                    .setRefSpecs(new org.eclipse.jgit.transport.RefSpec(
                            "refs/heads/master:refs/heads/master"),
                            new org.eclipse.jgit.transport.RefSpec(MILESTONE_SPEC))
                    .setTimeout(60)
                    .call();
            boolean rejected = false;
            StringBuilder msg = new StringBuilder();
            for (org.eclipse.jgit.transport.PushResult r : results) {
                for (org.eclipse.jgit.transport.RemoteRefUpdate u : r.getRemoteUpdates()) {
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
            throw new VersionException("上传到云端失败: project=" + projectId, e);
        }
    }

    /**
     * 快进 master 到 targetRef（含工作区）——云端更新（Task 9）判定「本地未分叉」后走这条路。
     * 非快进（历史已分叉）或工作区有冲突一律抛技术档异常：调用方（CloudSyncService）已经
     * 用 {@code isAncestor} 判过快进条件、且已 dockCurrentLine 保证工作区干净，真出现即
     * 编程错误，不给 userFacing 粉饰。
     */
    public void fastForwardMainline(long projectId, String targetRef) {
        try (Repository repo = open(projectId); Git git = new Git(repo)) {
            var target = repo.resolve(targetRef);
            if (target == null) throw new VersionException("目标版本不存在: " + targetRef);
            var result = git.merge()
                    .include(target)
                    .setFastForward(MergeCommand.FastForwardMode.FF_ONLY)
                    .call();
            if (!result.getMergeStatus().isSuccessful()) {
                throw new VersionException("快进失败: " + result.getMergeStatus());
            }
        } catch (VersionException e) {
            throw e;
        } catch (Exception e) {
            throw new VersionException("快进主线失败: project=" + projectId, e);
        }
    }

    /** ancestorRef 是否在 descendantRef 的历史里（快进判定/主线被推进判定）。 */
    public boolean isAncestor(long projectId, String ancestorRef, String descendantRef) {
        try (Repository repo = open(projectId);
             org.eclipse.jgit.revwalk.RevWalk walk = new org.eclipse.jgit.revwalk.RevWalk(repo)) {
            var a = repo.resolve(ancestorRef);
            var d = repo.resolve(descendantRef);
            if (a == null || d == null) return false;
            return walk.isMergedInto(walk.parseCommit(a), walk.parseCommit(d));
        } catch (Exception e) {
            throw new VersionException("比较版本先后失败: project=" + projectId, e);
        }
    }

    /**
     * 只建仓不落提交：等着接收共享方的首推。共享方的首推要带完整历史进来，服务端
     * 先落初始提交会造出两条无关历史（unrelated histories），永远合不上，所以这里
     * 只建仓、把 HEAD 指到 master，不调用 commit。已初始化则 no-op（幂等）。
     */
    public void initEmptyForReceive(long projectId) {
        if (isInitialized(projectId)) return;
        try {
            Files.createDirectories(workTree(projectId));
            Files.createDirectories(gitDir(projectId).getParent());
            Repository repo = new FileRepositoryBuilder()
                    .setGitDir(gitDir(projectId).toFile())
                    .setWorkTree(workTree(projectId).toFile())
                    .build();
            repo.create();
            org.eclipse.jgit.lib.RefUpdate head = repo.updateRef(Constants.HEAD);
            head.link("refs/heads/" + MAIN_BRANCH);
            repo.close();
        } catch (Exception e) {
            throw new VersionException("初始化云端仓库失败: project=" + projectId, e);
        }
    }

    /** 从云端整仓克隆（gitDir/workTree 分离布局与 init 一致）。 */
    public void cloneFromRemote(long projectId, String url, String username, String token) {
        try {
            Files.createDirectories(workTree(projectId));
            Git.cloneRepository()
                    .setURI(url)
                    .setGitDir(gitDir(projectId).toFile())
                    .setDirectory(workTree(projectId).toFile())
                    .setBranch(MAIN_BRANCH)
                    .setCredentialsProvider(
                            new org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider(
                                    username == null ? "" : username, token == null ? "" : token))
                    .setTimeout(120)
                    .call()
                    .close();
        } catch (Exception e) {
            throw new VersionException("从云端接入项目失败: project=" + projectId, e);
        }
    }

    /** 该版全部文件路径（首推物化用）。 */
    public List<String> listPaths(long projectId, String ref) {
        try (Repository repo = open(projectId); RevWalk walk = new RevWalk(repo)) {
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
            throw new VersionException("读取版本文件列表失败: project=" + projectId, e);
        }
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
