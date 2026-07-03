package com.checkba.service.ai.tools;

import com.checkba.service.ai.EditorBridgeService;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * PPTX 编辑工具集
 * 
 * 提供 Agent 编辑已打开 PPT 的能力：
 * 1. 获取演示文稿信息
 * 2. 获取幻灯片内容
 * 3. 修改幻灯片文本（带修订标记）
 * 4. 插入/删除文本（带修订标记）
 * 5. 保存文件
 * 
 * 技术说明：
 * - 通过 SSE client_action 发送命令到前端
 * - 前端 useEditorBridge.js 分发到嵌入式 LibreOffice 执行器执行操作
 * - PPT 不支持原生修订模式，使用视觉标记替代：
 *   - 新增内容：用【】括起来
 *   - 删除内容：用【删除：xxx】标记
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PptxEditTools implements AgentToolComponent {

    private final EditorBridgeService editorBridgeService;

    // ==================== 信息获取工具 ====================

    @Tool("获取当前打开的 PPT 演示文稿信息，包括页数、只读状态等。在编辑 PPT 之前应先调用此工具了解文档结构。")
    public String pptx_get_presentation_info() {
        log.info("Tool: pptx_get_presentation_info called");
        try {
            return editorBridgeService.executeEditorCommand("ppt_get_presentation_info", null);
        } catch (Exception e) {
            log.error("Failed to get presentation info", e);
            return "{\"error\": \"" + e.getMessage() + "\"}";
        }
    }

    @Tool("获取指定幻灯片的文本内容。返回该页所有文本区域的内容和索引，用于后续编辑。")
    public String pptx_get_slide_content(
            @P("幻灯片页码，从 1 开始") Integer slideIndex
    ) {
        log.info("Tool: pptx_get_slide_content called, slideIndex={}", slideIndex);
        try {
            if (slideIndex == null || slideIndex < 1) {
                return "{\"error\": \"幻灯片页码必须大于 0\"}";
            }
            return editorBridgeService.executeEditorCommand("ppt_get_slide_content", 
                    Map.of("slideIndex", slideIndex));
        } catch (Exception e) {
            log.error("Failed to get slide content", e);
            return "{\"error\": \"" + e.getMessage() + "\"}";
        }
    }

    @Tool("获取 PPT 当前选区信息。了解用户当前选中的内容类型（文本/形状/幻灯片）。")
    public String pptx_get_selection() {
        log.info("Tool: pptx_get_selection called");
        try {
            return editorBridgeService.executeEditorCommand("ppt_get_selection", null);
        } catch (Exception e) {
            log.error("Failed to get PPT selection", e);
            return "{\"error\": \"" + e.getMessage() + "\"}";
        }
    }

    // ==================== 编辑工具（带修订标记） ====================

    @Tool("修改幻灯片中指定文本区域的内容。修改会用【】标记，便于用户审阅。使用前需先调用 pptx_get_slide_content 获取 shapeIndex。")
    public String pptx_modify_slide_text(
            @P("幻灯片页码，从 1 开始") Integer slideIndex,
            @P("文本区域索引（从 pptx_get_slide_content 返回的 shapeIndex 获取）") Integer shapeIndex,
            @P("新的文本内容") String newText
    ) {
        log.info("Tool: pptx_modify_slide_text called, slide={}, shape={}", slideIndex, shapeIndex);
        try {
            if (slideIndex == null || slideIndex < 1) {
                return "{\"error\": \"幻灯片页码必须大于 0\"}";
            }
            if (shapeIndex == null || shapeIndex < 1) {
                return "{\"error\": \"文本区域索引必须大于 0\"}";
            }
            if (newText == null) {
                return "{\"error\": \"新文本内容不能为空\"}";
            }
            
            return editorBridgeService.executeEditorCommand("ppt_modify_slide_text", 
                    Map.of(
                            "slideIndex", slideIndex,
                            "shapeIndex", shapeIndex,
                            "newText", newText,
                            "markAsRevision", true  // 始终添加修订标记
                    ));
        } catch (Exception e) {
            log.error("Failed to modify slide text", e);
            return "{\"error\": \"" + e.getMessage() + "\"}";
        }
    }

    @Tool("在幻灯片的指定文本区域插入新内容。新内容会用【】标记，便于用户审阅。")
    public String pptx_insert_text(
            @P("幻灯片页码，从 1 开始") Integer slideIndex,
            @P("文本区域索引") Integer shapeIndex,
            @P("要插入的文本内容") String text,
            @P("插入位置：start（开头）、end（末尾）、replace（替换全部）") String position
    ) {
        log.info("Tool: pptx_insert_text called, slide={}, shape={}, position={}", 
                slideIndex, shapeIndex, position);
        try {
            if (slideIndex == null || slideIndex < 1) {
                return "{\"error\": \"幻灯片页码必须大于 0\"}";
            }
            if (shapeIndex == null || shapeIndex < 1) {
                return "{\"error\": \"文本区域索引必须大于 0\"}";
            }
            if (text == null || text.isEmpty()) {
                return "{\"error\": \"插入文本不能为空\"}";
            }
            
            String pos = position != null ? position : "end";
            if (!pos.equals("start") && !pos.equals("end") && !pos.equals("replace")) {
                pos = "end";
            }
            
            return editorBridgeService.executeEditorCommand("ppt_insert_text", 
                    Map.of(
                            "slideIndex", slideIndex,
                            "shapeIndex", shapeIndex,
                            "text", text,
                            "position", pos
                    ));
        } catch (Exception e) {
            log.error("Failed to insert text in PPT", e);
            return "{\"error\": \"" + e.getMessage() + "\"}";
        }
    }

    @Tool("标记删除幻灯片中的指定文本。删除的内容会用【删除：xxx】标记，便于用户审阅后确认删除。")
    public String pptx_mark_delete_text(
            @P("幻灯片页码，从 1 开始") Integer slideIndex,
            @P("文本区域索引") Integer shapeIndex,
            @P("要删除的文本内容（必须与原文完全匹配）") String textToDelete
    ) {
        log.info("Tool: pptx_mark_delete_text called, slide={}, shape={}", slideIndex, shapeIndex);
        try {
            if (slideIndex == null || slideIndex < 1) {
                return "{\"error\": \"幻灯片页码必须大于 0\"}";
            }
            if (shapeIndex == null || shapeIndex < 1) {
                return "{\"error\": \"文本区域索引必须大于 0\"}";
            }
            if (textToDelete == null || textToDelete.isEmpty()) {
                return "{\"error\": \"要删除的文本不能为空\"}";
            }
            
            return editorBridgeService.executeEditorCommand("ppt_mark_delete_text", 
                    Map.of(
                            "slideIndex", slideIndex,
                            "shapeIndex", shapeIndex,
                            "textToDelete", textToDelete
                    ));
        } catch (Exception e) {
            log.error("Failed to mark delete text in PPT", e);
            return "{\"error\": \"" + e.getMessage() + "\"}";
        }
    }

    // ==================== 保存工具 ====================

    @Tool("保存当前打开的 PPT 文件。修改完成后应调用此工具保存。")
    public String pptx_save() {
        log.info("Tool: pptx_save called");
        try {
            return editorBridgeService.executeEditorCommand("ppt_save", null);
        } catch (Exception e) {
            log.error("Failed to save PPT", e);
            return "{\"error\": \"" + e.getMessage() + "\"}";
        }
    }
}

