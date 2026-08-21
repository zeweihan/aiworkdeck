package com.checkba.service.quota;

import com.checkba.exception.StageQuotaExceededException;
import com.checkba.model.entity.ProjectFile;
import com.checkba.repository.ProjectFileRepository;
import com.checkba.service.entitlement.EntitlementService;
import com.checkba.service.entitlement.FeatureCatalog;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * 文件缓存区免费额度（Spec §5）。核心不变式：<b>拦新增，不动存量</b>。
 * 超额时抛异常拒绝这次移入，缓存区里已有的文件一个都不许被删/被隐藏。
 */
class StageQuotaServiceTest {

    private static final Long PROJECT = 1L;
    private static final Long STAGE_FOLDER = 100L;
    private static final Long OTHER_FOLDER = 200L;

    private ProjectFileRepository repository;
    private EntitlementService entitlementService;
    private StageQuotaService service;

    /** id -> 文件，模拟一张最小的文件表。 */
    private final Map<Long, ProjectFile> table = new HashMap<>();

    @BeforeEach
    void setUp() {
        repository = mock(ProjectFileRepository.class);
        entitlementService = mock(EntitlementService.class);
        when(entitlementService.isEnabled(anyString())).thenReturn(false);
        // localMode=true：额度只在桌面单机版执行，团队服务器不限（见 StageQuotaService.limited 注释）
        service = new StageQuotaService(repository, entitlementService, true);

        table.clear();
        put(folder(STAGE_FOLDER, StageQuotaService.STAGING_FOLDER_NAME));
        put(folder(OTHER_FOLDER, "合同"));

        when(repository.findById(any())).thenAnswer(inv -> Optional.ofNullable(table.get(inv.getArgument(0))));
        // lockById 是悲观行锁读取（并发竞态修复，dev-board#74），对这张纯内存假表而言
        // 语义上就是同一次查找——真实的行锁互斥由 ProjectFileRepositoryStagingLockConcurrentTest
        // 用真 Spring 容器 + 真 H2 验证。
        when(repository.lockById(any())).thenAnswer(inv -> Optional.ofNullable(table.get(inv.getArgument(0))));
        when(repository.findByProjectIdAndParentIdAndIsDeletedFalseOrderBySortOrderAsc(any(), any()))
                .thenAnswer(inv -> {
                    Long parent = inv.getArgument(1);
                    return table.values().stream()
                            .filter(f -> parent != null && parent.equals(f.getParentId()))
                            .toList();
                });
    }

    private void put(ProjectFile f) {
        table.put(f.getId(), f);
    }

    private static ProjectFile folder(Long id, String name) {
        ProjectFile f = new ProjectFile();
        f.setId(id);
        f.setProjectId(PROJECT);
        f.setName(name);
        f.setIsFolder(true);
        f.setIsDeleted(false);
        return f;
    }

    private static ProjectFile file(Long id, Long parentId, long size) {
        ProjectFile f = new ProjectFile();
        f.setId(id);
        f.setProjectId(PROJECT);
        f.setParentId(parentId);
        f.setName("f" + id + ".docx");
        f.setIsFolder(false);
        f.setIsDeleted(false);
        f.setFileSize(size);
        return f;
    }

    /** 往缓存区放 n 个文件，每个 size 字节。 */
    private void fillStage(int n, long size) {
        for (int i = 0; i < n; i++) put(file(1000L + i, STAGE_FOLDER, size));
    }

    /** 在缓存区外准备 n 个待移入的文件，返回它们的 id。 */
    private List<Long> pending(int n, long size) {
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            ProjectFile f = file(2000L + i, OTHER_FOLDER, size);
            put(f);
            ids.add(f.getId());
        }
        return ids;
    }

    @Test
    @DisplayName("缓存区已有 19 个，再放第 20 个：放行（上限是闭区间）")
    void twentiethFileAllowed() {
        fillStage(19, 1024);
        assertDoesNotThrow(() -> service.checkAdmission(STAGE_FOLDER, pending(1, 1024)));
    }

    @Test
    @DisplayName("缓存区已有 20 个，再放第 21 个：拒绝，且不碰任何已有文件")
    void twentyFirstFileRejected() {
        fillStage(20, 1024);
        List<Long> incoming = pending(1, 1024);

        StageQuotaExceededException e = assertThrows(StageQuotaExceededException.class,
                () -> service.checkAdmission(STAGE_FOLDER, incoming));
        assertTrue(e.getMessage().contains("20"));
        assertTrue(e.getMessage().contains("已有文件不会被删除"), "提示必须让用户确信数据安全");
        assertEquals(20, e.getFileCount());
        verify(repository, never()).delete(any());
        verify(repository, never()).deleteAll(any());
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("一次批量移入导致越界：整批拒绝（不做部分放行）")
    void batchThatOverflowsIsRejectedWholesale() {
        fillStage(18, 1024);
        assertThrows(StageQuotaExceededException.class,
                () -> service.checkAdmission(STAGE_FOLDER, pending(5, 1024)));
    }

    @Test
    @DisplayName("字节数边界：正好 500MB 放行，超 1 字节拒绝")
    void byteLimitBoundary() {
        long half = StageQuotaService.FREE_MAX_BYTES / 2;
        fillStage(1, half);
        // 已用 half，再放 half → 正好 500MB
        assertDoesNotThrow(() -> service.checkAdmission(STAGE_FOLDER, pending(1, half)));

        table.remove(2000L);
        assertThrows(StageQuotaExceededException.class,
                () -> service.checkAdmission(STAGE_FOLDER, pending(1, half + 1)));
    }

    @Test
    @DisplayName("已在缓存区里的文件重复拖入不算新增")
    void alreadyStagedFilesDoNotCountAsNew() {
        fillStage(20, 1024);
        List<Long> alreadyInside = List.of(1000L, 1001L);
        assertDoesNotThrow(() -> service.checkAdmission(STAGE_FOLDER, alreadyInside));
    }

    @Test
    @DisplayName("目标不是缓存区：额度完全不介入（普通移动不受影响）")
    void nonStagingTargetUnaffected() {
        fillStage(50, StageQuotaService.FREE_MAX_BYTES);
        assertDoesNotThrow(() -> service.checkAdmission(OTHER_FOLDER, pending(30, 1024)));
        assertDoesNotThrow(() -> service.checkAdmission(null, pending(30, 1024)));
    }

    @Test
    @DisplayName("拥有 stage.unlimited：条数与字节都不再限制")
    void unlimitedBypassesBothLimits() {
        when(entitlementService.isEnabled(FeatureCatalog.STAGE_UNLIMITED)).thenReturn(true);
        fillStage(100, StageQuotaService.FREE_MAX_BYTES);
        assertDoesNotThrow(() -> service.checkAdmission(STAGE_FOLDER, pending(50, StageQuotaService.FREE_MAX_BYTES)));
    }

    @Test
    @DisplayName("fileSize 为 null 的历史文件按 0 计，不因脏数据误拦")
    void nullFileSizeTreatedAsZero() {
        ProjectFile legacy = file(1500L, STAGE_FOLDER, 0);
        legacy.setFileSize(null);
        put(legacy);
        assertDoesNotThrow(() -> service.checkAdmission(STAGE_FOLDER, pending(1, 1024)));
    }

    @Test
    @DisplayName("usage 报当前用量与上限；解锁后上限为 null")
    void usageReportsCountsAndLimits() {
        fillStage(3, 1024);
        Map<String, Object> free = service.usage(STAGE_FOLDER);
        assertEquals(3, free.get("fileCount"));
        assertEquals(3L * 1024, free.get("totalBytes"));
        assertEquals(true, free.get("limited"));
        assertEquals(StageQuotaService.FREE_MAX_FILES, free.get("maxFiles"));
        assertEquals(StageQuotaService.FREE_MAX_BYTES, free.get("maxBytes"));

        when(entitlementService.isEnabled(FeatureCatalog.STAGE_UNLIMITED)).thenReturn(true);
        Map<String, Object> paid = service.usage(STAGE_FOLDER);
        assertEquals(false, paid.get("limited"));
        assertNull(paid.get("maxFiles"));
        assertNull(paid.get("maxBytes"));
        assertEquals(3, paid.get("fileCount"), "解锁前后看到的文件数一致——存量不受额度影响");
    }

    @Test
    @DisplayName("跨项目的文件 id 不参与额度计算（否则能靠拒绝与否探测他人文件大小）")
    void crossProjectIdsIgnored() {
        fillStage(19, 1024);
        ProjectFile foreign = file(9000L, 8888L, StageQuotaService.FREE_MAX_BYTES);
        foreign.setProjectId(999L); // 别人的项目
        put(foreign);

        // 这个文件足够大，若参与计算必然触发字节上限；正确行为是被整个忽略
        assertDoesNotThrow(() -> service.checkAdmission(STAGE_FOLDER, List.of(9000L)));
    }

    @Test
    @DisplayName("空的子文件夹本身不计入文件数")
    void subFoldersNotCounted() {
        fillStage(19, 1024);
        ProjectFile sub = folder(1900L, "子目录");
        sub.setParentId(STAGE_FOLDER);
        put(sub);
        assertDoesNotThrow(() -> service.checkAdmission(STAGE_FOLDER, pending(1, 1024)));
    }

    // ==================== 文件夹递归（拖文件夹曾能整体绕过额度） ====================

    /** 造一个装了 n 个文件的文件夹，返回文件夹 id。 */
    private Long folderWith(Long folderId, int n, long size) {
        ProjectFile dir = folder(folderId, "尽调材料");
        dir.setParentId(OTHER_FOLDER);
        put(dir);
        for (int i = 0; i < n; i++) put(file(folderId + 1 + i, folderId, size));
        return folderId;
    }

    @Test
    @DisplayName("拖入的文件夹按它装的文件数计——不能靠套一层目录绕过条数上限")
    void incomingFolderCountsItsFiles() {
        fillStage(15, 1024);
        Long dir = folderWith(3000L, 10, 1024); // 15 + 10 = 25 > 20

        StageQuotaExceededException e = assertThrows(StageQuotaExceededException.class,
                () -> service.checkAdmission(STAGE_FOLDER, List.of(dir)));
        assertTrue(e.getMessage().contains("已有文件不会被删除"));
        verify(repository, never()).delete(any());
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("拖入的文件夹按它装的字节数计——不能靠套一层目录绕过 500MB")
    void incomingFolderCountsItsBytes() {
        Long dir = folderWith(3000L, 2, StageQuotaService.FREE_MAX_BYTES);
        assertThrows(StageQuotaExceededException.class,
                () -> service.checkAdmission(STAGE_FOLDER, List.of(dir)));
    }

    @Test
    @DisplayName("多层嵌套的文件夹也要一路数到底")
    void nestedFoldersCountedRecursively() {
        Long outer = folderWith(3000L, 5, 1024);
        ProjectFile inner = folder(4000L, "第二层");
        inner.setParentId(outer);
        put(inner);
        for (int i = 0; i < 16; i++) put(file(4100L + i, 4000L, 1024)); // 5 + 16 = 21 > 20

        assertThrows(StageQuotaExceededException.class,
                () -> service.checkAdmission(STAGE_FOLDER, List.of(outer)));
    }

    @Test
    @DisplayName("同时勾了文件夹和它里面的文件：里面的文件只算一次")
    void folderAndItsChildSelectedTogetherCountedOnce() {
        fillStage(10, 1024);
        Long dir = folderWith(3000L, 10, 1024); // 10 + 10 = 20，正好到上限
        // 3001 是 dir 里的第一个文件，与 dir 一起选中；重复计数会变成 21 而误拒
        assertDoesNotThrow(() -> service.checkAdmission(STAGE_FOLDER, List.of(dir, 3001L)));
    }

    @Test
    @DisplayName("缓存区子目录里的存量文件计入用量，且不被当作新增重复计一次")
    void filesInStageSubFolderCountAsExisting() {
        ProjectFile sub = folder(1900L, "旧批次");
        sub.setParentId(STAGE_FOLDER);
        put(sub);
        for (int i = 0; i < 20; i++) put(file(1910L + i, 1900L, 1024));

        // 用量条要看得见它们（否则显示 0/20 却拒绝新文件，用户无法自证）
        Map<String, Object> usage = service.usage(STAGE_FOLDER);
        assertEquals(20, usage.get("fileCount"));

        // 已经在区内的文件重复拖入不算新增
        assertDoesNotThrow(() -> service.checkAdmission(STAGE_FOLDER, List.of(1910L, 1911L)));
        // 但真正的新增会被拒
        assertThrows(StageQuotaExceededException.class,
                () -> service.checkAdmission(STAGE_FOLDER, pending(1, 1024)));
    }

    @Test
    @DisplayName("自己是自己父目录的脏数据不会让递归转不出来")
    void selfParentedFolderDoesNotHang() {
        // 历史数据修复留下的自环：缓存区目录的 parentId 指向它自己，
        // 于是「列出子项」把它自己也列进来。visitedFolders 必须挡住这一跳。
        table.get(STAGE_FOLDER).setParentId(STAGE_FOLDER);
        fillStage(3, 1024);

        assertTimeoutPreemptively(java.time.Duration.ofSeconds(5), () -> {
            Map<String, Object> usage = service.usage(STAGE_FOLDER);
            assertEquals(3, usage.get("fileCount"));
        });
    }

    // ==================== 团队服务器模式 ====================

    @Test
    @DisplayName("非单机模式（团队案件库服务器）不执行额度：本机权益对服务器成员没有意义")
    void serverModeIsNeverLimited() {
        StageQuotaService serverSide = new StageQuotaService(repository, entitlementService, false);
        fillStage(100, StageQuotaService.FREE_MAX_BYTES);

        assertDoesNotThrow(() -> serverSide.checkAdmission(STAGE_FOLDER, pending(50, 1024)));
        Map<String, Object> usage = serverSide.usage(STAGE_FOLDER);
        assertEquals(false, usage.get("limited"));
        assertNull(usage.get("maxFiles"));
    }
}
