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

    /**
     * conversationId -> (fileId -> 本轮快照信息)。
     *
     * <p>此前 key 只有 conversationId、幂等判据也只看 conversationId 是否已有快照——
     * 同一轮里 doc_open_file 切换活跃文档后（guard.activeFileId 变了），第二个文件的
     * 修改类工具执行前 ensureCheckpoint 直接因为「本会话已有快照」短路跳过，第二个文件
     * 从未被快照过。doc_restore_checkpoint 恢复时用的还是第一个文件的快照：覆盖 A、
     * 对 A 发 reload，却告诉用户「已恢复，本轮所有修改已丢弃」——用户这一轮真正改的是 B，
     * B 的修改一个都没回退，且用户会误以为已经恢复干净。
     * 按 (conversationId, fileId) 存快照才能让同一轮里碰过的每个文件都能被独立恢复。
     */
    private final Map<String, Map<Long, Checkpoint>> checkpoints = new ConcurrentHashMap<>();

    private record Checkpoint(Long fileId, String checkpointKey, long createdAt) {}

    /**
     * 为指定文件创建本轮快照（幂等：同一会话同一文件已有快照则跳过；不同文件各自独立）。
     * 任何失败只记日志、不阻断工具执行——快照是保险，不是主流程。
     */
    public void ensureCheckpoint(String conversationId, Long fileId) {
        if (conversationId == null || fileId == null) return;
        Map<Long, Checkpoint> perFile = checkpoints.computeIfAbsent(conversationId, k -> new ConcurrentHashMap<>());
        if (perFile.containsKey(fileId)) return;
        try {
            ProjectFile file = projectFileService.getFile(fileId);
            if (file == null || file.getFilePath() == null) return;
            byte[] bytes = projectFileService.getFileBytes(fileId);
            if (bytes == null || bytes.length == 0) return;

            String checkpointKey = "checkpoints/" + conversationId + "/" + fileId + "_" + System.currentTimeMillis();
            StorageService storage = storageServiceFactory.getStorageService();
            storage.save(checkpointKey, new ByteArrayInputStream(bytes));
            perFile.put(fileId, new Checkpoint(fileId, checkpointKey, System.currentTimeMillis()));
            log.info("Document checkpoint created: conv={}, fileId={}, key={}, size={}",
                    conversationId, fileId, checkpointKey, bytes.length);
        } catch (Exception e) {
            log.warn("Failed to create document checkpoint for conv={}, fileId={}", conversationId, fileId, e);
        }
    }

    /**
     * 恢复本轮快照：把本轮碰过的<b>全部</b>文件的快照内容写回原存储路径，并逐个通知编辑器重载。
     * 返回给模型的结果描述（多文件时合并成一条，个别文件失败不影响其余文件继续恢复）。
     */
    public String restore(String conversationId) {
        Map<Long, Checkpoint> perFile = checkpoints.get(conversationId);
        if (perFile == null || perFile.isEmpty()) {
            return "Error: 本轮没有可恢复的文档快照（本轮尚未执行过修改类工具，或快照创建失败）。请改用 doc_undo 逐步撤销。";
        }
        StorageService storage = storageServiceFactory.getStorageService();
        java.util.List<String> restoredNames = new java.util.ArrayList<>();
        java.util.List<String> failures = new java.util.ArrayList<>();
        for (Checkpoint cp : perFile.values()) {
            try {
                ProjectFile file = projectFileService.getFile(cp.fileId());
                if (file == null || file.getFilePath() == null) {
                    failures.add("文件 " + cp.fileId() + " 已不存在");
                    continue;
                }
                try (InputStream in = storage.load(cp.checkpointKey()).getInputStream()) {
                    storage.save(file.getFilePath(), in);
                }
                // 通知前端编辑器重新加载该文件（丢弃编辑器内当前状态）
                editorBridgeService.sendReloadFileAction(file);
                restoredNames.add(file.getName());
                log.info("Document checkpoint restored: conv={}, fileId={}, key={}",
                        conversationId, cp.fileId(), cp.checkpointKey());
            } catch (Exception e) {
                log.error("Failed to restore document checkpoint for conv={}, fileId={}", conversationId, cp.fileId(), e);
                failures.add("文件 " + cp.fileId() + " 恢复失败：" + e.getMessage());
            }
        }
        if (restoredNames.isEmpty()) {
            return "Error: 快照恢复失败：" + String.join("；", failures);
        }
        String names = restoredNames.stream()
                .map(n -> "《" + n + "》")
                .collect(java.util.stream.Collectors.joining("、"));
        String failureSuffix = failures.isEmpty() ? "" : "（另有恢复失败：" + String.join("；", failures) + "）";
        return String.format("已将%s恢复到本轮开始前的快照，编辑器正在重新加载。本轮所有修改（含修订）已丢弃，请重新规划后再操作。%s",
                names, failureSuffix);
    }

    /**
     * 新的一轮开始时清除上一轮快照（每轮一个独立检查点，语义不变：按 conversationId 整体清空）。
     *
     * <p>此前只摘掉内存里的登记条目，存储里的 blob 从来没人删过——checkpoints/ 前缀下的对象
     * 只增不减，每一轮至少一次修改类文档工具调用就留一条永久占用（审计条目：Document checkpoint
     * blobs are written to storage but never deleted）。这里连同 blob 一起删是安全的：
     * {@link #restore} 只能恢复"当前还在内存 map 里"的快照，一旦这里把某个 conversationId
     * 的条目摘掉，对应 blob 已经彻底不可达——没有任何代码路径还能把它读回来，不是「提前删了
     * 还有用的东西」，是删一个已经没用的东西。删除失败只记日志：这是清理动作，
     * 不能因为存储抖动挡住新一轮的正常执行。
     */
    public void clearForNewRun(String conversationId) {
        Map<Long, Checkpoint> perFile = checkpoints.remove(conversationId);
        if (perFile == null || perFile.isEmpty()) {
            return;
        }
        StorageService storage = storageServiceFactory.getStorageService();
        for (Checkpoint cp : perFile.values()) {
            try {
                storage.delete(cp.checkpointKey());
            } catch (Exception e) {
                log.warn("Failed to delete stale document checkpoint blob: conv={}, fileId={}, key={}",
                        conversationId, cp.fileId(), cp.checkpointKey(), e);
            }
        }
    }

    public boolean hasCheckpoint(String conversationId) {
        Map<Long, Checkpoint> perFile = checkpoints.get(conversationId);
        return perFile != null && !perFile.isEmpty();
    }
}
