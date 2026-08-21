package com.checkba.service;

import com.checkba.model.entity.ProjectVariable;
import com.checkba.repository.ProjectVariableRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * createOrUpdateVariable 的插入分支是「先查后插」：两个并发请求都查到「不存在」就都会
 * 走插入分支，第二个撞上 project_variables(project_id, name) 唯一约束抛
 * DataIntegrityViolationException——这本该被当成一次更新（两边语义上都是「给这个变量
 * 设这个值」，没有歧义），却会冒泡成一句和输入毫不相关的「服务器内部错误」。
 */
@ExtendWith(MockitoExtension.class)
class ProjectVariableServiceConcurrentCreateTest {

    @Mock
    private ProjectVariableRepository repository;

    private ProjectVariableService service;

    @BeforeEach
    void setUp() {
        service = new ProjectVariableService();
        ReflectionTestUtils.setField(service, "repository", repository);
        // createOrUpdateVariable 现在经 self 转发到 REQUIRES_NEW 的 createOrUpdateVariableTx
        // （同批另一条修复）。纯 Mockito 测试里没有 Spring 代理，把 self 指回自己：
        // 事务语义在这条用例里本来就测不到（它测的是「撞约束后回退成更新」这条行为），
        // 不接上 self 的话调用直接 NPE。
        ReflectionTestUtils.setField(service, "self", service);
    }

    private static ProjectVariable variable(Long id, Long projectId, String name, String value) {
        ProjectVariable v = new ProjectVariable();
        v.setId(id);
        v.setProjectId(projectId);
        v.setName(name);
        v.setValue(value);
        v.setType("TEXT");
        return v;
    }

    @Test
    @DisplayName("插入撞上并发唯一约束时退回更新，而不是把异常甩给调用方")
    void insertRaceFallsBackToUpdateInsteadOfThrowing() {
        ProjectVariable incoming = variable(null, 1L, "案号", "新值");

        // 第一次查：两边并发请求都看到「不存在」；冲突之后重新查：另一边已经提交
        ProjectVariable alreadyCommitted = variable(99L, 1L, "案号", "旧值");
        when(repository.findByProjectIdAndName(1L, "案号"))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(alreadyCommitted));
        // 单条 any() 桩，在 thenAnswer 里按参数状态分流——避免同一方法挂两条 argThat
        // 桩时，Mockito 用 null 占位符重放已注册的匹配器，把测试自己先炸了。
        when(repository.save(any(ProjectVariable.class))).thenAnswer(inv -> {
            ProjectVariable v = inv.getArgument(0);
            if (v.getId() == null) {
                throw new DataIntegrityViolationException("duplicate key");
            }
            return v;
        });

        ProjectVariable result = service.createOrUpdateVariable(incoming);

        assertEquals(99L, result.getId(), "应该落到已经存在的那一行，而不是继续尝试插入新行");
        assertEquals("新值", result.getValue(), "值仍要按本次请求更新");
        verify(repository, times(2)).findByProjectIdAndName(1L, "案号");
    }
}
