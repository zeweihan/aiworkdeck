// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.service.ai;

/**
 * 本轮上下文组装的「账本」：每个附件的最终处置，以及每一次截断/丢弃/降级。
 *
 * <p><b>为什么要有它</b>（dev-board#793 K14 ④、#801 K21 ⑦）：
 * {@link ContextAssemblerService} 是全仓唯一知道「这份附件到底怎么处理了」的地方——
 * 图直送了还是降级 OCR 了、正文被截在第几个字、第 11 份文件被丢了。
 * 在它之外重新判一遍必然漂移（判据会分叉成两份），而这些事实有两个必需的消费者：
 * <ol>
 *   <li><b>用户</b>：每一次降级都要在界面上看得见（原则 2）。原来它们全是静默的——
 *       用户拖了 15 份材料进去、界面上 15 个标签都在，模型只看到前 10 份；
 *       贴一张 12MB 的扫描件，模型拿到的「正文」是一句 {@code [System: 文件超过大小限制]}。</li>
 *   <li><b>持久化</b>：消息 ↔ 附件的关联（{@code project_ai_message_attachment}），
 *       历史回放时重建气泡下的附件 chip。</li>
 * </ol>
 *
 * <p>实现方是 {@link AgentOrchestrator}：{@link #notice} 立刻发 SSE {@code context_notice}，
 * {@link #attachment} 攒起来在 assemble 之后落库。两个方法都<b>不许抛异常</b>——
 * 组装跑在用户等首 token 的关键路径上，一条提示发不出去绝不能掀翻整轮对话。
 */
public interface ContextTurnSink {

    /** 正文被按字符上限截断（附件的 max-chars-per-file / 活跃文档的 max-chars-active-document）。 */
    String TRUNCATED = "truncated";
    /** 超出 max-files-per-context 总闸，这一条整个没进上下文。 */
    String DROPPED = "dropped";
    /** 图片降级走 OCR，原因是当前模型读不了图。 */
    String OCR_FALLBACK = "ocr_fallback";
    /** 图片降级走 OCR，原因是超出本轮直送张数上限。 */
    String IMAGE_LIMIT = "image_limit";
    /** 图片降级走 OCR，原因是单张超过体积上限。 */
    String IMAGE_TOO_LARGE = "image_too_large";
    /** 抽不出任何正文（扫描件无文字层、格式不支持、读盘失败、OCR 一个字没认出来）。 */
    String UNREADABLE = "unreadable";

    /**
     * 一个附件的最终处置。
     *
     * @param fileType   客户端自填的 fileType，原样记下来——「重新生成」要按这份记录
     *                   重建那一轮的 contextItems，而后端判图是「fileType 优先、
     *                   缺失退回文件名后缀」的双判据，丢了它就丢了一半
     * @param kind       {@code file} / {@code image} / {@code folder}
     * @param visionUsed 这一条是不是作为图像内容块直送给模型了（只有 image 可能为 true）
     */
    default void attachment(String fileId, String name, String fileType, String kind, boolean visionUsed) {
    }

    /**
     * 一次降级/截断/丢弃。{@code kind} 取本接口的六个常量之一，
     * {@code detail} 是给用户看的补充信息（可空），文案由前端按 kind 出，这里只带数字与名字。
     */
    default void notice(String kind, String fileId, String name, String detail) {
    }

    /** 不关心账本的调用方（既有测试、回放评测、以及不带 sink 的旧重载）用它。 */
    ContextTurnSink NOOP = new ContextTurnSink() {
    };
}
