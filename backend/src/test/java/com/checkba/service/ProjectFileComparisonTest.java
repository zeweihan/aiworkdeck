// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.checkba.service;

import com.checkba.model.entity.ProjectFile;
import com.checkba.repository.ProjectFileRepository;
import com.checkba.storage.*;
import com.checkba.version.WorkSessionService;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.transaction.support.*;
import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ProjectFileComparisonTest {
    @TempDir Path root;
    ProjectFileRepository repo;
    LocalFileStorageService storage;
    StorageServiceFactory factory;
    ProjectFileService service;
    WorkSessionService sessions;
    byte[] docx;
    String hash;
    ProjectFile base, revised;

    @BeforeEach void setup() throws Exception {
        var props = new StorageProperties();
        props.getLocal().setRootPath(root.toString());
        props.getLocal().setTemplatePath(root.resolve("missing.docx").toString());
        storage = new LocalFileStorageService(new ProjectStorageResolver(props, null));
        factory = mock(StorageServiceFactory.class);
        when(factory.getStorageService()).thenReturn(storage);
        repo = mock(ProjectFileRepository.class);
        sessions = mock(WorkSessionService.class);
        service = new ProjectFileService(repo, null, factory, sessions, mock(UserService.class), null,
                mock(com.checkba.service.telemetry.TelemetryService.class), null);
        try (var doc = new XWPFDocument(); var out = new ByteArrayOutputStream()) {
            doc.createParagraph().createRun().setText("基础文本");
            doc.write(out); docx = out.toByteArray();
        }
        hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(docx));
        base = source(1L, "base.docx"); revised = source(2L, "revised.docx");
        when(repo.saveAndFlush(any())).thenAnswer(call -> { ProjectFile f = call.getArgument(0); f.setId(3L); return f; });
    }
    ProjectFile source(long id, String name) {
        var f = new ProjectFile(); f.setId(id); f.setProjectId(10L); f.setName(name);
        f.setFileType("docx"); f.setIsFolder(false); f.setFilePath("projects/10/" + name);
        when(repo.findById(id)).thenReturn(Optional.of(f));
        storage.save(f.getFilePath(), new ByteArrayInputStream(docx)); return f;
    }
    ProjectFile create() { return service.createComparison(10L, 1L, 2L, hash, hash, docx, 7L); }
    long diskCount() throws IOException { try(var files = Files.walk(root)) { return files.filter(Files::isRegularFile).count(); } }
    @AfterEach void clearTx() { if (TransactionSynchronizationManager.isSynchronizationActive()) TransactionSynchronizationManager.clearSynchronization(); }

    @Test void publishesIndependentDocxAndSignalsOnlyAfterCommit() throws Exception {
        TransactionSynchronizationManager.initSynchronization();
        ProjectFile result = create();
        assertNotEquals(base.getFilePath(), result.getFilePath());
        byte[] savedBytes = storage.load(result.getFilePath()).getInputStream().readAllBytes();
        try (var document = new XWPFDocument(new ByteArrayInputStream(savedBytes))) {
            assertEquals("基础文本", document.getParagraphs().get(0).getText());
        }
        assertArrayEquals(docx, storage.load(base.getFilePath()).getInputStream().readAllBytes());
        assertArrayEquals(docx, storage.load(revised.getFilePath()).getInputStream().readAllBytes());
        assertEquals((long)savedBytes.length, result.getFileSize());
        verifyNoInteractions(sessions);
        for(var sync: TransactionSynchronizationManager.getSynchronizations()) { sync.afterCommit(); sync.afterCompletion(TransactionSynchronization.STATUS_COMMITTED); }
        verify(sessions).onChangeSignal(eq(10L), eq(7L), anyString());
        assertEquals(3, diskCount());
    }
    @Test void rollbackRemovesOnlyNewResultWithoutSignaling() throws Exception {
        TransactionSynchronizationManager.initSynchronization(); create();
        for(var sync: TransactionSynchronizationManager.getSynchronizations()) sync.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
        assertEquals(2, diskCount()); verifyNoInteractions(sessions);
    }
    @Test void databaseFailureRemovesPublishedBytes() throws Exception {
        doThrow(new IllegalStateException("db failed")).when(repo).saveAndFlush(any());
        assertThrows(IllegalStateException.class, this::create);
        assertEquals(2, diskCount()); verifyNoInteractions(sessions);
    }
    @Test void changedSourceRejectsBeforeCreatingResult() throws Exception {
        storage.save(base.getFilePath(), new ByteArrayInputStream("changed".getBytes()));
        assertThrows(ProjectFileService.ComparisonSourceChangedException.class, this::create);
        verify(repo, never()).saveAndFlush(any()); assertEquals(2, diskCount());
    }
    @Test void rejectsInvalidPackageAndCrossProjectOrDeletedSources() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> service.createComparison(10L,1L,2L,hash,hash,new byte[]{1},7L));
        revised.setProjectId(11L); assertThrows(IllegalArgumentException.class, this::create);
        revised.setProjectId(10L); revised.setIsDeleted(true); assertThrows(IllegalArgumentException.class, this::create);
        assertEquals(2, diskCount()); verify(repo, never()).saveAndFlush(any());
    }
    @Test void existingDiskResultGetsDifferentNameAndRetainsItsBytes() throws Exception {
        ProjectFile first = create();
        byte[] original = "retained".getBytes(); storage.save(first.getFilePath(), new ByteArrayInputStream(original));
        ProjectFile second = create();
        assertNotEquals(first.getFilePath(), second.getFilePath());
        assertArrayEquals(original, storage.load(first.getFilePath()).getInputStream().readAllBytes());
        assertEquals(4, diskCount());
    }
    @Test void concurrentDestinationWinsWithoutOverwriteOrPlaceholder() throws Exception {
        storage = spy(storage);
        when(factory.getStorageService()).thenReturn(storage);
        String[] competingPath = {null};
        doAnswer(call -> {
            competingPath[0] = call.getArgument(1);
            storage.save(competingPath[0], new ByteArrayInputStream("other creation".getBytes()));
            return call.callRealMethod();
        }).when(storage).moveNew(anyString(), anyString());
        assertThrows(StorageException.class, this::create);
        verify(repo, never()).saveAndFlush(any());
        assertEquals("other creation", new String(storage.load(competingPath[0]).getInputStream().readAllBytes()));
        assertEquals(3, diskCount()); verifyNoInteractions(sessions);
    }

    @Test void realDatabaseRollbackLeavesNeitherRowNorFile() throws Exception {
        var ds = new org.springframework.jdbc.datasource.DriverManagerDataSource(
                "jdbc:h2:mem:comparison_" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        var jdbc = new org.springframework.jdbc.core.JdbcTemplate(ds);
        jdbc.execute("create table comparison_result(id bigint, path varchar(1024))");
        doAnswer(call -> {
            ProjectFile file = call.getArgument(0);
            jdbc.update("insert into comparison_result values (?, ?)", 3L, file.getFilePath());
            file.setId(3L); return file;
        }).when(repo).saveAndFlush(any());
        var tx = new TransactionTemplate(new org.springframework.jdbc.datasource.DataSourceTransactionManager(ds));
        assertThrows(IllegalStateException.class, () -> tx.executeWithoutResult(status -> {
            create();
            assertEquals(1, jdbc.queryForObject("select count(*) from comparison_result", Integer.class));
            throw new IllegalStateException("rollback after insert");
        }));
        assertEquals(0, jdbc.queryForObject("select count(*) from comparison_result", Integer.class));
        assertEquals(2, diskCount()); verifyNoInteractions(sessions);
    }

    @Test void moveNewNeverOverwritesExistingFile() throws Exception {
        String staged = "projects/10/result.tmp";
        storage.save(staged, new ByteArrayInputStream("different".getBytes()));
        assertThrows(StorageException.class, () -> storage.moveNew(staged, base.getFilePath()));
        assertArrayEquals(docx, storage.load(base.getFilePath()).getInputStream().readAllBytes());
        assertTrue(storage.exists(staged));
    }
}
