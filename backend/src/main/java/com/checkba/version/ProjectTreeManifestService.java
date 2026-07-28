package com.checkba.version;

import com.checkba.model.entity.ProjectFile;
import com.checkba.repository.ProjectFileRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

/**
 * 文件树清单：把数据库里的文件树随版本一起存进仓库，
 * 使「退回到某一版」能同时还原目录结构、排序与回收站状态。
 */
@Service
public class ProjectTreeManifestService {

    public static final String MANIFEST_PATH = ".awd/tree.json";

    private final ProjectFileRepository projectFileRepository;
    private final ProjectRepoService repoService;
    private final ObjectMapper objectMapper;

    public ProjectTreeManifestService(ProjectFileRepository projectFileRepository,
                                      ProjectRepoService repoService,
                                      ObjectMapper objectMapper) {
        this.projectFileRepository = projectFileRepository;
        this.repoService = repoService;
        this.objectMapper = objectMapper;
    }

    /** 从数据库采集当前文件树。软删除的节点也要收进来，否则回退无法还原回收站状态。 */
    public TreeManifest capture(long projectId) {
        List<TreeManifest.Node> nodes = projectFileRepository.findByProjectId(projectId)
                .stream()
                .sorted(Comparator.comparing(ProjectFile::getId))
                .map(f -> new TreeManifest.Node(
                        f.getId(),
                        f.getParentId(),
                        f.getName(),
                        Boolean.TRUE.equals(f.getIsFolder()),
                        f.getFileType(),
                        f.getSortOrder(),
                        f.getFilePath(),
                        Boolean.TRUE.equals(f.getIsDeleted())))
                .toList();
        return new TreeManifest(TreeManifest.CURRENT_VERSION, nodes);
    }

    public void writeToWorkTree(long projectId, TreeManifest manifest) {
        try {
            Path target = repoService.workTree(projectId).resolve(MANIFEST_PATH);
            Files.createDirectories(target.getParent());
            Files.writeString(target,
                    objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(manifest),
                    StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new VersionException("写入文件树清单失败: project=" + projectId, e);
        }
    }

    /** 读取某一版的清单；该版没有清单（例如开启版本记录之前的历史）时返回 null。 */
    public TreeManifest readAtRef(long projectId, String ref) {
        byte[] bytes = repoService.readBlobAtCommit(projectId, ref, MANIFEST_PATH);
        if (bytes == null || bytes.length == 0) return null;
        try {
            return objectMapper.readValue(new String(bytes, StandardCharsets.UTF_8),
                    TreeManifest.class);
        } catch (Exception e) {
            throw new VersionException("解析文件树清单失败: project=" + projectId + " ref=" + ref, e);
        }
    }
}
