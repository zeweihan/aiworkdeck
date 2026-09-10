// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.service.account;

import com.checkba.model.entity.User;
import com.checkba.service.LocalIdentityService;
import com.checkba.service.UserService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Objects;

/**
 * 官网身份 → 本机 {@code User} 行的**唯一同步点**（spec 2026-09-10 §5）。
 *
 * <p>官网的展示名与头像是唯一权威源，本机那份只是它的一份可刷新的副本。落地点有四处：
 * 连接账户时、每次 {@code GET /api/account/status}（应用启动会拉）、
 * {@code GET /api/account/profile}，以及个人设置里三个写动作之后。
 *
 * <p>与 {@code LocalIdentityService.commit} 那条「真实账号的 displayName 一个字都不动」不冲突：
 * 那条禁的是本机按 admin 心智替用户改名，这里是随权威源刷新。
 *
 * <p>红线：
 * <ul>
 *   <li><b>未连接账户 / 官网不可达：不动本机行，也不报错</b>——同步是顺手动作，
 *       为它把应用启动或设置页搞失败不划算；</li>
 *   <li><b>非 local-mode 整条短路</b>：团队案件库与插件云上账户是<b>机器级</b>状态，
 *       按它去改某一个租户的 User 行是张冠李戴（同 TeamUsageUploadService 的第二道闸）。</li>
 * </ul>
 */
@Service
@Slf4j
public class AccountIdentitySync {

    private final AccountService accountService;
    private final LocalIdentityService localIdentityService;
    private final UserService userService;
    private final ApplicationEventPublisher events;

    /**
     * 本机行刚按官网展示名同步过。官方团队案件库那边的展示名只在桥接时刷新，
     * 由 {@code OfficialCloudService} 听这个事件决定要不要重桥（v0.38.2 发版走查：
     * 改完昵称，案件库参与人列表里自己仍是打码手机号）。
     */
    public record DisplayNameSynced(Long userId, String displayName) {}

    public AccountIdentitySync(AccountService accountService,
                               LocalIdentityService localIdentityService,
                               UserService userService,
                               ApplicationEventPublisher events) {
        this.accountService = accountService;
        this.localIdentityService = localIdentityService;
        this.userService = userService;
        this.events = events;
    }

    /**
     * 拉官网档案、同步到本机行，返回身份视图
     * {@code {accountId, displayName, avatarUrl, displayNameIsDefault}}。
     *
     * @throws AccountException 未连接（NOT_CONNECTED）/ 官网不可达 —— 调用方要么上抛给用户，
     *                          要么用 {@link #refreshQuietly()}
     */
    public Map<String, Object> refresh() {
        Map<String, Object> view = accountService.profileIdentity();
        write(str(view.get("displayName")), true, str(view.get("avatarUrl")));
        return view;
    }

    /**
     * 静默版：连接账户时与 {@code GET /api/account/status}（应用启动会拉）用。失败返回 null。
     *
     * <p>非 local-mode 直接短路，连出站都不发：那里同步无处可落，
     * 而 status 是个会被反复调用的端点，白打一趟官网没有任何意义。
     */
    public Map<String, Object> refreshQuietly() {
        if (!localIdentityService.isLocalMode() || !accountService.isConnected()) {
            return null;
        }
        try {
            return refresh();
        } catch (RuntimeException e) {
            log.debug("身份同步跳过（官网不可达或返回异常）: {}", e.getMessage());
            return null;
        }
    }

    /** 改完昵称之后：官网已经写成功了，本机行跟着走。只刷展示名，不碰头像。 */
    public void applyDisplayName(String displayName) {
        write(displayName, false, null);
    }

    /** 传/删完头像之后。{@code null} 表示删掉了——官网没有就是没有。只刷头像，不碰展示名。 */
    public void applyAvatarUrl(String avatarUrl) {
        write(null, true, avatarUrl);
    }

    /**
     * 唯一写入口。{@code displayName} 为空表示这一项不动（官网也可能确实没有名字，
     * 那种情况同样保留本机已有的那份，不清成空白）；头像要不要动由 {@code touchAvatar} 说了算，
     * 因为「清成空」本身就是一种合法的新值。
     */
    private void write(String displayName, boolean touchAvatar, String avatarUrl) {
        if (!localIdentityService.isLocalMode()) {
            return;
        }
        try {
            Long userId = localIdentityService.localUserId();
            if (userId == null) return;
            User user = userService.getUserById(userId);
            if (user == null) return;
            if (displayName != null && !displayName.isBlank()) {
                userService.refreshDisplayNameFromWebsite(user, displayName);
                // 每次都发（不只「本机行变了」时）：升级上来的机器本机行早就是新名字，
                // 案件库连接却还停在旧值，只有这样下次启动才会收敛。听的一方自己比对、没变不动。
                events.publishEvent(new DisplayNameSynced(userId, displayName.trim()));
            }
            if (touchAvatar && !Objects.equals(user.getAvatarUrl(), avatarUrl)) {
                userService.updateAvatar(userId, avatarUrl);
            }
        } catch (RuntimeException e) {
            // 本机行同步失败不该冒泡：官网那边已经是对的，下一次启动还会再同步一次
            log.warn("本机身份行同步失败（不影响官网侧结果）: {}", e.toString());
        }
    }

    private static String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
