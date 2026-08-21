package com.example.hello;

import com.checkba.plugin.api.FileInfo;
import com.checkba.plugin.api.HostAware;
import com.checkba.plugin.api.PluginHost;
import com.checkba.plugin.api.ToolCall;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;

import java.util.List;

/**
 * 示例插件工具类（插件规范 v2.4，见 docs/PLUGIN_SPEC.md）。
 *
 * 约定：
 * - 必须有无参构造函数（宿主通过反射实例化）；
 * - 工具方法用 @Tool 注解，方法名即工具名，需与 manifest.json 的 tools[].name 一致；
 * - description 用中文写清楚用途，Agent 依赖它决定是否调用；
 * - 实现 {@link HostAware} 即可拿到宿主门面 {@link PluginHost}（§11 SPI），不实现也照常工作。
 */
public class HelloTools implements HostAware {

    private PluginHost host;

    @Override
    public void setHost(PluginHost host) {
        this.host = host;
    }

    @Tool("原样回显输入文本，用于验证插件链路是否打通")
    public String helloEcho(String text) {
        return "echo: " + (text == null ? "" : text);
    }

    @Tool("统计输入文本的字符数与词数（按空白分词）")
    public String helloWordCount(String text) {
        if (text == null || text.isBlank()) {
            return "字符数: 0, 词数: 0";
        }
        int chars = text.length();
        int words = text.trim().split("\\s+").length;
        return "字符数: " + chars + ", 词数: " + words;
    }

    /**
     * 宿主 SPI 示范：经 {@code host.files().list} 列出当前项目根目录。
     * projectId 参数由宿主按服务端上下文强制注入（模型传什么都会被覆盖），
     * 与 {@code host.call().projectId()} 取到的是同一个值。
     */
    @Tool("列出当前项目根目录下的文件与文件夹（宿主 SPI 示范）")
    public String helloListFiles(@P("项目 ID，由宿主注入") Long projectId) {
        if (host == null) {
            return "Error: host not injected (plugin loaded by a host older than spec v2.4).";
        }
        ToolCall call = host.call();
        long pid = projectId != null ? projectId : (call != null && call.projectId() != null ? call.projectId() : -1L);
        if (pid < 0) {
            return "Error: no project context.";
        }
        List<FileInfo> files = host.files().list(pid, null, false);
        if (files.isEmpty()) {
            return "(empty project)";
        }
        StringBuilder sb = new StringBuilder();
        for (FileInfo f : files) {
            sb.append(f.folder() ? "[dir]  " : "[file] ").append(f.name()).append(" (id=").append(f.id()).append(")\n");
        }
        return sb.toString().trim();
    }
}
