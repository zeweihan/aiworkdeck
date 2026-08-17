package com.checkba.controller;

import com.checkba.model.dto.CompanyBasicInfoDTO;
import com.checkba.model.dto.CompanySearchRequest;
import com.checkba.service.CompanyMirrorService;
import com.checkba.service.LangText;
import com.checkba.service.QichachaService;
import com.checkba.service.StockCodeService;
import com.checkba.service.platform.GatewayException;
import lombok.RequiredArgsConstructor;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * 企业工商信息查询。
 *
 * <p><b>信封是标准的 {@code {code, message}}，不是自造的 {@code {error, message}} + HTTP 5xx</b>
 * （2026-08-17 改）。旧写法把 {@link GatewayException} 的两样关键信息全丢了：
 * {@code kind}（未开放 / 上游挂了 / 我们挂了 / 余额不足）与 {@code suggestsByok()}
 * （要不要摆「改用自己的 Key」的逃生门）。前端只拿到一个 500 加一句中文，
 * 于是企业数据这条路上「未开放」「上游挂了」「余额不足」长得一模一样，
 * 而这三件事的下一步毫无共同点。想按中文子串把分类猜回来是死路——
 * api.js 早年那套「登录/未授权/请先」子串判定就是这么误伤业务文案的。
 *
 * <p>网关失败一律<b>抛出</b>交给 {@code GlobalExceptionHandler.handleGateway}，
 * 由它统一压成 {@code code=1 + gatewayKind + canUseOwnKey}。
 * 分类只许有一处定义，在这里再 catch 一次就是第二处。
 */
@RestController
@RequestMapping("/api/external")
@RequiredArgsConstructor
public class ExternalController {

    private final QichachaService qichachaService;
    private final com.checkba.service.TushareService tushareService;
    private final CompanyMirrorService companyMirrorService;
    private final StockCodeService stockCodeService;

    @PostMapping("/company/basic")
    public Map<String, Object> getCompanyBasicInfo(@RequestBody CompanySearchRequest request,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        // 这条接口花的是账号配置里的企查查/Tushare 付费额度（平台档下花的是 Credits），
        // 并把结果写进全账号共用的公司镜像表；匿名可调既能烧额度，
        // 也能往别人的尽调标的列表里塞条目。
        // 这是**真的**鉴权失败，交给全局 handler 回 4010（前端据此清会话）——
        // 与下面那些绝不能带 4010 的业务失败是两回事。
        if (AuthController.getUserIdFromSession(sessionId) == null) {
            throw new IllegalArgumentException("未登录");
        }
        if (request.getName() == null || request.getName().isEmpty()) {
            return error(LangText.of("请填写公司名称", "A company name is required"));
        }

        String original = request.getName().trim();
        String searchKey = original;

        // 如果输入看起来是 6 位股票代码，先通过证券接口解析公司名称，再去企查查查公司
        if (original.matches("\\d{6}")) {
            String resolvedName = stockCodeService.resolveCompanyName(original);
            if (StringUtils.hasText(resolvedName)) {
                searchKey = resolvedName;
            }
        }

        try {
            CompanyBasicInfoDTO dto = null;
            // 上市公司优先走 Tushare（财务字段更全），失败回落企查查。
            // 回落本身是对的：两家档位各自独立，Tushare 未开放而企查查自备 Key 可用是常态。
            GatewayException tushareGatewayFailure = null;

            if ("LISTED".equalsIgnoreCase(request.getRole())) {
                try {
                    dto = tushareService.fetchCompanyInfoDTO(searchKey);
                } catch (GatewayException e) {
                    // 记下来但不当场抛：企查查可能照样查得到。
                    // 只有在**两条路都没拿到数据**时才把它抛出去——否则一次
                    // 「Tushare 未开放」会被表达成「查无此企业」，用户永远查不到原因。
                    tushareGatewayFailure = e;
                } catch (Exception e) {
                    // 非网关失败（解析异常、上游返回意外结构）：照旧回落企查查
                }
            }

            if (dto == null) {
                dto = qichachaService.searchCompany(searchKey, request.getRole());
            }
            // 二次判空：searchCompany 可能返回 null，后续解引用会 NPE→500
            if (dto == null) {
                if (tushareGatewayFailure != null) throw tushareGatewayFailure;
                return error(LangText.of("未找到相关企业信息，请检查公司名称是否正确：",
                        "No matching company information found. Please check the company name: ") + searchKey);
            }

            // 如果是股票代码查询且企查查/Tushare返回的 stockCode 为空，则用用户输入的代码兜底
            if (original.matches("\\d{6}") && !StringUtils.hasText(dto.getStockCode())) {
                dto.setStockCode(original);
            }

            // 将查询结果落库，形成"公司镜像"，供"我的客户"模块使用
            companyMirrorService.saveFromExternal(dto, request);
            Map<String, Object> result = new HashMap<>();
            result.put("code", 0);
            result.put("data", dto);
            return result;
        } catch (GatewayException e) {
            // 抛给 GlobalExceptionHandler.handleGateway：kind 与 canUseOwnKey 由它透出去
            throw e;
        } catch (RuntimeException e) {
            String message = e.getMessage();
            if (message != null && (message.contains("查询无结果") || message.contains("未查询到") || message.contains("查询失败"))) {
                return error(LangText.of("未找到相关企业信息，请检查公司名称是否正确：",
                        "No matching company information found. Please check the company name: ") + searchKey);
            }
            return error(LangText.of("查询失败，稍后重试", "Lookup failed, please try again later."));
        }
    }

    /**
     * 业务失败一律 {@code code=1}。
     *
     * <p><b>绝不是 4010</b>：那个码前端判掉线并清会话，一次「查无此企业」会把用户踢下线。
     * 文案同样不许含「登录」「未授权」「请先」——api.js 拿这三个子串做同一件事。
     */
    private Map<String, Object> error(String message) {
        Map<String, Object> result = new HashMap<>();
        result.put("code", 1);
        result.put("message", message);
        return result;
    }
}
