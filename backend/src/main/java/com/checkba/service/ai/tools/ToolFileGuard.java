package com.checkba.service.ai.tools;

import com.checkba.model.entity.ProjectFile;
import com.checkba.service.ai.context.ProjectContextHolder;

/**
 * 工具层的项目边界校验。
 *
 * ToolRegistry 已经把 projectId/conversationId/userId 三个参数强制改写为服务端上下文
 * （见 {@link ToolContext} 注释），LLM 伪造不了它们；但 fileId 之类的业务 ID 仍是 LLM
 * 自由填写的普通参数。工具若直接 findById(fileId) 取库，就等于把「读哪个文件」的授权
 * 决定权交给了模型——而模型的输入里混有文档正文、网页内容、他人上传的文件名，
 * 提示注入可以直接驱使它去点名别人项目里的文件。
 *
 * 因此凡是按 LLM 给的 ID 取到的 ProjectFile，都要在这里和当前会话的真实项目比对一次。
 * 没有项目上下文时一律拒绝（fail closed）：无从判断归属，就不能给。
 */
public final class ToolFileGuard {

    private ToolFileGuard() {}

    /**
     * @return 校验不通过时返回给模型的错误串；通过则返回 null。
     */
    public static String rejectIfOutsideProject(ProjectFile file) {
        if (file == null) {
            return null; // 「文件不存在」由调用方自己的分支处理，这里不抢
        }
        Long currentProjectId = ProjectContextHolder.getProjectIdAsLong();
        if (currentProjectId == null) {
            return "Error: no project context for this request; refusing to access file "
                    + file.getId() + ".";
        }
        if (!currentProjectId.equals(file.getProjectId())) {
            return "Error: file " + file.getId() + " does not belong to the current project.";
        }
        return null;
    }
}
