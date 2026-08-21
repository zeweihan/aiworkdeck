package com.checkba.controller;

import com.checkba.model.entity.WebFavorite;
import com.checkba.repository.UserRepository;
import com.checkba.service.WebFavoriteService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * {@code GET /api/favorites/my}：跟项目内收藏同口径——限量 + 只回轻量列表。
 * meta 里可能压着整页 html 快照（网核证据），直接序列化实体会把它整包发给前端，
 * 而「我的收藏」那一栏一个字段都不用它。
 */
@ExtendWith(MockitoExtension.class)
class WebFavoriteControllerMyFavoritesTest {

    @Mock
    private WebFavoriteService webFavoriteService;
    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private WebFavoriteController controller;

    private static WebFavorite row(long id, String meta) {
        WebFavorite fav = new WebFavorite();
        fav.setId(id);
        fav.setUserId(7L);
        fav.setTitle("某工商登记页");
        fav.setSourceUrl("https://example.com/" + id);
        fav.setContent("摘录文本");
        fav.setMeta(meta);
        fav.setCreatedAt(LocalDateTime.of(2026, 8, 20, 9, 0));
        return fav;
    }

    @SuppressWarnings("unchecked")
    private static List<WebFavoriteController.WebFavoriteListItem> items(ResponseEntity<?> resp) {
        return (List<WebFavoriteController.WebFavoriteListItem>) resp.getBody();
    }

    @Test
    void dropsHugeMetaAndKeepsDisplayFields() {
        String html = "<html>" + "x".repeat(200_000) + "</html>";
        String meta = "{\"sourceHost\":\"example.com\",\"html\":\"" + html + "\"}";
        when(webFavoriteService.listMyFavorites(7L)).thenReturn(List.of(row(1L, meta)));

        try (MockedStatic<AuthController> auth = mockStatic(AuthController.class)) {
            auth.when(() -> AuthController.getUserIdFromSession("s")).thenReturn(7L);
            ResponseEntity<?> resp = controller.myFavorites(null, "s");

            List<WebFavoriteController.WebFavoriteListItem> body = items(resp);
            assertEquals(1, body.size());
            // 轻量投影：没有 meta 这个字段，html 快照走不出去
            assertEquals(1L, body.get(0).getId());
            assertEquals("example.com", body.get(0).getSourceHost());
            assertEquals("摘录文本", body.get(0).getContent());
            assertFalse(hasField(body.get(0), "meta"), "轻量列表项不该带 meta");
        }
    }

    @Test
    void capsRowCount() {
        List<WebFavorite> many = new ArrayList<>();
        for (int i = 0; i < 600; i++) many.add(row(i, "{}"));
        when(webFavoriteService.listMyFavorites(7L)).thenReturn(many);

        try (MockedStatic<AuthController> auth = mockStatic(AuthController.class)) {
            auth.when(() -> AuthController.getUserIdFromSession("s")).thenReturn(7L);

            // 默认上限 200
            assertEquals(200, items(controller.myFavorites(null, "s")).size());
            // 显式 limit 生效，且被夹在 [1, 500]
            assertEquals(10, items(controller.myFavorites(10, "s")).size());
            assertEquals(500, items(controller.myFavorites(9999, "s")).size());
            assertEquals(1, items(controller.myFavorites(0, "s")).size());
        }
    }

    private static boolean hasField(Object o, String name) {
        try {
            o.getClass().getDeclaredField(name);
            return true;
        } catch (NoSuchFieldException e) {
            return false;
        }
    }
}
