package com.example.helloevidence;

import com.checkba.plugin.api.evidence.EvidenceProviderConformanceKit;
import com.checkba.plugin.api.evidence.EvidenceQuery;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * conformance 自测（规范 v2.8）：广场受理要求附全绿运行记录。
 * 插件仓照这个形状写一条测试即可——夹具查询要能命中至少一条真实数据。
 */
class DemoRegistryProviderConformanceTest {

    @Test
    void conformance() {
        var provider = new DemoRegistryProvider();
        var query = new EvidenceQuery("1", "示例科技", null, List.of(), Map.of(), 5);
        assertEquals(List.of(), EvidenceProviderConformanceKit.run(provider, query));
    }
}
