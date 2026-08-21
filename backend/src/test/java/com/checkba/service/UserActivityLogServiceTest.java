package com.checkba.service;

import com.checkba.model.entity.Project;
import com.checkba.model.entity.UserActivityLog;
import com.checkba.repository.ProjectRepository;
import com.checkba.repository.UserActivityLogRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * logActivity 落库带 projectId；getUserLogs 按 projectId 批量补 projectName，
 * 老数据（无 projectId）与项目已删除（IN 查不到）都不炸、都留空。
 */
@ExtendWith(MockitoExtension.class)
class UserActivityLogServiceTest {

    private static final Long USER = 1L;

    @Mock private UserActivityLogRepository repository;
    @Mock private ProjectRepository projectRepository;

    @InjectMocks private UserActivityLogService service;

    private Project project(long id, String name) {
        Project p = new Project();
        p.setId(id);
        p.setName(name);
        return p;
    }

    private UserActivityLog log(Long projectId) {
        UserActivityLog l = new UserActivityLog();
        l.setUserId(USER);
        l.setProjectId(projectId);
        return l;
    }

    @Test
    void logActivityPersistsProjectId() {
        service.logActivity(USER, "OPEN_FILE", 10L, "合同.docx", 5000L, "meta", 88L);

        ArgumentCaptor<UserActivityLog> captor = ArgumentCaptor.forClass(UserActivityLog.class);
        verify(repository).save(captor.capture());
        assertEquals(88L, captor.getValue().getProjectId());
    }

    @Test
    void logActivityAllowsNullProjectId() {
        service.logActivity(USER, "OPEN_FILE", 10L, "合同.docx", 5000L, "meta", null);

        ArgumentCaptor<UserActivityLog> captor = ArgumentCaptor.forClass(UserActivityLog.class);
        verify(repository).save(captor.capture());
        assertNull(captor.getValue().getProjectId());
    }

    @Test
    void getUserLogsFillsProjectNameByBatchQuery() {
        UserActivityLog withProject = log(5L);
        when(repository.findTop500ByUserIdOrderByTimestampDesc(USER))
                .thenReturn(Arrays.asList(withProject));
        when(projectRepository.findAllById(anyList()))
                .thenReturn(Arrays.asList(project(5L, "并购项目")));

        List<UserActivityLog> logs = service.getUserLogs(USER);

        assertEquals("并购项目", logs.get(0).getProjectName());
    }

    @Test
    void getUserLogsLeavesNameNullWhenProjectIdMissingOrDeleted() {
        UserActivityLog legacy = log(null); // 老数据，从未写过 projectId
        UserActivityLog deletedProject = log(999L); // 项目已删除，IN 查不到

        when(repository.findTop500ByUserIdOrderByTimestampDesc(USER))
                .thenReturn(Arrays.asList(legacy, deletedProject));
        when(projectRepository.findAllById(anyList()))
                .thenReturn(Collections.emptyList());

        List<UserActivityLog> logs = service.getUserLogs(USER);

        assertNull(logs.get(0).getProjectName());
        assertNull(logs.get(1).getProjectName());
    }

    @Test
    void getUserLogsSkipsProjectQueryWhenNoProjectIds() {
        when(repository.findTop500ByUserIdOrderByTimestampDesc(USER))
                .thenReturn(Arrays.asList(log(null)));

        service.getUserLogs(USER);

        verify(projectRepository, org.mockito.Mockito.never()).findAllById(any());
    }

    // ==== 修复：user_activity_log 此前没有任何 TTL/归档/@Scheduled 清理，行数随全量历史
    // 使用单调增长（见类头注释）。补一个按保留窗口清理的定时任务，与同一份代码里
    // UserSessionService.purgeExpired() 的写法一致。

    @Test
    void purgeOldDeletesRowsOlderThanRetentionWindow() {
        when(repository.deleteByTimestampBefore(any())).thenReturn(3L);

        service.purgeOld();

        org.mockito.ArgumentCaptor<java.time.LocalDateTime> captor =
                org.mockito.ArgumentCaptor.forClass(java.time.LocalDateTime.class);
        verify(repository).deleteByTimestampBefore(captor.capture());
        long daysBeforeNow = java.time.Duration.between(captor.getValue(), java.time.LocalDateTime.now()).toDays();
        assertTrue(Math.abs(daysBeforeNow - UserActivityLogService.RETENTION_DAYS) <= 1,
                "清理截止时间应约为「现在减保留天数」，实际相差 " + daysBeforeNow + " 天");
    }
}
