<!-- SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors -->
<!-- SPDX-License-Identifier: AGPL-3.0-or-later -->
# 腾讯会议 Marketplace 插件

独立安装的 Web 面板和 Java 后端插件，通过腾讯会议官方 `tmeet` CLI 读取会议、录制逐字稿和智能纪要。默认不自动同步，不创建、修改或取消会议，也不申请录制权限。

## 使用

1. 在 AI WorkDeck 的「插件广场 / Marketplace → 插件」搜索「腾讯会议」，安装后启用。
2. 在本机安装腾讯会议官方 `tmeet` CLI，并确认终端运行 `tmeet --version` 成功。可在已安装 Node.js 的终端执行 `npm install -g @tencentcloud/tmeet`。插件不会替你下载安装可执行程序。
3. 选择项目，打开插件，点「检查连接」。尚未授权时点「登录」，在浏览器中打开授权链接。
4. 设置回溯天数（1–90 天）及排除关键词，点「同步」。同步仅拉取列表；打开一场会议时才读取其逐字稿和智能纪要。搜索只筛选已同步的本地列表。
5. 可归档到当前项目，或选择生成纪要、提取待办；这两项会先归档，再以文件引用交给 AI。读取不完整时明确提示，并阻止将其作为完整材料归档。

依赖账号对腾讯会议云录制与转写的权限，插件不会绕过权限、替会议生成云端转写，或把尚未结束转码的录制视为已完成。真实账号授权和会议数据需由使用者验收。

登录账号属于本机操作系统用户的 CLI，共享该 CLI 的程序使用同一授权。退出需确认，并会影响其他程序。插件缓存按 AI WorkDeck 用户和项目区分；这不是独立的腾讯会议租户账号。用户仍应只同步当前项目相关材料。

0.54 系列客户端还可能显示旧的内置腾讯会议入口。此插件使用独立的面板和技能 ID；如出现两个入口，可在技能设置中停用旧内置入口。插件要求 AI WorkDeck 0.53.1 或以上；0.53.1 已通过真实宿主安装器的隔离验签、安装和工具加载测试，无需为此升级客户端。

## 数据流

本机 CLI 直连腾讯会议；凭据留在官方 CLI，会议列表、设置及逐字稿任务结果留在本机，归档落在当前项目。插件广场只分发安装文件，不接收会议材料。主动生成纪要或待办时，归档内容按宿主对话流程发送给所选模型。参见根目录 `legal/PRIVACY.md`。

## 构建与验证

需要 JDK 21、Maven、Python 3。仓库根目录执行：

```sh
./official-plugins/tencent-meeting/build.sh
NODE_PATH="$PWD/frontend/node_modules" node --test official-plugins/tencent-meeting/tests/web-*.mjs
```

输出 `dist/tencent-meeting-1.0.0.zip`，包含真实 JAR、Web 资源和独立技能。JAR 仅使用公开 PluginHost API，宿主依赖不打包进插件。发布使用官网现有 `publish-plugin.mts` 的受理、扫描、审核、签名流程；本构建脚本不会发布。

## English

Install **Tencent Meeting** from **Marketplace → Plugins**, enable it, select a project, and check the connection. The official `tmeet` CLI must already be installed locally. Open the supplied authorization link in your browser, then manually sync the meeting list. Transcripts and smart minutes load on demand. Archive a meeting to the project, or generate minutes/action items from the archived file using your selected AI model.

There is no automatic background sync, recording permission bypass, meeting mutation, or automatic CLI installation. The CLI login is shared by programs under the same OS user; signing out affects them too. Plugin caches are scoped to the WorkDeck user and project. Marketplace servers distribute the plugin and do not receive meeting content. Real-account authorization and recording availability require user verification.
