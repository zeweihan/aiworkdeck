package com.example.helloevidence;

import com.checkba.plugin.api.evidence.EvidenceItem;
import com.checkba.plugin.api.evidence.EvidenceProvider;
import com.checkba.plugin.api.evidence.EvidenceQuery;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * evidence.retrieve.v1 公开 Provider 的最小示例（插件规范 v2.8）。
 *
 * 三条铁律的示范：
 * 1. sourceId 必须带 <pluginId>. 前缀，且与 manifest contributes.evidenceSources 声明一致；
 * 2. 幂等重放：evidenceId 与 contentHash 由数据内容决定，不掺时间戳/随机数；
 * 3. 缺定位符即丢弃：给不出 locator 的内容根本不进返回列表。
 */
public class DemoRegistryProvider implements EvidenceProvider {

    /** 假数据：真实插件在这里接自己的数据库/REST API */
    private static final Map<String, String> FAKE_REGISTRY = Map.of(
            "示例科技有限公司", "统一社会信用代码 91110000MA01XXXX00，成立于 2018-03-05，注册资本 500 万元",
            "演示咨询合伙企业", "统一社会信用代码 91310000MA02YYYY11，成立于 2020-11-12，执行事务合伙人 张三");

    @Override
    public String sourceId() {
        return "hello-evidence-plugin.demo-registry";
    }

    @Override
    public List<EvidenceItem> retrieve(EvidenceQuery query) {
        List<EvidenceItem> out = new ArrayList<>();
        if (query.query() == null || query.query().isBlank()) {
            return out;
        }
        for (Map.Entry<String, String> e : FAKE_REGISTRY.entrySet()) {
            if (!e.getKey().contains(query.query()) && !query.query().contains(e.getKey())) {
                continue;
            }
            out.add(new EvidenceItem(
                    "demo-registry:" + e.getKey(),
                    "https://example.com/registry/" + e.getKey(),
                    sha256(e.getValue()),
                    null, null,
                    "registry_record#" + e.getKey(),
                    e.getValue(),
                    "text/plain",
                    "public",
                    List.of("demo-registry 内存假数据"),
                    null, null, null));
            if (query.limit() > 0 && out.size() >= query.limit()) {
                break;
            }
        }
        return out;
    }

    private static String sha256(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }
}
