// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

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
     * 按 {@link #MAX_TOOL_TEXT_CHARS} 截断读取类工具的正文，并**显式告诉模型被截断了、怎么接着读**。
     *
     * <p>为什么必须截断：工具结果原样进 {@code ToolExecutionResultMessage} 入栈，没有任何
     * 上限。一次读取一份几 MB 的合同就能产生几十万字符的单条消息，
     * 下一次 generate 必然被服务商以上下文超限 400 挡回。而这条超长结果落在
     * {@code RunLoopCompactor} 的 keepRecent 尾区（尾部平时刻意不剪）、中段又往往不够
     * 折叠条数，于是强制压缩缩不动、编排器判定「压不动」直接终态——同一份文档每次重试
     * 都必然再撞同一个 400，用户侧表现为「这份文件永远读不了」。
     *
     * <p>截断说明写成模型能据以行动的一句话。<b>点名的工具必须真的存在</b>：这里曾点名一个
     * 注册表里从来没有过的分段读取工具（审计 A5/B-01），模型照着调只会拿到「Tool not found」。
     * 续读的主路是 {@code extract_file_text(fileId, offset)}（dev-board#1065，审计 T-04）——
     * 此前 id 式读取只有 fileId 一个参数，超过 8 万字符的未打开文件后半段谁也读不到；
     * 文档若已在编辑器中打开，{@code doc_get_document_text(startParagraph, maxParagraphs)}
     * 仍是按段落读的另一条路。
     */
    public static String capToolText(String fileName, String text) {
        return capToolText(fileName, null, text);
    }

    /** 同上，知道 fileId 时把它直接写进续读指引（模型照抄即可，不必再去查 id）。 */
    public static String capToolText(String fileName, Long fileId, String text) {
        if (text == null || text.length() <= MAX_TOOL_TEXT_CHARS) {
            return text;
        }
        return pageToolText(fileName, fileId, text, 0, null);
    }

    /**
     * 按字符分页取正文（{@code extract_file_text} 的 offset / maxChars，形状照抄 {@code office_get_text}：
     * 起点从 0 开始，缺省一次给满 {@link #MAX_TOOL_TEXT_CHARS}，给出 nextStart 与「还有 N 字符未读」）。
     *
     * <p>从头读且一次读得完时<b>原样返回 text</b>（不加任何抬头），由调用方决定要不要加「[文件 X]」；
     * 其余情况返回带抬头的那一段。起点越过文末返回 {@code Error:} 开头的一句话——
     * 那说明模型记错了位置，不是文件出了问题。切点落在代理对中间时往前挪一位，免得切出半个字。
     *
     * @param offset   起始字符位置，null 或负数按 0
     * @param maxChars 本次最多返回多少字符，null / 非正数按上限，超过上限按上限
     */
    public static String pageToolText(String fileName, Long fileId, String text, Integer offset, Integer maxChars) {
        String body = text == null ? "" : text;
        int total = body.length();
        int start = offset == null || offset < 0 ? 0 : offset;
        int limit = maxChars == null || maxChars <= 0 ? MAX_TOOL_TEXT_CHARS : Math.min(maxChars, MAX_TOOL_TEXT_CHARS);
        if (start == 0 && total <= limit) {
            return text;
        }
        if (start >= total) {
            return "Error: offset=" + start + " 已超出全文长度（「" + fileName + "」全文 " + total
                    + " 字符），前面已经读到文末，不需要再读。";
        }
        int end = Math.min(total, start + limit);
        if (end < total && end > start + 1 && Character.isHighSurrogate(body.charAt(end - 1))) {
            end--;
        }
        String next = "extract_file_text(fileId=" + (fileId == null ? "<它的 fileId>" : fileId)
                + ", offset=" + end + ")";
        StringBuilder sb = new StringBuilder("[文件 ").append(fileName).append("，全文 ").append(total).append(" 字符，");
        if (start == 0) {
            sb.append("已截断至前 ").append(end).append(" 字符。接着读用 ").append(next)
                    .append("；文档若已在编辑器中打开，也可用 doc_get_document_text(startParagraph=…, maxParagraphs=…) 按段落读；"
                            + "找某处先用 search_project_content。]\n");
        } else {
            sb.append("本次返回第 ").append(start).append("–").append(end).append(" 字符。]\n");
        }
        sb.append(body, start, end);
        if (end < total) {
            sb.append("\n[还有 ").append(total - end).append(" 字符未读，nextStart=").append(end)
                    .append("：传 offset=").append(end).append(" 再调一次 extract_file_text 接着读]");
        } else {
            sb.append("\n[已读到文末]");
        }
        return sb.toString();
    }
}
