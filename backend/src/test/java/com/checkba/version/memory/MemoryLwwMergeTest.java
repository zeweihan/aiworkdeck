package com.checkba.version.memory;

import com.checkba.model.entity.MemoryEntry;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * LWW 合并矩阵（spec Phase A 验证标准第 1 条）：真分叉（两边都有本地提交）下的
 * 逐文件裁决——单边改取该边；双边改同 uid 按 updatedAt 后者胜；墓碑对编辑无条件胜
 * （防陈旧端复活）。全程无 MERGING 停留窗口、无任何人工介入。
 *
 * 制造真分叉的手法：B 先只 exportRealm（本地提交、不 fetch），让 A 的推送与 B 的
 * 本地提交在 git 层面各自领先，随后 B.syncNow 走 lwwMerge 的双亲合并路径。
 */
class MemoryLwwMergeTest {

    @TempDir
    Path tmp;

    private MemorySyncTestMachine a;
    private MemorySyncTestMachine b;

    @BeforeEach
    void setUp() throws Exception {
        Path hub = tmp.resolve("hub.git");
        Git.init().setBare(true).setDirectory(hub.toFile()).call().close();
        String hubUrl = hub.toUri().toString();
        a = new MemorySyncTestMachine(tmp.resolve("machine-a"), hubUrl, MemoryRealm.project(1));
        b = new MemorySyncTestMachine(tmp.resolve("machine-b"), hubUrl, MemoryRealm.project(2));
    }

    /** 建一条两边都同步过的基线记忆，返回 uid。 */
    private String seedShared(String key, String value) {
        a.addEntry("project", key, value);
        a.syncNow();
        b.syncNow();
        return a.byKey(key).getUid();
    }

    private void edit(MemorySyncTestMachine m, String uid, String newValue, LocalDateTime at) {
        MemoryEntry e = m.byUid(uid);
        e.setMemoryValue(newValue);
        e.setUpdatedAt(at);
    }

    @Test
    void bothEditedSameMemoryLaterUpdateWins() {
        String uid = seedShared("付款安排", "一次性支付");
        LocalDateTime now = LocalDateTime.now();

        edit(a, uid, "A 的旧改法", now.minusHours(2));
        a.syncNow(); // A 推上去

        edit(b, uid, "B 的新改法", now.minusHours(1)); // B 更晚
        b.sync.exportRealm(b.realm); // B 本地提交，与 A 真分叉
        Map<String, Object> r = b.syncNow(); // fetch → LWW 合并 → 重推
        assertEquals(true, r.get("synced"));

        a.syncNow(); // A 取回合并结果
        assertEquals("B 的新改法", a.byUid(uid).getMemoryValue());
        assertEquals("B 的新改法", b.byUid(uid).getMemoryValue());
    }

    @Test
    void bothEditedSameMemoryEarlierSideLoses() {
        String uid = seedShared("交割条件", "工商变更完成");
        LocalDateTime now = LocalDateTime.now();

        edit(a, uid, "A 的新改法", now.minusHours(1)); // A 更晚
        a.syncNow();

        edit(b, uid, "B 的旧改法", now.minusHours(3));
        b.sync.exportRealm(b.realm);
        b.syncNow();

        a.syncNow();
        assertEquals("A 的新改法", a.byUid(uid).getMemoryValue());
        assertEquals("A 的新改法", b.byUid(uid).getMemoryValue());
    }

    @Test
    void tombstoneBeatsEvenNewerEdit() {
        String uid = seedShared("过时结论", "对赌条款有效");

        // A 删除（导出为墓碑并推送）
        MemoryEntry rowA = a.byUid(uid);
        a.db.remove(rowA.getId());
        a.syncNow();

        // B 在更晚的时刻编辑同一条 → 真分叉 → 墓碑必须胜
        edit(b, uid, "B 挽救性的编辑", LocalDateTime.now().plusHours(1));
        b.sync.exportRealm(b.realm);
        b.syncNow();

        assertNull(b.byUid(uid), "墓碑对编辑无条件胜，B 的行应被删除");
        a.syncNow();
        assertNull(a.byUid(uid));

        // 防复活：两边再各同步一轮，谁都不该把它带回来，也不该产生新提交
        assertEquals(false, a.syncNow().get("committed"));
        assertEquals(false, b.syncNow().get("committed"));
        assertNull(a.byUid(uid));
        assertNull(b.byUid(uid));
    }

    @Test
    void deletionPropagatesWithoutResurrection() throws Exception {
        String uid = seedShared("临时事实", "股东会定于下周");

        MemoryEntry rowA = a.byUid(uid);
        a.db.remove(rowA.getId());
        a.syncNow(); // 导出为墓碑（文件仍在，tombstone: true）

        // 墓碑文件确实保留在仓库里（审计轨迹 + 防复活的依据）
        String rel = "project/" + uid + ".md";
        byte[] fileBytes = a.repo.readBlobAtCommit(a.realm.repoKey(), "master", rel);
        assertNotNull(fileBytes, "删除必须落成墓碑文件，不是删文件");
        assertTrue(MemoryFileCodec.decode(uid, fileBytes).tombstone());

        b.syncNow();
        assertNull(b.byUid(uid), "删除应传播到 B");
        // B 陈旧端反复同步不复活
        assertEquals(false, b.syncNow().get("committed"));
        assertNull(b.byUid(uid));
    }

    @Test
    void independentEditsOnDifferentMemoriesBothSurvive() {
        String uidX = seedShared("条款 X", "原文 X");
        String uidY = seedShared("条款 Y", "原文 Y");
        LocalDateTime now = LocalDateTime.now();

        edit(a, uidX, "A 改了 X", now);
        a.syncNow();

        edit(b, uidY, "B 改了 Y", now);
        b.sync.exportRealm(b.realm);
        b.syncNow();

        a.syncNow();
        for (MemorySyncTestMachine m : new MemorySyncTestMachine[]{a, b}) {
            assertEquals("A 改了 X", m.byUid(uidX).getMemoryValue());
            assertEquals("B 改了 Y", m.byUid(uidY).getMemoryValue());
        }
    }

    @Test
    void mergeNeverLeavesRepositoryMidMerge() throws Exception {
        String uid = seedShared("并发条款", "v0");
        LocalDateTime now = LocalDateTime.now();
        edit(a, uid, "A v1", now.minusMinutes(10));
        a.syncNow();
        edit(b, uid, "B v1", now.minusMinutes(5));
        b.sync.exportRealm(b.realm);
        b.syncNow();
        // 合并全自动完成：仓库目录里不允许残留 MERGE_HEAD（无裁决窗口是硬承诺）
        Path mergeHead = b.repo.gitDir(b.realm.repoKey()).resolve("MERGE_HEAD");
        assertFalse(Files.exists(mergeHead));
    }
}
