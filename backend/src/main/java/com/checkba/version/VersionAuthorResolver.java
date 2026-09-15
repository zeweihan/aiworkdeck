// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.version;

import com.checkba.model.entity.CloudConnection;
import com.checkba.model.entity.ProjectRemote;
import com.checkba.model.entity.User;
import com.checkba.repository.CloudConnectionRepository;
import com.checkba.repository.ProjectRemoteRepository;
import com.checkba.repository.UserRepository;
import com.checkba.service.LangText;
import com.checkba.service.LocalIdentityService;
import com.checkba.service.UserService;
import com.checkba.service.account.AccountService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 提交署名（作者名 + 作者邮箱）的**唯一出口**（spec 2026-09-14 §2.1）。
 *
 * <h3>为什么要收敛</h3>
 * 在这之前邮箱有两套合成公式：{@code {name}@aiworkdeck.local} 与
 * {@code user-{本机userId}@aiworkdeck.local}。前者跟着展示名走（改个昵称就换一个身份），
 * 后者是**本机**自增主键——同一个官网账户在两台电脑上是两个不同的本机 userId，
 * 反过来两个不同的人在各自机器上又常常都是 {@code user-1}，于是「这一版是不是我提交的」
 * 既会漏判、也会误判。案件库协作一上来，两边看到的历史署名就对不齐。
 *
 * <h3>邮箱规则</h3>
 * <ul>
 *   <li>项目已绑定案件库（{@link ProjectRemote} 存在）→
 *       {@code {CloudConnection.username}@collab.aiworkdeck.local}。
 *       那个 {@code awd_xxx} 是官网账户在案件库侧的账号名，同一账户在任何一台电脑上
 *       都是同一个串——跨机器稳定、跨人唯一，正是「账户级身份」要的东西。</li>
 *   <li>未绑定 → {@code {本机 username}@local.aiworkdeck.local}。本机范围内够用，
 *       且域名自带「这是本机身份、别拿去跨机器比」的语义。</li>
 * </ul>
 *
 * <h3>为什么域名要分两种</h3>
 * {@link #isSelf} 判「这一版是不是我」时先比邮箱；存量历史里的旧公式邮箱既不可信也无从
 * 补救（历史永不重写，地雷 #1），只能回落比展示名。两种新域名是这条分流的判据——
 * 域名不是 {@code collab.}/{@code local.} 的一律按旧格式处理。
 */
@Service
public class VersionAuthorResolver {

    /** 案件库账户级身份的域。 */
    static final String COLLAB_DOMAIN = "collab.aiworkdeck.local";
    /** 本机身份的域。 */
    static final String LOCAL_DOMAIN = "local.aiworkdeck.local";

    /** 一次提交的署名。 */
    public record AuthorIdent(String name, String email) {}

    private final ProjectRemoteRepository remoteRepository;
    private final CloudConnectionRepository connectionRepository;
    private final UserRepository userRepository;

    /**
     * 官网账户（本人历史署名的一种可能来源）。**字段注入**：本类的构造器被几个单测手工
     * {@code new}，加构造参数就要挨个改，换不来行为收益（同 {@code ProjectMemberService}
     * 的先例）。为空时那一条别名自然缺席，其余判定照常。
     */
    @Autowired(required = false)
    private AccountService accountService;

    public VersionAuthorResolver(ProjectRemoteRepository remoteRepository,
                                 CloudConnectionRepository connectionRepository,
                                 UserRepository userRepository) {
        this.remoteRepository = remoteRepository;
        this.connectionRepository = connectionRepository;
        this.userRepository = userRepository;
    }

    /** 手工 new 出实例的单测用。 */
    void setAccountServiceForTest(AccountService accountService) {
        this.accountService = accountService;
    }

    /** 完整署名：名字走 {@link UserService#signatureName}，邮箱走上面那两条规则。 */
    public AuthorIdent resolve(long projectId, Long userId) {
        String name = nameOf(userId);
        return new AuthorIdent(name, email(projectId, userId, name));
    }

    /**
     * 只要邮箱。{@code fallbackName} 是调用方手上已有的署名——本机 username 取不到
     * （userId 为 null，例如上传被拒后的自动整合没有用户上下文）时用它凑本机邮箱，
     * 与旧行为的退化口径一致。
     *
     * <p>整段吞异常回落本机域：这是**落版路径**，版本记录不阻断主流程（地雷 #5），
     * 为一次查库失败让律师的「结束本次工作」失败是本末倒置。
     */
    public String email(long projectId, Long userId, String fallbackName) {
        try {
            String collab = collabUsername(projectId);
            if (collab != null) return sanitize(collab) + "@" + COLLAB_DOMAIN;
            String local = localUsername(userId);
            return sanitize(local != null ? local : fallbackName) + "@" + LOCAL_DOMAIN;
        } catch (Exception e) {
            return localEmail(fallbackName);
        }
    }

    /**
     * 「我是谁」的一次性快照。一趟历史循环（{@code /history} 的一页、云端状态那 200 版）
     * 里复用同一份，不必逐版重查库、重读 account.json。
     *
     * @param email      当前的账户级邮箱（新提交只比它）
     * @param displayName 我现在叫什么（出参侧用它顶掉自己的旧署名）
     * @param aliases    我历史上用过的全部署名，见 {@link #isSelf(VersionEntry, SelfIdentity)}
     */
    public record SelfIdentity(String email, String displayName, Set<String> aliases) {}

    /** 旧公式邮箱的域。 */
    static final String LEGACY_DOMAIN = "aiworkdeck.local";

    /** 旧公式里那个机器级的本地部分 {@code user-{本机userId}}。 */
    private static final Pattern LEGACY_MACHINE_LOCAL_PART = Pattern.compile("user-\\d+");

    /** 见 {@link SelfIdentity}。 */
    public SelfIdentity selfIdentity(long projectId, Long userId) {
        String name = nameOf(userId);
        Set<String> aliases = new LinkedHashSet<>();
        // 出参侧把本机哨兵名按界面语言本地化过，这一侧也要过同一道翻译才比得上
        addAlias(aliases, LocalIdentityService.displayNameOf(name));
        addAlias(aliases, name);
        try {
            // 那阵子 signatureName 还取用户名，时间线上留下的就是这一串
            addAlias(aliases, localUsername(userId));
            // 案件库账号名（awd_xxx）也当过署名
            addAlias(aliases, collabUsername(projectId));
        } catch (Exception ignored) {
            // 查库失败只是少几条别名，判定照常走下去
        }
        try {
            // 官网账户的展示名与本机展示名不一定一样，历史里两种都可能留下过
            if (accountService != null) addAlias(aliases, accountService.currentDisplayNameOrNull());
        } catch (Exception ignored) {
            // 同上：读不到就少一条别名
        }
        return new SelfIdentity(email(projectId, userId, name),
                LocalIdentityService.displayNameOf(name), aliases);
    }

    /**
     * 这一版是不是当前这个人提交的。
     *
     * <p>新提交比邮箱（账户级、跨机器稳定）。邮箱缺失或还是旧公式（域名不是
     * {@code collab.}/{@code local.}）时回落比署名——存量历史只剩这一条可比的线索，
     * 不能因为「比不准」就对全部存量历史一律判否。
     *
     * <p><b>回落这一侧比的是「我历史上用过的全部署名」</b>（dev-board#647）：本机 username
     * （那阵子 signatureName 还取用户名）、案件库账号名、官网账户展示名、当前展示名，
     * 外加旧公式邮箱 {@code {name}@aiworkdeck.local} 的 name 部分。真机上同一个人的 6 版
     * 新稿跨了三个年代的署名，只比当前展示名就会得出「韩泽伟等 3 人交了新稿」。
     *
     * <p><b>放宽只作用在「是本人」这一侧</b>：新域邮箱仍然只按邮箱判（上面那个 if 直接返回），
     * 同名的另一个账户不会因为别名多了就被认成我。{@code user-{本机userId}} 那种机器级的
     * 本地部分也绝不当别名——「两个不同的人都叫 user-1」正是这套邮箱当初被换掉的病根。
     */
    public boolean isSelf(VersionEntry entry, SelfIdentity me) {
        if (entry == null || me == null) return false;
        String email = entry.authorEmail();
        if (isAccountScoped(email)) {
            return email.equalsIgnoreCase(me.email());
        }
        return matchesAlias(me.aliases(), entry.authorName())
                || matchesAlias(me.aliases(), legacyLocalPart(email));
    }

    /** 单次判定（不在循环里的调用方用这个）。 */
    public boolean isSelf(VersionEntry entry, long projectId, Long userId) {
        if (entry == null) return false;
        return isSelf(entry, selfIdentity(projectId, userId));
    }

    private static void addAlias(Set<String> out, String alias) {
        if (alias != null && !alias.isBlank()) out.add(alias.trim());
    }

    private static boolean matchesAlias(Set<String> aliases, String candidate) {
        if (candidate == null || candidate.isBlank() || aliases == null) return false;
        String c = candidate.trim();
        for (String alias : aliases) {
            if (alias.equalsIgnoreCase(c)) return true;
        }
        return false;
    }

    /**
     * 旧公式邮箱 {@code {name}@aiworkdeck.local} 里的 name 部分；不是旧域就回 null。
     *
     * <p>{@code user-{本机userId}} 这一种**不当别名**：那是本机自增主键，两台电脑上的
     * 同一个人是两个 id、两个不同的人又常常都是 {@code user-1}，拿它认人只会误判。
     */
    private static String legacyLocalPart(String email) {
        if (email == null) return null;
        String e = email.trim();
        int at = e.lastIndexOf('@');
        if (at <= 0) return null;
        if (!e.substring(at + 1).equalsIgnoreCase(LEGACY_DOMAIN)) return null;
        String local = e.substring(0, at);
        if (local.isBlank() || LEGACY_MACHINE_LOCAL_PART.matcher(local).matches()) return null;
        return local;
    }

    /**
     * 邮箱里的案件库账号名（{@code awd_xxx@collab.aiworkdeck.local} → {@code awd_xxx}），
     * 不是这个域就回 null。
     *
     * <p>这是「跨机器认人」唯一可靠的锚：同一个官网账户在任何一台电脑上写下的提交
     * 都带同一个账号名，而 git 署名里的**名字**是对方那台机器的本机展示名——
     * 单机模式下人人都叫「本机用户」，两个不同的同事在历史里会显示成同一个人。
     */
    public static String collabUsernameOf(String email) {
        if (email == null) return null;
        String suffix = "@" + COLLAB_DOMAIN;
        String e = email.trim();
        if (e.length() <= suffix.length()) return null;
        if (!e.toLowerCase(Locale.ROOT).endsWith(suffix)) return null;
        String local = e.substring(0, e.length() - suffix.length());
        return local.isBlank() ? null : local;
    }

    /**
     * 这一版该署谁的名字：能在案件库参与人里按账号名对上就用**案件库那边的展示名**，
     * 对不上原样保留 git 署名。
     *
     * <p>为什么要换：事件行（「律师乙 交了稿」）取的是案件库账户的展示名，而版本行取的是
     * 对方机器上的 git 署名——同一个人在同一屏里两种叫法，没连官网账户的用户之间
     * 更是全都叫「本机用户」。本人那一行走的是同一条映射，不走特例。
     *
     * @param remoteNames 案件库账号名 → 展示名，见 {@code CloudSyncService.remoteDisplayNames}
     */
    public static String preferredAuthorName(VersionEntry e, Map<String, String> remoteNames) {
        return preferredAuthorName(e, remoteNames, null);
    }

    /**
     * 同上，外加「这一行是我自己的」这一档（dev-board#647）。
     *
     * <p>顺序是刻意的：**案件库参与人表永远优先**——它是展示名的权威源，本人那一行
     * 也走同一条映射（本列上线时就定下的口径）。只有映射没命中（旧域邮箱、或参与人表里
     * 没有我）时才用当前署名顶掉历史旧署名，否则律师会在自己的历史里看到一串当年的用户名，
     * 还以为是另一个人。Git 对象一字节不碰，只翻出参（历史永不重写）。
     *
     * @param selfCurrentName 这一行判为本人时我现在叫什么；不是本人传 null
     */
    public static String preferredAuthorName(VersionEntry e, Map<String, String> remoteNames,
                                             String selfCurrentName) {
        if (e == null) return null;
        String username = collabUsernameOf(e.authorEmail());
        String mapped = username == null || remoteNames == null ? null : remoteNames.get(username);
        if (mapped != null && !mapped.isBlank()) return mapped;
        if (selfCurrentName != null && !selfCurrentName.isBlank()) return selfCurrentName;
        return e.authorName();
    }

    /** 邮箱是不是本设计新写入的那两种域之一（判读侧据此分流新旧提交）。 */
    static boolean isAccountScoped(String email) {
        if (email == null) return false;
        String e = email.toLowerCase(Locale.ROOT);
        return e.endsWith("@" + COLLAB_DOMAIN) || e.endsWith("@" + LOCAL_DOMAIN);
    }

    /**
     * 没有 resolver 可用时（手工 new 出服务实例的单测）的本机域回落。
     * 生产路径永远走实例方法，这里只保证域名格式一致，isSelf 的分流不会因此走岔。
     */
    public static String localEmail(String userName) {
        return sanitize(userName) + "@" + LOCAL_DOMAIN;
    }

    private String collabUsername(long projectId) {
        if (remoteRepository == null || connectionRepository == null) return null;
        ProjectRemote remote = remoteRepository.findByProjectId(projectId).orElse(null);
        if (remote == null || remote.getConnectionId() == null) return null;
        String username = connectionRepository.findById(remote.getConnectionId())
                .map(CloudConnection::getUsername).orElse(null);
        return username == null || username.isBlank() ? null : username;
    }

    private String localUsername(Long userId) {
        if (userId == null || userRepository == null) return null;
        return userRepository.findById(userId).map(User::getUsername)
                .filter(u -> !u.isBlank()).orElse(null);
    }

    private String nameOf(Long userId) {
        if (userId != null && userRepository != null) {
            try {
                String name = userRepository.findById(userId)
                        .map(UserService::signatureName).orElse(null);
                if (name != null && !name.isBlank()) return name;
            } catch (Exception ignored) {
                // 查库失败不该让落版失败；下面回落到通用称呼
            }
        }
        return LangText.of("用户", "User");
    }

    /**
     * 邮箱本地部分：只留邮箱地址里安全的字符，不安全的**丢掉**并在末尾补一段
     * 原值的哈希。
     *
     * <p>为什么不是「逐字换成 {@code -}」：本机 username 可能是中文（自建服务器上的
     * 人工账号），那样处理之后所有三字中文名都变成 {@code ---@local.…}，
     * {@link #isSelf} 会把两个同事判成同一个人——比「邮箱不好看」严重得多。
     * 补哈希后不同的原值仍然得到不同的本地部分，且是确定性的：同一个 username
     * 在任何机器上算出同一个地址，比对不受影响。纯 ASCII 的名字原样不动。
     */
    private static String sanitize(String raw) {
        if (raw == null || raw.isBlank()) return "user";
        String trimmed = raw.trim();
        StringBuilder sb = new StringBuilder(trimmed.length());
        boolean dropped = false;
        for (char c : trimmed.toCharArray()) {
            boolean safe = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '.' || c == '_' || c == '+' || c == '-';
            if (safe) sb.append(c); else dropped = true;
        }
        if (!dropped) return sb.toString();
        String kept = sb.length() == 0 ? "user" : sb.toString();
        return kept + "-" + shortHash(trimmed);
    }

    /** 原值的 SHA-256 前 6 位十六进制，只用来把被清洗掉的差异重新区分开。 */
    private static String shortHash(String raw) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest, 0, 3);
        } catch (Exception e) {
            return Integer.toHexString(raw.hashCode() & 0xffffff);
        }
    }
}
