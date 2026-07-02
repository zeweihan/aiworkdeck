# LibreOffice 简体中文界面语言包 / zh-CN UI langpack (issue #66)

让嵌入式 LibreOffice（LOWA）编辑器界面显示简体中文。开机时由
`frontend/src/composables/zetaOfficeBoot.js` 的 `preRun` 注入到 LOWA 的 MEMFS
`/instdir/` 下（与 CJK 字体同法，LOWA data mount 合并而非替换）。

## 内容 / Contents

- `program/resource/zh_CN/LC_MESSAGES/*.mo` — gettext 翻译目录（菜单/工具栏/对话框/右键）。
  注意 gettext 目录名用下划线 `zh_CN`（LibreOffice 约定），而 locale id 用连字符 `zh-CN`。
- `share/registry/Langpack-zh-CN.xcd` — 把 zh-CN 注册为「已安装 UI locale」（上游语言包原件）。
- `share/registry/ZZZ-zetaoffice-ui-locale-zh-CN.xcd` — **本项目新建**，设
  `Setup/L10N/ooLocale = zh-CN`，把默认 UI 语言切到中文（仅注册不够，还要设默认）。
- `manifest.json` — 上述文件相对 `/instdir` 的路径清单（boot 据此 fetch + 写入）。

## 来源与许可 / Source & License

提取自 **LibreOffice 24.2.7.2** Linux x86-64 deb 语言包 zh-CN（The Document Foundation）：
`libobasis24.2-zh-cn_24.2.7.2-2_amd64.deb`。LibreOffice 翻译资源许可 **MPL-2.0**。

嵌入式 LOWA 引擎本体为 **ZetaOffice 24 / LibreOffice 24.2.8.0**（allotropia）。TDF 二进制档
最高到 24.2.7.2；24.2.x 各 micro 之间 UI 字符串已冻结，与 24.2.8 几乎完全匹配，个别未匹配
字符串 gettext 会逐条优雅回退为英文。如需更精确匹配，换用更接近 buildid 的语言包重新提取即可。

## 更新方法 / How to refresh

```sh
# 下载并提取对应版本的 zh-CN deb 语言包，把 .mo 与 Langpack-zh-CN.xcd 覆盖到本目录，
# 然后重新生成 manifest.json（保留 ZZZ-zetaoffice-ui-locale-zh-CN.xcd）。
```
