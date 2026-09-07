package com.checkba.service.ai.tools;

import com.checkba.repository.UserRepository;
import com.checkba.service.AdminAccessService;
import com.checkba.service.ai.context.ProjectContextHolder;
import com.checkba.service.capability.CapabilityInstallService;
import com.checkba.service.capability.CapabilitySlotRegistry;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 能力槽的 AI 工具（设计稿 docs/superpowers/specs/2026-09-07-capability-slots-self-upgrade-design.md
 * 第 5.2 节）：让「粘一个 GitHub 链接 + 一句话」在对话里走通。
 *
 * <p><b>工具闸写在代码里</b>（登录 + admin），不靠 skill 的 allowed_tools——那份白名单
 * 只裁可见性、不拦分发（XML 兜底协议下模型凭记忆写 tool_code 照样能命中）。
 *
 * <p><b>确认流程</b>：{@code capability_install} 只做 plan（拉取 + 校验，不落盘），把计划
 * 原样交给模型；模型必须用 {@code <question>} 把仓库、commit、文件数、权限、目标槽念给
 * 用户听并停机等回答，用户点头后才调 {@code capability_apply(planId)}。
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class CapabilityTools implements AgentToolComponent {

    private final CapabilitySlotRegistry slotRegistry;
    private final CapabilityInstallService installService;
    private final UserRepository userRepository;
    private final AdminAccessService adminAccessService;

    @ToolMeta(displayName = "查看能力实现", category = "plugin")
    @Tool("列出宿主的全部能力槽（可替换的能力，如诉讼可视化出图引擎）、每个槽的候选实现与当前选择。"
            + "用户问「现在用的是哪个引擎」「有哪些可选实现」，或准备切换/回滚前，先调这个工具。")
    public String capability_list() {
        log.info("Tool: capability_list called");
        String denied = requireAdmin();
        if (denied != null) {
            return denied;
        }
        StringBuilder sb = new StringBuilder();
        for (CapabilitySlotRegistry.SlotDef def : slotRegistry.slots()) {
            String selected = slotRegistry.selectedRef(def.id());
            if (selected == null || selected.isBlank()) {
                selected = CapabilitySlotRegistry.REF_BUILTIN;
            }
            sb.append("能力槽 ").append(def.id()).append("（").append(def.name())
                    .append("，协议 ").append(def.protocol()).append("）\n");
            sb.append("  当前实现: ").append(selected);
            if (slotRegistry.isDegraded(def.id())) {
                sb.append("（已降级到内置——选中的实现当前不可用）");
            }
            sb.append("\n");
            for (CapabilitySlotRegistry.Candidate c : slotRegistry.candidates(def.id())) {
                sb.append("  - ").append(c.ref()).append(" [").append(c.source()).append("]")
                        .append(c.unsigned() ? "（未签名）" : "")
                        .append(c.available() ? "" : "（不可用：" + c.reason() + "）")
                        .append("\n");
            }
        }
        if (sb.length() == 0) {
            return "当前没有注册任何能力槽。";
        }
        return sb.toString();
    }

    @ToolMeta(displayName = "准备能力升级", category = "plugin")
    @Tool("从一个 GitHub 仓库链接拉取能力包源码并校验，返回「安装计划」（仓库、commit、文件数、"
            + "声明的权限、落到哪个能力槽、能否自动安装、拒绝理由）。**本工具不安装任何东西**。"
            + "拿到计划后必须用 <question> 把计划念给用户听并停机等确认，用户明确同意后再调 "
            + "capability_apply(planId)。计划里有拒绝理由时不要重试安装，先按理由逐条修复或如实告诉用户。"
            + "只收 https://github.com/<owner>/<repo> 或 .../tree/<分支或标签> 形态的链接。")
    public String capability_install(
            @P("GitHub 仓库链接，如 https://github.com/acme/litviz-engine 或 .../tree/v2") String url
    ) {
        log.info("Tool: capability_install called for url={}", url);
        String denied = requireAdmin();
        if (denied != null) {
            return denied;
        }
        try {
            CapabilityInstallService.Plan plan = installService.plan(url);
            StringBuilder sb = new StringBuilder();
            sb.append("安装计划 planId=").append(plan.planId()).append("\n");
            sb.append("来源: ").append(plan.owner()).append("/").append(plan.repo())
                    .append("@").append(plan.ref())
                    .append(plan.commit() == null || plan.commit().isBlank() ? "" : "（" + plan.commit() + "）")
                    .append("\n");
            sb.append("插件: ").append(plan.pluginId()).append(" ").append(plan.pluginName())
                    .append(" ").append(plan.pluginVersion()).append("\n");
            sb.append("档位: ").append(plan.kind())
                    .append("；能力槽: ").append(plan.slots().isEmpty() ? "无" : String.join(", ", plan.slots()))
                    .append("\n");
            sb.append("权限: ").append(plan.permissions().isEmpty() ? "无" : String.join(", ", plan.permissions()))
                    .append("；文件 ").append(plan.fileCount()).append(" 个，共 ")
                    .append(plan.totalBytes() / 1024).append(" KB\n");
            if (plan.canAutoInstall()) {
                sb.append("可以自动安装。请用 <question> 把以上信息念给用户并等他确认，"
                        + "确认后调用 capability_apply(\"").append(plan.planId()).append("\")。");
            } else {
                sb.append("不能自动安装，理由:\n");
                for (String r : plan.reasons()) {
                    sb.append("  - ").append(r).append("\n");
                }
            }
            return sb.toString();
        } catch (IllegalArgumentException e) {
            return "Error: " + e.getMessage();
        } catch (Exception e) {
            return "Error: 拉取失败: " + e.getMessage();
        }
    }

    @ToolMeta(displayName = "执行能力升级", category = "plugin")
    @Tool("按 capability_install 返回的 planId 真正安装能力包（落盘 + 启用）。"
            + "**只有在用户以 <question> 明确确认之后才允许调用**。安装完成后如果这个包提供了"
            + "某个能力槽的实现，还要用 capability_select 切过去才会生效。")
    public String capability_apply(
            @P("capability_install 返回的 planId") String planId
    ) {
        log.info("Tool: capability_apply called for planId={}", planId);
        String denied = requireAdmin();
        if (denied != null) {
            return denied;
        }
        try {
            String id = installService.apply(planId);
            return "已安装并启用能力包「" + id + "」。若它提供了能力槽实现，请用 capability_list 看候选引用，"
                    + "再用 capability_select 切换过去。";
        } catch (IllegalArgumentException e) {
            return "Error: 安装被拒绝:\n" + e.getMessage();
        } catch (Exception e) {
            return "Error: 安装失败: " + e.getMessage();
        }
    }

    @ToolMeta(displayName = "切换能力实现", category = "plugin")
    @Tool("把某个能力槽切换到指定的候选实现（切换即生效，可回滚）。ref 用 capability_list 里列出的"
            + "候选引用原文：内置是 builtin，签名包是 pack:<packId>，能力包是 plugin:<插件id>:<实现id>。")
    public String capability_select(
            @P("能力槽 id，如 litigation.diagram") String slot,
            @P("候选实现引用，如 builtin / pack:litigation-visual / plugin:acme-litviz:engine") String ref
    ) {
        log.info("Tool: capability_select called slot={} ref={}", slot, ref);
        String denied = requireAdmin();
        if (denied != null) {
            return denied;
        }
        try {
            slotRegistry.select(slot, ref);
            return "能力槽「" + slot + "」已切换到 " + ref + "，立即生效。不满意可以让用户在设置页「能力升级」里回滚。";
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    /** 登录 + admin 闸。不满足时返回给模型的说明（不是异常——模型要能把原因转述给用户）。 */
    private String requireAdmin() {
        Long userId = ProjectContextHolder.getUserId();
        if (userId == null) {
            return "Error: 当前会话没有登录身份，不能操作能力槽。";
        }
        boolean admin = userRepository.findById(userId).map(adminAccessService::isAdmin).orElse(false);
        if (!admin) {
            return "Error: 只有管理员可以安装能力包或切换能力实现，请让管理员在设置页「能力升级」里操作。";
        }
        return null;
    }
}
