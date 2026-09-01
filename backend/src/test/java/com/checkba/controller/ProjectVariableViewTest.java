package com.checkba.controller;

import com.checkba.model.entity.ProjectVariable;
import com.checkba.service.AppLanguageService;
import com.checkba.service.LangText;
import com.checkba.service.LocalIdentityService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 变量库创建者名的读时本地化（dev-board#351）。
 *
 * <p>{@code ProjectVariable.creatorName} 是写时快照：建变量那一刻把 User.displayName 抄进
 * project_variables，单机模式下抄到的是中文哨兵「本机用户」。v0.30.0 的读时本地化只覆盖了
 * 直接读 User 的五处，够不着这张表，英文界面的变量面板照旧显示中文。修法是出参侧映射，
 * **不碰库里存的值**——这也是本测试最后两条断言守的东西。
 */
class ProjectVariableViewTest {

    @AfterEach
    void resetLangText() {
        LangText.reset();
    }

    private ProjectVariable localUserVariable() {
        ProjectVariable v = new ProjectVariable();
        v.setId(11L);
        v.setProjectId(7L);
        v.setName("甲方");
        v.setValue("某某科技有限公司");
        v.setVariableGroup("当事人");
        v.setResolvedValue("某某科技有限公司");
        v.setType("TEXT");
        v.setCreatorId(3L);
        v.setCreatorName(LocalIdentityService.LOCAL_DISPLAY_NAME);
        v.setCreatedAt(LocalDateTime.of(2026, 9, 1, 10, 0));
        v.setUpdatedAt(LocalDateTime.of(2026, 9, 1, 11, 0));
        return v;
    }

    private void useEnglish() {
        AppLanguageService en = mock(AppLanguageService.class);
        when(en.isEnglish()).thenReturn(true);
        LangText.register(en);
    }

    @Test
    void sentinelCreatorIsLocalizedInEnglishUi() {
        useEnglish();
        assertEquals("Local user",
                ProjectVariableController.VariableView.of(localUserVariable()).creatorName());
    }

    @Test
    void sentinelCreatorStaysChineseInChineseUi() {
        LangText.reset(); // 未登记 = 中文，与既有默认态一致
        assertEquals(LocalIdentityService.LOCAL_DISPLAY_NAME,
                ProjectVariableController.VariableView.of(localUserVariable()).creatorName());
    }

    @Test
    void realCreatorNamePassesThroughEvenInEnglishUi() {
        useEnglish();
        ProjectVariable v = localUserVariable();
        v.setCreatorName("韩泽伟");
        assertEquals("韩泽伟", ProjectVariableController.VariableView.of(v).creatorName());
    }

    @Test
    void nullCreatorNameSurvivesSoFrontendFallbackStillFires() {
        // VariablePanel.vue 的 `it.creatorName || 'Project'` 回退依赖 null 原样传出
        useEnglish();
        ProjectVariable v = localUserVariable();
        v.setCreatorName(null);
        assertNull(ProjectVariableController.VariableView.of(v).creatorName());
    }

    /** 本地化只发生在出参上：实体一个字段都不许被改（改了会被 OSIV 会话刷回库）。 */
    @Test
    void mappingDoesNotMutateTheEntity() {
        useEnglish();
        ProjectVariable v = localUserVariable();

        ProjectVariableController.VariableView view = ProjectVariableController.VariableView.of(v);

        assertEquals("Local user", view.creatorName());
        assertEquals(LocalIdentityService.LOCAL_DISPLAY_NAME, v.getCreatorName(),
                "实体的 creatorName 被就地改写了——Hibernate 脏检查会把英文刷回库，中文界面反过来看到英文");
    }

    /** 出参逐字段照抄，且新加的实体字段不能被这层视图静默吞掉。 */
    @Test
    void viewCarriesEveryEntityField() {
        ProjectVariable v = localUserVariable();
        ProjectVariableController.VariableView view = ProjectVariableController.VariableView.of(v);

        assertEquals(v.getId(), view.id());
        assertEquals(v.getProjectId(), view.projectId());
        assertEquals(v.getName(), view.name());
        assertEquals(v.getValue(), view.value());
        assertEquals(v.getVariableGroup(), view.variableGroup());
        assertEquals(v.getResolvedValue(), view.resolvedValue());
        assertEquals(v.getType(), view.type());
        assertEquals(v.getCreatorId(), view.creatorId());
        assertEquals(v.getCreatedAt(), view.createdAt());
        assertEquals(v.getUpdatedAt(), view.updatedAt());

        Set<String> viewFields = Arrays.stream(ProjectVariableController.VariableView.class.getRecordComponents())
                .map(RecordComponent::getName)
                .collect(Collectors.toSet());
        Set<String> entityFields = Arrays.stream(ProjectVariable.class.getDeclaredFields())
                .filter(f -> !f.isSynthetic() && !Modifier.isStatic(f.getModifiers()))
                .map(Field::getName)
                .collect(Collectors.toSet());
        assertEquals(entityFields, viewFields,
                "ProjectVariable 加了新字段就要同步加进 VariableView，否则接口静默少一个字段");
    }
}
