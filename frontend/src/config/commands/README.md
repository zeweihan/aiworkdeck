# 命令注册表

原生菜单栏、加速键、命令面板三者的**唯一数据源**。设计见
`docs/superpowers/specs/2026-08-16-desktop-chrome-and-command-menu.md`。

## 一条命令长什么样

```js
{
  id: 'view.toggleAiPanel',            // 全局唯一，也是派发时的 action 值
  label: { zh: 'AI 面板', en: 'AI Panel' },
  accel: 'Alt+CmdOrCtrl+I',            // 可选。Electron accelerator 语法
  menu: 'view',                        // 归到哪个菜单
  group: 10,                           // 同 group 连成一块，跨 group 插分隔线
  type: 'checkbox',                    // 默认 normal
  checked: 'aiPanelOpen',              // type=checkbox 时读 state.flags[checked]
  when: ['workbench', 'notClient'],    // 全部满足才 enabled（也决定能否执行）
  run: 'wb:toggleAiPanel',             // 派发目标
}
```

**每个字段都必须可 JSON 序列化。** 整张表要经 IPC 下发给主进程渲染成 NSMenu，
`checked` 是 flags 的键名、`run` 是派发表的键名，两者都不能写成函数。

## run 的两个命名空间

- `app:*` —— 与页面无关，`appMenuBridge` 自己执行（导航、开系统对话框、外链）。
- `wb:*` —— 工作台内的动作，经 `uni.$emit('awd:command')` 交给**活跃的**
  project-overview 实例（`isActiveOverviewInstance()` 守卫，避开页面栈多实例）。

## when 的取值

不做 VS Code 那套表达式引擎，只有一组枚举，全部满足才算通过：

| token | 含义 |
|---|---|
| `workbench` | 当前在工作台页 |
| `project` | 有打开的项目 |
| `tab` | 有打开的标签 |
| `docTab` | 当前标签是 Writer 文档（修订/批注类命令的前提） |
| `split` | 分屏开着 |
| `notClient` | 不是客户视图（**安全边界**，见 spec §6.3） |
| `aiRunning` / `notAiRunning` | AI 是否在跑 |

## 加速键：编辑器优先

外壳里嵌着 Word 编辑器，**放进菜单的加速键会被永久从编辑器手里拿走**
（macOS 上 NSMenu 的 key equivalent 先于响应链）。规则见 spec §4：

- 裸 `⌘+字母` 只保留语义同构的那几个（`⌘O` `⌘W` `⌘F` `⌥⌘F` `⌘,` 与 Edit roles）；
- 其余外壳命令一律走 `Alt+CmdOrCtrl+*`；
- `Esc` / `Enter` / `Tab` 永不做加速键；
- 不碰 macOS 系统截图键（`Shift+Cmd+3/4/5`）。

这三条由 `frontend/tests/commands/commands.test.mjs` 断言，改表时它会拦你。
