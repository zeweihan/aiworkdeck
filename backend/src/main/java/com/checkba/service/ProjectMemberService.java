package com.checkba.service;

import com.checkba.model.entity.Project;
import com.checkba.model.entity.ProjectMember;
import com.checkba.model.entity.User;
import com.checkba.repository.AccountBindingRepository;
import com.checkba.repository.ProjectInvitationRepository;
import com.checkba.repository.ProjectMemberRepository;
import com.checkba.repository.ProjectRepository;
import com.checkba.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ProjectMemberService {

    /** 与 SmsAuthService 的落库口径同形：大陆号 11 位裸号，境外号 E.164。 */
    private static final java.util.regex.Pattern MAINLAND_PHONE =
            java.util.regex.Pattern.compile("1[3-9]\\d{9}");
    private static final java.util.regex.Pattern E164_PHONE =
            java.util.regex.Pattern.compile("\\+[1-9]\\d{6,14}");

    private final ProjectMemberRepository projectMemberRepository;
    private final UserRepository userRepository;
    private final ProjectRepository projectRepository;
    private final ProjectInvitationRepository invitationRepository;

    // 下面两样只服务「查人卡片」的头像，**刻意用字段注入而不是构造器参数**：
    // 本类的构造器被 ProjectMemberAddByContactTest / ProjectMemberServiceAddMemberRaceTest
    // 手工 new，加参数是纯 churn（同 ProjectRepoService.maxTrackedFileSizeBytes 的先例）。
    @Autowired(required = false)
    private AccountBindingRepository accountBindingRepository;

    @Value("${ai.account.base-url:https://www.aiworkdeck.com}")
    private String accountBaseUrl;

    /** 单测用：这两样走字段注入，手工 new 出来的实例得有地方补上。 */
    void setAccountLookupForTest(AccountBindingRepository repo, String accountBaseUrl) {
        this.accountBindingRepository = repo;
        this.accountBaseUrl = accountBaseUrl;
    }

    public List<ProjectMember> getProjectMembers(Long projectId) {
        return projectMemberRepository.findByProjectId(projectId);
    }

    /**
     * Get member details (with User info populated if possible, but here we return entities)
     * Usually we need a DTO to return User info.
     */
    public List<ProjectMember> getMembers(Long projectId) {
        return projectMemberRepository.findByProjectId(projectId);
    }
    
    // Helper to get User objects for members
    public List<User> getMemberUsers(Long projectId) {
        List<Long> userIds = projectMemberRepository.findByProjectId(projectId).stream()
                .map(ProjectMember::getUserId)
                .collect(Collectors.toList());
        return userRepository.findAllById(userIds);
    }

    public User getProjectOwner(Long projectId) {
        Project project = projectRepository.findById(projectId).orElse(null);
        if (project != null) {
            return userRepository.findById(project.getUserId()).orElse(null);
        }
        return null;
    }

    /**
     * 把一个人加进项目。
     *
     * @param identifier 手机号 / 邮箱 / 用户名——律师输入什么就传什么，解析见
     *                   {@link #resolveMemberUser(String)}
     */
    @Transactional
    public void addMember(Long projectId, String identifier, String role, Long requesterId) {
        checkAdminPermission(projectId, requesterId);

        User user = resolveMemberUser(identifier);

        if (projectMemberRepository.findByProjectIdAndUserId(projectId, user.getId()).isPresent()) {
            throw new IllegalArgumentException("用户已在项目中");
        }

        ProjectMember member = new ProjectMember();
        member.setProjectId(projectId);
        member.setUserId(user.getId());
        member.setRole(role);
        try {
            projectMemberRepository.save(member);
        } catch (DataIntegrityViolationException e) {
            // 先查后插不是原子的：两个并发请求都查到"不在项目中"就都会插入，第二个撞上
            // (project_id, user_id) 唯一约束。这里不追加恢复性查询——本方法整体
            // @Transactional，插入失败后同一事务在部分数据库（如 Postgres）上已经
            // 不可再用，追加查询本身会再报错——只把异常翻译成查重分支本该给出的提示，
            // 跟着事务一起干净回滚。
            throw new IllegalArgumentException("用户已在项目中");
        }
    }

    /**
     * 把律师输入的一串东西解析成人：**手机号 / 邮箱优先，用户名兜底**。
     *
     * <p>为什么不能只认用户名：官方团队案件库里的账号是 awdk 桥自动建的
     * {@code awd_} 前缀用户名（{@code AwdkLoginService.allocateUsername}），
     * 律师根本无从知道同事叫什么——他知道的是手机号。用户名这条留着，是自建
     * 服务器上人工建号的场景（也覆盖「账号名恰好是一串数字」这种情况）。
     *
     * <p>手机号按存储形态查：大陆号 11 位裸号（{@code +86} 与分隔符都剥掉），
     * 与 {@code SmsAuthService.normalizePhone} 落库时的口径一致——写法不同就查不到，
     * 而律师从通讯录粘出来的号常带 {@code +86} 和空格。邮箱查的是
     * {@code verifiedEmail}（验证过因而可当身份），不是自由填写的资料邮箱。
     */
    private User resolveMemberUser(String identifier) {
        return findMemberUser(identifier)
                .orElseThrow(() -> new IllegalArgumentException(notFoundMessage(identifier)));
    }

    /** 解析本体。查不到不抛异常——查人卡片要把「查不到」当成一个正常结果回显。 */
    private Optional<User> findMemberUser(String identifier) {
        String raw = identifier == null ? "" : identifier.trim();
        if (raw.isEmpty()) {
            return Optional.empty();
        }
        if (raw.contains("@")) {
            Optional<User> byMail = userRepository.findByVerifiedEmail(raw.toLowerCase(Locale.ROOT));
            if (byMail.isPresent()) return byMail;
        } else {
            String phone = phoneForLookup(raw);
            if (phone != null) {
                Optional<User> byPhone = userRepository.findByPhone(phone);
                if (byPhone.isPresent()) return byPhone;
            }
        }
        return userRepository.findByUsername(raw);
    }

    /** 查不到时说下一步该做什么（「用户不存在」对律师没有任何指导意义）。 */
    private static String notFoundMessage(String identifier) {
        String raw = identifier == null ? "" : identifier.trim();
        if (raw.isEmpty()) {
            return LangText.of("请填写同事的手机号或邮箱", "Enter your colleague's phone number or email");
        }
        if (raw.contains("@")) {
            return LangText.of(
                    "对方还没用这个邮箱登录过 AI WorkDeck，请让对方先登录一次再加",
                    "Nobody has signed in to AI WorkDeck with that email yet - ask them to sign in once, then add them");
        }
        if (phoneForLookup(raw) != null) {
            return LangText.of(
                    "对方还没用这个手机号登录过 AI WorkDeck，请让对方先登录一次再加",
                    "Nobody has signed in to AI WorkDeck with that phone number yet - ask them to sign in once, then add them");
        }
        return "用户不存在: " + raw;
    }

    /**
     * 查人卡片（dev-board#444）：{@code found} 之外的字段只在查到人时有值。
     *
     * <p><b>绝不携带原始手机号、用户名、userId</b>——这个端点是一个「这个号注册过没有」
     * 的探测口，回原料等于替撞库与社工做了半件事。展示名 + 打码联系方式 + 头像
     * 足够律师确认「是不是这个人」，也就够了。
     */
    public record MemberLookup(boolean found, String displayName, String avatarUrl,
                               String maskedContact, boolean alreadyMember, String currentRole,
                               String message) {}

    /**
     * 按手机号/邮箱找人并回一张确认卡，**不**做任何改动。
     *
     * <p>存在理由：号码打错一位就把一个陌生人加进了案卷，而案卷里是客户材料。
     * 先看清姓名与头像再点确认，是加人这条路上唯一的可核对环节。
     *
     * <p>权限与 {@link #addMember} 同一道（项目管理员）——查人本身也是能力泄漏，
     * 谁不能加人谁就不该能查。
     */
    public MemberLookup lookupMember(Long projectId, String identifier, Long requesterId) {
        checkAdminPermission(projectId, requesterId);

        Optional<User> found = findMemberUser(identifier);
        if (found.isEmpty()) {
            return new MemberLookup(false, null, null, null, false, null,
                    notFoundMessage(identifier));
        }
        User user = found.get();
        String currentRole = currentRoleOrNull(projectId, user.getId());
        return new MemberLookup(
                true,
                LocalIdentityService.displayNameOf(user.getDisplayName()),
                avatarUrlFor(user),
                maskedContactFor(user, identifier),
                currentRole != null,
                currentRole,
                null);
    }

    /** 已经在案卷里就回现在的角色，否则 null。项目负责人算 OWNER（member 表里可能没有他）。 */
    private String currentRoleOrNull(Long projectId, Long userId) {
        Project project = projectRepository.findById(projectId).orElse(null);
        if (project != null && project.getUserId().equals(userId)) {
            return "OWNER";
        }
        return projectMemberRepository.findByProjectIdAndUserId(projectId, userId)
                .map(ProjectMember::getRole)
                .orElse(null);
    }

    /**
     * 头像地址：本机上传过就用本机那份（自建服务器的人工账号），否则按官网账户绑定
     * 拼出官网的公开头像地址。没有绑定就给 null——硬拼一个必然 404 的地址只会让
     * 界面白等一次网络请求，前端本来就有首字母降级。
     */
    private String avatarUrlFor(User user) {
        if (user.getAvatarUrl() != null && !user.getAvatarUrl().isBlank()) {
            return user.getAvatarUrl();
        }
        if (accountBindingRepository == null || accountBaseUrl == null || accountBaseUrl.isBlank()) {
            return null;
        }
        return accountBindingRepository.findByUserId(user.getId())
                .map(b -> trimTrailingSlash(accountBaseUrl) + "/api/avatar/" + b.getExternalAccountId())
                .orElse(null);
    }

    private static String trimTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    /**
     * 打码联系方式：优先回显律师刚才输入的那一类（他输邮箱却看到一个手机号会以为查错人），
     * 那一类没有再退到另一类，两类都没有（纯用户名建的本地账号）回 null。
     */
    private static String maskedContactFor(User user, String identifier) {
        boolean typedEmail = identifier != null && identifier.contains("@");
        String phone = blankToNull(com.checkba.service.sms.SmsAuthService.maskPhone(user.getPhone()));
        String mail = blankToNull(com.checkba.service.mail.MailAuthService.maskEmail(user.getVerifiedEmail()));
        if (typedEmail) {
            return mail != null ? mail : phone;
        }
        return phone != null ? phone : mail;
    }

    private static String blankToNull(String v) {
        return v == null || v.isBlank() ? null : v;
    }

    /** 号码形态则返回存储形态，否则 null（不抛异常——这里是"要不要按号码查"的判断，不是校验）。 */
    private static String phoneForLookup(String raw) {
        String trimmed = raw.replaceAll("[\\s()-]", "");
        if (trimmed.startsWith("+86")) {
            trimmed = trimmed.substring(3);
        }
        if (MAINLAND_PHONE.matcher(trimmed).matches() || E164_PHONE.matcher(trimmed).matches()) {
            return trimmed;
        }
        return null;
    }

    @Transactional
    public void removeMember(Long projectId, Long userIdToRemove, Long requesterId) {
        if (userIdToRemove.equals(requesterId)) {
             throw new IllegalArgumentException("无法将自己移出项目");
        }

        String requesterRole = getMemberRole(projectId, requesterId);
        String targetRole = getMemberRole(projectId, userIdToRemove);

        if (requesterRole == null) {
            throw new IllegalArgumentException("无权操作");
        }

        boolean allowed = false;
        if ("OWNER".equals(requesterRole)) {
            allowed = true;
        } else if ("ADMIN".equals(requesterRole)) {
            if (!"OWNER".equals(targetRole)) {
                allowed = true;
            }
        } else if ("PARTICIPANT".equals(requesterRole)) {
            if ("READ_ONLY".equals(targetRole) || "CLIENT".equals(targetRole) || "CLIENT_NAMED".equals(targetRole) || "CLIENT_GENERIC".equals(targetRole)) {
                allowed = true;
            }
        }

        if (!allowed) {
            throw new IllegalArgumentException("权限不足：您无法移除该角色的成员");
        }

        ProjectMember member = projectMemberRepository.findByProjectIdAndUserId(projectId, userIdToRemove)
                .orElseThrow(() -> new IllegalArgumentException("成员不存在"));

        projectMemberRepository.delete(member);

        // 移出客户只删成员行不算收回权限：访问码没有有效期，持码人再登一次
        // 就会被重新加成 CLIENT 成员，所以同时把他名下的访问码作废掉。
        if (targetRole != null && targetRole.startsWith("CLIENT")) {
            invitationRepository.findByProjectIdAndRelatedUserId(projectId, userIdToRemove)
                    .ifPresent(invitation -> {
                        invitation.setRevokedAt(LocalDateTime.now());
                        invitationRepository.save(invitation);
                    });
        }
    }

    private String getMemberRole(Long projectId, Long userId) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new IllegalArgumentException("项目不存在"));
        
        if (project.getUserId().equals(userId)) {
            return "OWNER";
        }
        
        return projectMemberRepository.findByProjectIdAndUserId(projectId, userId)
                .map(ProjectMember::getRole)
                .orElse(null);
    }

    public void checkAdminPermission(Long projectId, Long userId) {
        // Check if user is creator
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new IllegalArgumentException("项目不存在"));
        
        if (project.getUserId().equals(userId)) {
            return; 
        }

        // Check if user is ADMIN member
        Optional<ProjectMember> memberOpt = projectMemberRepository.findByProjectIdAndUserId(projectId, userId);
        if (memberOpt.isPresent() && "ADMIN".equals(memberOpt.get().getRole())) {
            return;
        }

        throw new IllegalArgumentException("权限不足：只有管理员可以执行此操作");
    }

    public boolean hasReadPermission(Long projectId, Long userId) {
        Project project = projectRepository.findById(projectId).orElse(null);
        if (project == null) return false;
        if (project.getUserId().equals(userId)) return true;
        
        return projectMemberRepository.findByProjectIdAndUserId(projectId, userId).isPresent();
    }
    
    public boolean hasWritePermission(Long projectId, Long userId) {
        Project project = projectRepository.findById(projectId).orElse(null);
        if (project == null) return false;
        if (project.getUserId().equals(userId)) return true;

        Optional<ProjectMember> memberOpt = projectMemberRepository.findByProjectIdAndUserId(projectId, userId);
        if (memberOpt.isEmpty()) return false;
        
        String role = memberOpt.get().getRole();
        return "ADMIN".equals(role) || "PARTICIPANT".equals(role);
    }

    public boolean isClient(Long projectId, Long userId) {
        Project project = projectRepository.findById(projectId).orElse(null);
        if (project != null && project.getUserId().equals(userId)) return false; // Owner is not client

        Optional<ProjectMember> memberOpt = projectMemberRepository.findByProjectIdAndUserId(projectId, userId);
        if (memberOpt.isEmpty()) return false;
        
        String role = memberOpt.get().getRole();
        return "CLIENT".equals(role) || "CLIENT_NAMED".equals(role) || "CLIENT_GENERIC".equals(role);
    }
}
