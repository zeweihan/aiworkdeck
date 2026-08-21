package com.checkba.service;

import com.checkba.model.entity.ClipboardItem;
import com.checkba.repository.ClipboardItemRepository;
import com.checkba.service.entitlement.EntitlementService;
import com.checkba.storage.StorageService;
import com.checkba.storage.StorageServiceFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 删除文件型剪贴板记录时，底层对象也要一起删掉。
 *
 * <p>病灶：delete() 只删数据库行，从不调 StorageService.delete——每删一条文件型记录
 * 就永久漏一份对象。这是维护者看不见的泄漏：功能上一切正常，只有磁盘/对象存储用量
 * 在慢慢涨，涨到出问题时也很难回溯到剪贴板这个功能上。
 */
class ClipboardBlobCleanupTest {

    private static final Long USER = 7L;

    private ClipboardItemRepository repository;
    private StorageService storage;
    private ClipboardService service;

    @BeforeEach
    void setUp() {
        repository = mock(ClipboardItemRepository.class);
        storage = mock(StorageService.class);
        StorageServiceFactory factory = mock(StorageServiceFactory.class);
        when(factory.getStorageService()).thenReturn(storage);
        EntitlementService entitlement = mock(EntitlementService.class);
        service = new ClipboardService(repository, factory, entitlement, true);
    }

    private ClipboardItem item(String type, String meta) {
        ClipboardItem it = new ClipboardItem();
        it.setId(9L);
        it.setUserId(USER);
        it.setType(type);
        it.setMeta(meta);
        return it;
    }

    @Test
    @DisplayName("删除文件型记录时一并删掉存储里的对象")
    void deletingFileItemAlsoRemovesTheBlob() {
        when(repository.findById(9L)).thenReturn(Optional.of(item("IMAGE", "{\"path\":\"clipboard/7/abc\"}")));

        service.delete(9L, USER);

        verify(repository).delete(org.mockito.ArgumentMatchers.any());
        verify(storage).delete("clipboard/7/abc");
    }

    @Test
    @DisplayName("文本记录不碰存储；meta 里没有 path 也不该炸")
    void textItemAndMissingPathAreSafe() {
        when(repository.findById(9L)).thenReturn(Optional.of(item("TEXT", "{}")));
        service.delete(9L, USER);
        verify(storage, never()).delete(anyString());

        when(repository.findById(9L)).thenReturn(Optional.of(item("IMAGE", "not-json")));
        service.delete(9L, USER);
        verify(storage, never()).delete(anyString());
    }
}
