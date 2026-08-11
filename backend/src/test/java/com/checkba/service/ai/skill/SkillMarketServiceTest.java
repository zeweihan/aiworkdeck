package com.checkba.service.ai.skill;

import com.checkba.service.account.AccountService;
import com.checkba.service.ai.PluginService;
import com.checkba.service.entitlement.EntitlementService;
import com.checkba.service.market.MarketPurchaseGate;
import com.checkba.service.market.RegistryReply;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * SkillMarketService 单测：注册表列表解析与安装标注、id/文件名安全校验、
 * 安装写盘 + rescan 生效、卸载（含拒绝插件携带 skill）、付费项闸门（PR-D）。
 * httpGet seam 打桩，不走真实网络。
 */
class SkillMarketServiceTest {

    private static final String REGISTRY_URL = "https://registry.example/api/registry/skills";

    @TempDir
    Path tempDir;

    /**
     * @param accountKey 账户 Key；null = 未连接
     * @param ownedFeature 已购的 feature（skill:/plugin: 命名空间），null = 未购
     */
    private static MarketPurchaseGate gate(String accountKey, String ownedFeature) {
        AccountService account = mock(AccountService.class);
        when(account.currentKeyOrNull()).thenReturn(accountKey);
        when(account.isConnected()).thenReturn(accountKey != null);
        EntitlementService entitlements = mock(EntitlementService.class);
        when(entitlements.isEnabled(anyString()))
                .thenAnswer(inv -> ownedFeature != null && ownedFeature.equals(inv.getArgument(0)));
        return new MarketPurchaseGate(account, entitlements);
    }

    /** httpGet seam 打桩：url -> 响应体；未配置的 url 模拟网络失败。记录每次请求带的 bearer。 */
    private static class StubMarketService extends SkillMarketService {
        final Map<String, String> responses = new HashMap<>();
        /** url -> 状态码，缺省 200（402 用例在这里登记） */
        final Map<String, Integer> statuses = new HashMap<>();
        final List<String> requestedUrls = new ArrayList<>();
        final List<String> sentBearers = new ArrayList<>();

        StubMarketService(SkillProperties properties, SkillRegistry registry, MarketPurchaseGate gate) {
            super(properties, registry, gate);
        }

        @Override
        protected RegistryReply httpGet(String url, String bearer) {
            requestedUrls.add(url);
            sentBearers.add(String.valueOf(bearer));
            String body = responses.get(url);
            if (body == null) {
                throw new IllegalStateException("注册表不可达: stub");
            }
            return new RegistryReply(statuses.getOrDefault(url, 200), body.getBytes(StandardCharsets.UTF_8));
        }
    }

    private SkillProperties props;
    private SkillRegistry registry;

    private StubMarketService newService(Path skillsDir, PluginService pluginService) {
        return newService(skillsDir, pluginService, gate(null, null));
    }

    private StubMarketService newService(Path skillsDir, PluginService pluginService, MarketPurchaseGate gate) {
        props = new SkillProperties();
        props.setDir(skillsDir.toString());
        props.setRegistryUrl(REGISTRY_URL);
        registry = new SkillRegistry(props, null, pluginService);
        registry.init();
        return new StubMarketService(props, registry, gate);
    }

    private void writeLocalSkill(Path dir, String id) throws IOException {
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("skill.yml"), """
                id: %s
                name: 本地技能
                triggers: [本地关键词]
                """.formatted(id));
        Files.writeString(dir.resolve("prompt.md"), "本地指引");
    }

    private static String bundleJson(String id) {
        return """
                { "id": "%s", "version": "1.0.0", "files": {
                    "skill.yml": "id: %s\\nname: 在线技能\\ntriggers: [在线关键词]\\n",
                    "prompt.md": "在线指引",
                    "../../evil.txt": "escape",
                    "hack.sh": "rm -rf /"
                } }""".formatted(id, id);
    }

    @Test
    @DisplayName("listMarket：解析注册表数组并标注 installed（本地已有同 id 的为 true）")
    void listMarketMarksInstalled() throws IOException {
        Path skillsDir = tempDir.resolve("skills");
        writeLocalSkill(skillsDir.resolve("contract-review"), "contract-review");
        StubMarketService service = newService(skillsDir, new PluginService());
        service.responses.put(REGISTRY_URL, """
                [{ "id": "contract-review", "name": "合同审查", "description": "d", "icon": "🧩",
                   "version": "1.0.0", "author": "u", "authorDisplayName": "显示名",
                   "triggers": ["合同审查"], "allowedTools": ["read_document"],
                   "downloads": 12, "updatedAt": "2026-07-14T00:00:00.000Z",
                   "homepage": "https://example.com" },
                 { "id": "other-skill", "name": "其他", "triggers": ["其他"] }]""");

        List<SkillMarketService.MarketSkillView> list = service.listMarket();

        assertEquals(2, list.size());
        SkillMarketService.MarketSkillView first = list.get(0);
        assertEquals("contract-review", first.getId());
        assertEquals("合同审查", first.getName());
        assertEquals("显示名", first.getAuthorDisplayName());
        assertEquals(List.of("合同审查"), first.getTriggers());
        assertEquals(12, first.getDownloads());
        assertTrue(first.isInstalled());
        assertFalse(list.get(1).isInstalled());
    }

    @Test
    @DisplayName("listMarket：注册表不可达/返回非数组时抛 IllegalStateException")
    void listMarketFailsCleanly() {
        StubMarketService service = newService(tempDir.resolve("skills"), new PluginService());
        assertThrows(IllegalStateException.class, service::listMarket, "网络失败");

        service.responses.put(REGISTRY_URL, "<html>error page</html>");
        assertThrows(IllegalStateException.class, service::listMarket, "解析失败");
    }

    @Test
    @DisplayName("install：只落盘 skill.yml + prompt.md（bundle 中其余条目忽略），rescan 后可用")
    void installWritesKnownFilesAndRescans() throws IOException {
        Path skillsDir = tempDir.resolve("skills");
        StubMarketService service = newService(skillsDir, new PluginService());
        service.responses.put(REGISTRY_URL + "/contract-review/bundle", bundleJson("contract-review"));

        assertEquals("contract-review", service.install("contract-review"));

        Path skillDir = skillsDir.resolve("contract-review");
        assertTrue(Files.isRegularFile(skillDir.resolve("skill.yml")));
        assertTrue(Files.isRegularFile(skillDir.resolve("prompt.md")));
        assertFalse(Files.exists(skillDir.resolve("hack.sh")), "白名单外文件不落盘");
        assertFalse(Files.exists(tempDir.resolve("evil.txt")), "路径穿越条目不落盘");
        try (var stream = Files.list(skillDir)) {
            assertEquals(2, stream.count(), "只写两个已知文件");
        }
        // rescan 已被 install 触发，skill 立即可用
        SkillDefinition skill = registry.getSkill("contract-review").orElseThrow();
        assertEquals("在线技能", skill.getName());

        // 重装即更新：同 id 再次安装直接覆盖，不报错
        assertEquals("contract-review", service.install("contract-review"));
    }

    @Test
    @DisplayName("install：非法 id 直接拒绝（路径穿越守卫），不发起任何请求")
    void installRejectsInvalidIds() {
        StubMarketService service = newService(tempDir.resolve("skills"), new PluginService());
        for (String bad : new String[]{null, "", "../evil", "a/b", "UPPER-Case", "a", "-lead", "a".repeat(51)}) {
            assertThrows(IllegalArgumentException.class, () -> service.install(bad), String.valueOf(bad));
        }
        assertTrue(service.requestedUrls.isEmpty(), "校验失败不应发起网络请求");
    }

    @Test
    @DisplayName("install：bundle 缺 skill.yml 或 prompt.md 时拒绝，不写盘")
    void installRequiresBothFiles() {
        Path skillsDir = tempDir.resolve("skills");
        StubMarketService service = newService(skillsDir, new PluginService());
        service.responses.put(REGISTRY_URL + "/half-skill/bundle",
                """
                { "id": "half-skill", "version": "1.0.0", "files": { "skill.yml": "id: half-skill" } }""");

        assertThrows(IllegalStateException.class, () -> service.install("half-skill"));
        assertFalse(Files.exists(skillsDir.resolve("half-skill")), "校验失败不写盘");
    }

    @Test
    @DisplayName("uninstall：删除 skills/ 下的目录并 rescan；未安装/非法 id 拒绝")
    void uninstallRemovesInstalledSkill() throws IOException {
        Path skillsDir = tempDir.resolve("skills");
        writeLocalSkill(skillsDir.resolve("contract-review"), "contract-review");
        StubMarketService service = newService(skillsDir, new PluginService());
        assertTrue(registry.getSkill("contract-review").isPresent());

        service.uninstall("contract-review");

        assertFalse(Files.exists(skillsDir.resolve("contract-review")));
        assertTrue(registry.getSkill("contract-review").isEmpty(), "rescan 后注册表同步移除");

        assertThrows(IllegalArgumentException.class, () -> service.uninstall("contract-review"), "重复卸载=未安装");
        assertThrows(IllegalArgumentException.class, () -> service.uninstall("../evil"));
    }

    @Test
    @DisplayName("uninstall：enabled_by_default:false 的内置 skill（随包分发）只停用不删文件")
    void uninstallOnlyDisablesBuiltinOptionalSkill() throws IOException {
        Path skillsDir = tempDir.resolve("skills");
        Path skillDir = skillsDir.resolve("litigation-visual");
        Files.createDirectories(skillDir);
        Files.writeString(skillDir.resolve("skill.yml"), """
                id: litigation-visual
                name: 诉讼可视化
                triggers: [案件时间轴]
                enabled_by_default: false
                """);
        Files.writeString(skillDir.resolve("prompt.md"), "指引");
        StubMarketService service = newService(skillsDir, new PluginService());
        assertFalse(registry.isEnabled("litigation-visual"), "首次扫描默认禁用");
        registry.setEnabled("litigation-visual", true);
        assertTrue(registry.isEnabled("litigation-visual"));

        service.uninstall("litigation-visual");

        assertFalse(registry.isEnabled("litigation-visual"), "卸载=停用");
        assertTrue(Files.isDirectory(skillDir), "随包分发的文件必须保留，删了就再也装不回来（不在线上广场）");
        assertTrue(registry.getSkill("litigation-visual").isPresent(), "仍在注册表里，只是被禁用");
    }

    @Test
    @DisplayName("uninstall：插件携带的 skill（sourcePluginId != null）拒绝卸载")
    void uninstallRefusesPluginCarriedSkill() throws IOException {
        Path pluginDir = tempDir.resolve("plugins").resolve("my-plugin");
        Files.createDirectories(pluginDir);
        Files.writeString(pluginDir.resolve("manifest.json"), """
                { "id": "my-plugin", "name": "带技能的插件", "version": "1.0.0", "skills": ["my-skill"] }""");
        writeLocalSkill(pluginDir.resolve("my-skill"), "plugin-skill");
        PluginService pluginService = new PluginService(null, tempDir.resolve("plugins").toString());
        pluginService.init();

        StubMarketService service = newService(tempDir.resolve("skills"), pluginService);
        assertTrue(registry.getSkill("plugin-skill").isPresent());

        assertThrows(IllegalStateException.class, () -> service.uninstall("plugin-skill"));
        assertTrue(registry.getSkill("plugin-skill").isPresent(), "插件 skill 原样保留");
    }

    // ==================== 付费项（PR-D） ====================

    /** 注册表列表：一个 ¥19.90 的付费 Skill */
    private static final String PAID_REGISTRY = """
            [{ "id": "due-diligence", "name": "尽调助手", "triggers": ["尽调"],
               "priceCents": 1990, "pricingModel": "once" }]""";

    /**
     * 付费闸门的文案不能撞上前端的掉线启发式：services/api.js 对 {@code code:1} 的消息做子串匹配，
     * 见到「登录」「未授权」「请先」就清本地会话，浏览器端还会跳登录页。这里是业务错误，不是掉线。
     */
    private static void assertNotMistakenForLogout(String message) {
        for (String needle : List.of("登录", "未授权", "请先")) {
            assertFalse(message.contains(needle),
                    "文案含「" + needle + "」会被前端当成掉线清会话: " + message);
        }
    }

    @Test
    @DisplayName("listMarket：官网旧格式没有 priceCents 时按免费处理（向后兼容，不能把免费项锁住）")
    void listTreatsMissingPriceAsFree() {
        StubMarketService service = newService(tempDir.resolve("skills"), new PluginService());
        service.responses.put(REGISTRY_URL, """
                [{ "id": "legacy-skill", "name": "老技能", "triggers": ["老"] }]""");

        SkillMarketService.MarketSkillView view = service.listMarket().get(0);
        assertEquals(0, view.getPriceCents(), "缺字段 = 免费");
        assertEquals("once", view.getPricingModel(), "缺字段按一次性买断兜底");
        assertFalse(view.isPurchased());
    }

    @Test
    @DisplayName("listMarket：付费项已购时标 purchased（feature 命名空间 skill:<id>，不与本地功能键混淆）")
    void listMarksPurchased() {
        StubMarketService service = newService(tempDir.resolve("skills"), new PluginService(),
                gate("awdk_live", "skill:due-diligence"));
        service.responses.put(REGISTRY_URL, PAID_REGISTRY);

        SkillMarketService.MarketSkillView view = service.listMarket().get(0);
        assertEquals(1990, view.getPriceCents());
        assertEquals("once", view.getPricingModel());
        assertTrue(view.isPurchased());
        assertTrue(service.accountConnected());

        // 同一条目在未购账户下不得标已购
        StubMarketService unpaid = newService(tempDir.resolve("skills2"), new PluginService(),
                gate("awdk_live", "clipboard.unlimited"));
        unpaid.responses.put(REGISTRY_URL, PAID_REGISTRY);
        assertFalse(unpaid.listMarket().get(0).isPurchased(), "本地 SKU 的权益不能蹭成广场条目的已购");
    }

    @Test
    @DisplayName("install：付费项未连接账户时本地拦下并给出连接引导，不发 bundle 请求")
    void paidInstallWithoutAccountIsBlockedLocally() {
        StubMarketService service = newService(tempDir.resolve("skills"), new PluginService());
        service.responses.put(REGISTRY_URL, PAID_REGISTRY);

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> service.install("due-diligence"));
        assertTrue(e.getMessage().contains("连接 AI Workdeck 账户"), e.getMessage());
        assertTrue(e.getMessage().contains("¥19.90"), "价格要写进引导文案: " + e.getMessage());
        assertNotMistakenForLogout(e.getMessage());
        assertEquals(List.of(REGISTRY_URL), service.requestedUrls, "只查了元数据，不该再打 bundle 端点");
    }

    @Test
    @DisplayName("install：官网 402 被翻成明确中文（含条目名与价格），不吞成通用失败")
    void paidInstallSurfaces402() {
        Path skillsDir = tempDir.resolve("skills");
        StubMarketService service = newService(skillsDir, new PluginService(), gate("awdk_live", null));
        String bundleUrl = REGISTRY_URL + "/due-diligence/bundle";
        service.responses.put(REGISTRY_URL, PAID_REGISTRY);
        service.responses.put(bundleUrl, "{\"code\":\"payment_required\",\"priceCents\":1990,\"itemName\":\"尽调助手\"}");
        service.statuses.put(bundleUrl, 402);

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> service.install("due-diligence"));
        assertTrue(e.getMessage().contains("尽调助手"), e.getMessage());
        assertTrue(e.getMessage().contains("需购买后安装"), e.getMessage());
        assertTrue(e.getMessage().contains("¥19.90"), e.getMessage());
        assertEquals("awdk_live", service.sentBearers.get(1), "付费项 bundle 请求必须带账户 Bearer");
        assertFalse(Files.exists(skillsDir.resolve("due-diligence")), "402 不得留下半成品");
    }

    @Test
    @DisplayName("install：已购付费项带 Bearer 正常安装；免费项即使已连接账户也不带鉴权头")
    void paidPurchasedInstallsAndFreeStaysAnonymous() {
        Path skillsDir = tempDir.resolve("skills");
        StubMarketService paid = newService(skillsDir, new PluginService(),
                gate("awdk_live", "skill:due-diligence"));
        paid.responses.put(REGISTRY_URL, PAID_REGISTRY);
        paid.responses.put(REGISTRY_URL + "/due-diligence/bundle", bundleJson("due-diligence"));

        assertEquals("due-diligence", paid.install("due-diligence"));
        assertEquals("awdk_live", paid.sentBearers.get(1));

        StubMarketService free = newService(tempDir.resolve("free"), new PluginService(),
                gate("awdk_live", null));
        free.responses.put(REGISTRY_URL, """
                [{ "id": "contract-review", "name": "合同审查", "triggers": ["合同"], "priceCents": 0 }]""");
        free.responses.put(REGISTRY_URL + "/contract-review/bundle", bundleJson("contract-review"));

        assertEquals("contract-review", free.install("contract-review"));
        assertEquals(List.of("null", "null"), free.sentBearers, "免费项链路一字不变：不带 Authorization");
    }

    @Test
    @DisplayName("install：注册表列表不可达时按免费继续（网络抖动不该连免费项都装不上）")
    void installFallsBackToFreeWhenRegistryListUnavailable() {
        Path skillsDir = tempDir.resolve("skills");
        StubMarketService service = newService(skillsDir, new PluginService());
        // 只登记 bundle，列表 url 缺失 = stub 抛网络失败
        service.responses.put(REGISTRY_URL + "/contract-review/bundle", bundleJson("contract-review"));

        assertEquals("contract-review", service.install("contract-review"));
        assertEquals("null", service.sentBearers.get(1), "未连账户就没得带，按免费继续");
    }

    @Test
    @DisplayName("install：价格没查到但本机已连账户时照带 Bearer（已购用户不该被 402 反过来指控没付费）")
    void unknownPriceStillSendsBearerWhenConnected() {
        Path skillsDir = tempDir.resolve("skills");
        StubMarketService service = newService(skillsDir, new PluginService(),
                gate("awdk_live", "skill:due-diligence"));
        // 列表 url 缺失 = 价格查不到；付费与否无从判断
        service.responses.put(REGISTRY_URL + "/due-diligence/bundle", bundleJson("due-diligence"));

        assertEquals("due-diligence", service.install("due-diligence"));
        assertEquals("awdk_live", service.sentBearers.get(1),
                "价格未知时本机有 Key 就带上——免费项的 bundle 端点不看 Authorization，附上无副作用");
    }

    @Test
    @DisplayName("install：价格未知 + 未连账户吃到 402 时说「去连接账户」，不说「去购买」")
    void unknownPrice402WithoutAccountSuggestsConnecting() {
        StubMarketService service = newService(tempDir.resolve("skills"), new PluginService());
        String bundleUrl = REGISTRY_URL + "/due-diligence/bundle";
        service.responses.put(bundleUrl, "{\"code\":\"payment_required\",\"priceCents\":1990,\"itemName\":\"尽调助手\"}");
        service.statuses.put(bundleUrl, 402);

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> service.install("due-diligence"));
        assertTrue(e.getMessage().contains("连接 AI Workdeck 账户"), e.getMessage());
        assertFalse(e.getMessage().contains("需购买后安装"),
                "本机没连账户，官网无从查购买记录：402 不等于用户没买过 —— " + e.getMessage());
        assertNotMistakenForLogout(e.getMessage());
    }

    @Test
    @DisplayName("listMarket：priceCents 畸形（负数 / 超上限的截断值）按未知处理，不展示假价格")
    void listNormalizesMalformedPrice() {
        StubMarketService service = newService(tempDir.resolve("skills"), new PluginService());
        // 1215752191 = 官网写了个超 int 范围的值、反序列化时被截断的实测结果（¥12,157,521.91）
        service.responses.put(REGISTRY_URL, """
                [{ "id": "neg-price", "name": "负价", "priceCents": -100 },
                 { "id": "huge-price", "name": "溢出", "priceCents": 1215752191 }]""");

        List<SkillMarketService.MarketSkillView> list = service.listMarket();
        assertEquals(0, list.get(0).getPriceCents(), "负数按未知");
        assertEquals(0, list.get(1).getPriceCents(), "超上限只可能是畸形值，不能当真价展示");
    }
}
