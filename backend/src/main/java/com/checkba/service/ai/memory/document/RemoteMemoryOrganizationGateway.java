// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.service.ai.memory.document;

import com.checkba.model.entity.AccountBinding;
import com.checkba.repository.AccountBindingRepository;
import com.checkba.service.account.AccountService;
import com.checkba.service.site.SiteProfileService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;

@Component
public class RemoteMemoryOrganizationGateway implements MemoryOrganizationGateway {
    private final AccountService accountService;
    private final SiteProfileService siteProfileService;
    private final AccountBindingRepository bindings;
    private final MemoryHttpTransport transport;
    private final ObjectMapper mapper;
    private final boolean localMode;
    private final String sharedBaseUrl;
    private final String sharedSecret;

    public RemoteMemoryOrganizationGateway(AccountService accountService,
                                           SiteProfileService siteProfileService,
                                           AccountBindingRepository bindings,
                                           MemoryHttpTransport transport,
                                           ObjectMapper mapper,
                                           @Value("${security.local-mode:false}") boolean localMode,
                                           @Value("${memory.shared.base-url:}") String sharedBaseUrl,
                                           @Value("${memory.shared.secret:}") String sharedSecret) {
        this.accountService = accountService;
        this.siteProfileService = siteProfileService;
        this.bindings = bindings;
        this.transport = transport;
        this.mapper = mapper;
        this.localMode = localMode;
        this.sharedBaseUrl = trim(sharedBaseUrl);
        this.sharedSecret = sharedSecret == null ? "" : sharedSecret.trim();
    }

    @Override
    public List<MemorySpaceView> listSpaces(Long userId) {
        Identity identity = identity(userId);
        if (identity == null) return unavailableSpaces(unavailableReason());
        try {
            Object data = request(identity, "GET", "/spaces", null, null);
            List<Map<String, Object>> rows = mapper.convertValue(data, new TypeReference<>() {});
            List<MemorySpaceView> result = new ArrayList<>();
            for (Map<String, Object> row : rows) {
                String scope = text(row.get("scope"));
                if (!"team".equals(scope) && !"firm".equals(scope)) continue;
                result.add(new MemorySpaceView(text(row.get("id")), scope, text(row.get("label")),
                        bool(row.get("readable")), bool(row.get("writable")), bool(row.get("available")),
                        text(row.get("reason"))));
            }
            return result;
        } catch (RuntimeException e) {
            return unavailableSpaces("共享记忆服务暂时不可用");
        }
    }

    @Override
    public List<MemoryFileView> listFiles(Long userId, String spaceId) {
        Object data = request(requireIdentity(userId), "GET", "/files",
                "spaceId=" + enc(spaceId), null);
        List<Map<String, Object>> rows = mapper.convertValue(data, new TypeReference<>() {});
        return rows.stream().map(row -> file(row, false)).toList();
    }

    @Override
    public List<MemoryFileView> search(Long userId, String spaceId, String query, int limit) {
        Object data = request(requireIdentity(userId), "GET", "/search",
                "spaceId=" + enc(spaceId) + "&query=" + enc(query) + "&limit=" + limit, null);
        List<Map<String, Object>> rows = mapper.convertValue(data, new TypeReference<>() {});
        return rows.stream().map(row -> file(row, false)).toList();
    }

    @Override
    public MemoryFileView read(Long userId, String spaceId, String path) {
        Object data = request(requireIdentity(userId), "GET", "/file",
                "spaceId=" + enc(spaceId) + "&path=" + enc(path), null);
        return file(mapper.convertValue(data, new TypeReference<Map<String, Object>>() {}), true);
    }

    @Override
    public MemoryFileView write(Long userId, String spaceId, String path, String content, long expectedRevision) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("spaceId", spaceId);
        body.put("path", path);
        body.put("content", content);
        body.put("expectedRevision", expectedRevision);
        Object data = request(requireIdentity(userId), "PUT", "/file", null, json(body));
        return file(mapper.convertValue(data, new TypeReference<Map<String, Object>>() {}), true);
    }

    @Override
    public void delete(Long userId, String spaceId, String path, long expectedRevision) {
        request(requireIdentity(userId), "DELETE", "/file",
                "spaceId=" + enc(spaceId) + "&path=" + enc(path)
                        + "&expectedRevision=" + expectedRevision, null);
    }

    @Override
    public String download(Long userId, String spaceId, String path) {
        Identity identity = requireIdentity(userId);
        MemoryHttpTransport.Reply reply = transport.send("GET", identity.baseUrl() + "/download?spaceId="
                + enc(spaceId) + "&path=" + enc(path), identity.headers(), null);
        if (reply.status() < 200 || reply.status() >= 300) throw remoteError(reply);
        return reply.body();
    }

    private Object request(Identity identity, String method, String endpoint, String query, String body) {
        String url = identity.baseUrl() + endpoint + (query == null ? "" : "?" + query);
        MemoryHttpTransport.Reply reply = transport.send(method, url, identity.headers(), body);
        if (reply.status() < 200 || reply.status() >= 300) throw remoteError(reply);
        try {
            Map<String, Object> envelope = mapper.readValue(reply.body(), new TypeReference<>() {});
            Number code = envelope.get("code") instanceof Number n ? n : null;
            if (code == null || code.intValue() != 200) {
                throw new MemoryDocumentException(code == null ? 502 : code.intValue(),
                        text(envelope.get("message")) == null ? "共享记忆响应不正确" : text(envelope.get("message")));
            }
            return envelope.get("data");
        } catch (MemoryDocumentException e) {
            throw e;
        } catch (Exception e) {
            throw new MemoryDocumentException(502, "共享记忆响应无法解析");
        }
    }

    private Identity requireIdentity(Long userId) {
        Identity identity = identity(userId);
        if (identity == null) throw new MemoryDocumentException(403, unavailableReason());
        return identity;
    }

    private Identity identity(Long userId) {
        if (userId == null) return null;
        if (localMode) {
            String key = accountService.currentKeyOrNull();
            if (key == null || key.isBlank()) return null;
            return new Identity(trim(siteProfileService.baseUrl()) + "/api/account/memory",
                    Map.of("Authorization", "Bearer " + key));
        }
        if (sharedBaseUrl.isBlank() || sharedSecret.isBlank()) return null;
        Optional<AccountBinding> binding = bindings.findByUserId(userId);
        if (binding.isEmpty() || binding.get().getExternalAccountId() == null
                || binding.get().getExternalAccountId().isBlank()) return null;
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("X-Internal-Secret", sharedSecret);
        headers.put("X-Requester-Account-Id", binding.get().getExternalAccountId());
        return new Identity(sharedBaseUrl + "/api/internal/memory", Map.copyOf(headers));
    }

    private String unavailableReason() {
        if (localMode) return "尚未连接 AI WorkDeck 账户";
        if (sharedBaseUrl.isBlank() || sharedSecret.isBlank()) return "当前服务器未配置共享记忆";
        return "当前登录身份尚未绑定官网账户";
    }

    private static List<MemorySpaceView> unavailableSpaces(String reason) {
        return List.of(MemorySpaceView.unavailable("team", "团队记忆", reason),
                MemorySpaceView.unavailable("firm", "律所记忆", reason));
    }

    private MemoryDocumentException remoteError(MemoryHttpTransport.Reply reply) {
        String message = null;
        try {
            Map<String, Object> body = mapper.readValue(reply.body(), new TypeReference<>() {});
            message = text(body.get("message"));
        } catch (Exception ignored) {}
        int status = reply.status() <= 0 ? 503 : reply.status();
        return new MemoryDocumentException(status, message == null ? "共享记忆请求失败" : message);
    }

    private static MemoryFileView file(Map<String, Object> row, boolean content) {
        return new MemoryFileView(text(row.get("path")), text(row.get("title")),
                content ? text(row.get("content")) : null, lng(row.get("revision")),
                date(row.get("updatedAt")), bool(row.get("writable")));
    }

    private String json(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (Exception e) { throw new MemoryDocumentException(400, "记忆请求无法序列化"); }
    }

    private static LocalDateTime date(Object raw) {
        if (raw == null) return null;
        String value = String.valueOf(raw);
        try { return LocalDateTime.parse(value); } catch (Exception ignored) {}
        try { return OffsetDateTime.parse(value).toLocalDateTime(); } catch (Exception ignored) {}
        try { return LocalDateTime.ofInstant(Instant.parse(value), ZoneId.systemDefault()); }
        catch (Exception ignored) { return null; }
    }

    private static String enc(String value) { return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8); }
    private static String text(Object value) { return value == null ? null : String.valueOf(value); }
    private static boolean bool(Object value) { return Boolean.TRUE.equals(value); }
    private static long lng(Object value) { return value instanceof Number n ? n.longValue() : Long.parseLong(String.valueOf(value)); }
    private static String trim(String value) {
        if (value == null) return "";
        String v = value.trim();
        return v.endsWith("/") ? v.substring(0, v.length() - 1) : v;
    }

    private record Identity(String baseUrl, Map<String, String> headers) {}
}
