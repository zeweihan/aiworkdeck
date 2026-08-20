package com.checkba.service.ai.tools;

import com.checkba.service.QichachaService;
import com.checkba.service.TushareService;
import com.checkba.service.platform.GatewayException;
import dev.langchain4j.agent.tool.Tool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;

/**
 * 企业与金融数据的 Java 侧一等工具（企查查 / Tushare）。
 *
 * <h3>为什么必须有这两个工具</h3>
 * 在此之前，模型查这两家的唯一方式是写 Python 脚本——{@code PythonTools} 把
 * {@code TUSHARE_TOKEN} / {@code QICHACHA_KEY} / {@code QICHACHA_SECRET} 注入子进程，
 * 脚本从用户本机直连上游。<b>平台代采档下没有可注入的凭证</b>（凭证在官网，
 * 下发给每台机器等于把公司账号发给所有人），脚本会拿到空 token，
 * 失败表现成「查不到数据」而不是「未配置」——正好打在「零配置即可用」这个目标上。
 *
 * <p>收进 Java 侧还有第二个好处：脚本那条路是唯一一条 AI 能循环打出几百次上游调用
 * 的口子，进了 {@code PlatformGatewayClient} 才受任务级花费上限约束。
 *
 * <h3>两档都能用</h3>
 * 这两个工具调的是 {@code QichachaService} / {@code TushareService}，
 * <b>分档在那两个 service 内部</b>：平台档走网关按次扣 Credits，自备 Key 档
 * 走用户自己的凭证。所以这两个工具不是「平台档专用」，换档不会让它们失效。
 *
 * <p>网关失败<b>不抛异常打断整轮对话</b>：返回一段说明文本让模型基于已有信息继续，
 * 与 {@code WebTools.search_web} 同一口径（licensing-billing 地雷 27）。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class EnterpriseDataTools implements AgentToolComponent {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(EnterpriseDataTools.class);

    private final QichachaService qichachaService;
    private final TushareService tushareService;

    /** 发布视频演示桩目录，见 ai.tools.enterprise-demo-fixtures。留空 = 关闭，走原逻辑。 */
    @Value("${ai.tools.enterprise-demo-fixtures:}")
    private String enterpriseDemoFixturesDir;

    @ToolMeta(displayName = "查询企业工商信息", category = "data")
    @Tool("Look up a Chinese company's business registration record (legal name, registered capital, address, shareholders, executives) by company name or unified social credit code. Returns the raw record as JSON.")
    public String qichacha_query(String companyName) {
        log.info("Tool: qichacha_query called for '{}'", companyName);
        if (!StringUtils.hasText(companyName)) {
            return "Error: companyName is required.";
        }
        String demoFixture = readEnterpriseDemoFixture(companyName);
        if (demoFixture != null) {
            log.info("Tool: qichacha_query 命中演示 fixture '{}'", companyName);
            return demoFixture;
        }
        try {
            return qichachaService.queryEciInfoJson(companyName);
        } catch (GatewayException e) {
            return unavailable("企业工商信息查询", "企业数据", e);
        } catch (Exception e) {
            log.warn("企业工商信息查询失败: {}", e.toString());
            return "企业工商信息查询失败：" + e.getMessage()
                    + " 本次已跳过该查询，请基于已有信息继续完成任务。";
        }
    }

    @ToolMeta(displayName = "查询金融数据", category = "data")
    @Tool("Query Tushare financial data for Chinese listed companies. apiName is the Tushare interface name "
            + "(e.g. stock_basic, stock_company, top10_holders, stk_managers, daily, income, balancesheet). "
            + "paramsJson is a JSON object of that interface's parameters (e.g. {\"ts_code\":\"000001.SZ\"}). "
            + "fields is a comma-separated list of columns to return. Returns the raw Tushare response as JSON.")
    public String tushare_query(String apiName, String paramsJson, String fields) {
        log.info("Tool: tushare_query called api='{}' params='{}'", apiName, paramsJson);
        if (!StringUtils.hasText(apiName)) {
            return "Error: apiName is required.";
        }
        Map<String, Object> params = new HashMap<>();
        if (StringUtils.hasText(paramsJson)) {
            try {
                cn.hutool.json.JSONUtil.parseObj(paramsJson).forEach(params::put);
            } catch (Exception e) {
                // 模型偶尔会把参数写成非 JSON，明确告诉它错在哪比静默当空参数好
                return "Error: paramsJson 必须是 JSON 对象，例如 {\"ts_code\":\"000001.SZ\"}。收到的是：" + paramsJson;
            }
        }
        try {
            String json = tushareService.queryJson(apiName, params, fields == null ? "" : fields);
            return StringUtils.hasText(json) ? json : "金融数据接口未返回结果（可能是参数不匹配或该接口无权限）。";
        } catch (GatewayException e) {
            return unavailable("金融数据查询", "金融数据", e);
        } catch (Exception e) {
            log.warn("金融数据查询失败: {}", e.toString());
            return "金融数据查询失败：" + e.getMessage()
                    + " 本次已跳过该查询，请基于已有信息继续完成任务。";
        }
    }

    /**
     * 发布视频演示桩：目录未配置时永远返回 null（不影响任何现有行为）；
     * 配置了但命中不了这个公司名时也返回 null，交回原逻辑走真实网关。
     */
    private String readEnterpriseDemoFixture(String companyName) {
        if (!StringUtils.hasText(enterpriseDemoFixturesDir)) {
            return null;
        }
        File file = new File(enterpriseDemoFixturesDir, companyName.trim() + ".json");
        if (!file.isFile()) {
            return null;
        }
        try {
            return Files.readString(file.toPath());
        } catch (IOException e) {
            log.warn("演示 fixture 读取失败，回落真实查询: {}", e.toString());
            return null;
        }
    }

    /** 网关失败的统一说明文本。带上「改用自己的 Key」这条真出路，但不替用户做决定。 */
    private String unavailable(String action, String panelName, GatewayException e) {
        log.warn("{}走平台通道失败 kind={}: {}", action, e.getKind(), e.getMessage());
        String hint = e.suggestsByok()
                ? "如需继续使用，可在「系统管理 → 平台服务」把" + panelName + "改为自备 Key。"
                : "";
        return action + "本次不可用：" + e.getMessage() + hint
                + " 本次已跳过该查询，请基于已有信息继续完成任务。";
    }
}
