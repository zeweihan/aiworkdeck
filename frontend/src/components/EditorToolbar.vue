<template>
  <view class="etb" @tap="closeMenus">
    <!-- 主命令区：窄了就横向滚动，不换行（换行会把画布挤下去） -->
    <scroll-view class="etb-scroll" scroll-x>
      <view class="etb-row">
        <!-- 撤销 / 重做 -->
        <view class="etb-btn" :class="{ off: undoDisabled }" title="撤销 (Cmd/Ctrl+Z)" @tap.stop="run('undo')">
          <svg class="etb-ico" viewBox="0 0 24 24" fill="none"><path v-for="(d,i) in ICONS.undo" :key="i" :d="d" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"/></svg>
        </view>
        <view class="etb-btn" :class="{ off: redoDisabled }" title="重做 (Cmd/Ctrl+Shift+Z)" @tap.stop="run('redo')">
          <svg class="etb-ico" viewBox="0 0 24 24" fill="none"><path v-for="(d,i) in ICONS.redo" :key="i" :d="d" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"/></svg>
        </view>
        <view class="etb-sep"></view>

        <!-- 段落样式 -->
        <view class="etb-drop" :class="{ open: menu === 'style' }">
          <view class="etb-field w110" title="段落样式" @tap.stop="toggleMenu('style')">
            <text class="etb-field-t">{{ styleLabel }}</text>
            <text class="etb-caret">⌄</text>
          </view>
          <scroll-view v-if="menu === 'style'" class="etb-menu w160" scroll-y @tap.stop>
            <view v-for="s in styleOptions" :key="s.name" class="etb-item"
                  :class="{ on: s.name === state.paragraph.styleName }" @tap.stop="applyStyle(s.name)">
              <text class="etb-item-t">{{ s.label }}</text>
            </view>
          </scroll-view>
        </view>

        <!-- 字体 -->
        <view class="etb-drop" :class="{ open: menu === 'font' }">
          <view class="etb-field w110" title="字体" @tap.stop="toggleMenu('font')">
            <text class="etb-field-t">{{ fontLabel }}</text>
            <text class="etb-caret">⌄</text>
          </view>
          <scroll-view v-if="menu === 'font'" class="etb-menu w180" scroll-y @tap.stop>
            <view v-for="f in fontOptions" :key="f" class="etb-item"
                  :class="{ on: f === state.character.font }" @tap.stop="applyFont(f)">
              <text class="etb-item-t">{{ f }}</text>
            </view>
          </scroll-view>
        </view>

        <!-- 字号：引擎的 .uno:Grow/Shrink 是哑弹，这里读回当前值自己步进 -->
        <view class="etb-stepper" title="字号">
          <text class="etb-step-b" @tap.stop="stepSize(-1)">−</text>
          <text class="etb-step-v">{{ sizeLabel }}</text>
          <text class="etb-step-b" @tap.stop="stepSize(1)">+</text>
        </view>
        <view class="etb-sep"></view>

        <!-- 字符格式 -->
        <view class="etb-btn" :class="{ on: state.character.bold }" title="加粗 (Cmd/Ctrl+B)" @tap.stop="ui('bold')"><text class="etb-tx b">B</text></view>
        <view class="etb-btn" :class="{ on: state.character.italic }" title="倾斜 (Cmd/Ctrl+I)" @tap.stop="ui('italic')"><text class="etb-tx i">I</text></view>
        <view class="etb-btn" :class="{ on: state.character.underline }" title="下划线 (Cmd/Ctrl+U)" @tap.stop="ui('underline')"><text class="etb-tx u">U</text></view>
        <view class="etb-btn" :class="{ on: state.character.strikeout }" title="删除线" @tap.stop="ui('strikeout')"><text class="etb-tx s">S</text></view>
        <view class="etb-btn" :class="{ on: state.character.superscript }" title="上标" @tap.stop="ui('superscript')"><text class="etb-tx">X²</text></view>
        <view class="etb-btn" :class="{ on: state.character.subscript }" title="下标" @tap.stop="ui('subscript')"><text class="etb-tx">X₂</text></view>

        <!-- 字色 / 高亮 -->
        <view class="etb-drop" :class="{ open: menu === 'color' }">
          <view class="etb-btn" title="字体颜色" @tap.stop="toggleMenu('color')">
            <text class="etb-tx">A</text>
            <view class="etb-swatch" :style="{ background: state.character.color === 'auto' ? '#2C3338' : state.character.color }"></view>
          </view>
          <view v-if="menu === 'color'" class="etb-palette" @tap.stop>
            <view v-for="c in TEXT_COLORS" :key="c.v" class="etb-chip" :style="{ background: c.v === 'auto' ? '#2C3338' : c.v }"
                  :title="c.t" @tap.stop="applyColor('color', c.v)"></view>
          </view>
        </view>
        <view class="etb-drop" :class="{ open: menu === 'hl' }">
          <view class="etb-btn" title="突出显示" @tap.stop="toggleMenu('hl')">
            <svg class="etb-ico" viewBox="0 0 24 24" fill="none"><path v-for="(d,i) in ICONS.marker" :key="i" :d="d" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"/></svg>
            <view class="etb-swatch" :style="{ background: state.character.highlight === 'none' ? 'transparent' : state.character.highlight }"></view>
          </view>
          <view v-if="menu === 'hl'" class="etb-palette" @tap.stop>
            <view v-for="c in HL_COLORS" :key="c.v" class="etb-chip" :class="{ none: c.v === 'none' }"
                  :style="{ background: c.v === 'none' ? '#fff' : c.v }" :title="c.t" @tap.stop="applyColor('highlight', c.v)"></view>
          </view>
        </view>
        <view class="etb-btn" title="清除格式" @tap.stop="ui('clear_formatting')">
          <svg class="etb-ico" viewBox="0 0 24 24" fill="none"><path v-for="(d,i) in ICONS.clear" :key="i" :d="d" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"/></svg>
        </view>
        <view class="etb-sep"></view>

        <!-- 段落 -->
        <view v-for="a in ALIGNS" :key="a.k" class="etb-btn" :class="{ on: state.paragraph.alignment === a.k }"
              :title="a.t" @tap.stop="ui(a.cmd)">
          <svg class="etb-ico" viewBox="0 0 24 24" fill="none"><path v-for="(d,i) in ICONS[a.icon]" :key="i" :d="d" stroke="currentColor" stroke-width="1.7" stroke-linecap="round"/></svg>
        </view>
        <view class="etb-btn" :class="{ on: state.paragraph.listKind === 'bullet' }" title="项目符号" @tap.stop="ui('bullet_list')">
          <svg class="etb-ico" viewBox="0 0 24 24" fill="none"><path v-for="(d,i) in ICONS.bullet" :key="i" :d="d" stroke="currentColor" stroke-width="1.7" stroke-linecap="round"/></svg>
        </view>
        <view class="etb-btn" :class="{ on: state.paragraph.listKind === 'number' }" title="编号列表" @tap.stop="ui('number_list')">
          <svg class="etb-ico" viewBox="0 0 24 24" fill="none"><path v-for="(d,i) in ICONS.number" :key="i" :d="d" stroke="currentColor" stroke-width="1.7" stroke-linecap="round"/></svg>
        </view>
        <view class="etb-btn" title="减少缩进" @tap.stop="ui('indent_less')">
          <svg class="etb-ico" viewBox="0 0 24 24" fill="none"><path v-for="(d,i) in ICONS.outdent" :key="i" :d="d" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"/></svg>
        </view>
        <view class="etb-btn" title="增加缩进" @tap.stop="ui('indent_more')">
          <svg class="etb-ico" viewBox="0 0 24 24" fill="none"><path v-for="(d,i) in ICONS.indent" :key="i" :d="d" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"/></svg>
        </view>
        <view class="etb-sep"></view>
        <view class="etb-btn" title="插入分页符" @tap.stop="ui('page_break')">
          <svg class="etb-ico" viewBox="0 0 24 24" fill="none"><path v-for="(d,i) in ICONS.pagebreak" :key="i" :d="d" stroke="currentColor" stroke-width="1.7" stroke-linecap="round"/></svg>
        </view>
        <view class="etb-btn" :class="{ on: formattingMarks }" title="显示格式标记" @tap.stop="toggleMarks">
          <text class="etb-tx">¶</text>
        </view>
      </view>
    </scroll-view>

    <!-- 右侧常驻区：不参与滚动 -->
    <view class="etb-right">
      <view class="etb-btn wide" :class="{ on: state.view.recordChanges }" title="记录修订" @tap.stop="toggleTrack">
        <text class="etb-tx sm">修订</text>
      </view>
      <view class="etb-btn wide" :class="{ on: reviewOpen }" title="审阅面板" @tap.stop="$emit('toggle-review')">
        <text class="etb-tx sm">审阅</text>
      </view>
      <view class="etb-stepper" title="显示比例">
        <text class="etb-step-b" @tap.stop="stepZoom(-10)">−</text>
        <text class="etb-step-v z" @tap.stop="resetZoom">{{ Math.round(state.view.zoom || 100) }}%</text>
        <text class="etb-step-b" @tap.stop="stepZoom(10)">+</text>
      </view>
    </view>
  </view>
</template>

<script>
// EditorToolbar.vue — 自建编辑器工具栏（P2）。
//
// WHY: LibreOffice 自己画的菜单栏/工具栏是引擎渲染在 canvas 上的，观感老旧，
// 菜单栏末端还挂着一个会把文档关掉的 ×。方案定的最终形态是自建工具栏，LO 的
// chrome 退场（docs/superpowers/specs/2026-08-14-editor-chrome-self-built-toolbar.md）。
//
// 本阶段（P2a）**只加不减**：工具栏挂上去，LO chrome 原样保留。用户可以两套并存
// 地用，功能覆盖到位了再谈隐藏——「体验不能退步」是硬约束，不能先砍后补。
//
// 每个按钮都落到 worker 的一个 action 上（P1 命令层）：
//   ui_command（.uno: 白名单）/ format_selection / set_style / set_zoom /
//   set_track_changes / undo / redo。
// **不放做不到的按钮**：`.uno:Grow`/`.uno:Shrink` 在本引擎是哑弹（派发成功但
// 字号纹丝不动），所以字号步进是读回当前值再写 fontSize，不是派发那两个槽。
//
// 激活态刷新（get_ui_state，实测 ~6ms）由三路信号驱动，见 refreshKey：
//   1) worker 的 XSelectionChangeListener（选区类变化）
//   2) IME 覆盖层的 onCursorMoved（纯光标移动/画布点击——上面那条盖不住）
//   3) 本组件自己发完命令之后
// 没有轮询：以上三路已经覆盖了用户能让光标动起来的所有途径。

const ICONS = {
  undo: ['M9 14 4 9l5-5', 'M4 9h10a6 6 0 0 1 0 12h-3'],
  redo: ['M15 14l5-5-5-5', 'M20 9H10a6 6 0 0 0 0 12h3'],
  marker: ['M4 20h5', 'M9 16l-3 4', 'M12.5 4.5l7 7-6 6-7-7z', 'M10 17l-3 3'],
  clear: ['M6 5h13', 'M9 5l6 14', 'M4 19h7'],
  alignLeft: ['M4 6h16', 'M4 10h10', 'M4 14h16', 'M4 18h10'],
  alignCenter: ['M4 6h16', 'M7 10h10', 'M4 14h16', 'M7 18h10'],
  alignRight: ['M4 6h16', 'M10 10h10', 'M4 14h16', 'M10 18h10'],
  alignJustify: ['M4 6h16', 'M4 10h16', 'M4 14h16', 'M4 18h16'],
  bullet: ['M9 6h11', 'M9 12h11', 'M9 18h11', 'M4.5 6h.01', 'M4.5 12h.01', 'M4.5 18h.01'],
  number: ['M10 6h10', 'M10 12h10', 'M10 18h10', 'M4 5h1v4', 'M4 9h2', 'M4 15h2v2H4v2h2'],
  indent: ['M4 6h16', 'M10 10h10', 'M10 14h10', 'M4 18h16', 'M4 10l3 2-3 2'],
  outdent: ['M4 6h16', 'M10 10h10', 'M10 14h10', 'M4 18h16', 'M7 10l-3 2 3 2'],
  pagebreak: ['M6 4h12', 'M6 20h12', 'M3 12h4', 'M10 12h4', 'M17 12h4'],
}
const ALIGNS = [
  { k: 'left', cmd: 'align_left', t: '左对齐', icon: 'alignLeft' },
  { k: 'center', cmd: 'align_center', t: '居中', icon: 'alignCenter' },
  { k: 'right', cmd: 'align_right', t: '右对齐', icon: 'alignRight' },
  { k: 'justify', cmd: 'align_justify', t: '两端对齐', icon: 'alignJustify' },
]
const TEXT_COLORS = [
  { v: 'auto', t: '自动' }, { v: '#C0392B', t: '红' }, { v: '#1A5336', t: '墨绿' },
  { v: '#1D4ED8', t: '蓝' }, { v: '#B45309', t: '棕' }, { v: '#6B21A8', t: '紫' },
  { v: '#495057', t: '深灰' }, { v: '#868E96', t: '灰' },
]
const HL_COLORS = [
  { v: 'none', t: '无' }, { v: 'yellow', t: '黄' }, { v: 'green', t: '绿' },
  { v: 'cyan', t: '青' }, { v: 'magenta', t: '品红' }, { v: 'gray', t: '灰' },
]
// LO 的样式**程序名**是英文（Standard / Heading 1），DisplayName 在部分构建上
// 也回英文。常用几条自备中文名兜底，引擎给了中文就用引擎的。
const STYLE_ZH = {
  'Standard': '正文', 'Default Paragraph Style': '正文', 'Text body': '正文文本',
  'Heading': '标题', 'Heading 1': '标题 1', 'Heading 2': '标题 2', 'Heading 3': '标题 3',
  'Heading 4': '标题 4', 'Heading 5': '标题 5', 'Heading 6': '标题 6',
  'Title': '文档标题', 'Subtitle': '副标题', 'Quotations': '引用', 'List': '列表',
  'Caption': '题注', 'First line indent': '首行缩进', 'Hanging indent': '悬挂缩进',
  'Signature': '署名', 'Salutation': '称谓',
}
// 样式下拉只列律师真会用的这些（126 条全塞进去没人找得到）；当前段落用的样式
// 若不在表里也会被补进列表，不至于「显示的样式选不回来」。
const STYLE_PICKS = [
  'Standard', 'Text body', 'Title', 'Subtitle',
  'Heading 1', 'Heading 2', 'Heading 3', 'Heading 4',
  'Quotations', 'List', 'Caption', 'First line indent',
]
const SIZES = [8, 9, 10, 10.5, 11, 12, 14, 16, 18, 20, 22, 24, 26, 28, 36, 48, 72]

const EMPTY = () => ({ character: {}, paragraph: {}, view: {}, selection: {}, undo: null })

export default {
  name: 'EditorToolbar',
  emits: ['toggle-review', 'changed'],
  props: {
    // LibreOffice executor（executeCommand(action, params)）。null 时整条静默。
    executor: { type: Object, default: null },
    // 宿主在「选区/光标动了」「文档改了」时自增，驱动激活态刷新。
    refreshKey: { type: Number, default: 0 },
    reviewOpen: { type: Boolean, default: false },
  },
  data() {
    return { state: EMPTY(), styleList: [], fontList: [], menu: '', formattingMarks: false }
  },
  computed: {
    ICONS: () => ICONS, ALIGNS: () => ALIGNS,
    TEXT_COLORS: () => TEXT_COLORS, HL_COLORS: () => HL_COLORS,
    // 读不到撤销可用性时**不置灰**——宁可多点一下，也不要把能用的功能锁死
    undoDisabled() { return this.state.undo ? this.state.undo.canUndo === false : false },
    redoDisabled() { return this.state.undo ? this.state.undo.canRedo === false : false },
    styleLabel() { return this.zhStyle(this.state.paragraph.styleName || '正文') },
    fontLabel() {
      const f = this.state.character.font || ''
      return f.length > 8 ? f.slice(0, 8) + '…' : (f || '字体')
    },
    sizeLabel() {
      const s = this.state.character.sizePt
      return s ? (Math.round(s * 10) / 10) : '—'
    },
    styleOptions() {
      const have = {}
      for (const s of this.styleList) have[s.name] = s
      const picks = STYLE_PICKS.filter((n) => have[n])
      const cur = this.state.paragraph.styleName
      if (cur && picks.indexOf(cur) === -1 && have[cur]) picks.unshift(cur)
      return picks.map((n) => ({ name: n, label: this.zhStyle(n, have[n] && have[n].display) }))
    },
    // 中文字体排前面——这是给中国律师用的编辑器，别让他们在 300 个西文字体里翻
    fontOptions() {
      const cjk = [], rest = []
      for (const f of this.fontList) (/[一-龥]/.test(f) ? cjk : rest).push(f)
      return cjk.concat(rest)
    },
  },
  watch: {
    executor: { handler() { this.bootstrap() }, immediate: true },
    refreshKey() { this.refresh() },
  },
  methods: {
    async call(action, params) {
      if (!this.executor) return null
      try { return await this.executor.executeCommand(action, params || {}) }
      catch (e) { return { success: false, message: (e && e.message) || String(e) } }
    },
    async bootstrap() {
      this.state = EMPTY()
      if (!this.executor) return
      await this.refresh()
      const [st, fo] = await Promise.all([this.call('list_styles', {}), this.call('list_fonts', {})])
      this.styleList = (st && st.styles) || []
      this.fontList = (fo && fo.families) || []
    },
    async refresh() {
      const r = await this.call('get_ui_state', {})
      if (r && r.success) this.state = r
    },
    // 命令跑完必须刷新两件事：工具栏自己的激活态，以及宿主的自动保存（改了文档）
    async after(res, changed) {
      if (changed !== false) this.$emit('changed')
      await this.refresh()
      return res
    },
    ui(name) { this.closeMenus(); return this.call('ui_command', { name }).then((r) => this.after(r)) },
    run(action) { this.closeMenus(); return this.call(action, {}).then((r) => this.after(r)) },
    toggleMenu(name) { this.menu = this.menu === name ? '' : name },
    closeMenus() { this.menu = '' },
    zhStyle(name, display) {
      if (!name) return ''
      if (STYLE_ZH[name]) return STYLE_ZH[name]
      if (display && /[一-龥]/.test(display)) return display
      return display || name
    },
    applyStyle(name) {
      this.closeMenus()
      return this.call('set_style', { kind: 'paragraph', styleName: name }).then((r) => this.after(r))
    },
    applyFont(family) {
      this.closeMenus()
      return this.call('format_selection', { fontName: family }).then((r) => this.after(r))
    },
    applyColor(kind, value) {
      this.closeMenus()
      return this.call('format_selection', { [kind]: value }).then((r) => this.after(r))
    },
    // 字号步进：本引擎的 .uno:Grow/.uno:Shrink 是哑弹，只能读回当前值再写。
    // 按常用字号表跳档，而不是 ±1——12→14→16 才是人的用法。
    stepSize(dir) {
      const cur = Number(this.state.character.sizePt) || 12
      let idx = SIZES.findIndex((s) => s >= cur - 0.01)
      if (idx < 0) idx = SIZES.length - 1
      if (dir > 0 && SIZES[idx] <= cur + 0.01) idx++
      else if (dir < 0) idx--
      const next = SIZES[Math.max(0, Math.min(SIZES.length - 1, idx))]
      if (!next || Math.abs(next - cur) < 0.01) return null
      return this.call('format_selection', { fontSize: next }).then((r) => this.after(r))
    },
    stepZoom(delta) {
      return this.call('set_zoom', { delta }).then((r) => this.after(r, false))
    },
    resetZoom() { return this.call('set_zoom', { value: 100 }).then((r) => this.after(r, false)) },
    toggleTrack() {
      const next = !this.state.view.recordChanges
      return this.call('set_track_changes', { on: next }).then((r) => this.after(r, false))
    },
    // 格式标记是纯视图开关，引擎不回报状态，本地记一份
    toggleMarks() {
      this.formattingMarks = !this.formattingMarks
      return this.call('ui_command', { name: 'formatting_marks' }).then((r) => this.after(r, false))
    },
  },
}
</script>

<style scoped>
.etb { display: flex; align-items: center; gap: 6px; height: 38px; padding: 0 8px; flex-shrink: 0;
  background: #FBFCFD; border-bottom: 1px solid #E9ECEF; }
.etb-scroll { flex: 1; min-width: 0; white-space: nowrap; }
.etb-row { display: flex; align-items: center; gap: 2px; }
.etb-right { display: flex; align-items: center; gap: 4px; flex-shrink: 0; padding-left: 6px;
  border-left: 1px solid #E9ECEF; }
.etb-sep { width: 1px; height: 18px; background: #E9ECEF; margin: 0 5px; flex-shrink: 0; }

.etb-btn { position: relative; display: flex; align-items: center; justify-content: center; gap: 3px;
  min-width: 26px; height: 26px; padding: 0 5px; border-radius: 5px; color: #495057; flex-shrink: 0; }
.etb-btn:hover { background: #F1F3F5; }
.etb-btn.on { background: #E6F9F0; color: #1A5336; }
.etb-btn.off { color: #CED4DA; }
.etb-btn.wide { padding: 0 9px; }
.etb-ico { width: 16px; height: 16px; display: block; }
.etb-tx { font-size: 14px; line-height: 1; }
.etb-tx.sm { font-size: 12px; }
.etb-tx.b { font-weight: 700; }
.etb-tx.i { font-style: italic; font-family: Georgia, serif; }
.etb-tx.u { text-decoration: underline; }
.etb-tx.s { text-decoration: line-through; }
.etb-swatch { position: absolute; left: 4px; right: 4px; bottom: 3px; height: 3px; border-radius: 2px;
  border: 1px solid rgba(0, 0, 0, 0.08); }

.etb-field { display: flex; align-items: center; justify-content: space-between; gap: 4px; height: 26px;
  padding: 0 6px; border: 1px solid #DEE2E6; border-radius: 5px; background: #fff; }
.etb-field:hover { border-color: #ADB5BD; }
.etb-field-t { font-size: 12px; color: #2C3338; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.etb-caret { font-size: 11px; color: #ADB5BD; }
.w110 { width: 110px; }

.etb-drop { position: relative; flex-shrink: 0; }
.etb-menu { position: absolute; top: 30px; left: 0; z-index: 40; max-height: 280px;
  padding: 4px; background: #fff; border: 1px solid #E9ECEF; border-radius: 8px;
  box-shadow: 0 6px 20px rgba(15, 23, 42, 0.12); }
.w160 { width: 160px; }
.w180 { width: 180px; }
.etb-item { padding: 5px 8px; border-radius: 5px; }
.etb-item:hover { background: #F1F3F5; }
.etb-item.on { background: #E6F9F0; }
.etb-item-t { font-size: 12px; color: #2C3338; }

.etb-palette { position: absolute; top: 30px; left: 0; z-index: 40; display: flex; flex-wrap: wrap; gap: 5px;
  width: 128px; padding: 7px; background: #fff; border: 1px solid #E9ECEF; border-radius: 8px;
  box-shadow: 0 6px 20px rgba(15, 23, 42, 0.12); }
.etb-chip { width: 18px; height: 18px; border-radius: 4px; border: 1px solid rgba(0, 0, 0, 0.12); }
.etb-chip.none { position: relative; }
.etb-chip.none::after { content: ''; position: absolute; left: 1px; right: 1px; top: 8px; height: 1px;
  background: #C0392B; transform: rotate(-45deg); }

.etb-stepper { display: flex; align-items: center; height: 26px; border: 1px solid #DEE2E6; border-radius: 5px;
  background: #fff; flex-shrink: 0; }
.etb-step-b { width: 20px; text-align: center; font-size: 14px; color: #868E96; line-height: 24px; }
.etb-step-b:hover { color: #1A5336; background: #F1F3F5; }
.etb-step-v { min-width: 34px; text-align: center; font-size: 12px; color: #2C3338; }
.etb-step-v.z { min-width: 42px; }
</style>
