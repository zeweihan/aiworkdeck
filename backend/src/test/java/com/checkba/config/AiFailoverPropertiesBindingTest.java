package com.checkba.config;

import com.checkba.service.ai.AllowedModels;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * application.yml 的故障转移链与自动 compaction 配置绑定。
 *
 * <p>守两个静默失效：一是 key 拼错（failover.model / compaction.keep-recents 之类）绑不上、
 * 默认值悄悄生效；二是候选模型不在 AllowedModels 白名单里——工厂会把它回落成默认模型，
 * 故障转移看似跑了实则原地踏步（PR#144 同源的坑）。
 */
class AiFailoverPropertiesBindingTest {

    private static Binder binder() throws IOException {
        List<PropertySource<?>> sources =
                new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yml"));
        return new Binder(ConfigurationPropertySources.from(sources));
    }

    @Test
    @DisplayName("ai.failover 绑得上，且候选模型全在白名单内")
    void failoverChainBindsAndIsAllowlisted() throws IOException {
        AiFailoverProperties props = binder().bind("ai.failover", AiFailoverProperties.class).get();

        assertTrue(props.isEnabled());
        assertFalse(props.getModels().isEmpty(), "默认要给出备选，否则加固等于没开");
        for (String model : props.getModels()) {
            assertTrue(AllowedModels.isAllowed(model),
                    "候选 " + model + " 不在白名单内，切过去会被静默回落成默认模型");
        }
    }

    @Test
    @DisplayName("ai.context.compaction 绑得上，阈值取值合理")
    void compactionConfigBinds() throws IOException {
        AiContextProperties props = binder().bind("ai.context", AiContextProperties.class).get();
        AiContextProperties.Compaction compaction = props.getCompaction();

        assertTrue(compaction.isEnabled());
        assertTrue(compaction.getTriggerRatio() > 0 && compaction.getTriggerRatio() < 1,
                "触发比例必须是预算的一个真分数");
        assertTrue(compaction.getKeepRecent() >= 4, "保留太少会把当前正在做的事一起折掉");
        assertTrue(compaction.getMinMiddleMessages() >= 2, "中段下限太低会误伤短会话");
    }
}
