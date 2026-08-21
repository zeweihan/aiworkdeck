package com.checkba.service.evidence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 锚点归一化与 sha256 的对拍向量：fixtures/anchor-hash-vectors.json 与
 * frontend/tests/evidence/anchor-hash-vectors.json 是同一份字节，前端 utils/anchorHash.js
 * 必须对同一向量产出同一 norm 与 hash。改算法 = 改两端 + 改向量。
 */
class AnchorHashTest {

    private JsonNode vectors() throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/fixtures/anchor-hash-vectors.json")) {
            return new ObjectMapper().readTree(in);
        }
    }

    @Test
    void normalizeMatchesVectors() throws Exception {
        for (JsonNode v : vectors()) {
            assertEquals(v.get("norm").asText(), AnchorHash.normalize(v.get("in").asText()), v.get("in").asText());
        }
    }

    @Test
    void hashMatchesVectors() throws Exception {
        for (JsonNode v : vectors()) {
            assertTrue(v.hasNonNull("hash"), "向量缺 hash 字段: " + v.get("in").asText());
            assertEquals(v.get("hash").asText(), AnchorHash.of(v.get("in").asText()), v.get("in").asText());
        }
    }

    @Test
    void hashIs64Hex() {
        assertTrue(AnchorHash.of("abc").matches("[0-9a-f]{64}"));
        assertEquals(AnchorHash.of("a b"), AnchorHash.of("ab"));
        assertNotEquals(AnchorHash.of("ab"), AnchorHash.of("ac"));
    }

    @Test
    void nullIsEmpty() {
        assertEquals("", AnchorHash.normalize(null));
        assertEquals(AnchorHash.of(""), AnchorHash.of(null));
    }
}
