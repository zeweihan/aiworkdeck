# AI Workdeck Office 插件（Word/Excel/PowerPoint 任务窗格）

Microsoft Office Add-in（spec Phase B/C）。任务窗格 = Vue3 + Vite；
office.js 从微软官方 CDN 以 script 标签引入，不打包进 bundle。
宿主支持 Word（全功能，含原生修订）、Excel（区域读写/查找 + 单元格格式/边框/行列/合并/排序/工作表/冻结窗格/公式），
PowerPoint（页文本读取/跨页替换，需 PowerPointApi 1.4）。

插件独立连接后端实例（律所自建服务器 / 官方云 / 同机桌面版 `http://127.0.0.1:5269`），
鉴权用 awdt_ 设备令牌（X-Session-Id 请求头携带），也可用官网账户 Key（awdk_）
一键换取。对话复用主链路契约：
`POST /api/agent/chat` + `GET /api/agent/connect/{conversationId}`（SSE，断线自动重连），
当前文档内容经 `activeContext.inlineContent` 内联随消息上送。

## 目录结构

```
office-addin/
  manifest.xml        XML add-in only 清单（开发态，URL 指向 https://localhost:3000）
  assets/             图标（源自桌面应用 icon，构建时拷入 dist 根，dev 下按根路径直出）
  taskpane.html       任务窗格入口页（office.js 以 CDN script 标签引入）
  taskpane/           Vue3 源码（App / SettingsView / ChatView / lib）
  scripts/            build-manifest.mjs 生产部署产物生成器
  vite.config.js      端口 3000；自动读取 office-addin-dev-certs 的本地 HTTPS 证书
```

## 本地开发

```bash
cd office-addin
npm install

# 首次：安装本地 HTTPS 证书（Office 只加载 https 的任务窗格页面）。
# 会在 ~/.office-addin-dev-certs 下生成 localhost.key/localhost.crt 并把 CA 装入系统信任链，
# vite.config.js 检测到证书即自动启用 https。
npx office-addin-dev-certs install

npm run dev        # https://localhost:3000/taskpane.html
npm run build      # 产物在 dist/
```

无 Office 宿主时可直接在浏览器打开 `https://localhost:3000/taskpane.html` 调试 UI
（office.js 未初始化时「附带文档正文」会读不到内容，属预期）。

## Sideload 调试

### macOS（wef 目录方式）

```bash
cp manifest.xml ~/Library/Containers/com.microsoft.Word/Data/Documents/wef/
# Excel / PowerPoint 同理，各自的容器目录：
cp manifest.xml ~/Library/Containers/com.microsoft.Excel/Data/Documents/wef/
cp manifest.xml ~/Library/Containers/com.microsoft.Powerpoint/Data/Documents/wef/
```

目录不存在则先 `mkdir -p` 创建。然后完全退出并重开对应宿主，
菜单「插入 → 加载项（Add-ins）→ 我的加载项 → 开发人员加载项」里选择 AI Workdeck。
更新 manifest 后需删掉重拷并重启宿主。

### Windows（网络共享目录方式）

1. 建一个文件夹（如 `C:\addin-manifests`），右键 → 属性 → 共享，共享给自己，记下网络路径（`\\机器名\addin-manifests`）。
2. 把 `manifest.xml` 拷入该文件夹。
3. Word →「文件 → 选项 → 信任中心 → 信任中心设置 → 受信任的加载项目录」，
   把网络路径添加到目录 URL 并勾选「在菜单中显示」，确定后重启 Word。
4. 「插入 → 获取加载项 → 共享文件夹」里选择 AI Workdeck。

### Windows（注册表方式，仅经典 Office）

`HKEY_CURRENT_USER\Software\Microsoft\Office\16.0\WEF\Developer` 下新建字符串值，
名称任意（如 `AIWorkdeckAddin`），数据为 manifest.xml 的绝对路径。重启 Word 生效。

## manifest 校验

```bash
npx office-addin-manifest validate manifest.xml
```

## 生产部署产物（build-manifest.mjs）

manifest 与页面里的开发态 URL（`https://localhost:3000`）在部署时要整体替换为
实际托管地址。用脚本一步生成完整可托管目录：

```bash
npm run build                                                  # 先出 dist/
npm run build:deploy -- --url https://addin.yourfirm.com       # 生成 dist-deploy/
npx office-addin-manifest validate dist-deploy/manifest.xml    # 校验生产 manifest
```

- `dist-deploy/` = dist 页面产物 + 替换好 URL 的 manifest.xml，整体上传到
  `https://addin.yourfirm.com` 即可；manifest.xml 分发给用户 sideload 或提交管理中心。
- 部署地址也可用环境变量 `ADDIN_BASE_URL` 提供；必须是 https origin。
- **世纪互联（中国版 Microsoft 365）变体**：追加 `--china`，脚本会把输出目录中
  taskpane.html 的 office.js CDN 换成
  `https://appsforoffice.cdn.partner.office365.cn/appsforoffice/lib/1/hosted/office.js`
  （中国版环境访问不到全球版 CDN）。dev 流程不受影响——源文件 taskpane.html
  始终指向全球版 CDN。全球版与世纪互联版各出一份 dist-deploy 分开托管。

## 部署注意（CORS 与 local-mode）

后端不需要为插件改任何代码，但部署时要把插件页面的 Origin 配进白名单：

- **CORS 白名单**：插件正式托管后（如 `https://addin.yourfirm.com`），该 Origin 必须加进后端配置
  `security.cors.allowed-origins`（CSV），否则浏览器侧跨域请求会被拦。
  `http(s)://localhost:*` 与 `127.0.0.1` 默认已放行，本地开发态无需配置。
  `security.cors.allow-all` 逃生门绝不能开。
- **local-mode（单机桌面后端 5269）**：`LocalModeAccessFilter` 会对携带 Origin 的非 GET 请求
  做同一份白名单硬校验（跨站请求直接 403，不只是不回 CORS 头）。开发态 `https://localhost:3000`
  在默认放行范围内；若插件页面托管在非 localhost 域而要连本机 5269，同样需要把该 Origin
  加进 `security.cors.allowed-origins`。
- 团队服务器（server 模式）无 local-mode 闸，只需配 CORS 白名单。

## 真机手测清单（发版前过一遍）

1. `npm run dev` 起 dev server（https 生效），sideload 进 Word，任务窗格能打开。
2. 设置页：填后端地址 + awdt_ 令牌 → 「测试连接」显示可访问项目数；
   故意填错令牌 → 提示「令牌无效或后端拒绝了请求」（文案不含「登录/未授权/请先」）。
3. 保存后回到对话视图，顶部项目下拉能列出且能切换项目，选择在重开任务窗格后仍记住。
4. 发一条与文档无关的消息 → 助手回复流式逐字出现，结束后输入框解锁。
5. 打开一份有内容的文档，勾选「随消息附带当前文档正文」，问「总结当前文档」→
   回复内容明确引用了文档正文（验证 inlineContent 注入链路）。
6. 取消勾选再问 → 回复不再引用文档内容。
7. 流式中点「停止」→ 输出停止、输入框解锁；「新对话」→ 消息清空、后续消息开新会话。
8. 断网/停后端再发消息 → 出现「后端不可达」类错误提示，输入框不卡死。
9. 关闭再重开任务窗格 → 设置与项目选择仍在（localStorage 持久化）。

### 工具桥场景（Phase C：office_* 工具）

10. 打开一份合同文档，让 AI「把甲方全部改成买受人」→ 对话流出现「替换文本（修订）」chip，
    文档中的修改以 **Word 原生修订**（审阅 → 修订）形式出现，可逐条接受/拒绝；
    执行前后用户自己的「修订」开关状态不变（执行完恢复原值）。
11. 让 AI「在第一条后面插入一段不可抗力条款」→ 出现「插入文本（修订）」chip，
    插入内容同样是修订形态。
12. 让 AI「给违约金条款加个批注说明风险」→ 出现「插入批注」chip，
    Word 批注面板出现批注（作者为当前 Word 登录用户）。
13. 让 AI「找找文中有几处试用期」→ 出现「查找文本」chip，回复引用命中段落上下文。
14. 观察整轮对话：AI 不应尝试调用 doc_* 工具（会话能力过滤，
    插件 chat 请求带 `clientCapability: "office"` 与 `officeHost`）；
    主前端（LOWA）会话反之不见 office_*。
15. 任务窗格关闭时让主前端会话正常改文档（回归确认 LOWA 链路不受影响）。

### 表格/结构/批注场景（批次 8）

15a. 让 AI「插入一张两列三行的表格，写上项目和金额」→ 出现「插入表格」chip，
    表格以 Word 原生修订形式插入；接着让 AI「读一下这张表」→ 出现「读取表格」chip，
    回复给出二维数组坐标。
15b. 让 AI「把表格 B2 改成 20000」→ 出现「修改单元格」chip，单元格文本以修订形式变化。
15c. 让 AI「给这张表加一行」再「删掉第二行」→ 分别出现「插入表格行」「删除表格行」chip；
    删除**不会**出现在审阅面板的修订列表里（直接生效，靠 Ctrl+Z 撤销——工具描述已明说）。
15d. 让 AI「在段落中间插一个分页符」→ 出现「插入分页符」chip，光标处出现分页。
15e. 让 AI「把'点击这里'设置成指向 https://example.com 的链接」→ 出现「设置超链接」chip，
    对应文字变蓝加下划线且可点击跳转。
15f. 让 AI「把页脚改成'内部资料，禁止外传'」→ 出现「编辑页眉页脚」chip，页脚文字更新。
15g. 手动在 Word 里给一段文字加一条批注，再让 AI「看看文档里有什么批注」→ 出现
    「读取批注」chip，回复列出作者/内容/是否已解决；接着让 AI「回复说已核实，
    标记为已解决」→ 依次出现「回复批注」「解决批注」chip，Word 批注面板里能看到
    AI 的回复且该批注变灰（已解决状态）。

### Excel 场景（officeHost=excel，office_excel_* 工具面）

16. 在 Excel 中 sideload 并打开任务窗格，打开一份有数据的工作簿，
    勾选「随消息附带当前文档正文」问「这张表说了什么」→ 回复引用活动工作表内容
    （内联上送为 TSV 文本，附区域地址）。
17. 让 AI「把 B 列的单价都涨 10% 写到 D 列」→ 出现「读取区域」「写入区域」chip，
    单元格直接生效（Excel 没有修订机制，靠 Ctrl+Z 撤销）。
18. 让 AI「找找表里哪里有华为」→ 出现「查找单元格」chip，回复列出命中单元格地址。
19. 让 AI「给表头那行加粗、居中、填充浅灰色」→ 出现「设置单元格格式」chip；再让它
    「给这张表加个外边框」→ 出现「设置边框」chip；「在第 3 行上面插一行」再
    「把 B 列到 D 列的列宽设成 100」→ 出现「编辑行列」chip。
20. 让 AI「按第一列升序排一下这个区域」→ 出现「排序」chip；「新建一个叫汇总的工作表」
    →「管理工作表」chip；「冻结第一行」→「冻结窗格」chip。
21. 让 AI「在 D 列写公式算 B 列乘以 1.1」→ 出现「写入公式」chip，公式按 Excel 原生
    文法落地（逗号分隔、`Sheet1!A1` 跨表引用）；故意让它写一个引用不存在工作表的公式，
    确认它能从 formulaErrors 里看到 #REF!/#NAME? 并自纠。
22. 让 AI「这个工作簿有几张表，各自多大」→ 出现「读取总览」chip，回复列出各表名称/
    是否活动/已用区域尺寸；让它「带我去看看 F10 那块」→ 出现「选中区域」chip，
    Excel 视图跳转并选中该区域。
23. 让 AI「给这个区域加上筛选」→ 出现「设置自动筛选」chip，表头出现下拉箭头
    （不预设筛选条件）；再让它「把筛选去掉」→ 同一 chip，`remove` 动作生效。
24. 让 AI「金额超过 1000 的标红」→ 出现「设置条件格式」chip（cellValue + greaterThan）；
    再让它「换成用颜色深浅表示数值大小」→ 同一 chip 但 ruleType=colorScale，
    确认第二次调用替换而不是叠加第一次的规则。
25. 观察整轮对话：Excel 会话不应出现 Word 面工具（office_replace_text 等）与 doc_*/sheet_*。

### PowerPoint 场景（officeHost=powerpoint，office_ppt_* 工具面）

26. 在 PowerPoint 中 sideload 并打开任务窗格，打开一份演示文稿，
    问「这份 PPT 讲了什么」→ 回复引用各页文本（第 N 页：…）。
27. 让 AI「把标题里的 2025 都改成 2026」→ 出现「替换幻灯片文本」chip，
    跨页替换直接生效；回复说明改了哪些页。
28. 旧版 PowerPoint（不支持 PowerPointApi 1.4）上执行 26/27 → 工具 chip 显示失败，
    回复解释版本不支持（不是 30 秒超时空转）。
23. 让 AI「把第一页标题设成粗体红色」→ 出现「设置幻灯片文字格式」chip，直接生效
    （无修订面板可查，靠 Ctrl+Z）。
24. 让 AI「新增一页幻灯片，标题叫总结」→ 出现「新增幻灯片」chip；再让它「插到第 2 页后面」
    → 若宿主支持 PowerPointApi 1.8 会挪到指定位置，不支持则留在末尾且回复说明原因
    （返回值 `moved`/`note` 字段）。
25. 让 AI「删除最后一页」→ 出现「删除幻灯片」chip；只剩一页时再让它删 → 明确拒绝
    （PowerPoint 不允许空演示文稿）。
26. 让 AI「在第一页插一个文本框写谢谢观看」「在第二页插一个蓝色矩形」→ 分别出现
    「插入文本框」「插入形状」chip，直接生效。
27. 让 AI「把第一页移到最后」→ 出现「移动幻灯片」chip；旧版宿主（无 PowerPointApi 1.8）
    上执行 → 明确报错而不是静默不生效。
28. 让 AI「看看第二页都有什么形状」→ 出现「读取幻灯片明细」chip，回复列出各形状
    id/类型/位置尺寸/文字；再让它「把刚才那个文本框删掉」→ 出现「删除形状」chip
    （按上一步拿到的 id 精确删除）。

### 连接链路场景

29. 对话进行中断网 10 秒再恢复 → 出现「连接中断，正在自动重连……」提示，
    恢复后提示消失、对话可继续；断线期间跑完的回复不会卡住输入框
    （重连后按 run_state 兜底解锁）。
30. 设置页「用账户 Key 连接」：粘贴有效 awdk_ Key → 一键换取 awdt_ 令牌并直接进入
    对话视图；重开任务窗格后连接仍在（保存的是 awdt_，Key 本身不落盘）。
    对未开启账户桥接的服务器 → 提示「该服务器未开启账户直连，请改用设备令牌」。

## 已知边界

- 消费 `text_delta`/`bubble_end`/`error`/`cancelled`/`run_state` 与
  `client_action`（tool=office_command）六类 SSE 事件，`plan_update` 等先忽略。
- SSE 断线自动重连：指数退避 1s 起、上限 30s；心跳（后端每 15s 一次）连续缺失
  约两个周期（40s 无任何字节）判定死连接主动重建。首连失败不重连（即时报错）。
  事件为纯推送、后端不重放历史，重连不会重复渲染已收消息。
- office_* 工具桥命令集：Word 面 get_text / get_selection / search / replace_text /
  insert_text / add_comment / format_text / set_paragraph_format / get_formatting /
  set_numbering / format_table / apply_standard_format / insert_table / table_read /
  table_set_cell / table_add_row / table_delete_row / table_add_col / table_delete_col /
  insert_break / set_hyperlink / edit_header_footer / get_comments / reply_comment /
  resolve_comment；
  Excel 面 excel_get_range / excel_set_values / excel_search / excel_format_cells /
  excel_set_borders / excel_edit_rows_cols / excel_merge_cells / excel_sort_range /
  excel_manage_sheets / excel_freeze_panes / excel_set_formulas / excel_get_overview /
  excel_select_range / excel_set_autofilter / excel_conditional_format（15 个，与桌面端
  sheet_* 原语数量对齐）；
  PPT 面 ppt_get_slides / ppt_replace_text / ppt_format_text /
  ppt_add_slide / ppt_delete_slide / ppt_add_text_box / ppt_move_slide /
  ppt_add_shape / ppt_get_slide_details / ppt_delete_shape（批次7新增八项）。结果回传
  `POST /api/agent/office/result`（body `{requestId, ok, data|error}`，
  后端按挂起表做会话归属校验）。后端按 chat 请求的 officeHost 只暴露当前宿主的工具面。
- Word 修订与批注依赖 WordApi 1.4（Word 2019+/Microsoft 365）；不支持时替换/插入降级为
  直接修改（结果携带 `tracked:false`），批注返回明确错误。
- Word 格式面（format_text / set_paragraph_format / get_formatting）基于 WordApi 1.1，
  唯独段落 styleBuiltIn（标题级别）属 WordApi 1.3，旧宿主上设置会返回明确错误、读取时
  该字段缺省。长度单位一律是磅（行距按字号换算，12 磅字 1.5 倍行距 = 18 磅）。
- 自动编号与表格格式（set_numbering / format_table）整片依赖 WordApi 1.3
  （Word.List 与表格边框 API），旧宿主返回明确错误。Word 原生编号没有中文数字这一档，
  kind=chinese 改为把「一、」写进各段段首（返回值 via='literalText'，同样是修订形态）；
  旧宿主上 bullet/decimal 也走同一条手写回退。
- apply_standard_format 与桌面端 LOWA、后端 write_docx 同一套律所标准格式（正文 12 磅
  两端对齐、段后 18 磅、行距 16 磅、首行缩进 2 字符，主标题 16 磅加粗居中，小标题加粗
  不缩进，表格 10 磅）。Office.js 只有固定磅值行距（没有「最小值」档，返回值标
  lineSpacingMode='exact'）；中西文分设字体属 WordApiDesktop 1.3，不支持时退化为单一
  中文字体（返回值 fontSplit=false）。单次最多处理 500 段（超出标 truncated），
  scope=selection 时不处理表格。
- Excel 区域读写基于 ExcelApi 1.1/1.2（getResizedRange），查找是客户端在已用区域
  内扫描（兼容旧宿主，不依赖 ExcelApi 1.9 的 findAll）；单次写入上限 2000 单元格，
  读取返回上限 500 行。Excel/PowerPoint 没有修订机制，写入直接生效。
- Excel 格式/结构面（excel_format_cells / excel_set_borders / excel_edit_rows_cols /
  excel_merge_cells / excel_sort_range / excel_manage_sheets / excel_freeze_panes /
  excel_set_formulas / excel_get_overview / excel_select_range / excel_set_autofilter /
  excel_conditional_format）：单元格格式/边框/行列插删/选中区域是 ExcelApi 1.1，
  合并/排序/行高列宽是 ExcelApi 1.2，条件格式是 ExcelApi 1.6，自动筛选是 ExcelApi 1.9，
  冻结窗格是 ExcelApi 1.7（旧宿主均返回明确错误而非空等）。删除工作表在只剩
  一张表时会被拒绝（不依赖 Excel 原生异常，前端先查总数）。写公式必须用 Excel 原生
  文法（逗号分隔参数、`Sheet1!A1` 跨表引用），与桌面端 LOWA 电子表格原语的分号/点号
  文法刻意不同，两条桥各自忠于宿主原生语法；写入后自动读回结果，公式错误（`#REF!`
  等）收进返回值 `formulaErrors` 供模型自纠。自动筛选首版只做套上/清除筛选，不支持
  按具体条件筛值；条件格式每次套用会先清空该区域现有规则再套新规则（不叠加），
  只做 cellValue（数值比较高亮）与 colorScale（固定红黄绿三色刻度）两类规则。
- PowerPoint 文本读写依赖 PowerPointApi 1.4（TextFrame/TextRange，Microsoft 365
  较新版本才有；2019/2021 永久版不支持）；旧版宿主返回明确错误。修改只覆盖形状文本
  （表格/SmartArt/母版占位内容不在 v1 范围）。
- PowerPoint 能力对齐（批次7）：`ppt_format_text` 用 `TextRange.getSubstring(start,len)`
  在纯文本里找偏移后精确切子串设字体，不改文本长度、无需像 Word 字符级修订那样从右到左应用；
  `ppt_add_slide` 的 `position` 是"追加末尾 + 条件性挪动"两步近似——`slides.add()`
  （PowerPointApi 1.3）**只能加在末尾**（`AddSlideOptions.index` 官方文档标注 preview-only，
  不能用于生产），挪到指定位置要靠 `Slide.moveTo`（**PowerPointApi 1.8**，比其余 PPT 工具的
  1.4 门槛更高），旧宿主上退化为"留在末尾"并在返回值 `moved`/`note` 说明；`ppt_move_slide`
  同样卡在 1.8。`ppt_delete_slide` 拒绝删到只剩 0 页。`ppt_add_text_box`/`ppt_add_shape`
  用 `shapes.addTextBox`/`addGeometricShape`（均 1.4），形状类型 v1 只开 rectangle/ellipse/
  triangle。`ppt_get_slide_details`/`ppt_delete_shape` 按 `Shape.id` 精确定位（1.3），
  `ppt_delete_shape` 不传 id 时按文字精确匹配定位（这条分支要 1.4，因为要读 TextFrame）。
  查证过做不了的：`AddSlideOptions.slideMasterId/layoutId` 选母版/版式需要先枚举
  `presentation.slideMasters` 拿不透明 ID 串，本批次不做母版选择器，一律用默认母版/版式；
  `shapes.addTable` 表格形状不在本批次范围。
- 表格/结构/批注（批次 8）：insert_table / table_read / table_set_cell / table_add_row /
  table_delete_row / table_add_col / table_delete_col 与 set_hyperlink 均依赖 WordApi
  1.3；表格中间位置插列（colIndex 非 0/-1）额外依赖桌面版专属的 WordApiDesktop 1.3
  （Word 网页版不支持，colIndex 传 0 或 -1 不受此限）；**表格删行删列不产生修订**，
  直接生效（与桌面端 LOWA 的 doc_table_delete_row/col 同款限制）。insert_break 目前只
  开 page/sectionNext 两种分隔类型。edit_header_footer 只处理文档首节。批注读写依赖
  WordApi 1.4；get_comments 返回的 index/id 均可用于 reply_comment/resolve_comment
  定位，id 优先。
- 会话 ID 优先请求服务端签发（`POST /api/agent/conversations`），旧后端无该端点时
  静默回退客户端生成 `conv-<毫秒>`。
- 流式文本按 XML 标签轻量分流：`<final>` 与标签外文本为主回复、`<thinking>` 折叠展示，
  `<process>`/`<artifact>` 等暂不渲染。
