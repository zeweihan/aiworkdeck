package com.checkba.service.ai.tools;

import com.checkba.service.ai.PluginContributionService;
import com.checkba.service.ai.context.ProjectContextHolder;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 插件贡献模板的 AI 工具面（规范 v2.9 P4）：与「新建」入口共用
 * {@link PluginContributionService} 的同一份清单与创建链路。
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class ContributedTemplateTools implements AgentToolComponent {

    private final PluginContributionService contributionService;

    @Tool("列出插件贡献的文书模板（id/名称/体裁/说明）。用户要按模板起草文书时先调这个看有什么可用，"
            + "再用 create_file_from_template 落地；清单为空就说明没装带模板的插件，走常规起草。")
    public String list_contributed_templates() {
        List<PluginContributionService.ContributedTemplate> templates = contributionService.listTemplates();
        if (templates.isEmpty()) {
            return "当前没有插件贡献的模板（未安装带模板的插件，或插件被禁用）。";
        }
        StringBuilder sb = new StringBuilder("可用模板 " + templates.size() + " 份：\n");
        for (PluginContributionService.ContributedTemplate t : templates) {
            sb.append("- pluginId=").append(t.pluginId())
                    .append(" templateId=").append(t.id())
                    .append(" 名称=").append(t.name());
            if (t.genre() != null && !t.genre().isBlank()) {
                sb.append(" 体裁=").append(t.genre());
            }
            if (t.description() != null && !t.description().isBlank()) {
                sb.append(" 说明=").append(t.description());
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    @Tool("从插件贡献的模板创建一份项目文件（同名自动加序号）。pluginId 与 templateId 从 "
            + "list_contributed_templates 的结果里取；parentFolderId 可选（缺省放项目根）；"
            + "name 可选（缺省用模板名）。返回新文件的 fileId 与实际文件名。")
    @ToolMeta(displayName = "从模板新建文件", fileEffect = "MODIFIED")
    public String create_file_from_template(
            @P("模板所属插件 id") String pluginId,
            @P("模板 id") String templateId,
            @P(value = "目标文件夹 id，缺省项目根", required = false) Long parentFolderId,
            @P(value = "新文件名（不含扩展名也可），缺省用模板名", required = false) String name) {
        Long projectId = ProjectContextHolder.getProjectIdAsLong();
        Long userId = ProjectContextHolder.getUserId();
        if (projectId == null || userId == null) {
            return "Error: 缺少项目上下文，无法创建文件。";
        }
        try {
            var file = contributionService.createFromTemplate(
                    projectId, userId, pluginId, templateId, parentFolderId, name);
            return "已创建：fileId=" + file.getId() + " 文件名=" + file.getName();
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }
}
