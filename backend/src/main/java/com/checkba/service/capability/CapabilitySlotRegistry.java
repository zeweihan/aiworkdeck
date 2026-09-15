// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.service.capability;

import com.checkba.service.SystemSettingService;
import com.checkba.service.ai.PluginService;
import com.checkba.service.pack.NativePackService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * 能力槽注册表（规范 v2.10 §15，设计稿 docs/superpowers/specs/2026-09-07-capability-slots-self-upgrade-design.md）。
 *
 * <p>一个「槽」= 一项可替换的能力（首期只有诉讼可视化的出图引擎 {@code litigation.diagram}）。
 * 每个槽有若干候选实现：
 * <ul>
 *   <li>{@code builtin} —— 随包内置那份，由消费方（如 {@code LitigationVisualService}）
 *       在 {@code @PostConstruct} 里用 {@link #registerBuiltin} 登记探针。永远存在，是兜底。</li>
 *   <li>{@code pack:<packId>} —— 签名原生资源包解出来的组件目录。</li>
 *   <li>{@code plugin:<pluginId>:<capId>} —— 已启用插件在 manifest 的
 *       {@code contributes.capabilities} 里声明的实现目录。</li>
 * </ul>
 *
 * <p>选择位存 {@code system_setting} 的 {@code capability.<slot>.selected}，上一次的值存
 * {@code .previous}（回滚用）。形状与降级链<b>照抄</b>样式画像的
 * {@code ai.styleProfile.selected}：插件被禁用、目录被删、entry 下没有协议要求的入口文件，
 * 一律静默降级并记 WARN，绝不让一个坏候选把出图整条链路炸掉。
 *
 * <p><b>resolve 只回答「有没有选中一个非内置实现」</b>：选中 builtin 或什么都没选时返回
 * empty，消费方接着走自己原有的解析链（那条链的结果就是 builtin）。这样内置实现的定位
 * 逻辑只有一份，不会在这里被抄第二遍。
 */
@Service
@Slf4j
public class CapabilitySlotRegistry {

    /** 首期唯一的槽：诉讼可视化出图引擎 */
    public static final String SLOT_LITIGATION_DIAGRAM = "litigation.diagram";

    /** 该槽的协议号：能力包必须逐字声明同一个值才受理 */
    public static final String PROTOCOL_LITVIZ_CLI_1 = "litviz-cli/1";

    /** 内置实现的候选引用 */
    public static final String REF_BUILTIN = "builtin";

    /** 开发者模式开关（默认开）：关闭后禁止安装未签名的 process 型能力实现 */
    public static final String DEV_MODE_KEY = "capability.dev-mode";

    public static final String SOURCE_BUILTIN = "builtin";
    public static final String SOURCE_PACK = "pack";
    public static final String SOURCE_PLUGIN = "plugin";

    /**
     * 一个槽的定义。
     *
     * @param packId        该槽对应的签名资源包 id（无则 null）
     * @param packComponent 资源包里的组件目录名
     * @param entryFile     实现目录下必须存在的入口文件（协议要求），用于判定候选是否可用
     */
    public record SlotDef(String id, String name, String protocol, String kind,
                          String packId, String packComponent, String entryFile) {
    }

    /**
     * 一个候选实现。{@code dir} 为 null 表示当前解析不出目录（内置探针没登记、pack 未装、
     * 插件目录损坏），此时 {@code available} 必为 false。
     */
    public record Candidate(String ref, String source, String label, String dir,
                            boolean available, boolean unsigned, String reason) {
    }

    /** 槽表：首期硬编码一条。加槽在这里加，不需要动数据库。 */
    private static final Map<String, SlotDef> SLOTS = new LinkedHashMap<>();

    static {
        SLOTS.put(SLOT_LITIGATION_DIAGRAM, new SlotDef(
                SLOT_LITIGATION_DIAGRAM, "诉讼可视化出图引擎", PROTOCOL_LITVIZ_CLI_1,
                "process", "litigation-visual", "litviz", "cli.py"));
    }

    private final PluginService pluginService;
    private final SystemSettingService systemSettingService;
    /** 资源包服务可选：不带 pack 的部署形态（自部署 / 单测）下候选表少一档而已 */
    private final ObjectProvider<NativePackService> packServiceProvider;

    /** slotId -> 内置实现目录探针，由消费方登记（与 NativePackService.registerBuiltinProbe 同款） */
    private final Map<String, Supplier<Path>> builtinSuppliers = new ConcurrentHashMap<>();

    /**
     * slotId -> 「选择变了」回调。消费方多半把解析结果缓存起来（litviz 的 runtime 就是
     * 只算一次的懒加载），切换是不重启后端的 live 操作——没有这条回调，切完之后当前
     * 进程内仍然在用旧目录，「切换即生效」就是假的。形制同 NativePackService.onPackChanged。
     */
    private final Map<String, List<Runnable>> changeListeners = new ConcurrentHashMap<>();

    public CapabilitySlotRegistry(PluginService pluginService,
                                  SystemSettingService systemSettingService,
                                  ObjectProvider<NativePackService> packServiceProvider) {
        this.pluginService = pluginService;
        this.systemSettingService = systemSettingService;
        this.packServiceProvider = packServiceProvider;
    }

    // ==================== 槽表 ====================

    public List<SlotDef> slots() {
        return new ArrayList<>(SLOTS.values());
    }

    public SlotDef slot(String slotId) {
        return SLOTS.get(slotId);
    }

    /** 登记内置实现的定位探针；同一个槽重复登记以最后一次为准。 */
    public void registerBuiltin(String slotId, Supplier<Path> supplier) {
        if (slotId != null && supplier != null) {
            builtinSuppliers.put(slotId, supplier);
        }
    }

    /** 登记「该槽的选择变了」回调（消费方用它失效自己的解析缓存）。 */
    public void onSlotChanged(String slotId, Runnable listener) {
        if (slotId != null && listener != null) {
            changeListeners.computeIfAbsent(slotId, k -> new java.util.concurrent.CopyOnWriteArrayList<>())
                    .add(listener);
        }
    }

    private void fireChanged(String slotId) {
        for (Runnable r : changeListeners.getOrDefault(slotId, List.of())) {
            try {
                r.run();
            } catch (Exception e) {
                log.warn("能力槽 {} 的变更回调抛异常：{}", slotId, e.getMessage());
            }
        }
    }

    // ==================== 候选 ====================

    /** 某个槽的全部候选实现（builtin 恒在首位，其后是 pack 与已启用插件的声明）。 */
    public List<Candidate> candidates(String slotId) {
        SlotDef def = SLOTS.get(slotId);
        List<Candidate> out = new ArrayList<>();
        if (def == null) {
            return out;
        }
        out.add(builtinCandidate(def));
        packCandidate(def).ifPresent(out::add);
        out.addAll(pluginCandidates(def));
        return out;
    }

    private Candidate builtinCandidate(SlotDef def) {
        Path dir = null;
        Supplier<Path> supplier = builtinSuppliers.get(def.id());
        if (supplier != null) {
            try {
                dir = supplier.get();
            } catch (Exception e) {
                log.warn("能力槽 {} 的内置探针抛异常：{}", def.id(), e.getMessage());
            }
        }
        boolean ok = dir != null && hasEntryFile(dir, def);
        return new Candidate(REF_BUILTIN, SOURCE_BUILTIN, "内置",
                dir == null ? null : dir.toString(), ok, false,
                ok ? null : "随包内置资源不在场");
    }

    private Optional<Candidate> packCandidate(SlotDef def) {
        if (def.packId() == null) {
            return Optional.empty();
        }
        NativePackService pack = packServiceProvider == null ? null : packServiceProvider.getIfAvailable();
        if (pack == null) {
            return Optional.empty();
        }
        Path dir = pack.componentDir(def.packId(), def.packComponent()).orElse(null);
        if (dir == null) {
            return Optional.empty();
        }
        boolean ok = hasEntryFile(dir, def);
        return Optional.of(new Candidate("pack:" + def.packId(), SOURCE_PACK, "签名包 " + def.packId(),
                dir.toString(), ok, false, ok ? null : "资源包里没有 " + def.entryFile()));
    }

    private List<Candidate> pluginCandidates(SlotDef def) {
        List<Candidate> out = new ArrayList<>();
        for (PluginService.PluginMetadata meta : pluginService.getPlugins()) {
            if (!pluginService.isEnabled(meta.getId()) || meta.getContributes() == null
                    || meta.getContributes().getCapabilities() == null) {
                continue;
            }
            for (PluginService.CapabilityDecl decl : meta.getContributes().getCapabilities()) {
                if (!def.id().equals(decl.getCapability())) {
                    continue;
                }
                String ref = "plugin:" + meta.getId() + ":" + decl.getId();
                // 协议不匹配的实现照样列出来（否则用户看不见「装了但没生效」），但不可选
                if (!def.protocol().equals(decl.getProtocol())) {
                    out.add(new Candidate(ref, SOURCE_PLUGIN, meta.getName(), null, false, false,
                            "协议不匹配：槽要求 " + def.protocol() + "，实现声明 " + decl.getProtocol()));
                    continue;
                }
                Path dir = resolveEntryDir(meta.getId(), decl.getEntry());
                boolean unsigned = isDevInstalled(meta.getId());
                if (dir == null) {
                    out.add(new Candidate(ref, SOURCE_PLUGIN, meta.getName(), null, false, unsigned,
                            "entry 目录不存在或逃逸插件目录：" + decl.getEntry()));
                    continue;
                }
                boolean ok = hasEntryFile(dir, def);
                out.add(new Candidate(ref, SOURCE_PLUGIN, meta.getName(), dir.toString(), ok, unsigned,
                        ok ? null : "entry 目录下没有 " + def.entryFile()));
            }
        }
        return out;
    }

    /** 插件 entry 目录的 canonical 校验（第二道逃逸闸，第一道在 parseManifest） */
    private Path resolveEntryDir(String pluginId, String entry) {
        File pluginDir = pluginService.getPluginDir(pluginId);
        if (pluginDir == null || entry == null) {
            return null;
        }
        try {
            File target = new File(pluginDir, entry);
            String base = pluginDir.getCanonicalPath() + File.separator;
            if (!target.getCanonicalPath().startsWith(base) || !target.isDirectory()) {
                return null;
            }
            return target.toPath().toAbsolutePath().normalize();
        } catch (IOException e) {
            log.warn("插件 {} 的能力 entry 路径检查失败：{}", pluginId, e.getMessage());
            return null;
        }
    }

    private boolean isDevInstalled(String pluginId) {
        File dir = pluginService.getPluginDir(pluginId);
        return dir != null && new File(dir, com.checkba.service.ai.PluginDevService.DEV_MARKER).isFile();
    }

    private static boolean hasEntryFile(Path dir, SlotDef def) {
        if (dir == null) {
            return false;
        }
        if (def.entryFile() == null || def.entryFile().isBlank()) {
            return Files.isDirectory(dir);
        }
        return Files.isRegularFile(dir.resolve(def.entryFile()));
    }

    // ==================== 选择与解析 ====================

    public String selectedRef(String slotId) {
        return systemSettingService.get(selectedKey(slotId), "");
    }

    /**
     * 当前生效的引用：没选过时就是 {@code builtin}。
     * 「没选过」与「显式选了内置」在行为上没有区别，回滚历史里也不该有这个区别——
     * 否则从初始态切走一次之后就永远回不去（上一次的值是空串，看起来像没有历史）。
     */
    public String effectiveRef(String slotId) {
        String ref = selectedRef(slotId);
        return ref == null || ref.isBlank() ? REF_BUILTIN : ref;
    }

    public String previousRef(String slotId) {
        return systemSettingService.get(previousKey(slotId), "");
    }

    /** 完整解析（含 pack 候选）。 */
    public Optional<Path> resolve(String slotId) {
        return resolve(slotId, true);
    }

    /**
     * 选中的非内置实现目录。
     *
     * @param includePack false = 跳过 pack 来源的候选（消费方判定「不借助资源包时能力是否可用」时用；
     *                    插件来源的候选不受影响——它不是 pack）
     * @return 没选 / 选了 builtin / 选中的候选已不可用，一律 empty（调用方接着走自己的降级链）
     */
    public Optional<Path> resolve(String slotId, boolean includePack) {
        String ref = selectedRef(slotId);
        if (ref == null || ref.isBlank() || REF_BUILTIN.equals(ref)) {
            return Optional.empty();
        }
        if (!includePack && ref.startsWith("pack:")) {
            return Optional.empty();
        }
        Candidate c = candidates(slotId).stream().filter(x -> x.ref().equals(ref)).findFirst().orElse(null);
        if (c == null || !c.available() || c.dir() == null) {
            log.warn("能力槽 {} 选中的实现 {} 不可用（{}），降级到内置链",
                    slotId, ref, c == null ? "候选已消失" : c.reason());
            return Optional.empty();
        }
        return Optional.of(Path.of(c.dir()));
    }

    /** 选中的实现是否已降级（选了非内置实现但解析不出来）——给管理页打「已降级」标。 */
    public boolean isDegraded(String slotId) {
        String ref = selectedRef(slotId);
        if (ref == null || ref.isBlank() || REF_BUILTIN.equals(ref)) {
            return false;
        }
        Candidate c = candidates(slotId).stream().filter(x -> x.ref().equals(ref)).findFirst().orElse(null);
        return c == null || !c.available();
    }

    /** 切换某个槽的实现；写入前把当前值存进 {@code .previous} 供回滚。 */
    public void select(String slotId, String ref) {
        SlotDef def = SLOTS.get(slotId);
        if (def == null) {
            throw new IllegalArgumentException("未知能力槽: " + slotId);
        }
        String target = (ref == null || ref.isBlank()) ? REF_BUILTIN : ref.trim();
        if (!REF_BUILTIN.equals(target)) {
            Candidate c = candidates(slotId).stream().filter(x -> x.ref().equals(target)).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("候选实现不存在: " + target));
            if (!c.available()) {
                throw new IllegalArgumentException("候选实现当前不可用: " + target
                        + (c.reason() == null ? "" : "（" + c.reason() + "）"));
            }
        }
        String current = effectiveRef(slotId);
        if (target.equals(current)) {
            return;
        }
        systemSettingService.set(previousKey(slotId), current);
        systemSettingService.set(selectedKey(slotId), target);
        log.info("能力槽 {} 切换实现：{} -> {}", slotId, current, target);
        fireChanged(slotId);
    }

    /** 回到上一次选择（再点一次即在两者之间来回切——这正是「回滚」该有的手感）。 */
    public void rollback(String slotId) {
        if (!SLOTS.containsKey(slotId)) {
            throw new IllegalArgumentException("未知能力槽: " + slotId);
        }
        String previous = previousRef(slotId);
        if (previous == null || previous.isBlank()) {
            throw new IllegalArgumentException("没有可回滚的上一次选择");
        }
        String current = effectiveRef(slotId);
        systemSettingService.set(previousKey(slotId), current);
        systemSettingService.set(selectedKey(slotId), previous);
        log.info("能力槽 {} 回滚实现：{} -> {}", slotId, current, previous);
        fireChanged(slotId);
    }

    // ==================== 开发者模式 ====================

    /**
     * 开发者模式：允许把从 GitHub 拉来的、未签名的 process 型实现装到本机。
     * 默认开——维护者裁决（dev-board#497）：这类能力包目前只有开发者自己会装，
     * 默认开更贴合首期使用场景；不需要时可在设置页关闭。
     */
    public boolean devMode() {
        return "true".equals(systemSettingService.get(DEV_MODE_KEY, "true"));
    }

    public void setDevMode(boolean on) {
        systemSettingService.set(DEV_MODE_KEY, String.valueOf(on));
        log.info("能力包开发者模式：{}", on ? "开" : "关");
    }

    static String selectedKey(String slotId) {
        return "capability." + slotId + ".selected";
    }

    static String previousKey(String slotId) {
        return "capability." + slotId + ".previous";
    }
}
