package com.checkba.service.capability;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.checkba.service.LangText;
import com.checkba.service.ai.PluginDevService;
import com.checkba.service.ai.PluginService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 能力包安装：{@code plan(url)} 只拉取与校验并返回一份「安装计划」，{@code apply(planId)} 才落盘。
 *
 * <p>拆成两步是因为这条路径的本质是「从互联网取代码到宿主机」：不管发起方是设置页还是 AI，
 * 用户都必须先看到「哪个仓库、哪个 commit、多少文件、要什么权限、落到哪个能力槽」再点头。
 * AI 工具那侧的确认走 {@code <question>} 停机协议。
 *
 * <p>档位与谁能装（设计稿第 4 节）：
 * <ul>
 *   <li>{@code web} —— 沙箱 iframe 前端，AI 可自动装（走 dev 直装同一套校验）；</li>
 *   <li>{@code data} —— 纯声明式包（模板/画像/l10n/设置），零执行面，AI 可自动装；</li>
 *   <li>{@code process} —— 会在宿主机起进程，<b>默认拒绝</b>；只有显式打开开发者模式后
 *       才以 {@code .awd-dev} 标记安装，且候选项在 UI 上永远带「未签名」。</li>
 * </ul>
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class CapabilityInstallService {

    /** 同时保留的安装计划上限：超出即淘汰最旧的一份并清掉它的临时目录 */
    private static final int MAX_PENDING = 8;

    private final CapabilitySourceFetchService fetchService;
    private final PluginDevService pluginDevService;
    private final CapabilitySlotRegistry slotRegistry;

    /**
     * 安装计划。{@code reasons} 非空即 {@code canAutoInstall=false}——每条都是可读的拒绝理由，
     * 原样回给 AI 与设置页（AI 据此自修 manifest 后重来）。
     */
    public record Plan(String planId, String sourceUrl, String owner, String repo, String ref, String commit,
                       String pluginId, String pluginName, String pluginVersion, String description,
                       String kind, List<String> permissions, List<String> slots,
                       int fileCount, long totalBytes, boolean canAutoInstall, List<String> reasons) {
    }

    private record Pending(Plan plan, Path dir, long createdAt) {
    }

    private final Map<String, Pending> pending = new ConcurrentHashMap<>();

    // ==================== plan ====================

    /** 拉取 + 校验，返回安装计划（不落盘、不启用）。URL 非法或仓库拉不下来时抛 IllegalArgumentException。 */
    public Plan plan(String url) {
        CapabilitySourceFetchService.Source src = fetchService.fetch(url);
        Plan plan;
        try {
            plan = buildPlan(url, src);
        } catch (RuntimeException e) {
            CapabilitySourceFetchService.deleteTree(src.dir());
            throw e;
        }
        evictIfNeeded();
        pending.put(plan.planId(), new Pending(plan, src.dir(), System.currentTimeMillis()));
        return plan;
    }

    private Plan buildPlan(String url, CapabilitySourceFetchService.Source src) {
        List<String> reasons = new ArrayList<>();
        JSONObject manifest = readManifest(src.dir(), reasons);

        String id = manifest == null ? "" : manifest.getStr("id", "");
        String name = manifest == null ? "" : manifest.getStr("name", id);
        String version = manifest == null ? "" : manifest.getStr("version", "");
        String description = manifest == null ? "" : manifest.getStr("description", "");

        if (manifest != null && (id == null || id.isBlank())) {
            reasons.add(LangText.of("manifest.json 缺少 id", "manifest.json has no id"));
        } else if (manifest != null && pluginDevService.isMarketplaceInstalled(id)) {
            reasons.add(LangText.of(
                    "本机已装有同名的广场插件（" + id + "），免签安装不覆盖它",
                    "A marketplace plugin with the same id (" + id + ") is already installed"));
        }

        List<String> permissions = stringList(manifest, "permissions");
        for (String forbidden : List.of("backendJars", "tools", "skills", "packs")) {
            if (!stringList(manifest, forbidden).isEmpty()) {
                reasons.add(LangText.of(
                        "manifest." + forbidden + " 非空：JAR / 工具 / skill / 资源包必须走插件广场的审核签名流程",
                        "manifest." + forbidden + " is not empty; JAR/tools/skills/packs must go through the signed marketplace flow"));
            }
        }

        List<PluginService.CapabilityDecl> caps = capabilityDecls(manifest, reasons);
        Set<String> slots = new LinkedHashSet<>();
        String kind = "data";
        for (PluginService.CapabilityDecl decl : caps) {
            slots.add(decl.getCapability());
            kind = maxKind(kind, decl.getKind());
            CapabilitySlotRegistry.SlotDef def = slotRegistry.slot(decl.getCapability());
            if (def == null) {
                reasons.add(LangText.of(
                        "宿主没有这个能力槽: " + decl.getCapability(),
                        "The host has no such capability slot: " + decl.getCapability()));
            } else if (!def.protocol().equals(decl.getProtocol())) {
                reasons.add(LangText.of(
                        "协议不匹配：能力槽 " + def.id() + " 要求 " + def.protocol() + "，声明的是 " + decl.getProtocol(),
                        "Protocol mismatch: slot " + def.id() + " requires " + def.protocol()
                                + " but the declaration says " + decl.getProtocol()));
            }
            if ("process".equals(decl.getKind())) {
                String entry = decl.getEntry().endsWith("/") ? decl.getEntry() : decl.getEntry() + "/";
                if (!Files.isRegularFile(src.dir().resolve(entry + "cli.py"))) {
                    reasons.add(LangText.of(
                            "process 型能力实现的 entry 目录下没有 cli.py: " + decl.getEntry(),
                            "No cli.py under the process capability entry: " + decl.getEntry()));
                }
            }
        }
        if (caps.isEmpty() && manifest != null) {
            String entry = manifest.getStr("frontendEntry", "");
            kind = entry != null && entry.startsWith("web/") ? "web" : "data";
        }

        boolean devMode = slotRegistry.devMode();
        if ("process".equals(kind) && !devMode) {
            reasons.add(LangText.of(
                    "process 型能力包会在本机起进程执行代码，需要签名资源包/插件广场，"
                            + "或在设置页「能力升级」里打开开发者模式后重试",
                    "Process capability packages execute code on this machine; use a signed pack/marketplace, "
                            + "or enable developer mode in Settings > Capability upgrade"));
        }

        return new Plan(UUID.randomUUID().toString(), url, src.owner(), src.repo(), src.ref(), src.commit(),
                id, name, version, description, kind, permissions, new ArrayList<>(slots),
                src.files().size(), src.totalBytes(), reasons.isEmpty(), reasons);
    }

    // ==================== apply ====================

    /** 落盘并安装计划里的那份源码。计划过期、或计划本身不可自动安装，一律拒绝。 */
    public synchronized String apply(String planId) {
        Pending p = pending.get(planId);
        if (p == null) {
            throw new IllegalArgumentException(LangText.of(
                    "安装计划已过期或不存在，请重新获取安装计划",
                    "The install plan has expired or does not exist; create a new plan"));
        }
        Plan plan = p.plan();
        if (!plan.canAutoInstall()) {
            throw new IllegalArgumentException(String.join("\n", plan.reasons()));
        }
        boolean process = "process".equals(plan.kind());
        if (process && !slotRegistry.devMode()) {
            throw new IllegalArgumentException(LangText.of(
                    "开发者模式已关闭，不能安装 process 型能力包",
                    "Developer mode is off; process capability packages cannot be installed"));
        }
        JSONObject marker = new JSONObject();
        marker.set("source", "github");
        marker.set("url", plan.sourceUrl());
        marker.set("owner", plan.owner());
        marker.set("repo", plan.repo());
        marker.set("ref", plan.ref());
        marker.set("commit", plan.commit());
        marker.set("kind", plan.kind());
        try {
            String id = pluginDevService.installFromDirectory(p.dir().toFile(), plan.pluginId(), marker,
                    new PluginDevService.InstallPolicy(process));
            log.info("能力包已安装: id={} kind={} from={}", id, plan.kind(), plan.sourceUrl());
            return id;
        } finally {
            pending.remove(planId);
            CapabilitySourceFetchService.deleteTree(p.dir());
        }
    }

    /** 取回一份已生成的计划（端点/工具回显用）；不存在返回 null。 */
    public Plan peek(String planId) {
        Pending p = pending.get(planId);
        return p == null ? null : p.plan();
    }

    // ==================== 内部 ====================

    private JSONObject readManifest(Path dir, List<String> reasons) {
        Path file = dir.resolve("manifest.json");
        if (!Files.isRegularFile(file)) {
            reasons.add(LangText.of("仓库根目录缺少 manifest.json", "manifest.json is missing at the repository root"));
            return null;
        }
        try {
            return JSONUtil.parseObj(new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
        } catch (Exception e) {
            reasons.add(LangText.of("manifest.json 不是合法 JSON: ", "manifest.json is not valid JSON: ") + e.getMessage());
            return null;
        }
    }

    private static List<PluginService.CapabilityDecl> capabilityDecls(JSONObject manifest, List<String> reasons) {
        List<PluginService.CapabilityDecl> out = new ArrayList<>();
        if (manifest == null) {
            return out;
        }
        JSONObject contributes = manifest.getJSONObject("contributes");
        JSONArray arr = contributes == null ? null : contributes.getJSONArray("capabilities");
        if (arr == null) {
            return out;
        }
        for (Object o : arr) {
            PluginService.CapabilityDecl decl;
            try {
                decl = JSONUtil.toBean(JSONUtil.parseObj(o), PluginService.CapabilityDecl.class);
            } catch (Exception e) {
                reasons.add(LangText.of("contributes.capabilities 条目不是合法对象",
                        "contributes.capabilities entry is not a valid object"));
                continue;
            }
            String why = PluginService.validateCapabilityDecl(decl);
            if (why != null) {
                reasons.add(LangText.of("能力声明不合法: ", "Invalid capability declaration: ") + why);
                continue;
            }
            out.add(decl);
        }
        return out;
    }

    private static List<String> stringList(JSONObject manifest, String key) {
        List<String> out = new ArrayList<>();
        if (manifest == null) {
            return out;
        }
        JSONArray arr = manifest.getJSONArray(key);
        if (arr == null) {
            return out;
        }
        for (Object o : arr) {
            if (o != null) {
                out.add(String.valueOf(o));
            }
        }
        return out;
    }

    /** 档位取最高：process > web > data（一个包里混着多种实现时按最危险的那档受理） */
    private static String maxKind(String a, String b) {
        List<String> order = List.of("data", "web", "process");
        int ia = Math.max(order.indexOf(a), 0);
        int ib = Math.max(order.indexOf(b), 0);
        return order.get(Math.max(ia, ib));
    }

    private void evictIfNeeded() {
        while (pending.size() >= MAX_PENDING) {
            String oldest = pending.entrySet().stream()
                    .min(java.util.Comparator.comparingLong(e -> e.getValue().createdAt()))
                    .map(Map.Entry::getKey).orElse(null);
            if (oldest == null) {
                return;
            }
            Pending p = pending.remove(oldest);
            if (p != null) {
                CapabilitySourceFetchService.deleteTree(p.dir());
            }
        }
    }
}
