package com.checkba.version;

import com.checkba.model.entity.CloudConnection;
import com.checkba.model.entity.Project;
import com.checkba.model.entity.ProjectRemote;
import com.checkba.repository.CloudConnectionRepository;
import com.checkba.repository.ProjectFileRepository;
import com.checkba.repository.ProjectRemoteRepository;
import com.checkba.repository.ProjectRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 换机器取回的查重（dev-board#439 第 5 环）：同一个远端案卷已经在本机了，
 * 再点一次「取到本机」要回既有的本机 id，而不是造出第二个同名项目
 * ——两份各自有自己的 origin 绑定，律师会在两个项目里各改一半，谁也不知道另一半的存在。
 */
class CloudCloneDedupeTest {

    private Map<Long, CloudConnection> connections;
    private Map<Long, ProjectRemote> remotes;
    private ProjectRepository projectRepository;
    private CloudSyncService cloud;
    private int httpCalls;

    @BeforeEach
    void setUp() {
        connections = new HashMap<>();
        CloudConnection conn = new CloudConnection();
        conn.setId(1L);
        conn.setUserId(42L);
        conn.setServerUrl("https://case.aiworkdeck.com");
        conn.setUsername("awd_hanzewei");
        conn.setDeviceToken("awdt_x");
        conn.setCreatedAt(LocalDateTime.now());
        connections.put(1L, conn);

        CloudConnectionRepository connectionRepository = mock(CloudConnectionRepository.class);
        when(connectionRepository.findById(any())).thenAnswer(i ->
                Optional.ofNullable(connections.get(i.getArgument(0))));

        remotes = new HashMap<>();
        ProjectRemoteRepository remoteRepository = mock(ProjectRemoteRepository.class);
        when(remoteRepository.findByConnectionIdAndRemoteProjectId(any(), any())).thenAnswer(i ->
                remotes.values().stream()
                        .filter(r -> i.getArgument(0).equals(r.getConnectionId())
                                && i.getArgument(1).equals(r.getRemoteProjectId()))
                        .findFirst());

        projectRepository = mock(ProjectRepository.class);

        httpCalls = 0;
        cloud = new CloudSyncService(mock(ProjectRepoService.class), mock(WorkSessionService.class),
                mock(ProjectTreeManifestService.class), mock(ProjectFileRepository.class),
                connectionRepository, remoteRepository, projectRepository) {
            @Override
            protected String httpPost(String url, String jsonBody, String sessionToken) {
                httpCalls++;
                return "{\"code\":0}";
            }
        };
    }

    @Test
    void pullingACaseFileThatIsAlreadyOnThisMachineReturnsTheExistingOne() {
        ProjectRemote existing = new ProjectRemote();
        existing.setId(1L);
        existing.setProjectId(77L);
        existing.setConnectionId(1L);
        existing.setRemoteProjectId("5");
        existing.setPendingUpload(false);
        existing.setCreatedAt(LocalDateTime.now());
        remotes.put(1L, existing);

        Map<String, Object> result = cloud.cloneFromCloud(1L, 5L, 42L);

        assertEquals(77L, result.get("localProjectId"));
        assertEquals(Boolean.TRUE, result.get("alreadyLocal"));
        assertEquals(0, httpCalls, "已经在本机了就不该再打云端");
        verify(projectRepository, never()).save(any(Project.class));
    }
}
