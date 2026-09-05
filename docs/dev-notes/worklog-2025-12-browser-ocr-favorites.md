## 工作记录：浏览器工作区 + 截图OCR摘录 + 收藏（H5）

### 目标
- 在 `project-overview` 中新增“浏览器 Tab”，与文件 Tab 共存，支持拖拽、左右分屏、双开。
- 支持任意网页的“摘录”能力：截图框选 -> 后端 OCR -> 一键插入 WPS / 加入收藏。
- 收藏支持：项目内收藏 + 我的收藏（个人中心）。
- OCR 使用阿里云 OCR（后端调用），AK/Secret 通过“管理面板配置”维护，不写死在代码仓库。

### 关键限制与策略
1. **跨域限制（H5）**
   - 父页面无法读取跨域 iframe 的 DOM/选区文本（同源策略）。
   - 直接对跨域 iframe 截图到 canvas 会被 taint。
2. **覆盖面最大方案**
   - 使用 Screen Capture API（getDisplayMedia）抓取“当前标签页”的视频流 -> 框选裁剪 -> OCR。
   - 这需要用户授权共享屏幕/标签页（浏览器强制要求，无法绕过）。
3. **安全与合规**
   - AccessKey Secret 不提交进仓库；仅通过管理员界面写入 `system_setting` 表或以环境变量覆盖默认值。
   - OCR 图片不持久化（可选：仅在收藏需要时存储截图文件）。

### 进度
- [x] 将批量操作菜单改为左侧就地菜单，避免居中弹层影响动线
- [x] 统一按钮风格为 `icon-btn/tool-icon` 体系
- [ ] 管理面板新增阿里云 OCR 配置项（AK/Secret/endpoint 等）
- [ ] 后端接入阿里云 OCR SDK，提供 /api/ocr/recognize
- [ ] 后端收藏表与 API
- [ ] 前端浏览器 Tab + 屏幕抓取框选 + OCR + 插入 WPS/收藏

### 设计决策记录
- OCR：后端（效果优先），使用阿里云 OCR（参考：
  - `https://api.aliyun.com/api-tools/sdk/ocr?version=&language=java&tab=primer-doc`
  - `https://help.aliyun.com/zh/ocr/getting-started/use-process`
 ）
- 配置：通过 `/api/admin/config` 读写 `system_setting`，保持与现有外部服务（WPS/企查查/Tushare/Gemini）一致。


