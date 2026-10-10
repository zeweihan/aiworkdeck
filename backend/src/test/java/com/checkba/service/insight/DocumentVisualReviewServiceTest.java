// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.checkba.service.insight;

import com.checkba.model.entity.ProjectFile;
import com.checkba.repository.ProjectFileRepository;
import com.checkba.service.ProjectMemberService;
import com.checkba.service.ai.*;
import dev.langchain4j.data.message.*;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.model.output.TokenUsage;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import javax.imageio.ImageIO;
import java.io.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DocumentVisualReviewServiceTest {
    final ProjectMemberService members = mock(ProjectMemberService.class);
    final ProjectFileRepository files = mock(ProjectFileRepository.class);
    final ChatModelFactory models = mock(ChatModelFactory.class);
    final AuxModelResolver auxiliary = mock(AuxModelResolver.class);
    final TokenUsageService usage = mock(TokenUsageService.class);
    final ChatLanguageModel model = mock(ChatLanguageModel.class);
    final DocumentVisualReviewService service = new DocumentVisualReviewService(members, files, models, auxiliary, usage);
    ProjectFile file;
    String pdf;
    final TokenUsage tokens = new TokenUsage(10, 20);

    @BeforeEach void setup() throws Exception {
        file = new ProjectFile(); file.setId(3L); file.setProjectId(1L); file.setIsFolder(false); file.setIsDeleted(false);
        when(files.findById(3L)).thenReturn(Optional.of(file));
        when(members.hasWritePermission(1L, 9L)).thenReturn(true);
        when(auxiliary.auxModelId()).thenReturn("vision-aux");
        when(models.effectiveModelSupportsVision("vision-aux")).thenReturn(true);
        when(models.getAuxChatModel(Duration.ofSeconds(75))).thenReturn(model);
        when(model.generate(anyList())).thenReturn(Response.from(AiMessage.from("第2页有重复序号。"), tokens));
        pdf = pdf(3);
    }
    static String pdf(int count) throws Exception {
        try (var doc = new PDDocument(); var out = new ByteArrayOutputStream()) {
            for (int i = 0; i < count; i++) {
                var page = new PDPage(new PDRectangle(100, 120)); doc.addPage(page);
                try (var stream = new PDPageContentStream(doc, page)) {
                    stream.setNonStrokingColor(i == 1 ? java.awt.Color.RED : java.awt.Color.BLUE);
                    stream.addRect(0, 0, 100, 120); stream.fill();
                }
            }
            doc.save(out); return Base64.getEncoder().encodeToString(out.toByteArray());
        }
    }
    DocumentVisualReviewService.Request req(String bytes, int start, int end, boolean confirmed) {
        return new DocumentVisualReviewService.Request(3L, bytes, start, end, confirmed, 71L);
    }
    DocumentVisualReviewService.Result review(int start, int end) throws Exception {
        return service.review(9L, 1L, req(pdf, start, end, true));
    }
    void noModel() { verify(model, never()).generate(anyList()); verifyNoInteractions(usage); }

    @Test void permissionAndConfirmationAreRequiredBeforeFileOrModelAccess() {
        assertThrows(IllegalArgumentException.class, () -> service.review(null, 1L, req(pdf, 1, 1, true)));
        assertThrows(IllegalArgumentException.class, () -> service.review(8L, 1L, req(pdf, 1, 1, true)));
        assertThrows(IllegalArgumentException.class, () -> service.review(9L, 1L, req(pdf, 1, 1, false)));
        assertThrows(IllegalArgumentException.class, () -> service.review(9L, 1L, null));
        verify(files, never()).findById(any()); noModel();
    }
    @Test void foreignDeletedAndFolderFilesAreRejected() {
        file.setProjectId(2L); assertThrows(IllegalArgumentException.class, () -> review(1, 1));
        file.setProjectId(1L); file.setIsDeleted(true); assertThrows(IllegalArgumentException.class, () -> review(1, 1));
        file.setIsDeleted(false); file.setIsFolder(true); assertThrows(IllegalArgumentException.class, () -> review(1, 1));
        noModel();
    }
    @Test void invalidPageRangesAndOversizePayloadAreRejected() {
        for (int[] range : List.of(new int[]{0, 1}, new int[]{2, 1}, new int[]{1, 7}, new int[]{4, 5}))
            assertThrows(IllegalArgumentException.class, () -> review(range[0], range[1]));
        for (String value : Arrays.asList(null, "", "%%%", "A".repeat((DocumentVisualReviewService.MAX_BYTES + 2) / 3 * 4 + 1)))
            assertThrows(IllegalArgumentException.class, () -> service.review(9L, 1L, req(value, 1, 1, true)));
        noModel();
    }
    @Test void nonPdfNeverReachesModelAndDoesNotLeaveDocumentLocked() throws Exception {
        assertThrows(Exception.class, () -> service.review(9L, 1L, req(Base64.getEncoder().encodeToString("not a PDF".getBytes()), 1, 1, true)));
        noModel();
        assertEquals(List.of(1), review(1, 1).checkedPages());
    }
    @Test void noVisionRejectsWithoutFallbackOrOcr() {
        when(models.effectiveModelSupportsVision("vision-aux")).thenReturn(false);
        assertThrows(IllegalArgumentException.class, () -> review(1, 1));
        verify(models, never()).getAuxChatModel(any(Duration.class)); noModel();
    }
    @Test void selectedPagesBecomeRealPngHighImagesInTheBilledUserScope() throws Exception {
        when(model.generate(anyList())).thenAnswer(inv -> {
            assertEquals(9L, PlatformAiUserScope.current());
            List<ChatMessage> messages = inv.getArgument(0);
            assertEquals(1, messages.size());
            var contents = ((UserMessage) messages.get(0)).contents();
            assertEquals(5, contents.size());
            assertTrue(((TextContent) contents.get(1)).text().startsWith("第 2 页"));
            assertTrue(((TextContent) contents.get(3)).text().startsWith("第 3 页"));
            for (int i : List.of(2, 4)) {
                var image = (ImageContent) contents.get(i);
                assertEquals(ImageContent.DetailLevel.HIGH, image.detailLevel());
                assertEquals("image/png", image.image().mimeType());
                byte[] png = Base64.getDecoder().decode(image.image().base64Data());
                assertArrayEquals(new byte[]{(byte)137,80,78,71,13,10,26,10}, Arrays.copyOf(png, 8));
                var bitmap = ImageIO.read(new ByteArrayInputStream(png));
                assertNotNull(bitmap);
                assertEquals(i == 2 ? java.awt.Color.RED.getRGB() : java.awt.Color.BLUE.getRGB(), bitmap.getRGB(20, 20));
            }
            return Response.from(AiMessage.from("第2页有重复序号。"), tokens);
        });
        var result = review(2, 3);
        assertEquals(List.of(2, 3), result.checkedPages()); assertEquals(3, result.totalPages());
        assertFalse(result.complete()); assertEquals(71, result.revision());
        verify(models).ensurePaidAccess(9L); verify(models).getAuxChatModel(Duration.ofSeconds(75));
        verify(model, times(1)).generate(anyList());
        verify(usage).recordUsage(1L, 9L, "vision-aux", tokens, null);
        assertNull(PlatformAiUserScope.current());
    }
    @Test void defaultSixPageRequestReportsActualShortDocumentCoverage() throws Exception {
        var result = review(1, 6);
        assertEquals(List.of(1, 2, 3), result.checkedPages()); assertTrue(result.complete());
    }
    @Test void emptyResponseDoesNotClaimCompletionButRecordsConsumedTokens() {
        when(model.generate(anyList())).thenReturn(Response.from(AiMessage.from(" "), tokens));
        assertThrows(IllegalStateException.class, () -> review(1, 1));
        verify(usage).recordUsage(1L, 9L, "vision-aux", tokens, null);
    }
    @Test void nullResponseDoesNotClaimCompletion() {
        when(model.generate(anyList())).thenReturn(null);
        assertThrows(IllegalStateException.class, () -> review(1, 1));
        verifyNoInteractions(usage);
    }
    @Test void transportFailureIsNotRetriedAndReleasesDocumentLock() throws Exception {
        var failure = new IllegalStateException("transport timed out");
        when(model.generate(anyList())).thenThrow(failure).thenReturn(Response.from(AiMessage.from("完成")));
        assertSame(failure, assertThrows(IllegalStateException.class, () -> review(1, 1)));
        verify(model, times(1)).generate(anyList()); verifyNoInteractions(usage);
        assertEquals(List.of(1), review(1, 1).checkedPages());
        verify(model, times(2)).generate(anyList()); assertNull(PlatformAiUserScope.current());
    }
    @Test void concurrentRequestCannotDoubleChargeSameDocument() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        when(model.generate(anyList())).thenAnswer(inv -> { entered.countDown(); assertTrue(release.await(5, TimeUnit.SECONDS)); return Response.from(AiMessage.from("完成")); });
        var pool = Executors.newSingleThreadExecutor();
        try {
            var first = pool.submit(() -> review(1, 1));
            try { assertTrue(entered.await(5, TimeUnit.SECONDS)); assertThrows(IllegalStateException.class, () -> review(1, 1)); }
            finally { release.countDown(); }
            assertNotNull(first.get(5, TimeUnit.SECONDS));
        } finally { pool.shutdownNow(); }
        verify(model, times(1)).generate(anyList());
    }
}
