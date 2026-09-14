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
import org.springframework.stereotype.Service;

import java.util.Locale;

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

    public VersionAuthorResolver(ProjectRemoteRepository remoteRepository,
                                 CloudConnectionRepository connectionRepository,
                                 UserRepository userRepository) {
        this.remoteRepository = remoteRepository;
        this.connectionRepository = connectionRepository;
        this.userRepository = userRepository;
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
     * 这一版是不是当前这个人提交的。
     *
     * <p>新提交比邮箱（账户级、跨机器稳定）；邮箱缺失或还是旧公式（域名不是
     * {@code collab.}/{@code local.}）时回落比展示名——存量历史只剩这一条可比的线索，
     * 会把同名的两个人判成同一人，但那正是这个字段上线之前本来就有的精度，
     * 不能因为「比不准」就对全部存量历史一律判否。
     */
    public boolean isSelf(VersionEntry entry, long projectId, Long userId) {
        if (entry == null) return false;
        AuthorIdent me = resolve(projectId, userId);
        String email = entry.authorEmail();
        if (isAccountScoped(email)) {
            return email.equalsIgnoreCase(me.email());
        }
        // toEntry 出参时把本机哨兵名按界面语言本地化过，这一侧也要过同一道翻译才比得上
        String myName = LocalIdentityService.displayNameOf(me.name());
        return myName != null && myName.equals(entry.authorName());
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
