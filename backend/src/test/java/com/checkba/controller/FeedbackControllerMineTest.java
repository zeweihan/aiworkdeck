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

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * {@code GET /api/feedback/mine}：只回当前 session 解析出的用户自己的行，不需要管理员，
 * 也不许把 lastError 这种内部字段带出去（对照组：{@link #list}/{@link #detail} 才要管理员，
 * 那是在看别人的现场；这个只让人看自己报过什么）。
 */
@ExtendWith(MockitoExtension.class)
class FeedbackControllerMineTest {

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

    private static UserFeedback row(long id, Long userId, String status, boolean uploaded) {
        UserFeedback fb = new UserFeedback();
        fb.setId(id);
        fb.setUserId(userId);
        fb.setSource(UserFeedback.SOURCE_LOCAL);
        fb.setKind(UserFeedback.KIND_BUG);
        fb.setText("点保存没反应");
        fb.setStatus(status);
        fb.setUploaded(uploaded);
        fb.setLastError("optimizer 内部错误详情，用户不该看到");
        fb.setCreatedAt(LocalDateTime.of(2026, 8, 9, 9, 0));
        return fb;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> data(ResponseEntity<?> resp) {
        return (Map<String, Object>) ((Map<String, Object>) resp.getBody()).get("data");
    }

    @Test
    void requiresSession() {
        try (MockedStatic<AuthController> auth = mockStatic(AuthController.class)) {
            auth.when(() -> AuthController.getUserIdFromSession(null)).thenReturn(null);
            ResponseEntity<?> resp = controller.mine(null);
            assertEquals(HttpStatus.UNAUTHORIZED, resp.getStatusCode());
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void returnsOwnRowsWithoutLastErrorAndWithUploadedFlag() {
        when(feedbackService.listByUser(7L, 100)).thenReturn(List.of(row(3L, 7L, UserFeedback.STATUS_NEW, true)));
        when(userRepository.findById(7L)).thenReturn(Optional.empty());

        try (MockedStatic<AuthController> auth = mockStatic(AuthController.class)) {
            auth.when(() -> AuthController.getUserIdFromSession("sess")).thenReturn(7L);
            ResponseEntity<?> resp = controller.mine("sess");

            assertEquals(200, resp.getStatusCode().value());
            List<Map<String, Object>> items = (List<Map<String, Object>>) data(resp).get("items");
            assertEquals(1, items.size());
            Map<String, Object> item = items.get(0);
            assertEquals(3L, item.get("id"));
            assertEquals(Boolean.TRUE, item.get("uploaded"));
            assertFalse(item.containsKey("lastError"), "内部字段不许带给用户");
        }
    }

    @Test
    void neverAsksTheRepositoryForOtherUsersRows() {
        // 控制器只应该经由 feedbackService.listByUser(userId, ...) 取数，
        // 不该自己拼一个不带 userId 过滤的查询——这是「只看自己」这条契约的落点。
        when(feedbackService.listByUser(7L, 100)).thenReturn(List.of());

        try (MockedStatic<AuthController> auth = mockStatic(AuthController.class)) {
            auth.when(() -> AuthController.getUserIdFromSession("sess")).thenReturn(7L);
            controller.mine("sess");
        }

        org.mockito.Mockito.verify(feedbackService).listByUser(7L, 100);
        org.mockito.Mockito.verify(feedbackService, org.mockito.Mockito.never()).list(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyInt());
    }
}
