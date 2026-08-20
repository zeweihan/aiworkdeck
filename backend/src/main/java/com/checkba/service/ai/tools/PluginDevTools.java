package com.checkba.service.ai.tools;

import com.checkba.service.ai.PluginDevService;
import com.checkba.service.ai.context.ProjectContextHolder;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 插件开发形态的 AI 工具（dev-board#61）：让模型在对话里完成
 * 「建骨架 -> 写源码（text_* / write_file）-> 装机自测」的闭环。
 * 安全模型与校验规则全部在 {@link PluginDevService}，本类只是薄封装；
 * install 的校验错误按原文返回给模型，让它逐条修复后重装。
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class PluginDevTools implements AgentToolComponent {

    private final PluginDevService pluginDevService;

    @ToolMeta(displayName = "创建插件骨架", category = "file", fileEffect = "ADDED", refreshFiles = true)
    @Tool("在当前项目「插件开发/<id>/」下创建一个 Web 插件骨架（manifest.json + web/index.html + "
            + "web/awd-plugin-sdk.js），返回源码文件夹的数据库 ID。id 用小写字母/数字/连字符（2-50 位）。"
            + "骨架建好后用 text_write_file / text_find_replace 修改源码，用 write_file 新增文件，"
            + "改完调 plugin_dev_install 安装到本机测试。")
    public String plugin_dev_scaffold(
            @P("插件 id（小写字母/数字/连字符，2-50 位，例如 checklist-helper）") String pluginId,
            @P("插件显示名（给用户看的中文名）") String displayName
    ) {
        log.info("Tool: plugin_dev_scaffold called for id={}", pluginId);
        Long projectId = ProjectContextHolder.getProjectIdAsLong();
        Long userId = ProjectContextHolder.getUserId();
        if (projectId == null) {
            return "Error: 当前会话没有项目上下文。";
        }
        try {
            Long folderId = pluginDevService.scaffold(projectId, userId, pluginId, displayName);
            return "已创建插件骨架「" + PluginDevService.DEV_ROOT_FOLDER + "/" + pluginId + "/」（源码文件夹 ID "
                    + folderId + "）。包含 manifest.json、web/index.html、web/awd-plugin-sdk.js。"
                    + "接下来编辑 web/ 下的源码，改完用 plugin_dev_install(" + folderId + ") 安装到本机。";
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    @ToolMeta(displayName = "安装插件到本机", category = "file")
    @Tool("校验并把项目「插件开发」目录下的插件源码安装到本机运行（拷进本机插件目录、热重扫并启用）。"
            + "源码有改动后必须重新调用本工具才会生效。校验失败会返回逐条错误明细——逐条修复源码后重装，"
            + "不要带着错误反复重试。成功后提醒用户在左栏打开该插件面板测试。")
    public String plugin_dev_install(
            @P("插件源码文件夹的数据库 ID（plugin_dev_scaffold 的返回值，或从 list_files 找「插件开发」下的子文件夹）") Long folderId
    ) {
        log.info("Tool: plugin_dev_install called for folderId={}", folderId);
        Long projectId = ProjectContextHolder.getProjectIdAsLong();
        if (projectId == null) {
            return "Error: 当前会话没有项目上下文。";
        }
        try {
            String id = pluginDevService.install(projectId, folderId);
            return "已安装并启用插件「" + id + "」。请让用户在左栏点开该插件面板测试效果；"
                    + "后续每次修改源码后都要重新调用 plugin_dev_install 才会生效。";
        } catch (IllegalArgumentException e) {
            return "Error: 校验未通过，请逐条修复后重装:\n" + e.getMessage();
        } catch (Exception e) {
            return "Error: 安装失败: " + e.getMessage();
        }
    }
}
