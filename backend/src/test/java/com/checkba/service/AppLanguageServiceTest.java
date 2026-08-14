package com.checkba.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class AppLanguageServiceTest {

    private final SystemSettingService settings = mock(SystemSettingService.class);
    private final AppLanguageService service = new AppLanguageService(settings);

    @Test
    void defaultsToChineseWhenUnset() {
        when(settings.get(eq(AppLanguageService.KEY), eq(AppLanguageService.ZH_CN)))
                .thenReturn(AppLanguageService.ZH_CN);
        assertEquals("zh-CN", service.language());
        assertFalse(service.isEnglish());
    }

    @Test
    void invalidStoredValueFallsBackToChinese() {
        when(settings.get(eq(AppLanguageService.KEY), eq(AppLanguageService.ZH_CN)))
                .thenReturn("fr-FR");
        assertEquals("zh-CN", service.language());
    }

    @Test
    void setPersistsSupportedLanguage() {
        when(settings.get(eq(AppLanguageService.KEY), eq(AppLanguageService.ZH_CN)))
                .thenReturn(AppLanguageService.EN_US);
        String effective = service.setLanguage("en-US");
        verify(settings).set(AppLanguageService.KEY, "en-US");
        assertEquals("en-US", effective);
        assertTrue(service.isEnglish());
    }

    @Test
    void setIgnoresUnsupportedLanguage() {
        when(settings.get(eq(AppLanguageService.KEY), eq(AppLanguageService.ZH_CN)))
                .thenReturn(AppLanguageService.ZH_CN);
        service.setLanguage("ja-JP");
        service.setLanguage(null);
        verify(settings, never()).set(anyString(), anyString());
    }
}
