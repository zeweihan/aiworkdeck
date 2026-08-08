package com.checkba.service.ai;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 会话级内联正文缓存（Office 插件的「正文省传」）。
 *
 * 背景：插件里的文档在用户本机，后端没有可读的 fileId，正文只能随每条 /chat 请求内联上送。
 * 一篇合同上限 20 万字符，同一场对话里聊十轮就重传十遍——云后端场景下这段上行是
 * 「插件响应慢」的可测量组成部分。因此客户端在正文没变时改为只上送内容哈希，
 * 后端凭哈希从本缓存取回上一次的正文。
 *
 * 契约要点：
 * - 哈希一律由**后端**对收到的正文自算（SHA-256），客户端上送的哈希只当作「省传」信号，
 *   不作为缓存键的可信来源——否则客户端可以拿任意哈希取走别人的正文。
 * - 未命中不报错：调用方按「本轮没有内联正文」处理（提示词里已有对应文案，
 *   模型可改用 office_get_text 等读取类工具）。
 * - 进程内存态、不落库、不做主动清理；生命周期与 ClientCapabilityService 同款（按会话）。
 *
 * 内存上界：单条正文 ≤ 200k 字符（ContextAssemblerService 的 MAX_INLINE_CONTENT_CHARS，
 * 超限的不入缓存），条目上限 32，即 32 × 200k char ≈ 13MB（Java char 双字节）。
 * 超出后按访问顺序 LRU 驱逐最久未用的会话。
 */
@Service
@Slf4j
public class InlineContentCache {

    /** 条目上限：32 × 200k 字符 ≈ 13MB */
    static final int MAX_ENTRIES = 32;

    private record CachedBody(String hash, String content) { }

    /** accessOrder=true 的 LinkedHashMap 即 LRU；并发访问全部走 synchronized 包装 */
    private final Map<String, CachedBody> byConversation = Collections.synchronizedMap(
            new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, CachedBody> eldest) {
                    return size() > MAX_ENTRIES;
                }
            });

    /**
     * 记下本会话这一轮实际使用的内联正文。哈希由本方法自算，不接受外部传入。
     */
    public void put(String conversationId, String content) {
        if (conversationId == null || conversationId.isBlank() || content == null || content.isEmpty()) {
            return;
        }
        byConversation.put(conversationId, new CachedBody(sha256Hex(content), content));
    }

    /**
     * 按客户端上送的哈希取回本会话上一次的正文。
     * 会话没有缓存、或哈希与缓存内容不一致（文档已被改动）时返回 null——
     * 调用方按「没有内联正文」处理，不报错。
     */
    public String get(String conversationId, String hash) {
        if (conversationId == null || conversationId.isBlank() || hash == null || hash.isBlank()) {
            return null;
        }
        CachedBody entry = byConversation.get(conversationId);
        if (entry == null) {
            return null;
        }
        return entry.hash().equalsIgnoreCase(hash.trim()) ? entry.content() : null;
    }

    /** SHA-256 十六进制小写，口径与插件端 crypto.subtle.digest('SHA-256') 一致。 */
    static String sha256Hex(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 是 JDK 必备算法，走不到这里
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
