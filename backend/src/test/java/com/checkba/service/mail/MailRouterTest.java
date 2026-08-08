package com.checkba.service.mail;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.Order;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MailRouterTest {

    /** 用真网关而不是桩：选路谓词本身就是被测对象。构造 JavaMailSenderImpl 不建连接。 */
    private static DomesticMailGateway domestic(boolean enabled) {
        return new DomesticMailGateway(enabled, "smtpdm.aliyun.com", 465,
                "optimizer@dm.aiworkdeck.com", "pw", "", "AI Workdeck");
    }

    private static GlobalMailGateway global(boolean enabled) {
        return new GlobalMailGateway(enabled, "smtp.resend.com", 465,
                "resend", "re_x", "optimizer@send.aiworkdeck.com", "AI Workdeck");
    }

    private static MailRouter router(MailGateway... gateways) {
        return new MailRouter(List.of(gateways));
    }

    @Test
    @DisplayName("国内主流邮箱走阿里云，其余走 Resend")
    void routesByRecipientDomain() {
        MailRouter r = router(domestic(true), global(true));
        for (String cn : List.of("a@qq.com", "b@163.com", "c@126.com", "d@139.com",
                "e@foxmail.com", "f@sina.com", "g@189.cn")) {
            assertEquals("aliyun-directmail", r.gatewayFor(cn).name(), cn + " 应走国内通道");
        }
        for (String intl : List.of("a@gmail.com", "b@outlook.com", "c@example.org",
                "d@aiworkdeck.com")) {
            assertEquals("resend", r.gatewayFor(intl).name(), intl + " 应走兜底通道");
        }
    }

    @Test
    @DisplayName("域名匹配不受大小写影响（规范化后再判）")
    void domainMatchIsCaseInsensitive() {
        MailRouter r = router(domestic(true), global(true));
        assertEquals("aliyun-directmail", r.gatewayFor(MailRouter.normalize(" Foo@QQ.com ")).name());
    }

    @Test
    @DisplayName("只开一条通道时，不属于它的收件人也兜给它——宁可次优通道也要送达")
    void fallsBackToAnyEnabledGateway() {
        assertEquals("aliyun-directmail", router(domestic(true), global(false))
                .gatewayFor("a@gmail.com").name());
        assertEquals("resend", router(domestic(false), global(true))
                .gatewayFor("a@qq.com").name());
    }

    @Test
    @DisplayName("一条都没配则整体不可用，选路直接抛业务错误")
    void inactiveWhenNothingConfigured() {
        MailRouter r = router(domestic(false), global(false));
        assertFalse(r.active());
        assertThrows(IllegalArgumentException.class, () -> r.gatewayFor("a@qq.com"));
    }

    @Test
    @DisplayName("Resend 未配 from 判为未配置：用户名是字面量 resend，回落会拼出非法发件人")
    void globalGatewayRequiresRealFromAddress() {
        assertFalse(new GlobalMailGateway(true, "smtp.resend.com", 465, "resend", "re_x", "", "AI Workdeck")
                .enabled());
        assertTrue(global(true).enabled());
    }

    @Test
    @DisplayName("阿里云通道的 from 可留空——用户名本身就是发信地址")
    void domesticGatewayFallsBackFromToUsername() {
        assertTrue(domestic(true).enabled());
    }

    @Test
    @DisplayName("国内通道必须排在兜底通道之前，否则 Resend 会把 QQ/163 全吃掉")
    void gatewayOrderIsPinned() {
        int cn = DomesticMailGateway.class.getAnnotation(Order.class).value();
        int fallback = GlobalMailGateway.class.getAnnotation(Order.class).value();
        assertTrue(cn < fallback, "DomesticMailGateway 的 @Order 必须小于 GlobalMailGateway");
    }

    @Test
    @DisplayName("规范化：去空白、转小写；非法格式抛业务错误")
    void normalizeTrimsAndLowercases() {
        assertEquals("foo@qq.com", MailRouter.normalize("  Foo@QQ.Com "));
        for (String bad : List.of("", "  ", "foo", "foo@", "@qq.com", "foo@qq", "a b@qq.com", "foo@@qq.com")) {
            assertThrows(IllegalArgumentException.class, () -> MailRouter.normalize(bad), "应拒绝: [" + bad + "]");
        }
        assertThrows(IllegalArgumentException.class, () -> MailRouter.normalize(null));
    }

    @Test
    @DisplayName("日志脱敏不泄露完整地址")
    void masksAddressForLogging() {
        assertEquals("h***@gmail.com", SmtpMailGateway.mask("hanzewei@gmail.com"));
        assertEquals("***@qq.com", SmtpMailGateway.mask("a@qq.com"));
        assertEquals("", SmtpMailGateway.mask(null));
    }
}
