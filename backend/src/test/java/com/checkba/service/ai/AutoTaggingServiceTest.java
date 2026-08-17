package com.checkba.service.ai;

import com.checkba.model.entity.Tag;
import com.checkba.service.FileTagService;
import com.checkba.service.TagService;
import com.checkba.storage.StorageServiceFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.Mockito.*;

/**
 * 自动打标签的幂等闸。
 *
 * <p><b>没有这道闸会发生什么</b>：{@code POST /api/files/{id}/upload} 同时是编辑器
 * 自动保存的落点（{@code FileController} 的 legacy 分支——自动保存不带
 * {@code X-File-Total-Size} 头），所以「上传完成」这个信号在一份正在编辑的文档上
 * 每存一次盘就来一次。每来一次就跑一次 LLM，返回 5 个措辞不同的新词，
 * 而 {@code getOrCreateSystemTag} 只按精确字符串去重——标签于是无上限累积
 * （本机实测：单个文件积到 338 个标签，搜索面板的标签筛选区被撑成一面墙），
 * 同时每一次自动保存都白烧一次辅助模型的钱。
 */
class AutoTaggingServiceTest {

    /**
     * 观测点选的是 {@link StorageServiceFactory}，不是 {@code ChatModelFactory}：
     * 幂等闸的下一步是取文本（{@code extractText} → {@code getStorageService()}），
     * 而取文本在本用例里必然失败（存储是 mock），执行到不了模型那一步。
     * 拿模型当探针会得到「三条用例全绿或全红」的假信号——它压根不在被测路径上。
     */
    private AutoTaggingService service(FileTagService fileTagService, StorageServiceFactory storage) {
        return new AutoTaggingService(
                mock(ChatModelFactory.class),
                mock(TagService.class),
                fileTagService,
                storage,
                mock(AuxModelResolver.class),
                mock(TokenUsageService.class));
    }

    private static Tag systemTag() {
        Tag t = new Tag();
        t.setId(1L);
        t.setName("合同");
        t.setIsSystem(true);
        return t;
    }

    private static Tag manualTag() {
        Tag t = new Tag();
        t.setId(2L);
        t.setName("我自己加的");
        t.setIsSystem(false);
        return t;
    }

    @Test
    @DisplayName("已经打过自动标签的文件：直接返回，连文本都不取")
    void skipsFileThatAlreadyHasAutoTags() {
        FileTagService fileTags = mock(FileTagService.class);
        when(fileTags.getTagsByFileId(7L)).thenReturn(List.of(systemTag()));
        StorageServiceFactory storage = mock(StorageServiceFactory.class);

        service(fileTags, storage).autoTagFile(1L, 7L, "some/path.docx", 42L);

        // 闸必须在取文本之前就拦住：不然每次自动保存还是要白跑一遍 Tika 解析
        verifyNoInteractions(storage);
        verify(fileTags, never()).addTagToFile(anyLong(), anyLong(), anyLong());
    }

    @Test
    @DisplayName("只有手工标签的文件不算打过：仍然要走一次自动打标签")
    void manualTagsDoNotCountAsAlreadyTagged() {
        FileTagService fileTags = mock(FileTagService.class);
        when(fileTags.getTagsByFileId(8L)).thenReturn(List.of(manualTag()));
        StorageServiceFactory storage = mock(StorageServiceFactory.class);

        service(fileTags, storage).autoTagFile(1L, 8L, "some/path.docx", 42L);

        // 判据是「有没有系统标签」而不是「有没有标签」——用户自己打过标签的文件
        // 不该因此永远拿不到自动标签
        verify(storage).getStorageService();
    }

    @Test
    @DisplayName("查已有标签失败：当成没打过继续走，不能因为一次查询失败让文件永远没标签")
    void treatsLookupFailureAsNotTagged() {
        FileTagService fileTags = mock(FileTagService.class);
        when(fileTags.getTagsByFileId(9L)).thenThrow(new RuntimeException("db down"));
        StorageServiceFactory storage = mock(StorageServiceFactory.class);

        service(fileTags, storage).autoTagFile(1L, 9L, "some/path.docx", 42L);

        verify(storage).getStorageService();
    }
}
