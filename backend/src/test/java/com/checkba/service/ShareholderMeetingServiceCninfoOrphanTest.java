package com.checkba.service;

import com.checkba.model.entity.ProjectFile;
import com.checkba.model.entity.ShareholderMeetingCheck;
import com.checkba.repository.ProjectFileRepository;
import com.checkba.repository.ShareholderMeetingCheckRepository;
import com.checkba.storage.StorageException;
import com.checkba.storage.StorageService;
import com.checkba.storage.StorageServiceFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * cninfo 自动抓取写盘失败时的孤儿行清理（dev-board#74 稳定性审计条目 5）。
 *
 * saveBytesAsProjectFile 先 projectFileService.createOrUpdateFile 落库（该方法自带
 * @Transactional，与本类无关，独立成一次提交），后 storageServiceFactory...save()
 * 写字节。save() 抛出时行已经提交，上游 downloadAndAttach 的 catch 只把异常记进
 * errors 就完，没有补偿删除——文件树里于是多出一条有名有大小、内容不存在、
 * 谁也不认领的僵尸文件。
 */
@ExtendWith(MockitoExtension.class)
class ShareholderMeetingServiceCninfoOrphanTest {

    @Mock private ShareholderMeetingCheckRepository checkRepository;
    @Mock private ProjectFileRepository projectFileRepository;
    @Mock private ProjectFileService projectFileService;
    @Mock private StorageServiceFactory storageServiceFactory;
    @Mock private StorageService storageService;
    @Mock private CninfoAnnouncementService cninfoService;

    private ShareholderMeetingService svc;

    @BeforeEach
    void setUp() {
        svc = new ShareholderMeetingService(checkRepository, projectFileRepository,
                projectFileService, storageServiceFactory, cninfoService);
        // ensureFolder 经 self 转发到 REQUIRES_NEW 的 ensureFolderTx（并发竞态修复，
        // dev-board#74）；生产环境里 self 是 Spring 注入的 @Lazy 代理，这里手工 new
        // 没有容器，直接把 service 自己接上去，与 ProjectProfileServiceTest 同一套写法。
        svc.self = svc;
        when(storageServiceFactory.getStorageService()).thenReturn(storageService);
    }

    private ShareholderMeetingCheck check() {
        ShareholderMeetingCheck c = new ShareholderMeetingCheck();
        c.setId(1L);
        c.setProjectId(4L);
        c.setCompanyName("测试公司");
        c.setStockCode("000001");
        c.setMeetingName("2026年第一次临时股东会");
        c.setMeetingDate(LocalDate.of(2026, 6, 1));
        return c;
    }

    @Test
    void storageWriteFailureCleansUpTheJustCreatedRowInsteadOfLeavingAZombie() throws Exception {
        ShareholderMeetingCheck check = check();
        when(checkRepository.findById(1L)).thenReturn(Optional.of(check));
        when(checkRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        CninfoAnnouncementService.FetchResult fetched = new CninfoAnnouncementService.FetchResult();
        fetched.notice = new CninfoAnnouncementService.Announcement(
                "股东大会通知公告", 1L, "ann1", "org1", "/finalpage/notice.pdf", "000001");
        when(cninfoService.fetchForMeeting(anyString(), anyString(), any())).thenReturn(fetched);
        when(cninfoService.downloadPdf(anyString())).thenReturn(new byte[]{1, 2, 3});

        // 底稿夹全部文件夹都不存在，ensureFolder 统一走 createFolder 新建
        when(projectFileRepository.findByProjectIdAndParentIdAndNameAndIsDeletedFalse(any(), any(), any()))
                .thenReturn(Optional.empty());
        AtomicLong folderIdSeq = new AtomicLong(100);
        when(projectFileService.createFolder(anyLong(), any(), anyString(), anyLong())).thenAnswer(inv -> {
            ProjectFile f = new ProjectFile();
            f.setId(folderIdSeq.incrementAndGet());
            f.setProjectId(inv.getArgument(0));
            f.setParentId(inv.getArgument(1));
            f.setName(inv.getArgument(2));
            f.setIsFolder(true);
            return f;
        });

        // saveBytesAsProjectFile 第一步：createOrUpdateFile 落库成功，产出一条新行（id=555）
        ProjectFile created = new ProjectFile();
        created.setId(555L);
        created.setProjectId(4L);
        created.setIsFolder(false);
        created.setName("股东大会通知公告.pdf");
        created.setFileSize(3L);
        created.setFilePath("projects/4/股东大会核查/xxx/01-会议通知/股东大会通知公告.pdf");
        when(projectFileService.createOrUpdateFile(anyLong(), any(), anyString(), anyString(), anyLong(),
                isNull(), isNull(), anyLong())).thenReturn(created);

        // 第二步：写字节失败——存储服务抖动/磁盘写满
        when(storageService.save(anyString(), any())).thenThrow(new StorageException("模拟磁盘写满"));

        Map<String, Object> result = svc.fetchFromCninfo(1L, null, 9L);

        @SuppressWarnings("unchecked")
        List<String> errors = (List<String>) result.get("errors");
        assertFalse(errors.isEmpty(), "下载/写盘失败必须体现在 errors 里");

        verify(projectFileRepository).deleteById(555L);
    }
}
