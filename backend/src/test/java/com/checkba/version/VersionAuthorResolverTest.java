// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.version;

import com.checkba.model.entity.CloudConnection;
import com.checkba.model.entity.ProjectRemote;
import com.checkba.model.entity.User;
import com.checkba.repository.CloudConnectionRepository;
import com.checkba.repository.ProjectRemoteRepository;
import com.checkba.repository.UserRepository;
import com.checkba.service.account.AccountService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * 提交署名的唯一出口（spec 2026-09-14 §2.1）。
 *
 * <p>这条改动要解决的是：邮箱以前有两套合成公式，其中
 * {@code user-{本机userId}@aiworkdeck.local} 用的是**本机**自增主键——同一个官网账户
 * 在两台电脑上是两个 id，两个不同的人在各自机器上又常常都是 {@code user-1}。
 * 于是「这一版是不是我交的」既漏判也误判，界面对着自己昨晚交的稿说「同事交了新稿」。
 */
class VersionAuthorResolverTest {

    private static final long PROJECT = 7L;
    private static final long ME = 1L;

    private ProjectRemoteRepository remotes;
    private CloudConnectionRepository connections;
    private UserRepository users;
    private VersionAuthorResolver resolver;

    @BeforeEach
    void setUp() {
        remotes = mock(ProjectRemoteRepository.class);
        connections = mock(CloudConnectionRepository.class);
        users = mock(UserRepository.class);
        when(remotes.findByProjectId(anyLong())).thenReturn(Optional.empty());
        when(users.findById(ME)).thenReturn(Optional.of(user(ME, "hanzewei", "韩泽伟")));
        resolver = new VersionAuthorResolver(remotes, connections, users);
    }

    private static User user(long id, String username, String displayName) {
        User u = new User();
        u.setId(id);
        u.setUsername(username);
        u.setDisplayName(displayName);
        return u;
    }

    /** 把项目绑上案件库，连接的账号名是 awd_xxx（官网账户在案件库侧的账号名）。 */
    private void linkToLibrary(String connectionUsername) {
        ProjectRemote remote = new ProjectRemote();
        remote.setProjectId(PROJECT);
        remote.setConnectionId(3L);
        remote.setRemoteProjectId("55");
        CloudConnection conn = new CloudConnection();
        conn.setId(3L);
        conn.setUsername(connectionUsername);
        when(remotes.findByProjectId(PROJECT)).thenReturn(Optional.of(remote));
        when(connections.findById(3L)).thenReturn(Optional.of(conn));
    }

    private static VersionEntry entry(String authorName, String authorEmail) {
        return new VersionEntry("a1b2", "改了合同", authorName, authorEmail,
                Instant.now(), "session", null, List.of(), null, List.of());
    }

    @Test
    @DisplayName("未绑案件库：本机 username + local 域（不是展示名，展示名会被改掉）")
    void unlinkedProjectUsesLocalDomain() {
        VersionAuthorResolver.AuthorIdent id = resolver.resolve(PROJECT, ME);

        assertEquals("韩泽伟", id.name(), "名字仍是 signatureName（展示名优先）");
        assertEquals("hanzewei@local.aiworkdeck.local", id.email());
    }

    @Test
    @DisplayName("已绑案件库：连接的 awd_ 账号名 + collab 域（跨机器稳定、跨人唯一）")
    void linkedProjectUsesCollabDomain() {
        linkToLibrary("awd_hanzewei");

        assertEquals("awd_hanzewei@collab.aiworkdeck.local", resolver.resolve(PROJECT, ME).email());
    }

    @Test
    @DisplayName("本机 username 含中文/空格：清洗成邮箱安全字符，确定性，且两个人不许撞成同一个")
    void weirdUsernameIsSanitizedDeterministically() {
        when(users.findById(9L)).thenReturn(Optional.of(user(9L, "张 三", "张三")));
        when(users.findById(10L)).thenReturn(Optional.of(user(10L, "李 四", "李四")));

        String first = resolver.email(PROJECT, 9L, null);
        String second = resolver.email(PROJECT, 9L, null);

        assertEquals(first, second, "同一个 username 必须永远得到同一个邮箱，否则 isSelf 比不上");
        assertTrue(first.endsWith("@" + VersionAuthorResolver.LOCAL_DOMAIN), first);
        assertTrue(first.matches("[A-Za-z0-9._+\\-]+@.+"), "本地部分必须是邮箱安全字符：" + first);
        assertNotEquals(first, resolver.email(PROJECT, 10L, null),
                "两个中文名不能被清洗成同一个地址——那样 isSelf 会把两个同事判成同一人");
    }

    @Test
    @DisplayName("userId 解析不出本机 username（自动整合那条路）：退化用调用方手上的名字")
    void fallsBackToTheNameOnHand() {
        String email = resolver.email(PROJECT, null, "AI WorkDeck");
        assertTrue(email.startsWith("AIWorkDeck-"), email);
        assertTrue(email.endsWith("@" + VersionAuthorResolver.LOCAL_DOMAIN), email);
    }

    @Test
    @DisplayName("新提交比邮箱：同一账户在另一台电脑上交的稿判为本人")
    void newCommitsAreMatchedByEmail() {
        linkToLibrary("awd_hanzewei");

        // 另一台电脑上的本机 userId 完全不同、展示名恰好也被改过，只有邮箱对得上
        assertTrue(resolver.isSelf(entry("韩律师", "awd_hanzewei@collab.aiworkdeck.local"),
                PROJECT, ME));
    }

    @Test
    @DisplayName("新提交比邮箱：同名的另一个人不算本人")
    void sameNameDifferentAccountIsNotSelf() {
        linkToLibrary("awd_hanzewei");

        assertFalse(resolver.isSelf(entry("韩泽伟", "awd_lisi@collab.aiworkdeck.local"),
                PROJECT, ME));
    }

    @Test
    @DisplayName("存量历史（旧公式邮箱）：回落比展示名，不能一律判否")
    void legacyCommitsFallBackToDisplayName() {
        assertTrue(resolver.isSelf(entry("韩泽伟", "user-1@aiworkdeck.local"), PROJECT, ME),
                "旧公式邮箱不可信，只剩展示名这一条线索");
        assertFalse(resolver.isSelf(entry("李思", "user-1@aiworkdeck.local"), PROJECT, ME));
    }

    @Test
    @DisplayName("邮箱整个缺失（外部 git 客户端提交）：同样回落比展示名，不炸")
    void missingEmailFallsBackToDisplayName() {
        assertTrue(resolver.isSelf(entry("韩泽伟", null), PROJECT, ME));
        assertFalse(resolver.isSelf(null, PROJECT, ME));
    }

    @Test
    @DisplayName("落版路径不许因为查库失败而失败：异常吞掉回落本机域")
    void lookupFailureDegradesInsteadOfThrowing() {
        when(remotes.findByProjectId(PROJECT)).thenThrow(new RuntimeException("db down"));

        String email = resolver.email(PROJECT, ME, "韩泽伟");
        assertTrue(email.endsWith("@" + VersionAuthorResolver.LOCAL_DOMAIN),
                "查库炸了也得给出一个本机域的邮箱，不能把落版一起拖垮：" + email);
    }

    // ---------- 本人历史上用过的那些署名（dev-board#647） ----------
    //
    // 真机现象：案件库领先的 6 版全是本人在另一台电脑上交的，但 9 月 10 日及更早那几版的
    // 署名是「hanzewei」（那阵子 signatureName 还取用户名、邮箱还是旧公式），9 月 11 日起
    // 才是「韩泽伟」。旧的回落只比当前展示名，于是同一个人的几个旧署名被当成几个同事，
    // 顶栏说「韩泽伟等 3 人交了新稿」。放宽只作用在**旧域邮箱**这一侧。

    @Test
    @DisplayName("存量署名是本机 username（那阵子 signatureName 还取用户名）：仍然是我")
    void legacySignatureWithLocalUsernameIsSelf() {
        assertTrue(resolver.isSelf(entry("hanzewei", "hanzewei@aiworkdeck.local"), PROJECT, ME),
                "「hanzewei」是我自己的用户名，不是另一个同事");
    }

    @Test
    @DisplayName("存量署名是案件库账号名（awd_xxx）：仍然是我")
    void legacySignatureWithLibraryAccountIsSelf() {
        linkToLibrary("awd_hanzewei");

        assertTrue(resolver.isSelf(entry("awd_hanzewei", "user-1@aiworkdeck.local"), PROJECT, ME));
    }

    @Test
    @DisplayName("存量署名是官网账户的展示名（本机展示名后来改过）：仍然是我")
    void legacySignatureWithWebsiteDisplayNameIsSelf() {
        AccountService account = mock(AccountService.class);
        when(account.currentDisplayNameOrNull()).thenReturn("韩律师");
        resolver.setAccountServiceForTest(account);

        assertTrue(resolver.isSelf(entry("韩律师", "user-1@aiworkdeck.local"), PROJECT, ME));
    }

    @Test
    @DisplayName("署名早就改掉了，但旧邮箱的名字部分对得上：仍然是我")
    void legacyEmailLocalPartIsAnAliasToo() {
        assertTrue(resolver.isSelf(entry("某个早就改掉的名字", "hanzewei@aiworkdeck.local"),
                PROJECT, ME));
    }

    @Test
    @DisplayName("放宽只作用于旧域：新域邮箱仍然只按邮箱判，同名同事不会被认成我")
    void widenedAliasesNeverLeakIntoTheNewDomain() {
        linkToLibrary("awd_hanzewei");

        assertFalse(resolver.isSelf(entry("hanzewei", "awd_lisi@collab.aiworkdeck.local"),
                PROJECT, ME), "别名放宽绝不能让同名的另一个账户变成本人");
        assertFalse(resolver.isSelf(entry("韩泽伟", "awd_lisi@collab.aiworkdeck.local"),
                PROJECT, ME));
    }

    @Test
    @DisplayName("旧公式里的 user-{本机id} 不算别名：那串正是「两个人都叫 user-1」的病根")
    void machineScopedLegacyLocalPartIsNotAnAlias() {
        // 极端假设：本机 username 恰好就叫 user-1。它仍然不许把别人机器上的 user-1 认成我
        when(users.findById(ME)).thenReturn(Optional.of(user(ME, "user-1", "韩泽伟")));

        assertFalse(resolver.isSelf(entry("李思", "user-1@aiworkdeck.local"), PROJECT, ME));
    }

    @Test
    @DisplayName("快照里的当前署名（出参侧要用它顶掉旧署名）：展示名优先，与 signatureName 同口径")
    void selfIdentityCarriesTheCurrentSignatureName() {
        assertEquals("韩泽伟", resolver.selfIdentity(PROJECT, ME).displayName());
    }
}
