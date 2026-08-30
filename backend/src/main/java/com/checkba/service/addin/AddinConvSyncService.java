package com.checkba.service.addin;

import com.checkba.model.entity.AddinConvSyncOutbox;
import com.checkba.model.entity.AddinProjectLink;
import com.checkba.model.entity.ProjectAiMessage;
import com.checkba.repository.AddinConvSyncOutboxRepository;
import com.checkba.service.ai.ClientCapabilityService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * 插件对话镜像（dev-board#298）：绑定项目里的每条消息进 outbox，目标桌面机轮询导入后 ACK 删行。
 *
 * <p>挂在 {@link com.checkba.service.ProjectAiMessageService} 的三个落库口之后（可选注入，
 * 桌面端无绑定时每次只多一条按 cloudProjectId 的索引查询）。记录失败绝不影响消息本体落库。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AddinConvSyncService {

    /** outbox 行 TTL：桌面机长期不在线时的兜底清理（与 media 中转 30 天同口径）。 */
    static final Duration TTL = Duration.ofDays(30);

    private final AddinConvSyncOutboxRepository outboxRepository;
    private final AddinProjectLinkService linkService;
    private final ClientCapabilityService clientCapabilityService;

    /**
     * 消息落库后调用：项目有归档绑定才排队。刷新语义 = 删旧行插新行（新 id），
     * 保证「桌面端取件与 ACK 之间这条消息又被 upsert」时更新落在新行上不丢。
     */
    @Transactional
    public void record(ProjectAiMessage message) {
        try {
            if (message == null || message.getId() == null || message.getConversationId() == null) {
                return;
            }
            Optional<AddinProjectLink> link = linkService.findByCloudProjectId(message.getProjectId());
            if (link.isEmpty()) {
                return;
            }
            outboxRepository.findByUserIdAndSourceMessageId(message.getUserId(), message.getId())
                    .ifPresent(existing -> {
                        outboxRepository.delete(existing);
                        // 同事务里对同一唯一键先删后插：不 flush 的话 Hibernate 会把 INSERT
                        // 排到 DELETE 前撞唯一约束（与目录整批替换同一个地雷）
                        outboxRepository.flush();
                    });
            AddinConvSyncOutbox row = new AddinConvSyncOutbox();
            row.setUserId(message.getUserId());
            row.setDeviceId(link.get().getDeviceId());
            row.setProjectKey(link.get().getProjectKey());
            row.setConversationId(message.getConversationId());
            row.setSourceMessageId(message.getId());
            row.setRole(message.getRole());
            row.setContent(message.getContent());
            row.setDisplayContent(message.getDisplayContent());
            row.setSourceChannel(sourceChannelOf(message.getConversationId()));
            row.setMessageCreatedAt(message.getCreatedAt());
            row.setCreatedAt(LocalDateTime.now());
            outboxRepository.save(row);
        } catch (Exception e) {
            // 镜像是旁路：任何失败只告警，不许影响消息本体落库
            log.warn("插件对话镜像排队失败（消息 {} 本体不受影响）",
                    message == null ? null : message.getId(), e);
        }
    }

    /**
     * 来源通道推导：capability 内存态（进程重启即丢）——所以在消息落库当下就固化成字符串。
     * 非 office 会话（理论上不该出现在绑定项目里）回落 "office"。
     */
    private String sourceChannelOf(String conversationId) {
        ClientCapabilityService.Capability cap = clientCapabilityService.capabilityOf(conversationId);
        if (cap != ClientCapabilityService.Capability.OFFICE) {
            return "office";
        }
        String host = clientCapabilityService.officeHostOf(conversationId).name().toLowerCase(Locale.ROOT);
        String family = clientCapabilityService.officeFamilyOf(conversationId).name().toLowerCase(Locale.ROOT);
        return family + "-" + host;
    }

    /** 目标桌面机取件：按行 id 升序（导入顺序 = 落库顺序），单批上限 200。 */
    public List<AddinConvSyncOutbox> pendingForDevice(Long userId, String deviceId) {
        return outboxRepository.findTop200ByUserIdAndDeviceIdOrderByIdAsc(userId, deviceId);
    }

    /** 桌面端确认导入：只删点名的行（属主校验在查询条件里）。 */
    @Transactional
    public int ack(Long userId, List<Long> ids) {
        if (ids == null || ids.isEmpty()) return 0;
        List<AddinConvSyncOutbox> rows = outboxRepository.findByUserIdAndIdIn(userId, ids);
        outboxRepository.deleteAll(rows);
        return rows.size();
    }

    /** TTL 兜底：目标桌面机 30 天没来取的行清掉（ACK 才是主删除机制）。 */
    @Scheduled(initialDelay = 20 * 60 * 1000, fixedDelay = 24 * 60 * 60 * 1000)
    @Transactional
    public void cleanupExpired() {
        List<AddinConvSyncOutbox> expired = outboxRepository.findByCreatedAtBefore(LocalDateTime.now().minus(TTL));
        if (!expired.isEmpty()) {
            outboxRepository.deleteAll(expired);
            log.info("插件对话镜像 outbox TTL 清理 {} 行", expired.size());
        }
    }
}
