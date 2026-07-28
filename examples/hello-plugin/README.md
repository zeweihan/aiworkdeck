# hello-plugin 示例插件

插件规范 v1（[docs/PLUGIN_SPEC.md](../../docs/PLUGIN_SPEC.md)）的最小可运行示例：
提供 `helloEcho`（文本回显）与 `helloWordCount`（字数统计）两个 AI 工具。

## 目录

```
examples/hello-plugin/
├── manifest.json    # 插件元数据（安装时与 JAR 一起拷贝）
├── pom.xml          # 独立 Maven 工程（不参与宿主 backend 构建）
├── README.md
└── src/main/java/com/example/hello/HelloTools.java
```

## 构建

需要 JDK 17+ 与 Maven：

```bash
cd examples/hello-plugin
mvn -q package
# 产物：target/hello-plugin-1.0.0.jar
```

## 安装到 AI Workdeck

把 manifest 和 JAR 拷贝到后端工作目录的 `plugins/hello-plugin/` 下：

```bash
mkdir -p <后端工作目录>/plugins/hello-plugin
cp manifest.json target/hello-plugin-1.0.0.jar <后端工作目录>/plugins/hello-plugin/
```

然后二选一：

1. 重启后端（启动时自动扫描）；
2. 在「系统管理 → 插件广场」点击「重新扫描」（或 `POST /api/plugins/rescan`，需 admin）。

## 验证

- 插件广场应出现「Hello 示例插件」卡片，含 2 个工具与权限标签（本示例无权限声明）；
- 在 AI 对话中让 Agent「用 helloEcho 回显 hello world」，应返回 `echo: hello world`。

## 改成自己的插件

1. 换 `manifest.json` 的 `id/name/description/tools`（`id` 全局唯一且稳定，启停状态以它为键）；
2. 在 `permissions` 中如实声明需要的能力（`file_read` / `file_write` / `network` / `editor`）；
3. 修改/新增带 `@Tool` 注解的工具类（无参构造、方法名唯一）；
4. `pom.xml` 的 `artifactId/version` 与 `manifest.json` 的 `backendJars` 文件名保持一致。

## 发布到插件广场

本示例是仓库内的最小参考。要把插件发到线上供他人安装，走官网的提交与审核流程：

- 开发指南（含打包、审核标准、驳回情形）：<https://www.aiworkdeck.com/zh/plugins/develop>
- 模板工程下载：<https://www.aiworkdeck.com/api/plugins/template>
- 提交入口：<https://www.aiworkdeck.com/zh/plugins/submit>

官网那份模板与本目录同源，内容有出入时以 `aiworkdeckweb/lib/plugin-template.ts` 为准
（外部开发者拿不到本私有仓库，只能看到官网那份）。
