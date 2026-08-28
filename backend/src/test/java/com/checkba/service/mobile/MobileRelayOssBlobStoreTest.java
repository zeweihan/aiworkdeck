package com.checkba.service.mobile;

import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSException;
import com.aliyun.oss.model.OSSObject;
import com.aliyun.oss.model.ObjectMetadata;
import com.aliyun.oss.model.PutObjectResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * OSS 实现的契约测试（mock OSS，模式同 MeetingTranscriptionServiceTest）：
 * key 组装、真实字节计数、legacy 本地路径双读、删除失败不抛。
 */
class MobileRelayOssBlobStoreTest {

    private final OSS oss = mock(OSS.class);
    private final MobileRelayOssBlobStore store =
            new MobileRelayOssBlobStore(oss, "awd-mobile-relay", "mobile-relay/");

    @Test
    void putComposesKeyAndCountsRealBytes() {
        when(oss.putObject(anyString(), anyString(), any(InputStream.class), any(ObjectMetadata.class)))
                .thenAnswer(inv -> {
                    // 模拟 SDK 消费整个流
                    inv.getArgument(2, InputStream.class).readAllBytes();
                    return new PutObjectResult();
                });

        byte[] payload = "JPEG-BYTES".getBytes(StandardCharsets.UTF_8);
        MobileRelayBlobStore.StoredBlob stored =
                store.put(42L, "abc-def", new ByteArrayInputStream(payload), payload.length);

        assertEquals("mobile-relay/42/abc-def", stored.locator());
        assertEquals(payload.length, stored.size(), "size 必须是真实读到的字节数");

        ArgumentCaptor<ObjectMetadata> meta = ArgumentCaptor.forClass(ObjectMetadata.class);
        verify(oss).putObject(eq("awd-mobile-relay"), eq("mobile-relay/42/abc-def"),
                any(InputStream.class), meta.capture());
        assertEquals(payload.length, meta.getValue().getContentLength(),
                "必须带 Content-Length，否则 SDK 全量缓冲进堆");
    }

    @Test
    void openAndExistsRouteKeysToOss() throws Exception {
        OSSObject obj = new OSSObject();
        obj.setObjectContent(new ByteArrayInputStream("x".getBytes(StandardCharsets.UTF_8)));
        when(oss.getObject("awd-mobile-relay", "mobile-relay/1/k")).thenReturn(obj);
        when(oss.doesObjectExist("awd-mobile-relay", "mobile-relay/1/k")).thenReturn(true);

        assertTrue(store.exists("mobile-relay/1/k"));
        assertEquals("x", new String(store.open("mobile-relay/1/k").readAllBytes(), StandardCharsets.UTF_8));
    }

    @Test
    void legacyAbsolutePathsBypassOss(@TempDir Path dir) throws Exception {
        Path legacy = dir.resolve("blob");
        Files.writeString(legacy, "legacy-bytes");

        assertTrue(store.exists(legacy.toString()));
        assertEquals("legacy-bytes",
                new String(store.open(legacy.toString()).readAllBytes(), StandardCharsets.UTF_8));

        store.deleteQuietly(legacy.toString());
        assertFalse(Files.exists(legacy), "legacy 路径的删除仍走文件系统");
        verifyNoInteractions(oss);
    }

    @Test
    void deleteQuietlyNeverThrows() {
        doThrow(new OSSException("boom")).when(oss).deleteObject(anyString(), anyString());
        assertDoesNotThrow(() -> store.deleteQuietly("mobile-relay/1/k"),
                "删不掉不该挡住 ACK/TTL 的行级操作");
    }
}
