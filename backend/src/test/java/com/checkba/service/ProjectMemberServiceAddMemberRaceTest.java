package com.checkba.service;

import com.checkba.model.entity.Project;
import com.checkba.model.entity.User;
import com.checkba.repository.ProjectInvitationRepository;
import com.checkba.repository.ProjectMemberRepository;
import com.checkba.repository.ProjectRepository;
import com.checkba.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * addMember 的重复校验是「先查后插」：两个并发请求都查到「不在项目中」就都会插入，
 * 第二个撞上 project_member(project_id, user_id) 唯一约束抛
 * DataIntegrityViolationException——未被 GlobalExceptionHandler 任何具名
 * {@code @ExceptionHandler} 接住，落进通用兜底回一句和输入毫不相关的「服务器内部错误」，
 * 而不是查重分支本该给出的「用户已在项目中」。
 *
 * <p>addMember 整体是 {@code @Transactional}：修法只把插入异常翻译成同一条
 * IllegalArgumentException 后立即向外抛、不在同一事务里再发任何查询——插入失败后
 * 部分数据库（如 Postgres）的当前事务已经不可再用，追加恢复性查询本身会再报错。
 */
@ExtendWith(MockitoExtension.class)
class ProjectMemberServiceAddMemberRaceTest {

    private static final Long PROJECT_ID = 1L;
    private static final Long OWNER_ID = 10L;
    private static final Long TARGET_USER_ID = 20L;

    @Mock private ProjectMemberRepository projectMemberRepository;
    @Mock private UserRepository userRepository;
    @Mock private ProjectRepository projectRepository;
    @Mock private ProjectInvitationRepository invitationRepository;

    private ProjectMemberService service;

    @BeforeEach
    void setUp() {
        service = new ProjectMemberService(
                projectMemberRepository, userRepository, projectRepository, invitationRepository);
    }

    @Test
    @DisplayName("插入撞上并发唯一约束时，报「用户已在项目中」而不是「服务器内部错误」")
    void insertRaceSurfacesFriendlyDuplicateMessage() {
        Project project = new Project();
        project.setId(PROJECT_ID);
        project.setUserId(OWNER_ID); // 请求者是项目所有者，直接过权限检查
        when(projectRepository.findById(PROJECT_ID)).thenReturn(Optional.of(project));

        User target = new User();
        target.setId(TARGET_USER_ID);
        target.setUsername("newmember");
        when(userRepository.findByUsername("newmember")).thenReturn(Optional.of(target));

        // 查重时（先查后插的"查"）还没有——两个并发请求都会看到这个状态
        when(projectMemberRepository.findByProjectIdAndUserId(PROJECT_ID, TARGET_USER_ID))
                .thenReturn(Optional.empty());
        // 插入时撞上另一个并发请求已经提交的同一行
        when(projectMemberRepository.save(any()))
                .thenThrow(new DataIntegrityViolationException("duplicate key"));

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.addMember(PROJECT_ID, "newmember", "MEMBER", OWNER_ID));

        assertEquals("用户已在项目中", e.getMessage(),
                "应该是查重分支本该给出的提示，而不是唯一约束异常原样冒泡");
    }
}
