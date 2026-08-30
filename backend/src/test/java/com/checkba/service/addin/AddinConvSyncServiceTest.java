package com.checkba.service.addin;

import com.checkba.model.entity.AddinConvSyncOutbox;
import com.checkba.model.entity.AddinProjectLink;
import com.checkba.model.entity.ProjectAiMessage;
import com.checkba.repository.AddinConvSyncOutboxRepository;
import com.checkba.service.ai.ClientCapabilityService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 插件对话镜像 outbox（dev-board#298）：只有绑定项目的消息才排队；刷新语义 = 删旧插新；
 * 来源通道在落库当下固化（capability 是内存态，进程重启即丢，不能事后推导）。
 */
class AddinConvSyncServiceTest {

    private AddinConvSyncOutboxRepository outboxRepository;
    private AddinProjectLinkService linkService;
    private ClientCapabilityService capabilityService;
    private AddinConvSyncService service;

    @BeforeEach
    void setUp() {
        outboxRepository = mock(AddinConvSyncOutboxRepository.class);
        linkService = mock(AddinProjectLinkService.class);
        capabilityService = new ClientCapabilityService();
        service = new AddinConvSyncService(outboxRepository, linkService, capabilityService);
    }

    private static ProjectAiMessage message(Long id, Long projectId, String conversationId) {
        ProjectAiMessage m = new ProjectAiMessage();
        m.setId(id);
        m.setProjectId(projectId);
        m.setUserId(7L);
        m.setRole("USER");
        m.setContent("你好");
        m.setConversationId(conversationId);
        m.setCreatedAt(LocalDateTime.now());
        return m;
    }

    private static AddinProjectLink link() {
        AddinProjectLink l = new AddinProjectLink();
        l.setUserId(7L);
        l.setDeviceId("device-1");
        l.setProjectKey("42");
        l.setCloudProjectId(100L);
        return l;
    }

    @Test
    @DisplayName("无绑定的项目：一行都不排队")
    void unlinkedProjectRecordsNothing() {
        when(linkService.findByCloudProjectId(100L)).thenReturn(Optional.empty());
        service.record(message(1L, 100L, "conv-1"));
        verify(outboxRepository, never()).save(any());
    }

    @Test
    @DisplayName("绑定项目：排队一行，deviceId/projectKey 取自映射，来源通道按 family-host 固化")
    void linkedProjectQueuesRowWithSourceChannel() {
        when(linkService.findByCloudProjectId(100L)).thenReturn(Optional.of(link()));
        when(outboxRepository.findByUserIdAndSourceMessageId(7L, 1L)).thenReturn(Optional.empty());
        capabilityService.record("conv-1", "office", "excel", "wps");

        service.record(message(1L, 100L, "conv-1"));

        ArgumentCaptor<AddinConvSyncOutbox> captor = ArgumentCaptor.forClass(AddinConvSyncOutbox.class);
        verify(outboxRepository).save(captor.capture());
        AddinConvSyncOutbox row = captor.getValue();
        assertEquals("device-1", row.getDeviceId());
        assertEquals("42", row.getProjectKey());
        assertEquals(1L, row.getSourceMessageId());
        assertEquals("wps-excel", row.getSourceChannel());
        assertEquals("你好", row.getContent());
    }

    @Test
    @DisplayName("未上送 officeFamily 的存量插件：来源通道回落 office-<host>")
    void missingFamilyFallsBackToOffice() {
        when(linkService.findByCloudProjectId(100L)).thenReturn(Optional.of(link()));
        when(outboxRepository.findByUserIdAndSourceMessageId(7L, 1L)).thenReturn(Optional.empty());
        capabilityService.record("conv-1", "office", "word");

        service.record(message(1L, 100L, "conv-1"));

        ArgumentCaptor<AddinConvSyncOutbox> captor = ArgumentCaptor.forClass(AddinConvSyncOutbox.class);
        verify(outboxRepository).save(captor.capture());
        assertEquals("office-word", captor.getValue().getSourceChannel());
    }

    @Test
    @DisplayName("同一条消息被 upsert 刷新：删旧行（flush 防同键先插后删乱序）再插新行")
    void refreshDeletesOldRowThenInserts() {
        when(linkService.findByCloudProjectId(100L)).thenReturn(Optional.of(link()));
        AddinConvSyncOutbox old = new AddinConvSyncOutbox();
        old.setId(500L);
        when(outboxRepository.findByUserIdAndSourceMessageId(7L, 1L)).thenReturn(Optional.of(old));

        service.record(message(1L, 100L, "conv-1"));

        var order = inOrder(outboxRepository);
        order.verify(outboxRepository).delete(old);
        order.verify(outboxRepository).flush();
        order.verify(outboxRepository).save(any(AddinConvSyncOutbox.class));
    }

    @Test
    @DisplayName("排队失败绝不外抛：消息本体落库不受镜像旁路故障影响")
    void recordFailureNeverPropagates() {
        when(linkService.findByCloudProjectId(anyLong())).thenThrow(new RuntimeException("db down"));
        assertDoesNotThrow(() -> service.record(message(1L, 100L, "conv-1")));
    }

    @Test
    @DisplayName("ACK 只删点名的行，属主过滤在查询条件里")
    void ackDeletesOnlyNamedRows() {
        AddinConvSyncOutbox a = new AddinConvSyncOutbox();
        a.setId(1L);
        when(outboxRepository.findByUserIdAndIdIn(7L, List.of(1L, 2L))).thenReturn(List.of(a));
        int deleted = service.ack(7L, List.of(1L, 2L));
        assertEquals(1, deleted);
        verify(outboxRepository).deleteAll(List.of(a));
    }
}
