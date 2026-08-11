package com.checkba.service;

import com.checkba.model.entity.Project;
import com.checkba.model.entity.ProjectProfileField;
import com.checkba.repository.ProjectProfileFieldRepository;
import com.checkba.repository.ProjectRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

/**
 * 项目档案服务。装配风格对齐 ProjectAiMessageServiceTest:35-55：手工 mock + 手工 new，
 * 不用 MockitoExtension（避免严格 stub 检查在多测试方法共用 setUp 时误报）。
 */
class ProjectProfileServiceTest {

    private ProjectProfileFieldRepository repository;
    private ProjectRepository projectRepository;
    private ProjectProfileService service;

    /** 项目 42 的建档时间 = 2026-08-01 09:30，openedAt 未填时应回落到 2026-08-01 */
    private static final LocalDateTime PROJECT_CREATED_AT = LocalDateTime.of(2026, 8, 1, 9, 30, 0);

    @BeforeEach
    void setUp() {
        repository = mock(ProjectProfileFieldRepository.class);
        projectRepository = mock(ProjectRepository.class);
        service = new ProjectProfileService(repository, projectRepository);

        Project project = new Project();
        project.setId(42L);
        project.setCreatedAt(PROJECT_CREATED_AT);
        when(projectRepository.findById(42L)).thenReturn(Optional.of(project));
        when(repository.findByProjectId(anyLong())).thenReturn(List.of());
    }

    private ProjectProfileField row(String fieldKey, String value, String source) {
        ProjectProfileField f = new ProjectProfileField();
        f.setId(1L);
        f.setProjectId(42L);
        f.setFieldKey(fieldKey);
        f.setFieldValue(value);
        f.setSource(source);
        f.setUid("uid-1");
        f.setUpdatedAt(LocalDateTime.of(2026, 8, 8, 10, 11, 12));
        return f;
    }

    @Test
    void 空档案也返回五条且顺序固定() {
        List<Map<String, Object>> fields = service.getProfile(42L);

        assertEquals(5, fields.size());
        assertEquals(List.of("client", "matterType", "openedAt", "nextStep", "counterparty"),
                fields.stream().map(f -> f.get("fieldKey")).toList());
        assertEquals(List.of("客户", "事项类型", "立项时间", "下一步", "对方"),
                fields.stream().map(f -> f.get("label")).toList());

        Map<String, Object> client = fields.get(0);
        assertTrue(client.containsKey("fieldValue"), "未填的字段也要出现在数组里，值为 null");
        assertNull(client.get("fieldValue"));
        assertNull(client.get("source"));
        assertNull(client.get("confidence"));
        assertNull(client.get("evidence"));
        assertNull(client.get("updatedAt"));
    }

    @Test
    void openedAt无行时回落建档时间并标default() {
        Map<String, Object> openedAt = service.getProfile(42L).get(2);

        assertEquals("openedAt", openedAt.get("fieldKey"));
        assertEquals("2026-08-01", openedAt.get("fieldValue"));
        assertEquals("default", openedAt.get("source"));
        assertNull(openedAt.get("updatedAt"), "派生值没有更新时间");
    }

    @Test
    void 已填字段原样返回并带ISO更新时间() {
        when(repository.findByProjectId(42L)).thenReturn(List.of(
                row("client", "北京某某科技有限公司", "user"),
                row("openedAt", "2026-07-15", "ai")));

        List<Map<String, Object>> fields = service.getProfile(42L);

        Map<String, Object> client = fields.get(0);
        assertEquals("北京某某科技有限公司", client.get("fieldValue"));
        assertEquals("user", client.get("source"));
        assertEquals("2026-08-08T10:11:12", client.get("updatedAt"));

        Map<String, Object> openedAt = fields.get(2);
        assertEquals("2026-07-15", openedAt.get("fieldValue"), "有行时不再回落建档时间");
        assertEquals("ai", openedAt.get("source"));
    }

    @Test
    void 项目不存在时openedAt不回落也不抛异常() {
        when(projectRepository.findById(99L)).thenReturn(Optional.empty());
        when(repository.findByProjectId(99L)).thenReturn(List.of());

        Map<String, Object> openedAt = service.getProfile(99L).get(2);
        assertNull(openedAt.get("fieldValue"));
        assertNull(openedAt.get("source"));
    }
}
