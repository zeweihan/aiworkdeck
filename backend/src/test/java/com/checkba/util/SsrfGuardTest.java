package com.checkba.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 锁定 SSRF 目标校验：出站抓取的 URL 由 LLM/前端给出，必须挡住内网与云元数据。
 */
class SsrfGuardTest {

    @Test
    void blocksLoopbackInEveryNotation() {
        // 字符串匹配挡不住这些写法，所以校验必须落在解析后的 IP 上
        assertNotNull(SsrfGuard.rejectIfBlocked("http://127.0.0.1:9696/api/admin/config"));
        assertNotNull(SsrfGuard.rejectIfBlocked("http://localhost:9696/"));
        assertNotNull(SsrfGuard.rejectIfBlocked("http://[::1]/"));
        // 127.0.0.1 的十进制整数写法
        assertNotNull(SsrfGuard.rejectIfBlocked("http://2130706433/"));
    }

    @Test
    void blocksCloudMetadataEndpoints() {
        // AWS/GCP/Azure 元数据，取实例凭证
        assertNotNull(SsrfGuard.rejectIfBlocked("http://169.254.169.254/latest/meta-data/"));
        // 阿里云元数据在 100.64.0.0/10，Java 的 isSiteLocalAddress 不覆盖这段
        assertNotNull(SsrfGuard.rejectIfBlocked("http://100.100.100.200/latest/meta-data/"));
    }

    @Test
    void blocksPrivateRanges() {
        assertNotNull(SsrfGuard.rejectIfBlocked("http://10.0.0.5/"));
        assertNotNull(SsrfGuard.rejectIfBlocked("http://172.16.3.9/"));
        assertNotNull(SsrfGuard.rejectIfBlocked("http://192.168.1.1/"));
        assertNotNull(SsrfGuard.rejectIfBlocked("http://0.0.0.0/"));
    }

    @Test
    void blocksNonHttpSchemes() {
        assertNotNull(SsrfGuard.rejectIfBlocked("file:///etc/passwd"));
        assertNotNull(SsrfGuard.rejectIfBlocked("gopher://127.0.0.1:9696/"));
        assertNotNull(SsrfGuard.rejectIfBlocked("ftp://example.com/"));
    }

    @Test
    void rejectsMalformedInput() {
        assertNotNull(SsrfGuard.rejectIfBlocked("not a url"));
        assertNotNull(SsrfGuard.rejectIfBlocked("http://"));
    }

    @Test
    void allowsOrdinaryPublicHost() {
        // 公网地址直接给 IP，避免测试依赖 DNS
        assertNull(SsrfGuard.rejectIfBlocked("https://93.184.216.34/"));
        assertNull(SsrfGuard.rejectIfBlocked("http://8.8.8.8/"));
    }
}
