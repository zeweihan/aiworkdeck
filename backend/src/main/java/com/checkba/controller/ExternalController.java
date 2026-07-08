package com.checkba.controller;

import com.checkba.model.dto.CompanyBasicInfoDTO;
import com.checkba.model.dto.CompanySearchRequest;
import com.checkba.service.CompanyMirrorService;
import com.checkba.service.QichachaService;
import com.checkba.service.StockCodeService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/external")
@CrossOrigin(origins = "*") // 允许跨域
public class ExternalController {

    @Autowired
    private QichachaService qichachaService;

    @Autowired
    private com.checkba.service.TushareService tushareService;

    @Autowired
    private CompanyMirrorService companyMirrorService;

    @Autowired
    private StockCodeService stockCodeService;

    @PostMapping("/company/basic")
    public ResponseEntity<?> getCompanyBasicInfo(@RequestBody CompanySearchRequest request) {
        if (request.getName() == null || request.getName().isEmpty()) {
            Map<String, String> error = new HashMap<>();
            error.put("error", "Company name is required");
            return ResponseEntity.badRequest().body(error);
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
            
            // Prioritize Tushare for LISTED companies
            if ("LISTED".equalsIgnoreCase(request.getRole())) {
                try {
                    dto = tushareService.fetchCompanyInfoDTO(searchKey);
                } catch (Exception e) {
                    // Log error and fallback to Qichacha? Or just fail?
                    // Let's assume fallback or just let it be null so Qichacha picks it up?
                    // User explicitly asked to change data source to Tushare.
                    // So if Tushare fails (returns null), we might want to fail or try Qichacha as backup.
                    // For now, if Tushare returns null, we proceed to Qichacha.
                }
            }
            
            if (dto == null) {
                dto = qichachaService.searchCompany(searchKey, request.getRole());
            }
            // 二次判空：searchCompany 可能返回 null，后续解引用会 NPE→500
            if (dto == null) {
                Map<String, String> error = new HashMap<>();
                error.put("error", "未找到相关企业信息，请检查公司名称是否正确");
                error.put("message", "查询无结果: " + searchKey);
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(error);
            }

            // 如果是股票代码查询且企查查/Tushare返回的 stockCode 为空，则用用户输入的代码兜底
            if (original.matches("\\d{6}") && !StringUtils.hasText(dto.getStockCode())) {
                dto.setStockCode(original);
            }

            // 将查询结果落库，形成"公司镜像"，供"我的客户"模块使用
            companyMirrorService.saveFromExternal(dto, request);
            return ResponseEntity.ok(dto);
        } catch (RuntimeException e) {
            // 处理企查查查询无结果或其他错误
            Map<String, String> error = new HashMap<>();
            String message = e.getMessage();
            // 检查多种可能的错误消息格式
            if (message != null && (message.contains("查询无结果") || message.contains("未查询到") || message.contains("查询失败"))) {
                error.put("error", "未找到相关企业信息，请检查公司名称是否正确");
                error.put("message", "企查查查询无结果: " + searchKey);
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(error);
            } else {
                error.put("error", "查询失败，请稍后重试");
                error.put("message", message != null ? message : "未知错误");
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
            }
        } catch (Exception e) {
            Map<String, String> error = new HashMap<>();
            error.put("error", "服务异常，请稍后重试");
            error.put("message", e.getMessage() != null ? e.getMessage() : "未知错误");
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
        }
    }
}
