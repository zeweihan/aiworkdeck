// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.controller;

import com.checkba.config.GlobalExceptionHandler;
import com.checkba.model.entity.ProjectFile;
import com.checkba.model.entity.ProjectFileReview;
import com.checkba.model.entity.ProjectFileReviewComment;
import com.checkba.service.ProjectFileService;
import com.checkba.service.ProjectMemberService;
import com.checkba.service.review.FileReviewService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** /api/projects/{pid}/files/{fileId}/review 的 HTTP 面（dev-board#1022）。 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class FileReviewControllerTest {

    @Mock FileReviewService svc;
    @Mock ProjectFileService projectFileService;
    @Mock ProjectMemberService projectMemberService;
    @InjectMocks FileReviewController controller;

    private MockedStatic<AuthController> auth;
    private MockMvc mvc;

    private static final String BASE = "/api/projects/1/files/77/review";

    @BeforeEach
    void setUp() {
        auth = mockStatic(AuthController.class);
        auth.when(() -> AuthController.getUserIdFromSession("s")).thenReturn(5L);
        when(projectMemberService.hasReadPermission(1L, 5L)).thenReturn(true);
        when(projectMemberService.hasWritePermission(1L, 5L)).thenReturn(true);
        when(projectMemberService.isClient(1L, 5L)).thenReturn(false);
        ProjectFile f = new ProjectFile(); f.setId(77L); f.setProjectId(1L);
        when(projectFileService.getFile(77L)).thenReturn(f);
        ProjectFile foreign = new ProjectFile(); foreign.setId(88L); foreign.setProjectId(2L);
        when(projectFileService.getFile(88L)).thenReturn(foreign);
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @AfterEach
    void tearDown() { auth.close(); }

    private static ProjectFileReview review(String status) {
        ProjectFileReview r = new ProjectFileReview();
        r.setId(9L); r.setProjectId(1L); r.setFileId(77L); r.setConversationId("conv"); r.setArtifactId("art");
        r.setStatus(status); r.setBaselineText("基线"); r.setCreatedBy(5L);
        r.setCreatedAt(LocalDateTime.of(2026, 9, 29, 10, 0)); r.setUpdatedAt(LocalDateTime.of(2026, 9, 29, 10, 0));
        return r;
    }

    @Test @DisplayName("GET 无 open 记录回 204")
    void getNoReview204() throws Exception {
        when(svc.current(77L)).thenReturn(Optional.empty());
        mvc.perform(get(BASE).header("X-Session-Id", "s")).andExpect(status().isNoContent());
    }

    @Test @DisplayName("GET 有 open 记录回快照")
    void getReturnsSnapshot() throws Exception {
        when(svc.current(77L)).thenReturn(Optional.of(review("open")));
        ProjectFileReviewComment c = new ProjectFileReviewComment();
        c.setId(31L); c.setReviewId(9L); c.setFromLine(3); c.setToLine(4); c.setQuotedText("引用"); c.setBody("评论");
        when(svc.comments(9L)).thenReturn(List.of(c));
        mvc.perform(get(BASE).header("X-Session-Id", "s"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.review.id").value(9))
                .andExpect(jsonPath("$.comments[0].id").value(31))
                .andExpect(jsonPath("$.comments[0].quotedText").value("引用"))
                .andExpect(jsonPath("$.comments[0].reviewId").doesNotExist());
    }

    @Test @DisplayName("POST 开审阅：形状含 review.status=open 与 comments=[]，参数透传")
    void postOpenShape() throws Exception {
        when(svc.open(1L, 77L, "conv", "art", "基线", 5L)).thenReturn(review("open"));
        when(svc.comments(9L)).thenReturn(List.of());
        mvc.perform(post(BASE).header("X-Session-Id", "s").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"conversationId\":\"conv\",\"artifactId\":\"art\",\"baselineText\":\"基线\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.review.id").value(9))
                .andExpect(jsonPath("$.review.fileId").value(77))
                .andExpect(jsonPath("$.review.conversationId").value("conv"))
                .andExpect(jsonPath("$.review.artifactId").value("art"))
                .andExpect(jsonPath("$.review.status").value("open"))
                .andExpect(jsonPath("$.review.baselineText").value("基线"))
                .andExpect(jsonPath("$.review.createdAt").exists())
                .andExpect(jsonPath("$.review.updatedAt").exists())
                .andExpect(jsonPath("$.comments").isArray())
                .andExpect(jsonPath("$.comments").isEmpty());
    }

    @Test @DisplayName("POST comments 缺 body 回 400，不落库")
    void postCommentMissingBody400() throws Exception {
        mvc.perform(post(BASE + "/comments").header("X-Session-Id", "s").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fromLine\":3,\"toLine\":4,\"quotedText\":\"引用\"}"))
                .andExpect(status().isBadRequest());
        verify(svc, never()).addComment(any(), any(), any(), any(), any());
    }

    @Test @DisplayName("POST comments 返回批注形状")
    void postCommentShape() throws Exception {
        ProjectFileReviewComment c = new ProjectFileReviewComment();
        c.setId(31L); c.setReviewId(9L); c.setFromLine(3); c.setToLine(4); c.setQuotedText("引用"); c.setBody("评论");
        c.setCreatedAt(LocalDateTime.of(2026, 9, 29, 10, 1));
        when(svc.addComment(77L, 3, 4, "引用", "评论")).thenReturn(c);
        mvc.perform(post(BASE + "/comments").header("X-Session-Id", "s").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fromLine\":3,\"toLine\":4,\"quotedText\":\"引用\",\"body\":\"评论\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(31))
                .andExpect(jsonPath("$.fromLine").value(3))
                .andExpect(jsonPath("$.toLine").value(4))
                .andExpect(jsonPath("$.quotedText").value("引用"))
                .andExpect(jsonPath("$.body").value("评论"))
                .andExpect(jsonPath("$.createdAt").exists());
    }

    @Test @DisplayName("没有 open 记录时加批注：服务层 IllegalStateException 映射 409")
    void noOpenReview409() throws Exception {
        when(svc.addComment(anyLong(), any(), any(), any(), any())).thenThrow(new FileReviewService.NoOpenReviewException());
        mvc.perform(post(BASE + "/comments").header("X-Session-Id", "s").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":\"评论\"}"))
                .andExpect(status().isConflict());
    }

    @Test @DisplayName("其它 IllegalStateException（如写回时文件没有存储路径）不冒充 409，照全局处理器走")
    void otherIllegalStateNotConflict() throws Exception {
        when(svc.discard(1L, 77L, 5L)).thenThrow(new IllegalStateException("文件没有存储路径: 77"));
        mvc.perform(post(BASE + "/discard").header("X-Session-Id", "s"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1));
    }

    @Test @DisplayName("DELETE comments 回 204")
    void deleteComment204() throws Exception {
        mvc.perform(delete(BASE + "/comments/31").header("X-Session-Id", "s"))
                .andExpect(status().isNoContent());
        verify(svc).deleteComment(77L, 31L);
    }

    @Test @DisplayName("submit 回 submitted 快照；discard 回 discarded 并带 userId 调服务")
    void submitAndDiscard() throws Exception {
        when(svc.submit(77L)).thenReturn(review("submitted"));
        when(svc.comments(9L)).thenReturn(List.of());
        mvc.perform(post(BASE + "/submit").header("X-Session-Id", "s"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.review.status").value("submitted"))
                .andExpect(jsonPath("$.comments").isArray());
        when(svc.discard(1L, 77L, 5L)).thenReturn(review("discarded"));
        mvc.perform(post(BASE + "/discard").header("X-Session-Id", "s"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.review.status").value("discarded"));
        verify(svc).discard(1L, 77L, 5L);
    }

    @Test @DisplayName("fileId 不属于项目：照 checkFileInProject 既有行为（IllegalArgumentException → 全局 code=1），不触达服务")
    void foreignFileRejected() throws Exception {
        mvc.perform(get("/api/projects/1/files/88/review").header("X-Session-Id", "s"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1));
        mvc.perform(post("/api/projects/1/files/88/review/discard").header("X-Session-Id", "s"))
                .andExpect(jsonPath("$.code").value(1));
        verify(svc, never()).current(any());
        verify(svc, never()).discard(any(), any(), any());
    }

    @Test @DisplayName("只读成员不能写；未登录回 4010")
    void readOnlyAndUnauthenticated() throws Exception {
        when(projectMemberService.hasWritePermission(1L, 5L)).thenReturn(false);
        mvc.perform(post(BASE + "/submit").header("X-Session-Id", "s"))
                .andExpect(jsonPath("$.code").value(1));
        verify(svc, never()).submit(any());
        // 静态 mock 对未打桩的 Long 回 0L 而不是 null，这里显式打桩「无会话 → null」
        auth.when(() -> AuthController.getUserIdFromSession(isNull())).thenReturn(null);
        mvc.perform(get(BASE))
                .andExpect(jsonPath("$.code").value(GlobalExceptionHandler.CODE_UNAUTHENTICATED));
        verify(svc, never()).current(any());
    }
}
