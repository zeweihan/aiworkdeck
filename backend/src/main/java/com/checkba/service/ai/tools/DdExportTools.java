package com.checkba.service.ai.tools;

import com.checkba.repository.EvidenceLinkRepository;
import com.checkba.service.DdExportService;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 尽调交付件导出（dev-board#100 P2）的 AI 工具入口。核心逻辑全在 {@link DdExportService}，
 * 这里只做 docFileId 缺省时的自动定位与面向模型的摘要文案。
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class DdExportTools implements AgentToolComponent {

    private final DdExportService ddExportService;
    private final EvidenceLinkRepository evidenceLinkRepository;

    @ToolMeta(displayName = "导出尽调交付件", category = "document", fileEffect = "ADDED")
    @Tool("导出尽调报告的交付件到项目 _交付件/ 文件夹（同名就地覆盖）。kind=docket 底稿目录（按章节列出每份被引底稿及其反向引用的段落）、" +
          "verify-plan 查验计划（按查验方式 written_review/written_statement/web_check/third_party/interview 归类文件与对应段落）、" +
          "gaps 缺口清单（正文里的【待补：…】占位符 + 项目里状态为 orphan 的证据链接）。数据源是既有的 EvidenceLink 关联表与项目文件，" +
          "不需要安装尽调插件。")
    public String dd_export(
            @P("导出种类：docket / verify-plan / gaps") String kind,
            @P(value = "导出格式：docx 或 xlsx，缺省 docx", required = false) String format,
            @P(value = "报告所在的项目文件 ID；缺省时若项目里只有一份带底稿关联的文档会自动使用它，" +
                    "否则会报错并列出候选，需要你显式指定", required = false) Long docFileId,
            Long projectId,
            Long userId
    ) {
        log.info("Tool: dd_export called kind={}, format={}, docFileId={}", kind, format, docFileId);
        try {
            Long resolvedDocFileId = docFileId != null ? docFileId : resolveSoleDocFileId(projectId);
            DdExportService.ExportResult r = ddExportService.export(userId, projectId, resolvedDocFileId, kind, format);
            return String.format("已生成交付件：%s（文件ID: %d，共 %d 条）。可用 extract_file_text 或 doc_open_file 查看。",
                    r.path(), r.fileId(), r.rows());
        } catch (IllegalArgumentException e) {
            return "Error: " + e.getMessage();
        } catch (Exception e) {
            log.error("dd_export failed", e);
            return "Error: " + e.getMessage();
        }
    }

    /** 项目里恰好只有一份带底稿关联的文档时才能自动定位，否则要求模型显式指定。 */
    private Long resolveSoleDocFileId(Long projectId) {
        List<Long> candidates = evidenceLinkRepository.findDistinctDocFileIdsByProjectId(projectId);
        if (candidates.isEmpty()) {
            throw new IllegalArgumentException("项目里还没有任何证据链接（EvidenceLink），无法自动确定报告文件，请显式传 docFileId。");
        }
        if (candidates.size() > 1) {
            throw new IllegalArgumentException("项目里有多份带底稿关联的文档，请显式指定 docFileId，候选：" + candidates);
        }
        return candidates.get(0);
    }
}
