package com.checkba.service;

import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HttpRequest;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.checkba.model.dto.CompanyBasicInfoDTO;
import com.checkba.model.entity.ProjectVariable;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;

import org.springframework.beans.factory.annotation.Autowired;

/**
 * Tushare 金融数据：平台代采（网关）与自备 token 两档。
 *
 * <p>上游只有一个统一入口（POST 一个 JSON，{@code api_name} 指哪个接口），
 * 所以<b>分档只需要落在 {@link #callTushare} 这一个缝上</b>——上面那几个
 * 解析函数（股东、管理层、工商信息）一行都不用改，两档产出同一个 JSON。
 *
 * <p><b>平台档失败绝不静默回落 byok</b>（licensing-billing 地雷 8 / 27）。
 */
@Service
public class TushareService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(TushareService.class);

    private static final int GATEWAY_TIMEOUT_SECONDS = 30;

    @Autowired
    private SystemSettingService systemSettingService;

    @Autowired
    private com.checkba.service.platform.ExternalProviderResolver externalProviderResolver;

    @Autowired
    private com.checkba.service.platform.PlatformGatewayClient platformGatewayClient;

    @Value("${external.tushare.base-url:http://api.tushare.pro}")
    private String defaultTushareApiUrl;

    @Value("${external.tushare.token:}")
    private String defaultTushareToken;

    /**
     * 任意 Tushare 接口的原始响应（JSON 文本）。
     *
     * <p>给 AI 侧的一等工具用：模型此前是写 Python 脚本直连 Tushare 的，
     * 那条路在平台代采档下没有可注入的 token（设计 §5.5），改由这里代它调。
     */
    public String queryJson(String apiName, Map<String, Object> params, String fields) {
        JSONObject p = new JSONObject();
        if (params != null) params.forEach(p::set);
        JSONObject response = callTushare(apiName, p, fields);
        return response == null ? "" : response.toString();
    }

    /**
     * Fetch listed company data and return a DTO for frontend display.
     */
    public CompanyBasicInfoDTO fetchCompanyInfoDTO(String companyName) {
        String tsCode = getTsCodeByName(companyName);
        if (StrUtil.isBlank(tsCode)) {
            return null;
        }

        CompanyBasicInfoDTO dto = new CompanyBasicInfoDTO();
        dto.setName(companyName);
        dto.setRole("LISTED");
        dto.setStockCode(tsCode);
        dto.setFullName(companyName);

        // Basic Info
        Map<String, String> basicInfo = fetchBasicInfoMap(tsCode);
        if (basicInfo != null) {
            dto.setRegisteredAddress(basicInfo.get("office"));
            dto.setRegisteredCapital(basicInfo.get("reg_capital"));
            // dto.setShortName(basicInfo.get("name"));
        }

        // Top 10 Holders
        dto.setTop10Shareholders(fetchTop10HoldersList(tsCode));

        // Executives
        dto.setExecutives(fetchManagersList(tsCode));

        return dto;
    }

    /**
     * Fetch listed company data and return a list of ProjectVariable objects.
     */
    public List<ProjectVariable> fetchAndCreateVariables(Long projectId, String companyName) {
        List<ProjectVariable> variables = new ArrayList<>();
        String tsCode = getTsCodeByName(companyName);

        if (StrUtil.isBlank(tsCode)) {
            log.warn("Could not find ts_code for company: {}", companyName);
            return variables;
        }

        // 1. Basic Info
        Map<String, String> basicInfo = fetchBasicInfoMap(tsCode);
        if (basicInfo != null) {
            String group = "上市公司-基本信息";
            basicInfo.forEach((k, v) -> createVar(variables, projectId, mapFieldName(k), v, group));
        }

        // 2. Top 10 Holders
        List<Map<String, String>> holders = fetchTop10HoldersList(tsCode);
        if (holders != null && !holders.isEmpty()) {
            String group = "上市公司-前十大股东";
            int index = 1;
            for (Map<String, String> holder : holders) {
                if (index > 10) break;
                createVar(variables, projectId, "第" + index + "大股东名称", holder.get("name"), group);
                createVar(variables, projectId, "第" + index + "大股东持股数", holder.get("amount"), group);
                createVar(variables, projectId, "第" + index + "大股东持股比例", holder.get("ratio"), group);
                index++;
            }
            if (!holders.isEmpty() && holders.get(0).containsKey("date")) {
                createVar(variables, projectId, "股东数据报告期", holders.get(0).get("date"), group);
            }
        }

        // 3. Management
        List<Map<String, String>> managers = fetchManagersList(tsCode);
        if (managers != null && !managers.isEmpty()) {
            String group = "上市公司-管理层";
            Set<String> directors = new LinkedHashSet<>();
            Set<String> supervisors = new LinkedHashSet<>();
            Set<String> executives = new LinkedHashSet<>();

            for (Map<String, String> mgr : managers) {
                // 上游 stk_managers 允许 name/title 缺失（原始公告没披露职务是常态）。
                // 这里不做兜底的话，一行坏数据 NPE 出去，上层 catch 会把这次已经采到的
                // 基本信息、股东、管理层三组变量一起丢掉，用户还看不到任何报错。
                String name = StrUtil.nullToEmpty(mgr.get("name"));
                String title = StrUtil.nullToEmpty(mgr.get("job")); // mapped to 'job' in helper
                if (StrUtil.isBlank(name)) {
                    continue;
                }
                if (title.contains("独立董事")) {
                    directors.add(name + "(独董)");
                } else if (title.contains("董事")) {
                    directors.add(name);
                } else if (title.contains("监事")) {
                    supervisors.add(name);
                } else {
                    executives.add(name);
                }
            }
            createVar(variables, projectId, "董事列表", String.join("、", directors), group);
            createVar(variables, projectId, "监事列表", String.join("、", supervisors), group);
            createVar(variables, projectId, "高管列表", String.join("、", executives), group);
        }

        return variables;
    }

    private String getTsCodeByName(String name) {
        JSONObject params = new JSONObject();
        params.put("list_status", "L");
        
        JSONObject response = callTushare("stock_basic", params, "ts_code,name,fullname");
        if (response == null || !response.containsKey("data")) {
            return null;
        }

        JSONObject data = response.getJSONObject("data");
        JSONArray items = data.getJSONArray("items"); 
        
        if (items == null) return null;

        for (Object itemObj : items) {
            JSONArray item = (JSONArray) itemObj;
            String stockName = item.getStr(1);
            String fullName = item.getStr(2);
            if (name.equals(stockName) || name.equals(fullName)) {
                return item.getStr(0);
            }
        }
        return null;
    }

    private Map<String, String> fetchBasicInfoMap(String tsCode) {
        JSONObject params = new JSONObject();
        params.put("ts_code", tsCode);
        String fields = "introduction,main_business,chairman,manager,secretary,reg_capital,setup_date,province,city,website,email,employees,office";
        JSONObject response = callTushare("stock_company", params, fields);
        
        if (response != null && response.containsKey("data")) {
            JSONObject data = response.getJSONObject("data");
            JSONArray items = data.getJSONArray("items");
            JSONArray fieldNames = data.getJSONArray("fields");
            
            if (items != null && !items.isEmpty()) {
                JSONArray item = items.getJSONArray(0);
                Map<String, String> result = new HashMap<>();
                for (int i = 0; i < fieldNames.size(); i++) {
                    result.put(fieldNames.getStr(i), item.getStr(i));
                }
                return result;
            }
        }
        return null;
    }

    private List<Map<String, String>> fetchTop10HoldersList(String tsCode) {
        JSONObject params = new JSONObject();
        params.put("ts_code", tsCode);
        JSONObject response = callTushare("top10_holders", params, "ann_date,end_date,holder_name,hold_amount,hold_ratio");
        
        if (response != null && response.containsKey("data")) {
            JSONObject data = response.getJSONObject("data");
            JSONArray items = data.getJSONArray("items");
            
            if (items != null && !items.isEmpty()) {
                String latestDate = "";
                for (Object itemObj : items) {
                    JSONArray item = (JSONArray) itemObj;
                    String endDate = item.getStr(1);
                    if (endDate != null && endDate.compareTo(latestDate) > 0) {
                        latestDate = endDate;
                    }
                }
                
                if (StrUtil.isNotBlank(latestDate)) {
                    final String targetDate = latestDate;
                    List<Map<String, String>> holders = new ArrayList<>();
                    for (Object itemObj : items) {
                         JSONArray item = (JSONArray) itemObj;
                         if (targetDate.equals(item.getStr(1))) {
                             Map<String, String> map = new HashMap<>();
                             map.put("name", item.getStr(2));
                             map.put("amount", item.getStr(3));
                             map.put("ratio", item.getStr(4));
                             map.put("date", targetDate);
                             // match DTO structure keys for frontend
                             map.put("shareholderName", item.getStr(2)); // DTO might use this or the list config
                             map.put("shares", item.getStr(3)); // match projectTypes.js
                             map.put("shareholdingRatio", item.getStr(4)); // match projectTypes.js
                             holders.add(map);
                         }
                    }
                    return holders;
                }
            }
        }
        return Collections.emptyList();
    }

    private List<Map<String, String>> fetchManagersList(String tsCode) {
        JSONObject params = new JSONObject();
        params.put("ts_code", tsCode);
        JSONObject response = callTushare("stk_managers", params, "name,title,begin_date,end_date");
        
        if (response != null && response.containsKey("data")) {
            JSONObject data = response.getJSONObject("data");
            JSONArray items = data.getJSONArray("items");
            
            if (items != null && !items.isEmpty()) {
                List<Map<String, String>> list = new ArrayList<>();
                for (Object itemObj : items) {
                    JSONArray item = (JSONArray) itemObj;
                    String name = item.getStr(0);
                    String title = item.getStr(1);
                    String begin = item.getStr(2);
                    String end = item.getStr(3);
                    
                    Map<String, String> map = new HashMap<>();
                    map.put("name", name);
                    map.put("position", title);
                    map.put("job", title);
                    map.put("term", (begin != null ? begin : "?") + " - " + (end != null ? end : "?"));
                    list.add(map);
                }
                return list;
            }
        }
        return Collections.emptyList();
    }

    /**
     * 上游唯一的出站缝，两档在这里分。
     *
     * <p>平台档的失败<b>抛出去而不是回 null</b>：null 在上面几个解析函数眼里就是
     * 「这家公司没数据」，于是「未开放」「余额不足」会伪装成一次查不到——
     * 那正是设计 §5.5 点名要避免的表现。自备 Key 档的 null 语义一字未改。
     */
    private JSONObject callTushare(String apiName, JSONObject params, String fields) {
        if (externalProviderResolver.resolve(
                com.checkba.service.platform.ExternalServiceProvider.TUSHARE)
                == com.checkba.service.platform.ExternalServiceProvider.PLATFORM) {
            return callTushareViaPlatform(apiName, params, fields);
        }

        String token = systemSettingService.get("external.tushare.token", defaultTushareToken);
        String apiUrl = systemSettingService.get("external.tushare.baseUrl", defaultTushareApiUrl);

        // 未配置时给出可读提示（dev-board#69，与 search_web/企查查同口径）：
        // 空 token 打上游只会换来一条看不出原因的失败。
        if (token == null || token.isBlank()) {
            throw new IllegalStateException(
                    "金融数据查询未配置：当前部署未提供 Tushare token（环境变量 TUSHARE_TOKEN）。请基于已有信息继续完成任务。");
        }

        JSONObject body = new JSONObject();
        body.put("api_name", apiName);
        body.put("token", token);
        body.put("params", params);
        body.put("fields", fields);

        try {
            String result = HttpRequest.post(apiUrl)
                    .body(body.toString())
                    .timeout(10000)
                    .execute()
                    .body();
            return JSONUtil.parseObj(result);
        } catch (Exception e) {
            log.error("Failed to call Tushare API: {}", apiName, e);
            return null;
        }
    }
    
    /** 平台代采档：官网持 token 调 Tushare，按次扣 Credits。响应形状与自备 token 档一致。 */
    private JSONObject callTushareViaPlatform(String apiName, JSONObject params, String fields) {
        Map<String, Object> body = new HashMap<>();
        body.put("apiName", apiName);
        body.put("params", params == null ? new JSONObject() : params);
        body.put("fields", fields == null ? "" : fields);

        com.checkba.service.platform.PlatformGatewayClient.Result result =
                platformGatewayClient.call("tushare", "query", body, GATEWAY_TIMEOUT_SECONDS);
        return JSONUtil.parseObj(result.data().toString());
    }

    private void createVar(List<ProjectVariable> variables, Long projectId, String name, String value, String group) {
        ProjectVariable var = new ProjectVariable();
        var.setProjectId(projectId);
        var.setName(name);
        var.setValue(value);
        var.setType("TEXT");
        var.setVariableGroup(group);
        variables.add(var);
    }
    
    private String mapFieldName(String field) {
        switch (field) {
            case "introduction": return "公司简介";
            case "main_business": return "主营业务";
            case "chairman": return "法人代表"; 
            case "manager": return "总经理";
            case "secretary": return "董秘";
            case "reg_capital": return "注册资本";
            case "setup_date": return "成立日期";
            case "province": return "所在省份";
            case "city": return "所在城市";
            case "website": return "公司官网";
            case "email": return "电子邮箱";
            case "employees": return "员工人数";
            case "office": return "办公地址";
            default: return field;
        }
    }
}
