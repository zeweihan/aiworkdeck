package com.checkba.service.ai;

import com.checkba.storage.StorageProperties;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentParser;
import dev.langchain4j.data.document.loader.FileSystemDocumentLoader;
import dev.langchain4j.data.document.parser.apache.tika.ApacheTikaDocumentParser;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.content.retriever.EmbeddingStoreContentRetriever;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * RAG Service responsible for building knowledge base from project files.
 * Supports universal file formats via Apache Tika.
 */
@Service
@RequiredArgsConstructor
public class ProjectRagService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ProjectRagService.class);

    private final StorageProperties storageProperties;
    private final EmbeddingModel embeddingModel;

    // 有界 LRU（access-order）：每个 retriever 持有整个项目的向量库，无界缓存会随项目数持续增长占堆。
    // 超过上限时淘汰最久未访问的项目（下次访问按需重建）。
    private static final int MAX_CACHED_PROJECTS = 32;
    private final Map<String, ContentRetriever> retrieverCache = java.util.Collections.synchronizedMap(
            new java.util.LinkedHashMap<String, ContentRetriever>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, ContentRetriever> eldest) {
                    return size() > MAX_CACHED_PROJECTS;
                }
            });

    public ContentRetriever getRetrieverForProject(String projectId) {
        return retrieverCache.computeIfAbsent(projectId, this::buildRetriever);
    }

    /**
     * 刷新项目知识库（全量刷新）
     */
    public void refreshProjectKnowledge(String projectId) {
        retrieverCache.remove(projectId);
        log.info("项目知识库缓存已清除，下次访问时将重新构建: projectId={}", projectId);
    }

    /**
     * 增量刷新项目知识库（仅刷新指定文件）
     * 注意：由于使用InMemoryEmbeddingStore，增量刷新需要重新构建整个知识库
     * 但可以通过只扫描变化的文件来优化性能
     */
    public void refreshProjectKnowledgeIncremental(String projectId, String filePath) {
        // 简化实现：清除缓存，让下次访问时重新构建
        // 由于InMemoryEmbeddingStore的限制，真正的增量更新需要更复杂的实现
        // 这里先使用全量刷新，但记录日志以便后续优化
        log.info("增量刷新项目知识库: projectId={}, filePath={}", projectId, filePath);
        retrieverCache.remove(projectId);
    }

    private ContentRetriever buildRetriever(String projectId) {
        log.info("Building knowledge base for project: {}", projectId);
        EmbeddingStore<TextSegment> embeddingStore = new InMemoryEmbeddingStore<>();

        Path storageRoot = getStorageRoot();
        if (storageRoot == null || !Files.exists(storageRoot)) {
            log.warn("Storage root does not exist: {}", storageRoot);
            return createEmptyRetriever(embeddingStore);
        }

        DocumentParser parser = new ApacheTikaDocumentParser();
        List<Document> allDocuments = new ArrayList<>();

        // Strategy 1: Scan new directory structure: data/projects/{projectId}
        Path projectDir = storageRoot.resolve("projects").resolve(projectId);
        if (Files.exists(projectDir)) {
            log.info("Scanning project directory: {}", projectDir);
            allDocuments.addAll(scanDirectory(projectDir, parser, null));
        }

        // Strategy 2: Scan legacy directory (root) for files starting with project_{id}_
        // Disabled for performance and safety (prevent scanning entire root)
        // log.info("Scanning root directory for legacy files: {}", storageRoot);
        // allDocuments.addAll(scanDirectory(storageRoot, parser, "project_" + projectId + "_"));

        if (!allDocuments.isEmpty()) {
            // 使用更“温和”的方式逐段向量化，避免一次性向 Ollama 发送过大的 embedding 请求
            var splitter = DocumentSplitters.recursive(800, 200);
            int docCount = 0;
            int segmentCount = 0;

            for (Document document : allDocuments) {
                docCount++;
                List<TextSegment> segments = splitter.split(document);
                for (TextSegment segment : segments) {
                    try {
                        Embedding embedding = embeddingModel.embed(segment).content();
                        embeddingStore.add(embedding, segment);
                        segmentCount++;
                    } catch (Exception e) {
                        log.warn("Failed to embed segment for project {}: {}", projectId, e.getMessage());
                    }
                }
            }

            log.info("Ingested {} documents ({} segments) for project {}", docCount, segmentCount, projectId);
        } else {
            log.info("No documents found for project {}", projectId);
        }

        return EmbeddingStoreContentRetriever.builder()
                .embeddingStore(embeddingStore)
                .embeddingModel(embeddingModel)
                .maxResults(5)
                .minScore(0.5)
                .build();
    }

    private List<Document> scanDirectory(Path dir, DocumentParser parser, String filenamePrefix) {
        try (Stream<Path> paths = Files.walk(dir)) {
            return paths
                .filter(Files::isRegularFile)
                .filter(path -> {
                    // Avoid scanning hidden files or temporary files
                    String name = path.getFileName().toString();
                    if (name.startsWith(".")) return false;
                    
                    // If prefix is specified, filter by it
                    if (filenamePrefix != null) {
                        return name.startsWith(filenamePrefix);
                    }
                    return true;
                })
                .map(path -> {
                    try {
                        log.debug("Loading document: {}", path);
                        return FileSystemDocumentLoader.loadDocument(path, parser);
                    } catch (Exception e) {
                        log.warn("Failed to load document: {}", path, e);
                        return null;
                    }
                })
                .filter(doc -> doc != null)
                .collect(Collectors.toList());
        } catch (IOException e) {
            log.error("Error scanning directory: " + dir, e);
            return new ArrayList<>();
        }
    }

    private ContentRetriever createEmptyRetriever(EmbeddingStore<TextSegment> store) {
        return EmbeddingStoreContentRetriever.builder()
                .embeddingStore(store)
                .embeddingModel(embeddingModel)
                .build();
    }

    private Path getStorageRoot() {
        String rootPath = storageProperties.getLocal().getRootPath();
        if (rootPath == null) rootPath = "data/wps-files";
        
        if (Paths.get(rootPath).isAbsolute()) {
            return Paths.get(rootPath);
        } else {
            String userDir = System.getProperty("user.dir");
            Path projectRoot = Paths.get(userDir);
            if (userDir.endsWith("backend")) {
                projectRoot = projectRoot.getParent();
            }
            return projectRoot.resolve(rootPath);
        }
    }
}
