package com.checkba.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 「诉讼可视化」面板 kick-off prompt 的契约测试（dev-board#456）。
 *
 * <p>真机症状：点「股权结构」出图，模型读完材料就在对话里给了一张 markdown 表格收工，
 * 一个 litigation_* 工具都没调，追问才补上 litigation_render。两条勾住的契约：
 * <ol>
 *   <li>第一步必须落在读取工具上，不能是「写进回复」的交付物；</li>
 *   <li>完成判据挂在整段的末位（仓内「约束要挂消息末位」），并且必须与
 *       litigation_checkpoint 的「三问发给用户后停下等回复」是同一个口径——
 *       写成「不出图不许结束」会把人工确认那一停也一起禁掉。</li>
 * </ol>
 *
 * <p>buildKickoffPrompt 不碰任何字段，构造器传 null 即可。
 */
class LitigationKickoffPromptTest {

    private static final LitigationVisualPanelService SVC =
            new LitigationVisualPanelService(null, null, null, null, null);

    /** 整段的最后一个自然段（末位约束所在处）。 */
    private static String lastParagraph(String prompt) {
        String p = prompt.trim();
        int i = p.lastIndexOf("\n\n");
        return i < 0 ? p : p.substring(i + 2);
    }

    @Test
    void semanticMapStepOneIsAToolCallNotAReplyDeliverable() {
        String p = SVC.buildKickoffPrompt("本项目全部材料", "股权控制结构树");

        assertFalse(p.contains("在心里/回复里"),
                "第一步不能允许把语义地图写进回复当交付——真机上模型就是在这里收工的（dev-board#456）");
        assertFalse(p.contains("回复里写出"), "同上：回复不是本轮的交付物");
        assertTrue(p.contains("extract_file_text"),
                "第一步必须落在具体的读取工具上，与时间轴分支的第 1 步同构");
        assertTrue(p.contains("不是本轮的交付物") || p.contains("不要把它写进回复"),
                "要显式说明材料摘要/对照表不是交付物，否则模型会拿它顶交付");
    }

    @Test
    void completionCriterionSitsLastAndKeepsTheCheckpointStop() {
        String p = SVC.buildKickoffPrompt("本项目全部材料", "股权控制结构树").trim();
        String tail = lastParagraph(p);

        assertTrue(p.lastIndexOf("litigation_render") > p.lastIndexOf("逐字"),
                "完成判据必须排在 write_file/read_file 禁令之后，占住末位");
        assertTrue(tail.contains("litigation_checkpoint") && tail.contains("停下等我回复"),
                "末位那段必须先给出 checkpoint 三问后停下的合法结束方式，"
                        + "否则与 LitigationVisualTools.CHECKPOINT_NEXT_STEPS 第 1 条打架");
        assertTrue(tail.contains("litigation_render"), "另一种合法结束方式是 render 成功返回");
        assertTrue(tail.contains("<final>"), "要明说不许直接以 <final> 收工");
    }

    @Test
    void writeFileBanSurvivesTheRewrite() {
        String p = SVC.buildKickoffPrompt("本项目全部材料", "股权控制结构树");
        assertTrue(p.contains("write_file") && p.contains("read_file"),
                "语义地图不落文件是三处同写的契约（.claude/agents/litigation-visual.md），改第一步时不许顺手删掉");
        assertTrue(p.contains("litigation_checkpoint") && p.contains("litigation_render"),
                "工具链本身不变");
    }

    @Test
    void timelineBranchKeepsItsOwnPipelineAndAlsoEndsWithACompletionCriterion() {
        String t = SVC.buildKickoffPrompt("本项目全部材料", "事实经过时间轴");

        assertTrue(t.contains("litigation_timeline_start"), "时间轴走的是时间轴大师管线");
        assertFalse(t.contains("litigation_checkpoint"), "两条路不许串");

        String tail = lastParagraph(t.trim());
        assertTrue(tail.contains("litigation_timeline_render"), "末位要给出 render 这个完成判据");
        assertTrue(tail.contains("停下等我回复"), "勾选清单那一停同样是合法结束方式");
    }
}
