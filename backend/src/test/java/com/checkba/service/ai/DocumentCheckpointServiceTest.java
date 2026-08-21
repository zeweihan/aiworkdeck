package com.checkba.service.ai;

import com.checkba.model.entity.ProjectFile;
import com.checkba.service.ProjectFileService;
import com.checkba.storage.StorageService;
import com.checkba.storage.StorageServiceFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 文档检查点按 (conversationId, fileId) 存的修复。
 *
 * 背景：此前 key 只有 conversationId，幂等判据 `checkpoints.containsKey(conversationId)`
 * 完全不看 fileId。同一轮里 doc_open_file 切换活跃文档后，第二个文件的第一次修改类工具
 * 执行前 ensureCheckpoint 会因为"本会话已有快照"直接跳过——第二个文件从未被快照。
 * restore() 用的是第一个文件的快照：覆盖 A、对 A 发 reload，却告诉用户"本轮所有修改已丢弃"
 * ——用户这一轮真正改的是 B，B 的修改一个都没回退。
 */
class DocumentCheckpointServiceTest {

    private ProjectFileService projectFileService;
    private StorageService storage;
    private EditorBridgeService editorBridgeService;
    private DocumentCheckpointService service;

    private ProjectFile fileOf(Long id, String name, String path) {
        ProjectFile f = new ProjectFile();
        f.setId(id);
        f.setName(name);
        f.setFilePath(path);
        return f;
    }

    @BeforeEach
    void setUp() {
        projectFileService = mock(ProjectFileService.class);
        StorageServiceFactory storageServiceFactory = mock(StorageServiceFactory.class);
        storage = mock(StorageService.class);
        when(storageServiceFactory.getStorageService()).thenReturn(storage);
        editorBridgeService = mock(EditorBridgeService.class);
        service = new DocumentCheckpointService(projectFileService, storageServiceFactory, editorBridgeService);
    }

    @Test
    @DisplayName("修复：同一轮里两个不同文件各自建快照，不会因为「本会话已有快照」互相顶掉")
    void ensureCheckpointKeepsSnapshotsForEachFileIndependently() throws Exception {
        when(projectFileService.getFile(1L)).thenReturn(fileOf(1L, "A.docx", "projects/1/A.docx"));
        when(projectFileService.getFile(2L)).thenReturn(fileOf(2L, "B.docx", "projects/1/B.docx"));
        when(projectFileService.getFileBytes(1L)).thenReturn("内容A".getBytes());
        when(projectFileService.getFileBytes(2L)).thenReturn("内容B".getBytes());
        when(storage.load(org.mockito.ArgumentMatchers.contains("1_")))
                .thenReturn(new ByteArrayResource("内容A".getBytes()));
        when(storage.load(org.mockito.ArgumentMatchers.contains("2_")))
                .thenReturn(new ByteArrayResource("内容B".getBytes()));

        service.ensureCheckpoint("conv-1", 1L);
        service.ensureCheckpoint("conv-1", 2L);

        // 两个文件的快照都要存在——用 restore() 的行为反向验证：应同时覆盖 A 与 B，
        // 而不是只覆盖第一个文件（这正是修复前的故障现象）
        String result = service.restore("conv-1");

        assertTrue(result.contains("A.docx"), "应包含第一个文件：" + result);
        assertTrue(result.contains("B.docx"), "应包含第二个文件，修复前这里会漏掉：" + result);
        verify(storage).save(eq("projects/1/A.docx"), any());
        verify(storage).save(eq("projects/1/B.docx"), any());
        verify(editorBridgeService, times(1)).sendReloadFileAction(argThatFileId(1L));
        verify(editorBridgeService, times(1)).sendReloadFileAction(argThatFileId(2L));
    }

    private ProjectFile argThatFileId(Long id) {
        return org.mockito.ArgumentMatchers.argThat(f -> f != null && id.equals(f.getId()));
    }

    @Test
    @DisplayName("幂等：同一文件重复 ensureCheckpoint 只建一次快照（不因为改了别的文件而重建）")
    void ensureCheckpointIsIdempotentPerFile() throws Exception {
        when(projectFileService.getFile(1L)).thenReturn(fileOf(1L, "A.docx", "projects/1/A.docx"));
        when(projectFileService.getFileBytes(1L)).thenReturn("v1".getBytes());

        service.ensureCheckpoint("conv-1", 1L);
        service.ensureCheckpoint("conv-1", 1L); // 重复调用（同一文件的下一个修改类工具执行前也会调）
        service.ensureCheckpoint("conv-1", 1L);

        verify(projectFileService, times(1)).getFileBytes(1L);
        verify(storage, times(1)).save(any(), any());
    }

    @Test
    @DisplayName("hasCheckpoint / clearForNewRun 对齐新的按文件存储语义")
    void hasCheckpointAndClearForNewRun() throws Exception {
        assertFalse(service.hasCheckpoint("conv-1"));

        when(projectFileService.getFile(1L)).thenReturn(fileOf(1L, "A.docx", "projects/1/A.docx"));
        when(projectFileService.getFileBytes(1L)).thenReturn("v1".getBytes());
        service.ensureCheckpoint("conv-1", 1L);
        assertTrue(service.hasCheckpoint("conv-1"));

        service.clearForNewRun("conv-1");
        assertFalse(service.hasCheckpoint("conv-1"), "新一轮开始应清空上一轮全部文件的快照");
        assertTrue(service.restore("conv-1").startsWith("Error"), "清空后 restore 应回落到既有的无快照提示");
    }

    @Test
    @DisplayName("单文件场景行为不变：restore 文案与既有格式一致")
    void singleFileRestoreMessageUnchanged() throws Exception {
        when(projectFileService.getFile(1L)).thenReturn(fileOf(1L, "合同.docx", "projects/1/合同.docx"));
        when(projectFileService.getFileBytes(1L)).thenReturn("内容".getBytes());
        when(storage.load(any())).thenReturn(new ByteArrayResource("内容".getBytes()));

        service.ensureCheckpoint("conv-1", 1L);
        String result = service.restore("conv-1");

        assertTrue(result.contains("已将《合同.docx》恢复到本轮开始前的快照"), result);
        assertTrue(result.contains("本轮所有修改（含修订）已丢弃"), result);
    }
}
