package com.checkba.service.ai.memory;

import com.checkba.model.entity.MemoryEntry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 证据账本格式化测试：
 * - 每条记忆附记录时间/作用域/来源文件/受保护/更新信号
 * - 顶部有"今天"时间锚点
 */
class MemoryEvidenceFormatterTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 7, 15);

    private static MemoryEntry entry(Long id, String type, String key, String value) {
        MemoryEntry e = MemoryEntry.builder()
                .id(id)
                .projectId(1L)
                .memoryType(type)
                .memoryKey(key)
                .memoryValue(value)
                .createdAt(LocalDateTime.of(2026, 5, 2, 10, 0))
                .build();
        return e;
    }

    @Test
    @DisplayName("账本包含时间锚点、条目内容与记录时间")
    void basicLedgerFormat() {
        String ledger = MemoryEvidenceFormatter.format(
                List.of(entry(1L, "decision", "交易结构", "采用协议收购")), Map.of(), TODAY);

        assertTrue(ledger.contains("今天是 2026-07-15"));
        assertTrue(ledger.contains("1. [decision] 交易结构: 采用协议收购"));
        assertTrue(ledger.contains("记录于2026-05-02"));
        assertFalse(ledger.contains("· ⚠️已被更新"), "无更新记录时不应有条目级更新注记");
    }

    @Test
    @DisplayName("受保护/来源文件/非项目作用域有对应注记，project 作用域不冗余标注")
    void provenanceAnnotations() {
        MemoryEntry protectedFileMem = entry(2L, "conclusion", "质押核查", "无质押");
        protectedFileMem.setIsProtected(true);
        protectedFileMem.setSourceFileId(42L);
        protectedFileMem.setScope(MemoryEntry.MemoryScope.FILE);

        String ledger = MemoryEvidenceFormatter.format(List.of(protectedFileMem), Map.of(), TODAY);
        assertTrue(ledger.contains("🔒受保护"));
        assertTrue(ledger.contains("来源文件#42"));
        assertTrue(ledger.contains("作用域:file"));

        String projectLedger = MemoryEvidenceFormatter.format(
                List.of(entry(3L, "fact", "k", "v")), Map.of(), TODAY);
        assertFalse(projectLedger.contains("作用域:"));
    }

    @Test
    @DisplayName("已被更新的条目标注更新信号与最新记录时间")
    void supersededSignal() {
        MemoryEntry stale = entry(4L, "fact", "质押情况", "大股东质押 30%");
        String ledger = MemoryEvidenceFormatter.format(
                List.of(stale),
                Map.of(4L, LocalDateTime.of(2026, 6, 30, 9, 0)),
                TODAY);

        assertTrue(ledger.contains("⚠️已被更新，最新记录于2026-06-30"));
    }

    @Test
    @DisplayName("createdAt 为空与 supersededAt 为 null 时不抛异常")
    void nullSafety() {
        MemoryEntry noDate = entry(5L, "fact", null, "v");
        noDate.setCreatedAt(null);

        String ledger = MemoryEvidenceFormatter.format(List.of(noDate), null, TODAY);
        assertTrue(ledger.contains("记录于未知时间"));
    }
}
