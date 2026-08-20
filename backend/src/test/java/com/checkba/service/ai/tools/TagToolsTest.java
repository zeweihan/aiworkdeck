package com.checkba.service.ai.tools;

import com.checkba.model.entity.ProjectFile;
import com.checkba.model.entity.Tag;
import com.checkba.repository.ProjectFileRepository;
import com.checkba.service.FileTagService;
import com.checkba.service.TagService;
import com.checkba.service.ai.context.ProjectContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * tag_list / tag_file / tag_remove_from_file（dev-board #63）单测。
 *
 * TagService/FileTagService/ProjectFileRepository 均为 mock：本类只验证 TagTools 这一层的
 * 契约——fileId 归属校验如何转成拒绝文案、白名单异常如何回喂、同名不同型时如何说明「未改型」、
 * 幂等挂标签不因重复调用报错、tag_list 按类型分组渲染。TagService 内部的白名单校验与
 * getOrCreateTag 复用-不改型行为由 TagServiceTest 覆盖。
 *
 * ToolFileGuard 依赖 ProjectContextHolder 这个 ThreadLocal（生产由 ToolRegistry 在分发前
 * 设置），这里手动 set/clear 模拟。
 */
class TagToolsTest {

    private TagService tagService;
    private FileTagService fileTagService;
    private ProjectFileRepository projectFileRepository;
    private TagTools tools;

    private static final Long PROJECT_ID = 1L;
    private static final Long OTHER_PROJECT_ID = 2L;
    private static final Long USER_ID = 10L;

    @BeforeEach
    void setUp() {
        tagService = mock(TagService.class);
        fileTagService = mock(FileTagService.class);
        projectFileRepository = mock(ProjectFileRepository.class);
        tools = new TagTools(tagService, fileTagService, projectFileRepository);
        ProjectContextHolder.setProjectId(String.valueOf(PROJECT_ID));
    }

    @AfterEach
    void tearDown() {
        ProjectContextHolder.clear();
    }

    private ProjectFile file(Long id, Long projectId) {
        ProjectFile f = new ProjectFile();
        f.setId(id);
        f.setProjectId(projectId);
        return f;
    }

    private Tag tag(Long id, String name, String type, String color) {
        Tag t = new Tag();
        t.setId(id);
        t.setName(name);
        t.setType(type);
        t.setColor(color);
        return t;
    }

    @Test
    @DisplayName("tag_file：新建标签（带 type 与默认色），挂到文件")
    void tagFileCreatesNewTag() {
        when(projectFileRepository.findById(100L)).thenReturn(Optional.of(file(100L, PROJECT_ID)));
        when(tagService.getOrCreateTag(PROJECT_ID, "张三", "PARTY", null))
                .thenReturn(tag(5L, "张三", "PARTY", "#B45309"));

        String out = tools.tag_file(100L, "张三", "PARTY", PROJECT_ID, USER_ID);

        assertTrue(out.contains("张三"), out);
        assertFalse(out.contains("实际类型"), out);
        verify(fileTagService).addTagToFile(100L, 5L, USER_ID);
    }

    @Test
    @DisplayName("tag_file：type 缺省时按 NORMAL 处理")
    void tagFileDefaultsToNormal() {
        when(projectFileRepository.findById(100L)).thenReturn(Optional.of(file(100L, PROJECT_ID)));
        when(tagService.getOrCreateTag(PROJECT_ID, "证据一", "NORMAL", null))
                .thenReturn(tag(6L, "证据一", null, "#3B82F6"));

        String out = tools.tag_file(100L, "证据一", null, PROJECT_ID, USER_ID);

        assertTrue(out.contains("证据一"), out);
        verify(tagService).getOrCreateTag(PROJECT_ID, "证据一", "NORMAL", null);
    }

    @Test
    @DisplayName("tag_file：撞上同名不同型的既有标签——复用不改型，返回文本说明实际类型")
    void tagFileReusesExistingTagWithDifferentType() {
        when(projectFileRepository.findById(100L)).thenReturn(Optional.of(file(100L, PROJECT_ID)));
        // 请求 PARTY，但项目里"李四"已经是普通标签——service 层不改型，原样返回既有标签
        when(tagService.getOrCreateTag(PROJECT_ID, "李四", "PARTY", null))
                .thenReturn(tag(7L, "李四", "NORMAL", "#3B82F6"));

        String out = tools.tag_file(100L, "李四", "PARTY", PROJECT_ID, USER_ID);

        assertTrue(out.contains("李四"), out);
        assertTrue(out.contains("实际类型"), out);
        assertTrue(out.contains("NORMAL"), out);
        verify(fileTagService).addTagToFile(100L, 7L, USER_ID);
    }

    @Test
    @DisplayName("tag_file：重复挂同一标签——幂等，两次调用都返回成功文案")
    void tagFileIsIdempotent() {
        when(projectFileRepository.findById(100L)).thenReturn(Optional.of(file(100L, PROJECT_ID)));
        when(tagService.getOrCreateTag(PROJECT_ID, "张三", "PARTY", null))
                .thenReturn(tag(5L, "张三", "PARTY", "#B45309"));

        String first = tools.tag_file(100L, "张三", "PARTY", PROJECT_ID, USER_ID);
        String second = tools.tag_file(100L, "张三", "PARTY", PROJECT_ID, USER_ID);

        assertFalse(first.startsWith("错误"), first);
        assertFalse(second.startsWith("错误"), second);
        verify(fileTagService, times(2)).addTagToFile(100L, 5L, USER_ID);
    }

    @Test
    @DisplayName("tag_file：非法 type——service 抛出的 IllegalArgumentException 转成可行动错误文案")
    void tagFileRejectsIllegalType() {
        when(projectFileRepository.findById(100L)).thenReturn(Optional.of(file(100L, PROJECT_ID)));
        when(tagService.getOrCreateTag(eq(PROJECT_ID), eq("张三"), eq("BOGUS"), any()))
                .thenThrow(new IllegalArgumentException("非法的标签类型：BOGUS"));

        String out = tools.tag_file(100L, "张三", "BOGUS", PROJECT_ID, USER_ID);

        assertTrue(out.startsWith("错误"), out);
        assertTrue(out.contains("非法的标签类型"), out);
        verify(fileTagService, never()).addTagToFile(any(), any(), any());
    }

    @Test
    @DisplayName("tag_file：文件不属于当前项目——拒绝，不调用 service")
    void tagFileRejectsFileFromAnotherProject() {
        when(projectFileRepository.findById(100L)).thenReturn(Optional.of(file(100L, OTHER_PROJECT_ID)));

        String out = tools.tag_file(100L, "张三", "PARTY", PROJECT_ID, USER_ID);

        assertFalse(out.isBlank());
        verify(tagService, never()).getOrCreateTag(any(), any(), any(), any());
        verify(fileTagService, never()).addTagToFile(any(), any(), any());
    }

    @Test
    @DisplayName("tag_file：文件不存在——返回明确错误")
    void tagFileRejectsMissingFile() {
        when(projectFileRepository.findById(100L)).thenReturn(Optional.empty());

        String out = tools.tag_file(100L, "张三", "PARTY", PROJECT_ID, USER_ID);

        assertTrue(out.startsWith("错误"), out);
    }

    @Test
    @DisplayName("tag_remove_from_file：标签存在且已挂——移除成功")
    void removeSucceeds() {
        when(projectFileRepository.findById(100L)).thenReturn(Optional.of(file(100L, PROJECT_ID)));
        Tag t = tag(5L, "张三", "PARTY", "#B45309");
        when(tagService.getProjectTags(PROJECT_ID)).thenReturn(List.of(t));
        when(fileTagService.getTagsByFileId(100L)).thenReturn(List.of(t));

        String out = tools.tag_remove_from_file(100L, "张三", PROJECT_ID);

        assertTrue(out.contains("张三"), out);
        verify(fileTagService).removeTagFromFile(100L, 5L);
    }

    @Test
    @DisplayName("tag_remove_from_file：标签不存在——返回说明性文本，不抛错")
    void removeMissingTagReturnsExplanation() {
        when(projectFileRepository.findById(100L)).thenReturn(Optional.of(file(100L, PROJECT_ID)));
        when(tagService.getProjectTags(PROJECT_ID)).thenReturn(List.of());

        String out = tools.tag_remove_from_file(100L, "不存在的标签", PROJECT_ID);

        assertFalse(out.isBlank());
        assertFalse(out.startsWith("错误"), out);
        verify(fileTagService, never()).removeTagFromFile(any(), any());
    }

    @Test
    @DisplayName("tag_remove_from_file：标签存在但文件本来没挂——返回说明性文本，不抛错")
    void removeUnattachedTagReturnsExplanation() {
        when(projectFileRepository.findById(100L)).thenReturn(Optional.of(file(100L, PROJECT_ID)));
        Tag t = tag(5L, "张三", "PARTY", "#B45309");
        when(tagService.getProjectTags(PROJECT_ID)).thenReturn(List.of(t));
        when(fileTagService.getTagsByFileId(100L)).thenReturn(List.of());

        String out = tools.tag_remove_from_file(100L, "张三", PROJECT_ID);

        assertFalse(out.isBlank());
        assertFalse(out.startsWith("错误"), out);
        verify(fileTagService, never()).removeTagFromFile(any(), any());
    }

    @Test
    @DisplayName("tag_list：按类型分组渲染当事人/争议焦点/普通标签三段")
    void tagListGroupsByType() {
        when(tagService.getProjectTags(PROJECT_ID)).thenReturn(List.of(
                tag(1L, "张三", "PARTY", "#B45309"),
                tag(2L, "李四", "PARTY", "#B45309"),
                tag(3L, "违约责任", "ISSUE", "#9B1C31"),
                tag(4L, "证据一", "NORMAL", "#3B82F6"),
                tag(5L, "证据二", null, "#3B82F6")
        ));

        String out = tools.tag_list(PROJECT_ID);

        assertTrue(out.contains("张三"), out);
        assertTrue(out.contains("李四"), out);
        assertTrue(out.contains("违约责任"), out);
        assertTrue(out.contains("证据一"), out);
        assertTrue(out.contains("证据二"), out);
        assertTrue(out.contains("当事人"), out);
        assertTrue(out.contains("争议焦点"), out);
        assertTrue(out.contains("普通标签"), out);
    }

    @Test
    @DisplayName("tag_list：空标签清单返回明确文案，不是空字符串")
    void tagListEmptyReturnsExplicitText() {
        when(tagService.getProjectTags(PROJECT_ID)).thenReturn(List.of());

        String out = tools.tag_list(PROJECT_ID);

        assertFalse(out.isBlank(), "空工具输出会掀翻整轮对话，必须给明确文案");
    }

    @Test
    @DisplayName("tag_list：projectId 缺失时返回可行动错误，不调用 service")
    void tagListRejectsMissingProjectId() {
        String out = tools.tag_list(null);

        assertTrue(out.startsWith("错误"), out);
        verify(tagService, never()).getProjectTags(any());
    }
}
