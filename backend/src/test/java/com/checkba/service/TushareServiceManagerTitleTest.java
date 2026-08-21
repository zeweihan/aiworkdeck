package com.checkba.service;

import com.checkba.model.entity.ProjectVariable;
import com.checkba.service.platform.ExternalProviderResolver;
import com.checkba.service.platform.ExternalServiceProvider;
import com.checkba.service.platform.PlatformGatewayClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tushare 管理层字段缺失时不能把整批变量一起赔掉。
 *
 * <p>stk_managers 的 title 在真实数据里会缺（原始公告没披露职务）。以前那一行
 * {@code title.contains(...)} 直接 NPE，而 ProjectService.createProject 把整个
 * fetchAndCreateVariables 包在 catch 里——一行坏数据 = 基本信息、前十大股东、
 * 管理层三组全丢，用户还看不到任何报错。
 */
class TushareServiceManagerTitleTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 平台档网关桩：按 apiName 分发假响应。 */
    private static TushareService serviceWith(Map<String, String> responsesByApi) {
        PlatformGatewayClient gateway = mock(PlatformGatewayClient.class);
        try {
            when(gateway.call(anyString(), anyString(), anyMap(), anyInt())).thenAnswer(inv -> {
                Map<String, Object> body = inv.getArgument(2);
                String apiName = String.valueOf(body.get("apiName"));
                String json = responsesByApi.getOrDefault(apiName, "{\"code\":0}");
                return new PlatformGatewayClient.Result(MAPPER.readTree(json), 3, 1, "call");
            });
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }

        SystemSettingService settings = mock(SystemSettingService.class);
        when(settings.get(anyString(), any())).thenAnswer(inv -> {
            String key = inv.getArgument(0);
            return key.equals(ExternalProviderResolver.providerKey(ExternalServiceProvider.TUSHARE))
                    ? ExternalServiceProvider.PLATFORM.settingValue()
                    : inv.getArgument(1);
        });

        TushareService svc = new TushareService();
        ReflectionTestUtils.setField(svc, "systemSettingService", settings);
        ReflectionTestUtils.setField(svc, "externalProviderResolver",
                new ExternalProviderResolver(settings, true));
        ReflectionTestUtils.setField(svc, "platformGatewayClient", gateway);
        ReflectionTestUtils.setField(svc, "defaultTushareToken", "ts-token");
        ReflectionTestUtils.setField(svc, "defaultTushareApiUrl", "http://127.0.0.1:1");
        return svc;
    }

    @Test
    @DisplayName("某位管理层没有职务：不抛异常，基本信息与股东两组照样落库")
    void nullTitleDoesNotWipeEverything() {
        TushareService svc = serviceWith(Map.of(
                "stock_basic", "{\"code\":0,\"data\":{\"fields\":[\"ts_code\",\"name\",\"fullname\"],"
                        + "\"items\":[[\"000001.SZ\",\"平安银行\",\"平安银行股份有限公司\"]]}}",
                "stock_company", "{\"code\":0,\"data\":{\"fields\":[\"reg_capital\",\"office\"],"
                        + "\"items\":[[\"194亿\",\"深圳市\"]]}}",
                "top10_holders", "{\"code\":0,\"data\":{\"fields\":[\"ann_date\",\"end_date\",\"holder_name\",\"hold_amount\",\"hold_ratio\"],"
                        + "\"items\":[[\"20240101\",\"20231231\",\"中国平安\",\"1000\",\"49.5\"]]}}",
                // 第二行的 title 是 null——真实数据里就这样
                "stk_managers", "{\"code\":0,\"data\":{\"fields\":[\"name\",\"title\",\"begin_date\",\"end_date\"],"
                        + "\"items\":[[\"张三\",\"董事长\",\"20200101\",null],[\"李四\",null,\"20200101\",null]]}}"
        ));

        List<ProjectVariable> vars = svc.fetchAndCreateVariables(1L, "平安银行");

        assertTrue(vars.stream().anyMatch(v -> "注册资本".equals(v.getName())),
                "基本信息组不该因为管理层一行坏数据而丢失");
        assertTrue(vars.stream().anyMatch(v -> "第1大股东名称".equals(v.getName())),
                "股东组不该因为管理层一行坏数据而丢失");
        String directors = vars.stream().filter(v -> "董事列表".equals(v.getName()))
                .map(ProjectVariable::getValue).findFirst().orElse(null);
        assertEquals("张三", directors, "有职务的照常归类");
        String executives = vars.stream().filter(v -> "高管列表".equals(v.getName()))
                .map(ProjectVariable::getValue).findFirst().orElse(null);
        assertEquals("李四", executives, "职务缺失的落到「高管」兜底组，不该丢人也不该带 null");
    }

    @Test
    @DisplayName("姓名缺失的行整行跳过，不生成「null(独董)」这种脏值")
    void blankNameRowIsSkipped() {
        TushareService svc = serviceWith(Map.of(
                "stock_basic", "{\"code\":0,\"data\":{\"fields\":[\"ts_code\",\"name\",\"fullname\"],"
                        + "\"items\":[[\"000001.SZ\",\"平安银行\",\"平安银行股份有限公司\"]]}}",
                "stk_managers", "{\"code\":0,\"data\":{\"fields\":[\"name\",\"title\",\"begin_date\",\"end_date\"],"
                        + "\"items\":[[null,\"独立董事\",\"20200101\",null],[\"张三\",\"独立董事\",\"20200101\",null]]}}"
        ));

        List<ProjectVariable> vars = svc.fetchAndCreateVariables(1L, "平安银行");

        String directors = vars.stream().filter(v -> "董事列表".equals(v.getName()))
                .map(ProjectVariable::getValue).findFirst().orElse(null);
        assertEquals("张三(独董)", directors, "实际为：" + directors);
    }
}
