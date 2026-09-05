package com.checkba.service.ai.tools;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「AI 新建文件」的目的地契约（dev-board#465）。
 *
 * <p>病灶：用户说「放在 08-尽调清单与工作底稿 里」，AI 照 system prompt 的
 * **Preferred** 路径调 {@code doc_start_stream} 起草——而这个工具压根没有目标文件夹参数，
 * 方法体里 {@code createOrUpdateFile(projectId, null, ...)} 把 parentId 写死成 null，
 * 于是文件必然落在项目根目录。模型无从表达用户的要求，用户也看不到任何报错。
 * {@code sheet_create_file} 是同一处病灶的副本。
 *
 * <p>参照系是 {@code write_docx}（FileTools）：它一直有
 * {@code @P(value = "目标文件夹ID（可选…）", required = false) Long parentFolderId}。
 *
 * <p>本测试只守「签名里有这个入口、说明里告诉了模型怎么拿 id」；真正的落点行为
 * （parentId 传到 createFile、物理路径落在文件夹下、非法 id 报错、路径穿越围栏仍在）
 * 由 {@link DocumentEditToolsNewFileFolderTest} 断言。
 */
class NewFileFolderContractTest {

    /** 会在项目里新建文件的工具：每一个都必须能指定目标文件夹。 */
    private static final List<String> FILE_CREATING_TOOLS = List.of("doc_start_stream", "sheet_create_file");

    private static Method tool(String name) {
        for (Method m : DocumentEditTools.class.getDeclaredMethods()) {
            if (m.getName().equals(name)) return m;
        }
        throw new AssertionError("DocumentEditTools 里没有工具 " + name);
    }

    @Test
    @DisplayName("新建文件的工具都必须有目标文件夹参数（可选），否则用户指定的文件夹结构上无法表达")
    void everyFileCreatingToolAcceptsATargetFolder() {
        for (String name : FILE_CREATING_TOOLS) {
            Method m = tool(name);
            boolean found = false;
            StringBuilder actual = new StringBuilder();
            for (Parameter param : m.getParameters()) {
                P p = param.getAnnotation(P.class);
                if (p == null) continue;
                actual.append("\n  - ").append(param.getType().getSimpleName()).append(" : ").append(p.value());
                boolean isFolderParam = param.getType() == Long.class
                        && (p.value().contains("文件夹") || p.value().toLowerCase().contains("folder"));
                if (isFolderParam) {
                    found = true;
                    assertTrue(!p.required(),
                            name + " 的目标文件夹参数必须是可选的（required = false），"
                                    + "否则不指定文件夹的老用法全部报错");
                    assertTrue(p.value().contains("list_project_folders"),
                            name + " 的目标文件夹参数说明必须告诉模型去哪儿取 id"
                                    + "（list_project_folders），实际是：" + p.value());
                }
            }
            assertTrue(found, name + " 没有目标文件夹参数，用户指定的文件夹无从传达。当前参数：" + actual);
        }
    }

    @Test
    @DisplayName("工具描述必须提醒模型：用户指名文件夹时先取 id 再传，不许静默落根目录")
    void toolDescriptionTellsTheModelToResolveTheFolderFirst() {
        for (String name : FILE_CREATING_TOOLS) {
            Tool t = tool(name).getAnnotation(Tool.class);
            assertNotNull(t, name + " 应当是 @Tool");
            String desc = String.join("", t.value());
            assertTrue(desc.contains("list_project_folders"),
                    name + " 的描述没告诉模型「用户指名文件夹时先 list_project_folders 拿 id」，实际是：" + desc);
        }
    }
}
