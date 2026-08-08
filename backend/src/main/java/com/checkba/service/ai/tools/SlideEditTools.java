package com.checkba.service.ai.tools;

import com.checkba.service.ai.EditorBridgeService;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * 演示文稿（Impress / pptx）实时编辑工具集：slide_* 原语。
 *
 * 与 doc_*（Writer）/sheet_*（Calc）三分，互不相通——工具名前缀是
 * ClientCapabilityService 会话能力过滤的依据，也是 EditorBridgeService 经
 * SSE client_action 下发到前端 EDITOR_ACTIONS 白名单的 action 名（工具名 = action 名，
 * 不做映射，沿用 sheet_* 口径，doc_* 的映射表是历史包袱）。
 *
 * PowerPoint/Impress 没有修订（redline）机制，写类原语直接生效——安全网是本轮首个
 * MODIFIED 工具前建立的文档检查点（@ToolMeta fileEffect=MODIFIED）与 doc_undo，
 * 工具描述里对模型明说，避免模型误以为可以走审阅面板撤销。
 *
 * 本文件实现设计 Phase 1（打开/读取/文本编辑，7 个原语：slide_get_overview /
 * slide_get_page / slide_read_notes / slide_write_notes / slide_goto /
 * slide_set_shape_text / slide_replace_text）与 Phase 2（页与形状结构，8 个原语：
 * slide_add_page / slide_delete_page / slide_move_page / slide_set_layout /
 * slide_add_text_box / slide_add_shape / slide_delete_shape / slide_set_shape_geometry）。
 * Phase 3（格式与表格）留待后续排期，不在本文件范围内。
 *
 * 设计依据：docs/superpowers/specs/2026-08-07-impress-bridge-design.md §4（原语表）。
 * 新建独立文件而非塞进 DocumentEditTools：后者的 doc_*+sheet_* 已 70+ 个方法，
 * ToolRegistry 经 List&lt;AgentToolComponent&gt; 自动发现，无需改动 ToolRegistry。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SlideEditTools implements AgentToolComponent {

    private final EditorBridgeService editorBridgeService;

    private static final String NO_REVISION_NOTE =
            "PPT 没有修订机制，改动直接生效，无法像 Word 那样在审阅面板里逐条撤销；" +
            "误改请用 doc_restore_checkpoint 回滚到本轮开始前的快照。";

    @Tool("【幻灯片·看】查看当前打开的演示文稿（pptx/odp）总览：每页的页码、名称、版式、母版、标题文字、" +
          "形状数、是否有备注、是否含表格。打开演示文稿后先用本工具了解结构，再决定读哪一页、改哪个形状。" +
          "Word 文档请用 doc_* 工具，电子表格请用 sheet_* 工具，本工具仅对演示文稿有效。")
    public String slide_get_overview() {
        log.info("Tool: slide_get_overview called");
        try {
            return editorBridgeService.executeEditorCommand("slide_get_overview", null);
        } catch (Exception e) {
            log.error("Failed to get slide overview", e);
            return "Error: " + e.getMessage();
        }
    }

    @Tool("【幻灯片·看】读取指定页的明细：页面尺寸（磅）、版式、母版、备注文字，以及该页每个形状的名称、" +
          "类型、位置尺寸（磅）、文字内容；表格形状另带行列数。返回的形状名（shapeName）是后续原语" +
          "（slide_set_shape_text / slide_goto 等）定位该形状的依据——未命名形状会被自动分配一个稳定名" +
          "（形如 __awd_shape_3），此后按名定位，不受形状顺序变化影响。")
    public String slide_get_page(
            @P("页码，1 开始") Integer slideNumber
    ) {
        log.info("Tool: slide_get_page called slideNumber={}", slideNumber);
        if (slideNumber == null) {
            return "Error: 缺少 slideNumber 参数（1 开始）";
        }
        try {
            Map<String, Object> params = new HashMap<>();
            params.put("slideNumber", slideNumber);
            return editorBridgeService.executeEditorCommand("slide_get_page", params);
        } catch (Exception e) {
            log.error("Failed to get slide page", e);
            return "Error: " + e.getMessage();
        }
    }

    @Tool("【幻灯片·看】读取备注页（Speaker Notes）文字。不传 slideNumber 则读取全篇所有页的备注。")
    public String slide_read_notes(
            @P("页码，1 开始；不传则读取全篇所有页") Integer slideNumber
    ) {
        log.info("Tool: slide_read_notes called slideNumber={}", slideNumber);
        try {
            Map<String, Object> params = new HashMap<>();
            if (slideNumber != null) params.put("slideNumber", slideNumber);
            return editorBridgeService.executeEditorCommand("slide_read_notes", params);
        } catch (Exception e) {
            log.error("Failed to read slide notes", e);
            return "Error: " + e.getMessage();
        }
    }

    @ToolMeta(displayName = "写入备注", category = "document", fileEffect = "MODIFIED")
    @Tool("【幻灯片·写】整体覆盖指定页的备注页（Speaker Notes）文字（不是追加）。" + NO_REVISION_NOTE)
    public String slide_write_notes(
            @P("页码，1 开始") Integer slideNumber,
            @P("备注文字，整体覆盖该页原有备注") String text
    ) {
        log.info("Tool: slide_write_notes called slideNumber={}", slideNumber);
        if (slideNumber == null) {
            return "Error: 缺少 slideNumber 参数（1 开始）";
        }
        try {
            Map<String, Object> params = new HashMap<>();
            params.put("slideNumber", slideNumber);
            params.put("text", text != null ? text : "");
            return editorBridgeService.executeEditorCommand("slide_write_notes", params);
        } catch (Exception e) {
            log.error("Failed to write slide notes", e);
            return "Error: " + e.getMessage();
        }
    }

    @Tool("【幻灯片·定位】把编辑器视图切到指定页（用户能看到 AI 正在操作哪一页），可选再选中该页某个形状。" +
          "改动一个形状前建议先用本工具定位，让操作过程对用户可见。")
    public String slide_goto(
            @P("页码，1 开始") Integer slideNumber,
            @P("要一并选中的形状名（可选，来自 slide_get_page 返回的 shapeName）") String shapeName
    ) {
        log.info("Tool: slide_goto called slideNumber={}, shapeName={}", slideNumber, shapeName);
        if (slideNumber == null) {
            return "Error: 缺少 slideNumber 参数（1 开始）";
        }
        try {
            Map<String, Object> params = new HashMap<>();
            params.put("slideNumber", slideNumber);
            if (shapeName != null && !shapeName.isBlank()) params.put("shapeName", shapeName);
            return editorBridgeService.executeEditorCommand("slide_goto", params);
        } catch (Exception e) {
            log.error("Failed to goto slide", e);
            return "Error: " + e.getMessage();
        }
    }

    @ToolMeta(displayName = "设置形状文字", category = "document", fileEffect = "MODIFIED")
    @Tool("【幻灯片·写】整体覆盖指定形状（文本框/标题/占位符）的文字（不是追加）。形状用 slide_get_page " +
          "返回的 shapeName 定位——改之前建议先调 slide_get_page 看清该页有哪些形状。" + NO_REVISION_NOTE)
    public String slide_set_shape_text(
            @P("页码，1 开始") Integer slideNumber,
            @P("形状名，来自 slide_get_page 返回的 shapeName") String shapeName,
            @P("新文字，整体覆盖该形状原有文字") String text
    ) {
        log.info("Tool: slide_set_shape_text called slideNumber={}, shapeName={}", slideNumber, shapeName);
        if (slideNumber == null) {
            return "Error: 缺少 slideNumber 参数（1 开始）";
        }
        if (shapeName == null || shapeName.isBlank()) {
            return "Error: 缺少 shapeName 参数（先用 slide_get_page 查看该页形状名）";
        }
        try {
            Map<String, Object> params = new HashMap<>();
            params.put("slideNumber", slideNumber);
            params.put("shapeName", shapeName);
            params.put("text", text != null ? text : "");
            return editorBridgeService.executeEditorCommand("slide_set_shape_text", params);
        } catch (Exception e) {
            log.error("Failed to set shape text", e);
            return "Error: " + e.getMessage();
        }
    }

    @ToolMeta(displayName = "替换幻灯片文字", category = "document", fileEffect = "MODIFIED")
    @Tool("【幻灯片·写】查找并替换文字，覆盖普通文本框、标题、占位符与表格单元格。缺省只替换第一处命中" +
          "并返回（可看清改了哪里再决定要不要继续）；all=true 替换全篇（或 slideNumber 限定页内）全部匹配。" +
          NO_REVISION_NOTE)
    public String slide_replace_text(
            @P("要查找的文字") String searchText,
            @P("替换为的文字") String replaceText,
            @P("限定页码，1 开始；不传则搜索全篇") Integer slideNumber,
            @P("是否替换全部匹配（默认 false，只替换第一处命中）") Boolean all
    ) {
        log.info("Tool: slide_replace_text called searchText={}, slideNumber={}, all={}", searchText, slideNumber, all);
        if (searchText == null || searchText.isEmpty()) {
            return "Error: 缺少 searchText 参数";
        }
        try {
            Map<String, Object> params = new HashMap<>();
            params.put("searchText", searchText);
            params.put("replaceText", replaceText != null ? replaceText : "");
            if (slideNumber != null) params.put("slideNumber", slideNumber);
            if (all != null) params.put("all", all);
            return editorBridgeService.executeEditorCommand("slide_replace_text", params);
        } catch (Exception e) {
            log.error("Failed to replace slide text", e);
            return "Error: " + e.getMessage();
        }
    }

    // ==================== Phase 2：页与形状结构 ====================

    @ToolMeta(displayName = "插入幻灯片", category = "document", fileEffect = "MODIFIED")
    @Tool("【幻灯片·写】插入一页新幻灯片。position 指插到第 N 页之后（1 开始，不传则插到末尾，" +
          "插到末尾时可能连带让另一对既有页的相对顺序也换一次，不丢内容只是顺序细节）；" +
          "title/body 可选，写在新页的标题/内容占位符；layout（版式常量）是独立参数，与 title/body " +
          "无关联，谨慎使用（多页文档上给已有页设置版式，真机实测过有清空相邻页占位符内容的风险，" +
          "改前后建议用 slide_get_overview 核对）。" + NO_REVISION_NOTE)
    public String slide_add_page(
            @P("插到第 N 页之后，1 开始；不传则插到末尾") Integer position,
            @P("版式常量（可选，独立生效，与 title/body 无关；多页文档上谨慎使用，见工具说明）") Integer layout,
            @P("标题文字（可选，以文本框形式写在新页顶部）") String title,
            @P("正文文字（可选，以文本框形式写在新页中部）") String body
    ) {
        log.info("Tool: slide_add_page called position={}, layout={}", position, layout);
        try {
            Map<String, Object> params = new HashMap<>();
            if (position != null) params.put("position", position);
            if (layout != null) params.put("layout", layout);
            if (title != null) params.put("title", title);
            if (body != null) params.put("body", body);
            return editorBridgeService.executeEditorCommand("slide_add_page", params);
        } catch (Exception e) {
            log.error("Failed to add slide page", e);
            return "Error: " + e.getMessage();
        }
    }

    @ToolMeta(displayName = "删除幻灯片", category = "document", fileEffect = "MODIFIED")
    @Tool("【幻灯片·写】删除指定页。演示文稿至少保留一页，删到只剩最后一页时会被拒绝。" + NO_REVISION_NOTE)
    public String slide_delete_page(
            @P("要删除的页码，1 开始") Integer slideNumber
    ) {
        log.info("Tool: slide_delete_page called slideNumber={}", slideNumber);
        if (slideNumber == null) {
            return "Error: 缺少 slideNumber 参数（1 开始）";
        }
        try {
            Map<String, Object> params = new HashMap<>();
            params.put("slideNumber", slideNumber);
            return editorBridgeService.executeEditorCommand("slide_delete_page", params);
        } catch (Exception e) {
            log.error("Failed to delete slide page", e);
            return "Error: " + e.getMessage();
        }
    }

    @ToolMeta(displayName = "移动幻灯片", category = "document", fileEffect = "MODIFIED")
    @Tool("【幻灯片·写】把指定页移动到新位置（重排顺序）。slideNumber 是移动前的页码，" +
          "toPosition 是目标位置，两者都是 1 开始。移到末尾时可能连带让另一对既有页的相对顺序也换一次" +
          "（不丢内容，只是顺序细节；改动大范围排序后建议用 slide_get_overview 核对最终顺序）。" +
          NO_REVISION_NOTE)
    public String slide_move_page(
            @P("要移动的页码（移动前），1 开始") Integer slideNumber,
            @P("目标位置，1 开始") Integer toPosition
    ) {
        log.info("Tool: slide_move_page called slideNumber={}, toPosition={}", slideNumber, toPosition);
        if (slideNumber == null || toPosition == null) {
            return "Error: 缺少 slideNumber 或 toPosition 参数（均 1 开始）";
        }
        try {
            Map<String, Object> params = new HashMap<>();
            params.put("slideNumber", slideNumber);
            params.put("toPosition", toPosition);
            return editorBridgeService.executeEditorCommand("slide_move_page", params);
        } catch (Exception e) {
            log.error("Failed to move slide page", e);
            return "Error: " + e.getMessage();
        }
    }

    @ToolMeta(displayName = "设置版式", category = "document", fileEffect = "MODIFIED")
    @Tool("【幻灯片·写】设置指定页的版式（layout，AutoLayout 常量）和/或母版（masterName，" +
          "按名字匹配演示文稿现有母版）。两个参数至少给一个。谨慎使用 layout：真机实测过在多页文档上" +
          "设置某页版式可能连带清空共享同一母版的相邻页占位符内容，改前后建议用 slide_get_overview " +
          "核对相邻页未受影响，误改用 doc_restore_checkpoint 回滚。" + NO_REVISION_NOTE)
    public String slide_set_layout(
            @P("页码，1 开始") Integer slideNumber,
            @P("版式常量（可选）") Integer layout,
            @P("母版名（可选，按名字匹配演示文稿现有母版）") String masterName
    ) {
        log.info("Tool: slide_set_layout called slideNumber={}, layout={}, masterName={}", slideNumber, layout, masterName);
        if (slideNumber == null) {
            return "Error: 缺少 slideNumber 参数（1 开始）";
        }
        if (layout == null && (masterName == null || masterName.isBlank())) {
            return "Error: layout 与 masterName 至少给一个";
        }
        try {
            Map<String, Object> params = new HashMap<>();
            params.put("slideNumber", slideNumber);
            if (layout != null) params.put("layout", layout);
            if (masterName != null && !masterName.isBlank()) params.put("masterName", masterName);
            return editorBridgeService.executeEditorCommand("slide_set_layout", params);
        } catch (Exception e) {
            log.error("Failed to set slide layout", e);
            return "Error: " + e.getMessage();
        }
    }

    @ToolMeta(displayName = "插入文本框", category = "document", fileEffect = "MODIFIED")
    @Tool("【幻灯片·写】在指定页插入一个新文本框。位置尺寸单位为磅（pt），不传则用默认值" +
          "（left/top=100, width=300, height=80）。可选设置字号/加粗/颜色。" + NO_REVISION_NOTE)
    public String slide_add_text_box(
            @P("页码，1 开始") Integer slideNumber,
            @P("文本框文字") String text,
            @P("左边距（磅，可选，默认 100）") Double left,
            @P("上边距（磅，可选，默认 100）") Double top,
            @P("宽度（磅，可选，默认 300）") Double width,
            @P("高度（磅，可选，默认 80）") Double height,
            @P("字号（磅，可选）") Double fontSize,
            @P("是否加粗（可选）") Boolean bold,
            @P("文字颜色，形如 #RRGGBB（可选）") String color
    ) {
        log.info("Tool: slide_add_text_box called slideNumber={}", slideNumber);
        if (slideNumber == null) {
            return "Error: 缺少 slideNumber 参数（1 开始）";
        }
        try {
            Map<String, Object> params = new HashMap<>();
            params.put("slideNumber", slideNumber);
            params.put("text", text != null ? text : "");
            if (left != null) params.put("left", left);
            if (top != null) params.put("top", top);
            if (width != null) params.put("width", width);
            if (height != null) params.put("height", height);
            if (fontSize != null) params.put("fontSize", fontSize);
            if (bold != null) params.put("bold", bold);
            if (color != null) params.put("color", color);
            return editorBridgeService.executeEditorCommand("slide_add_text_box", params);
        } catch (Exception e) {
            log.error("Failed to add text box", e);
            return "Error: " + e.getMessage();
        }
    }

    @ToolMeta(displayName = "插入形状", category = "document", fileEffect = "MODIFIED")
    @Tool("【幻灯片·写】在指定页插入一个几何形状：rectangle（矩形）/ ellipse（椭圆）/ " +
          "triangle（三角形）/ line（直线）。位置尺寸单位为磅（pt），不传则用默认值" +
          "（left/top=100, width=200, height=150）。可选填充色与形状内文字。" + NO_REVISION_NOTE)
    public String slide_add_shape(
            @P("页码，1 开始") Integer slideNumber,
            @P("形状类型：rectangle / ellipse / triangle / line") String shapeType,
            @P("左边距（磅，可选，默认 100）") Double left,
            @P("上边距（磅，可选，默认 100）") Double top,
            @P("宽度（磅，可选，默认 200）") Double width,
            @P("高度（磅，可选，默认 150）") Double height,
            @P("形状内文字（可选）") String text,
            @P("填充色，形如 #RRGGBB（可选）") String fillColor
    ) {
        log.info("Tool: slide_add_shape called slideNumber={}, shapeType={}", slideNumber, shapeType);
        if (slideNumber == null) {
            return "Error: 缺少 slideNumber 参数（1 开始）";
        }
        if (shapeType == null || shapeType.isBlank()) {
            return "Error: 缺少 shapeType 参数（rectangle/ellipse/triangle/line）";
        }
        try {
            Map<String, Object> params = new HashMap<>();
            params.put("slideNumber", slideNumber);
            params.put("shapeType", shapeType);
            if (left != null) params.put("left", left);
            if (top != null) params.put("top", top);
            if (width != null) params.put("width", width);
            if (height != null) params.put("height", height);
            if (text != null) params.put("text", text);
            if (fillColor != null) params.put("fillColor", fillColor);
            return editorBridgeService.executeEditorCommand("slide_add_shape", params);
        } catch (Exception e) {
            log.error("Failed to add shape", e);
            return "Error: " + e.getMessage();
        }
    }

    @ToolMeta(displayName = "删除形状", category = "document", fileEffect = "MODIFIED")
    @Tool("【幻灯片·写】按 shapeName 精确删除指定页的一个形状（来自 slide_get_page 返回的 shapeName）。" +
          NO_REVISION_NOTE)
    public String slide_delete_shape(
            @P("页码，1 开始") Integer slideNumber,
            @P("要删除的形状名，来自 slide_get_page 返回的 shapeName") String shapeName
    ) {
        log.info("Tool: slide_delete_shape called slideNumber={}, shapeName={}", slideNumber, shapeName);
        if (slideNumber == null) {
            return "Error: 缺少 slideNumber 参数（1 开始）";
        }
        if (shapeName == null || shapeName.isBlank()) {
            return "Error: 缺少 shapeName 参数（先用 slide_get_page 查看该页形状名）";
        }
        try {
            Map<String, Object> params = new HashMap<>();
            params.put("slideNumber", slideNumber);
            params.put("shapeName", shapeName);
            return editorBridgeService.executeEditorCommand("slide_delete_shape", params);
        } catch (Exception e) {
            log.error("Failed to delete shape", e);
            return "Error: " + e.getMessage();
        }
    }

    @ToolMeta(displayName = "调整形状位置尺寸", category = "document", fileEffect = "MODIFIED")
    @Tool("【幻灯片·写】移动和/或改变指定形状的位置尺寸（磅）。left/top/width/height 均可选，" +
          "不传的保留原值。用于逐步调整排版；返回值带 before/after 两组数值供核对实际生效结果。" +
          NO_REVISION_NOTE)
    public String slide_set_shape_geometry(
            @P("页码，1 开始") Integer slideNumber,
            @P("形状名，来自 slide_get_page 返回的 shapeName") String shapeName,
            @P("左边距（磅，可选）") Double left,
            @P("上边距（磅，可选）") Double top,
            @P("宽度（磅，可选）") Double width,
            @P("高度（磅，可选）") Double height
    ) {
        log.info("Tool: slide_set_shape_geometry called slideNumber={}, shapeName={}", slideNumber, shapeName);
        if (slideNumber == null) {
            return "Error: 缺少 slideNumber 参数（1 开始）";
        }
        if (shapeName == null || shapeName.isBlank()) {
            return "Error: 缺少 shapeName 参数（先用 slide_get_page 查看该页形状名）";
        }
        try {
            Map<String, Object> params = new HashMap<>();
            params.put("slideNumber", slideNumber);
            params.put("shapeName", shapeName);
            if (left != null) params.put("left", left);
            if (top != null) params.put("top", top);
            if (width != null) params.put("width", width);
            if (height != null) params.put("height", height);
            return editorBridgeService.executeEditorCommand("slide_set_shape_geometry", params);
        } catch (Exception e) {
            log.error("Failed to set shape geometry", e);
            return "Error: " + e.getMessage();
        }
    }
}
