package com.example.hello;

import dev.langchain4j.agent.tool.Tool;

/**
 * 示例插件工具类（插件规范 v1，见 docs/PLUGIN_SPEC.md）。
 *
 * 约定：
 * - 必须有无参构造函数（宿主通过反射实例化）；
 * - 工具方法用 @Tool 注解，方法名即工具名，需与 manifest.json 的 tools[].name 一致；
 * - description 用中文写清楚用途，Agent 依赖它决定是否调用。
 */
public class HelloTools {

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
}
