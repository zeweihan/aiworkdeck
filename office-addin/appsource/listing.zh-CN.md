# 商店列表文案 — zh-CN（简体中文）

逐块粘进 Partner Center → **Marketplace listings** → `Chinese (Simplified)`。
字数上限引自
[Create effective listings](https://learn.microsoft.com/partner-center/marketplace-offers/create-effective-office-store-listings)
的「Apply guidelines for name and description length」表；微软的上限按**字符**数，
中文一个字算一个字符。

zh-CN 是清单的 `DefaultLocale`，所以这份是主列表；en-US 那份见 `listing.en-US.md`。

---

## Name（名称）

> 上限 50 字符，建议 30 以内；必须与清单 `DisplayName` 相同或高度相似。

```
AI WorkDeck
```

11 字符，与 `manifest.xml` 的 `<DisplayName DefaultValue="AI WorkDeck">` 逐字一致。
中文列表同样用英文品牌名——品牌名不翻译，商标写法固定为 AI WorkDeck（大写 D）。

---

## Summary（摘要 / 搜索结果短描述）

> 上限 100 字符，建议 70 以内，关键信息放前 30 字符。

```
在 Word、Excel、PowerPoint 里用 AI 起草、改红线、审阅文件，答案基于你自己的项目资料。
```

55 字符。开头就是「在 Word、Excel、PowerPoint 里用 AI 起」，先说能干什么、不复述品牌名。

---

## Description（长描述）

> 长度表给的上限是 **10,000 字符**，同一页正文又写「maximum length for descriptions is 4,000
> characters」，两处冲突——统一按 4,000 控制。支持 HTML，Partner Center 没有预览，粘贴前先在
> HTML 编辑器里看一遍。
>
> 政策 1100.1 与提交前清单都要求**描述正文自身**披露「需要另行注册账户」「AI 用量需要额外付费」
> 并给出获取链接。下面「安装前须知」那一段就是这份披露，不要删减。

```html
<p>AI WorkDeck 把 AI 起草与审阅助手装进 Word、Excel、PowerPoint 的任务窗格。它读你正打开的这份文档，在修订模式下直接改，在批注里回复，并结合你项目里的其他文件回答问题——你看到的是一份可逐条接受或拒绝的红线稿，而不必把正文复制进聊天框、再把结果贴回来。</p>

<p>面向每天在 Office 里干活的律师与企业法务。</p>

<h3>能做什么</h3>
<ul>
  <li><strong>直接在文档里改红线。</strong>用日常语言提要求，改动以修订形式落进当前文档，像同事改的一样可以逐条接受或拒绝。</li>
  <li><strong>把批注一条条处理掉。</strong>助手读完文档里的每条批注，按批注要求改对应的正文，再在批注串里回复说明改了什么。</li>
  <li><strong>长文档分段校对。</strong>错别字、前后不一致的定义术语、拗口的表述，在不改变原意的前提下逐段处理，界面上能看到读到第几段。</li>
  <li><strong>答案落在你自己的材料上。</strong>可以附上项目里的文件，也可以上传本地文件，跨文件提问。</li>
  <li><strong>Excel 与 PowerPoint 同样可用。</strong>表格面可读写区域、公式、数字格式、筛选、图表与数据透视表；演示面可改正文、表格、形状与页序。</li>
</ul>

<h3>安装前须知：账户与付费</h3>
<p>本加载项是 AI WorkDeck 服务的 Office 客户端，不能独立使用。</p>
<ul>
  <li><strong>需要 AI WorkDeck 账户。</strong>在任务窗格内用手机号或邮箱＋验证码登录。账户可在 <a href="https://www.workdeck.ai">https://www.workdeck.ai</a> 免费注册。</li>
  <li><strong>AI 用量需要额外付费。</strong>每次 AI 请求都会扣减账户里的 Credits，余额用尽后 AI 功能停止工作。Credits 在 <a href="https://www.workdeck.ai">https://www.workdeck.ai</a> 的账户页充值。加载项本身免费安装、内部没有收银台，只在账户菜单里显示剩余额度，需要充值时跳转到官网。</li>
</ul>
<p>服务条款：<a href="https://www.aiworkdeck.com/legal/terms">https://www.aiworkdeck.com/legal/terms</a>；隐私政策：<a href="https://www.aiworkdeck.com/legal/privacy">https://www.aiworkdeck.com/legal/privacy</a>。</p>

<h3>支持的应用</h3>
<p>Word、Excel、PowerPoint 的 Windows 版、Mac 版与网页版。界面有中文与英文两种，跟随 Office 的显示语言。</p>

<h3>文档怎么被处理</h3>
<p>你正打开的文档正文，以及你附上的文件，会发送到 AI WorkDeck 服务用于回答与改写。不发消息就不会发送任何内容。改动以修订形式写回文档，改了什么始终由你看得见、控制得住。保存范围与保存期限见上面的隐私政策。</p>

<h3>技术支持</h3>
<p><a href="https://www.workdeck.ai">https://www.workdeck.ai</a></p>
```

**账户与充值链接为什么跟英文列表一样指向 workdeck.ai**

一个 AppSource offer 只能传一份包，而「连哪个后端」是构建期焊进包里的
（`vite.config.js` 的 `__ADDIN_DEFAULT_SERVER__`）。本次提交的包托管在 `addin.workdeck.ai`、
后端也是它，所以中文列表里的注册/充值入口必须一并指向 workdeck.ai——写成 aiworkdeck.com
会让中文用户在国内站注册完、回到任务窗格发现登不进去。
（国内站用户拿插件的路子不是 AppSource，是官网下载页的 DMG/EXE 安装器。）

**法务链接则相反**：中文版隐私政策/服务条款实际托在 `www.aiworkdeck.com/legal/*`
（`www.workdeck.ai/legal/privacy` 会 301 跳到这里，2026-09-16 实测），所以中文列表直接写
最终地址，英文列表写 `/en/legal/*`。

---

## Search keywords（搜索关键词）

```
法律 AI
合同审查
修订 红线
文档起草
律师
```

---

## Categories / Industries

与 en-US 同一套（Partner Center 的分类是按 offer 设的，不分语言），见 `listing.en-US.md`。

---

## Video（可选）

首次提交不做。
