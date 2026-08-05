package com.checkba.service.ai.skill;

import cn.hutool.core.io.FileUtil;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.checkba.service.market.MarketPurchaseGate;
import com.checkba.service.market.RegistryReply;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 在线 Skill 广场客户端（官网公共注册表，配置 ai.skills.registry-url）。
 *
 * 注册表契约：
 * - GET {registryUrl}                返回 skill 元数据 JSON 数组；
 * - GET {registryUrl}/{id}/bundle    返回 { id, version, files: { "skill.yml": ..., "prompt.md": ... } }。
 *
 * 安装 = 把 bundle 中的两个已知文件写入 {ai.skills.dir}/{id}/ 后 rescan（重装即更新）；
 * 注册表不可达只影响广场功能本身，不影响本地 skill / 插件体系。
 *
 * <h3>付费项（PR-D）</h3>
 * 列表多出 {@code priceCents}（分，0=免费）/{@code pricingModel}，安装时 bundle 端点对付费项要求
 * {@code Authorization: Bearer awdk_} 且已购，否则 402。判定与文案统一在 {@link MarketPurchaseGate}。
 * <b>免费项（含官网旧格式缺 priceCents 字段）不查账户、不带鉴权头</b>，bundle 请求逐字节与改造前一致。
 * 唯一的增量是 {@link #install} 会先查一次注册表列表拿价格（价格必须服务端自证，见 {@link #findRegistryEntry}），
 * 免费项也走这一步——不能靠「是不是免费」来决定要不要查，那等于让客户端说了算。
 */
@Service
@Slf4j
public class SkillMarketService {

    /** skill id 白名单（kebab-case），同时兼作路径穿越防护 */
    private static final Pattern SKILL_ID = Pattern.compile("^[a-z0-9][a-z0-9-]{1,49}$");

    /** 允许从 bundle 落盘的文件名——只认这两个已知文件，其余条目一律忽略 */
    private static final List<String> BUNDLE_FILES = List.of("skill.yml", "prompt.md");

    private final SkillProperties properties;
    private final SkillRegistry skillRegistry;
    private final MarketPurchaseGate purchaseGate;

    public SkillMarketService(SkillProperties properties, SkillRegistry skillRegistry,
                              MarketPurchaseGate purchaseGate) {
        this.properties = properties;
        this.skillRegistry = skillRegistry;
        this.purchaseGate = purchaseGate;
    }

    /** 广场 skill 视图：注册表元数据 + 本地是否已安装 */
    @lombok.Data
    public static class MarketSkillView {
        private String id;
        private String name;
        private String description;
        private String icon;
        private String version;
        private String author;
        private String authorDisplayName;
        private List<String> triggers = new ArrayList<>();
        private List<String> allowedTools = new ArrayList<>();
        /** 官网 SKILL_CATEGORIES 分类 id；旧注册表可能缺失，前端按"其他"兜底 */
        private String category;
        private Integer downloads;
        private String updatedAt;
        private String homepage;
        /**
         * 售价（分），0 = 免费。**官网旧格式没有这个字段 → 反序列化得 null → 归一为 0（免费）**，
         * 绝不能因为字段缺失把免费项锁住。
         */
        private Integer priceCents;
        /** 计价方式，当前只有 "once"（一次性买断）；缺失同样按 once 处理 */
        private String pricingModel;
        /** 本地 SkillRegistry 中已存在同 id skill */
        private boolean installed;
        /** 付费项且账户权益里有 skill:{id}（免费项恒 false，前端按 priceCents 判免费） */
        private boolean purchased;
    }

    /**
     * 拉取在线广场列表并标注安装 / 已购状态。
     * @throws IllegalStateException 注册表不可达或返回内容无法解析（调用方转 {code:1}，不阻断本地功能）
     */
    public List<MarketSkillView> listMarket() {
        String body = httpGet(properties.getRegistryUrl());
        List<MarketSkillView> list;
        try {
            list = JSONUtil.toList(JSONUtil.parseArray(body), MarketSkillView.class);
        } catch (Exception e) {
            throw new IllegalStateException("注册表返回内容无法解析: " + e.getMessage());
        }
        for (MarketSkillView view : list) {
            view.setPriceCents(MarketPurchaseGate.normalizePrice(view.getPriceCents()));
            if (view.getPricingModel() == null || view.getPricingModel().isBlank()) {
                view.setPricingModel("once");
            }
            view.setInstalled(view.getId() != null && skillRegistry.getSkill(view.getId()).isPresent());
            view.setPurchased(view.getPriceCents() > 0 && view.getId() != null
                    && purchaseGate.purchased(MarketPurchaseGate.skillFeature(view.getId())));
        }
        return list;
    }

    /** 广场列表响应带回的账户连接状态：未连接时前端把付费项显示为「需连接账户」。 */
    public boolean accountConnected() {
        return purchaseGate.accountConnected();
    }

    /**
     * 安装（或重装 = 更新）一个在线 skill：下载 bundle 写入 {ai.skills.dir}/{id}/ 并 rescan。
     * @return 安装的 skill id
     * @throws IllegalArgumentException id 非法
     * @throws IllegalStateException 注册表不可达 / 付费项未连接账户或未购买 / bundle 缺必需文件 / 写盘失败
     */
    public synchronized String install(String id) {
        requireValidId(id);
        MarketSkillView entry = findRegistryEntry(id);
        int priceCents = entry == null ? 0 : MarketPurchaseGate.normalizePrice(entry.getPriceCents());
        String itemName = entry == null || entry.getName() == null ? id : entry.getName();
        // 免费项 bearer 为 null：不带鉴权头，与改造前逐字节一致。
        // entry == null 是「价格没查到」而不是「免费」：本机有 Key 就带上，见 bearerForUnknownPrice
        String bearer = entry == null
                ? purchaseGate.bearerForUnknownPrice()
                : purchaseGate.bearerFor(priceCents, itemName);

        RegistryReply reply = httpGet(properties.getRegistryUrl() + "/" + id + "/bundle", bearer);
        if (reply.status() == 402) {
            throw purchaseGate.paymentRequired(reply.body(), itemName, priceCents);
        }
        if (reply.status() != 200) {
            throw new IllegalStateException("注册表请求失败 (HTTP " + reply.status() + ")");
        }
        String body = reply.body();
        JSONObject files;
        try {
            files = JSONUtil.parseObj(body).getJSONObject("files");
        } catch (Exception e) {
            throw new IllegalStateException("bundle 内容无法解析: " + e.getMessage());
        }
        for (String name : BUNDLE_FILES) {
            if (files == null || !(files.get(name) instanceof CharSequence)) {
                throw new IllegalStateException("bundle 缺少必需文件: " + name);
            }
        }
        try {
            File skillDir = new File(properties.getDir(), id);
            Files.createDirectories(skillDir.toPath());
            for (String name : BUNDLE_FILES) {
                Files.writeString(new File(skillDir, name).toPath(), files.getStr(name), StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            throw new IllegalStateException("写入 skill 文件失败: " + e.getMessage());
        }
        skillRegistry.rescan();
        log.info("Installed market skill '{}'", id);
        return id;
    }

    /**
     * 卸载在线安装的 skill：删除 {ai.skills.dir}/{id}/ 目录并 rescan。
     * @throws IllegalArgumentException id 非法或未安装
     * @throws IllegalStateException skill 来自插件（应通过插件管理卸载）或删除失败
     */
    public synchronized void uninstall(String id) {
        requireValidId(id);
        SkillDefinition existing = skillRegistry.getSkill(id).orElse(null);
        if (existing != null && existing.getSourcePluginId() != null) {
            throw new IllegalStateException("该 Skill 来自插件 " + existing.getSourcePluginId() + "，请通过插件管理卸载");
        }
        try {
            File skillsDir = new File(properties.getDir());
            File skillDir = new File(skillsDir, id);
            // 只允许删除 skills 目录正下方的子目录（canonical 校验防符号链接逃逸）
            if (!skillDir.getCanonicalPath().startsWith(skillsDir.getCanonicalPath() + File.separator)) {
                throw new IllegalArgumentException("非法 skill 目录: " + id);
            }
            if (!skillDir.isDirectory()) {
                throw new IllegalArgumentException("Skill 未安装: " + id);
            }
            FileUtil.del(skillDir);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("删除 skill 目录失败: " + e.getMessage());
        }
        skillRegistry.rescan();
        log.info("Uninstalled market skill '{}'", id);
    }

    private static void requireValidId(String id) {
        if (id == null || !SKILL_ID.matcher(id).matches()) {
            throw new IllegalArgumentException("非法 skill id: " + id);
        }
    }

    /**
     * 安装前查一次注册表元数据拿价格。
     *
     * 价格必须由服务端自己确认——前端传来的 priceCents 只是显示值，拿它决定「要不要带 Bearer」
     * 就等于让客户端决定付费闸门什么时候生效。
     *
     * 列表不可达 / 条目已下架时返回 null，按免费继续尝试：真付费项官网仍会 402 兜底，
     * 而在这里直接失败会把一次网络抖动变成免费项也装不上。
     */
    private MarketSkillView findRegistryEntry(String id) {
        try {
            return listMarket().stream()
                    .filter(v -> id.equals(v.getId()))
                    .findFirst().orElse(null);
        } catch (Exception e) {
            log.debug("安装前拉取注册表元数据失败，按免费项继续: {}", e.getMessage());
            return null;
        }
    }

    /** 无鉴权 HTTP GET；非 200 或网络异常抛 IllegalStateException */
    protected String httpGet(String url) {
        RegistryReply reply = httpGet(url, null);
        if (reply.status() != 200) {
            throw new IllegalStateException("注册表请求失败 (HTTP " + reply.status() + ")");
        }
        return reply.body();
    }

    /**
     * HTTP GET seam（单测覆写此方法打桩，无鉴权的 {@link #httpGet(String)} 也走这里）。
     *
     * @param bearer 非空时带 {@code Authorization: Bearer}；付费项 bundle 端点需要
     * @throws IllegalStateException 网络不可达（状态码交由调用方判断，402 不在这里抛）
     */
    protected RegistryReply httpGet(String url, String bearer) {
        HttpResponse resp;
        try {
            HttpRequest req = HttpRequest.get(url)
                    .setConnectionTimeout(5000)
                    .setReadTimeout(10000);
            if (bearer != null && !bearer.isBlank()) {
                req.header("Authorization", "Bearer " + bearer);
            }
            resp = req.execute();
        } catch (Exception e) {
            throw new IllegalStateException("注册表不可达: " + e.getMessage());
        }
        return new RegistryReply(resp.getStatus(), resp.bodyBytes());
    }
}
