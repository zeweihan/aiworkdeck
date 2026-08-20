package com.checkba.service.ai.tools;

import com.checkba.model.entity.ProjectFile;
import com.checkba.model.entity.Tag;
import com.checkba.repository.ProjectFileRepository;
import com.checkba.service.FileTagService;
import com.checkba.service.TagService;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * 标签类型维度工具（dev-board #63，spec：
 * docs/superpowers/specs/2026-08-20-file-party-issue-tags-design.md）。
 *
 * 标签类型是既有普通标签的一个维度扩展：PARTY（当事人）/ ISSUE（争议焦点）/ NORMAL（普通，
 * 含存量 null）。文件-标签关联本身不新增概念，直接复用 {@link FileTagService}。
 *
 * projectId/userId 由 ToolRegistry 按服务端上下文强制注入（SERVER_CONTEXT_PARAMS），
 * LLM 传入的同名值会被忽略；fileId 是 LLM 自由填写的业务 ID，用 {@link ToolFileGuard}
 * 校验归属，防止越权访问其它项目的文件。
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class TagTools implements AgentToolComponent {

    private final TagService tagService;
    private final FileTagService fileTagService;
    private final ProjectFileRepository projectFileRepository;

    @Tool("查看当前项目的标签清单，按类型分组（当事人/争议焦点/普通标签）。" +
          "给文件打标签前必须先调用本工具查看已有标签，优先复用已有名字，不要为同一个人/" +
          "同一个争点造出措辞不同的同义词标签。")
    @ToolMeta(displayName = "查看项目标签", category = "file")
    public String tag_list(Long projectId) {
        log.info("Tool: tag_list project={}", projectId);
        if (projectId == null) {
            return "错误：无法获取当前项目ID，请在项目上下文中使用此工具。";
        }

        List<Tag> tags = tagService.getProjectTags(projectId);
        if (tags.isEmpty()) {
            return "当前项目暂无标签。";
        }

        StringBuilder parties = new StringBuilder();
        StringBuilder issues = new StringBuilder();
        StringBuilder normal = new StringBuilder();
        for (Tag tag : tags) {
            String type = tag.getType();
            if ("PARTY".equals(type)) {
                appendName(parties, tag.getName());
            } else if ("ISSUE".equals(type)) {
                appendName(issues, tag.getName());
            } else {
                appendName(normal, tag.getName());
            }
        }

        StringBuilder sb = new StringBuilder();
        sb.append("当事人：").append(parties.length() > 0 ? parties : "（无）").append('\n');
        sb.append("争议焦点：").append(issues.length() > 0 ? issues : "（无）").append('\n');
        sb.append("普通标签：").append(normal.length() > 0 ? normal : "（无）");
        return sb.toString();
    }

    private void appendName(StringBuilder sb, String name) {
        if (sb.length() > 0) {
            sb.append("、");
        }
        sb.append(name);
    }

    @Tool("给文件打标签，可指定类型（NORMAL 普通 / PARTY 当事人 / ISSUE 争议焦点，缺省 NORMAL）。" +
          "按名字找已有标签，找不到才新建；已挂过同一标签会幂等返回成功。打标签前建议先用 " +
          "tag_list 查看已有标签，避免造同义词。")
    @ToolMeta(displayName = "给文件打标签", category = "file")
    public String tag_file(
            @P("要打标签的项目内文件ID，须来自 doc_list_project_files 等工具返回的 fileId") Long fileId,
            @P("标签名称") String tagName,
            @P(value = "标签类型：NORMAL/PARTY/ISSUE，缺省 NORMAL", required = false) String type,
            Long projectId,
            Long userId
    ) {
        log.info("Tool: tag_file fileId={} tagName='{}' type={} project={}", fileId, tagName, type, projectId);

        if (projectId == null) {
            return "错误：无法获取当前项目ID，请在项目上下文中使用此工具。";
        }
        if (fileId == null) {
            return "错误：fileId 不能为空。";
        }
        if (tagName == null || tagName.isBlank()) {
            return "错误：标签名称不能为空。";
        }
        String effectiveType = (type == null || type.isBlank()) ? "NORMAL" : type;

        try {
            Optional<ProjectFile> fileOpt = projectFileRepository.findById(fileId);
            if (fileOpt.isEmpty()) {
                return "错误：文件不存在：" + fileId;
            }
            String denied = ToolFileGuard.rejectIfOutsideProject(fileOpt.get());
            if (denied != null) {
                return denied;
            }

            Tag tag = tagService.getOrCreateTag(projectId, tagName, effectiveType, null);
            fileTagService.addTagToFile(fileId, tag.getId(), userId);

            String actualType = tag.getType() == null ? "NORMAL" : tag.getType();
            StringBuilder sb = new StringBuilder("已给文件打上标签「").append(tag.getName()).append("」。");
            if (!actualType.equals(effectiveType)) {
                sb.append("该标签已存在，实际类型为 ").append(actualType).append("（未按本次请求改型）。");
            }
            return sb.toString();
        } catch (IllegalArgumentException e) {
            return "错误：" + e.getMessage();
        } catch (Exception e) {
            log.warn("tag_file failed", e);
            return "错误：打标签失败，" + e.getMessage();
        }
    }

    @Tool("移除文件上的某个标签（只解除文件与标签的关联，不删除标签本身）。")
    @ToolMeta(displayName = "移除文件标签", category = "file")
    public String tag_remove_from_file(
            @P("文件ID，须来自 doc_list_project_files 等工具返回的 fileId") Long fileId,
            @P("要移除的标签名称") String tagName,
            Long projectId
    ) {
        log.info("Tool: tag_remove_from_file fileId={} tagName='{}' project={}", fileId, tagName, projectId);

        if (projectId == null) {
            return "错误：无法获取当前项目ID，请在项目上下文中使用此工具。";
        }
        if (fileId == null) {
            return "错误：fileId 不能为空。";
        }
        if (tagName == null || tagName.isBlank()) {
            return "错误：标签名称不能为空。";
        }

        try {
            Optional<ProjectFile> fileOpt = projectFileRepository.findById(fileId);
            if (fileOpt.isEmpty()) {
                return "错误：文件不存在：" + fileId;
            }
            String denied = ToolFileGuard.rejectIfOutsideProject(fileOpt.get());
            if (denied != null) {
                return denied;
            }

            List<Tag> existingTags = tagService.getProjectTags(projectId);
            Tag target = existingTags.stream()
                    .filter(t -> tagName.equals(t.getName()))
                    .findFirst()
                    .orElse(null);
            if (target == null) {
                return "项目里没有名为「" + tagName + "」的标签，无需移除。";
            }

            List<Tag> fileTags = fileTagService.getTagsByFileId(fileId);
            boolean attached = fileTags.stream().anyMatch(t -> t.getId().equals(target.getId()));
            if (!attached) {
                return "文件本来就没有挂「" + tagName + "」这个标签。";
            }

            fileTagService.removeTagFromFile(fileId, target.getId());
            return "已移除标签「" + tagName + "」。";
        } catch (Exception e) {
            log.warn("tag_remove_from_file failed", e);
            return "错误：移除标签失败，" + e.getMessage();
        }
    }
}
