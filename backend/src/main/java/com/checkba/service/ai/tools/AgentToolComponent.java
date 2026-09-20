// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.service.ai.tools;

/**
 * 内置 Agent 工具组件的标记接口。
 * 实现此接口的 Spring Bean 会被 ToolRegistry 自动扫描，
 * 其中的 @Tool 方法注册为可分发工具——新增工具类只需实现本接口，无需改动编排器。
 */
public interface AgentToolComponent {

    /**
     * 本机上这个组件的工具是否值得下发给模型。默认 true。
     *
     * <p>返回 false 时 ToolRegistry 仍然登记这些工具（万一被 XML 兜底路径调到，
     * 走的还是工具自己那句可行动的错误），但<b>不把它们的 spec 下发给模型</b>——
     * 模型看不见即不会去试。存在的理由是 run_python：它无条件依赖本机 Docker，
     * 而绝大多数用户机器上没有 Docker，模型把它当成"读图/OCR 的备选路子"调用，
     * 撞上 "Cannot run program docker" 后自己得出「OCR 环境不可用」的错误结论。
     *
     * <p>实现必须自己缓存探测结果并且<b>绝不抛异常</b>：本方法在 ToolRegistry
     * 的 @PostConstruct 里调用，抛出去就是后端起不来。
     */
    default boolean isAvailable() {
        return true;
    }

    /**
     * 本组件里此刻<b>确定</b>用不了的工具名（dev-board#750）。默认空集。
     *
     * <p>与 {@link #isAvailable()} 的分工：那一个是**进程级**的（@PostConstruct 探一次，
     * 例如本机有没有 Docker），这一个是**运行期**的——账户连没连、凭证配没配会在用户手里
     * 随时变，探一次没用。粒度也不同：这里按工具名，因为同一个组件里常常一半工具依赖外部
     * 服务、另一半是纯本地的（{@code WebTools} 的 search_web 要账户，browse_url 不要；
     * {@code LegalTools} 的 law_* 要账户，read_document 不要）。
     *
     * <p>处置与 {@code isAvailable()} 一致：<b>只裁 spec、不裁 resolve/execute</b>。模型看不见
     * 就不会浪费一整轮去试（实测一条纯法律问答 4 个 LLM 往返里有 2 轮花在必然「不可用」的
     * 工具上，每轮 3~5 秒），万一经 XML 兜底路径调到，拿到的仍是工具自己那句可行动的错误。
     *
     * <p><b>判不准一律返回空集</b>：宁可多下发一个会失败的工具，也不能把能用的藏起来——
     * 藏掉的表现是「这个能力整个不存在」，比失败一次严重得多。实现必须绝不抛异常
     * （抛了也会被 ToolRegistry 兜成空集），也不该做网络请求：它在每条用户消息起跑时都要跑一次。
     */
    default java.util.Set<String> currentlyUnusableTools() {
        return java.util.Set.of();
    }
}
