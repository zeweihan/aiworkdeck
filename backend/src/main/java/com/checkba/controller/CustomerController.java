package com.checkba.controller;

import com.checkba.model.dto.CompanyBasicInfoDTO;
import com.checkba.service.CompanyMirrorService;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
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
     * @param role 可选，公司角色：LISTED / TARGET 等
     */
    @GetMapping("/companies")
    public List<CompanyBasicInfoDTO> listCompanies(@RequestParam(required = false) String role) {
        return companyMirrorService.listCompanies(role);
    }
}


