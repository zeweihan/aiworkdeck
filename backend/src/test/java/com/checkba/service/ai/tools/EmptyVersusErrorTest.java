package com.checkba.service.ai.tools;

import com.checkba.service.ai.ToolRegistry;
import com.checkba.service.ai.context.ProjectContextHolder;
import com.checkba.service.ai.evidence.EvidenceItem;
import com.checkba.service.ai.evidence.EvidenceQuery;
import com.checkba.service.ai.evidence.EvidenceRetriever;
import com.checkba.service.ai.evidence.EvidenceRetrieverRegistry;
import com.checkba.storage.ProjectStorageResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

/**
 * 「查不到」与「没查成」必须分得开。
 *
 * <p>这是本轮审计里最反复出现的一族病：系统故障被写成一个合法的空结果交给模型，
 * 全程没有任何错误信号。对法律产品来说尤其危险——模型会据此在文书里断言「无相应依据」。
 */
class EmptyVersusErrorTest {

    @AfterEach
    void clear() {
        ProjectContextHolder.clear();
    }

    private static boolean classifiedAsSuccess(String out) {
        return new ToolRegistry.ToolResult(out, null, true).success();
    }

    // ==================== retrieve_evidence ====================

    private static EvidenceRetriever retriever(String id, boolean explode) {
        return new EvidenceRetriever() {
            @Override
            public String sourceId() {
                return id;
            }

            @Override
            public List<EvidenceItem> retrieve(EvidenceQuery query) {
                if (explode) throw new IllegalStateException("upstream down");
                return List.of();
            }
        };
    }

    private static EvidenceTools evidenceToolsWith(EvidenceRetriever... retrievers) {
        EvidenceRetrieverRegistry registry = Mockito.mock(EvidenceRetrieverRegistry.class);
        when(registry.all()).thenReturn(List.of(retrievers));
        ProjectContextHolder.setProjectId("7");
        return new EvidenceTools(registry);
    }

    @Test
    @DisplayName("所有证据来源都失败：必须说「没查成」，绝不能说「查无此据」")
    void allSourcesFailingIsNotAnAbsenceOfEvidence() {
        String out = evidenceToolsWith(retriever("pkulaw", true), retriever("memory", true))
                .retrieve_evidence("违约金上限", 10);

        assertFalse(out.contains("未检索到相关证据"),
                "把系统故障说成查无此据，模型会在法律文书里断言「无相应依据」。实际是：" + out);
        assertTrue(out.contains("未能完成"), "要明说没查成，实际是：" + out);
        assertTrue(out.contains("不代表查无此据"), "要主动否掉「无据」这个结论，实际是：" + out);
        assertFalse(classifiedAsSuccess(out), "检索没跑成必须被判成工具失败");
    }

    @Test
    @DisplayName("部分来源失败但确实没结果：仍是「查无此据」，但要标明结果可能不完整")
    void partialFailureStillSaysNoEvidenceButFlagsIncompleteness() {
        String out = evidenceToolsWith(retriever("pkulaw", true), retriever("memory", false))
                .retrieve_evidence("违约金上限", 10);

        assertTrue(out.contains("未检索到相关证据"), "还有来源跑通了，仍属查无此据，实际是：" + out);
        assertTrue(out.contains("检索失败"), "要标明有来源失败、结果可能不完整，实际是：" + out);
        assertTrue(out.contains("pkulaw"), "要点名是哪个来源失败，实际是：" + out);
    }

    @Test
    @DisplayName("全部来源正常且确实没有证据：既有文案与既有判定一字不改")
    void genuineAbsenceKeepsTheExistingWording() {
        String out = evidenceToolsWith(retriever("memory", false)).retrieve_evidence("违约金上限", 10);

        assertTrue(out.contains("未检索到相关证据"), "实际是：" + out);
        assertTrue(out.contains("查无此据不等于结论矛盾"), "既有的契约提示不许丢，实际是：" + out);
        assertFalse(out.contains("检索失败"), "没有来源失败就别加噪声，实际是：" + out);
        assertTrue(classifiedAsSuccess(out), "真的查无此据是一次成功的检索");
    }

    // ==================== search_project_files ====================

    @Test
    @DisplayName("一个读不了的目录不许掀翻整次搜索——已经找到的匹配必须照常返回")
    void oneUnreadableDirectoryDoesNotDiscardEveryMatch(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("起诉状.docx"), "x", StandardCharsets.UTF_8);
        Path locked = Files.createDirectory(root.resolve("锁住的目录"));
        Files.writeString(locked.resolve("里面.docx"), "y", StandardCharsets.UTF_8);
        // root 用户读得动任何目录，这条断言对它没意义
        assumeTrue(!"root".equals(System.getProperty("user.name")));
        assumeTrue(locked.toFile().setReadable(false, false), "本文件系统不支持去掉读权限");

        try {
            ProjectStorageResolver resolver = Mockito.mock(ProjectStorageResolver.class);
            when(resolver.projectRoot(anyLong())).thenReturn(root);
            // 走完目录树后还会查库补 fileId，这里给一个空索引即可
            com.checkba.repository.ProjectFileRepository repo =
                    Mockito.mock(com.checkba.repository.ProjectFileRepository.class);
            when(repo.findByProjectIdAndIsDeletedFalseOrderBySortOrderAsc(anyLong())).thenReturn(List.of());
            FileTools tools = new FileTools(null, repo, null, null, resolver, null, null);
            ProjectContextHolder.setProjectId("7");

            String out = tools.search_project_files("*.docx", null);

            assertFalse(out.startsWith("Error searching files"),
                    "SimpleFileVisitor 默认会把 IOException 重新抛出，一个没权限的目录就能"
                            + "让整次搜索报错、已找到的匹配全丢。实际是：" + out);
            assertTrue(out.contains("起诉状.docx"), "可读的匹配必须照常返回，实际是：" + out);
        } finally {
            locked.toFile().setReadable(true, false);
        }
    }
}
