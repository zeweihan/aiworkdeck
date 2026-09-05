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
}
