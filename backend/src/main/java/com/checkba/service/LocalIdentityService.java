package com.checkba.service;

import com.checkba.controller.AuthController;
import com.checkba.model.entity.SystemSetting;
import com.checkba.model.entity.User;
import com.checkba.repository.ProjectFileRepository;
import com.checkba.repository.ProjectRepository;
import com.checkba.repository.SystemSettingRepository;
import com.checkba.repository.UserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * 单机免登模式下的「本机用户」身份解析（商业化改造 PR-A，2026-08-05 修正）。
 *
 * <h3>为什么要改</h3>
 * PR-A 的规则是「local 优先，否则回落 admin」，前提是「老安装的数据都挂在 admin 名下」。
 * 这条前提是错的：真机（~/.aiworkdeck/local.mv.db）实测 admin 名下只有 1 个项目 0 个文件，
 * 用户 6 个项目 21 个文件全在 username=hanzewei 名下。回落 admin 会让老用户解锁后
 * 看到一个近乎空的工作区——数据没丢，但全部不可见。
 *
 * <h3>解析顺序</h3>
 * <ol>
 *   <li><b>已持久化的选择</b>（{@link #SELECTED_KEY}）：用户选过就不再问，跨重启稳定。</li>
 *   <li><b>username=local</b>：一旦某次启动创建过 local 用户（如首个请求赶在 DataInitializer
 *       建 admin 之前的竞态窗口），后续不能因 admin 出现而翻转 userId，否则 local 名下数据成孤儿。
 *       命中后顺手持久化，把这条历史包袱固化成规则 1。</li>
 *   <li><b>按数据量判定</b>：统计每个候选用户的项目数 + 未删除文件数。
 *       <ul>
 *         <li>恰好一个候选有数据 → 选它并持久化（老安装的典型形态，零迁移）；</li>
 *         <li>多个候选有数据 → <b>不猜</b>，返回「待选定」交给前端引导（见 {@link Resolution}）；</li>
 *         <li>没有任何候选有数据 → 复用已存在的空 admin（全新库的典型形态，零新增行、
 *             与 PR-A 行为一致），admin 也不存在时才新建 username=local。</li>
 *       </ul></li>
 * </ol>
 *
 * <h3>「待选定」怎么表达</h3>
 * {@link #localUserId()} 永远返回非 null——它是全后端 90 余处调用方的热路径，返回 null
 * 等于全站 500。待选定时它临时落在「数据量最大的候选」上，但<b>不写持久化</b>，
 * 所以用户在选择页做出的选择仍然说了算。真正的门在前端：
 * {@code GET /api/local-identity/status} 暴露 needsSelection，launch 页解锁后据此
 * 分流到选择页，选完才进工作区。
 *
 * <h3>持久化位置：SystemSetting 表（不是 ~/.aiworkdeck/identity.json）</h3>
 * 存的值是一个指向 local.mv.db 内 user 表的外键。把指针放进它所指向的库里，
 * 二者才能同生共死：用户还原一份旧的 local.mv.db、或重置工作区时，指针跟着一起回退，
 * 不会出现「指针指向一个已经不存在或含义变了的 userId」。license.json / account.json
 * 恰恰相反——它们描述的是「这台机器的授权」，本就该独立于库存在，所以不同规格是刻意的。
 * 顺带省掉一套文件权限收敛与 JSON 解析容错，只为存一个 Long。
 */
@Service
@Slf4j
public class LocalIdentityService {

    public static final String LOCAL_DISPLAY_NAME = "本机用户";

    /**
     * 读取/展示出口：库里恒存中文哨兵值 {@link #LOCAL_DISPLAY_NAME}（见 {@link #commit}/
     * {@link #createLocalUser}），但界面语言切到英文后不能让用户看到「本机用户」四个字。
     * 只在 stored 恰好等于哨兵值时才按当前界面语言替换，真实用户（含云端多用户场景）的
     * displayName 一个字都不动，null 也原样返回——不动库里存的值，只在输出时本地化。
     */
    public static String displayNameOf(String stored) {
        if (!LOCAL_DISPLAY_NAME.equals(stored)) {
            return stored;
        }
        return LangText.of("本机用户", "Local user");
    }

    /** 已选定本机身份的持久化键。 */
    static final String SELECTED_KEY = "local.identity.selectedUserId";

    /**
     * 测试账号用户名前缀。刻意保守——只排除测试脚手架自己造的、名字里带明确标记的账号，
     * 宁可多留一个候选让用户在选择页里自己排除，也绝不能误排真实账号
     * （真机上就有 hanzewei / hanzewei1 / newuser 这类名字，一条都不许命中）。
     */
    private static final List<String> TEST_ACCOUNT_PREFIXES = List.of(
            "qa_bot_",        // frontend/tests/app-e2e：qa_bot_<时间戳>
            "claude-e2e",     // 早期人工/脚本走查账号
            "e2e_keepalive"); // 保活探针账号

    private final UserRepository userRepository;
    private final ProjectRepository projectRepository;
    private final ProjectFileRepository projectFileRepository;
    private final SystemSettingRepository systemSettingRepository;
    private final boolean localMode;

    /** 解析一次后缓存——localUserId 是全后端每请求热路径。选定身份后由 {@link #select} 失效。 */
    private volatile Resolution cached;

    public LocalIdentityService(UserRepository userRepository,
                                ProjectRepository projectRepository,
                                ProjectFileRepository projectFileRepository,
                                SystemSettingRepository systemSettingRepository,
                                @Value("${security.local-mode:false}") boolean localMode) {
        this.userRepository = userRepository;
        this.projectRepository = projectRepository;
        this.projectFileRepository = projectFileRepository;
        this.systemSettingRepository = systemSettingRepository;
        this.localMode = localMode;
        // 无条件注册（与 UserSessionService 同款）：localMode=false 时也要把 static 指回
        // 本上下文，否则测试 JVM 里先起过的 local-mode=true 上下文会一直霸占这个指针，
        // 后续显式关闭 local-mode 的集成测试（如 IdorAuthIntegrationTest）全部被解析成
        // 同一个本机用户，越权断言随类执行顺序漂移（Linux CI 红、mac 本地绿）。
        AuthController.registerLocalIdentityService(this);
    }

    /**
     * 一次解析的结果。
     *
     * @param userId        本机用户 id，恒非 null；needsSelection 时是「数据量最大的候选」这个临时落点
     * @param needsSelection 是否需要用户亲自选定（多个历史账号都有数据）
     */
    public record Resolution(Long userId, boolean needsSelection) {}

    /** 候选账号及其数据量，供选择页与设置页展示。 */
    public record Candidate(Long userId, String username, String displayName,
                            long projectCount, long fileCount) {
        public long dataScore() {
            return projectCount + fileCount;
        }

        public boolean hasData() {
            return dataScore() > 0;
        }
    }

    public boolean isLocalMode() {
        return localMode;
    }

    /** 返回本机用户 id（懒解析 + 缓存），恒非 null。 */
    public Long localUserId() {
        return resolution().userId();
    }

    /** 是否处于「待选定」状态：本机有多个带数据的历史账号，且用户还没选过。 */
    public boolean needsSelection() {
        return resolution().needsSelection();
    }

    /** 当前解析结果（懒解析 + 缓存）。 */
    public Resolution resolution() {
        Resolution r = cached;
        if (r != null) return r;
        synchronized (this) {
            if (cached == null) {
                cached = resolve();
            }
            return cached;
        }
    }

    /**
     * 候选账号列表，按数据量降序（同分按 id 升序，保证顺序稳定）。
     * 无论是否已选定都如实计算——设置页的「切换本机工作区」要靠它给出可改选的全集。
     */
    public List<Candidate> candidates() {
        return userRepository.findAll().stream()
                .filter(u -> u.getId() != null)
                .filter(u -> !isTestAccount(u.getUsername()))
                .map(u -> new Candidate(
                        u.getId(),
                        u.getUsername(),
                        u.getDisplayName(),
                        projectRepository.countByUserId(u.getId()),
                        projectFileRepository.countByUserIdAndIsDeletedFalse(u.getId())))
                .sorted(Comparator.comparingLong(Candidate::dataScore).reversed()
                        .thenComparing(Candidate::userId))
                .toList();
    }

    /**
     * 选定本机身份并持久化。
     *
     * @throws IllegalArgumentException userId 为空、不存在或不在候选集内（测试账号不可选）
     */
    public synchronized Resolution select(Long userId) {
        if (userId == null) {
            throw new IllegalArgumentException("缺少 userId");
        }
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("该账号不存在"));
        if (isTestAccount(user.getUsername())) {
            throw new IllegalArgumentException("该账号不可作为本机工作区");
        }
        Resolution resolved = commit(user);
        cached = resolved;
        log.info("单机模式：已选定本机身份 userId={} username={}", user.getId(), user.getUsername());
        return resolved;
    }

    // ==================== 解析 ====================

    private Resolution resolve() {
        // 1. 用户选过就不再问
        Long selected = loadSelected();
        if (selected != null && userRepository.existsById(selected)) {
            return new Resolution(selected, false);
        }
        if (selected != null) {
            log.warn("单机模式：已持久化的本机身份 userId={} 不存在（库被替换？），重新解析", selected);
        }

        // 2. 历史 local 用户永远优先
        Optional<User> local = userRepository.findByUsername("local");
        if (local.isPresent()) {
            return commit(local.get());
        }

        // 3. 按数据量判定
        List<Candidate> withData = candidates().stream().filter(Candidate::hasData).toList();
        if (withData.size() == 1) {
            Candidate only = withData.get(0);
            log.info("单机模式：唯一有数据的账号 {}（id={}，{} 项目 / {} 文件）已选为本机工作区",
                    only.username(), only.userId(), only.projectCount(), only.fileCount());
            return commit(userRepository.findById(only.userId()).orElseThrow());
        }
        if (withData.size() > 1) {
            Candidate top = withData.get(0);
            log.warn("单机模式：检测到 {} 个有数据的历史账号，等待用户选定本机工作区；"
                            + "选定前临时落在数据量最大的 {}（id={}）",
                    withData.size(), top.username(), top.userId());
            // 临时落点不写持久化——用户的选择才算数
            return new Resolution(top.userId(), true);
        }

        // 4. 全空库：复用 DataInitializer 建出的空 admin（零新增行，与 PR-A 行为一致）
        return commit(userRepository.findByUsername("admin").orElseGet(this::createLocalUser));
    }

    /** 落定一个身份：admin 改名去掉管理员心智 + 持久化 + 返回已确定的 Resolution。 */
    private Resolution commit(User user) {
        User target = user;
        // 只对 username=admin 改名：那是 DataInitializer 播的系统默认账号，
        // 「管理员」不是用户自己起的名字。真实账号（hanzewei 等）的 displayName 一个字都不能动。
        if ("admin".equalsIgnoreCase(target.getUsername())
                && !LOCAL_DISPLAY_NAME.equals(target.getDisplayName())) {
            target.setDisplayName(LOCAL_DISPLAY_NAME);
            target.setUpdatedAt(LocalDateTime.now());
            target = userRepository.save(target);
            log.info("单机模式：复用已有 admin 用户作为本机用户（id={}）", target.getId());
        }
        saveSelected(target.getId());
        return new Resolution(target.getId(), false);
    }

    private User createLocalUser() {
        User user = new User();
        user.setUsername("local");
        user.setDisplayName(LOCAL_DISPLAY_NAME);
        // 本机用户没有登录入口，密码仅为满足非空约束——随机强口令，不可登录使用。
        user.setPassword(UserService.encodePassword(randomPassword()));
        user.setCreatedAt(LocalDateTime.now());
        user.setUpdatedAt(LocalDateTime.now());
        User saved = userRepository.save(user);
        log.info("单机模式：已创建本机用户（id={}）", saved.getId());
        return saved;
    }

    // ==================== 持久化 ====================

    private Long loadSelected() {
        try {
            return systemSettingRepository.findByKey(SELECTED_KEY)
                    .map(SystemSetting::getValue)
                    .map(String::trim)
                    .filter(v -> !v.isEmpty())
                    .map(v -> {
                        try {
                            return Long.parseLong(v);
                        } catch (NumberFormatException e) {
                            log.warn("本机身份持久化值非法（{}），忽略", v);
                            return null;
                        }
                    })
                    .orElse(null);
        } catch (Exception e) {
            log.warn("读取本机身份持久化值失败，按未选定处理: {}", e.getMessage());
            return null;
        }
    }

    private void saveSelected(Long userId) {
        try {
            SystemSetting setting = systemSettingRepository.findByKey(SELECTED_KEY)
                    .orElseGet(() -> {
                        SystemSetting fresh = new SystemSetting();
                        fresh.setKey(SELECTED_KEY);
                        return fresh;
                    });
            setting.setValue(String.valueOf(userId));
            systemSettingRepository.save(setting);
        } catch (Exception e) {
            // 写不进去不致命：下次启动按同样规则重算，结果一致（只是多一次统计）
            log.warn("本机身份持久化写入失败（不影响本次运行）: {}", e.getMessage());
        }
    }

    // ==================== 工具 ====================

    static boolean isTestAccount(String username) {
        if (username == null) return false;
        String lower = username.toLowerCase(Locale.ROOT);
        return TEST_ACCOUNT_PREFIXES.stream().anyMatch(lower::startsWith);
    }

    private static String randomPassword() {
        byte[] bytes = new byte[24];
        new java.security.SecureRandom().nextBytes(bytes);
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
