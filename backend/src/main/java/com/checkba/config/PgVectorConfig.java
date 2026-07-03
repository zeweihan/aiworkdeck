package com.checkba.config;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import dev.langchain4j.store.embedding.pgvector.PgVectorEmbeddingStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

/**
 * PgVector 配置类
 * 配置向量存储用于记忆语义检索
 * 如果 PgVector 不可用，回退到内存存储
 *
 * 向量维度从 EmbeddingModel 动态读取（如 nomic-embed-text=768、OpenAI=1536），
 * 读取失败时回退到默认值。
 */
@Configuration
public class PgVectorConfig {

    private static final Logger log = LoggerFactory.getLogger(PgVectorConfig.class);

    /** EmbeddingModel 维度探测失败时的回退维度（历史默认值） */
    static final int FALLBACK_DIMENSION = 1536;

    /** 维度探测结果缓存（探测可能触发一次真实 embed 调用，避免每个 store bean 各探测一次） */
    private volatile Integer cachedDimension;

    private int dimensionFor(EmbeddingModel embeddingModel) {
        if (cachedDimension == null) {
            cachedDimension = resolveDimension(embeddingModel);
        }
        return cachedDimension;
    }

    @Value("${spring.datasource.url}")
    private String jdbcUrl;

    @Value("${spring.datasource.username}")
    private String username;

    @Value("${spring.datasource.password}")
    private String password;

    /**
     * 记忆向量存储
     * 用于存储和检索记忆条目的向量表示
     * 如果 PgVector 不可用，回退到内存存储
     */
    @Bean(name = "memoryEmbeddingStore")
    public EmbeddingStore<TextSegment> memoryEmbeddingStore(EmbeddingModel embeddingModel) {
        // 从 JDBC URL 提取主机和数据库信息
        // jdbc:postgresql://localhost:5432/checkba
        String host = "localhost";
        int port = 5432;
        String database = "checkba";

        try {
            String urlWithoutPrefix = jdbcUrl.replace("jdbc:postgresql://", "");
            String[] parts = urlWithoutPrefix.split("/");
            String[] hostPort = parts[0].split(":");
            host = hostPort[0];
            if (hostPort.length > 1) {
                port = Integer.parseInt(hostPort[1]);
            }
            if (parts.length > 1) {
                database = parts[1].split("\\?")[0];
            }
        } catch (Exception e) {
            // 使用默认值
        }

        try {
            return PgVectorEmbeddingStore.builder()
                    .host(host)
                    .port(port)
                    .database(database)
                    .user(username)
                    .password(password)
                    .table("memory_embedding_store")  // 自动创建的表
                    .dimension(dimensionFor(embeddingModel))
                    .createTable(true)
                    .build();
        } catch (Exception e) {
            log.warn("PgVector 不可用，回退到内存存储。错误: {}", e.getMessage());
            return new InMemoryEmbeddingStore<>();
        }
    }

    /**
     * 从 EmbeddingModel 动态读取向量维度（替换原硬编码 1536）。
     * 读取可能触发一次真实 embed 调用；模型不可用时回退到默认维度，保持原有启动行为。
     */
    static int resolveDimension(EmbeddingModel embeddingModel) {
        try {
            int dimension = embeddingModel.dimension();
            log.info("Embedding dimension resolved from model: {}", dimension);
            return dimension;
        } catch (Exception e) {
            log.warn("Failed to resolve embedding dimension from model, falling back to {}. 错误: {}",
                    FALLBACK_DIMENSION, e.getMessage());
            return FALLBACK_DIMENSION;
        }
    }

    /**
     * 项目文档向量存储
     * 用于 RAG 检索项目文档
     * 如果 PgVector 不可用，回退到内存存储
     */
    @Bean(name = "projectDocEmbeddingStore")
    public EmbeddingStore<TextSegment> projectDocEmbeddingStore(EmbeddingModel embeddingModel) {
        String host = "localhost";
        int port = 5432;
        String database = "checkba";
        
        try {
            String urlWithoutPrefix = jdbcUrl.replace("jdbc:postgresql://", "");
            String[] parts = urlWithoutPrefix.split("/");
            String[] hostPort = parts[0].split(":");
            host = hostPort[0];
            if (hostPort.length > 1) {
                port = Integer.parseInt(hostPort[1]);
            }
            if (parts.length > 1) {
                database = parts[1].split("\\?")[0];
            }
        } catch (Exception e) {
            // 使用默认值
        }

        try {
            return PgVectorEmbeddingStore.builder()
                    .host(host)
                    .port(port)
                    .database(database)
                    .user(username)
                    .password(password)
                    .table("project_doc_embedding")
                    .dimension(dimensionFor(embeddingModel))
                    .createTable(true)
                    .build();
        } catch (Exception e) {
            log.warn("PgVector 不可用（projectDocEmbeddingStore），回退到内存存储。错误: {}", e.getMessage());
            return new InMemoryEmbeddingStore<>();
        }
    }
}

