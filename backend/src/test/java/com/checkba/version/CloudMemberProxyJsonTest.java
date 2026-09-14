// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.version;

import com.checkba.model.entity.CloudConnection;
import com.checkba.model.entity.ProjectRemote;
import com.checkba.repository.CloudConnectionRepository;
import com.checkba.repository.ProjectFileRepository;
import com.checkba.repository.ProjectRemoteRepository;
import com.checkba.repository.ProjectRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 成员代理端点的返回值必须是纯 Java 结构（dev-board#444 回归）。
 *
 * hutool 把 JSON null 解析成 cn.hutool.json.JSONNull 单例，Jackson 没有它的序列化器：
 * 把上游的 JSONObject 原样当控制器返回值，只要某个字段是 null 就整条 500
 * （HttpMessageConversionException: No serializer found for class cn.hutool.json.JSONNull）。
 * 而 avatarUrl 为 null 正是没绑官网/没传头像的默认状态，即绝大多数账号——
 * 用户侧表现是参与人列表永远「只有你一个人」、查人永远「服务器内部错误」。
 *
 * 所以这两条用例断言的不是业务字段，而是**返回值能被 Jackson 序列化**，
 * 并且 null 原样是 JSON null（不是字符串 "null"、也不是被抹掉）。
 */
class CloudMemberProxyJsonTest {

    private static final ObjectMapper JACKSON = new ObjectMapper();

    private String canned;
    private CloudSyncService cloud;

    @BeforeEach
    void setUp() {
        ProjectRemote remote = new ProjectRemote();
        remote.setId(1L);
        remote.setProjectId(7L);
        remote.setConnectionId(3L);
        remote.setRemoteProjectId("55");

        CloudConnection conn = new CloudConnection();
        conn.setId(3L);
        conn.setServerUrl("https://case.example.com");
        conn.setUsername("awd_hanzewei");
        conn.setDeviceToken("awdt_x");
        conn.setTokenId(41L);
        conn.setRemoteUserId(88L);

        ProjectRemoteRepository remoteRepo = mock(ProjectRemoteRepository.class);
        when(remoteRepo.findByProjectId(any())).thenReturn(Optional.of(remote));
        CloudConnectionRepository connRepo = mock(CloudConnectionRepository.class);
        when(connRepo.findById(any())).thenReturn(Optional.of(conn));

        cloud = new CloudSyncService(
                mock(ProjectRepoService.class),
                mock(WorkSessionService.class),
                mock(ProjectTreeManifestService.class),
                mock(ProjectFileRepository.class),
                connRepo,
                remoteRepo,
                mock(ProjectRepository.class)) {
            @Override
            protected String httpGet(String url, String sessionToken) {
                return canned;
            }
        };
    }

    /** 参与人列表：没传头像的同事（avatarUrl=null）不能把整条列表打成 500。 */
    @Test
    void memberListWithNullAvatarSerializesInsteadOfBlowingUpJackson() throws Exception {
        canned = "{\"code\":0,\"data\":[{\"username\":\"a\",\"displayName\":\"甲律师\","
                + "\"role\":\"PARTICIPANT\",\"avatarUrl\":null}]}";

        List<Map<String, Object>> members = cloud.proxyMembers(7L);

        String json = assertDoesNotThrow(() -> JACKSON.writeValueAsString(Map.of("members", members)),
                "返回值必须是 Jackson 能序列化的纯 Java 结构");
        assertTrue(json.contains("\"avatarUrl\":null"), "avatarUrl 要原样是 JSON null: " + json);
        assertEquals(1, members.size());
        assertNull(members.get(0).get("avatarUrl"), "JSONNull 必须已经变回 Java null");
        assertEquals("甲律师", members.get(0).get("displayName"));
    }

    /** 查人：同款，avatarUrl 为 null 时不能让「查人」永远报服务器内部错误。 */
    @Test
    void memberLookupWithNullAvatarSerializesInsteadOfBlowingUpJackson() throws Exception {
        canned = "{\"code\":0,\"data\":{\"found\":true,\"displayName\":\"乙律师\","
                + "\"avatarUrl\":null,\"maskedContact\":\"138****0000\","
                + "\"alreadyMember\":false,\"currentRole\":null}}";

        Map<String, Object> data = cloud.proxyMemberLookup(7L, "13800000000");

        String json = assertDoesNotThrow(() -> JACKSON.writeValueAsString(data),
                "返回值必须是 Jackson 能序列化的纯 Java 结构");
        assertTrue(json.contains("\"avatarUrl\":null"), "avatarUrl 要原样是 JSON null: " + json);
        assertTrue(json.contains("\"currentRole\":null"), "currentRole 同样要原样是 JSON null: " + json);
        assertNull(data.get("avatarUrl"), "JSONNull 必须已经变回 Java null");
        assertEquals(Boolean.TRUE, data.get("found"));
        assertEquals("乙律师", data.get("displayName"));
    }

    /**
     * 协作事件（spec 2026-09-14 §2.3）：同款 JSONNull 陷阱——事件行里
     * {@code avatarUrl}、{@code device.name}、{@code commitCount}、{@code target}
     * 天生就有一大半是 null（成员事件没有设备、签出事件没有 sha），
     * 不过 toPlain 就是「提交历史」标签页永远打不开。
     *
     * <p>同时钉住外层那两个字段：{@code selfUserId} 取**案件库那一侧**的 userId
     * （CloudConnection.remoteUserId，不是本机 userId），{@code selfTokenId} 是本机这枚令牌。
     */
    @Test
    void collabEventsAreProxiedAsPlainJavaWithSelfIdentity() throws Exception {
        canned = "{\"code\":0,\"data\":{\"events\":[{\"id\":9,\"kind\":\"PUSH\","
                + "\"actor\":{\"userId\":5,\"displayName\":\"乙律师\",\"avatarUrl\":null},"
                + "\"device\":{\"tokenId\":3,\"name\":null},"
                + "\"fromSha\":\"aaa\",\"toSha\":\"bbb\",\"commitCount\":2,"
                + "\"target\":null,\"detail\":null,\"createdAt\":\"2026-09-14T10:30:00\"}]}}";

        Map<String, Object> data = cloud.proxyCollabEvents(7L, 100, null);

        String json = assertDoesNotThrow(() -> JACKSON.writeValueAsString(data),
                "返回值必须是 Jackson 能序列化的纯 Java 结构");
        assertTrue(json.contains("\"avatarUrl\":null"), json);
        assertTrue(json.contains("\"name\":null"), json);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> events = (List<Map<String, Object>>) data.get("events");
        assertEquals(1, events.size());
        assertNull(events.get(0).get("target"), "JSONNull 必须已经变回 Java null");
        assertEquals(2, events.get(0).get("commitCount"));
        // 连接里存的是案件库那一侧的身份：本机 userId 与事件表毫无关系
        assertEquals(88L, data.get("selfUserId"));
        assertEquals(41L, data.get("selfTokenId"));
    }
}
