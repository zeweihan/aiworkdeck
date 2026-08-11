package com.checkba.service;

import com.checkba.model.entity.Project;
import com.checkba.model.entity.ProjectProfileField;
import com.checkba.repository.ProjectProfileFieldRepository;
import com.checkba.repository.ProjectRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
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

        // 模拟 JPA：save 时给新实体分配自增 ID 并补 updatedAt（@UpdateTimestamp 在单元测试里不会触发）
        when(repository.save(any(ProjectProfileField.class))).thenAnswer(inv -> {
            ProjectProfileField f = inv.getArgument(0);
            if (f.getId() == null) f.setId(100L);
            if (f.getUpdatedAt() == null) f.setUpdatedAt(LocalDateTime.of(2026, 8, 8, 10, 11, 12));
            return f;
        });
        when(repository.findByProjectIdAndFieldKey(anyLong(), anyString())).thenReturn(Optional.empty());
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

    @Test
    void 白名单外字段混入不影响五条固定顺序() {
        when(repository.findByProjectId(42L)).thenReturn(List.of(
                row("client", "北京某某科技有限公司", "user"),
                row("bogusKey", "不在白名单里的脏数据", "ai")));

        List<Map<String, Object>> fields = service.getProfile(42L);

        assertEquals(5, fields.size());
        assertEquals(List.of("client", "matterType", "openedAt", "nextStep", "counterparty"),
                fields.stream().map(f -> f.get("fieldKey")).toList());

        assertEquals("北京某某科技有限公司", fields.get(0).get("fieldValue"));
        // 白名单外的行不会挤占任何位置，其余四个仍是未填态（openedAt 除外，
        // 它会回落建档时间——这里没有可比对的固定值，只断言没有被串位成"不在白名单里的脏数据"）
        assertNull(fields.get(1).get("fieldValue"));
        assertNotEquals("不在白名单里的脏数据", fields.get(2).get("fieldValue"));
        assertNull(fields.get(3).get("fieldValue"));
        assertNull(fields.get(4).get("fieldValue"));
    }

    @Test
    void 同一fieldKey两行时后到的行覆盖前一行() {
        // 正常情况下库里 (project_id, field_key) 唯一约束挡着，这种数据形状进不来；
        // 这个用例守的是服务层自身的防御行为：万一出现重复，语义是什么。
        // 实现用 HashMap.put 按 fieldKey 建索引（ProjectProfileService.getProfile），
        // 遍历仓储返回的列表时后出现的行会覆盖先出现的行。
        when(repository.findByProjectId(42L)).thenReturn(List.of(
                row("client", "旧值-第一行", "ai"),
                row("client", "新值-第二行", "user")));

        Map<String, Object> client = service.getProfile(42L).get(0);

        assertEquals("新值-第二行", client.get("fieldValue"));
        assertEquals("user", client.get("source"));
    }

    @Test
    void 手填新字段落新行并锁成user() {
        Map<String, Object> saved = service.saveUserField(42L, "client", "  北京某某科技有限公司  ");

        ArgumentCaptor<ProjectProfileField> captor = ArgumentCaptor.forClass(ProjectProfileField.class);
        verify(repository).save(captor.capture());
        ProjectProfileField row = captor.getValue();
        assertEquals(42L, row.getProjectId());
        assertEquals("client", row.getFieldKey());
        assertEquals("北京某某科技有限公司", row.getFieldValue(), "两端空白要 trim");
        assertEquals("user", row.getSource());
        assertNull(row.getConfidence());
        assertNull(row.getEvidence());
        assertNotNull(row.getUid(), "新建行必须生成 uid——跨机器身份只认它");
        assertEquals(36, row.getUid().length());

        assertEquals("client", saved.get("fieldKey"));
        assertEquals("客户", saved.get("label"));
        assertEquals("北京某某科技有限公司", saved.get("fieldValue"));
        assertEquals("user", saved.get("source"));
        assertEquals("2026-08-08T10:11:12", saved.get("updatedAt"));
    }

    @Test
    void 改既有字段时uid不变() {
        ProjectProfileField existing = row("client", "旧客户", "ai");
        existing.setUid("uid-keep-me");
        existing.setConfidence(0.7);
        existing.setEvidence("从某份文件推断");
        when(repository.findByProjectIdAndFieldKey(42L, "client")).thenReturn(Optional.of(existing));

        service.saveUserField(42L, "client", "新客户");

        assertEquals("uid-keep-me", existing.getUid());
        assertEquals("新客户", existing.getFieldValue());
        assertEquals("user", existing.getSource(), "律师改过就锁成 user");
        assertNull(existing.getConfidence(), "改成手填后 AI 的置信度要清掉");
        assertNull(existing.getEvidence(), "改成手填后 AI 的证据要清掉");
        verify(repository).save(existing);
    }

    @Test
    void 并发撞车时按已存在处理不抛异常() {
        // 两个请求都查到空 Optional、都走插入：第一次 save 撞 (projectId, fieldKey) 唯一约束，
        // 恢复路径重新查到那一行（已存在），走更新语义、保留它的 uid。
        ProjectProfileField existing = row("client", "旧客户", "user");
        when(repository.findByProjectIdAndFieldKey(42L, "client"))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(existing));
        when(repository.save(any(ProjectProfileField.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate key"))
                .thenAnswer(inv -> inv.getArgument(0));

        Map<String, Object> result = assertDoesNotThrow(
                () -> service.saveUserField(42L, "client", "新客户"));

        assertEquals("新客户", result.get("fieldValue"));
        assertEquals("user", result.get("source"));
        assertEquals("uid-1", existing.getUid(), "uid 用既存那行的，不是抢插失败那次生成的");
        verify(repository, times(2)).save(any(ProjectProfileField.class));
    }

    @Test
    void 传空串删除该行() {
        ProjectProfileField existing = row("nextStep", "下周一交初稿", "user");
        when(repository.findByProjectIdAndFieldKey(42L, "nextStep")).thenReturn(Optional.of(existing));

        Map<String, Object> result = service.saveUserField(42L, "nextStep", "   ");

        verify(repository).delete(existing);
        verify(repository, never()).save(any(ProjectProfileField.class));
        assertNull(result.get("fieldValue"));
        assertNull(result.get("source"));
    }

    @Test
    void 删除openedAt后回落建档时间() {
        ProjectProfileField existing = row("openedAt", "2026-07-15", "user");
        when(repository.findByProjectIdAndFieldKey(42L, "openedAt")).thenReturn(Optional.of(existing));

        Map<String, Object> result = service.saveUserField(42L, "openedAt", null);

        verify(repository).delete(existing);
        assertEquals("2026-08-01", result.get("fieldValue"));
        assertEquals("default", result.get("source"));
    }

    @Test
    void 未知字段名被拒() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.saveUserField(42L, "secretColumn", "随便"));
        assertEquals("未知的档案字段", e.getMessage());
        verify(repository, never()).save(any(ProjectProfileField.class));
    }
}
