package com.checkba.version;

import com.checkba.storage.StorageProperties;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 每项目一个 Git 仓库的薄封装。只认识 Git 概念，不认识「工作段」——
 * 业务语义在 WorkSessionService。
 *
 * 仓库目录与工作区分离：
 *   gitDir   = {root}/repos/project-{id}.git
 *   workTree = {root}/projects/{id}
 * 这样 .git 不会出现在 data/projects/ 下被 RAG 扫描、压缩包导出、搜索误伤。
 */
@Service
public class ProjectRepoService {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(ProjectRepoService.class);

    private final Path storageRoot;

    public ProjectRepoService(StorageProperties storageProperties) {
        String rootPath = storageProperties.getLocal().getRootPath();
        Path root = Paths.get(rootPath);
        if (!root.isAbsolute()) {
            String userDir = System.getProperty("user.dir");
            Path base = Paths.get(userDir);
            if (userDir.endsWith("backend")) base = base.getParent();
            root = base.resolve(rootPath);
        }
        this.storageRoot = root;
    }

    public Path gitDir(long projectId) {
        return storageRoot.resolve("repos").resolve("project-" + projectId + ".git");
    }

    public Path workTree(long projectId) {
        return storageRoot.resolve("projects").resolve(String.valueOf(projectId));
    }

    public boolean isInitialized(long projectId) {
        return Files.isDirectory(gitDir(projectId).resolve("objects"));
    }

    public Repository open(long projectId) {
        try {
            return new FileRepositoryBuilder()
                    .setGitDir(gitDir(projectId).toFile())
                    .setWorkTree(workTree(projectId).toFile())
                    .setMustExist(true)
                    .build();
        } catch (IOException e) {
            throw new VersionException("打开版本记录失败: project=" + projectId, e);
        }
    }

    /** 建仓库并落一笔「初始版本」。已存在则直接返回。 */
    public void init(long projectId, String authorName, String authorEmail) {
        if (isInitialized(projectId)) return;
        try {
            Files.createDirectories(workTree(projectId));
            Files.createDirectories(gitDir(projectId).getParent());
            try (Repository repo = new FileRepositoryBuilder()
                    .setGitDir(gitDir(projectId).toFile())
                    .setWorkTree(workTree(projectId).toFile())
                    .build()) {
                repo.create(true);
                try (Git git = new Git(repo)) {
                    git.add().addFilepattern(".").call();
                    git.commit()
                       .setMessage("初始版本\n\nX-AWD-Kind: session")
                       .setAuthor(authorName, authorEmail)
                       .setAllowEmpty(true)
                       .call();
                }
            }
            log.info("版本记录已开启: project={}", projectId);
        } catch (Exception e) {
            throw new VersionException("开启版本记录失败: project=" + projectId, e);
        }
    }
}
