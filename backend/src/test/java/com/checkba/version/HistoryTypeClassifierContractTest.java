// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.version;

import com.checkba.storage.ProjectStorageResolver;
import com.checkba.storage.StorageProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link HistoryTypeClassifier} 认的那几句话，必须就是生成侧真的写进提交消息的那几句。
 *
 * <p>分类器是只读的旁观者：它靠字符串认出「这一版是一次退回/一次采纳/一次取回」，
 * 而那些字符串的主人在别的类里。谁改了文案而没同步改分类器，界面上的类型标签
 * 就会**静默**退回成笼统的「结束工作」——没有编译错误、没有异常、没有日志。
 * 这条测试就是那个信号。
 *
 * <p>三种钉法，按能拿到什么选：
 * <ul>
 *   <li>能真跑的就真跑：初始版本直接建一个真仓库，读它落下的那笔提交消息；</li>
 *   <li>是私有静态方法的就反射调它：取回最新稿、采纳一稿；</li>
 *   <li>是方法体里的行内字面量、拿不到也不该为它改生成侧的（退回、升级清单），
 *       退一步核对源文件里确实有这两个字面量。格式无关（只查字面量本身），
 *       改词就会红。</li>
 * </ul>
 */
class HistoryTypeClassifierContractTest {

    /** 生成侧的源文件（surefire 的工作目录是 backend/）。 */
    private static final Path WORK_SESSION_SERVICE =
            Path.of("src/main/java/com/checkba/version/WorkSessionService.java");

    @Test
    @DisplayName("初始版本：真建一个仓库，它落下的那笔提交必须被认成 initial")
    void initialVersionFromTheRealRepositoryIsClassifiedAsInitial(@TempDir Path tmp) throws Exception {
        Files.createDirectories(tmp.resolve("projects/7"));
        Files.writeString(tmp.resolve("projects/7/合同.txt"), "初稿");
        StorageProperties props = new StorageProperties();
        props.getLocal().setRootPath(tmp.toAbsolutePath().toString());
        ProjectRepoService repo = new ProjectRepoService(new ProjectStorageResolver(props, null));
        repo.init(7L, "韩泽伟", "awd_x@collab.aiworkdeck.local");

        VersionEntry initial = repo.log(7L, "HEAD", 1).get(0);

        assertEquals(HistoryTypeClassifier.INITIAL,
                HistoryTypeClassifier.classify(initial.message(), initial.kind()),
                "ProjectRepoService.init 的提交消息变了就要同步改 HistoryTypeClassifier");
        assertEquals("session", initial.kind(), "初始版本身上的 kind 是 session——"
                + "所以分类必须先看消息、后看 kind，否则它会被归成「结束工作」");
    }

    @Test
    @DisplayName("取回最新稿：反射拿 CloudSyncService 真正用的那句标题")
    void cloudMergeTitleIsClassifiedAsPull() throws Exception {
        Method m = declared(CloudSyncService.class, "cloudMergeTitle");
        String title = (String) m.invoke(null);

        assertEquals(HistoryTypeClassifier.PULL,
                HistoryTypeClassifier.classify(title, "session"),
                "CloudSyncService.cloudMergeTitle() 现在返回「" + title
                        + "」，HistoryTypeClassifier 认不出来");
    }

    @Test
    @DisplayName("采纳一稿：反射拿 WorkSessionService 真正用的那句标题（带稿名）")
    void adoptMessageIsClassifiedAsAdopt() throws Exception {
        WorkSession draft = new WorkSession();
        draft.setTitle("试验稿");
        Method m = declared(WorkSessionService.class, "adoptMessage", WorkSession.class);
        String title = (String) m.invoke(null, draft);

        assertTrue(title.contains("试验稿"), "采纳节点的标题里应带稿名，实际: " + title);
        assertEquals(HistoryTypeClassifier.ADOPT,
                HistoryTypeClassifier.classify(title, "session"),
                "WorkSessionService.adoptMessage 现在返回「" + title
                        + "」，HistoryTypeClassifier 的前缀认不出来");
    }

    @Test
    @DisplayName("退回与升级清单：源文件里的那两对字面量必须还在，且两种语言都认得出来")
    void inlineMessagesInWorkSessionServiceStillMatch() throws Exception {
        String source = readWorkSessionServiceSource();

        assertContainsLiteral(source, "退回到早先的版本");
        assertContainsLiteral(source, "Reverted to an earlier version");
        assertContainsLiteral(source, "升级版本记录格式");
        assertContainsLiteral(source, "Upgraded version history format");

        // 同一个仓库里可能同时存在中英两种写法（提交时界面是什么语言就写什么）
        assertEquals(HistoryTypeClassifier.REVERT,
                HistoryTypeClassifier.classify("退回到早先的版本", "session"));
        assertEquals(HistoryTypeClassifier.REVERT,
                HistoryTypeClassifier.classify("Reverted to an earlier version", "session"));
        assertEquals(HistoryTypeClassifier.UPGRADE,
                HistoryTypeClassifier.classify("升级版本记录格式", "session"));
        assertEquals(HistoryTypeClassifier.UPGRADE,
                HistoryTypeClassifier.classify("Upgraded version history format", "session"));
    }

    @Test
    @DisplayName("认不出来的消息按 kind 兜底：自动存档是 auto，其余是结束工作")
    void anythingElseFallsBackToKind() {
        assertEquals(HistoryTypeClassifier.AUTO,
                HistoryTypeClassifier.classify("修改了《股权转让协议》", "auto"));
        assertEquals(HistoryTypeClassifier.SESSION,
                HistoryTypeClassifier.classify("核对注册资本", "session"));
        // kind 尾注缺失的老提交（toEntry 会把它当 auto）
        assertEquals(HistoryTypeClassifier.AUTO, HistoryTypeClassifier.classify(null, "auto"));
    }

    // ---------- 小工具 ----------

    private static Method declared(Class<?> owner, String name, Class<?>... args) {
        try {
            Method m = owner.getDeclaredMethod(name, args);
            m.setAccessible(true);
            return m;
        } catch (NoSuchMethodException e) {
            throw new AssertionError(owner.getSimpleName() + "." + name
                    + " 不见了或换了签名。它是提交消息模板的生成点，"
                    + "HistoryTypeClassifier 靠它认出这一类版本——两边要一起改。", e);
        }
    }

    private static String readWorkSessionServiceSource() throws Exception {
        assertTrue(Files.exists(WORK_SESSION_SERVICE),
                "读不到 " + WORK_SESSION_SERVICE.toAbsolutePath()
                        + "（surefire 的工作目录应是 backend/）");
        return Files.readString(WORK_SESSION_SERVICE, StandardCharsets.UTF_8);
    }

    private static void assertContainsLiteral(String source, String literal) {
        assertTrue(source.contains("\"" + literal + "\""),
                "WorkSessionService 里已经没有字面量「" + literal
                        + "」了。改了提交消息就要同步改 HistoryTypeClassifier，"
                        + "否则历史上这一类版本的类型标签会静默退回成「结束工作」。");
    }
}
