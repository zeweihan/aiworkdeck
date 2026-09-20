// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.service.ai;

import com.checkba.model.entity.ProjectFile;
import com.checkba.repository.ProjectFileRepository;
import com.checkba.service.SystemSettingService;
import com.checkba.storage.StorageService;
import com.checkba.storage.StorageServiceFactory;
import com.checkba.util.style.StyleProfiles;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 模板画像事实直接告诉模型（dev-board#729 ②）。
 *
 * <p>病灶：{@code doc_apply_style_profile} 的描述写「项目有模板画像时用本工具」，而
 * 「有没有画像」模型<b>无法自行判断</b>——真机上它会先花一整轮去 {@code list_files(_模板)}
 * 探一探。那一轮的墙钟中位 80 秒，91% 花在推理上，纯属白烧。服务端本来就知道答案。
 */
class TemplateProfileFactTest {

    private static final String PROFILE_JSON = "{\"schemaVersion\":1,\"body\":{\"fontSize\":11}}";

    private StyleProfileResolver resolverWithProjectProfile() throws Exception {
        ProjectFileRepository repo = mock(ProjectFileRepository.class);
        ProjectFile folder = new ProjectFile();
        folder.setId(9L);
        folder.setName(StyleProfileResolver.TEMPLATE_FOLDER);
        folder.setIsFolder(true);
        ProjectFile profile = new ProjectFile();
        profile.setId(10L);
        profile.setName(StyleProfileResolver.PROFILE_FILE);
        profile.setFilePath("p/1/_模板/画像.json");
        when(repo.findByProjectIdAndParentIdOrderBySortOrderAsc(eq(1L), isNull()))
                .thenReturn(List.of(folder));
        when(repo.findByProjectIdAndParentIdOrderBySortOrderAsc(eq(1L), eq(9L)))
                .thenReturn(List.of(profile));

        StorageService storage = mock(StorageService.class);
        when(storage.load(any())).thenReturn(
                new ByteArrayResource(PROFILE_JSON.getBytes(StandardCharsets.UTF_8)));
        StorageServiceFactory factory = mock(StorageServiceFactory.class);
        when(factory.getStorageService()).thenReturn(storage);

        return new StyleProfileResolver(repo, factory, mock(SystemSettingService.class));
    }

    private StyleProfileResolver resolverWithNothing() {
        ProjectFileRepository repo = mock(ProjectFileRepository.class);
        when(repo.findByProjectIdAndParentIdOrderBySortOrderAsc(any(), any())).thenReturn(List.of());
        StorageServiceFactory factory = mock(StorageServiceFactory.class);
        SystemSettingService settings = mock(SystemSettingService.class);
        when(settings.get(any(), any())).thenReturn(null);
        return new StyleProfileResolver(repo, factory, settings);
    }

    @Test
    @DisplayName("有项目画像：resolveWithSource 报 PROJECT，且与 resolve 返回同一份画像")
    void projectProfileIsReportedAsItsOwnSource() throws Exception {
        StyleProfileResolver resolver = resolverWithProjectProfile();
        StyleProfileResolver.Resolved resolved = resolver.resolveWithSource(1L, null);

        assertEquals(StyleProfileResolver.Source.PROJECT, resolved.source());
        assertTrue(resolved.source().hasCustomProfile());
        // 解析链只此一份：resolve() 必须就是 resolveWithSource().profile()，
        // 否则「提醒说有画像」与「写端实际用了什么」会各说各话，两边都不报错
        assertEquals(StyleProfiles.toJson(resolved.profile()),
                StyleProfiles.toJson(resolver.resolve(1L, null)));
    }

    @Test
    @DisplayName("什么都没有：报 HOUSE_DEFAULT，hasCustomProfile=false")
    void houseDefaultIsNotACustomProfile() {
        StyleProfileResolver.Resolved resolved = resolverWithNothing().resolveWithSource(1L, null);
        assertEquals(StyleProfileResolver.Source.HOUSE_DEFAULT, resolved.source());
        assertTrue(!resolved.source().hasCustomProfile());
    }

    @Test
    @DisplayName("显式 JSON 优先级最高，报 EXPLICIT")
    void explicitJsonWins() throws Exception {
        StyleProfileResolver resolver = resolverWithProjectProfile();
        assertEquals(StyleProfileResolver.Source.EXPLICIT,
                resolver.resolveWithSource(1L, PROFILE_JSON).source());
    }

    @Test
    @DisplayName("工具描述不再要求模型自己判断有没有画像，并明确禁止去翻模板文件夹")
    void toolDescriptionsStopAskingTheModelToGuess() {
        String applyProfile = toolDescription("doc_apply_style_profile");
        assertTrue(applyProfile.contains("系统提醒"),
                "必须指向末位提醒里那条事实，实际: " + applyProfile);
        assertTrue(applyProfile.contains("list_files"),
                "必须明说不要去 list_files 翻模板文件夹，实际: " + applyProfile);

        String standard = toolDescription("doc_apply_standard_format");
        assertTrue(standard.contains("模板画像：无"),
                "没有画像时的落点工具也要接上那条事实，实际: " + standard);
    }

    /** 从真实 @Tool 注解上取描述（与下发给模型的是同一份文本）。 */
    private static String toolDescription(String toolName) {
        for (java.lang.reflect.Method m : com.checkba.service.ai.tools.DocumentEditTools.class
                .getDeclaredMethods()) {
            if (!m.getName().equals(toolName)) continue;
            dev.langchain4j.agent.tool.Tool tool =
                    m.getAnnotation(dev.langchain4j.agent.tool.Tool.class);
            if (tool != null) return String.join(" ", tool.value());
        }
        throw new AssertionError("找不到工具 " + toolName);
    }
}
