package com.checkba.service;

import com.checkba.model.entity.DdItem;
import com.checkba.model.entity.DdRequest;
import com.checkba.model.entity.ProjectFile;
import com.checkba.repository.DdCommentRepository;
import com.checkba.repository.DdItemRepository;
import com.checkba.repository.DdRequestRepository;
import com.checkba.repository.ProjectFileRepository;
import com.checkba.storage.StorageService;
import com.checkba.storage.StorageServiceFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * DdService.uploadFile 先写存储再落库（dev-board#74 稳定性审计条目 4）：
 * 展示名用的是未清洗的原始文件名，超过 ProjectFile.name 的 256 字符列宽时落库炸出
 * DataIntegrityViolationException，事务回滚，但物理对象已经先落盘——孤儿对象永久
 * 留在存储里（存储 key 带时间戳，重试一次多漏一个）。
 *
 * 用真实 H2 库（而非纯 Mockito）承载 ProjectFileRepository，才能真正复现列宽超限
 * 在数据库层的报错，而不是靠猜测 Hibernate/驱动的具体异常类型。
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:dd-service-upload-test;MODE=PostgreSQL;NON_KEYWORDS=VALUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
class DdServiceUploadFileTest {

    @Autowired private ProjectFileRepository projectFileRepository;
    @Autowired private DdRequestRepository ddRequestRepository;
    @Autowired private DdItemRepository ddItemRepository;

    private StorageService storageService;
    private StorageServiceFactory storageServiceFactory;
    private ProjectFileService projectFileService;

    private DdRequest seedRequest(Long projectId) {
        DdRequest req = new DdRequest();
        req.setProjectId(projectId);
        req.setName("尽调请求");
        req.setCreatedBy(9L);
        return ddRequestRepository.save(req);
    }

    private DdItem seedItem(Long requestId) {
        DdItem item = new DdItem();
        item.setDdRequestId(requestId);
        item.setTitle("营业执照");
        item.setSortOrder(0);
        return ddItemRepository.save(item);
    }

    private void setUpCollaborators() {
        storageService = mock(StorageService.class);
        storageServiceFactory = mock(StorageServiceFactory.class);
        when(storageServiceFactory.getStorageService()).thenReturn(storageService);

        // ensureFolder 找不到就建：给个自增 id 的假文件夹，不需要真实建文件夹的副作用
        projectFileService = mock(ProjectFileService.class);
        AtomicLong folderIdSeq = new AtomicLong(1000);
        when(projectFileService.createFolder(anyLong(), any(), anyString(), anyLong())).thenAnswer(inv -> {
            ProjectFile f = new ProjectFile();
            f.setId(folderIdSeq.incrementAndGet());
            f.setProjectId(inv.getArgument(0));
            f.setParentId(inv.getArgument(1));
            f.setName(inv.getArgument(2));
            f.setIsFolder(true);
            return f;
        });
    }

    private DdService buildService(ProjectFileRepository repo) {
        DdService svc = new DdService(ddRequestRepository, ddItemRepository,
                mock(DdCommentRepository.class), repo, projectFileService, storageServiceFactory);
        // ensureFolder 经 self 转发到 REQUIRES_NEW 的 ensureFolderTx（并发竞态修复，
        // dev-board#74）；生产环境里 self 是 Spring 注入的 @Lazy 代理，这里手工 new
        // 没有容器，直接把 service 自己接上去，与 ProjectProfileServiceTest 同一套写法。
        svc.self = svc;
        return svc;
    }

    @Test
    void overlongOriginalFilenameDoesNotBlowUpTheColumnAndLeavesNoOrphan() throws Exception {
        setUpCollaborators();
        DdRequest req = seedRequest(1L);
        DdItem item = seedItem(req.getId());
        DdService ddService = buildService(projectFileRepository);

        String longStem = "a".repeat(400);
        String originalFilename = longStem + ".pdf";
        MockMultipartFile file = new MockMultipartFile("file", originalFilename,
                "application/pdf", new byte[]{1, 2, 3});

        DdItem updated = assertDoesNotThrow(() -> ddService.uploadFile(item.getId(), file, 9L),
                "超长文件名不该在落库时炸出 DataIntegrityViolationException");

        ProjectFile saved = projectFileRepository.findById(updated.getUploadedFileId()).orElseThrow();
        assertTrue(saved.getName().length() <= 256, "展示名必须截到列宽以内: len=" + saved.getName().length());
        assertTrue(saved.getName().endsWith(".pdf"), "截断要尽量保留扩展名，否则用户认不出文件类型: " + saved.getName());
        verify(storageService, never()).delete(anyString());
    }

    /**
     * 落库失败（任何原因，未必是长文件名）时，物理对象已经写盘，必须补偿删除，
     * 否则每次重试都在存储里留一个孤儿（存储 key 带时间戳，永不覆盖）。
     *
     * 这里改用纯 Mockito mock 顶替 ProjectFileRepository（不再是 Test A 用的真实 H2
     * 库），只为了能可控地让 save() 在任意时刻抛出任意异常——不依赖列宽超限这个
     * 具体触发条件，覆盖"任何落库失败都不该留孤儿"这个更通用的不变式。
     */
    @Test
    void dbSaveFailureAfterStorageWriteCleansUpOrphan() throws Exception {
        setUpCollaborators();
        DdRequest req = seedRequest(1L);
        DdItem item = seedItem(req.getId());

        ProjectFileRepository failingRepo = mock(ProjectFileRepository.class);
        when(failingRepo.findByProjectIdAndParentIdAndName(anyLong(), any(), anyString()))
                .thenReturn(java.util.Optional.empty());
        when(failingRepo.save(any(ProjectFile.class)))
                .thenThrow(new DataIntegrityViolationException("模拟落库失败"));
        DdService ddService = buildService(failingRepo);

        MockMultipartFile file = new MockMultipartFile("file", "license.pdf",
                "application/pdf", new byte[]{1, 2, 3});

        assertThrows(DataIntegrityViolationException.class,
                () -> ddService.uploadFile(item.getId(), file, 9L));

        org.mockito.ArgumentCaptor<String> pathCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(storageService).save(pathCaptor.capture(), any());
        verify(storageService).delete(pathCaptor.getValue());
    }
}
