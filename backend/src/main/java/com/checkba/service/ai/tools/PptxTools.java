package com.checkba.service.ai.tools;

import com.checkba.config.AiModelProperties;
import com.checkba.model.ai.TaskInfo;
import com.checkba.model.entity.ProjectFile;
import com.checkba.repository.ProjectFileRepository;
import com.checkba.service.ProjectFileService;
import com.checkba.service.ai.BackgroundTaskService;
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

    private static final Long AGENT_USER_ID = 10001L;

    /**
     * 获取项目数据目录
     */
    private Path getProjectRoot() {
        String userDir = System.getProperty("user.dir");
        Path backendPath = Paths.get(userDir);
        if (backendPath.endsWith("backend")) {
            return backendPath.getParent();
        }
        return backendPath;
    }

    // ==================== 文件管理工具 ====================

    @Tool("列出项目中的所有文件夹。返回文件夹 ID、名称和路径。仅当用户明确指定要保存到某个特定文件夹时才需要调用此工具。")
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

    @Tool("列出项目中的所有 PPTX 演示文稿文件。返回文件 ID、名称和位置信息。")
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
            
            if (!isPptxFile(file.getName())) {
                return "Error: 该文件不是 PPTX 格式: " + file.getName();
            }
            
            // 通过 SSE 发送打开文件指令到前端
            editorBridgeService.sendOpenFileAction(file);
            
            return String.format("已发送打开文件指令。文件名: %s。请等待 PPT 加载完成后再进行编辑操作。\n" +
                    "加载完成后，可以使用 pptx_get_presentation_info 获取 PPT 信息，" +
                    "使用 pptx_get_slide_content 获取幻灯片内容。", 
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
                return "PPTX 生成服务不可用。请确保已启动 Docker 服务：docker-compose up -d pptx-service";
            }
        } catch (Exception e) {
            log.error("Failed to check PPTX service", e);
            return "检查服务状态失败: " + e.getMessage() + "。请确保 Docker 服务已启动。";
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
        
        try {
            // 检查服务
            if (!pptxServiceClient.isHealthy()) {
                return "错误：PPTX 生成服务不可用。请先启动 Docker 服务：docker-compose up -d pptx-service";
            }
            
            // 验证父文件夹存在（如果指定）
            String folderPath = "";
            if (parentId != null) {
                try {
                    ProjectFile parentFolder = projectFileService.getFile(parentId);
                    if (parentFolder == null || !Boolean.TRUE.equals(parentFolder.getIsFolder())) {
                        return "错误：指定的父文件夹不存在或不是文件夹，ID=" + parentId;
                    }
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
            Path localPath = getProjectRoot().resolve("data").resolve(storagePath);
            
            // 确保目录存在
            if (!Files.exists(localPath.getParent())) {
                Files.createDirectories(localPath.getParent());
            }
            
            // 构建模型配置（使用用户选择的模型）
            PptxServiceClient.ModelConfig modelConfig = buildModelConfig(modelId);
            
            // 调用服务生成
            log.info("Starting PPTX generation to: {}, using model: {}, exportEditable: {}", localPath, modelId, exportEditable);
            PptxServiceClient.PptxGenerationResult result;
            
            // 如果有 conversationId，使用带进度回调的版本
            String taskId = null;
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
                        result.isEditable() ? "✅ 可编辑版（文字/表格可直接修改）" : "纯图片版"
                ));
                
                // 添加警告信息（如有）
                if (result.getWarnings() != null && !result.getWarnings().isEmpty()) {
                    successMsg.append("\n⚠️ **注意**:\n");
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
                successMsg.append("- pptx_refine_outline: 修改大纲结构（增删页面）");
                
                // 标记后台任务完成
                if (taskId != null) {
                    backgroundTaskService.completeTask(taskId, result.getProjectId());
                }
                
                return successMsg.toString();
                
            } catch (Exception e) {
                // 文件已生成但注册失败
                log.warn("PPTX file created but DB registration failed", e);
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
            log.error("PPTX generation failed", e);
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
                return "错误：PPTX 生成服务不可用。请先启动 Docker 服务。";
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

    // ==================== 智能 PPT 修改工具 ====================

    @ToolMeta(displayName = "智能修改PPT", category = "pptx", fileEffect = "MODIFIED")
    @Tool("智能修改 PPT 页面。会自动判断页面类型：如果是可编辑组件则直接修改文本，如果是纯图片则使用 AI 重新生成。这是修改 PPT 的首选工具。")
    public String pptx_smart_modify(
            @P("文件 ID（从 pptx_list_files 或 pptx_search_files 获取）") Long fileId,
            @P("页面索引（从 1 开始）") Integer pageIndex,
            @P("修改要求，用自然语言描述，如：'把汇报人改成韩泽伟'、'标题改成红色'、'删除第三个要点'") String modifyInstruction
    ) {
        // 模型 ID 由 ToolRegistry 通过线程上下文透传
        return pptx_smart_modify(fileId, pageIndex, modifyInstruction, ToolContextHolder.currentModelId());
    }

    /**
     * 带 modelId 参数的 pptx_smart_modify 内部版本
     * 用于从 AgentOrchestrator 调用时传递用户选择的模型（用于纯图片页面的 AI 编辑）
     */
    public String pptx_smart_modify(Long fileId, Integer pageIndex, String modifyInstruction, String modelId) {
        log.info("Tool: pptx_smart_modify called, fileId={}, pageIndex={}, instruction={}, modelId={}", 
                fileId, pageIndex, modifyInstruction, modelId);
        
        try {
            // 1. 获取文件信息
            ProjectFile file = projectFileService.getFile(fileId);
            if (file == null) {
                return "Error: 文件不存在，ID=" + fileId;
            }
            
            if (!isPptxFile(file.getName())) {
                return "Error: 该文件不是 PPTX 格式: " + file.getName();
            }
            
            // 2. 尝试通过编辑器获取页面内容
            String slideContent = editorBridgeService.executeEditorCommand("ppt_get_slide_content", 
                    java.util.Map.of("slideIndex", pageIndex));
            
            // 3. 分析返回结果，判断是否可编辑
            cn.hutool.json.JSONObject contentJson = null;
            boolean hasEditableShapes = false;
            
            try {
                contentJson = cn.hutool.json.JSONUtil.parseObj(slideContent);
                if (contentJson.containsKey("error")) {
                    // 编辑器返回错误，可能文件未打开或页面不存在
                    log.warn("Editor get slide content returned error: {}", contentJson.getStr("error"));
                } else if (contentJson.containsKey("shapes")) {
                    cn.hutool.json.JSONArray shapes = contentJson.getJSONArray("shapes");
                    // 检查是否有可编辑的文本 shape
                    for (int i = 0; i < shapes.size(); i++) {
                        cn.hutool.json.JSONObject shape = shapes.getJSONObject(i);
                        String shapeType = shape.getStr("type");
                        if ("text".equals(shapeType) || "textBox".equals(shapeType)) {
                            hasEditableShapes = true;
                            break;
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("Failed to parse slide content: {}", e.getMessage());
            }
            
            // 4. 根据页面类型选择修改方式
            if (hasEditableShapes) {
                // 有可编辑组件 - 使用编辑器 API 直接修改
                return modifyWithEditorApi(fileId, pageIndex, modifyInstruction, contentJson);
            } else {
                // 纯图片或无法获取内容 - 使用 AI 图片编辑（传递 modelId）
                return modifyWithAiImageEdit(file, pageIndex, modifyInstruction, modelId);
            }
            
        } catch (Exception e) {
            log.error("Smart modify failed", e);
            return "智能修改失败: " + e.getMessage();
        }
    }

    /**
     * 使用编辑器 API 直接修改文本
     */
    private String modifyWithEditorApi(Long fileId, Integer pageIndex, String instruction, 
                                     cn.hutool.json.JSONObject contentJson) {
        log.info("Modifying with editor API: fileId={}, pageIndex={}", fileId, pageIndex);
        
        try {
            // 分析修改指令，找到需要修改的 shape
            cn.hutool.json.JSONArray shapes = contentJson.getJSONArray("shapes");
            
            // 简单的关键词匹配来找到目标 shape
            String lowerInstruction = instruction.toLowerCase();
            
            for (int i = 0; i < shapes.size(); i++) {
                cn.hutool.json.JSONObject shape = shapes.getJSONObject(i);
                String text = shape.getStr("text", "");
                int shapeIndex = shape.getInt("index", i + 1);
                
                // 检查指令中是否提到这个 shape 的内容
                if (shouldModifyShape(text, lowerInstruction)) {
                    // 提取新文本
                    String newText = extractNewTextFromInstruction(text, instruction);
                    
                    // 调用编辑器修改
                    String result = editorBridgeService.executeEditorCommand("ppt_modify_slide_text", 
                            java.util.Map.of(
                                    "slideIndex", pageIndex,
                                    "shapeIndex", shapeIndex,
                                    "newText", newText,
                                    "markAsRevision", true
                            ));
                    
                    return String.format("已通过编辑器修改页面内容！\n" +
                            "- 页面: 第 %d 页\n" +
                            "- 原文本: %s\n" +
                            "- 新文本: %s\n\n" +
                            "修改已标记为修订，请在编辑器中确认。", 
                            pageIndex, text.length() > 50 ? text.substring(0, 50) + "..." : text, newText);
                }
            }
            
            // 没有找到匹配的 shape，返回提示
            StringBuilder sb = new StringBuilder("未找到匹配的文本区域。页面包含以下可编辑内容：\n");
            for (int i = 0; i < shapes.size(); i++) {
                cn.hutool.json.JSONObject shape = shapes.getJSONObject(i);
                sb.append(String.format("- Shape %d: %s\n", i + 1, 
                        shape.getStr("text", "").substring(0, Math.min(50, shape.getStr("text", "").length()))));
            }
            sb.append("\n请更具体地描述要修改哪个内容。");
            return sb.toString();
            
        } catch (Exception e) {
            log.error("Editor API modify failed", e);
            return "编辑器修改失败: " + e.getMessage();
        }
    }

    /**
     * 使用 AI 图片编辑修改纯图片页面
     * 
     * 完整流程：
     * 1. 获取本地 PPTX 文件路径
     * 2. 构建模型配置（使用用户选择的模型，包含 API Key 和正确的图片模型映射）
     * 3. 调用 pptx-service 的 /edit-pptx-slide API（提取页面图片 → AI 编辑 → 替换回 PPTX）
     * 4. 用编辑后的文件覆盖原文件
     * 5. 通知前端重新加载文件
     * 
     * @param file 要修改的 PPTX 文件
     * @param pageIndex 页面索引
     * @param instruction 修改指令
     * @param modelId 用户选择的模型 ID（如 google/gemini-3-pro-preview），用于正确映射到图片模型
     */
    private String modifyWithAiImageEdit(ProjectFile file, Integer pageIndex, String instruction, String modelId) {
        log.info("Modifying with AI image edit: file={}, pageIndex={}, modelId={}", file.getName(), pageIndex, modelId);
        
        try {
            // 1. 获取本地 PPTX 文件路径
            String filePath = file.getFilePath();
            Path localPath = getProjectRoot().resolve("data").resolve(filePath);
            
            if (!Files.exists(localPath)) {
                return String.format("错误：PPTX 文件不存在于本地磁盘: %s\n\n" +
                        "请确保文件已正确上传到服务器。", localPath);
            }
            
            log.info("PPTX file found at: {}", localPath);
            
            // 2. 构建模型配置（使用用户选择的模型，会自动映射到对应的图片生成模型）
            PptxServiceClient.ModelConfig modelConfig = buildModelConfig(modelId);
            
            // 3. 调用 pptx-service 编辑 API（提取 → AI 编辑 → 替换）
            log.info("Calling pptx-service to edit slide {} with instruction: {}", pageIndex, instruction);
            
            PptxServiceClient.PptxSlideEditResult editResult = pptxServiceClient.editPptxSlide(
                    localPath.toString(),
                    pageIndex,
                    instruction,
                    localPath.toString(),  // 直接覆盖原文件
                    modelConfig            // 传递模型配置
            );
            
            if (!editResult.isSuccess()) {
                // 如果是因为没有图片，给出友好提示
                String errorMsg = editResult.getMessage();
                if (errorMsg != null && errorMsg.contains("does not contain an image")) {
                    return String.format("第 %d 页不是纯图片页面，可能包含可编辑的文本元素。\n\n" +
                            "请尝试使用 pptx_modify_slide_text 工具直接修改文本，\n" +
                            "或在文档编辑器中手动修改。", pageIndex);
                }
                return "AI 编辑失败: " + errorMsg;
            }
            
            // 3. 更新文件信息：生成新的 wpsFileId 以强制编辑器重新下载文件
            // 编辑器通过 fileId 识别文档并缓存，更新 wpsFileId 可以让编辑器认为是"新文件"从而重新下载
            String newWpsFileId = generateNewWpsFileId(file.getProjectId());
            file.setWpsFileId(newWpsFileId);
            file.setUpdatedAt(LocalDateTime.now());
            // 更新文件大小
            try {
                file.setFileSize(Files.size(localPath));
            } catch (Exception e) {
                log.warn("Failed to update file size: {}", e.getMessage());
            }
            projectFileRepository.save(file);
            log.info("Updated file wpsFileId: {} -> {}", file.getId(), newWpsFileId);
            
            // 4. 通知前端重新加载文件（携带新的 wpsFileId）
            log.info("Notifying frontend to reload file: {}", file.getId());
            editorBridgeService.sendReloadFileAction(file);
            
            return String.format("✅ 第 %d 页已使用 AI 成功修改！\n\n" +
                    "修改内容：%s\n\n" +
                    "文件已自动更新，文档编辑器将重新加载以显示修改后的内容。",
                    pageIndex, instruction);
            
        } catch (Exception e) {
            log.error("AI image edit failed", e);
            return "AI 图片编辑失败: " + e.getMessage();
        }
    }

    /**
     * 判断是否应该修改这个 shape
     */
    private boolean shouldModifyShape(String shapeText, String instruction) {
        if (shapeText == null || shapeText.isEmpty()) return false;
        
        String lowerText = shapeText.toLowerCase();
        
        // 检查指令中是否包含 shape 中的关键词
        // 例如：指令"把汇报人改成韩泽伟"，检查 shape 是否包含"汇报人"或现有的人名
        String[] keywords = {"汇报人", "报告人", "演讲人", "作者", "presenter", "author"};
        for (String keyword : keywords) {
            if (instruction.contains(keyword) && lowerText.contains(keyword.toLowerCase())) {
                return true;
            }
        }
        
        // 检查"把 X 改成 Y"的模式
        if (instruction.contains("改成") || instruction.contains("改为") || instruction.contains("换成")) {
            // 提取要替换的原文
            String[] patterns = {"把", "将"};
            for (String pattern : patterns) {
                int patternIdx = instruction.indexOf(pattern);
                int changeIdx = instruction.indexOf("改");
                if (patternIdx >= 0 && changeIdx > patternIdx) {
                    String originalText = instruction.substring(patternIdx + pattern.length(), changeIdx).trim();
                    if (!originalText.isEmpty() && lowerText.contains(originalText.toLowerCase())) {
                        return true;
                    }
                }
            }
        }
        
        return false;
    }

    /**
     * 从指令中提取新文本
     */
    private String extractNewTextFromInstruction(String originalText, String instruction) {
        // 尝试提取"改成 X"中的 X
        String[] patterns = {"改成", "改为", "换成", "替换为", "修改为"};
        for (String pattern : patterns) {
            int idx = instruction.indexOf(pattern);
            if (idx >= 0) {
                String newText = instruction.substring(idx + pattern.length()).trim();
                // 移除可能的引号
                newText = newText.replaceAll("[\"'「」『』]", "");
                // 移除可能的句末标点
                newText = newText.replaceAll("[。，！？]$", "");
                if (!newText.isEmpty()) {
                    return newText;
                }
            }
        }
        
        // 如果无法提取，返回原文
        return originalText;
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

    @Tool("获取 PPT 文件中指定页面的截图。用于查看页面内容后再进行编辑。")
    public String pptx_get_page_screenshot(
            @P("文件 ID（数据库中的项目文件 ID）") Long fileId,
            @P("页面索引（从 0 开始）") int pageIndex
    ) {
        log.info("Tool: pptx_get_page_screenshot called, fileId={}, pageIndex={}", fileId, pageIndex);
        
        try {
            ProjectFile file = projectFileService.getFile(fileId);
            if (file == null) {
                return "Error: 文件不存在，ID=" + fileId;
            }
            
            if (!isPptxFile(file.getName())) {
                return "Error: 该文件不是 PPTX 格式: " + file.getName();
            }
            
            // 获取文件的物理路径
            String filePath = file.getFilePath();
            Path localPath = getProjectRoot().resolve("data").resolve(filePath);
            
            if (!Files.exists(localPath)) {
                return "Error: 文件不存在于磁盘: " + localPath;
            }
            
            // 调用 PPTX 服务获取页面截图
            String screenshotUrl = pptxServiceClient.getPageScreenshot(localPath.toString(), pageIndex);
            
            return String.format("页面截图已生成！\n" +
                    "- 文件: %s\n" +
                    "- 页面: 第 %d 页\n" +
                    "- 截图 URL: %s\n\n" +
                    "你可以查看此截图了解页面内容，然后使用 pptx_edit_page 进行修改。",
                    file.getName(), pageIndex + 1, screenshotUrl);
            
        } catch (Exception e) {
            log.error("Failed to get page screenshot", e);
            return "获取页面截图失败: " + e.getMessage();
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
     * 构建模型配置
     * 
     * 使用用户选择的模型（通过 modelId 传入），而不是配置文件中的默认值。
     * 这样 PPTX 服务就可以使用用户在前端选择的 AI 模型。
     * 
     * @param modelId 用户选择的模型 ID（如 "google/gemini-3-pro-preview"）
     * @return ModelConfig 模型配置对象
     */
    private PptxServiceClient.ModelConfig buildModelConfig(String modelId) {
        AiModelProperties.OpenRouter orConfig = aiModelProperties.getOpenRouter();
        
        // 使用用户选择的模型，如果没有指定则使用配置文件默认值
        String targetModel = (modelId != null && !modelId.isEmpty()) 
                ? modelId 
                : orConfig.getDefaultModel();
        
        // 将文本模型名映射到对应的图片模型名
        // 参考：https://openrouter.ai/google/gemini-3-pro-image-preview
        String imageModel = mapToImageModel(targetModel);
        
        log.info("Building model config with model: {} (user selected: {}), image model: {}", 
                targetModel, modelId, imageModel);
        
        // 使用 OpenRouter 配置（OpenAI 兼容格式）
        return PptxServiceClient.ModelConfig.builder()
                .provider("openai")  // 使用 OpenAI 兼容格式
                .apiKey(orConfig.getApiKey())
                .apiBase(orConfig.getBaseUrl())
                .textModel(targetModel)  // 使用用户选择的模型
                .imageModel(imageModel)  // 使用对应的图片生成模型
                .build();
    }
    
    /**
     * 将文本模型名映射到对应的图片生成模型名
     * OpenRouter 上的 Gemini 模型有两个版本：
     * - 文本模型：google/gemini-3-pro-preview
     * - 图片模型：google/gemini-3-pro-image-preview (Nano Banana Pro)
     * 
     * @param textModel 用户选择的文本模型
     * @return 对应的图片生成模型
     */
    private String mapToImageModel(String textModel) {
        if (textModel == null || textModel.isEmpty()) {
            return "google/gemini-3-pro-image-preview";
        }
        
        // Gemini 3 系列：将 -preview 替换为 -image-preview
        if (textModel.contains("gemini-3") && textModel.endsWith("-preview")) {
            // google/gemini-3-pro-preview -> google/gemini-3-pro-image-preview
            return textModel.replace("-preview", "-image-preview");
        }
        
        // Gemini 2.5 系列：类似处理
        if (textModel.contains("gemini-2.5") && textModel.endsWith("-preview")) {
            // google/gemini-2.5-flash-preview -> google/gemini-2.5-flash-image-preview
            return textModel.replace("-preview", "-image-preview");
        }
        
        // Gemini 2.0 系列
        if (textModel.contains("gemini-2.0")) {
            // gemini-2.0-flash-exp 本身就支持图片生成
            return textModel;
        }
        
        // 其他模型默认使用 Nano Banana Pro
        return "google/gemini-3-pro-image-preview";
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

