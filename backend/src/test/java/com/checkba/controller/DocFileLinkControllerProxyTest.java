package com.checkba.controller;

import com.checkba.model.entity.ProjectFile;
import com.checkba.repository.ProjectFileRepository;
import com.checkba.service.evidence.EvidenceLinkService;
import com.checkba.service.evidence.EvidenceLinkViews.FileBrief;
import com.checkba.service.evidence.EvidenceLinkViews.LinkView;
import com.checkba.service.evidence.EvidenceLinkViews.TargetView;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 旧 /doc-links 端点：GET 只读代理到 EvidenceLinkService 并转成旧 DocFileLinkResult 形状
 * （files 按 target 的 fileId 去重）；POST 410。
 */
@ExtendWith(MockitoExtension.class)
class DocFileLinkControllerProxyTest {

    @Mock private EvidenceLinkService evidenceLinkService;
    @Mock private ProjectFileRepository projectFileRepository;
    @InjectMocks private DocFileLinkController controller;

    private static ProjectFile file(long id, String wps) {
        ProjectFile f = new ProjectFile();
        f.setId(id);
        f.setWpsFileId(wps);
        return f;
    }

    @Test
    void getProxiesToNewServiceInOldShape() {
        try (MockedStatic<AuthController> auth = mockStatic(AuthController.class)) {
            auth.when(() -> AuthController.getUserIdFromSession("sess")).thenReturn(9L);
            LinkView v = new LinkView(100L, "lk_x", 10L, "锚点", "h", null, null, "unverified", "human", null, null, List.of(
                    new TargetView(1L, 11L, new FileBrief(11L, "a.pdf", "pdf", null, false), null, "supports", null, null, null),
                    new TargetView(2L, 11L, new FileBrief(11L, "a.pdf", "pdf", null, false), null, "supports", null, null, null),
                    new TargetView(3L, 12L, new FileBrief(12L, "b.pdf", "pdf", null, false), null, "supports", null, null, null)));
            when(evidenceLinkService.getByKey(9L, 1L, "lk_x")).thenReturn(v);
            when(projectFileRepository.findById(10L)).thenReturn(Optional.of(file(10L, "w1")));
            when(projectFileRepository.findAllById(any())).thenReturn(List.of(file(12L, null), file(11L, null)));

            DocFileLinkController.DocFileLinkResult r = controller.get(1L, "lk_x", "sess");
            assertEquals("lk_x", r.getLinkKey());
            assertEquals("w1", r.getDocWpsFileId());
            assertEquals("锚点", r.getAnchorText());
            assertEquals(List.of(11L, 12L), r.getFileIds());
            assertEquals(List.of(11L, 12L), r.getFiles().stream().map(ProjectFile::getId).toList());
        }
    }

    @Test
    void getRejectsAnonymousBeforeTouchingService() {
        try (MockedStatic<AuthController> auth = mockStatic(AuthController.class)) {
            auth.when(() -> AuthController.getUserIdFromSession(null)).thenReturn(null);
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> controller.get(1L, "lk_x", null));
            assertEquals("请先登录", e.getMessage());
            verify(evidenceLinkService, never()).getByKey(any(), any(), any());
        }
    }

    @Test
    void postIsGone() {
        assertEquals(410, controller.createOrAppend().getStatusCode().value());
    }
}
