package com.checkba.version.memory;

import com.checkba.version.memory.MemoryFileCodec.MemoryFileData;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class MemoryFileCodecTest {

    private MemoryFileData sample() {
        return new MemoryFileData(
                "3f2b9c1a-1111-2222-3333-444455556666",
                "project",
                "decision",
                "股权转让价款支付方式",
                "分三期支付，首期 30%。\n---\n正文里出现分隔线也不能破坏解析。",
                0.8,
                true,
                "conv-abc",
                "zhangsan",
                "file-uid-1",
                Map.of("amount", 3000000, "currency", "CNY"),
                1700000000000L,
                1700000600000L,
                false);
    }

    @Test
    void roundTripKeepsEveryField() {
        MemoryFileData d = sample();
        byte[] encoded = MemoryFileCodec.encode(d);
        MemoryFileData back = MemoryFileCodec.decode(d.uid(), encoded);
        assertNotNull(back);
        assertEquals(d.uid(), back.uid());
        assertEquals(d.scope(), back.scope());
        assertEquals(d.memoryType(), back.memoryType());
        assertEquals(d.memoryKey(), back.memoryKey());
        assertEquals(d.memoryValue(), back.memoryValue());
        assertEquals(d.importanceScore(), back.importanceScore());
        assertEquals(d.isProtected(), back.isProtected());
        assertEquals(d.conversationId(), back.conversationId());
        assertEquals(d.author(), back.author());
        assertEquals(d.sourceFileUid(), back.sourceFileUid());
        assertEquals(d.metadata(), back.metadata());
        assertEquals(d.createdAtMs(), back.createdAtMs());
        assertEquals(d.updatedAtMs(), back.updatedAtMs());
        assertFalse(back.tombstone());
        assertTrue(d.semanticallyEquals(back));
    }

    @Test
    void tombstoneSurvivesRoundTrip() {
        MemoryFileData d = new MemoryFileData("u-1", "user", "preference", null, "",
                null, null, null, null, null, null, null, 1700000000000L, true);
        MemoryFileData back = MemoryFileCodec.decode("u-1", MemoryFileCodec.encode(d));
        assertNotNull(back);
        assertTrue(back.tombstone());
    }

    @Test
    void encodingIsDeterministic() {
        assertArrayEquals(MemoryFileCodec.encode(sample()), MemoryFileCodec.encode(sample()));
    }

    @Test
    void semanticEqualityIgnoresTimestamps() {
        MemoryFileData a = sample();
        MemoryFileData b = new MemoryFileData(a.uid(), a.scope(), a.memoryType(), a.memoryKey(),
                a.memoryValue(), a.importanceScore(), a.isProtected(), a.conversationId(),
                a.author(), a.sourceFileUid(), a.metadata(), 1L, 2L, false);
        assertTrue(a.semanticallyEquals(b));
        MemoryFileData c = new MemoryFileData(a.uid(), a.scope(), a.memoryType(), a.memoryKey(),
                "改过的内容", a.importanceScore(), a.isProtected(), a.conversationId(),
                a.author(), a.sourceFileUid(), a.metadata(), a.createdAtMs(), a.updatedAtMs(), false);
        assertFalse(a.semanticallyEquals(c));
    }

    @Test
    void garbageContentDecodesToNull() {
        assertNull(MemoryFileCodec.decode("u", "不是记忆文件".getBytes(StandardCharsets.UTF_8)));
        assertNull(MemoryFileCodec.decode("u", "---\n没有闭合".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void uidValidationRejectsPathHostileNames() {
        assertTrue(MemoryFileCodec.isValidUid("3f2b9c1a-1111-2222-3333-444455556666"));
        assertFalse(MemoryFileCodec.isValidUid("../escape"));
        assertFalse(MemoryFileCodec.isValidUid(null));
        assertFalse(MemoryFileCodec.isValidUid(""));
    }
}
