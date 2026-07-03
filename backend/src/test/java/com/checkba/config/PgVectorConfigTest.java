package com.checkba.config;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * PgVectorConfig 测试：向量维度从 EmbeddingModel 动态读取
 */
class PgVectorConfigTest {

    /** 固定维度的测试用 EmbeddingModel */
    private static EmbeddingModel modelWithDimension(int dim) {
        return new EmbeddingModel() {
            @Override
            public Response<Embedding> embed(String text) {
                return Response.from(Embedding.from(new float[dim]));
            }

            @Override
            public Response<Embedding> embed(TextSegment textSegment) {
                return embed(textSegment.text());
            }

            @Override
            public Response<List<Embedding>> embedAll(List<TextSegment> textSegments) {
                throw new UnsupportedOperationException();
            }
        };
    }

    @Test
    @DisplayName("维度从 EmbeddingModel 动态读取（如 nomic-embed-text 为 768）")
    void shouldResolveDimensionFromModel() {
        assertEquals(768, PgVectorConfig.resolveDimension(modelWithDimension(768)));
        assertEquals(1536, PgVectorConfig.resolveDimension(modelWithDimension(1536)));
    }

    @Test
    @DisplayName("模型不可用时回退到默认维度")
    void shouldFallBackWhenModelUnavailable() {
        EmbeddingModel broken = new EmbeddingModel() {
            @Override
            public Response<Embedding> embed(String text) {
                throw new RuntimeException("embedding service down");
            }

            @Override
            public Response<Embedding> embed(TextSegment textSegment) {
                return embed(textSegment.text());
            }

            @Override
            public Response<List<Embedding>> embedAll(List<TextSegment> textSegments) {
                throw new UnsupportedOperationException();
            }
        };

        assertEquals(PgVectorConfig.FALLBACK_DIMENSION, PgVectorConfig.resolveDimension(broken));
    }
}
