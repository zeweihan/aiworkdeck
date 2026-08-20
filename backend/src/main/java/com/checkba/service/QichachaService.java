package com.checkba.service;

import cn.hutool.core.date.DateUtil;
import cn.hutool.core.util.RandomUtil;
import cn.hutool.crypto.SecureUtil;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.checkba.model.dto.CompanyBasicInfoDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 企查查企业工商详情：平台代采（网关）与自备 Key 两档。
 *
 * <p>分档在这一层，两档共用同一个 {@link #mapToDTO} ——上游响应形状一致，
 * 只换了「谁的 Key、谁出钱」。<b>平台档失败绝不静默回落 byok</b>：
 * 回落会去花用户自己的企查查订阅额度（licensing-billing 地雷 8 / 27）。
 */
@Service
public class QichachaService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(QichachaService.class);

    private static final int GATEWAY_TIMEOUT_SECONDS = 30;

    @Autowired
    private SystemSettingService systemSettingService;

    @Autowired
    private com.checkba.service.platform.ExternalProviderResolver externalProviderResolver;

    @Autowired
    private com.checkba.service.platform.PlatformGatewayClient platformGatewayClient;

    @Value("${external.qichacha.key:}")
    private String defaultAppKey;

    @Value("${external.qichacha.secret:}")
    private String defaultSecretKey;

    @Value("${external.qichacha.base-url:https://api.qichacha.com}")
    private String defaultBaseUrl;

    /**
     * 调用企查查企业工商详情接口
     */
    public CompanyBasicInfoDTO searchCompany(String searchKey, String role) {
        return mapToDTO(fetchEciInfoResult(searchKey), role);
    }

    /**
     * 企业工商详情的原始 {@code Result} 对象（未映射成 DTO）。
     *
     * <p>给 AI 侧的一等工具用：DTO 是按界面字段裁过的，模型要的是全量事实。
     */
    public String queryEciInfoJson(String searchKey) {
        return fetchEciInfoResult(searchKey).toString();
    }

    /** 取一次工商详情的 {@code Result}。两档在这里分，上面两个方法都不关心谁出的钱。 */
    private JSONObject fetchEciInfoResult(String searchKey) {
        if (externalProviderResolver.resolve(
                com.checkba.service.platform.ExternalServiceProvider.QICHACHA)
                == com.checkba.service.platform.ExternalServiceProvider.PLATFORM) {
            return fetchEciInfoViaPlatform(searchKey);
        }
        // 如果是纯 6 位数字，视为股票代码，暂不直接传给企查查，优先按名称处理。
        // 这里保留原实现，但在上层先做一层股票代码 → 公司名称的解析。
        // 0. 获取配置（优先 DB，兜底 Env）
        String appKey = systemSettingService.get("external.qichacha.key", defaultAppKey);
        String secretKey = systemSettingService.get("external.qichacha.secret", defaultSecretKey);
        String baseUrl = systemSettingService.get("external.qichacha.baseUrl", defaultBaseUrl);

        // 未配置时给出与 search_web 同款的可读提示（dev-board#69）：此前空 key 会
        // 直接打真实接口，失败后被包成泛泛的「外部数据服务暂不可用」，用户和模型
        // 都猜不到是没配凭证。
        if (appKey == null || appKey.isBlank() || secretKey == null || secretKey.isBlank()) {
            throw new IllegalStateException(
                    "企业工商信息查询未配置：缺少企查查凭证（管理页「外部服务」或环境变量 QICHACHA_KEY/QICHACHA_SECRET）。请基于已有信息继续完成任务。");
        }

        // 1. 准备请求参数
        String timeSpan = String.valueOf(System.currentTimeMillis() / 1000);
        // Token = Md5(key + Timespan + SecretKey)
        String token = SecureUtil.md5(appKey + timeSpan + secretKey).toUpperCase();

        String url = baseUrl + "/ECIInfoVerify/GetInfo";

        try {
            log.info("Requesting Qichacha API: {}, searchKey: {}", url, searchKey);
            
            // 2. 发起请求
            HttpResponse response = HttpRequest.get(url)
                    .form("key", appKey)
                    .form("searchKey", searchKey)
                    .header("Token", token)
                    .header("Timespan", timeSpan)
                    .timeout(30000)
                    .execute();

            String body = response.body();
            log.info("Qichacha response: {}", body);

            JSONObject json = JSONUtil.parseObj(body);
            
            // 检查状态码 (根据企查查文档，通常 Status 为 "200" 表示成功)
            // 注意：具体字段需根据实际返回调整，这里假设 Standard Response Format
            if (!"200".equals(json.getStr("Status"))) {
                log.error("Qichacha API error: {}", json.getStr("Message"));
                throw new RuntimeException("查询失败: " + json.getStr("Message"));
            }

            JSONObject result = json.getJSONObject("Result");
            if (result == null) {
                throw new RuntimeException("未查询到相关企业信息");
            }
            return result;

        } catch (Exception e) {
            log.error("调用企查查接口异常", e);
            throw new RuntimeException("外部数据服务暂不可用: " + e.getMessage());
        }
    }

    /**
     * 平台代采档：官网持凭证调企查查，按次扣 Credits。
     *
     * <p>网关只在上游 {@code Status=200} 时才算成功（查无此企业、限额都不扣费），
     * 所以这里拿到的一定是有 Result 的响应；其余情形以 {@code GatewayException}
     * 抛出，由 {@code GlobalExceptionHandler} 按 kind 落成可读的业务错误——
     * <b>刻意不裹进上面那个 catch(Exception)</b>：裹进去 kind 就丢了，
     * 「未开放」「余额不足」会一起变成一句「外部数据服务暂不可用」。
     */
    private JSONObject fetchEciInfoViaPlatform(String searchKey) {
        com.checkba.service.platform.PlatformGatewayClient.Result result =
                platformGatewayClient.call("qichacha", "eci_info",
                        Map.of("searchKey", searchKey), GATEWAY_TIMEOUT_SECONDS);
        // 网关原样透传企查查的 {Status, Message, Result}，与自备 Key 档同一形状
        JSONObject data = JSONUtil.parseObj(result.data().toString()).getJSONObject("Result");
        if (data == null) {
            throw new RuntimeException("未查询到相关企业信息");
        }
        return data;
    }

    private CompanyBasicInfoDTO mapToDTO(JSONObject data, String role) {
        CompanyBasicInfoDTO dto = new CompanyBasicInfoDTO();

        dto.setRole(role);

        // 通用字段
        dto.setName(data.getStr("Name"));
        dto.setFullName(data.getStr("Name"));
        dto.setRegisteredAddress(data.getStr("Address")); // 注册地址
        dto.setRegisteredCapital(data.getStr("RegistCapi")); // 注册资本

        // 根据角色提取特定字段
        // 注意：上市公司字段（股票代码、收盘价等）通常在普通工商接口里不全，可能需要额外接口。
        // 这里先尽量从 GetInfo 结果里拿，拿不到的留空或模拟。
        
        if ("LISTED".equals(role)) {
            // 上市公司特有逻辑
            // 尝试获取股票代码 (企查查 GetInfo 可能不直接返回最新股价，这里作为示例)
            dto.setStockCode(findStockCode(data)); 
            dto.setShortName(data.getStr("Name")); // 暂用全称代替简称
            // 这些字段 GetInfo 接口可能没有，需要用 "GetStockInfo" 等接口，这里暂时给假数据或空
            dto.setBoard("主板 (需对接证券接口)");
            dto.setTotalShares("暂无数据");
            dto.setLatestClosePrice("暂无数据");

            // 股东 (Partners) -> 前十大股东
            dto.setTop10Shareholders(mapShareholders(data.getJSONArray("Partners"), 10));
            
            // 董监高 (Employees)
            dto.setExecutives(mapExecutives(data.getJSONArray("Employees")));
        } else {
            // 标的公司逻辑
            // 股权结构说明
            dto.setEquityStructureRemark("请参考详细股东信息");
            
            // 股东信息
            dto.setShareholders(mapShareholders(data.getJSONArray("Partners"), 100));
        }

        return dto;
    }

    private String findStockCode(JSONObject data) {
        // 尝试查找上市公司代码逻辑，部分接口直接有 StockNumber 等字段
        // 这里简单检查是否有相关字段
        return data.getStr("No"); // 临时用注册号/No占位
    }

    private List<Map<String, String>> mapShareholders(JSONArray partners, int limit) {
        List<Map<String, String>> list = new ArrayList<>();
        if (partners == null) return list;

        for (int i = 0; i < Math.min(partners.size(), limit); i++) {
            JSONObject p = partners.getJSONObject(i);
            Map<String, String> map = new HashMap<>();
            map.put("name", p.getStr("StockName")); // 股东名称
            map.put("shareholdingRatio", p.getStr("StockPercent")); // 比例
            
            // 出资额 / 持股数
            String cap = p.getStr("ShouldCapi"); // 认缴出资额
            if (cap == null) cap = p.getStr("SubscribedCapital");
            map.put("contribution", cap);
            map.put("shares", "-"); // 接口未直接返回持股数时用 -

            list.add(map);
        }
        return list;
    }

    private List<Map<String, String>> mapExecutives(JSONArray employees) {
        List<Map<String, String>> list = new ArrayList<>();
        if (employees == null) return list;

        for (int i = 0; i < employees.size(); i++) {
            JSONObject e = employees.getJSONObject(i);
            Map<String, String> map = new HashMap<>();
            map.put("name", e.getStr("Name"));
            map.put("position", e.getStr("Job"));
            map.put("term", "-"); // 接口通常不含任期
            list.add(map);
        }
        return list;
    }
}

