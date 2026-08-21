package com.checkba.service.ai.tools;

import com.checkba.config.AiModelProperties;
import com.checkba.model.ai.TaskInfo;
import com.checkba.repository.ProjectFileRepository;
import com.checkba.service.ProjectFileService;
import com.checkba.service.ai.BackgroundTaskService;
import com.checkba.service.ai.ChatModelFactory;
import com.checkba.service.ai.EditorBridgeService;
import com.checkba.service.ai.PlatformAiChannel;
import com.checkba.service.ai.PptxServiceClient;
import com.checkba.storage.ProjectStorageResolver;
import com.checkba.storage.StorageServiceFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * pptx_generate 卡死 RUNNING 任务的修复：registerTask 之后如果 pptx-service 调用抛异常
 * （网络失败是真实触发场景），此前两条异常路径（外层 catch、DB 注册失败的内层 catch）
 * 直接 return 不碰 taskId——BackgroundTaskService 里那条 RUNNING 永久留着，
 * hasActiveTasks 恒为 true，前端进度卡永远转下去。
 */
class PptxToolsTest {

    private PptxServiceClient pptxServiceClient;
    private BackgroundTaskService backgroundTaskService;
    private ChatModelFactory chatModelFactory;
    private ProjectFileRepository projectFileRepository;
    private ProjectFileService projectFileService;
    private ProjectStorageResolver storageResolver;
    private PptxTools pptxTools;

    @TempDir
    Path tmp;

    @BeforeEach
    void setUp() {
        pptxServiceClient = mock(PptxServiceClient.class);
        projectFileService = mock(ProjectFileService.class);
        projectFileRepository = mock(ProjectFileRepository.class);
        when(projectFileRepository.findByProjectIdOrderBySortOrderAsc(anyLong()))
                .thenReturn(Collections.emptyList());
        EditorBridgeService editorBridgeService = mock(EditorBridgeService.class);
        StorageServiceFactory storageServiceFactory = mock(StorageServiceFactory.class);
        com.checkba.config.AiModelProperties aiModelProperties = mock(AiModelProperties.class);
        backgroundTaskService = mock(BackgroundTaskService.class);
        chatModelFactory = mock(ChatModelFactory.class);
        when(chatModelFactory.resolveProvider()).thenReturn(AiModelProperties.Provider.OPENROUTER);
        when(chatModelFactory.resolveOpenRouterApiKey()).thenReturn("sk-test");
        when(chatModelFactory.resolveOpenRouterBaseUrl()).thenReturn("https://openrouter.ai/api/v1");
        when(chatModelFactory.resolveDefaultModel()).thenReturn("deepseek/deepseek-v4");
        PlatformAiChannel platformAiChannel = mock(PlatformAiChannel.class);
        storageResolver = mock(ProjectStorageResolver.class);
        when(storageResolver.resolve(anyString())).thenReturn(tmp.resolve("out.pptx"));

        pptxTools = new PptxTools(pptxServiceClient, projectFileService, projectFileRepository,
                editorBridgeService, storageServiceFactory, aiModelProperties, backgroundTaskService,
                chatModelFactory, platformAiChannel, storageResolver);
    }

    @Test
    @DisplayName("修复：pptx-service 网络失败（外层 catch）时已注册的后台任务被标记失败，不再永久卡在 RUNNING")
    void outerCatchFailsRegisteredTaskOnServiceException() {
        when(pptxServiceClient.isHealthy()).thenReturn(true);
        when(backgroundTaskService.registerTask(eq("conv-1"), eq(7L),
                eq(TaskInfo.TaskType.PPTX_GENERATE), any())).thenReturn("task-1");
        when(pptxServiceClient.generatePptxWithProgress(anyString(), anyString(), any(), anyString(),
                any(), anyBoolean(), any()))
                .thenThrow(new RuntimeException("Connection refused: pptx-service"));

        String result = pptxTools.performPptGenerationWithProgress(
                "尽调汇报", 42L, null, "报告", null, "zh",
                "deepseek/deepseek-v4", "conv-1", 7L, false);

        assertTrue(result.contains("PPTX 生成过程中出错"), result);
        assertTrue(result.contains("Connection refused"), result);
        verify(backgroundTaskService).failTask(eq("task-1"), contains("Connection refused"));
    }

    @Test
    @DisplayName("修复：文件已生成但 DB 注册失败（内层 catch）时同样标记后台任务失败")
    void innerCatchFailsRegisteredTaskWhenDbRegistrationThrows() throws Exception {
        Path localPath = tmp.resolve("out.pptx");
        Files.write(localPath, "fake pptx bytes".getBytes()); // 模拟 pptx-service 已经把文件写到本地

        PptxServiceClient.PptxGenerationResult ok = new PptxServiceClient.PptxGenerationResult();
        ok.setSuccess(true);
        ok.setProjectId("proj-1");
        ok.setPagesCount(5);
        ok.setEditable(true);

        when(pptxServiceClient.isHealthy()).thenReturn(true);
        when(backgroundTaskService.registerTask(eq("conv-1"), eq(7L),
                eq(TaskInfo.TaskType.PPTX_GENERATE), any())).thenReturn("task-2");
        when(pptxServiceClient.generatePptxWithProgress(anyString(), anyString(), any(), anyString(),
                any(), anyBoolean(), any())).thenReturn(ok);
        when(projectFileService.createOrUpdateFile(any(), any(), anyString(), anyString(),
                any(), anyString(), anyString(), any()))
                .thenThrow(new RuntimeException("DB 写入失败"));

        String result = pptxTools.performPptGenerationWithProgress(
                "尽调汇报", 42L, null, "报告", null, "zh",
                "deepseek/deepseek-v4", "conv-1", 7L, false);

        assertTrue(result.contains("PPTX 已生成但注册到数据库失败"), result);
        verify(backgroundTaskService).failTask(eq("task-2"), contains("DB 写入失败"));
    }

    @Test
    @DisplayName("无 conversationId/userId 时未注册任务，异常路径不应调用 failTask（没有 taskId 可标）")
    void noTaskRegisteredWhenConversationMissing() {
        when(pptxServiceClient.isHealthy()).thenReturn(true);
        when(pptxServiceClient.generatePptxSync(anyString(), anyString(), any(), anyString(), any(), anyBoolean()))
                .thenThrow(new RuntimeException("网络错误"));

        String result = pptxTools.performPptGenerationWithProgress(
                "尽调汇报", 42L, null, "报告", null, "zh",
                "deepseek/deepseek-v4", null, null, false);

        assertTrue(result.contains("PPTX 生成过程中出错"), result);
        verify(backgroundTaskService, never()).failTask(any(), any());
        verify(backgroundTaskService, never()).registerTask(any(), any(), any(), any());
    }
}
