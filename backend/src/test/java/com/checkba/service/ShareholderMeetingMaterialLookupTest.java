package com.checkba.service;

import com.checkba.model.entity.ProjectFile;
import com.checkba.repository.ProjectFileRepository;
import com.checkba.repository.ShareholderMeetingCheckRepository;
import com.checkba.storage.StorageServiceFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

/**
 * 材料查找必须排除回收站里的文件。
 *
 * <p>病灶：findFiles 用的是裸 findById，不过滤 isDeleted，而 ProjectFileService 完全不知道
 * 股东大会核查这回事，文件被删时从不 detachMaterial。于是把材料丢进回收站之后，
 * 「开始核查」的 kick-off prompt 仍把它列成一份在场的材料、也不进「缺失材料」告警——
 * 与这段代码为 null 槽位精心实现的「缺失材料」提示自相矛盾。
 * 文件若已被彻底删除（字节没了），后续复制或 AI 读取还会失败，而清单从没提醒过它没了。
 */
@ExtendWith(MockitoExtension.class)
class ShareholderMeetingMaterialLookupTest {

    @Mock private ShareholderMeetingCheckRepository checkRepository;
    @Mock private ProjectFileRepository projectFileRepository;
    @Mock private ProjectFileService projectFileService;
    @Mock private StorageServiceFactory storageServiceFactory;
    @Mock private CninfoAnnouncementService cninfoService;

    private ShareholderMeetingService svc;

    @BeforeEach
    void setUp() {
        svc = new ShareholderMeetingService(checkRepository, projectFileRepository,
                projectFileService, storageServiceFactory, cninfoService);
    }

    private static ProjectFile file(long id, boolean deleted) {
        ProjectFile f = new ProjectFile();
        f.setId(id);
        f.setName("f" + id + ".pdf");
        f.setIsDeleted(deleted);
        return f;
    }

    @Test
    @DisplayName("回收站里的材料不算在场，只留还活着的")
    void trashedMaterialIsNotTreatedAsPresent() {
        ProjectFile live = file(1L, false);
        when(projectFileRepository.findById(1L)).thenReturn(Optional.of(live));
        when(projectFileRepository.findById(2L)).thenReturn(Optional.of(file(2L, true)));

        assertEquals(List.of(live), svc.findFiles(List.of(1L, 2L)),
                "被丢进回收站的材料仍被当成在场，「缺失材料」告警因此不会提到它");
    }

    @Test
    @DisplayName("isDeleted 为 null 的历史数据按未删除处理")
    void nullDeletedFlagCountsAsAlive() {
        ProjectFile legacy = file(3L, false);
        legacy.setIsDeleted(null);
        when(projectFileRepository.findById(3L)).thenReturn(Optional.of(legacy));

        assertEquals(List.of(legacy), svc.findFiles(List.of(3L)));
    }
}
