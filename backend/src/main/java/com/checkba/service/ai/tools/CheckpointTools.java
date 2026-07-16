package com.checkba.service.ai.tools;

import com.checkba.service.ai.DocumentCheckpointService;
import com.checkba.service.ai.context.ProjectContextHolder;
import dev.langchain4j.agent.tool.Tool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 文档检查点工具：恢复本轮开始前的文档快照。
 * 快照由编排器在本轮第一个修改类工具执行前自动创建。
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class CheckpointTools implements AgentToolComponent {

    private final DocumentCheckpointService documentCheckpointService;

    @Tool("【恢复】把文档恢复到本轮开始前的快照（检查点）。这是最后手段：" +
          "仅在文档已被改乱、doc_undo 无法逐步退回时使用。" +
          "恢复会丢弃本轮的所有修改（包括修订标记）并让编辑器重新加载文档。" +
          "常规纠错请优先使用 doc_undo。")
    @ToolMeta(displayName = "恢复文档快照", category = "document", fileEffect = "MODIFIED")
    public String doc_restore_checkpoint() {
        log.info("Tool: doc_restore_checkpoint called");
        String conversationId = ProjectContextHolder.getConversationId();
        if (conversationId == null || conversationId.isEmpty()) {
            return "Error: 无法获取当前会话ID。";
        }
        return documentCheckpointService.restore(conversationId);
    }
}
