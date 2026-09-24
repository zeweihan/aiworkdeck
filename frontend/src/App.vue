<!-- SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors -->
<!-- SPDX-License-Identifier: AGPL-3.0-or-later -->
<script>
import { getSessionId } from '@/utils/auth.js'
import { openLocalRootPath, openLocalFilePath } from '@/utils/ideOpen.js'
import { track } from '@/utils/telemetryClient.js'
import { host, isDesktopHost } from '@/services/host.js'
import { mountFeedbackWidget } from '@/utils/feedbackWidget.js'
import { mountRecordingIndicator } from '@/utils/recordingIndicator.js'
import { initWindowChrome, refreshDragStrip } from '@/utils/windowChrome.js'
import { mountGlobalBack, refreshGlobalBack } from '@/utils/globalBack.js'
import { initAppMenuBridge } from '@/utils/appMenuBridge.js'
import { getAppLanguage, APP_LANGUAGE_EVENT } from '@/utils/appLanguage.js'
import { initAppTheme } from '@/utils/appTheme.js'
import { saveAppLanguageRemote } from '@/services/api.js'

export default {
  onLaunch: function () {
    console.log('App Launch')
    // 无边框窗口适配：挂平台/全屏 class + 页面级拖拽条。最早做，
    // 免得启动页先渲染出来再跳一下（见 utils/windowChrome.js）。
    initWindowChrome()
    // 外观主题：也要最早——晚一步首屏会先白一下再变深（见 utils/appTheme.js）
    initAppTheme()
    // 应用语言：最早读一次（首启在此完成猜测并持久化），并把镜像写透到
    // 桌面主进程（菜单等原生文案）与后端 system_setting（prompt/文案语言）。
    // 之后语言切换（设置页 setAppLanguage）经 APP_LANGUAGE_EVENT 走同一条同步链。
    const syncLanguageMirrors = (lang) => {
      try { if (host.appLanguage && host.appLanguage.set) host.appLanguage.set(lang) } catch (e) { /* ignore */ }
      // fire-and-forget：后端没起来也不能拦启动，下次切换/启动会再补。
      // 浏览器态未登录时不发——POST 会回「未登录」，request 包装器据此清会话
      // 并 reLaunch 登录页，启动链会被这条后台同步搅乱；桌面 local-mode 恒可发。
      if (isDesktopHost() || getSessionId()) {
        try { saveAppLanguageRemote(lang).catch(() => {}) } catch (e) { /* ignore */ }
      }
    }
    syncLanguageMirrors(getAppLanguage())
    try { uni.$on(APP_LANGUAGE_EVENT, syncLanguageMirrors) } catch (e) { /* ignore */ }
    // 常驻反馈浮窗：挂在页面树之外，全应用一个实例（见 utils/feedbackWidget.js）
    mountFeedbackWidget()
    // 会议「录音中」浮动指示器：同一模式，录音时才显形（见 utils/recordingIndicator.js）
    mountRecordingIndicator()
    // 全局返回键：页面栈深度 > 1 时出现在顶部拖拽条里（见 utils/globalBack.js）
    mountGlobalBack()
    // 埋点：页面路由唯一收口（全仓 50 处 navigateTo/reLaunch 直调，拦截器一处全覆盖）；
    // 只记页面路径枚举（pages.json 里的 13 个页面，2026-08-08 三级导航加了
    // project-list 与 project-home 两页，2026-08-20 加了 calendar 全局日历页，
    // 2026-08-27 首启向导页下线），query 参数不采集
    const navTrack = (routeType) => ({
      invoke(args) {
        try {
          const page = String((args && args.url) || '').split('?')[0]
          if (page) track('ui.nav', { page, branch: routeType })
        } catch (e) { /* 静默 */ }
        return true
      },
      // 全局返回键的可见性跟着页面栈走，跳转完成后重算一次（含 navigateBack，
      // 它没有 url、不参与埋点，但会改变栈深度）。
      // 无边框窗口的拖拽条同理：自带顶栏的页面要整条让开，否则那一页顶栏里的
      // 按钮全部点不动（见 utils/windowChrome.js 的 OWN_TITLEBAR_ROUTES）。
      complete() { refreshGlobalBack(); refreshDragStrip() },
    })
    ;['navigateTo', 'redirectTo', 'reLaunch', 'switchTab', 'navigateBack'].forEach((t) => {
      try { uni.addInterceptor(t, navTrack(t)) } catch (e) { /* 静默 */ }
    })
    // 应用菜单：命令表 → 菜单树的下发与动作派发全部收在 appMenuBridge 里。
    // App 级注册一次，天然避开 project-overview 的页面栈多实例问题。
    initAppMenuBridge()
    // Dock/访达「打开方式」进来的路径不是菜单命令，主进程直发，单独接。
    if (host.menu && host.menu.onAction) {
      host.menu.onAction(async (data) => {
        if (!data || data.action !== 'open-path' || !data.path) return
        if (!getSessionId()) {
          uni.showToast({ title: this.$t('shell.pleaseLoginFirst'), icon: 'none' })
          return
        }
        try {
          if (data.isDirectory) await openLocalRootPath(data.path)
          else await openLocalFilePath(data.path)
        } catch (e) {
          uni.showToast({ title: (e && e.message) || this.$t('shell.menuActionFailed'), icon: 'none' })
        }
      })
    }
    // IDE 化：拖一个文件夹到窗口任意位置 = 打开为项目（capture 段拦截，
    // 避免文件上传等既有 drop 区把「文件夹」误当文件收走；单个目录才接管）
    if (host.fs && host.fs.getPathForFile) {
      window.addEventListener('dragover', (e) => { e.preventDefault() }, false)
      window.addEventListener('drop', (e) => {
        try {
          // 落点在文件树里的一律让给文件树（dev-board#363：拖文件夹进目录节点 = 上传进去，
          // 不是「打开为项目」）；窗口其余位置照旧接管。
          const t = e.target
          if (t && typeof t.closest === 'function' && t.closest('.file-tree')) return
          const items = e.dataTransfer && e.dataTransfer.items
          if (!items || items.length !== 1) return
          const entry = items[0].webkitGetAsEntry && items[0].webkitGetAsEntry()
          if (!entry || !entry.isDirectory) return
          e.preventDefault()
          e.stopPropagation()
          const path = host.fs.getPathForFile(e.dataTransfer.files[0])
          if (!path) return
          if (!getSessionId()) {
            uni.showToast({ title: this.$t('shell.pleaseLoginFirst'), icon: 'none' })
            return
          }
          uni.showModal({
            title: this.$t('shell.openFolderTitle'),
            content: this.$t('shell.openFolderConfirm', { name: entry.name }),
            confirmText: this.$t('shell.open'),
            success: (r) => {
              if (r.confirm) {
                openLocalRootPath(path).catch((err) => {
                  uni.showToast({ title: (err && err.message) || this.$t('shell.openFolderFailed'), icon: 'none' })
                })
              }
            },
          })
        } catch (err) {
          console.warn('folder drop failed:', err)
        }
      }, true)
    }
  },
  onShow: function () {
    console.log('App Show')
  },
  onHide: function () {
    console.log('App Hide')
  },
}
</script>

<style>
/* AI WorkDeck Global Overrides */

/* ============ 全局字体（dev-board#849） ============
   此前全仓没有 html/body 字体规则：页面里的组件各自带字体没露馅，挂在 document.body
   上的 uni 弹层（<uni-toast>，以及接管前的 <uni-modal>）却直接掉到浏览器默认衬线字。
   字栈与 uni.scss 的 $awd-font-serif / $awd-font-sans / $awd-font-mono 逐字一致
   （字体不属于配色体系，不进 generate-tokens / check-palette，改一处要手动同步另一处）。 */
:root {
    --awd-font-serif: 'Noto Serif SC', 'Source Han Serif SC', 'Songti SC', 'STSong', 'SimSun', Georgia, serif;
    --awd-font-sans: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, 'PingFang SC', 'Hiragino Sans GB', 'Microsoft YaHei', sans-serif;
    --awd-font-mono: 'JetBrains Mono', 'SF Mono', SFMono-Regular, Menlo, Consolas, 'Courier New', monospace;
}

html,
body,
uni-modal,
uni-toast {
    font-family: var(--awd-font-sans);
}

/* ============ 颜色语义令牌（浅色/深色两套取值） ============
   改造前全前端有 3950 处硬编码颜色、435 种色值，文字/背景/边框各自混着四套
   并行灰阶（Bootstrap 的 #6C757D/#ADB5BD、Tailwind slate 的 #64748B/#94A3B8、
   Tailwind gray 的 #6B7280/#D1D5DB，外加 #333/#666/#999 遗留）。收敛成下面这
   一套语义令牌：**按用途取名，不按色值取名**——深色模式只是同一批名字换一组值。

   用 CSS 自定义属性而非 scss 变量：各组件的 <style scoped> 有的写 scss 有的写
   纯 css，自定义属性两边都能用、天然穿透 scoped，且能在运行时整体切换。

   切换机制：utils/theme.js 在 documentElement 上挂 data-theme="light|dark"
   （「跟随系统」解析成其中之一后再挂，页面里不出现第三种状态）。

   **下面两个 AWD-TOKENS 区段由脚本生成，不要手改。**
   色源是 design/tokens/awd-palette.json（三个仓各存一份逐字节相同的副本），
   改配色只改那里，然后跑 node scripts/generate-tokens.mjs 重新生成；
   scripts/check-palette.mjs 在 CI 里对拍 sha256、令牌漂移与对比度。 */
html,
html[data-theme='light'] {
    /* AWD-TOKENS:BEGIN app-light */
    /* 此段由 scripts/generate-tokens.mjs 生成，勿手改。色源：design/tokens/awd-palette.json */
    /* 表面：底色 → 卡片 → 悬停/凹陷 → 更重的填充 */
    --awd-bg: #F1EFE7;
    --awd-surface: #FCFBF7;
    --awd-surface-2: #E9E6DC;
    --awd-surface-3: #DFDBCE;
    /* 文字三阶 + 强调底上的反白 */
    --awd-text: #2B2A26;
    --awd-text-2: #6B675C;
    --awd-text-3: #A8A296;
    --awd-text-on-accent: #FFFFFF;
    /* 边框三阶 */
    --awd-border-subtle: #EAE7DD;
    --awd-border: #DDD8CA;
    --awd-border-strong: #C3BCA9;
    /* 品牌墨竹青：accent 作底、accent-text 作字（深色下两者取值不同，必须分开） */
    --awd-accent: #2E5A50;
    --awd-accent-hover: #254A41;
    --awd-accent-text: #2E5A50;
    --awd-accent-soft: #E3EDE9;
    --awd-accent-wash: rgba(46, 90, 80, 0.05);
    /* 氛围色竹月青：大面积浅底/选中/悬停/图表/装饰线，对白底仅 2.6:1，不承载正文与按钮文字。
       --awd-mint 是插件 SDK 的公开契约名（utils/appTheme.js 的 THEME_TOKEN_NAMES →
       PluginPane 注入第三方插件 iframe，插件 CSS 里写着 var(--awd-mint)），改名会静默
       打破它们，所以名字保留、只换值；--awd-bamboo 是同值别名，新代码用语义正确的这个。 */
    --awd-mint: #89A8A0;
    --awd-bamboo: #89A8A0;
    --awd-text-on-mint: #1E3A33;
    /* 浅茶金点睛：分隔金线 / 引用块 / 徽章 / 空状态描边。对白底 1.7:1，不做大面积底色、
       不承载任何信息层级；需要茶金文字时一律用 gold-text。 */
    --awd-gold: #D7C5A1;
    --awd-gold-line: #C9B48A;
    --awd-gold-text: #7E6439;
    --awd-gold-soft: #F5EFE2;
    /* 语义状态 */
    --awd-danger: #B5483C;
    --awd-danger-text: #9A3A30;
    --awd-danger-soft: #FAF0EE;
    --awd-warning: #B8842B;
    --awd-warning-text: #8A6320;
    --awd-warning-soft: #F8F1E2;
    --awd-info: #3C5A73;
    --awd-info-text: #2F4A60;
    --awd-info-soft: #EDF1F4;
    /* 阴影与遮罩 */
    --awd-shadow-sm: 0 1px 2px rgba(58, 52, 40, 0.06);
    --awd-shadow-md: 0 4px 16px rgba(58, 52, 40, 0.08);
    --awd-shadow-lg: 0 12px 32px rgba(58, 52, 40, 0.16);
    --awd-overlay: rgba(35, 32, 26, 0.45);
    /* 空态光晕（工作区/首屏那圈柔光）与整页斜向柔光的落点色 */
    --awd-halo-1: #E3EDE9;
    --awd-halo-2: rgba(46, 90, 80, 0.04);
    --awd-halo-page: #E3EDE9;
    /* 毛玻璃表面（登录页那种半透明卡片） */
    --awd-glass: rgba(252, 251, 247, 0.75);
    --awd-glass-border: rgba(255, 255, 255, 0.5);
    /* 编辑器纸外工作区（纸张本身由 LOWA 引擎渲染，永远是纸白，不参与主题） */
    --awd-canvas: #E4E0D4;
    /* AWD-TOKENS:END app-light */
    /* 文件类型色（dev-board#504）：工作台标签页的图标与激活指示条按文件类型着色。
       前四种沿用各自宿主软件的既有认知（Word 蓝 / PPT 橙 / Excel 绿 / PDF 红），
       Markdown 用中性灰（它是纯文本，不属于任何一家），图片取紫——六个色相里
       只有紫既离前五种最远、又不是任何办公套件的品牌色，一眼能认出「这不是文档」。
       这六个是宿主软件的官方品牌色，是识别线索不是装饰，**不随配色体系变化**，
       所以刻意留在生成区段之外（色源 awd-palette.json 的 frozen 块同此约定）。 */
    --awd-file-word: #185ABD;
    --awd-file-ppt: #C43E1C;
    --awd-file-excel: #1D6F42;
    --awd-file-pdf: #D93025;
    --awd-file-md: #6B7280;
    --awd-file-image: #7C3AED;
    color-scheme: light;
}

html[data-theme='dark'] {
    /* 阶差要比浅色更舍得拉开：深色下人眼对低亮度差极不敏感，页面与卡片只差
       3-4 个灰阶时卡片会整个"沉进"背景里（第一版 #17191C/#1E2125 就是这样，
       维护者实测反馈「卡片边框也看不见」）。现在页面最深、卡片明显抬起、
       边框独立成一档，三者互相认得出。

       东方清雅体系下深色是**暖墨调**（不是中性灰）：深档跟着玉脂白那支暖相走，
       与浅色模式同源。accent 底仍是压深的墨竹青，反白文字语义与浅色一致；
       绿字直接用墨竹青在深底上读不出来，accent-text 换成竹月青本色
       （对 --awd-bg 7.0:1，由 check-palette.mjs 的 dark/accent-text-on-bg 钉着）。
       空态光晕不沿用浅色的带色雾——半透明色铺在深底上会糊成一团脏雾，
       改成不带色相的极淡提亮。 */
    /* AWD-TOKENS:BEGIN app-dark */
    /* 此段由 scripts/generate-tokens.mjs 生成，勿手改。色源：design/tokens/awd-palette.json */
    /* 表面：底色 → 卡片 → 悬停/凹陷 → 更重的填充 */
    --awd-bg: #191713;
    --awd-surface: #221F1A;
    --awd-surface-2: #2B2721;
    --awd-surface-3: #363129;
    /* 文字三阶 + 强调底上的反白 */
    --awd-text: #EDE9DF;
    --awd-text-2: #B0AA9C;
    --awd-text-3: #7D7768;
    --awd-text-on-accent: #FFFFFF;
    /* 边框三阶 */
    --awd-border-subtle: #2B2721;
    --awd-border: #3D372E;
    --awd-border-strong: #524B3F;
    /* 品牌墨竹青：accent 作底、accent-text 作字（深色下两者取值不同，必须分开） */
    --awd-accent: #3E6E62;
    --awd-accent-hover: #4C8576;
    --awd-accent-text: #89A8A0;
    --awd-accent-soft: rgba(137, 168, 160, 0.14);
    --awd-accent-wash: rgba(137, 168, 160, 0.06);
    /* 氛围色竹月青：大面积浅底/选中/悬停/图表/装饰线，对白底仅 2.6:1，不承载正文与按钮文字。
       --awd-mint 是插件 SDK 的公开契约名（utils/appTheme.js 的 THEME_TOKEN_NAMES →
       PluginPane 注入第三方插件 iframe，插件 CSS 里写着 var(--awd-mint)），改名会静默
       打破它们，所以名字保留、只换值；--awd-bamboo 是同值别名，新代码用语义正确的这个。 */
    --awd-mint: #89A8A0;
    --awd-bamboo: #89A8A0;
    --awd-text-on-mint: #1E3A33;
    /* 浅茶金点睛：分隔金线 / 引用块 / 徽章 / 空状态描边。对白底 1.7:1，不做大面积底色、
       不承载任何信息层级；需要茶金文字时一律用 gold-text。 */
    --awd-gold: #D7C5A1;
    --awd-gold-line: #8A7A5C;
    --awd-gold-text: #D7C5A1;
    --awd-gold-soft: rgba(215, 197, 161, 0.13);
    /* 语义状态 */
    --awd-danger: #CC6154;
    --awd-danger-text: #E8938A;
    --awd-danger-soft: rgba(181, 72, 60, 0.16);
    --awd-warning: #D19C3D;
    --awd-warning-text: #E0B461;
    --awd-warning-soft: rgba(184, 132, 43, 0.15);
    --awd-info: #5E8299;
    --awd-info-text: #93B3C6;
    --awd-info-soft: rgba(60, 90, 115, 0.18);
    /* 阴影与遮罩 */
    --awd-shadow-sm: 0 1px 2px rgba(0, 0, 0, 0.4);
    --awd-shadow-md: 0 4px 16px rgba(0, 0, 0, 0.45);
    --awd-shadow-lg: 0 12px 32px rgba(0, 0, 0, 0.55);
    --awd-overlay: rgba(0, 0, 0, 0.6);
    /* 空态光晕（工作区/首屏那圈柔光）与整页斜向柔光的落点色 */
    --awd-halo-1: rgba(255, 255, 255, 0.045);
    --awd-halo-2: rgba(255, 255, 255, 0);
    --awd-halo-page: #1D1A16;
    /* 毛玻璃表面（登录页那种半透明卡片） */
    --awd-glass: rgba(34, 31, 26, 0.74);
    --awd-glass-border: rgba(255, 255, 255, 0.10);
    /* 编辑器纸外工作区（纸张本身由 LOWA 引擎渲染，永远是纸白，不参与主题） */
    --awd-canvas: #141210;
    /* AWD-TOKENS:END app-dark */
    /* 文件类型色：浅色那六个是「铺在白底上」调的深色，直接搬到深底上一律读不出来，
       统一提亮到对 --awd-surface 至少 4.5:1。色相不动，认知不变。
       同浅色，这六个不随配色体系变化，留在生成区段之外。 */
    --awd-file-word: #6FA8FF;
    --awd-file-ppt: #FF9470;
    --awd-file-excel: #4CC38A;
    --awd-file-pdf: #FF8A80;
    --awd-file-md: #A6ADB4;
    --awd-file-image: #A78BFA;
    color-scheme: dark;
}

/* 深色下的品牌字标：logo 是深墨字 + 绿标记的透明 PNG，铺在深底上几乎看不见。
   invert + hue-rotate 把明度翻过来、把色相转回去：墨字变浅、绿仍是绿。
   注意作用对象——uni-image 在 H5 下真正显示的是那个 background-image 的 div，
   同级的 <img> 只是隐藏的加载器，只给 img 加滤镜是没有效果的（实测踩过）。 */
html[data-theme='dark'] .awd-brand-logo > div,
html[data-theme='dark'] .awd-brand-logo > img {
    filter: invert(1) hue-rotate(180deg) saturate(1.35) brightness(1.1);
}

/* ============ 无边框窗口：给系统窗口控件让位 ============
   桌面壳去掉了系统标题栏（main.js titleBarStyle: 'hidden'），窗口控件浮在
   网页内容上。类名由 utils/windowChrome.js 挂在 documentElement 上。
   设计见 docs/superpowers/specs/2026-08-16-desktop-chrome-and-command-menu.md */

/* 选择器一律写成 html.is-xxx：组件的 scoped 样式带 [data-v-] 属性选择器，
   跟 `.is-mac .project-header` 同权重（0,2,0），而它注入得更晚会赢。
   加上元素选择器变成 (0,2,1) 才压得住 project-overview.scss 的 `padding: 0 18px`。 */

/* ---- 保留区变量：给「让位」一个不打权重官司的表达 ----
   抬权重只赢一轮。`.compact-mode .project-header { padding: 0 16px }` 是 (0,4,0)，
   照样把上面那条 (0,2,1) 的 88px 让位整条吃掉——窗口窄于 1360px 就必然被交通灯
   压住项目名。逐页抬权重是个追不完的坑，所以改成变量：
     · 语义是**距窗口边缘的绝对位置**——顶栏内容的左缘不得早于这个值；
     · 页面在**自己的样式表里**用 `padding-left: max(自己的边距, var(...))` 消费它，
       跟自己的 padding 简写同属一处，不存在谁压谁；
     · 非 mac / 全屏 / 浏览器版下变量是 0，max() 自动退回页面原本的边距。
   只对「紧贴窗口左缘的顶栏」成立；带外边距的页面（admin/个人中心/项目列表都是
   `padding: 40px 24px`）内容本来就落在交通灯下方，不需要让位。 */
html {
    --awd-titlebar-safe-inline-start: 0px;
    --awd-titlebar-safe-inline-end: 0px;
}

/* ---- 左栏面板密度令牌 ----
   左栏那一列宽度只有 260px，每个面板却各写各的边距（EasyVoice 是 16px 页边距 +
   24px 段间距 + 白卡片套白卡片，插件广场是 6-10px + 26px 分组头），并排看密度差一倍。
   基准取插件广场（MarketSidebarPanel）那一套——它是维护者点过名的形态。

   用 CSS 自定义属性而不是 scss 变量，是因为各面板的 `<style scoped>` 有的写 scss
   有的写纯 css，自定义属性两边都能用、且天然穿透 scoped。 */
html {
    --awd-panel-pad-x: 10px;          /* 面板内容左右边距 */
    --awd-panel-gap: 8px;             /* 同组元素间距 */
    --awd-panel-gap-lg: 14px;         /* 跨组间距（不要再用 24px） */
    --awd-panel-sec-h: 26px;          /* 分组头行高 */
    --awd-panel-row-h: 28px;          /* 输入框/按钮等单行控件高度 */
    --awd-panel-radius: 6px;
    --awd-panel-fs-sec: 11px;         /* 分组头字号（配 700 字重） */
    --awd-panel-fs: 12px;             /* 行文字号 */
    --awd-panel-fs-meta: 11px;        /* 次要信息 */
    /* 颜色一律转发到上面的语义令牌，左栏因此自动跟随主题。
       text-2/-3/-4 三阶在浅色下原本是 #495057/#868E96/#ADB5BD，收敛到统一灰阶后
       只剩两阶可用（text-2 与 text-3 合并），差别肉眼几乎不可辨。 */
    --awd-panel-border: var(--awd-border);
    --awd-panel-hover: var(--awd-surface-2);
    --awd-panel-text: var(--awd-text);
    --awd-panel-text-2: var(--awd-text-2);
    --awd-panel-text-3: var(--awd-text-2);
    --awd-panel-text-4: var(--awd-text-3);
    --awd-panel-accent: var(--awd-accent-text);
    --awd-panel-accent-2: var(--awd-mint);
    --awd-panel-accent-wash: var(--awd-accent-wash);
}

/* ---- 悬浮细滑轨（.awd-hairline-scroll，dev-board#543） ----
   VS Code 式：静止时只有轨道没有滑块（thumb 透明），鼠标进到容器里才显形。
   给横向可滚的窄条（编辑器工具栏主命令区 .etb-scroll、编辑器标签栏 .tabs-scroll）
   用——原生 15px 滚动条会把 38px 的一行撑成 41px（dev-board#502），而整条藏掉
   又等于把「这里还能往右滚」这件事从界面上抹掉（#543 复发的正是这一步）。

   写成全局类而不是各自 scoped：uni-h5 的 <scroll-view> 真正 overflow 的是内层
   那个 div.uni-scroll-view（组件的 scope id 只落在 <uni-scroll-view> 根元素上），
   scoped 样式够不着它，非要写就得逐处 :deep 复制一份。

   4px：两处宿主各自按这个数补偿高度（.etb-scroll 的 calc(100% - 8px)/-4px、
   .tabs-scroll 的 calc(100% + 4px)），改这里要连带改那两处。

   Chromium 一旦看到 scrollbar-width，就整个忽略 ::-webkit-scrollbar 那套，4px 的
   确定高度会退化成 thin（约 8px），把按上面 4px 算好的居中偏移带歪。所以标准属性
   只留给没有 webkit 伪元素的浏览器。 */
.awd-hairline-scroll::-webkit-scrollbar,
.awd-hairline-scroll ::-webkit-scrollbar {
    width: 4px;
    height: 4px;
}
.awd-hairline-scroll::-webkit-scrollbar-track,
.awd-hairline-scroll ::-webkit-scrollbar-track {
    background: transparent;
}
.awd-hairline-scroll::-webkit-scrollbar-thumb,
.awd-hairline-scroll ::-webkit-scrollbar-thumb {
    background: transparent;
    border-radius: 999px;
}
.awd-hairline-scroll:hover::-webkit-scrollbar-thumb,
.awd-hairline-scroll:hover ::-webkit-scrollbar-thumb {
    background: var(--awd-border-strong);
}
@supports not selector(::-webkit-scrollbar) {
    .awd-hairline-scroll,
    .awd-hairline-scroll .uni-scroll-view {
        scrollbar-width: thin;
        scrollbar-color: transparent transparent;
    }
    .awd-hairline-scroll:hover,
    .awd-hairline-scroll:hover .uni-scroll-view {
        scrollbar-color: var(--awd-border-strong) transparent;
    }
}
/* mac：三颗交通灯占住左上角（右缘约 70px，留 18px 呼吸） */
html.is-mac {
    --awd-titlebar-safe-inline-start: 88px;
}
/* 全屏时交通灯隐藏，保留区归零 */
html.is-mac.is-fullscreen {
    --awd-titlebar-safe-inline-start: 0px;
}
/* win：右上角原生最小化/最大化/关闭 */
html.is-win {
    --awd-titlebar-safe-inline-end: 148px;
}
/* win：非最大化时应用边界与浅色资源管理器（#F8F9FA 页面、#FFFFFF 顶栏）几乎同色
   （dev-board#722），描 1px 内框区分开；最大化/全屏时系统本身就有窗口边界，不描。
   用 outline + 负 outline-offset，不用 inset box-shadow：body/页面根节点（100vh、
   不透明背景）是 html 的子节点，会按绘制顺序盖在父节点的 box-shadow 上面，实测
   inset box-shadow 打在 html 上完全不可见；outline 不受这个绘制层级限制，能穿透
   子节点的不透明背景显示（同样已用无头浏览器验证：加了它不产生滚动条、不改
   scrollHeight）。不占布局空间，也不跟内容产生额外间距。配色红线：只用既有的
   --awd-border-strong 令牌，不新增硬编码颜色，深色主题沿用该令牌已有的深色取值。 */
html.is-win:not(.is-maximized):not(.is-fullscreen) {
    outline: 1px solid var(--awd-border-strong);
    outline-offset: -1px;
}
/* 顶栏下边框同样提到 --awd-border-strong，跟窗口描边一个基调，避免描边窗口里
   顶栏下面那条更浅的 --awd-border 显得突兀。选择器权重高于 project-overview.scss
   里的 `.project-header { border-bottom: ... }`（(0,1,1)），同一份文档里就能压住。 */
html.is-win:not(.is-maximized):not(.is-fullscreen) .project-header {
    border-bottom-color: var(--awd-border-strong);
}

/* 工作台顶栏就是标题栏：空白处可拖动窗口 */
html.is-desktop .project-header {
    -webkit-app-region: drag;
}

/* 顶栏里每一个能点的东西都必须显式退出拖拽区。
   漏掉一个，那个按钮就点不动——这是本段最容易出错的地方，加控件时记得同步。 */
html.is-desktop .project-header .project-name,
html.is-desktop .project-header .rename-container,
html.is-desktop .project-header .rename-input,
html.is-desktop .project-header .project-switcher,
html.is-desktop .project-header .switcher-mask,
html.is-desktop .project-header .switcher-menu,
html.is-desktop .project-header .project-status-badge,
html.is-desktop .project-header .work-status-chip,
html.is-desktop .project-header .collab-chip,
html.is-desktop .project-header .trial-chip,
html.is-desktop .project-header .header-center,
html.is-desktop .project-header .header-tools,
html.is-desktop .project-header .top-bar-btn,
html.is-desktop .project-header .icon-btn,
/* 右上角头像（2026-08-19 从 rail 底部搬上来；2026-08-27 下拉恢复成两项：
   设置 / 退出登录，dev-board#205——菜单与全屏 mask 都要能点）。 */
html.is-desktop .project-header .header-account,
html.is-desktop .project-header .avatar-btn,
html.is-desktop .project-header .avatar-menu,
html.is-desktop .project-header .avatar-menu-mask {
    -webkit-app-region: no-drag;
}

/* 全屏浮层退出拖拽区（B4 / 0907 清单 B10）。

   工作台顶栏那 42px 自己就是标题栏（上面那条 drag）。拖拽区是壳按 app-region
   另算的一套，**不受 z-index 与 DOM 命中管**（见 utils/windowChrome.js 的长注释）：
   任何 `position: fixed; inset: 0` 的浮层打开后，它盖在顶栏上的那一条仍然是 drag，
   于是点那一条想关掉浮层，DOM 里收不到 click、浮层不关；用户再点一下——两次落在
   标题栏上的点击就是 macOS 的「双击标题栏 = 缩放」，AppKit 自己把窗口撑成整块工作区
   （实测 1920×962 = workAreaSize）。全仓没有任何改主窗口尺寸的代码
   （desktop/tests/main-window-bounds.test.js 钉着），撑窗口的是 macOS。

   fixed 盒子恒排在常规流之后合成，所以这里的 no-drag 抠洞会赢过顶栏的 drag
   （.awd-global-back 一直好用，同一个道理）。浮层铺满视口，连带保护了它里面的
   菜单面板，不必逐个再列。

   **新增全屏浮层要加进这张名单**；frontend/tests/window-chrome/titlebar-drag-region.test.mjs
   会扫出漏掉的那个。真不吃鼠标事件的层（pointer-events: none）才进那份 EXEMPT。 */
html.is-desktop .amb-mask,
html.is-desktop .awd-dialog-mask,
html.is-desktop .awd-dlg-mask,
html.is-desktop .awd-mask,
html.is-desktop .awd-select-mask,
html.is-desktop .awd-toast-mask,
html.is-desktop .awdfb-mask,
html.is-desktop .batch-menu-mask,
html.is-desktop .ch-mask,
html.is-desktop .compare-dialog-mask,
html.is-desktop .context-menu-mask,
html.is-desktop .cp-mask,
html.is-desktop .dd-dialog-mask,
html.is-desktop .dialog-overlay,
html.is-desktop .dlp-mask,
html.is-desktop .dock-menu-mask,
html.is-desktop .dropdown-fixed-mask,
html.is-desktop .dropdown-mask,
html.is-desktop .file-picker-mask,
html.is-desktop .filelink-mask,
html.is-desktop .ihc-mask,
html.is-desktop .image-preview-mask,
html.is-desktop .memory-mask,
html.is-desktop .modal-mask,
html.is-desktop .model-mask,
html.is-desktop .mr-dialog-mask,
html.is-desktop .msg-act-mask,
html.is-desktop .naming-mask,
html.is-desktop .ocd-mask,
html.is-desktop .ocr-overlay,
html.is-desktop .popup-mask-transparent,
html.is-desktop .qo-mask,
html.is-desktop .sm-dialog-mask,
html.is-desktop .task-dialog-mask,
html.is-desktop .theme-menu-mask,
html.is-desktop .upload-mask,
html.is-desktop .webmark-drag-overlay,
html.is-desktop .workdeck-dialog-mask {
    -webkit-app-region: no-drag;
}

/* 工作台顶栏与项目概览顶栏的让位写在各自的样式表里（消费上面那两个变量），
   不在这里写——它们都有自己的 padding 简写，写在这里就要打权重官司。
   见 pages/project-overview/project-overview.scss 与
   pages/project-home/project-home.scss 里的 `max(…, var(--awd-titlebar-safe-*))`。 */

/* 没有自己顶栏的页面（项目列表、设置、个人中心…）的拖拽条。

   **自带顶栏的几页由 JS 把它整条藏掉**（utils/windowChrome.js 的
   OWN_TITLEBAR_ROUTES）。原先靠 z-index 让工作台顶栏「盖在它上面」是错的：
   z-index 只管画谁在上面和 DOM 事件命中，而窗口拖拽区是壳按 app-region 另算
   的一套，fixed 的这条带子永远最后合成，会把它底下所有 no-drag 抠洞盖回成
   可拖，于是顶栏里的按钮一个都点不动（v0.18.0 的顶栏死区）。 */
.awd-window-drag-strip {
    display: none;
}
html.is-desktop .awd-window-drag-strip {
    display: block;
    position: fixed;
    top: 0;
    left: 0;
    right: 0;
    height: 38px;
    -webkit-app-region: drag;
    z-index: 1;
}

/* 全局返回键（utils/globalBack.js）。落在拖拽条这条空白带里，紧挨着保留区右缘——
   mac 上就是交通灯右手边，Finder/Safari 里返回键该在的位置。
   z-index 高于拖拽条一档；no-drag 必须写，否则整条带子是 drag 区、按钮点不动。
   display 由 JS 在 inline-flex / none 之间切，这里不要写 display。 */
.awd-global-back {
    position: fixed;
    top: 5px;
    left: max(12px, var(--awd-titlebar-safe-inline-start));
    z-index: 2;
    align-items: center;
    gap: 4px;
    height: 28px;
    padding: 0 11px 0 8px;
    border: 1px solid var(--awd-border);
    border-radius: 14px;
    background: var(--awd-surface);
    color: var(--awd-accent-text);
    font-size: 12px;
    line-height: 1;
    cursor: pointer;
    user-select: none;
    -webkit-app-region: no-drag;
    box-shadow: var(--awd-shadow-md);
    transition: box-shadow 0.15s ease, border-color 0.15s ease;
}

.awd-global-back:hover {
    border-color: var(--awd-mint);
    box-shadow: var(--awd-shadow-lg);
}

/* ---- 逐页让位 ----
   不做全局 padding 注入——13 个页面布局差异太大，全局注入必然出回归。
   走查方法与结论见 spec §3.4；改这几个页面的顶部结构时记得回来看一眼。

   走查结论（mac）：紧贴窗口左上角、会被交通灯压住的只有五处——
   工作台 .project-header、项目概览 .home-topbar（这两处的让位写在各自的样式表
   里，见上）、login .top-nav、variable-library .header-card、plugin-market .hero。
   其余页面（admin / 个人中心 / 项目列表 / newproject）根容器都是
   `padding: 40px 24px`，内容起点落在交通灯下方，不需要让位。
   注意窄窗口：让位一律用 max(自己的边距, var(--awd-titlebar-safe-*)) 表达，
   不要写死像素——写死的那版在全屏下会留一段莫名其妙的缩进。 */

/* login：整条 top-nav 就是这一页的标题栏 */
html.is-desktop .top-nav {
    -webkit-app-region: drag;
}
html.is-desktop .top-nav .nav-item,
html.is-desktop .top-nav .nav-logo {
    -webkit-app-region: no-drag;
}
html.is-desktop .top-nav {
    padding-left: max(48px, var(--awd-titlebar-safe-inline-start));
    padding-right: max(48px, var(--awd-titlebar-safe-inline-end));
}

/* project-home 薄壳页：这条 52px 顶栏就是这一页的标题栏。
   它在 OWN_TITLEBAR_ROUTES 里，body 级拖拽条会让开，拖拽由它自己承担；
   两个出口按钮照例要显式退出拖拽区，否则又是一对点不动的按钮。 */
html.is-desktop .home-topbar {
    -webkit-app-region: drag;
}
html.is-desktop .home-topbar .home-back,
html.is-desktop .home-topbar .home-enter {
    -webkit-app-region: no-drag;
}

/* variable-library：顶部卡片里的项目名紧贴左边 */
html.is-desktop .page-variable-library .header-card {
    padding-left: max(8px, var(--awd-titlebar-safe-inline-start));
}

/* plugin-market：hero 的分类标签在最上一行 */
html.is-desktop .page-plugin-market .hero {
    padding-top: 38px;
}
</style>
