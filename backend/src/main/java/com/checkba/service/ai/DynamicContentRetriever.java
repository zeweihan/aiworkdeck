package com.checkba.service.ai;

import com.checkba.service.ai.context.ProjectContextHolder;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;

/**
 * 按项目动态取 RAG 内容的 ContentRetriever。
 *
 * <p><b>不要因为「grep 不到调用方」就删掉它。</b>唯一的显式注入方（AiConfiguration 的
 * projectAssistant bean）已随 v1 对话链路一起移除，但这个 @Component **靠存在本身**
 * 满足了 langchain4j `RagAutoConfig` 的 {@code @ConditionalOnMissingBean(ContentRetriever.class)}：
 * 一旦容器里没有任何 ContentRetriever，自动配置就会自己造一个，而它要求容器里
 * 恰好只有一个 {@code EmbeddingStore<TextSegment>} —— 本项目有两个
 * （memoryEmbeddingStore 与 projectDocEmbeddingStore），于是全部 @SpringBootTest
 * 直接 NoUniqueBeanDefinitionException 起不来。2026-08 清理孤儿类时踩过一次。
 *
 * <p>护栏是 {@code DesktopContextSmokeTest}（它就是这么抓到的）。
 * 真要移除，得同时排除 RagAutoConfig，那是另一件事，别顺手做。
 */
@Component
@RequiredArgsConstructor
public class DynamicContentRetriever implements ContentRetriever {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(DynamicContentRetriever.class);

    private final ProjectRagService projectRagService;

    @Override
    public List<Content> retrieve(Query query) {
        String projectId = ProjectContextHolder.getProjectId();
        if (projectId != null) {
            log.debug("Retrieving content for project: {} query: {}", projectId, query.text());
            ContentRetriever retriever = projectRagService.getRetrieverForProject(projectId);
            return retriever.retrieve(query);
        }
        log.warn("No project context found, returning empty content");
        return Collections.emptyList();
    }
}

