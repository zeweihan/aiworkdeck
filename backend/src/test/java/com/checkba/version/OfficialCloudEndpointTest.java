package com.checkba.version;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 官方团队案件库地址的派生（dev-board#439 第 1 环）。
 *
 * 三条分支各有各的理由，别合并成一句三目：
 * ① 显式配置优先——自建/私有部署靠它把「官方」指到自己的服务器上（能力保留是拍板项）；
 * ② 国际站（workdeck.ai）暂不提供，返回 null——国际站账户的案卷不该被默默送进大陆的服务器；
 * ③ 其余（大陆站）走 case.aiworkdeck.com。
 */
class OfficialCloudEndpointTest {

    @Test
    void mainlandAccountWithoutConfigurationGetsTheOfficialCaseLibrary() {
        assertEquals("https://case.aiworkdeck.com",
                OfficialCloudEndpoint.resolve("", "https://www.aiworkdeck.com"));
        assertEquals("https://case.aiworkdeck.com",
                OfficialCloudEndpoint.resolve(null, "https://www.aiworkdeck.com"));
    }

    @Test
    void internationalAccountHasNoOfficialCaseLibraryYet() {
        assertNull(OfficialCloudEndpoint.resolve("", "https://www.workdeck.ai"));
        assertNull(OfficialCloudEndpoint.resolve(null, "https://workdeck.ai"));
    }

    /** 域名是否「workdeck.ai 系」按主机判定，不能用 contains——那会把 workdeck.ai.evil.com 也算进去。 */
    @Test
    void lookalikeHostIsNotTreatedAsTheInternationalSite() {
        assertEquals("https://case.aiworkdeck.com",
                OfficialCloudEndpoint.resolve("", "https://workdeck.ai.evil.com"));
    }

    @Test
    void explicitConfigurationIsUsedAsIsOnBothSites() {
        assertEquals("https://case.example.com",
                OfficialCloudEndpoint.resolve("https://case.example.com", "https://www.aiworkdeck.com"));
        assertEquals("https://case.example.com",
                OfficialCloudEndpoint.resolve("https://case.example.com/", "https://www.workdeck.ai"));
    }

    /** 设备令牌走这条通道，明文 http 一律拒绝（回环例外，供本地联调）——口径同 AccountEndpoint。 */
    @Test
    void plainHttpConfigurationIsRejectedButLoopbackIsAllowed() {
        assertThrows(IllegalArgumentException.class,
                () -> OfficialCloudEndpoint.resolve("http://case.example.com", "https://www.aiworkdeck.com"));
        assertEquals("http://localhost:9696",
                OfficialCloudEndpoint.resolve("http://localhost:9696", "https://www.aiworkdeck.com"));
    }
}
