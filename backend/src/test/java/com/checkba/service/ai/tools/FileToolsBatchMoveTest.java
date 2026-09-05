package com.checkba.service.ai.tools;

import com.checkba.model.entity.ProjectFile;
import com.checkba.repository.ProjectFileRepository;
import com.checkba.service.ProjectFileService;
import com.checkba.service.ai.EditorBridgeService;
import com.checkba.service.ai.context.ProjectContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 整理文件类任务必须能在一轮里做完（dev-board#466）。
 *
 * <p>病灶：文件树的每个变更原语都是单项的（create_folder / move_project_file /
 * rename_project_file / move_file 各管一个），而步数预算按 LLM 轮数计
 * （AgentOrchestrator.MAX_LOOP_DEPTH = 30）。弱模型一轮只发一个工具调用时，
 * 「把 14 份文件归进 8 个文件夹」光是变更就要十几二十轮，加上分类阅读与
 * todo_write 进度更新就撞上 30 步暂停——用户看到的是任务干到一半停住。
 *
 * <p>#419 在 Word 面已经解过同一道题（office_replace_batch，一批 50 处）。
 * 本测试钉住文件树面的同款批量原语：一次调用完成整批移动，缺失的目标文件夹自动补建，
 * 单条失败不影响其余，刷新文件树只触发一次。
 *
 * <p>用反射调用被测方法，是为了让本测试在工具还不存在时也能编译并给出可读的红——
 * 而不是整个测试类编译不过。
 */
class FileToolsBatchMoveTest {

    private static final long PROJECT_ID = 42L;
    private static final long USER_ID = 7L;

    private ProjectFileService fileService;
    private ProjectFileRepository repository;
    private EditorBridgeService bridge;
    private FileTools tools;

    /** 内存里的文件树：仓储 mock 每次都读它，所以批内新建的文件夹对后续条目可见。 */
    private List<ProjectFile> tree;
    private final AtomicLong nextId = new AtomicLong(1000L);

    @BeforeEach
    void setUp() {
        fileService = Mockito.mock(ProjectFileService.class);
        repository = Mockito.mock(ProjectFileRepository.class);
        bridge = Mockito.mock(EditorBridgeService.class);
        tree = new ArrayList<>();

        when(repository.findByProjectIdAndIsDeletedFalseOrderBySortOrderAsc(anyLong()))
                .thenAnswer(inv -> new ArrayList<>(tree));

        // ensureFolderPath：真实服务是「缺哪段补哪段」，这里照样建到树里并返回末段
        when(fileService.ensureFolderPath(anyLong(), anyLong(), any()))
                .thenAnswer(inv -> {
                    @SuppressWarnings("unchecked")
                    List<String> segments = (List<String>) inv.getArgument(2);
                    Long parentId = null;
                    ProjectFile current = null;
                    for (String seg : segments) {
                        ProjectFile existing = find(seg, parentId);
                        current = existing != null ? existing : addNode(seg, parentId, true);
                        parentId = current.getId();
                    }
                    return current;
                });

        // move：改父节点即可（真实服务同时搬物理文件，这里不关心）
        when(fileService.move(anyLong(), any(), any(), anyLong()))
                .thenAnswer(inv -> {
                    Long fileId = inv.getArgument(0);
                    Long targetFolderId = inv.getArgument(1);
                    ProjectFile node = byId(fileId);
                    node.setParentId(targetFolderId);
                    return node;
                });
        when(fileService.rename(anyLong(), any(), anyLong()))
                .thenAnswer(inv -> {
                    ProjectFile node = byId(inv.getArgument(0));
                    node.setName(inv.getArgument(1));
                    return node;
                });

        tools = new FileTools(fileService, repository, bridge, null, null, null, null, null);
        ProjectContextHolder.setProjectId(String.valueOf(PROJECT_ID));
        ProjectContextHolder.setUserId(USER_ID);
    }

    @AfterEach
    void clear() {
        ProjectContextHolder.clear();
    }

    // ---------- 用例 ----------

    @Test
    @DisplayName("14 份文件归进 8 个新文件夹：一次调用做完，文件夹自动补建（每建一次）")
    void fourteenFilesIntoEightFoldersInOneCall() {
        String[][] plan = {
                {"起诉状.docx", "01 诉讼文书/起诉状.docx"},
                {"答辩状.docx", "01 诉讼文书/答辩状.docx"},
                {"证据目录.docx", "02 证据/证据目录.docx"},
                {"银行流水.pdf", "02 证据/银行流水.pdf"},
                {"合同正本.pdf", "03 合同/合同正本.pdf"},
                {"补充协议.pdf", "03 合同/补充协议.pdf"},
                {"营业执照.jpg", "04 主体资料/营业执照.jpg"},
                {"身份证.jpg", "04 主体资料/身份证.jpg"},
                {"会议记录.txt", "05 会议/会议记录.txt"},
                {"通话记录.txt", "05 会议/通话记录.txt"},
                {"法条摘录.md", "06 法律检索/法条摘录.md"},
                {"判例摘要.md", "06 法律检索/判例摘要.md"},
                {"费用清单.xlsx", "07 财务/费用清单.xlsx"},
                {"往来函件.docx", "08 函件/往来函件.docx"},
        };
        for (String[] row : plan) {
            addNode(row[0], null, false);
        }

        String out = invokeBatch(json(plan));

        assertFalse(out.startsWith("Error"), "整批合法时不该整体报错，实际是：" + out);
        verify(fileService, times(14)).move(anyLong(), any(), any(), anyLong());
        // 8 个目标文件夹各建一次：索引每成功一项刷新一次，后续条目看得见前面新建的文件夹
        assertEquals(8, tree.stream().filter(f -> Boolean.TRUE.equals(f.getIsFolder())).count(),
                "8 个目标文件夹应各建一次，实际树：" + tree.stream().map(ProjectFile::getName).toList());
        assertTrue(out.contains("moved: 14"), "要逐项回报成功条数，实际是：" + out);
    }

    @Test
    @DisplayName("超过 50 条整批拒绝，一条都不许先动手")
    void overCapIsRejectedBeforeAnyMove() {
        String[][] plan = new String[51][2];
        for (int i = 0; i < 51; i++) {
            plan[i] = new String[]{"f" + i + ".txt", "归档/f" + i + ".txt"};
            addNode(plan[i][0], null, false);
        }

        String out = invokeBatch(json(plan));

        assertTrue(out.startsWith("Error"), "超限必须整批拒绝，实际是：" + out);
        assertTrue(out.contains("50"), "要把上限告诉模型，实际是：" + out);
        verify(fileService, never()).move(anyLong(), any(), any(), anyLong());
    }

    @Test
    @DisplayName("单条失败只报这一条，其余照常移动（整批重发会移两遍）")
    void oneBadEntryDoesNotAbortTheRest() {
        String[][] plan = {
                {"a.docx", "归档/a.docx"},
                {"查无此文件.docx", "归档/查无此文件.docx"},
                {"c.docx", "归档/c.docx"},
        };
        addNode("a.docx", null, false);
        addNode("c.docx", null, false);

        String out = invokeBatch(json(plan));

        assertFalse(out.startsWith("Error"), "个别条目失败不是整批失败，实际是：" + out);
        verify(fileService, times(2)).move(anyLong(), any(), any(), anyLong());
        assertTrue(out.contains("moved: 2"), "要报成功条数，实际是：" + out);
        assertTrue(out.contains("查无此文件.docx"), "失败条目要点名，实际是：" + out);
    }

    @Test
    @DisplayName("越界路径（..）整批拒绝：校验全部前置，不留半成品文件树")
    void pathEscapeIsRejectedUpFront() {
        addNode("a.docx", null, false);
        String out = invokeBatch("[{\"sourcePath\":\"a.docx\",\"destPath\":\"../别的项目/a.docx\"}]");

        assertTrue(out.startsWith("Error"), "越界路径必须拒绝，实际是：" + out);
        verify(fileService, never()).move(anyLong(), any(), any(), anyLong());
    }

    @Test
    @DisplayName("坏 JSON 给可行动的错误，不静默空跑")
    void malformedJsonIsActionable() {
        String out = invokeBatch("这不是 JSON");
        assertTrue(out.startsWith("Error"), "解析失败要以 Error 开头（ToolResult.success 的失败判据），实际是：" + out);
        verify(fileService, never()).move(anyLong(), any(), any(), anyLong());
    }

    @Test
    @DisplayName("刷新文件树由 @ToolMeta 触发一次，方法体不许逐项刷")
    void refreshFilesFiresOncePerCallNotPerItem() {
        addNode("a.docx", null, false);
        addNode("b.docx", null, false);
        invokeBatch("[{\"sourcePath\":\"a.docx\",\"destPath\":\"归档/a.docx\"},"
                + "{\"sourcePath\":\"b.docx\",\"destPath\":\"归档/b.docx\"}]");

        verify(bridge, never()).sendRefreshFilesAction();
        ToolMeta meta = batchMethod().getAnnotation(ToolMeta.class);
        assertTrue(meta != null && meta.refreshFiles(),
                "批量移动要声明 refreshFiles=true，由编排器每次调用刷一次");
        assertEquals("file", meta.category(), "要归在 file 类目下");
    }

    @Test
    @DisplayName("两版 system prompt 的 File Operations 表都点名批量移动与三个文件树原语")
    void bothPromptsListTheFileTreePrimitives() throws Exception {
        for (String name : List.of("prompts/system_prompt.md", "prompts/system_prompt.en.md")) {
            String text;
            try (var in = getClass().getClassLoader().getResourceAsStream(name)) {
                assertTrue(in != null, name + " 应存在");
                text = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            }
            assertTrue(text.contains("move_files_batch"), name + " 的文件操作表应点名批量移动工具");
            assertTrue(text.contains("create_folder"), name + " 的文件操作表缺 create_folder");
            assertTrue(text.contains("move_project_file"), name + " 的文件操作表缺 move_project_file");
            assertTrue(text.contains("rename_project_file"), name + " 的文件操作表缺 rename_project_file");
        }
    }

    // ---------- helpers ----------

    private static String json(String[][] plan) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < plan.length; i++) {
            if (i > 0) sb.append(',');
            sb.append("{\"sourcePath\":\"").append(plan[i][0])
              .append("\",\"destPath\":\"").append(plan[i][1]).append("\"}");
        }
        return sb.append(']').toString();
    }

    private Method batchMethod() {
        try {
            return FileTools.class.getMethod("move_files_batch", String.class);
        } catch (NoSuchMethodException e) {
            fail("FileTools 没有批量移动工具 move_files_batch(String)：文件树的每个变更原语都是单项的，"
                    + "整理十几份文件就会一轮一个地耗尽 30 步预算（dev-board#466）");
            throw new AssertionError("unreachable");
        }
    }

    private String invokeBatch(String movesJson) {
        try {
            return String.valueOf(batchMethod().invoke(tools, movesJson));
        } catch (InvocationTargetException e) {
            throw new AssertionError("move_files_batch 抛异常了（工具必须返回 Error: 文本，不能抛）", e.getCause());
        } catch (IllegalAccessException e) {
            throw new AssertionError(e);
        }
    }

    private ProjectFile addNode(String name, Long parentId, boolean folder) {
        ProjectFile f = new ProjectFile();
        f.setId(nextId.incrementAndGet());
        f.setProjectId(PROJECT_ID);
        f.setName(name);
        f.setParentId(parentId);
        f.setIsFolder(folder);
        f.setIsDeleted(false);
        f.setSortOrder(tree.size());
        tree.add(f);
        return f;
    }

    private ProjectFile find(String name, Long parentId) {
        return tree.stream()
                .filter(f -> f.getName().equals(name)
                        && java.util.Objects.equals(f.getParentId(), parentId))
                .findFirst().orElse(null);
    }

    private ProjectFile byId(Long id) {
        return tree.stream().filter(f -> f.getId().equals(id)).findFirst()
                .orElseThrow(() -> new IllegalStateException("no node " + id));
    }
}
