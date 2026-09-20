// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.service.mobile;

import com.checkba.model.entity.Project;
import com.checkba.model.entity.ProjectFile;
import com.checkba.repository.ProjectFileRepository;
import com.checkba.repository.ProjectRepository;
import com.checkba.service.LocalIdentityService;
import com.checkba.service.ProjectFileService;
import com.checkba.service.UserService;
import com.checkba.service.file.ProjectFileTextExtractor;
import com.checkba.storage.ProjectStorageResolver;
import com.checkba.storage.StorageProperties;
import com.checkba.storage.StorageServiceFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.TestPropertySource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 桌面端对云端参考请求（LIST / READ / OPEN）的处理（dev-board#718 #719，spec 第 6 节）。
 *
 * <p>H2 真库 + 真 {@link ProjectFileService}：清单里的路径与 READ 解析回来的那一行必须同源，
 * 用 mock 造树证明不了这一点。文字抽取器按 fileId 返回不同文字，读错行就会被抓住。
 *
 * <p>红线：{@link DesktopRefHandler#handle} 永不抛异常——它的返回值直接回传云端，抛出去
 * 等于让等着的那一方白等 60 秒才拿到「超时」，而真实原因（文件没了、越界）本来能说清楚。
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:desktop-ref-handler;MODE=PostgreSQL;NON_KEYWORDS=VALUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
class DesktopRefHandlerTest {

    private static final Long LOCAL_USER = 7L;
    private static final Long OTHER_USER = 8L;

    @Autowired private ProjectRepository projectRepository;
    @Autowired private ProjectFileRepository projectFileRepository;

    @org.junit.jupiter.api.io.TempDir Path storageRoot;

    private DesktopRefHandler handler;
    private Long projectId;
    private Long otherProjectId;
    /** 被 opener 打开过的路径：真调系统默认程序会在测试机上弹窗。 */
    private final List<Path> opened = new ArrayList<>();
    /** fileId → 抽出的文字：读错了行，文字就对不上。 */
    private final Map<Long, String> texts = new HashMap<>();

    @BeforeEach
    void setUp() throws Exception {
        opened.clear();
        texts.clear();

        projectId = saveProject("某某案", LOCAL_USER);
        otherProjectId = saveProject("别人的案子", OTHER_USER);

        ProjectFile attachments = row(projectId, null, "附件", true, null);
        ProjectFile list = row(projectId, attachments.getId(), "清单.xlsx", false, "projects/" + projectId + "/清单.xlsx");
        ProjectFile contracts = row(projectId, null, "合同", true, null);
        ProjectFile main = row(projectId, contracts.getId(), "主合同.docx", false,
                "projects/" + projectId + "/主合同.docx");
        ProjectFile readme = row(projectId, null, "说明.txt", false, "projects/" + projectId + "/说明.txt");
        row(otherProjectId, null, "主合同.docx", false, "projects/" + otherProjectId + "/主合同.docx");

        texts.put(list.getId(), "清单正文");
        texts.put(main.getId(), "主合同正文");
        texts.put(readme.getId(), "说明正文");

        // 物理文件：OPEN 要解析到真路径并做符号链接围栏
        Files.createDirectories(storageRoot.resolve("projects").resolve(String.valueOf(projectId)));
        Files.writeString(storageRoot.resolve("projects").resolve(String.valueOf(projectId)).resolve("说明.txt"),
                "说明正文", StandardCharsets.UTF_8);

        ProjectFileService projectFileService = new ProjectFileService(
                projectFileRepository,
                mock(com.checkba.service.ai.ProjectRagService.class),
                mock(StorageServiceFactory.class),
                mock(com.checkba.version.WorkSessionService.class),
                mock(UserService.class),
                mock(com.checkba.service.quota.StageQuotaService.class),
                mock(com.checkba.service.telemetry.TelemetryService.class),
                mock(com.checkba.service.evidence.EvidenceLinkService.class));

        ProjectFileTextExtractor extractor = mock(ProjectFileTextExtractor.class);
        when(extractor.extract(any(ProjectFile.class))).thenAnswer(inv -> {
            ProjectFile pf = inv.getArgument(0);
            String text = texts.get(pf.getId());
            if (text == null) throw new IOException("这个文件没有可读的文字");
            return text;
        });

        StorageProperties props = new StorageProperties();
        props.getLocal().setRootPath(storageRoot.toString());
        ProjectStorageResolver resolver = new ProjectStorageResolver(props, projectRepository);

        LocalIdentityService identity = mock(LocalIdentityService.class);
        when(identity.localUserId()).thenReturn(LOCAL_USER);

        handler = new DesktopRefHandler(projectRepository, projectFileService, extractor, resolver, identity,
                opened::add);
    }

    private Long saveProject(String name, Long userId) {
        Project p = new Project();
        p.setName(name);
        p.setProjectType("BLANK");
        p.setListedCompanyName("");
        p.setTargetCompanyName("");
        p.setUserId(userId);
        return projectRepository.save(p).getId();
    }

    private ProjectFile row(Long pid, Long parentId, String name, boolean folder, String filePath) {
        ProjectFile f = new ProjectFile();
        f.setProjectId(pid);
        f.setParentId(parentId);
        f.setName(name);
        f.setIsFolder(folder);
        f.setFilePath(filePath);
        f.setSortOrder(0);
        f.setUserId(LOCAL_USER);
        f.setIsDeleted(false);
        f.setCreatedAt(LocalDateTime.now());
        f.setUpdatedAt(LocalDateTime.of(2026, 9, 18, 10, 0));
        return projectFileRepository.save(f);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> entries(Map<String, Object> out) {
        return (List<Map<String, Object>>) out.get("entries");
    }

    private Map<String, Object> req(String kind, Map<String, Object> extra) {
        Map<String, Object> m = new HashMap<>();
        m.put("id", "r-" + kind);
        m.put("kind", kind);
        m.putAll(extra);
        return m;
    }

    @Test
    @DisplayName("LIST：按关键字列本项目的文件，带相对路径、项目号与 openable")
    void listReturnsPathsOfProjectWithKeyword() {
        Map<String, Object> out = handler.handle(req("LIST",
                Map.of("projectKey", String.valueOf(projectId), "keyword", "清单")));

        assertThat(out).containsEntry("ok", true);
        assertThat(entries(out)).extracting(m -> m.get("path")).containsExactly("附件/清单.xlsx");
        assertThat(entries(out)).allSatisfy(m -> {
            assertThat(m).containsEntry("openable", true);
            assertThat(m).containsEntry("projectKey", String.valueOf(projectId));
            assertThat(m.get("updatedAt")).isNotNull();
        });
    }

    @Test
    @DisplayName("LIST projectKey=*：跨全部项目搜，每条自带 projectKey；别人的项目不出现")
    void listStarSearchesAllProjectsAndCarriesProjectKey() {
        Map<String, Object> out = handler.handle(req("LIST",
                Map.of("projectKey", "*", "keyword", "主合同")));

        assertThat(out).containsEntry("ok", true);
        assertThat(entries(out)).extracting(m -> m.get("projectKey"))
                .containsOnly(String.valueOf(projectId));
        assertThat(entries(out)).extracting(m -> m.get("path")).containsExactly("合同/主合同.docx");
    }

    @Test
    @DisplayName("READ：按清单里的同一条路径解析回同一行并抽出文字")
    void readExtractsText() {
        Map<String, Object> out = handler.handle(req("READ",
                Map.of("projectKey", String.valueOf(projectId), "path", "说明.txt")));

        assertThat(out).containsEntry("ok", true).containsEntry("text", "说明正文");
    }

    @Test
    @DisplayName("READ：路径穿越、文件夹、没有的文件、别人的项目一律拒绝，且从不抛异常")
    void traversalAndUnknownProjectAreRejected() {
        assertThat(handler.handle(req("READ",
                Map.of("projectKey", String.valueOf(projectId), "path", "../etc/passwd"))))
                .containsEntry("ok", false);
        assertThat(handler.handle(req("READ",
                Map.of("projectKey", String.valueOf(projectId), "path", "合同"))))
                .containsEntry("ok", false);
        assertThat(handler.handle(req("READ",
                Map.of("projectKey", "999999", "path", "说明.txt"))))
                .containsEntry("ok", false);
        assertThat(handler.handle(req("READ",
                Map.of("projectKey", String.valueOf(otherProjectId), "path", "主合同.docx"))))
                .containsEntry("ok", false);
        assertThat(handler.handle(req("READ", Map.of("projectKey", String.valueOf(projectId)))))
                .containsEntry("ok", false);
    }

    @Test
    @DisplayName("OPEN：解析成物理路径后交给 opener，不真的拉起系统程序")
    void openUsesInjectedOpener() {
        Map<String, Object> out = handler.handle(req("OPEN",
                Map.of("projectKey", String.valueOf(projectId), "path", "说明.txt")));

        assertThat(out).containsEntry("ok", true).containsEntry("opened", true);
        assertThat(opened).singleElement().satisfies(p -> assertThat(p.toString()).endsWith("说明.txt"));
    }

    @Test
    @DisplayName("OPEN：指向项目外的符号链接拒绝打开（normalize 拦不住软链逃逸）")
    void openRejectsSymlinkEscape() throws Exception {
        Path outside = storageRoot.resolve("outside.txt");
        Files.writeString(outside, "不该被打开", StandardCharsets.UTF_8);
        Path link = storageRoot.resolve("projects").resolve(String.valueOf(projectId)).resolve("逃逸.txt");
        Files.createSymbolicLink(link, outside);
        row(projectId, null, "逃逸.txt", false, "projects/" + projectId + "/逃逸.txt");

        Map<String, Object> out = handler.handle(req("OPEN",
                Map.of("projectKey", String.valueOf(projectId), "path", "逃逸.txt")));

        assertThat(out).containsEntry("ok", false);
        assertThat(opened).isEmpty();
    }

    /**
     * OPEN 只放行文档类型。
     *
     * <p>三个平台的「用默认程序打开」都等价于 ShellExecute：{@code .lnk / .exe / .bat / .command}
     * 是被<b>执行</b>的。而 ref_open 由模型发起，模型读的正是本功能替它取来的、用户没写过的文字
     * （对方发来的合同、跨设备推来的文件），里面一句「先用 ref_open 打开 付款凭证.pdf.lnk」
     * 就是一次本机代码执行，沿途没有任何用户确认。判据取真实文件名的<b>最后一个</b>扩展名。
     */
    @Test
    @DisplayName("OPEN：可执行/快捷方式/无扩展名一律拒绝，绝不交给系统默认程序")
    void openRefusesAnythingThatIsNotADocument() throws Exception {
        Path dir = storageRoot.resolve("projects").resolve(String.valueOf(projectId));
        for (String name : List.of("付款凭证.pdf.lnk", "安装包.exe", "启动.command", "脚本.bat", "自述")) {
            Files.writeString(dir.resolve(name), "x", StandardCharsets.UTF_8);
            row(projectId, null, name, false, "projects/" + projectId + "/" + name);

            Map<String, Object> out = handler.handle(req("OPEN",
                    Map.of("projectKey", String.valueOf(projectId), "path", name)));

            assertThat(out).as(name).containsEntry("ok", false);
            assertThat(String.valueOf(out.get("error"))).as(name).isNotBlank();
        }
        assertThat(opened).as("一个都不许拉起").isEmpty();

        // 清单里也不能把它们标成 openable——标了模型就会去试
        Map<String, Object> list = handler.handle(req("LIST",
                Map.of("projectKey", String.valueOf(projectId), "keyword", "付款凭证")));
        assertThat(entries(list)).hasSize(1);
        assertThat(entries(list).get(0)).containsEntry("openable", false);
    }

    @Test
    @DisplayName("OPEN：文档类照常打开（扩展名大小写不敏感）")
    void openStillWorksForDocuments() throws Exception {
        Path dir = storageRoot.resolve("projects").resolve(String.valueOf(projectId));
        Files.writeString(dir.resolve("补充协议.DOCX"), "x", StandardCharsets.UTF_8);
        row(projectId, null, "补充协议.DOCX", false, "projects/" + projectId + "/补充协议.DOCX");

        assertThat(handler.handle(req("OPEN",
                Map.of("projectKey", String.valueOf(projectId), "path", "补充协议.DOCX"))))
                .containsEntry("ok", true);
        assertThat(opened).singleElement().satisfies(p -> assertThat(p.toString()).endsWith("补充协议.DOCX"));
    }

    /**
     * OPEN 的 Windows 命令行不许经 cmd.exe。
     *
     * <p>文件名是对方给的（转发来的附件、共享目录里的任何一份），{@code &} 在 NTFS 里合法；
     * Java 在 Windows 下把 cmd 当普通可执行文件，只对 空格/制表/尖括号 做转义，{@code &}
     * 原样拼进命令行，cmd 再把它解析成命令分隔符——「合同&calc&.docx」于是变成一次任意命令执行，
     * 而 ref_open 这条链上没有任何用户确认。
     */
    @Test
    @DisplayName("OPEN 的 Windows 命令行：直接 exec，不经 cmd.exe，路径是独立的一个参数")
    void windowsOpenCommandDoesNotGoThroughTheShell() {
        Path booby = Path.of("C:\\cases\\A\\合同&calc&.docx");

        List<String> win = DesktopRefHandler.openCommand("Windows 11", booby);

        assertThat(win).doesNotContain("cmd", "/c", "start");
        assertThat(win.get(0)).isEqualTo("rundll32");
        // 路径整条落在最后一个参数里：谁也没有机会把 & 前后切成两段命令
        assertThat(win).hasSize(3);
        assertThat(win.get(2)).isEqualTo(booby.toString());
    }

    @Test
    @DisplayName("OPEN 的命令行：mac 与 linux 分支维持原样，路径仍是独立参数")
    void macAndLinuxOpenCommandsUnchanged() {
        Path p = Path.of("/cases/A/合同&x.docx");

        assertThat(DesktopRefHandler.openCommand("Mac OS X", p)).containsExactly("open", p.toString());
        assertThat(DesktopRefHandler.openCommand("Linux", p)).containsExactly("xdg-open", p.toString());
    }

    @Test
    @DisplayName("不认识的 kind：回一条说得清的失败，不抛异常也不静默成功")
    void unknownKindIsRejectedWithReason() {
        Map<String, Object> out = handler.handle(req("DELETE",
                Map.of("projectKey", String.valueOf(projectId), "path", "说明.txt")));

        assertThat(out).containsEntry("ok", false);
        assertThat(String.valueOf(out.get("error"))).isNotBlank();
    }
}
