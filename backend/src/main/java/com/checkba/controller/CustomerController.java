package com.checkba.controller;

import com.checkba.model.dto.CompanyBasicInfoDTO;
import com.checkba.service.CompanyMirrorService;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * “我的客户”模块相关接口
 */
@RestController
@RequestMapping("/api/customers")
public class CustomerController {

    private final CompanyMirrorService companyMirrorService;

    public CustomerController(CompanyMirrorService companyMirrorService) {
        this.companyMirrorService = companyMirrorService;
    }

    /**
     * 查询已保存的公司镜像列表
     *
     * 公司镜像表是全账号共用的一张表，没有归属列可供过滤；至少要求登录，
     * 否则任何人裸 curl 就能拉走全部尽调标的名单（role=TARGET 即在办并购标的）。
     *
     * @param role 可选，公司角色：LISTED / TARGET 等
     */
    @GetMapping("/companies")
    public List<CompanyBasicInfoDTO> listCompanies(
            @RequestParam(required = false) String role,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        if (AuthController.getUserIdFromSession(sessionId) == null) {
            throw new IllegalArgumentException("请先登录");
        }
        return companyMirrorService.listCompanies(role);
    }
}


