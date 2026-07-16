package com.checkba.service.ai;

import com.checkba.model.entity.ProjectFile;
import com.checkba.service.ProjectFileService;
import com.checkba.storage.StorageService;
import com.checkba.storage.StorageServiceFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 文档检查点服务（run 级快照，Cursor/Cline checkpoint 模式的文件级最简实现）。
 *
 * 每轮 Agent 运行在第一个"修改类"文档工具执行前，把目标文件在存储中的当前内容
 * 复制一份到 checkpoints/ 目录；模型把文档改乱且 doc_undo 无法逐步退回时，
 * 可用 doc_restore_checkpoint 恢复到本轮开始前的状态（恢复后编辑器自动重载）。
 *
 * 局限（已在工具描述中告知模型）：快照取自最近一次保存到存储的内容，
 * 编辑器中未保存的改动不在快照里；且 AI 修改本身走修订模式，常规纠错优先 doc_undo。
 */
@Service
@RequiredArgsConstructor
public class DocumentCheckpointService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(DocumentCheckpointService.class);

    private final ProjectFileService projectFileService;
    private final StorageServiceFactory storageServiceFactory;
    private final EditorBridgeService editorBridgeService;

    /** conversationId -> 本轮快照信息 */
    private final Map<String, Checkpoint> checkpoints = new ConcurrentHashMap<>();

    private record Checkpoint(Long fileId, String checkpointKey, long createdAt) {}

    /**
     * 为指定文件创建本轮快照（幂等：同一会话已有快照则跳过）。
     * 任何失败只记日志、不阻断工具执行——快照是保险，不是主流程。
     */
    public void ensureCheckpoint(String conversationId, Long fileId) {
        if (conversationId == null || fileId == null) return;
        if (checkpoints.containsKey(conversationId)) return;
        try {
            ProjectFile file = projectFileService.getFile(fileId);
            if (file == null || file.getFilePath() == null) return;
            byte[] bytes = projectFileService.getFileBytes(fileId);
            if (bytes == null || bytes.length == 0) return;

            String checkpointKey = "checkpoints/" + conversationId + "/" + fileId + "_" + System.currentTimeMillis();
            StorageService storage = storageServiceFactory.getStorageService();
            storage.save(checkpointKey, new ByteArrayInputStream(bytes));
            checkpoints.put(conversationId, new Checkpoint(fileId, checkpointKey, System.currentTimeMillis()));
            log.info("Document checkpoint created: conv={}, fileId={}, key={}, size={}",
                    conversationId, fileId, checkpointKey, bytes.length);
        } catch (Exception e) {
            log.warn("Failed to create document checkpoint for conv={}, fileId={}", conversationId, fileId, e);
        }
    }

    /**
     * 恢复本轮快照：把快照内容写回原文件存储路径，并通知编辑器重载。
     * 返回给模型的结果描述。
     */
    public String restore(String conversationId) {
        Checkpoint cp = checkpoints.get(conversationId);
        if (cp == null) {
            return "Error: 本轮没有可恢复的文档快照（本轮尚未执行过修改类工具，或快照创建失败）。请改用 doc_undo 逐步撤销。";
        }
        try {
            ProjectFile file = projectFileService.getFile(cp.fileId());
            if (file == null || file.getFilePath() == null) {
                return "Error: 快照对应的文件已不存在。";
            }
            StorageService storage = storageServiceFactory.getStorageService();
            try (InputStream in = storage.load(cp.checkpointKey()).getInputStream()) {
                storage.save(file.getFilePath(), in);
            }
            // 通知前端编辑器重新加载该文件（丢弃编辑器内当前状态）
            editorBridgeService.sendReloadFileAction(file);
            log.info("Document checkpoint restored: conv={}, fileId={}, key={}",
                    conversationId, cp.fileId(), cp.checkpointKey());
            return String.format("已将《%s》恢复到本轮开始前的快照，编辑器正在重新加载。本轮所有修改（含修订）已丢弃，请重新规划后再操作。",
                    file.getName());
        } catch (Exception e) {
            log.error("Failed to restore document checkpoint for conv={}", conversationId, e);
            return "Error: 快照恢复失败：" + e.getMessage();
        }
    }

    /**
     * 新的一轮开始时清除上一轮快照（每轮一个独立检查点）。
     */
    public void clearForNewRun(String conversationId) {
        checkpoints.remove(conversationId);
    }

    public boolean hasCheckpoint(String conversationId) {
        return checkpoints.containsKey(conversationId);
    }
}
