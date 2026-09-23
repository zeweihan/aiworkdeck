// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.service.ai.tools;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 工具的产品层元数据，与 langchain4j 的 @Tool（面向 LLM 的描述）互补。
 * 声明式地替代编排器里手写的展示名映射和文件副作用通知逻辑，
 * 新增工具无需再修改编排器。
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface ToolMeta {

    /**
     * 面向用户的中文显示名（历史记录、过程气泡中展示）。
     */
    String displayName() default "";

    /**
     * 工具分类（file/legal/web/memory/document/pptx/python/plugin），用于后续的可见性控制与插件广场分组。
     */
    String category() default "";

    /**
     * 执行成功后对项目文件的影响："ADDED" 或 "MODIFIED"，空串表示无文件副作用。
     */
    String fileEffect() default "";

    /**
     * 携带受影响文件名的参数名。fileEffect 非空但此项为空时（doc_* / sheet_* / slide_* 改的是编辑器里
     * 当前打开的那份），编排器用本轮活跃文档的真名并在 file_change 里带上它的 fileId（dev-board#852）；
     * 其它工具参数里有数字型 fileId 时（pdf_* / text_*），按该 id 查回真名并带上 id；
     * 连活跃文档都没有时文件名报「当前文档」、fileId 为 null，前端据此切回当前标签而不是按名字去找。
     */
    String fileArg() default "";

    /**
     * 执行成功后是否通知前端刷新文件树。
     */
    boolean refreshFiles() default false;

    /**
     * 这个工具要把事情做完，必须有哪一类客户端宿主（dev-board#799，审计 A9）。
     *
     * <p>{@code ClientCapabilityService.isToolVisible} 的第二层闸：第一层是工具名前缀
     * （doc_/sheet_/slide_ 要 LOWA、office_ 要 Office 插件、ref_ 只给 Office），
     * 本项是<b>叠加</b>在前缀之上的声明层，只会收窄、绝不放宽。前缀规则是既有公开契约，
     * 一行不动；无前缀的工具从此自己声明宿主依赖，不再靠往可见性服务里塞名字清单。
     *
     * <p><b>什么时候该声明 LOWA</b>——判据是<b>收尾是否经 EditorBridgeService 的四个
     * 文档级 UI 指令</b>：{@code sendOpenFileAction}（open_file）、
     * {@code sendReloadFileAction}（reload_file）、{@code sendTextReloadFileAction}
     * （text_reload_file）、{@code sendPptConfigAction}（ppt_config）。这四条都指名一份文档、
     * 要求桌面前端把它打开或重新加载；Office/WPS 任务窗格的 sse.js 对未知事件直接忽略，
     * 发了等于没发，而工具的返回文案还在对模型说「已在编辑器中打开」「编辑器将自动重载」——
     * 模型把它当成事实转述给用户，用户什么都没看到。{@code pptx_generate} 最严重：
     * 它发完 ppt_config 就返回「等待用户操作...」，而那个配置界面在插件里根本不存在，
     * 整轮彻底空转。
     *
     * <p><b>哪两个 send 刻意不算</b>：{@code sendRefreshFilesAction}（刷文件树）与
     * {@code sendComponentRequiredAction}（引导下载组件）是环境通知，不是工具的交付物——
     * 按它们判会把 {@code write_docx} / {@code create_folder} 这类纯后端建文件的工具
     * 一并锁进 LOWA，Office 会话里连新建文件都做不了。
     *
     * <p><b>代价要想清楚再标</b>：声明了 LOWA 的工具在 Office 与 none 会话里
     * <b>规格不下发、分发也拒绝</b>（能力闸本来就是两头都管的，见
     * {@code ToolRegistry.resolve(String, String)}）。其中一部分工具的实际写入是纯服务端的
     * （pdf_* 改的是磁盘上的 PDF、text_* 改的是项目里的纯文本文件），声明 LOWA 等于
     * 在那些会话里一并收走这份真能力——这是按审计口径做的取舍（那些会话里用户既看不到
     * 文件树也看不到预览，拿不到结果），不是顺手扩大的。新增声明前先确认不是在藏一个
     * 在那个会话里真能用完的能力。
     */
    Host requiresHost() default Host.NONE;

    /**
     * 是否把这个工具的规格下发给模型（dev-board#799，审计 A15）。
     *
     * <p>false = <b>只登记不下发</b>，与 {@code AgentToolComponent.isAvailable()} /
     * {@code currentlyUnusableTools()} 同一口径：模型看不见即不会去试，而万一经 XML 兜底
     * 路径调到了，拿到的仍是工具自己那句可行动的错误，好过一句 "Tool not found"
     * （后者会让模型以为这个能力整个不存在）。
     *
     * <p>与那两个机制的分工：{@code isAvailable()} 是<b>进程级</b>的（本机有没有 Docker），
     * {@code currentlyUnusableTools()} 是<b>运行期</b>的（账户连没连），本项是
     * <b>永久的</b>——工具本身已经停用或者压根不该出现在律师面前，与环境无关，
     * 所以写成声明而不是每次现算。今天的两个使用者：
     * {@code delete_file}（永久停用，实现就是一句拒绝）与
     * {@code doc_debug_revisions}（调试工具，不该进用户的过程卡）。
     */
    boolean offerToModel() default true;

    /**
     * {@link #requiresHost()} 的取值。与
     * {@code ClientCapabilityService.Capability} 三档一一对应（NONE 表示"不挑宿主"，
     * 不是"只给 none 会话"）。
     */
    enum Host {
        /** 不挑宿主：纯后端执行，任何会话都能把事情做完（默认）。 */
        NONE,
        /** 必须有桌面端嵌入式 LibreOffice 前端（LOWA）才能收尾。 */
        LOWA,
        /** 必须有 Office/WPS 任务窗格才能收尾。 */
        OFFICE
    }
}
