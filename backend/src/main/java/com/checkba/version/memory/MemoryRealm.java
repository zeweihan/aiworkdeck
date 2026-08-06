package com.checkba.version.memory;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 一个记忆仓库对应的「领域」：这台机器数据库里哪些 memory_entry 行属于这个仓库。
 *
 * 仓库拓扑（spec Phase A）：
 *   user-{userId}-memory     —— user/global 作用域，owner-only 访问
 *   project-{projectId}-memory —— project/file/conversation 作用域，复用项目成员权限
 *
 * repoKey 是**本机**的领域标识（ownerId 是本机数据库里的 userId/projectId）；
 * 远端 URL 里指向哪个仓库由 MemoryRemote.url 全量给出，两者不要求同名——
 * 跨机器时对方机器上的数字 id 与本机毫无关系（清单 v2 地雷 #27 同款纪律）。
 */
public record MemoryRealm(Kind kind, long ownerId) {

    public enum Kind { USER, PROJECT }

    private static final Pattern USER_KEY = Pattern.compile("^user-(\\d+)-memory$");
    private static final Pattern PROJECT_KEY = Pattern.compile("^project-(\\d+)-memory$");

    /** user 领域同步的记忆作用域。 */
    public static final List<String> USER_SCOPES = List.of("user", "global");
    /** project 领域同步的记忆作用域。 */
    public static final List<String> PROJECT_SCOPES = List.of("project", "file", "conversation");

    public static MemoryRealm user(long userId) {
        return new MemoryRealm(Kind.USER, userId);
    }

    public static MemoryRealm project(long projectId) {
        return new MemoryRealm(Kind.PROJECT, projectId);
    }

    /** 解析 repoKey；不是记忆仓库键时返回 null（调用方自行 404/忽略）。 */
    public static MemoryRealm parse(String repoKey) {
        if (repoKey == null) return null;
        Matcher u = USER_KEY.matcher(repoKey);
        if (u.matches()) return user(Long.parseLong(u.group(1)));
        Matcher p = PROJECT_KEY.matcher(repoKey);
        if (p.matches()) return project(Long.parseLong(p.group(1)));
        return null;
    }

    public String repoKey() {
        return (kind == Kind.USER ? "user-" : "project-") + ownerId + "-memory";
    }

    public List<String> scopes() {
        return kind == Kind.USER ? USER_SCOPES : PROJECT_SCOPES;
    }
}
