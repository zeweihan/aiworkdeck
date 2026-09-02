package com.checkba.service.ai.tools;

import com.checkba.config.AiModelProperties;
import com.checkba.model.ai.TaskInfo;
import com.checkba.model.entity.ProjectFile;
import com.checkba.repository.ProjectFileRepository;
import com.checkba.service.ProjectFileService;
import com.checkba.service.ai.AllowedModels;
import com.checkba.service.ai.BackgroundTaskService;
import com.checkba.service.ai.ChatModelFactory;
import com.checkba.service.ai.PlatformAiChannel;
import com.checkba.service.ai.PptxServiceClient;
import com.checkba.service.ai.EditorBridgeService;
import com.checkba.storage.StorageServiceFactory;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * PPTX 工具集
 * 
 * 提供 Agent 操作 PPT 的完整能力：
 * 1. 搜索/列出项目中的 PPTX 文件
 * 2. 打开 PPTX 文件进行编辑
 * 3. 一键生成 PPTX（支持指定存放路径）
 * 4. 检查服务状态
 * 
 * 技术说明：
 * - 生成功能调用 banana-slides Python 微服务（Docker 容器）
 * - 生成的 PPTX 文件保存到指定目录并注册到数据库
 * - 用户可以通过文档编辑器打开和编辑生成的 PPT
 * - 支持进度流式推送到前端
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PptxTools implements AgentToolComponent {

    private final PptxServiceClient pptxServiceClient;
    private final ProjectFileService projectFileService;
    private final ProjectFileRepository projectFileRepository;
    private final EditorBridgeService editorBridgeService;
    private final StorageServiceFactory storageServiceFactory;
    private final AiModelProperties aiModelProperties;
    private final BackgroundTaskService backgroundTaskService;
    // AI PPT 的模型与密钥必须与 AI 对话同口径（供应商/默认模型/DB 密钥），不能只读 yml
    private final ChatModelFactory chatModelFactory;
    private final PlatformAiChannel platformAiChannel;

    private final com.checkba.storage.ProjectStorageResolver storageResolver;
    private static final Long AGENT_USER_ID = 10001L;

    // ==================== 文件管理工具 ====================

    @Tool("只列文件夹、不列文件，是 folderId 的来源：write_docx 的 parentFolderId、move_project_file 的 targetFolderId "
            + "都可从这里取（新建文件夹用 create_folder，文件的 fileId 用 doc_list_project_files）。"
            + "返回文件夹 ID、名称和路径。仅当用户明确指定要保存到某个特定文件夹时才需要调用此工具。")
    public String list_project_folders(
            @P("项目 ID") Long projectId
    ) {
        log.info("Tool: list_project_folders called for projectId={}", projectId);
        try {
            List<ProjectFile> allFiles = projectFileRepository.findByProjectIdOrderBySortOrderAsc(projectId);
            
            // 筛选出所有文件夹
            List<ProjectFile> folders = allFiles.stream()
                    .filter(f -> Boolean.TRUE.equals(f.getIsFolder()))
                    .filter(f -> !Boolean.TRUE.equals(f.getIsDeleted()))
                    .collect(Collectors.toList());
            
            if (folders.isEmpty()) {
                return "项目中没有文件夹。使用 pptx_generate 时可以将 parentId 留空，文件将保存在项目根目录。";
            }
            
            StringBuilder sb = new StringBuilder("项目文件夹列表 (共 " + folders.size() + " 个):\n");
            sb.append("- ID: null, 名称: 根目录 (不传 parentId 时默认)\n");
            
            for (ProjectFile f : folders) {
                String folderPath = getFileFolderPath(f.getParentId(), allFiles);
                String fullPath = folderPath.isEmpty() ? f.getName() : folderPath + "/" + f.getName();
                sb.append(String.format("- ID: %d, 名称: %s, 完整路径: /%s\n", 
                        f.getId(), f.getName(), fullPath));
            }
            sb.append("\n提示：在 pptx_generate 中使用上述文件夹 ID 作为 parentId，可以将生成的 PPT 保存到对应文件夹。");
            return sb.toString();
            
        } catch (Exception e) {
            log.error("Failed to list project folders", e);
            return "Error: " + e.getMessage();
        }
    }

    @Tool("PPTX 专用清单，也是 PPTX 文件 ID 的来源：所有 pptx_* 工具的 fileId 从这里或 pptx_search_files 获取"
            + "（doc_list_project_files 也列 pptx，但 pptx_* 工具请以本清单为准）。"
            + "列出项目中的所有 PPTX 演示文稿文件，返回文件 ID、名称和位置信息。")
    public String pptx_list_files(
            @P("项目 ID") Long projectId
    ) {
        log.info("Tool: pptx_list_files called for projectId={}", projectId);
        try {
            List<ProjectFile> allFiles = projectFileRepository.findByProjectIdOrderBySortOrderAsc(projectId);
            
            List<ProjectFile> pptxFiles = allFiles.stream()
                    .filter(f -> !Boolean.TRUE.equals(f.getIsFolder()))
                    .filter(f -> !Boolean.TRUE.equals(f.getIsDeleted()))
                    .filter(f -> isPptxFile(f.getName()))
                    .collect(Collectors.toList());
            
            if (pptxFiles.isEmpty()) {
                return "项目中没有 PPTX 演示文稿文件。可以使用 pptx_generate 工具生成新的 PPT。";
            }
            
            StringBuilder sb = new StringBuilder("项目中的 PPTX 文件列表 (共 " + pptxFiles.size() + " 个):\n");
            for (ProjectFile f : pptxFiles) {
                String folderPath = getFileFolderPath(f.getParentId(), allFiles);
                sb.append(String.format("- ID: %d, 名称: %s, 位置: %s\n", 
                        f.getId(), f.getName(), folderPath.isEmpty() ? "根目录" : folderPath));
            }
            sb.append("\n使用 pptx_open_file 工具可以打开指定文件进行编辑。");
            return sb.toString();
            
        } catch (Exception e) {
            log.error("Failed to list PPTX files", e);
            return "Error: " + e.getMessage();
        }
    }

    @Tool("搜索项目中的 PPTX 演示文稿文件。可以根据关键词搜索文件名。")
    public String pptx_search_files(
            @P("项目 ID") Long projectId,
            @P("搜索关键词（文件名包含），留空则列出所有 PPTX 文件") String keyword
    ) {
        log.info("Tool: pptx_search_files called for projectId={}, keyword={}", projectId, keyword);
        try {
            List<ProjectFile> allFiles = projectFileRepository.findByProjectIdOrderBySortOrderAsc(projectId);
            
            List<ProjectFile> pptxFiles = allFiles.stream()
                    .filter(f -> !Boolean.TRUE.equals(f.getIsFolder()))
                    .filter(f -> !Boolean.TRUE.equals(f.getIsDeleted()))
                    .filter(f -> isPptxFile(f.getName()))
                    .filter(f -> !StringUtils.hasText(keyword) || 
                            f.getName().toLowerCase().contains(keyword.toLowerCase()))
                    .collect(Collectors.toList());
            
            if (pptxFiles.isEmpty()) {
                if (StringUtils.hasText(keyword)) {
                    return "未找到包含关键词 '" + keyword + "' 的 PPTX 文件。可以使用 pptx_list_files 查看所有 PPTX 文件。";
                }
                return "项目中没有 PPTX 演示文稿文件。可以使用 pptx_generate 工具生成新的 PPT。";
            }
            
            StringBuilder sb = new StringBuilder();
            if (StringUtils.hasText(keyword)) {
                sb.append("搜索到 ").append(pptxFiles.size()).append(" 个包含 '").append(keyword).append("' 的 PPTX 文件:\n");
            } else {
                sb.append("项目中的 PPTX 文件 (共 ").append(pptxFiles.size()).append(" 个):\n");
            }
            
            for (ProjectFile f : pptxFiles) {
                String folderPath = getFileFolderPath(f.getParentId(), allFiles);
                sb.append(String.format("- ID: %d, 名称: %s, 位置: %s\n", 
                        f.getId(), f.getName(), folderPath.isEmpty() ? "根目录" : folderPath));
            }
            sb.append("\n使用 pptx_open_file 工具可以打开指定文件进行编辑。");
            return sb.toString();
            
        } catch (Exception e) {
            log.error("Failed to search PPTX files", e);
            return "Error: " + e.getMessage();
        }
    }

    @Tool("打开指定的 PPTX 文件进行编辑。文件会在用户的文档编辑器中打开。")
    public String pptx_open_file(
            @P("文件 ID（从 pptx_list_files 或 pptx_search_files 获取）") Long fileId
    ) {
        log.info("Tool: pptx_open_file called for fileId={}", fileId);
        try {
            ProjectFile file = projectFileService.getFile(fileId);
            if (file == null) {
                return "Error: 文件不存在，ID=" + fileId;
            }
            String denied = ToolFileGuard.rejectIfOutsideProject(file);
            if (denied != null) return denied;

            if (!isPptxFile(file.getName())) {
                return "Error: 该文件不是 PPTX 格式: " + file.getName();
            }
            
            // 通过 SSE 发送打开文件指令到前端
            editorBridgeService.sendOpenFileAction(file);
            
            return String.format("已发送打开文件指令。文件名: %s。\n" +
                    "查看内容与格式可用 pptx_inspect_format（无需等待加载），" +
                    "修改文本与格式可用 pptx_apply_format（修改后编辑器自动重载）。",
                    file.getName());
            
        } catch (Exception e) {
            log.error("Failed to open PPTX file", e);
            return "Error: " + e.getMessage();
        }
    }

    // ==================== 服务检查工具 ====================

    @ToolMeta(displayName = "检查PPT服务", category = "pptx")
    @Tool("检查 PPTX 生成服务是否可用。在生成 PPT 之前应先调用此工具确认服务状态。")
    public String pptx_check_service() {
        log.info("Tool: pptx_check_service called");
        try {
            boolean healthy = pptxServiceClient.isHealthy();
            if (healthy) {
                return "PPTX 生成服务运行正常，可以开始生成 PPT。";
            } else {
                return "PPTX 生成服务当前不可用（本机的 PPT 服务组件没有就绪）。请稍后重试；这只影响 PPT 生成，不影响读文件与 OCR。";
            }
        } catch (Exception e) {
            log.error("Failed to check PPTX service", e);
            return "检查服务状态失败: " + e.getMessage() + "。这只影响 PPT 生成，不影响读文件与 OCR。";
        }
    }

    @ToolMeta(displayName = "生成PPT演示文稿", category = "pptx", fileEffect = "ADDED", fileArg = "fileName")
    @Tool("根据主题一键生成 PPTX 演示文稿。AI 将自动生成大纲、内容描述和幻灯片图片，最终输出可编辑的 PPTX 文件。默认保存到项目根目录（parentId 不传或传 null），只有用户明确指定保存位置时才需要查询文件夹。")
    public String pptx_generate(
            @P("PPT 主题或详细描述，如：'AI 在法律行业的应用' 或 '公司年度总结报告，包含业绩、成就和未来规划'") String topic,
            @P("项目 ID，生成的 PPTX 将关联到此项目") Long projectId,
            @P("父文件夹 ID（可选，传 null 表示保存到项目根目录）。可以调用 list_project_folders 获取可用的文件夹 ID。") Long parentId,
            @P("自定义文件名（可选，不含扩展名）。不指定则自动生成。") String fileName,
            @P("PPT 风格描述（可选），如：'科技风'、'商务简约'、'学术正式'。留空则使用默认风格。") String style,
            @P("输出语言：zh（中文，默认）、en（英文）、ja（日语）") String language
    ) {
        // 模型 ID 由 ToolRegistry 通过线程上下文透传（不暴露给 LLM 参数）
        return pptx_generate(topic, projectId, parentId, fileName, style, language, ToolContextHolder.currentModelId());
    }
    
    /**
     * 带 modelId 参数的 pptx_generate 内部版本
     * 用于从 AgentOrchestrator 调用时传递用户选择的模型
     */
    public String pptx_generate(String topic, Long projectId, Long parentId, 
                                String fileName, String style, String language, String modelId) {
        
        log.info("Tool: pptx_generate called (UI Interceptor), topic={}", topic);
        
        // 构造参数 Map
        java.util.Map<String, Object> params = new java.util.HashMap<>();
        params.put("topic", topic);
        params.put("projectId", projectId);
        params.put("parentId", parentId);
        params.put("fileName", fileName);
        params.put("style", style);
        params.put("language", language);
        params.put("modelId", modelId);
        
        // 发送 SSE 唤起 UI
        editorBridgeService.sendPptConfigAction(params);
        
        return "已唤起 PPT 生成配置界面，请在界面上选择生成选项（可编辑版/纯图片版）并确认。等待用户操作...";
    }
    
    /**
     * 执行实际的 PPT 生成任务 (由 Controller 调用)
     * 
     * @param exportEditable 是否生成可编辑版本
     */
    public String performPptGenerationWithProgress(String topic, Long projectId, Long parentId, 
                                               String fileName, String style, String language, 
                                               String modelId, String conversationId, Long userId,
                                               boolean exportEditable) {
        return pptx_generate_internal(topic, projectId, parentId, fileName, style, language, 
                                       modelId, conversationId, userId, exportEditable);
    }
    
    /**
     * 带进度报告的 pptx_generate 版本
     * 用于从 AgentOrchestrator 调用时传递 conversationId 和 userId 以启用实时进度推送
     * 
     * @param conversationId SSE 连接的会话 ID，用于推送进度事件
     * @param userId 用户 ID，用于任务归属
     */
    // Old method removed or replaced above

    

    /**
     * pptx_generate 内部实现
     */
    private String pptx_generate_internal(String topic, Long projectId, Long parentId, 
                                          String fileName, String style, String language, 
                                          String modelId, String conversationId, Long userId,
                                          boolean exportEditable) {
        log.info("Info: pptx_generate_internal start, topic={}, editable={}", topic, exportEditable);

        // 提到外层 try 之外声明：下面两条异常路径（DB 注册失败的内层 catch、方法级的外层 catch）
        // 都要在失败时把这个任务标成 failTask，否则 registerTask 登记的这条 RUNNING 永远留在
        // BackgroundTaskService 的三张表里——hasActiveTasks 恒为 true，前端进度卡永远转下去。
        String taskId = null;
        try {
            // 检查服务
            if (!pptxServiceClient.isHealthy()) {
                return "错误：PPTX 生成服务当前不可用（本机的 PPT 服务组件没有就绪）。请稍后重试；这只影响 PPT 生成，不影响读文件与 OCR。";
            }
            
            // 验证父文件夹存在（如果指定）
            String folderPath = "";
            if (parentId != null) {
                try {
                    ProjectFile parentFolder = projectFileService.getFile(parentId);
                    if (parentFolder == null || !Boolean.TRUE.equals(parentFolder.getIsFolder())) {
                        return "错误：指定的父文件夹不存在或不是文件夹，ID=" + parentId;
                    }
                    String denied = ToolFileGuard.rejectIfOutsideProject(parentFolder);
                    if (denied != null) return denied;
                    folderPath = getFileFolderPath(parentId, 
                            projectFileRepository.findByProjectIdOrderBySortOrderAsc(projectId));
                } catch (Exception e) {
                    return "错误：无法验证父文件夹，ID=" + parentId + ", " + e.getMessage();
                }
            }
            
            // 确定文件名
            String finalFileName;
            if (StringUtils.hasText(fileName)) {
                // 移除可能的扩展名，确保以 .pptx 结尾
                finalFileName = fileName.replaceAll("\\.[pP][pP][tT][xX]?$", "") + ".pptx";
            } else {
                finalFileName = "presentation_" + System.currentTimeMillis() + ".pptx";
            }
            
            // 构建物理存储路径
            String storagePath = buildPhysicalPath(projectId, parentId, finalFileName);
            Path localPath = storageResolver.resolve(storagePath);
            
            // 确保目录存在
            if (!Files.exists(localPath.getParent())) {
                Files.createDirectories(localPath.getParent());
            }
            
            // 构建模型配置（使用用户选择的模型）
            PptxServiceClient.ModelConfig modelConfig = buildModelConfig(modelId);
            
            // 调用服务生成
            log.info("Starting PPTX generation to: {}, using model: {}, exportEditable: {}", localPath, modelId, exportEditable);
            PptxServiceClient.PptxGenerationResult result;
            
            // 如果有 conversationId，使用带进度回调的版本（taskId 已提到外层 try 之外声明）
            if (conversationId != null && userId != null) {
                // 注册后台任务
                taskId = backgroundTaskService.registerTask(
                        conversationId, userId, TaskInfo.TaskType.PPTX_GENERATE, 
                        15 * 60  // 预估 15 分钟
                );
                
                final String finalTaskId = taskId;
                result = pptxServiceClient.generatePptxWithProgress(
                        topic,
                        language != null ? language : "zh",
                        style,
                        localPath.toString(),
                        modelConfig,
                        exportEditable, // Use passed parameter
                        (progress, stage, message) -> {
                            // 通过 BackgroundTaskService 发送进度更新
                            backgroundTaskService.updateProgress(finalTaskId, progress, message, stage);
                        }
                );
            } else {
                // 无进度回调的同步版本
                result = pptxServiceClient.generatePptxSync(
                        topic,
                        language != null ? language : "zh",
                        style,
                        localPath.toString(),
                        modelConfig,
                        exportEditable // Use passed parameter
                );
            }
            
            if (!result.isSuccess()) {
                if (taskId != null) {
                    backgroundTaskService.failTask(taskId, result.getError());
                }
                return "PPTX 生成失败: " + result.getError();
            }
            
            // 注册到项目文件库
            String wpsId = "pptx_" + System.currentTimeMillis() + "_" + 
                    java.util.UUID.randomUUID().toString().substring(0, 8);
            
            try {
                ProjectFile pf = projectFileService.createOrUpdateFile(
                        projectId, parentId, finalFileName, "pptx", 
                        Files.size(localPath), storagePath, wpsId, AGENT_USER_ID
                );
                
                log.info("PPTX registered to database: id={}, name={}, parentId={}", 
                        pf.getId(), finalFileName, parentId);
                
                String locationInfo = folderPath.isEmpty() ? "项目根目录" : folderPath;
                
                StringBuilder successMsg = new StringBuilder();
                successMsg.append(String.format(
                        "PPTX 生成成功！\n" +
                        "- 文件名: %s\n" +
                        "- 页数: %d\n" +
                        "- 文件 ID: %d\n" +
                        "- 存放位置: %s\n" +
                        "- PPTX服务项目ID: %s\n" +
                        "- 版本类型: %s\n",
                        finalFileName, result.getPagesCount(), pf.getId(), locationInfo, result.getProjectId(),
                        result.isEditable() ? "可编辑版（文字/表格可直接修改）" : "纯图片版"
                ));
                
                // 添加警告信息（如有）
                if (result.getWarnings() != null && !result.getWarnings().isEmpty()) {
                    successMsg.append("\n**注意**:\n");
                    for (String w : result.getWarnings()) {
                         successMsg.append("- ").append(w).append("\n");
                    }
                }
                
                // 通知前端刷新文件列表
                editorBridgeService.sendRefreshFilesAction();
                
                successMsg.append("\n文件已显示在项目文件树中。\n\n");
                
                if (result.isEditable()) {
                    // 可编辑版本的提示
                    successMsg.append("**可编辑版本已生成**: 文件中的文字和表格可以直接在文档编辑器或 PowerPoint 中编辑。\n\n");
                } else {
                    // 纯图片版本的提示（可编辑导出失败回退的情况）
                    successMsg.append("**注意**: 当前生成的是纯图片版 PPT（可编辑导出未成功）。\n");
                    successMsg.append("如需可编辑版本，请稍后使用 pptx_export_editable 工具重试，传入上述 PPTX服务项目ID。\n\n");
                }
                
                successMsg.append("**页面修改**: 可以使用以下工具进行修改：\n");
                successMsg.append("- pptx_get_project_pages: 查看所有页面\n");
                successMsg.append("- pptx_edit_page: 用自然语言修改页面（如'把标题改成红色'）\n");
                successMsg.append("- pptx_refine_outline: 修改大纲结构（增删页面）\n");
                successMsg.append("- pptx_inspect_format + pptx_apply_format: 直接修改文件中的文本与格式（可编辑版适用）");
                
                // 标记后台任务完成
                if (taskId != null) {
                    backgroundTaskService.completeTask(taskId, result.getProjectId());
                }
                
                return successMsg.toString();
                
            } catch (Exception e) {
                // 文件已生成但注册失败：任务对用户而言并未真正完成（文件不在项目文件树里可用），
                // 之前这里直接 return，taskId 那条 RUNNING 记录永远留在 BackgroundTaskService 里。
                log.warn("PPTX file created but DB registration failed", e);
                if (taskId != null) {
                    backgroundTaskService.failTask(taskId, "PPTX 已生成但注册到数据库失败: " + e.getMessage());
                }
                return String.format(
                        "PPTX 已生成但注册到数据库失败。\n" +
                        "- 文件名: %s\n" +
                        "- 页数: %d\n" +
                        "- 路径: %s\n" +
                        "- 错误: %s",
                        finalFileName, result.getPagesCount(), localPath.toString(), e.getMessage()
                );
            }

        } catch (Exception e) {
            // 同上：这条外层 catch 覆盖服务不可用/网络失败等更早期的异常，taskId 若已注册
            // 同样必须标失败，否则这条 RUNNING 记录永远回收不了（本条是 dev-board#74 的触发场景：
            // AI 调 pptx_generate、pptx-service 网络失败）。
            log.error("PPTX generation failed", e);
            if (taskId != null) {
                backgroundTaskService.failTask(taskId, e.getMessage());
            }
            return "PPTX 生成过程中出错: " + e.getMessage();
        }
    }

    @ToolMeta(displayName = "生成PPT大纲", category = "pptx")
    @Tool("生成 PPTX 大纲（不生成完整 PPT）。用于让用户先审阅和修改大纲结构，确认后再生成完整 PPT。")
    public String pptx_generate_outline(
            @P("PPT 主题或详细描述") String topic,
            @P("输出语言：zh（中文，默认）、en（英文）、ja（日语）") String language
    ) {
        // 模型 ID 由 ToolRegistry 通过线程上下文透传
        return pptx_generate_outline(topic, language, ToolContextHolder.currentModelId());
    }
    
    /**
     * 带 modelId 参数的 pptx_generate_outline 内部版本
     */
    public String pptx_generate_outline(String topic, String language, String modelId) {
        log.info("Tool: pptx_generate_outline called, topic={}, modelId={}", 
                topic.length() > 50 ? topic.substring(0, 50) + "..." : topic, modelId);
        
        try {
            // 检查服务
            if (!pptxServiceClient.isHealthy()) {
                return "错误：PPTX 生成服务当前不可用（本机的 PPT 服务组件没有就绪）。请稍后重试。";
            }
            
            // 创建项目
            String serviceProjectId = pptxServiceClient.createProject(topic);
            
            // 构建模型配置（使用用户选择的模型）
            PptxServiceClient.ModelConfig modelConfig = buildModelConfig(modelId);
            
            // 生成大纲（传递模型配置）
            cn.hutool.json.JSONArray pages = pptxServiceClient.generateOutline(
                    serviceProjectId, 
                    language != null ? language : "zh",
                    modelConfig
            );
            
            // 格式化输出
            StringBuilder sb = new StringBuilder();
            sb.append("PPTX 大纲生成成功！共 ").append(pages.size()).append(" 页：\n\n");
            sb.append("服务项目 ID: ").append(serviceProjectId).append("\n\n");
            
            for (int i = 0; i < pages.size(); i++) {
                cn.hutool.json.JSONObject page = pages.getJSONObject(i);
                cn.hutool.json.JSONObject outlineContent = page.getJSONObject("outline_content");
                
                if (outlineContent != null) {
                    String title = outlineContent.getStr("title", "未命名");
                    sb.append("第 ").append(i + 1).append(" 页: ").append(title).append("\n");
                    
                    cn.hutool.json.JSONArray points = outlineContent.getJSONArray("points");
                    if (points != null) {
                        for (int j = 0; j < points.size(); j++) {
                            sb.append("  - ").append(points.getStr(j)).append("\n");
                        }
                    }
                    sb.append("\n");
                }
            }
            
            sb.append("如需继续生成完整 PPT，请使用 pptx_generate 工具。");
            
            return sb.toString();
            
        } catch (Exception e) {
            log.error("PPTX outline generation failed", e);
            return "大纲生成失败: " + e.getMessage();
        }
    }

    // ==================== 存量 PPTX 格式识别与操作（/api/pptx/*） ====================
    // 注：曾有 pptx_smart_modify（编辑器桥 ppt_* 命令分支 + /edit-pptx-slide AI 改图分支），
    // 两条路径均已失效（前端明确拒绝 ppt_*、服务端点不存在），随本组工具上线一并下线。
    // 纯图像页的 AI 改图能力本期不恢复。

    @ToolMeta(displayName = "读取PPT格式", category = "pptx")
    @Tool("读取 PPTX 文件的结构化内容与格式全览（直接读文件，无需在编辑器中打开）。" +
          "返回每页每个形状（shape）的段落/run 文本及其格式：字体、中文字体、字号、粗体/斜体/下划线/删除线、" +
          "高亮、颜色、对齐、行距、段距、项目符号，以及表格的行列与单元格内容。" +
          "所有定位索引（slide/shape/paragraph/run/row/col）从 0 开始。" +
          "修改 PPT 文本或格式前必须先调用本工具获取定位索引，再用 pptx_apply_format 执行修改。")
    public String pptx_inspect_format(
            @P("文件 ID（从 pptx_list_files 或 pptx_search_files 获取）") Long fileId,
            @P("页码（从 0 开始，可选）。指定后只返回该页（推荐，输出更精简）；传 null 返回全部页") Integer slideIndex
    ) {
        log.info("Tool: pptx_inspect_format called, fileId={}, slideIndex={}", fileId, slideIndex);
        try {
            ProjectFile file = projectFileService.getFile(fileId);
            if (file == null) {
                return "Error: 文件不存在，ID=" + fileId;
            }
            String denied = ToolFileGuard.rejectIfOutsideProject(file);
            if (denied != null) return denied;
            if (!isPptxFile(file.getName())) {
                return "Error: 该文件不是 PPTX 格式: " + file.getName();
            }
            Path localPath = storageResolver.resolve(file.getFilePath());
            if (!Files.exists(localPath)) {
                return "Error: 文件不存在于本地磁盘: " + localPath;
            }
            cn.hutool.json.JSONObject data = pptxServiceClient.inspectPptx(localPath.toString());

            if (slideIndex != null) {
                int slideCount = data.getInt("slide_count", 0);
                if (slideIndex < 0 || slideIndex >= slideCount) {
                    return String.format("Error: 页码越界 slideIndex=%d（共 %d 页，从 0 开始）", slideIndex, slideCount);
                }
                cn.hutool.json.JSONArray slides = data.getJSONArray("slides");
                cn.hutool.json.JSONObject filtered = new cn.hutool.json.JSONObject();
                filtered.set("slide_count", slideCount);
                filtered.set("slides", new cn.hutool.json.JSONArray().set(slides.getJSONObject(slideIndex)));
                return filtered.toString();
            }
            return data.toString();

        } catch (Exception e) {
            log.error("Failed to inspect PPTX format", e);
            return "读取 PPT 格式失败: " + e.getMessage();
        }
    }

    @ToolMeta(displayName = "设置PPT格式", category = "pptx", fileEffect = "MODIFIED")
    @Tool("对 PPTX 文件批量执行文本与格式修改（直接改文件；完成后编辑器自动重载显示结果）。" +
          "使用顺序：先 pptx_inspect_format 获取 0 起的定位索引，再调用本工具。opsJson 是 JSON 数组，每项一个操作，六种 action：\n" +
          "1. {\"action\":\"set_run_format\",\"slide\":0,\"shape\":1,\"paragraph\":0,\"run\":0,\"format\":{…}}（省略 run 作用于该段全部 run，省略 paragraph 作用于全部段落）\n" +
          "2. {\"action\":\"set_paragraph_format\",\"slide\":0,\"shape\":1,\"paragraph\":0,\"format\":{…}}（省略 paragraph 作用于全部段落）\n" +
          "3. {\"action\":\"replace_text\",\"slide\":0,\"shape\":1,\"find\":\"旧文本\",\"replace\":\"新文本\"}（run 级匹配替换）\n" +
          "4. {\"action\":\"set_shape_text\",\"slide\":0,\"shape\":1,\"text\":\"整框重写的多行文本\"}\n" +
          "5. {\"action\":\"set_cell_text\",\"slide\":0,\"shape\":2,\"row\":0,\"col\":1,\"text\":\"单元格文本\"}\n" +
          "6. {\"action\":\"set_cell_format\",\"slide\":0,\"shape\":2,\"row\":0,\"col\":1,\"format\":{…}}\n" +
          "format 可用键——run 级：bold/italic/underline/strike(删除线)/highlight(高亮色如'#FFFF00')/color(文字色如'#FF0000')/" +
          "font_name(西文字体)/ea_font(中文字体如'楷体')/size_pt(字号磅值)；" +
          "段落级：align(left|center|right|justify)/line_spacing(行距倍数如1.5)/space_before_pt/space_after_pt/bullet(true|false)/number_start(编号起始值)。" +
          "落字文本自动清除 markdown 标记并转为真实格式。本工具只能改文本与格式，不能编辑图片内容（AI 改图能力当前不可用）。")
    public String pptx_apply_format(
            @P("文件 ID（从 pptx_list_files 或 pptx_search_files 获取）") Long fileId,
            @P("操作数组的 JSON 字符串，见工具描述中的六种 action 示例") String opsJson
    ) {
        log.info("Tool: pptx_apply_format called, fileId={}, opsJson length={}",
                fileId, opsJson != null ? opsJson.length() : 0);
        try {
            if (!StringUtils.hasText(opsJson)) {
                return "Error: 缺少 opsJson 参数（JSON 操作数组）";
            }
            cn.hutool.json.JSONArray ops;
            try {
                ops = cn.hutool.json.JSONUtil.parseArray(opsJson);
            } catch (Exception e) {
                return "Error: opsJson 不是合法的 JSON 数组: " + e.getMessage();
            }
            if (ops.isEmpty()) {
                return "Error: opsJson 不能为空数组";
            }

            ProjectFile file = projectFileService.getFile(fileId);
            if (file == null) {
                return "Error: 文件不存在，ID=" + fileId;
            }
            String denied = ToolFileGuard.rejectIfOutsideProject(file);
            if (denied != null) return denied;
            if (!isPptxFile(file.getName())) {
                return "Error: 该文件不是 PPTX 格式: " + file.getName();
            }
            Path localPath = storageResolver.resolve(file.getFilePath());
            if (!Files.exists(localPath)) {
                return "Error: 文件不存在于本地磁盘: " + localPath;
            }

            cn.hutool.json.JSONObject data = pptxServiceClient.formatPptx(localPath.toString(), ops);
            int applied = data.getInt("applied", 0);
            int failed = data.getInt("failed", 0);

            // 收尾三步：更新 wpsFileId 强制编辑器绕过缓存重新下载 → 存库 → 通知前端重载
            String newWpsFileId = generateNewWpsFileId(file.getProjectId());
            file.setWpsFileId(newWpsFileId);
            file.setUpdatedAt(LocalDateTime.now());
            try {
                file.setFileSize(Files.size(localPath));
            } catch (Exception e) {
                log.warn("Failed to update file size: {}", e.getMessage());
            }
            projectFileRepository.save(file);
            editorBridgeService.sendReloadFileAction(file);

            StringBuilder sb = new StringBuilder();
            sb.append(String.format("PPT 格式操作完成：成功 %d 项，失败 %d 项。\n", applied, failed));
            if (failed > 0) {
                cn.hutool.json.JSONArray results = data.getJSONArray("results");
                sb.append("失败明细：\n");
                for (int i = 0; i < results.size(); i++) {
                    cn.hutool.json.JSONObject r = results.getJSONObject(i);
                    if (!r.getBool("ok", true)) {
                        sb.append(String.format("- op #%d (%s): %s\n",
                                r.getInt("index", i), r.getStr("action"), r.getStr("error")));
                    }
                }
                sb.append("可用 pptx_inspect_format 重新核对定位索引后重试失败项。\n");
            }
            sb.append("文件已更新，文档编辑器将自动重新加载显示修改结果。");
            return sb.toString();

        } catch (Exception e) {
            log.error("Failed to apply PPTX format ops", e);
            return "PPT 格式操作失败: " + e.getMessage();
        }
    }

    // ==================== PPTX 编辑工具 ====================

    @Tool("使用自然语言编辑 PPT 页面图片。可以用口语化的方式描述修改需求，如'把标题改成红色'、'换成饼图'、'增大字体'等。这是基于 AI 图片编辑的能力，适合对已生成的 PPT 页面进行微调。")
    public String pptx_edit_page(
            @P("PPTX 服务中的项目 ID（通过 pptx_generate 生成 PPT 时返回）") String serviceProjectId,
            @P("页面 ID（从 pptx_get_project_pages 获取）") String pageId,
            @P("自然语言修改指令，如：'把这个图换成饼图'、'标题字体加大'、'背景改成蓝色渐变'") String editInstruction
    ) {
        log.info("Tool: pptx_edit_page called, projectId={}, pageId={}, instruction={}", 
                serviceProjectId, pageId, editInstruction.length() > 50 ? editInstruction.substring(0, 50) + "..." : editInstruction);
        
        try {
            // 调用 PPTX 服务的页面编辑 API
            String taskId = pptxServiceClient.editPageImage(serviceProjectId, pageId, editInstruction);
            
            // 等待任务完成
            cn.hutool.json.JSONObject taskResult = pptxServiceClient.waitForTask(serviceProjectId, taskId);
            
            if (taskResult == null) {
                return "页面编辑失败：任务超时或执行出错";
            }
            
            return String.format("页面编辑成功！\n" +
                    "- 项目 ID: %s\n" +
                    "- 页面 ID: %s\n" +
                    "- 修改指令: %s\n\n" +
                    "页面图片已更新。可以使用 pptx_export_editable 导出可编辑的 PPTX 文件。",
                    serviceProjectId, pageId, editInstruction);
            
        } catch (Exception e) {
            log.error("Failed to edit page", e);
            return "页面编辑失败: " + e.getMessage();
        }
    }

    @Tool("获取项目中的所有页面信息。返回每个页面的 ID、标题、状态和缩略图 URL。")
    public String pptx_get_project_pages(
            @P("PPTX 服务中的项目 ID") String serviceProjectId
    ) {
        log.info("Tool: pptx_get_project_pages called, projectId={}", serviceProjectId);
        
        try {
            cn.hutool.json.JSONObject projectInfo = pptxServiceClient.getProjectWithPages(serviceProjectId);
            cn.hutool.json.JSONArray pages = projectInfo.getJSONArray("pages");
            
            if (pages == null || pages.isEmpty()) {
                return "项目中没有页面。请先使用 pptx_generate 生成 PPT。";
            }
            
            StringBuilder sb = new StringBuilder("项目页面列表 (共 " + pages.size() + " 页):\n\n");
            
            for (int i = 0; i < pages.size(); i++) {
                cn.hutool.json.JSONObject page = pages.getJSONObject(i);
                String pageId = page.getStr("id");
                String title = "未命名";
                cn.hutool.json.JSONObject outlineContent = page.getJSONObject("outline_content");
                if (outlineContent != null) {
                    title = outlineContent.getStr("title", "未命名");
                }
                String status = page.getStr("status", "UNKNOWN");
                String imagePath = page.getStr("generated_image_path");
                
                sb.append(String.format("第 %d 页:\n", i + 1));
                sb.append(String.format("  - ID: %s\n", pageId));
                sb.append(String.format("  - 标题: %s\n", title));
                sb.append(String.format("  - 状态: %s\n", status));
                sb.append(String.format("  - 有图片: %s\n\n", imagePath != null ? "是" : "否"));
            }
            
            sb.append("使用 pptx_edit_page 工具可以编辑指定页面。");
            return sb.toString();
            
        } catch (Exception e) {
            log.error("Failed to get project pages", e);
            return "获取项目页面失败: " + e.getMessage();
        }
    }

    @Tool("使用自然语言修改 PPT 大纲结构。可以增加、删除、修改页面，调整顺序等。")
    public String pptx_refine_outline(
            @P("PPTX 服务中的项目 ID") String serviceProjectId,
            @P("用户的修改要求，如：'增加一页关于市场分析的内容'、'把第3页和第4页合并'、'删除结论页'") String userRequirement,
            @P("输出语言：zh（中文，默认）、en（英文）") String language
    ) {
        log.info("Tool: pptx_refine_outline called, projectId={}, requirement={}", 
                serviceProjectId, userRequirement.length() > 50 ? userRequirement.substring(0, 50) + "..." : userRequirement);
        
        try {
            cn.hutool.json.JSONObject result = pptxServiceClient.refineOutline(
                    serviceProjectId, 
                    userRequirement, 
                    language != null ? language : "zh"
            );
            
            cn.hutool.json.JSONArray pages = result.getJSONArray("pages");
            
            StringBuilder sb = new StringBuilder("大纲修改成功！\n\n");
            sb.append("新的大纲结构 (共 ").append(pages.size()).append(" 页):\n");
            
            for (int i = 0; i < pages.size(); i++) {
                cn.hutool.json.JSONObject page = pages.getJSONObject(i);
                cn.hutool.json.JSONObject outlineContent = page.getJSONObject("outline_content");
                String title = outlineContent != null ? outlineContent.getStr("title", "未命名") : "未命名";
                sb.append(String.format("%d. %s\n", i + 1, title));
            }
            
            sb.append("\n修改后需要重新生成描述和图片。可以使用以下工具：\n");
            sb.append("- pptx_regenerate_descriptions: 重新生成页面描述\n");
            sb.append("- pptx_regenerate_images: 重新生成页面图片\n");
            
            return sb.toString();
            
        } catch (Exception e) {
            log.error("Failed to refine outline", e);
            return "大纲修改失败: " + e.getMessage();
        }
    }

    @Tool("导出可编辑的 PPTX 文件。与普通导出不同，此功能会智能提取文字和表格，生成真正可编辑的 PPT（而非纯图片）。这是 beta 功能，使用 MinerU 进行智能解析。")
    public String pptx_export_editable(
            @P("PPTX 服务中的项目 ID") String serviceProjectId,
            @P("导出文件名（不含扩展名，可选）") String filename,
            @P("使用的 AI 模型 ID（可选，用于生成干净背景图）") String modelId
    ) {
        log.info("Tool: pptx_export_editable called, projectId={}, filename={}, modelId={}", 
                serviceProjectId, filename, modelId);
        
        try {
            // 构建模型配置（用于生成干净背景图）
            PptxServiceClient.ModelConfig modelConfig = buildModelConfig(modelId);
            log.info("Using model config for editable export: imageModel={}", modelConfig.getImageModel());
            
            // 创建导出任务（传递模型配置）
            String taskId = pptxServiceClient.startExportEditable(serviceProjectId, filename, modelConfig);
            
            // 等待任务完成
            cn.hutool.json.JSONObject taskResult = pptxServiceClient.waitForTask(serviceProjectId, taskId);
            
            if (taskResult == null) {
                return "可编辑 PPTX 导出失败：任务超时或执行出错";
            }
            
            // 获取下载链接
            cn.hutool.json.JSONObject progress = taskResult.getJSONObject("progress");
            String downloadUrl = progress != null ? progress.getStr("download_url") : null;
            String exportedFilename = progress != null ? progress.getStr("filename") : filename + ".pptx";
            
            return String.format("可编辑 PPTX 导出成功！\n" +
                    "- 文件名: %s\n" +
                    "- 下载链接: %s\n\n" +
                    "这个 PPTX 文件中的文字和表格都可以直接编辑。",
                    exportedFilename, downloadUrl != null ? downloadUrl : "请在项目导出目录查看");
            
        } catch (Exception e) {
            log.error("Failed to export editable PPTX", e);
            return "可编辑 PPTX 导出失败: " + e.getMessage();
        }
    }

    // ==================== 辅助方法 ====================

    /**
     * 判断文件是否是 PPTX 格式
     */
    private boolean isPptxFile(String fileName) {
        if (fileName == null) return false;
        String lower = fileName.toLowerCase();
        return lower.endsWith(".pptx") || lower.endsWith(".ppt");
    }

    /**
     * 获取文件所在文件夹的路径
     */
    private String getFileFolderPath(Long parentId, List<ProjectFile> allFiles) {
        if (parentId == null) {
            return "";
        }
        
        StringBuilder pathBuilder = new StringBuilder();
        Long currentParentId = parentId;
        int depth = 0;
        
        while (currentParentId != null && depth < 20) {
            final Long searchId = currentParentId;
            ProjectFile parent = allFiles.stream()
                    .filter(f -> f.getId().equals(searchId))
                    .findFirst()
                    .orElse(null);
            
            if (parent == null) break;
            
            if (pathBuilder.length() > 0) {
                pathBuilder.insert(0, "/");
            }
            pathBuilder.insert(0, parent.getName());
            currentParentId = parent.getParentId();
            depth++;
        }
        
        return pathBuilder.toString();
    }

    /**
     * 构建文件的物理存储路径
     * 格式: projects/{projectId}/{logical_path}/{fileName}
     */
    private String buildPhysicalPath(Long projectId, Long parentId, String fileName) {
        List<ProjectFile> allFiles = projectFileRepository.findByProjectIdOrderBySortOrderAsc(projectId);
        String logicalPath = getFileFolderPath(parentId, allFiles);
        
        if (StringUtils.hasText(logicalPath)) {
            return String.format("projects/%d/%s/%s", projectId, logicalPath, fileName);
        } else {
            return String.format("projects/%d/%s", projectId, fileName);
        }
    }

    /**
     * 构建下发给 pptx-service 的模型配置（供应商 / 密钥 / 文本模型 / 图像模型）。
     *
     * <p>口径与 AI 对话完全一致，都由 {@link ChatModelFactory} 解析：供应商读
     * {@code ai.activeProvider}，密钥读 DB 的 {@code external.openrouter.*}（空白视为未配置、
     * 回退 yml），默认模型走 {@code resolveDefaultModel()}。此前这里只读 yml 的 OpenRouter key，
     * 用户在设置页/向导里填的 key 一律用不上，且 AWD_CLOUD 平台通道完全没有分支——
     * 加上 pptx-service 侧 model_config 消费端被 re-vendor 删掉，AI PPT 三层同时断。
     *
     * @param modelId 本轮会话选定的模型 ID（由 ToolContextHolder 透传，可为空）
     * @return ModelConfig 模型配置对象
     * @throws com.checkba.exception.FeatureNotConfiguredException 当前供应商跑不了 AI PPT（本地 Ollama / 缺 key）
     * @throws com.checkba.service.account.AccountException 平台通道密钥不可用（原样抛，不回退 BYOK key）
     */
    private PptxServiceClient.ModelConfig buildModelConfig(String modelId) {
        AiModelProperties.OpenRouter orConfig = aiModelProperties.getOpenRouter();
        AiModelProperties.Provider provider = chatModelFactory.resolveProvider();

        // 文本模型：会话选定的模型优先；空或非白名单一律回落统一默认模型（同 ChatModelFactory）
        String textModel = (StringUtils.hasText(modelId) && AllowedModels.isAllowed(modelId))
                ? modelId
                : chatModelFactory.resolveDefaultModel();

        String apiKey;
        String apiBase;
        if (provider == AiModelProperties.Provider.AWD_CLOUD) {
            // 平台通道：密钥由官网按账户 provision。取不到时原样抛业务异常——静默回退用户自己的
            // BYOK key 等于拿用户的钱去跑，还会掩盖「额度未就绪」这类需要去官网处理的状态。
            apiKey = platformAiChannel.apiKey();
            // baseUrl 只认 yml：DB 那份是用户 BYOK 的自定义地址，把 provision 出来的凭据发过去
            // 等于把平台密钥交出去（与 ChatModelFactory 平台通道同规矩）
            apiBase = orConfig.getBaseUrl();
        } else if (provider == AiModelProperties.Provider.OPENROUTER) {
            // 走工厂的解析入口，不在这里复制一份「读 DB、空白回退 yml」——
            // 密钥解析有两处口径是本仓反复踩过的坑（改了一处、另一处继续读旧键）
            apiKey = chatModelFactory.resolveOpenRouterApiKey();
            apiBase = chatModelFactory.resolveOpenRouterBaseUrl();
            if (!StringUtils.hasText(apiKey)) {
                throw new com.checkba.exception.FeatureNotConfiguredException("ai-ppt",
                        "AI PPT 需要 OpenRouter 的 API Key，到设置页的 AI 供应商里填好后即可生成");
            }
        } else {
            // OLLAMA 是离线/实验档：本地模型没有 OpenAI 兼容的图像生成接口，PPT 的图片阶段跑不了
            throw new com.checkba.exception.FeatureNotConfiguredException("ai-ppt",
                    "当前 AI 供应商是本地 Ollama（离线实验档），AI PPT 需要云端模型；"
                            + "到设置页切换为「AI WorkDeck 云端」或 OpenRouter 后可重试");
        }

        log.info("Building PPT model config: provider={}, textModel={} (requested={}), imageModel={}",
                provider, textModel, modelId, PptxServiceClient.IMAGE_MODEL);

        return PptxServiceClient.ModelConfig.builder()
                .provider("openai")  // pptx-service 侧的 SDK 格式：OpenRouter 与平台通道都是 OpenAI 兼容
                .apiKey(apiKey)
                .apiBase(apiBase)
                .textModel(textModel)
                .imageModel(PptxServiceClient.IMAGE_MODEL)
                .build();
    }

    /**
     * 生成新的编辑器文件 ID（沿用 wpsFileId 字段名）
     * 当文件被修改后，需要更新 wpsFileId 以强制编辑器重新下载文件内容
     * 编辑器通过 fileId 识别和缓存文档，更新 fileId 可以绕过缓存
     * 
     * @param projectId 项目 ID
     * @return 新的 wpsFileId
     */
    private String generateNewWpsFileId(Long projectId) {
        String rand = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        return String.format("project_%d_doc_%d_%s", projectId, System.currentTimeMillis(), rand);
    }
}

