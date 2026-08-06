package com.checkba.version.memory;

import com.checkba.model.entity.MemoryEntry;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 导出/回灌 round-trip（spec Phase A 验证标准第 1 条）：A 写记忆 → push →
 * B fetch 回灌 → B 的 DB 命中同一条（uid 相同、内容相同、归属换成 B 本机领域）。
 * 两台「机器」= 两个独立存储根 + 独立 map 后备 DB，共享一个 file:// 裸仓库。
 */
class MemorySyncRoundTripTest {

    @TempDir
    Path tmp;

    private String hubUrl;
    private MemorySyncTestMachine a;
    private MemorySyncTestMachine b;

    @BeforeEach
    void setUp() throws Exception {
        Path hub = tmp.resolve("hub.git");
        Git.init().setBare(true).setDirectory(hub.toFile()).call().close();
        hubUrl = hub.toUri().toString();
        a = new MemorySyncTestMachine(tmp.resolve("machine-a"), hubUrl, MemoryRealm.project(1));
        b = new MemorySyncTestMachine(tmp.resolve("machine-b"), hubUrl, MemoryRealm.project(2));
    }

    @Test
    void memoriesTravelFromAToB() {
        a.addEntry("project", "交易结构", "老股转让 + 增资");
        a.addEntry("conversation", "待办", "核对股东名册");

        Map<String, Object> r1 = a.syncNow();
        assertEquals(true, r1.get("synced"));
        assertEquals(true, r1.get("committed"));
        assertEquals(false, r1.get("pendingUpload"));

        Map<String, Object> r2 = b.syncNow();
        assertEquals(true, r2.get("synced"));

        assertEquals(2, b.db.size());
        MemoryEntry got = b.byKey("交易结构");
        assertNotNull(got);
        assertEquals("老股转让 + 增资", got.getMemoryValue());
        assertEquals(2L, got.getProjectId()); // 归属换成 B 本机的项目 id
        MemoryEntry src = a.byKey("交易结构");
        assertEquals(src.getUid(), got.getUid()); // 跨机器只认 uid
    }

    @Test
    void uidBackfillIsIdempotentAndRepeatSyncIsQuiet() {
        a.addEntry("project", "结论", "尽调无重大障碍");
        a.syncNow();
        String uid = a.byKey("结论").getUid();
        assertNotNull(uid);

        Map<String, Object> again = a.syncNow();
        assertEquals(uid, a.byKey("结论").getUid()); // 回填幂等：第二轮不改 uid
        assertEquals(false, again.get("committed")); // 内容没变不产生新提交（防乒乓）
    }

    @Test
    void reimportDoesNotPingPong() {
        a.addEntry("project", "事实", "标的公司注册资本 1000 万");
        a.syncNow();
        b.syncNow(); // B 回灌（JPA 语义会把 B 行的 updatedAt 前推）
        assertEquals(false, b.syncNow().get("committed")); // B 不应因时间戳漂移再造提交
        assertEquals(false, a.syncNow().get("committed")); // A 也不应被 B 的回灌反向打扰
    }

    @Test
    void offlineRemoteTurnsIntoPendingUploadInsteadOfError() {
        MemorySyncTestMachine c = new MemorySyncTestMachine(
                tmp.resolve("machine-c"), tmp.resolve("nowhere.git").toUri().toString(),
                MemoryRealm.user(9));
        c.addEntry("user", "偏好", "书面语，禁用感叹号");
        Map<String, Object> r = c.syncNow();
        assertEquals(true, r.get("synced"));
        assertEquals(true, r.get("offline"));
        assertEquals(true, r.get("committed")); // 本地照常留痕
        assertEquals(true, r.get("pendingUpload"));
    }

    @Test
    void userRealmRowsCarryOwnerUserId() {
        MemorySyncTestMachine u1 = new MemorySyncTestMachine(
                tmp.resolve("machine-u1"), hubUrl, MemoryRealm.user(5));
        MemorySyncTestMachine u2 = new MemorySyncTestMachine(
                tmp.resolve("machine-u2"), hubUrl, MemoryRealm.user(8));
        u1.addEntry("user", "写作偏好", "先结论后论证");
        u1.syncNow();
        u2.syncNow();
        MemoryEntry got = u2.byKey("写作偏好");
        assertNotNull(got);
        assertEquals(8L, got.getUserId()); // 归属换成本机 user 领域的 ownerId
        assertNull(got.getProjectId());
    }
}
