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

    /**
     * 单次读取类工具交给模型的正文字符上限。
     *
     * <p>口径与 {@code extract_file_text} 既有的 80k 一致——本方法就是把那处硬编码
     * 收成单一来源，让三个读取工具（extract_file_text / read_file / read_document）
     * 不再各说各话。
     */
    public static final int MAX_TOOL_TEXT_CHARS = 80_000;

    /**
     * 按 {@link #MAX_TOOL_TEXT_CHARS} 截断读取类工具的正文，并**显式告诉模型被截断了**。
     *
     * <p>为什么必须截断：工具结果原样进 {@code ToolExecutionResultMessage} 入栈，没有任何
     * 上限。一次 {@code read_document} 读一份几 MB 的合同就能产生几十万字符的单条消息，
     * 下一次 generate 必然被服务商以上下文超限 400 挡回。而这条超长结果落在
     * {@code RunLoopCompactor} 的 keepRecent 尾区（尾部平时刻意不剪）、中段又往往不够
     * 折叠条数，于是强制压缩缩不动、编排器判定「压不动」直接终态——同一份文档每次重试
     * 都必然再撞同一个 400，用户侧表现为「这份文件永远读不了」。
     *
     * <p>截断说明写成模型能据以行动的一句话：告诉它还有多少、以及用哪个工具分段读。
     */
    public static String capToolText(String fileName, String text) {
        if (text == null || text.length() <= MAX_TOOL_TEXT_CHARS) {
            return text;
        }
        return "[文件 " + fileName + "，全文 " + text.length() + " 字符，已截断至前 "
                + MAX_TOOL_TEXT_CHARS + " 字符。需要后续内容请用 doc_read_paragraphs 分段读取，"
                + "或先检索定位再读该段。]\n"
                + text.substring(0, MAX_TOOL_TEXT_CHARS);
    }
}
