package com.checkba.service;

import com.checkba.model.entity.WebFavorite;
import com.checkba.repository.WebFavoriteRepository;
import com.checkba.storage.StorageService;
import com.checkba.storage.StorageServiceFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 收藏截图存储失败时的上报口径：截图是唯一载荷时必须报错（否则落一条空收藏还回 200），
 * 还有正文兜底时才允许降级保存。
 */
class WebFavoriteServiceScreenshotFailureTest {

    private static final String PNG_BASE64 =
            "data:image/png;base64," + Base64.getEncoder().encodeToString(new byte[]{1, 2, 3});

    private WebFavoriteRepository repository;
    private StorageService storageService;
    private WebFavoriteService service;

    @BeforeEach
    void setUp() {
        repository = mock(WebFavoriteRepository.class);
        storageService = mock(StorageService.class);
        StorageServiceFactory factory = mock(StorageServiceFactory.class);
        when(factory.getStorageService()).thenReturn(storageService);
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        service = new WebFavoriteService(repository, factory);
    }

    @Test
    @DisplayName("截图是唯一载荷时，存储失败必须抛出，不能落一条空收藏")
    void screenshotOnlyFavoriteFailsLoudlyWhenStorageThrows() {
        when(storageService.save(anyString(), any())).thenThrow(new RuntimeException("disk full"));

        assertThrows(IllegalArgumentException.class,
                () -> service.createFavorite(1L, 2L, "标题", "https://example.com", "", PNG_BASE64, null));

        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("还有正文兜底时，截图存储失败降级保存，正文不丢")
    void favoriteWithTextKeepsContentWhenScreenshotStorageThrows() {
        when(storageService.save(anyString(), any())).thenThrow(new RuntimeException("disk full"));

        WebFavorite fav = service.createFavorite(1L, 2L, "标题", "https://example.com", "正文", PNG_BASE64, null);

        assertEquals("正文", fav.getContent());
        assertNull(fav.getImagePath());
        verify(repository).save(any());
    }
}
