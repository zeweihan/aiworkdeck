# 动态插件左栏补充对齐

2026-09-15 · dev-board [#651](https://github.com/zeweihan/dev-board/issues/651)，关联 #648。

## 尽调报告

- 已定位私有源码仓 [zeweihan/aiworkdeck-dd-plugin](https://github.com/zeweihan/aiworkdeck-dd-plugin)。基线 `ef4d57b`；manifest `id=due-diligence`，`version=0.6.1`，入口 `web/index.html`，业务 `web/app.js`，样式 `web/style.css`。
- 已提交[草稿 PR #2](https://github.com/zeweihan/aiworkdeck-dd-plugin/pull/2)，提交 `8fb06d9`，分支 `codex/sidebar-density-651`。只修改 CSS：宿主面板密度令牌及兼容默认值、平铺模板/文件/章节/统计行、保留选中强调、窄栏主体表单两列、主按钮填满可用宽度。
- Chromium 加载真实插件页面与业务脚本，以模拟宿主桥供给仓库五套模板元数据：220/260/420px 无横向溢出；点击切换模板、添加主体、空主体拦截、入库参数携带选中模板均通过。实际入库与 AI 调用未执行。
- [浅色截图](verification/dd-sidebar-light.png)与[深色截图](verification/dd-sidebar-dark.png)来自上述隔离夹具，非线上项目。

## 分发状态

实查官网 `/api/registry/plugins`：`due-diligence` 当前仍为 **0.6.1**。源码仓没有自动发布工作流；在线安装由宿主 `PluginMarketService` 获取并校验签名 bundle。CSS 要经插件发版、重新打包和签名分发才会更新已安装客户端，**本草稿 PR 尚未上线**。

README 的本机插件目录是 `~/.aiworkdeck/plugins/due-diligence`，通过重启或 rescan 加载；本轮没有覆盖该目录，也没有修改运行中的插件。

## 通用宿主

`PluginPane.vue` 实际是 iframe 宿主，默认介绍来自另一个组件 `PluginGuidePane.vue`。后者已使用面板密度令牌，保留现有功能引导；本轮把前者加载失败空态从 40px padding 改为统一 10px 横向边距、14px 纵向边距及12px主题文字。未改变 sandbox、桥协议或权限。
