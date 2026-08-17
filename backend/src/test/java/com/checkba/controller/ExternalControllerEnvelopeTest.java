package com.checkba.controller;

import com.checkba.model.dto.CompanyBasicInfoDTO;
import com.checkba.model.dto.CompanySearchRequest;
import com.checkba.service.CompanyMirrorService;
import com.checkba.service.QichachaService;
import com.checkba.service.StockCodeService;
import com.checkba.service.TushareService;
import com.checkba.service.platform.GatewayException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * 企业数据这条路的错误信封。
 *
 * <p>旧写法把 {@link GatewayException} 的 {@code kind} 与 {@code suggestsByok()} 全丢在
 * {@code catch(RuntimeException)} → HTTP 500 里，前端只拿到一个 500 加一句中文，
 * 于是「未开放」「上游挂了」「余额不足」在企业数据上长得一模一样——
 * 而这三件事的下一步毫无共同点，第三件还得摆出「改用自己的 Key」的逃生门。
 *
 * <p>这里锁住的是：网关失败必须<b>抛出去</b>（由 GlobalExceptionHandler 统一压成
 * {@code code=1 + gatewayKind + canUseOwnKey}），业务失败一律 {@code code=1} 且绝不是 4010。
 */
class ExternalControllerEnvelopeTest {

    private final QichachaService qichacha = mock(QichachaService.class);
    private final TushareService tushare = mock(TushareService.class);
    private final CompanyMirrorService mirror = mock(CompanyMirrorService.class);
    private final StockCodeService stockCode = mock(StockCodeService.class);
    private final ExternalController controller =
            new ExternalController(qichacha, tushare, mirror, stockCode);

    private static CompanySearchRequest request(String name, String role) {
        CompanySearchRequest req = new CompanySearchRequest();
        req.setName(name);
        req.setRole(role);
        return req;
    }

    private Map<String, Object> callAsUser(CompanySearchRequest req) {
        try (MockedStatic<AuthController> auth = mockStatic(AuthController.class)) {
            auth.when(() -> AuthController.getUserIdFromSession("s")).thenReturn(7L);
            return controller.getCompanyBasicInfo(req, "s");
        }
    }

    @Test
    @DisplayName("网关失败原样抛出：kind 与「改用自己的 Key」的判据要到得了前端")
    void gatewayFailurePropagatesWithItsKind() {
        when(qichacha.searchCompany(anyString(), any()))
                .thenThrow(new GatewayException(GatewayException.Kind.SERVICE_DISABLED, "该服务暂未开放"));

        GatewayException e = assertThrows(GatewayException.class,
                () -> callAsUser(request("某某公司", null)));
        assertEquals(GatewayException.Kind.SERVICE_DISABLED, e.getKind());
    }

    @Test
    @DisplayName("Tushare 网关失败但企查查查得到：照常返回数据，不把一次回落变成报错")
    void tushareGatewayFailureStillFallsBackToQichacha() {
        when(tushare.fetchCompanyInfoDTO(anyString()))
                .thenThrow(new GatewayException(GatewayException.Kind.SERVICE_DISABLED, "该服务暂未开放"));
        CompanyBasicInfoDTO dto = new CompanyBasicInfoDTO();
        when(qichacha.searchCompany(anyString(), any())).thenReturn(dto);

        Map<String, Object> res = callAsUser(request("某某上市公司", "LISTED"));

        assertEquals(0, res.get("code"));
        assertSame(dto, res.get("data"));
    }

    @Test
    @DisplayName("两条路都没拿到数据时，抛的是 Tushare 那次网关失败，不是「查无此企业」")
    void surfacesGatewayFailureInsteadOfPretendingNoResults() {
        when(tushare.fetchCompanyInfoDTO(anyString()))
                .thenThrow(new GatewayException(GatewayException.Kind.NO_CREDITS, "Credits 余额不足"));
        when(qichacha.searchCompany(anyString(), any())).thenReturn(null);

        // 「余额不足」被表达成「查无此企业」的话，用户会去改公司名，永远查不到真原因
        GatewayException e = assertThrows(GatewayException.class,
                () -> callAsUser(request("某某上市公司", "LISTED")));
        assertEquals(GatewayException.Kind.NO_CREDITS, e.getKind());
    }

    @Test
    @DisplayName("查无结果是业务失败：code=1，绝不是 4010（那个码会让前端清会话）")
    void notFoundIsBusinessErrorNotLogout() {
        when(qichacha.searchCompany(anyString(), any())).thenReturn(null);

        Map<String, Object> res = callAsUser(request("不存在的公司", null));

        assertEquals(1, res.get("code"));
        assertNotEquals(4010, res.get("code"));
        assertNull(res.get("data"));
        String message = String.valueOf(res.get("message"));
        for (String forbidden : new String[] {"登录", "未授权", "请先"}) {
            org.junit.jupiter.api.Assertions.assertFalse(message.contains(forbidden),
                    "业务文案含「" + forbidden + "」会被 api.js 判成掉线：" + message);
        }
    }

    @Test
    @DisplayName("真的没有会话时才回 4010，交给全局 handler 判定")
    void missingSessionIsTheOnlyAuthFailure() {
        try (MockedStatic<AuthController> auth = mockStatic(AuthController.class)) {
            auth.when(() -> AuthController.getUserIdFromSession("s")).thenReturn(null);
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> controller.getCompanyBasicInfo(request("某某公司", null), "s"));
            // GlobalExceptionHandler 只对这两个字面量回 4010，写别的会变成 code=1
            assertEquals("未登录", e.getMessage());
        }
    }
}
