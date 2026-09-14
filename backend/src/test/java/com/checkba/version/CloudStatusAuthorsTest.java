// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.version;

import com.checkba.model.entity.CloudConnection;
import com.checkba.model.entity.ProjectRemote;
import com.checkba.model.entity.User;
import com.checkba.repository.CloudConnectionRepository;
import com.checkba.repository.ProjectFileRepository;
import com.checkba.repository.ProjectRemoteRepository;
import com.checkba.repository.ProjectRepository;
import com.checkba.repository.UserRepository;
import com.checkba.storage.StorageProperties;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.transport.RefSpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 「同事交了新稿」这句话要说对是谁（spec 2026-09-14 §2.5，dev-board#623）。
 *
 * <p>病根：{@code remoteAhead} 是纯 ref 比较，不读作者。而同一个官网账号在两台机器上
 * 桥接案件库落到**同一行** app_users，远端提交的署名与本机一模一样——律师昨晚在办公室
 * 交的稿，今早在家看到的提示是「同事交了新稿」。
 *
 * <p>fixture：一个真实本地仓 + 一个 {@link BareHub} 裸仓当案件库，用 peer 克隆推几版
 * 上去制造「案件库领先」，作者邮箱按 {@link VersionAuthorResolver} 的 collab 规则写
 * （案件库账号名 + {@code @collab.aiworkdeck.local}），这正是真实桌面端会写进去的值。
 */
class CloudStatusAuthorsTest {

    private static final long PROJECT = 7L;
    private static final long ME = 1L;
    /** 我在案件库里的账号名（awdk 桥自动生成的那串），两台电脑上都是它。 */
    private static final String MY_LIBRARY_ACCOUNT = "awd_hanzewei";

    private Path root;
    private ProjectRepoService repoSvc;
    private CloudSyncService cloud;

    /**
     * 案件库 {@code GET /api/projects/55/members} 的桩回包。默认是一张空表——
     * 映射不到任何人，状态条保持 git 署名，也就是本列上线前的行为。
     * 单测绝不许真的出网：{@link #newCloud} 里那个 httpGet 覆写对别的 URL 直接抛。
     */
    private String membersJson = "{\"code\":0,\"data\":[]}";

    @BeforeEach
    void setUp(@TempDir Path tmp) throws Exception {
        root = tmp;
        Files.createDirectories(root.resolve("projects/7"));
        Files.writeString(root.resolve("projects/7/合同.txt"), "初稿");

        StorageProperties props = new StorageProperties();
        props.getLocal().setRootPath(root.toAbsolutePath().toString());
        repoSvc = new ProjectRepoService(new com.checkba.storage.ProjectStorageResolver(props, null));
        repoSvc.init(PROJECT, "韩泽伟", MY_LIBRARY_ACCOUNT + "@collab.aiworkdeck.local");

        CloudConnection conn = new CloudConnection();
        conn.setId(3L);
        conn.setServerUrl("https://case.aiworkdeck.com");
        conn.setUsername(MY_LIBRARY_ACCOUNT);
        conn.setDeviceToken("awdt_test");
        conn.setCreatedAt(LocalDateTime.now());
        CloudConnectionRepository connRepo = mock(CloudConnectionRepository.class);
        when(connRepo.findById(3L)).thenReturn(Optional.of(conn));

        ProjectRemote remote = new ProjectRemote();
        remote.setId(1L);
        remote.setProjectId(PROJECT);
        remote.setConnectionId(3L);
        remote.setRemoteProjectId("55");
        remote.setPendingUpload(false);
        ProjectRemoteRepository remoteRepo = mock(ProjectRemoteRepository.class);
        when(remoteRepo.findByProjectId(PROJECT)).thenReturn(Optional.of(remote));

        User me = new User();
        me.setId(ME);
        me.setUsername("hanzewei");
        me.setDisplayName("韩泽伟");
        UserRepository users = mock(UserRepository.class);
        when(users.findById(ME)).thenReturn(Optional.of(me));

        cloud = newCloud(repoSvc, connRepo, remoteRepo);
        cloud.setAuthorResolverForTest(new VersionAuthorResolver(remoteRepo, connRepo, users));

        // 案件库：裸仓 + 把本机现有历史先推上去，两边这时是齐的
        Path bare = Files.createTempDirectory("case-library");
        repoSvc.setRemoteOrigin(PROJECT, BareHub.init(bare));
        assertTrue(repoSvc.pushMainlineToOrigin(PROJECT, MY_LIBRARY_ACCOUNT, "awdt_test").pushed());
        repoSvc.fetchFromOrigin(PROJECT, MY_LIBRARY_ACCOUNT, "awdt_test");
    }

    /** 某个人在案件库上交了一版（peer 克隆 → 提交 → 推回去），本机随后 fetch 一次。 */
    private void someoneSubmits(String displayName, String libraryAccount, String content)
            throws Exception {
        Path peerDir = Files.createTempDirectory("peer");
        try (Git peer = Git.cloneRepository()
                .setURI(repoSvc.remoteOriginUrl(PROJECT))
                .setDirectory(peerDir.toFile()).setBranch("master").call()) {
            Files.writeString(peerDir.resolve("合同.txt"), content);
            peer.add().addFilepattern(".").call();
            peer.commit().setMessage(content + "\n\nX-AWD-Kind: session")
                    .setAuthor(displayName, libraryAccount + "@collab.aiworkdeck.local").call();
            peer.push().setRefSpecs(new RefSpec("refs/heads/master:refs/heads/master")).call();
        }
        repoSvc.fetchFromOrigin(PROJECT, MY_LIBRARY_ACCOUNT, "awdt_test");
    }

    private Map<String, Object> status() {
        Map<String, Object> st = cloud.cloudStatus(PROJECT, ME);
        assertEquals(Boolean.TRUE, st.get("linked"));
        return st;
    }

    @Test
    @DisplayName("案件库不领先：一个作者字段都不多给（省掉一次白walk）")
    void nothingAheadCarriesNoAuthorFields() {
        Map<String, Object> st = status();

        assertEquals(Boolean.FALSE, st.get("remoteAhead"));
        assertNull(st.get("remoteAheadCount"));
        assertNull(st.get("remoteAheadAuthors"));
        assertNull(st.get("remoteAheadAuthorCount"));
        assertNull(st.get("remoteAheadBySelf"));
    }

    @Test
    @DisplayName("全是我自己在另一台电脑上交的：bySelf=true（这正是 #623 报的那句错话）")
    void allBySelf() throws Exception {
        someoneSubmits("韩泽伟", MY_LIBRARY_ACCOUNT, "办公室里改的第二稿");
        // 那台电脑上交第三稿之前把展示名改了——同一个账户，名字对不上，
        // 只有邮箱这条账户级线索还能认出是本人（比展示名的老办法在这里就会判错）
        someoneSubmits("韩律师", MY_LIBRARY_ACCOUNT, "办公室里改的第三稿");

        Map<String, Object> st = status();

        assertEquals(Boolean.TRUE, st.get("remoteAhead"));
        assertEquals(2, st.get("remoteAheadCount"));
        assertEquals(Boolean.TRUE, st.get("remoteAheadBySelf"));
        assertEquals(List.of("韩律师", "韩泽伟"), st.get("remoteAheadAuthors"), "新的在前");
    }

    @Test
    @DisplayName("一位同事交的：bySelf=false，名字给出来")
    void oneColleague() throws Exception {
        someoneSubmits("李思", "awd_lisi", "李思改的第二稿");

        Map<String, Object> st = status();

        assertEquals(1, st.get("remoteAheadCount"));
        assertEquals(Boolean.FALSE, st.get("remoteAheadBySelf"));
        assertEquals(List.of("李思"), st.get("remoteAheadAuthors"));
        assertEquals(1, st.get("remoteAheadAuthorCount"));
    }

    @Test
    @DisplayName("多人交的（含我自己一版）：bySelf 必须是 false，作者去重、最多 3 个")
    void severalPeopleIncludingMe() throws Exception {
        someoneSubmits("韩泽伟", MY_LIBRARY_ACCOUNT, "我改的一版");
        someoneSubmits("李思", "awd_lisi", "李思改的一版");
        someoneSubmits("李思", "awd_lisi", "李思又改的一版");
        someoneSubmits("王五", "awd_wangwu", "王五改的一版");
        someoneSubmits("赵六", "awd_zhaoliu", "赵六改的一版");

        Map<String, Object> st = status();

        assertEquals(5, st.get("remoteAheadCount"));
        assertEquals(Boolean.FALSE, st.get("remoteAheadBySelf"),
                "只要掺进一版同事的，界面就该说同事的名字");
        @SuppressWarnings("unchecked")
        List<String> authors = (List<String>) st.get("remoteAheadAuthors");
        assertEquals(3, authors.size(), "最多 3 个：状态条只放得下这么多，实际 " + authors);
        assertEquals(authors.size(), authors.stream().distinct().count(), "要去重：" + authors);
        // 名单截断到 3 个，人数不能跟着截断——否则「张三等 N 人」在四个人以上永远说成 3 人
        assertEquals(4, st.get("remoteAheadAuthorCount"), "我 + 李思 + 王五 + 赵六 = 4 个人");
    }

    @Test
    @DisplayName("userId 未知（单参重载）：退化成原来那份纯 ref 快照，不谎称是本人")
    void withoutUserIdItDegradesInsteadOfGuessing() throws Exception {
        someoneSubmits("韩泽伟", MY_LIBRARY_ACCOUNT, "我改的一版");

        Map<String, Object> st = cloud.cloudStatus(PROJECT);

        assertEquals(Boolean.TRUE, st.get("remoteAhead"));
        assertEquals(Boolean.FALSE, st.get("remoteAheadBySelf"));
    }

    @Test
    @DisplayName("统计炸了也只是少几个字段：remoteAhead 与整条状态照常给")
    void walkFailureKeepsTheRestOfTheStatus() throws Exception {
        someoneSubmits("李思", "awd_lisi", "李思改的第二稿");
        ProjectRepoService broken = spy(repoSvc);
        doThrow(new VersionException("读取区间历史失败"))
                .when(broken).commitsBetween(anyLong(), any(), any(), anyInt());
        CloudSyncService degraded = newCloud(broken, connRepoOf(), remoteRepoOf());

        Map<String, Object> st = degraded.cloudStatus(PROJECT, ME);

        assertEquals(Boolean.TRUE, st.get("remoteAhead"), "一句更准的话不值得把整条状态打成 500");
        assertNull(st.get("remoteAheadCount"));
    }

    /**
     * 案件库展示名的映射（把「本机用户」换成案件库账户真正的名字）。
     *
     * <p>这是 #623 的另一半：{@code remoteAheadAuthors} 取的是 git 署名，也就是**对方那台
     * 机器上的本机展示名**——单机模式下人人都叫「本机用户」，两个不同的同事在状态条里
     * 会显示成同一个人。而事件行取的是案件库账户的展示名，同一屏里两种叫法。
     */
    @Test
    @DisplayName("状态条里的名字换成案件库账户的展示名；映射不到的保持 git 署名")
    void remoteDisplayNamesReplaceTheGitSignature() throws Exception {
        membersJson = "{\"code\":0,\"data\":["
                + "{\"username\":\"awd_lisi\",\"displayName\":\"李思律师\"}]}";
        // 两个人在各自机器上都叫「本机用户」，只有案件库账号名能把他们分开
        someoneSubmits("本机用户", "awd_lisi", "李思改的第二稿");
        someoneSubmits("本机用户", "awd_wangwu", "王五改的一版");

        Map<String, Object> st = status();

        assertEquals(List.of("本机用户", "李思律师"), st.get("remoteAheadAuthors"),
                "新的在前：王五不在参与人表里，保持 git 署名；李思换成案件库那边的名字");
        assertEquals(2, st.get("remoteAheadAuthorCount"), "映射之后才看得出是两个人");
    }

    @Test
    @DisplayName("同一个项目十分钟内只取一次参与人表（状态条 120 秒轮询一次）")
    void memberTableIsFetchedOncePerTtl() throws Exception {
        someoneSubmits("本机用户", "awd_lisi", "李思改的第二稿");

        status();
        status();
        status();

        assertEquals(1, memberRequests, "缓存没生效的话每次轮询都要多打一趟请求");
    }

    private int memberRequests;

    /** 带 httpGet 桩的 CloudSyncService：只答参与人表，别的 URL 一律当作「这条测试写错了」。 */
    private CloudSyncService newCloud(ProjectRepoService repo,
                                      CloudConnectionRepository connRepo,
                                      ProjectRemoteRepository remoteRepo) {
        return new CloudSyncService(repo, mock(WorkSessionService.class),
                mock(ProjectTreeManifestService.class), mock(ProjectFileRepository.class),
                connRepo, remoteRepo, mock(ProjectRepository.class)) {
            @Override
            protected String httpGet(String url, String sessionToken) {
                if (url.endsWith("/members")) {
                    memberRequests++;
                    return membersJson;
                }
                throw new IllegalStateException("这条测试不该联网: " + url);
            }
        };
    }

    // 上一条用例要重建一个 CloudSyncService，把 setUp 里那两个 mock 仓储再取出来用
    private CloudConnectionRepository connRepoOf() {
        CloudConnection conn = new CloudConnection();
        conn.setId(3L);
        conn.setUsername(MY_LIBRARY_ACCOUNT);
        conn.setServerUrl("https://case.aiworkdeck.com");
        CloudConnectionRepository repo = mock(CloudConnectionRepository.class);
        when(repo.findById(3L)).thenReturn(Optional.of(conn));
        return repo;
    }

    private ProjectRemoteRepository remoteRepoOf() {
        ProjectRemote remote = new ProjectRemote();
        remote.setId(1L);
        remote.setProjectId(PROJECT);
        remote.setConnectionId(3L);
        remote.setRemoteProjectId("55");
        remote.setPendingUpload(false);
        ProjectRemoteRepository repo = mock(ProjectRemoteRepository.class);
        when(repo.findByProjectId(PROJECT)).thenReturn(Optional.of(remote));
        return repo;
    }
}
