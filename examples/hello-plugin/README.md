# hello-plugin 示例插件

插件规范 v1（`docs/PLUGIN_SPEC.md`，本地私有文档目录）的最小可运行示例：
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
