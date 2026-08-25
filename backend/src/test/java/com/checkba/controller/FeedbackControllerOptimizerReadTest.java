package com.checkba.controller;

import com.checkba.model.entity.UserFeedback;
import com.checkba.repository.UserFeedbackRepository;
import com.checkba.repository.UserRepository;
import com.checkba.service.AdminAccessService;
import com.checkba.service.feedback.FeedbackService;
import com.checkba.service.feedback.FeedbackUploadService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * 取件密钥（X-Optimizer-Token）对 {@code GET /api/feedback} 列表与详情的只读放行
 * （dev-board#152：官网 admin 的「用户反馈」分区经服务端代理用它拉数据）。
 * 该密钥本就能取全部待办反馈与附件，这里没有升格信任级；
 * 但没配密钥 / 密钥不对时必须回落到管理员判定，不留「未配置即放行」。
 */
@ExtendWith(MockitoExtension.class)
class FeedbackControllerOptimizerReadTest {

    @Mock
    private FeedbackService feedbackService;
    @Mock
    private FeedbackUploadService uploadService;
    @Mock
    private UserFeedbackRepository feedbackRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private AdminAccessService adminAccessService;

    @InjectMocks
    private FeedbackController controller;

    @Test
    void optimizerTokenGrantsListAndDetail() {
        ReflectionTestUtils.setField(controller, "optimizerToken", "tok");
        when(feedbackService.list(null, 50)).thenReturn(List.of());
        UserFeedback fb = new UserFeedback();
        fb.setId(10L);
        when(feedbackRepository.findById(10L)).thenReturn(Optional.of(fb));
        when(feedbackService.attachmentsOf(10L)).thenReturn(List.of());

        assertEquals(200, controller.list(null, "tok", null, 50).getStatusCode().value());
        assertEquals(200, controller.detail(null, "tok", 10L).getStatusCode().value());
    }

    @Test
    void wrongOrMissingTokenFallsBackToAdminCheck() {
        ReflectionTestUtils.setField(controller, "optimizerToken", "tok");
        try (MockedStatic<AuthController> auth = mockStatic(AuthController.class)) {
            auth.when(() -> AuthController.getUserIdFromSession(null)).thenReturn(null);
            ResponseEntity<?> wrong = controller.list(null, "not-the-token", null, 50);
            assertEquals(HttpStatus.FORBIDDEN, wrong.getStatusCode());
            ResponseEntity<?> missing = controller.detail(null, null, 10L);
            assertEquals(HttpStatus.FORBIDDEN, missing.getStatusCode());
        }
    }

    @Test
    void unconfiguredTokenNeverGrants() {
        // 服务端没配 feedback.optimizer-token 时，带任何 token 都不放行（默认拒绝）
        ReflectionTestUtils.setField(controller, "optimizerToken", "");
        try (MockedStatic<AuthController> auth = mockStatic(AuthController.class)) {
            auth.when(() -> AuthController.getUserIdFromSession(null)).thenReturn(null);
            ResponseEntity<?> resp = controller.list(null, "anything", null, 50);
            assertEquals(HttpStatus.FORBIDDEN, resp.getStatusCode());
        }
    }
}
