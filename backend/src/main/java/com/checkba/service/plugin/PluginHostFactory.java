package com.checkba.service.plugin;

import com.checkba.plugin.api.PluginHost;
import com.checkba.plugin.api.ToolCall;
import com.checkba.repository.ProjectFileRepository;
import com.checkba.repository.TagRepository;
import com.checkba.service.DocumentTextService;
import com.checkba.service.FileTagService;
import com.checkba.service.OcrService;
import com.checkba.service.ProjectFileService;
import com.checkba.service.ProjectMemberService;
import com.checkba.service.SystemSettingService;
import com.checkba.service.TagService;
import com.checkba.service.ai.AuxModelResolver;
import com.checkba.service.ai.ChatModelFactory;
import com.checkba.service.ai.EditorBridgeService;
import com.checkba.service.ai.StyleProfileResolver;
import com.checkba.service.ai.TokenUsageService;
import com.checkba.service.ai.tools.ToolContext;
import com.checkba.service.evidence.EvidenceLinkService;
import com.checkba.storage.StorageServiceFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 按插件 id 发放 {@link PluginHost}（缓存），并持有「当前调用上下文」的 ThreadLocal：
 * {@code ToolRegistry} 在分发插件工具前 {@link #bindCall}、分发后 {@link #clear}（与 ToolContextHolder 同一处）；
 * 后台任务线程由 {@code PluginJobService} 的任务体包装同样绑定一份快照。
 *
 * <p>所有宿主服务集中在这里注入，{@link PluginHostImpl} 只拿引用——单元 I/J/K 给宿主加能力时只改这两处。
 */
@Service
public class PluginHostFactory {

    final ProjectFileService projectFileService;
    final ProjectFileRepository projectFileRepository;
    final StorageServiceFactory storageServiceFactory;
    final ProjectMemberService projectMemberService;
    final DocumentTextService documentTextService;
    final OcrService ocrService;
    final TagService tagService;
    final TagRepository tagRepository;
    final FileTagService fileTagService;
    final EvidenceLinkService evidenceLinkService;
    final com.checkba.service.evidence.EvidenceAnchorService evidenceAnchorService;
    final PluginJobService pluginJobService;
    final EditorBridgeService editorBridgeService;
    final SystemSettingService systemSettingService;
    final StyleProfileResolver styleProfileResolver;
    final ChatModelFactory chatModelFactory;
    final AuxModelResolver auxModelResolver;
    final TokenUsageService tokenUsageService;
    final ObjectMapper objectMapper;
    final PluginHostQuota quota;

    private final ThreadLocal<ToolCall> currentCall = new ThreadLocal<>();
    private final ThreadLocal<Boolean> inJob = new ThreadLocal<>();
    private final Map<String, PluginHost> hosts = new ConcurrentHashMap<>();

    @Autowired
    public PluginHostFactory(ProjectFileService projectFileService,
                             ProjectFileRepository projectFileRepository,
                             StorageServiceFactory storageServiceFactory,
                             ProjectMemberService projectMemberService,
                             DocumentTextService documentTextService,
                             OcrService ocrService,
                             TagService tagService,
                             TagRepository tagRepository,
                             FileTagService fileTagService,
                             EvidenceLinkService evidenceLinkService,
                             com.checkba.service.evidence.EvidenceAnchorService evidenceAnchorService,
                             PluginJobService pluginJobService,
                             EditorBridgeService editorBridgeService,
                             SystemSettingService systemSettingService,
                             StyleProfileResolver styleProfileResolver,
                             ChatModelFactory chatModelFactory,
                             AuxModelResolver auxModelResolver,
                             TokenUsageService tokenUsageService,
                             ObjectMapper objectMapper) {
        this(projectFileService, projectFileRepository, storageServiceFactory, projectMemberService, documentTextService,
                ocrService, tagService, tagRepository, fileTagService, evidenceLinkService, evidenceAnchorService, pluginJobService,
                editorBridgeService, systemSettingService, styleProfileResolver, chatModelFactory, auxModelResolver,
                tokenUsageService, objectMapper, new PluginHostQuota());
    }

    PluginHostFactory(ProjectFileService projectFileService,
                      ProjectFileRepository projectFileRepository,
                      StorageServiceFactory storageServiceFactory,
                      ProjectMemberService projectMemberService,
                      DocumentTextService documentTextService,
                      OcrService ocrService,
                      TagService tagService,
                      TagRepository tagRepository,
                      FileTagService fileTagService,
                      EvidenceLinkService evidenceLinkService,
                      com.checkba.service.evidence.EvidenceAnchorService evidenceAnchorService,
                      PluginJobService pluginJobService,
                      EditorBridgeService editorBridgeService,
                      SystemSettingService systemSettingService,
                      StyleProfileResolver styleProfileResolver,
                      ChatModelFactory chatModelFactory,
                      AuxModelResolver auxModelResolver,
                      TokenUsageService tokenUsageService,
                      ObjectMapper objectMapper,
                      PluginHostQuota quota) {
        this.projectFileService = projectFileService;
        this.projectFileRepository = projectFileRepository;
        this.storageServiceFactory = storageServiceFactory;
        this.projectMemberService = projectMemberService;
        this.documentTextService = documentTextService;
        this.ocrService = ocrService;
        this.tagService = tagService;
        this.tagRepository = tagRepository;
        this.fileTagService = fileTagService;
        this.evidenceLinkService = evidenceLinkService;
        this.evidenceAnchorService = evidenceAnchorService;
        this.pluginJobService = pluginJobService;
        this.editorBridgeService = editorBridgeService;
        this.systemSettingService = systemSettingService;
        this.styleProfileResolver = styleProfileResolver;
        this.chatModelFactory = chatModelFactory;
        this.auxModelResolver = auxModelResolver;
        this.tokenUsageService = tokenUsageService;
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper();
        this.quota = quota != null ? quota : new PluginHostQuota();
    }

    /** 桥 ai.request 的频控入口（规范 v2.7 P2）：超限抛 HostQuotaException，PluginController 消费 */
    public void acquireAiQuota(String pluginId) {
        quota.acquireAi(pluginId);
    }

    public PluginHost forPlugin(String pluginId) {
        if (pluginId == null || pluginId.isBlank()) {
            throw new IllegalArgumentException("pluginId required");
        }
        return hosts.computeIfAbsent(pluginId, id -> new PluginHostImpl(id, this));
    }

    /** 分发插件工具前调用：把服务端上下文绑到当前线程（模型传的 projectId/userId 一律不可信）。 */
    public void bindCall(ToolContext ctx) {
        if (ctx == null) {
            currentCall.remove();
            return;
        }
        currentCall.set(new ToolCall(ctx.projectId(), ctx.conversationId(), ctx.userId(), ctx.modelId()));
    }

    /** 直接绑一份快照（工具线程语义：60 次/分钟窗口）。 */
    public void bindCall(ToolCall call) {
        if (call == null) currentCall.remove(); else currentCall.set(call);
    }

    /** 后台任务线程用：绑快照并标记「在 job 线程」，配额走 1200 次/分钟的大窗口。 */
    public void bindJob(ToolCall call) {
        bindCall(call);
        if (call != null) inJob.set(Boolean.TRUE);
    }

    public void clear() {
        currentCall.remove();
        inJob.remove();
    }

    ToolCall currentCall() {
        return currentCall.get();
    }

    /** 当前线程是否处于 JobContext 绑定期间（决定配额窗口）。 */
    boolean inJob() {
        return Boolean.TRUE.equals(inJob.get());
    }
}
