package com.checkba.controller;

import com.checkba.service.site.SiteProfile;
import com.checkba.service.site.SiteProfileService;
import com.checkba.service.site.SiteSwitchService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 站点端点（双主站设计 §2.8）。
 *
 * <ul>
 *   <li>GET  /api/site        当前站点 + 可选站点清单（前端渲染解锁页选择器与设置页）</li>
 *   <li>POST /api/site/select {"site":"intl"} 切站，连带清理旧站凭据</li>
 * </ul>
 *
 * <p><b>匿名端点</b>，与 {@link LicenseController} 同族：选站发生在解锁**之前**，
 * 那时没有任何身份可言。安全边界不新开口子——{@code LocalModeAccessFilter} 的三条闸
 * （跨站 Origin 硬拦截 / 回环校验 / 反代痕迹拒绝）同样覆盖这里，
 * 做法与 {@code POST /api/license/deactivate}、{@code POST /api/local-identity/select} 一致。
 *
 * <p>非 local-mode（团队服务器、插件云后端）下站点由部署配置钉定：
 * {@code GET} 只回当前一个站点且 {@code pinned:true}，{@code select} 一律拒绝。
 */
@RestController
@RequestMapping("/api/site")
@Slf4j
public class SiteController {

    private final SiteProfileService siteProfileService;
    private final SiteSwitchService siteSwitchService;

    public SiteController(SiteProfileService siteProfileService, SiteSwitchService siteSwitchService) {
        this.siteProfileService = siteProfileService;
        this.siteSwitchService = siteSwitchService;
    }

    @GetMapping
    public Map<String, Object> status() {
        List<SiteProfile> sites = siteProfileService.availableSites();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("current", siteProfileService.currentSite());
        result.put("pinned", siteProfileService.isPinned());
        // 只有一个可选站点时前端整行不渲染——单站产品摆一个选择器只会让人以为自己选错了
        result.put("multiSite", sites.size() > 1);
        result.put("sites", sites.stream().map(SiteController::describe).toList());
        return result;
    }

    @PostMapping("/select")
    public ResponseEntity<Map<String, Object>> select(@RequestBody(required = false) Map<String, String> body) {
        String site = body == null ? null : body.get("site");
        try {
            return ResponseEntity.ok(siteSwitchService.switchTo(site));
        } catch (IllegalArgumentException e) {
            // 400 才能让前端 api.js reject 到调用点做内联报错（200 会被当成功）
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        } catch (Exception e) {
            log.warn("切换站点失败: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("message", "切换站点失败，请稍后重试"));
        }
    }

    /** 只暴露展示与跳转需要的字段。registry / telemetry 地址是内部路由，不外发。 */
    private static Map<String, Object> describe(SiteProfile profile) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", profile.id());
        item.put("displayName", profile.displayName());
        item.put("baseUrl", profile.baseUrl());
        item.put("accountPageUrl", profile.accountPageUrl());
        return item;
    }
}
