package com.checkba.service.ai;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 跨语言签名一致性：验证官网（Node crypto）签发的签名能被桌面端（JDK）验过。
 * canonical JSON 只要差一个字节验签就会失败，所以这条链路必须真机对拍一次。
 *
 * 由 -Dcrosscheck.file=<path> 提供 Node 侧产物，未提供时跳过（CI 不依赖外部文件）。
 */
class CrossLanguageSignatureTest {

    @Test
    @EnabledIfSystemProperty(named = "crosscheck.file", matches = ".+")
    @DisplayName("Node 侧签发的签名在 Java 侧验签通过")
    void nodeSignatureVerifiesInJava() throws Exception {
        Path f = Path.of(System.getProperty("crosscheck.file"));
        var root = cn.hutool.json.JSONUtil.parseObj(Files.readString(f));
        String nodeCanonical = root.getStr("canonical");
        String sig = root.getStr("sig");
        var payload = root.getJSONObject("payload");
        var filesObj = payload.getJSONObject("files");

        Map<String, String> files = new TreeMap<>();
        for (String k : filesObj.keySet()) files.put(k, filesObj.getStr(k));

        String pubPem = Files.readString(f.getParent().resolve("test-pub.pem"));
        PluginMarketService svc = new PluginMarketService(
                "http://x", pubPem, f.getParent().toString(), new PluginService());

        assertTrue(
                svc.verifySignature(payload.getStr("id"), payload.getStr("version"),
                        payload.getStr("publishedAt"), files, sig),
                "Java 侧必须能验过 Node 签发的签名——canonical JSON 两端需逐字节一致。"
                        + "\nNode canonical: " + nodeCanonical);
    }
}
