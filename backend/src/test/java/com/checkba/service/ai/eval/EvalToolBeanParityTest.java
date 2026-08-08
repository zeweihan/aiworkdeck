package com.checkba.service.ai.eval;

import com.checkba.service.ai.tools.AgentToolComponent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AssignableTypeFilter;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 守 {@link RealToolBeans#instantiateAll()} 与生产工具组件集的一致性。
 *
 * <p>那份清单是**手工维护**的。漏掉一个工具组件不会有任何报错——只是它的工具在
 * 回放评测里根本没注册，于是所有 `offeredToolsInclude` 断言都断言不到它，
 * 评测看起来全绿，实际上那个工具的可见性从来没被验证过。这是记在
 * `.claude/agents/plugin-system.md` 里的地雷，本测试把它变成 CI 里的红灯。
 *
 * <p><b>已知欠账（本测试建立时就存在，不在本次改动范围内）</b>：下面 KNOWN_MISSING
 * 里的组件至今不在评测清单里。把它们补进去会改变现有用例的可见工具集，可能撞上
 * 那些用例的 `offeredToolsExclude` 断言，属于独立的一件事。列在这里是为了让欠账
 * 显形——**新增工具组件时不要往这个名单里加**，要加进 RealToolBeans。
 */
class EvalToolBeanParityTest {

    private static final Set<String> KNOWN_MISSING = Set.of(
            "TodoTools",          // todo_write：计划卡链路，评测里从未注册
            "CheckpointTools",    // 文档检查点
            "SlideEditTools"      // slide_* 演示文稿原语
    );

    @Test
    @DisplayName("生产的每个 AgentToolComponent 都在评测的工具 bean 清单里（已知欠账除外）")
    void everyProductionToolComponentIsInTheEvalList() {
        Set<String> inEvalList = new TreeSet<>();
        for (AgentToolComponent b : RealToolBeans.instantiateAll()) {
            inEvalList.add(b.getClass().getSimpleName());
        }

        var scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AssignableTypeFilter(AgentToolComponent.class));
        List<String> missing = new ArrayList<>();
        for (BeanDefinition bd : scanner.findCandidateComponents("com.checkba.service.ai.tools")) {
            String simple = bd.getBeanClassName().substring(bd.getBeanClassName().lastIndexOf('.') + 1);
            if (inEvalList.contains(simple) || KNOWN_MISSING.contains(simple)) continue;
            missing.add(simple);
        }

        assertTrue(missing.isEmpty(),
                "这些工具组件没进 RealToolBeans.instantiateAll()，它们的工具在回放评测里不会注册，"
                        + "可见性断言等于形同虚设：" + new TreeSet<>(missing));
    }

    @Test
    @DisplayName("KNOWN_MISSING 名单本身不许发霉：已经补进清单的要从名单里删掉")
    void knownMissingListStaysHonest() {
        Set<String> inEvalList = new TreeSet<>();
        for (AgentToolComponent b : RealToolBeans.instantiateAll()) {
            inEvalList.add(b.getClass().getSimpleName());
        }
        List<String> stale = KNOWN_MISSING.stream().filter(inEvalList::contains).sorted().toList();
        assertTrue(stale.isEmpty(),
                "这些已经在评测清单里了，请从 KNOWN_MISSING 删除，否则名单会掩盖真实状态：" + stale);
    }
}
