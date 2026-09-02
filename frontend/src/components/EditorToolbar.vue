<template>
  <view class="etb-wrap">
  <view class="etb" @tap="closeMenus">
    <!-- 主命令区：窄了就横向滚动，不换行（换行会把画布挤下去） -->
    <scroll-view class="etb-scroll" scroll-x>
      <view class="etb-row">
        <!-- 撤销 / 重做 -->
        <view class="etb-btn" :class="{ off: undoDisabled }" :title="$t('editor.toolbar.undo')" @tap.stop="run('undo')">
          <svg class="etb-ico" viewBox="0 0 24 24" fill="none"><path v-for="(d,i) in ICONS.undo" :key="i" :d="d" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"/></svg>
        </view>
        <view class="etb-btn" :class="{ off: redoDisabled }" :title="$t('editor.toolbar.redo')" @tap.stop="run('redo')">
          <svg class="etb-ico" viewBox="0 0 24 24" fill="none"><path v-for="(d,i) in ICONS.redo" :key="i" :d="d" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"/></svg>
        </view>
        <view class="etb-sep"></view>

        <!-- 段落样式 -->
        <view class="etb-drop" :class="{ open: menu === 'style' }">
          <view ref="trig_style" class="etb-field w110" :title="$t('editor.toolbar.paraStyle')" @tap.stop="toggleMenu('style')">
            <text class="etb-field-t">{{ styleLabel }}</text>
            <text class="etb-caret">⌄</text>
          </view>
          <scroll-view v-if="menu === 'style'" class="etb-menu w160" :style="popStyle(160)" scroll-y @tap.stop>
            <view v-for="s in styleOptions" :key="s.name" class="etb-item"
                  :class="{ on: s.name === state.paragraph.styleName }" @tap.stop="applyStyle(s.name)">
              <text class="etb-item-t">{{ s.label }}</text>
            </view>
          </scroll-view>
        </view>

        <!-- 字体 -->
        <view class="etb-drop" :class="{ open: menu === 'font' }">
          <view ref="trig_font" class="etb-field w110" :title="$t('editor.toolbar.font')" @tap.stop="toggleMenu('font')">
            <text class="etb-field-t">{{ fontLabel }}</text>
            <text class="etb-caret">⌄</text>
          </view>
          <scroll-view v-if="menu === 'font'" class="etb-menu w180" :style="popStyle(180)" scroll-y @tap.stop>
            <view v-for="f in fontOptions" :key="f" class="etb-item"
                  :class="{ on: f === state.character.font }" @tap.stop="applyFont(f)">
              <text class="etb-item-t">{{ f }}</text>
            </view>
          </scroll-view>
        </view>

        <!-- 字号：引擎的 .uno:Grow/Shrink 是哑弹，这里读回当前值自己步进 -->
        <view class="etb-stepper" :title="$t('editor.toolbar.fontSize')">
          <text class="etb-step-b" @tap.stop="stepSize(-1)">−</text>
          <text class="etb-step-v">{{ sizeLabel }}</text>
          <text class="etb-step-b" @tap.stop="stepSize(1)">+</text>
        </view>
        <view class="etb-sep"></view>

        <!-- 字符格式 -->
        <view class="etb-btn" :class="{ on: state.character.bold }" :title="$t('editor.toolbar.bold')" @tap.stop="ui('bold')"><text class="etb-tx b">B</text></view>
        <view class="etb-btn" :class="{ on: state.character.italic }" :title="$t('editor.toolbar.italic')" @tap.stop="ui('italic')"><text class="etb-tx i">I</text></view>
        <view class="etb-btn" :class="{ on: state.character.underline }" :title="$t('editor.toolbar.underline')" @tap.stop="ui('underline')"><text class="etb-tx u">U</text></view>
        <view class="etb-btn" :class="{ on: state.character.strikeout }" :title="$t('editor.toolbar.strikeout')" @tap.stop="ui('strikeout')"><text class="etb-tx s">S</text></view>
        <view class="etb-btn" :class="{ on: state.character.superscript }" :title="$t('editor.toolbar.superscript')" @tap.stop="ui('superscript')"><text class="etb-tx">X²</text></view>
        <view class="etb-btn" :class="{ on: state.character.subscript }" :title="$t('editor.toolbar.subscript')" @tap.stop="ui('subscript')"><text class="etb-tx">X₂</text></view>

        <!-- 字色 / 高亮 -->
        <view class="etb-drop" :class="{ open: menu === 'color' }">
          <view ref="trig_color" class="etb-btn" :title="$t('editor.toolbar.textColor')" @tap.stop="toggleMenu('color')">
            <text class="etb-tx">A</text>
            <view class="etb-swatch" :style="{ background: state.character.color === 'auto' ? '#2C3338' : state.character.color }"></view>
          </view>
          <view v-if="menu === 'color'" class="etb-palette" :style="popStyle(128)" @tap.stop>
            <view v-for="c in TEXT_COLORS" :key="c.v" class="etb-chip" :style="{ background: c.v === 'auto' ? '#2C3338' : c.v }"
                  :title="$t('editor.toolbar.colors.' + c.t)" @tap.stop="applyColor('color', c.v)"></view>
          </view>
        </view>
        <view class="etb-drop" :class="{ open: menu === 'hl' }">
          <view ref="trig_hl" class="etb-btn" :title="$t('editor.toolbar.highlight')" @tap.stop="toggleMenu('hl')">
            <svg class="etb-ico" viewBox="0 0 24 24" fill="none"><path v-for="(d,i) in ICONS.marker" :key="i" :d="d" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"/></svg>
            <view class="etb-swatch" :style="{ background: state.character.highlight === 'none' ? 'transparent' : state.character.highlight }"></view>
          </view>
          <view v-if="menu === 'hl'" class="etb-palette" :style="popStyle(128)" @tap.stop>
            <view v-for="c in HL_COLORS" :key="c.v" class="etb-chip" :class="{ none: c.v === 'none' }"
                  :style="{ background: c.v === 'none' ? '#fff' : c.v }" :title="$t('editor.toolbar.colors.' + c.t)" @tap.stop="applyColor('highlight', c.v)"></view>
          </view>
        </view>
        <view class="etb-btn" :title="$t('editor.toolbar.clearFormatting')" @tap.stop="ui('clear_formatting')">
          <svg class="etb-ico" viewBox="0 0 24 24" fill="none"><path v-for="(d,i) in ICONS.clear" :key="i" :d="d" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"/></svg>
        </view>
        <view class="etb-sep"></view>

        <!-- 段落 -->
        <view v-for="a in ALIGNS" :key="a.k" class="etb-btn" :class="{ on: state.paragraph.alignment === a.k }"
              :title="$t('editor.toolbar.' + a.t)" @tap.stop="ui(a.cmd)">
          <svg class="etb-ico" viewBox="0 0 24 24" fill="none"><path v-for="(d,i) in ICONS[a.icon]" :key="i" :d="d" stroke="currentColor" stroke-width="1.7" stroke-linecap="round"/></svg>
        </view>
        <view class="etb-btn" :class="{ on: state.paragraph.listKind === 'bullet' }" :title="$t('editor.toolbar.bulletList')" @tap.stop="ui('bullet_list')">
          <svg class="etb-ico" viewBox="0 0 24 24" fill="none"><path v-for="(d,i) in ICONS.bullet" :key="i" :d="d" stroke="currentColor" stroke-width="1.7" stroke-linecap="round"/></svg>
        </view>
        <view class="etb-btn" :class="{ on: state.paragraph.listKind === 'number' }" :title="$t('editor.toolbar.numberList')" @tap.stop="ui('number_list')">
          <svg class="etb-ico" viewBox="0 0 24 24" fill="none"><path v-for="(d,i) in ICONS.number" :key="i" :d="d" stroke="currentColor" stroke-width="1.7" stroke-linecap="round"/></svg>
        </view>
        <view class="etb-btn" :title="$t('editor.toolbar.indentLess')" @tap.stop="ui('indent_less')">
          <svg class="etb-ico" viewBox="0 0 24 24" fill="none"><path v-for="(d,i) in ICONS.outdent" :key="i" :d="d" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"/></svg>
        </view>
        <view class="etb-btn" :title="$t('editor.toolbar.indentMore')" @tap.stop="ui('indent_more')">
          <svg class="etb-ico" viewBox="0 0 24 24" fill="none"><path v-for="(d,i) in ICONS.indent" :key="i" :d="d" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"/></svg>
        </view>
        <view class="etb-sep"></view>

        <!-- 插入 -->
        <view class="etb-drop" :class="{ open: menu === 'insert' }">
          <view ref="trig_insert" class="etb-field w72" :title="$t('editor.toolbar.insert')" @tap.stop="openInsert">
            <text class="etb-field-t">{{ $t('editor.toolbar.insert') }}</text>
            <text class="etb-caret">⌄</text>
          </view>
          <view v-if="menu === 'insert'" class="etb-menu w200 pad" :style="popStyle(200)" @tap.stop>
            <!-- 一级清单 -->
            <template v-if="!insertMode">
              <view class="etb-item" @tap.stop="insertMode = 'table'"><text class="etb-item-t">{{ $t('editor.toolbar.insertTable') }}</text></view>
              <view class="etb-item" @tap.stop="pickImage"><text class="etb-item-t">{{ $t('editor.toolbar.insertImage') }}</text></view>
              <view class="etb-item" :class="{ dim: noSelection }" @tap.stop="startLink">
                <text class="etb-item-t">{{ $t('editor.toolbar.insertLink') }}</text>
                <text v-if="noSelection" class="etb-hint">{{ $t('editor.toolbar.needSelection') }}</text>
              </view>
              <view class="etb-item" :class="{ dim: noSelection }" @tap.stop="startComment">
                <text class="etb-item-t">{{ $t('editor.toolbar.insertComment') }}</text>
                <text v-if="noSelection" class="etb-hint">{{ $t('editor.toolbar.needSelection') }}</text>
              </view>
              <view class="etb-item" @tap.stop="ui('page_break')"><text class="etb-item-t">{{ $t('editor.toolbar.insertPageBreak') }}</text></view>
              <view class="etb-item" @tap.stop="startText('footnote')"><text class="etb-item-t">{{ $t('editor.toolbar.insertFootnote') }}</text></view>
              <view class="etb-item" @tap.stop="startText('header')"><text class="etb-item-t">{{ $t('editor.toolbar.editHeader') }}</text></view>
              <view class="etb-item" @tap.stop="startText('footer')"><text class="etb-item-t">{{ $t('editor.toolbar.editFooter') }}</text></view>
            </template>

            <!-- 脚注 / 尾注 / 页眉 / 页脚：同一个单行文本表单 -->
            <view v-else-if="TEXT_FORMS[insertMode]" class="etb-form">
              <text class="etb-form-t">{{ $t('editor.toolbar.' + TEXT_FORMS[insertMode].title) }}</text>
              <textarea class="etb-input ta" v-model="formText"
                        :placeholder="$t('editor.toolbar.' + TEXT_FORMS[insertMode].ph)" @click.stop />
              <view class="etb-form-acts">
                <text class="etb-form-b" @tap.stop="insertMode = ''">{{ $t('editor.toolbar.cancel') }}</text>
                <text class="etb-form-b ok" @tap.stop="doTextForm">{{ $t('editor.toolbar.confirm') }}</text>
              </view>
            </view>

            <!-- 表格：网格选择器 -->
            <view v-else-if="insertMode === 'table'" class="etb-form">
              <text class="etb-form-t">{{ grid.r ? $t('editor.toolbar.gridSize', { rows: grid.r, cols: grid.c }) : $t('editor.toolbar.gridPrompt') }}</text>
              <view class="etb-grid">
                <view v-for="cell in GRID_CELLS" :key="cell.k" class="etb-cell"
                      :class="{ hot: cell.r <= grid.r && cell.c <= grid.c }"
                      @mouseenter="grid = { r: cell.r, c: cell.c }" @tap.stop="doInsertTable(cell.r, cell.c)"></view>
              </view>
              <view class="etb-form-acts"><text class="etb-form-b" @tap.stop="insertMode = ''">{{ $t('editor.toolbar.cancel') }}</text></view>
            </view>

            <!-- 超链接 -->
            <view v-else-if="insertMode === 'link'" class="etb-form">
              <text class="etb-form-t">{{ $t('editor.toolbar.linkTitle', { sel: selPreview }) }}</text>
              <input class="etb-input" v-model="linkUrl" :placeholder="$t('editor.toolbar.linkPlaceholder')" @click.stop />
              <view class="etb-form-acts">
                <text class="etb-form-b" @tap.stop="insertMode = ''">{{ $t('editor.toolbar.cancel') }}</text>
                <text class="etb-form-b ok" @tap.stop="doLink">{{ $t('editor.toolbar.confirm') }}</text>
              </view>
            </view>

            <!-- 批注 -->
            <view v-else-if="insertMode === 'comment'" class="etb-form">
              <text class="etb-form-t">{{ $t('editor.toolbar.commentTitle', { sel: selPreview }) }}</text>
              <textarea class="etb-input ta" v-model="commentText" :placeholder="$t('editor.toolbar.commentPlaceholder')" @click.stop />
              <view class="etb-form-acts">
                <text class="etb-form-b" @tap.stop="insertMode = ''">{{ $t('editor.toolbar.cancel') }}</text>
                <text class="etb-form-b ok" @tap.stop="doComment">{{ $t('editor.toolbar.confirm') }}</text>
              </view>
            </view>
            <text v-if="insertErr" class="etb-err">{{ insertErr }}</text>
          </view>
        </view>

        <view class="etb-btn" :class="{ on: formattingMarks }" :title="$t('editor.toolbar.formattingMarks')" @tap.stop="toggleMarks">
          <text class="etb-tx">¶</text>
        </view>

        <!-- 表格上下文组：光标在表格里才出现（LO 自己那条 singlemode-table 工具栏
             的替代品）。行/列号从 get_ui_state 回的单元格名算出来——table_* 原语
             收的是绝对行号列号，没有「当前位置」的概念。 -->
        <template v-if="inTable">
          <view class="etb-sep"></view>
          <text class="etb-group-t">{{ $t('editor.toolbar.tableGroup') }}</text>
          <view class="etb-btn" :title="$t('editor.toolbar.rowAbove')" @tap.stop="tableOp('rowAbove')">
            <svg class="etb-ico" viewBox="0 0 24 24" fill="none"><path v-for="(d,i) in ICONS.rowAbove" :key="i" :d="d" stroke="currentColor" stroke-width="1.7" stroke-linecap="round"/></svg>
          </view>
          <view class="etb-btn" :title="$t('editor.toolbar.rowBelow')" @tap.stop="tableOp('rowBelow')">
            <svg class="etb-ico" viewBox="0 0 24 24" fill="none"><path v-for="(d,i) in ICONS.rowBelow" :key="i" :d="d" stroke="currentColor" stroke-width="1.7" stroke-linecap="round"/></svg>
          </view>
          <view class="etb-btn" :title="$t('editor.toolbar.colLeft')" @tap.stop="tableOp('colLeft')">
            <svg class="etb-ico" viewBox="0 0 24 24" fill="none"><path v-for="(d,i) in ICONS.colLeft" :key="i" :d="d" stroke="currentColor" stroke-width="1.7" stroke-linecap="round"/></svg>
          </view>
          <view class="etb-btn" :title="$t('editor.toolbar.colRight')" @tap.stop="tableOp('colRight')">
            <svg class="etb-ico" viewBox="0 0 24 24" fill="none"><path v-for="(d,i) in ICONS.colRight" :key="i" :d="d" stroke="currentColor" stroke-width="1.7" stroke-linecap="round"/></svg>
          </view>
          <view class="etb-btn" :title="$t('editor.toolbar.deleteRow')" @tap.stop="tableOp('delRow')">
            <svg class="etb-ico" viewBox="0 0 24 24" fill="none"><path v-for="(d,i) in ICONS.delRow" :key="i" :d="d" stroke="currentColor" stroke-width="1.7" stroke-linecap="round"/></svg>
          </view>
          <view class="etb-btn" :title="$t('editor.toolbar.deleteCol')" @tap.stop="tableOp('delCol')">
            <svg class="etb-ico" viewBox="0 0 24 24" fill="none"><path v-for="(d,i) in ICONS.delCol" :key="i" :d="d" stroke="currentColor" stroke-width="1.7" stroke-linecap="round"/></svg>
          </view>
        </template>
      </view>
    </scroll-view>

    <!-- 右侧常驻区：不参与滚动 -->
    <view class="etb-right">
      <view class="etb-btn wide" :class="{ on: !chromeHidden }" :title="$t('editor.toolbar.nativeMenusTitle')" @tap.stop="toggleChrome">
        <text class="etb-tx sm">{{ $t('editor.toolbar.nativeMenus') }}</text>
      </view>
      <view class="etb-btn wide" :class="{ on: findOpen }" :title="$t('editor.toolbar.findAndReplace')" @tap.stop="toggleFind">
        <text class="etb-tx sm">{{ $t('editor.toolbar.find') }}</text>
      </view>
      <view class="etb-btn wide" :class="{ on: state.view.recordChanges }" :title="$t('editor.toolbar.trackChanges')" @tap.stop="toggleTrack">
        <text class="etb-tx sm">{{ $t('editor.toolbar.trackChangesShort') }}</text>
      </view>
      <!-- 修订显示方式三态（dev-board#368）。当前态取自 get_ui_state 的真实读回，
           不是本地记的；引擎不支持页边显示时中间项自动消失（退成两态）。 -->
      <view v-if="state.view.revisionView" class="etb-drop" :class="{ open: menu === 'revview' }">
        <view ref="trig_revview" class="etb-field w96" :title="$t('editor.toolbar.revisionView')" @tap.stop="toggleMenu('revview')">
          <text class="etb-field-t">{{ revisionViewLabel }}</text>
          <text class="etb-caret">⌄</text>
        </view>
        <view v-if="menu === 'revview'" class="etb-menu w150" :style="popStyle(150)" @tap.stop>
          <view v-for="o in revisionViewOptions" :key="o.k" class="etb-item"
                :class="{ on: o.k === state.view.revisionView }" @tap.stop="pickRevisionView(o.k)">
            <text class="etb-item-t">{{ $t('editor.toolbar.' + o.t) }}</text>
          </view>
        </view>
      </view>
      <view class="etb-btn wide" :class="{ on: reviewOpen }" :title="$t('editor.toolbar.reviewPanel')" @tap.stop="$emit('toggle-review')">
        <text class="etb-tx sm">{{ $t('editor.toolbar.reviewShort') }}</text>
      </view>
      <!-- 解析（dev-board#182）：AI 通读全文抽实体 + 打外部库 + 一致性校验，联动打开
           「依据」窗格。这条工具栏只在 docKind==='writer' 时渲染，所以不用再判文档类型。 -->
      <view class="etb-btn wide" :class="{ on: insightOpen }" :title="$t('editor.toolbar.insightPanel')" @tap.stop="$emit('toggle-insight')">
        <text class="etb-tx sm">{{ $t('editor.toolbar.insightShort') }}</text>
      </view>
      <view class="etb-stepper" :title="$t('editor.toolbar.zoom')">
        <text class="etb-step-b" @tap.stop="stepZoom(-10)">−</text>
        <text class="etb-step-v z" @tap.stop="resetZoom">{{ Math.round(state.view.zoom || 100) }}%</text>
        <text class="etb-step-b" @tap.stop="stepZoom(10)">+</text>
      </view>
    </view>
  </view>

  <!-- 查找替换：自建面板，不走 LO 的 .uno:SearchDialog——真机审计实证那个对话框
       弹得出来但**键盘关不掉**（画布聚焦时按 Esc 同样无效），挂上去就是个坑。 -->
  <view v-if="findOpen" class="etb-find">
    <input class="etb-input fi" v-model="findText" :placeholder="$t('editor.toolbar.findPlaceholder')" @input="onFindInput" @confirm="findNext" />
    <text class="etb-find-n">{{ findStatus }}</text>
    <text class="etb-find-b" :title="$t('editor.toolbar.prevMatch')" @tap.stop="findPrev">{{ $t('editor.toolbar.prevMatch') }}</text>
    <text class="etb-find-b" :title="$t('editor.toolbar.nextMatch')" @tap.stop="findNext">{{ $t('editor.toolbar.nextMatch') }}</text>
    <input class="etb-input fi" v-model="replaceText" :placeholder="$t('editor.toolbar.replacePlaceholder')" />
    <text class="etb-find-b" @tap.stop="replaceCurrent">{{ $t('editor.toolbar.replace') }}</text>
    <text class="etb-find-b" @tap.stop="replaceAll">{{ $t('editor.toolbar.replaceAll') }}</text>
    <text class="etb-find-b" :class="{ on: matchCase }" :title="$t('editor.toolbar.matchCase')" @tap.stop="toggleCase">Aa</text>
    <text class="etb-find-x" @tap.stop="toggleFind">{{ $t('editor.toolbar.close') }}</text>
  </view>
  <text v-if="findOpen && findErr" class="etb-err bar">{{ findErr }}</text>
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
  rowAbove: ['M4 13h16v7H4z', 'M12 3v7', 'M9 6l3-3 3 3'],
  rowBelow: ['M4 4h16v7H4z', 'M12 21v-7', 'M9 18l3 3 3-3'],
  colLeft: ['M13 4h7v16h-7z', 'M3 12h7', 'M6 9l-3 3 3 3'],
  colRight: ['M4 4h7v16H4z', 'M21 12h-7', 'M18 9l3 3-3 3'],
  delRow: ['M4 9h16v6H4z', 'M9 5l6 14'],
  delCol: ['M9 4h6v16H9z', 'M5 9l14 6'],
}
const ALIGNS = [
  { k: 'left', cmd: 'align_left', t: 'alignLeft', icon: 'alignLeft' },
  { k: 'center', cmd: 'align_center', t: 'alignCenter', icon: 'alignCenter' },
  { k: 'right', cmd: 'align_right', t: 'alignRight', icon: 'alignRight' },
  { k: 'justify', cmd: 'align_justify', t: 'alignJustify', icon: 'alignJustify' },
]
const TEXT_COLORS = [
  { v: 'auto', t: 'auto' }, { v: '#C0392B', t: 'red' }, { v: '#1A5336', t: 'ink' },
  { v: '#1D4ED8', t: 'blue' }, { v: '#B45309', t: 'brown' }, { v: '#6B21A8', t: 'purple' },
  { v: '#495057', t: 'darkGray' }, { v: '#868E96', t: 'gray' },
]
const HL_COLORS = [
  { v: 'none', t: 'none' }, { v: 'yellow', t: 'yellow' }, { v: 'green', t: 'green' },
  { v: 'cyan', t: 'cyan' }, { v: 'magenta', t: 'magenta' }, { v: 'gray', t: 'gray' },
]
// 样式下拉只列律师真会用的这些（126 条全塞进去没人找得到）；当前段落用的样式
// 若不在表里也会被补进列表，不至于「显示的样式选不回来」。
const STYLE_PICKS = [
  'Standard', 'Text body', 'Title', 'Subtitle',
  'Heading 1', 'Heading 2', 'Heading 3', 'Heading 4',
  'Quotations', 'List', 'Caption', 'First line indent',
]
// 脚注/尾注/页眉/页脚共用一个单行文本表单：只差调哪个原语、用哪句提示。
// 注意：**没有尾注**。本引擎构建不支持——insert_endnote 设 IsEndnote 时抛
// IllegalArgumentException（真机实证）。做不到的不放按钮。
const TEXT_FORMS = {
  footnote: { title: 'footnoteTitle', ph: 'footnotePlaceholder', action: 'insert_footnote', arg: 'text' },
  header: { title: 'headerTitle', ph: 'headerPlaceholder', action: 'edit_header_footer', arg: 'text', extra: { target: 'header' } },
  footer: { title: 'footerTitle', ph: 'footerPlaceholder', action: 'edit_header_footer', arg: 'text', extra: { target: 'footer' } },
}
// 修订显示三态（dev-board#368）。k 与 worker 的 REVISION_VIEWS 一字不差：
// all=正文内联标记 / margin=删除文字挪页边 / final=痕迹全隐只看结果。
// 引擎读不到 ShowChangesInMargin（旧构建）时 margin 那项自动去掉，退成两态。
const REVISION_VIEWS = [
  { k: 'all', t: 'revisionViewAll' },
  { k: 'margin', t: 'revisionViewMargin' },
  { k: 'final', t: 'revisionViewFinal' },
]
const SIZES = [8, 9, 10, 10.5, 11, 12, 14, 16, 18, 20, 22, 24, 26, 28, 36, 48, 72]
// 插入表格的网格选择器：8 行 × 8 列够覆盖手工建表的绝大多数情形，再大的表
// 律师是从 Excel 粘过来或让 AI 生成的，不是在这里点出来的。
const GRID_R = 8, GRID_C = 8
const GRID_CELLS = []
for (let r = 1; r <= GRID_R; r++) for (let c = 1; c <= GRID_C; c++) GRID_CELLS.push({ r, c, k: r + '-' + c })

const EMPTY = () => ({ character: {}, paragraph: {}, view: {}, selection: {}, undo: null })

export default {
  name: 'EditorToolbar',
  emits: ['toggle-review', 'toggle-insight', 'changed', 'ui-state'],
  props: {
    // LibreOffice executor（executeCommand(action, params)）。null 时整条静默。
    executor: { type: Object, default: null },
    // 宿主在「选区/光标动了」「文档改了」时自增，驱动激活态刷新。
    refreshKey: { type: Number, default: 0 },
    reviewOpen: { type: Boolean, default: false },
    // 「依据」窗格（dev-board#182）此刻开着没有——按钮的按下态跟着它。
    insightOpen: { type: Boolean, default: false },
  },
  data() {
    return {
      state: EMPTY(), styleList: [], fontList: [], menu: '', popPos: null, formattingMarks: false,
      // 插入菜单：'' | 'table' | 'link' | 'comment'
      insertMode: '', insertErr: '', grid: { r: 0, c: 0 }, linkUrl: '', commentText: '', formText: '', selText: '',
      // 查找替换
      findOpen: false, findText: '', replaceText: '', matchCase: false,
      findTotal: null, findIndex: 0, findErr: '', findTruncated: false,
      // LO 自己的菜单栏/工具栏/状态栏/标尺。默认藏起来——这条工具栏就是它们的
      // 替代品；留一个开关是逃生阀，不是常规路径（开了那个会关文档的 × 也回来）。
      chromeHidden: true,
    }
  },
  computed: {
    ICONS: () => ICONS, ALIGNS: () => ALIGNS,
    TEXT_COLORS: () => TEXT_COLORS, HL_COLORS: () => HL_COLORS, GRID_CELLS: () => GRID_CELLS,
    TEXT_FORMS: () => TEXT_FORMS,
    inTable() { return this.state.selection.inTable === true },
    // 页边显示要引擎支持（LO 7.1+ 且我们的 r3 表格补丁）。worker 读不到那个视图
    // 设置时回 revisionMarginSupported:false，这里把中间项摘掉——不放做不到的选项。
    revisionViewOptions() {
      const marginOk = this.state.view.revisionMarginSupported !== false
      return REVISION_VIEWS.filter((o) => o.k !== 'margin' || marginOk)
    },
    revisionViewLabel() {
      const cur = this.state.view.revisionView
      const hit = REVISION_VIEWS.find((o) => o.k === cur)
      return hit ? this.$t('editor.toolbar.' + hit.t) : this.$t('editor.toolbar.revisionView')
    },
    // 单元格名如 "B2" → {row:2, col:'B'}。table_* 原语收 1 起的行号与列字母。
    cellPos() {
      const m = /^([A-Z]+)(\d+)$/.exec(String(this.state.selection.cellName || ''))
      return m ? { col: m[1], row: Number(m[2]) } : null
    },
    // 超链接/批注都作用于选区。没有选区就明说「需先选中文字」，不做成点了没反应
    noSelection() { return this.state.selection.collapsed !== false },
    selPreview() {
      const t = this.selText || ''
      return t.length > 12 ? t.slice(0, 12) + '…' : t
    },
    findStatus() {
      if (!this.findText) return ''
      if (this.findTotal === null) return '…'
      if (this.findTotal === 0) return this.$t('editor.toolbar.noMatch')
      const total = this.findTruncated ? this.findTotal + '+' : this.findTotal
      return this.findIndex
        ? this.$t('editor.toolbar.matchPosition', { index: this.findIndex, total })
        : this.$t('editor.toolbar.matchTotal', { total })
    },
    // 读不到撤销可用性时**不置灰**——宁可多点一下，也不要把能用的功能锁死
    undoDisabled() { return this.state.undo ? this.state.undo.canUndo === false : false },
    redoDisabled() { return this.state.undo ? this.state.undo.canRedo === false : false },
    styleLabel() { return this.localStyleName(this.state.paragraph.styleName || '') || this.$t('editor.toolbar.paraStyle') },
    fontLabel() {
      const f = this.state.character.font || ''
      return f.length > 8 ? f.slice(0, 8) + '…' : (f || this.$t('editor.toolbar.fontFallback'))
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
      return picks.map((n) => ({ name: n, label: this.localStyleName(n, have[n] && have[n].display) }))
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
      // 自建工具栏挂上了，LO 自己那套就该退场。只在 Writer 上做——本组件本来
      // 就只给 Writer 渲染；Calc/Impress 没有替代品，藏了会剩一片空白。
      await this.applyChrome(true)
    },
    async refresh() {
      const r = await this.call('get_ui_state', {})
      if (r && r.success) this.state = r
      // 激活态变了要通知宿主：菜单栏的「修订模式」勾选读的就是这里的
      // state.view.recordChanges，不广播的话菜单会停在上一次的状态。
      this.$emit('ui-state')
    },
    // 命令跑完必须刷新两件事：工具栏自己的激活态，以及宿主的自动保存（改了文档）
    async after(res, changed) {
      if (changed !== false) this.$emit('changed')
      await this.refresh()
      return res
    },
    ui(name) { this.closeMenus(); return this.call('ui_command', { name }).then((r) => this.after(r)) },
    run(action) { this.closeMenus(); return this.call(action, {}).then((r) => this.after(r)) },
    toggleMenu(name) {
      const opening = this.menu !== name
      this.menu = opening ? name : ''
      if (opening) this.capturePopPos(name)
    },
    // 弹层不能留在文档流里定位：工具栏行是横向 scroll-view（overflow 竖向 hidden），
    // 外面还套着 pane/workbench 一串 overflow:hidden，绝对定位的菜单会被裁得只剩
    // 顶边一条、看起来就是「点了打不开」（dev-board#245）。打开瞬间取触发器视口
    // 坐标，用 fixed 逃出所有裁剪上下文；z 序压在弹窗遮罩（1000+）之下、窗格与
    // 编辑器 webview 之上。
    capturePopPos(name) {
      const ref = this.$refs['trig_' + name]
      const el = ref && (ref.$el || ref)
      const rect = el && el.getBoundingClientRect ? el.getBoundingClientRect() : null
      this.popPos = rect ? { left: rect.left, top: rect.bottom + 4 } : null
    },
    popStyle(width) {
      if (!this.popPos) return {}
      const vw = (typeof window !== 'undefined' && window.innerWidth) || 0
      const left = vw ? Math.min(this.popPos.left, Math.max(8, vw - width - 8)) : this.popPos.left
      return { position: 'fixed', left: left + 'px', top: this.popPos.top + 'px', zIndex: 900 }
    },
    closeMenus() { this.menu = ''; this.insertMode = ''; this.insertErr = '' },

    // ---- 插入菜单 ----
    openInsert() {
      const opening = this.menu !== 'insert'
      this.insertMode = ''; this.insertErr = ''; this.grid = { r: 0, c: 0 }
      this.menu = opening ? 'insert' : ''
      if (opening) this.capturePopPos('insert')
      // 选区文字要现读：菜单里要显示「给『xxx』加链接」，而 get_ui_state 只回
      // collapsed 布尔值。顺带刷新一次状态，免得按上一次的选区判空。
      if (opening) {
        this.refresh()
        this.call('get_selection', {}).then((r) => { this.selText = (r && r.text) || '' })
      }
    },
    startText(kind) { this.formText = ''; this.insertErr = ''; this.insertMode = kind },
    doTextForm() {
      const form = TEXT_FORMS[this.insertMode]
      if (!form) return null
      const text = String(this.formText || '').trim()
      if (!text) { this.insertErr = this.$t('editor.toolbar.textRequired'); return null }
      const params = Object.assign({ [form.arg]: text }, form.extra || {})
      return this.call(form.action, params).then((res) => this.finishInsert(res))
    },
    // 表格相对操作。position 的语义是「插在该行/列之前」，缺省追加到末尾——
    // 所以「下方插入」= position+1，「右侧插入」= 列号+1。删除直接给当前行/列。
    async tableOp(op) {
      const pos = this.cellPos
      if (!pos) return null
      const colNum = pos.col.split('').reduce((n, ch) => n * 26 + (ch.charCodeAt(0) - 64), 0)
      const map = {
        rowAbove: ['table_add_row', { position: pos.row }],
        rowBelow: ['table_add_row', { position: pos.row + 1 }],
        colLeft: ['table_add_col', { position: colNum }],
        colRight: ['table_add_col', { position: colNum + 1 }],
        delRow: ['table_delete_row', { position: pos.row }],
        delCol: ['table_delete_col', { position: colNum }],
      }
      const spec = map[op]
      if (!spec) return null
      const res = await this.call(spec[0], spec[1])
      // 引擎拒绝（如合并过单元格的表按列插入）要如实说，不能默默什么都没发生
      if (!res || res.success !== true) { this.insertErr = (res && res.message) || this.$t('editor.toolbar.opFailed'); return null }
      this.insertErr = ''
      return this.after(res)
    },
    startLink() {
      if (this.noSelection) return
      this.linkUrl = ''; this.insertErr = ''; this.insertMode = 'link'
    },
    startComment() {
      if (this.noSelection) return
      this.commentText = ''; this.insertErr = ''; this.insertMode = 'comment'
    },
    // 失败必须说出来。工具栏上「点了没反应」和「点了偷偷失败」一样糟。
    async finishInsert(res, okMsg) {
      if (!res || res.success !== true) {
        this.insertErr = (res && res.message) || this.$t('editor.toolbar.opFailed')
        return false
      }
      this.closeMenus()
      await this.after(res)
      return true
    },
    doInsertTable(r, c) {
      // insert_table 收的是内容矩阵（string[][]），空表就是全空串；headerRow
      // 关掉——用户手工插的表还没内容，先加粗首行没有意义。
      const rows = []
      for (let i = 0; i < r; i++) rows.push(new Array(c).fill(''))
      return this.call('insert_table', { rows, headerRow: false }).then((res) => this.finishInsert(res))
    },
    doLink() {
      const url = String(this.linkUrl || '').trim()
      if (!url) { this.insertErr = this.$t('editor.toolbar.linkRequired'); return null }
      return this.call('set_selection_hyperlink', { url }).then((res) => this.finishInsert(res))
    },
    doComment() {
      const comment = String(this.commentText || '').trim()
      if (!comment) { this.insertErr = this.$t('editor.toolbar.commentRequired'); return null }
      return this.call('add_comment_at_selection', { comment }).then((res) => this.finishInsert(res))
    },
    // 图片走浏览器原生文件选择：引擎的 .uno:InsertGraphic 会开 LO 自己的文件
    // 对话框，在 WASM 里够不到本机文件系统。读成 dataURL 交给 insert_image。
    pickImage() {
      const el = document.createElement('input')
      el.type = 'file'
      el.accept = 'image/*'
      el.onchange = () => {
        const f = el.files && el.files[0]
        if (!f) return
        const fr = new FileReader()
        fr.onload = () => this.call('insert_image', { dataUrl: String(fr.result) }).then((res) => this.finishInsert(res))
        fr.onerror = () => { this.insertErr = this.$t('editor.toolbar.imageReadFailed') }
        fr.readAsDataURL(f)
      }
      el.click()
    },
    // 样式名显示：本地化表优先（引擎的 DisplayName 在部分构建上回英文），
    // 表里没有就用引擎给的，再没有才退回程序名。
    localStyleName(name, display) {
      if (!name) return ''
      const key = 'editor.toolbar.styleNames.' + name
      const hit = this.$t(key)
      if (hit && hit !== key) return hit
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
    // 修订显示方式。changed=false：一个字节都没改，别把文档标脏触发自动保存。
    // 高亮不在这里本地置位——after() 会重跑 get_ui_state，按引擎读回的真实态刷新。
    pickRevisionView(mode) {
      this.closeMenus()
      return this.call('set_revision_view', { mode }).then((r) => this.after(r, false))
    },
    // LO chrome 开关。hideElement 的返回值不可信，原语内部用 isElementVisible
    // 复核后回报，这里只按结果记状态。
    async applyChrome(hide) {
      const res = await this.call('set_chrome', {
        menubar: !hide, statusbar: !hide, toolbars: !hide, rulers: !hide,
      })
      if (res && res.success) this.chromeHidden = hide
      return res
    },
    toggleChrome() { this.closeMenus(); return this.applyChrome(!this.chromeHidden) },

    // ---- 查找替换 ----
    toggleFind() {
      this.findOpen = !this.findOpen
      this.closeMenus()
      if (!this.findOpen) { this.findErr = ''; return }
      this.findTotal = null; this.findIndex = 0; this.findErr = ''
    },
    toggleCase() { this.matchCase = !this.matchCase; this.findIndex = 0; this.onFindInput() },
    // 打字时不要每个字符都去搜一遍整篇文档——搜索跑在 office 线程上，连打会卡。
    onFindInput() {
      clearTimeout(this._findTimer)
      this.findTotal = null; this.findIndex = 0
      if (!this.findText) return
      this._findTimer = setTimeout(() => this.findNext(), 350)
    },
    async findGo(direction) {
      const keyword = String(this.findText || '')
      if (!keyword) return null
      this.findErr = ''
      const r = await this.call('find_navigate', { keyword, direction, matchCase: this.matchCase })
      if (!r || r.success !== true) { this.findErr = (r && r.message) || this.$t('editor.toolbar.findFailed'); return null }
      this.findTotal = r.total || 0
      this.findTruncated = !!r.truncated
      this.findIndex = r.found ? r.index : 0
      // 选区变了，工具栏激活态跟着刷新（但没改文档，别标脏）
      await this.refresh()
      return r
    },
    findNext() { return this.findGo('next') },
    findPrev() { return this.findGo('prev') },
    // 替换当前这一处：查找栏已经把它选中了，直接替换选区。RecordChanges 开着时
    // replace_selection 走最小修订路径，跟 AI 改文档同一条路。
    async replaceCurrent() {
      if (!this.findText) return
      if (!this.findIndex) { const r = await this.findNext(); if (!r || !r.found) return }
      const res = await this.call('replace_selection', { text: String(this.replaceText || '') })
      if (!res || res.success === false) { this.findErr = (res && res.message) || this.$t('editor.toolbar.replaceFailed'); return }
      this.$emit('changed')
      this.findIndex = 0
      await this.findNext()
    },
    async replaceAll() {
      const findText = String(this.findText || '')
      if (!findText) return
      const res = await this.call('find_replace', {
        findText, replaceText: String(this.replaceText || ''), replaceAll: true, matchCase: this.matchCase,
      })
      if (!res || res.success !== true) { this.findErr = (res && res.message) || this.$t('editor.toolbar.replaceFailed'); return }
      this.findErr = res.replaced ? '' : this.$t('editor.toolbar.nothingReplaced')
      this.findTotal = 0; this.findIndex = 0
      await this.after(res)
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
.etb-wrap { display: flex; flex-direction: column; flex-shrink: 0; }
.etb { display: flex; align-items: center; gap: 6px; height: 38px; padding: 0 8px; flex-shrink: 0;
  background: var(--awd-bg); border-bottom: 1px solid var(--awd-border); }
/* 查找替换条：工具栏下面单独一行，开着才占高度 */
.etb-find { display: flex; align-items: center; gap: 6px; height: 36px; padding: 0 8px; flex-shrink: 0;
  background: var(--awd-surface); border-bottom: 1px solid var(--awd-border); }
.etb-input.fi { width: 148px; height: 26px; flex-shrink: 0; }
.etb-find-n { min-width: 62px; font-size: 11px; color: var(--awd-text-2); }
.etb-find-b { padding: 3px 9px; border: 1px solid var(--awd-border); border-radius: 5px; font-size: 12px;
  color: var(--awd-text-2); flex-shrink: 0; }
.etb-find-b:hover { border-color: var(--awd-border-strong); }
.etb-find-b.on { background: var(--awd-accent-soft); border-color: var(--awd-mint); color: var(--awd-accent-text); }
.etb-find-x { margin-left: auto; padding: 3px 9px; font-size: 12px; color: var(--awd-text-2); flex-shrink: 0; }
.etb-err.bar { margin: 0; border-radius: 0; padding: 4px 10px; }
.etb-scroll { flex: 1; min-width: 0; white-space: nowrap; }
.etb-row { display: flex; align-items: center; gap: 2px; }
.etb-right { display: flex; align-items: center; gap: 4px; flex-shrink: 0; padding-left: 6px;
  border-left: 1px solid var(--awd-border); }
.etb-group-t { flex-shrink: 0; padding: 0 4px; font-size: 11px; color: var(--awd-text-3); }
.etb-sep { width: 1px; height: 18px; background: var(--awd-surface-3); margin: 0 5px; flex-shrink: 0; }

.etb-btn { position: relative; display: flex; align-items: center; justify-content: center; gap: 3px;
  min-width: 26px; height: 26px; padding: 0 5px; border-radius: 5px; color: var(--awd-text-2); flex-shrink: 0; }
.etb-btn:hover { background: var(--awd-surface-2); }
.etb-btn.on { background: var(--awd-accent-soft); color: var(--awd-accent-text); }
.etb-btn.off { color: var(--awd-text-3); }
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
  padding: 0 6px; border: 1px solid var(--awd-border); border-radius: 5px; background: var(--awd-surface); }
.etb-field:hover { border-color: var(--awd-border-strong); }
.etb-field-t { font-size: 12px; color: var(--awd-text); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.etb-caret { font-size: 11px; color: var(--awd-text-3); }
.w110 { width: 110px; }
.w96 { width: 96px; }

.etb-drop { position: relative; flex-shrink: 0; }
.etb-menu { position: absolute; top: 30px; left: 0; z-index: 40; max-height: 280px;
  padding: 4px; background: var(--awd-surface); border: 1px solid var(--awd-border); border-radius: 8px;
  box-shadow: 0 6px 20px rgba(15, 23, 42, 0.12); }
.w150 { width: 150px; }
.w160 { width: 160px; }
.w180 { width: 180px; }
.w200 { width: 200px; }
.w72 { width: 72px; }
.etb-menu.pad { padding: 6px; }
.etb-item { display: flex; align-items: baseline; justify-content: space-between; gap: 6px;
  padding: 5px 8px; border-radius: 5px; }
.etb-item:hover { background: var(--awd-surface-2); }
.etb-item.on { background: var(--awd-accent-soft); }
.etb-item.dim .etb-item-t { color: var(--awd-text-3); }
.etb-item-t { font-size: 12px; color: var(--awd-text); }
.etb-hint { font-size: 10px; color: var(--awd-text-3); }

.etb-form { display: flex; flex-direction: column; gap: 7px; padding: 3px 4px 1px; }
.etb-form-t { font-size: 12px; color: var(--awd-text-2); }
.etb-grid { display: grid; grid-template-columns: repeat(8, 1fr); gap: 2px; }
.etb-cell { height: 15px; border: 1px solid var(--awd-border); border-radius: 2px; background: var(--awd-surface); }
.etb-cell.hot { background: var(--awd-accent-soft); border-color: var(--awd-mint); }
.etb-input { width: 100%; height: 28px; padding: 0 7px; box-sizing: border-box; font-size: 12px;
  color: var(--awd-text); border: 1px solid var(--awd-border); border-radius: 5px; background: var(--awd-surface); }
.etb-input.ta { height: 58px; padding: 5px 7px; line-height: 1.45; }
.etb-form-acts { display: flex; justify-content: flex-end; gap: 6px; }
.etb-form-b { padding: 3px 11px; border: 1px solid var(--awd-border); border-radius: 5px; font-size: 12px; color: var(--awd-text-2); }
.etb-form-b.ok { border-color: var(--awd-mint); color: var(--awd-accent-text); background: var(--awd-accent-soft); }
.etb-err { display: block; margin-top: 6px; padding: 4px 7px; border-radius: 5px;
  background: var(--awd-danger-soft); color: var(--awd-danger-text); font-size: 11px; line-height: 1.4; }

.etb-palette { position: absolute; top: 30px; left: 0; z-index: 40; display: flex; flex-wrap: wrap; gap: 5px;
  width: 128px; padding: 7px; background: var(--awd-surface); border: 1px solid var(--awd-border); border-radius: 8px;
  box-shadow: 0 6px 20px rgba(15, 23, 42, 0.12); }
.etb-chip { width: 18px; height: 18px; border-radius: 4px; border: 1px solid rgba(0, 0, 0, 0.12); }
.etb-chip.none { position: relative; }
.etb-chip.none::after { content: ''; position: absolute; left: 1px; right: 1px; top: 8px; height: 1px;
  background: var(--awd-danger); transform: rotate(-45deg); }

.etb-stepper { display: flex; align-items: center; height: 26px; border: 1px solid var(--awd-border); border-radius: 5px;
  background: var(--awd-surface); flex-shrink: 0; }
.etb-step-b { width: 20px; text-align: center; font-size: 14px; color: var(--awd-text-2); line-height: 24px; }
.etb-step-b:hover { color: var(--awd-accent-text); background: var(--awd-surface-2); }
.etb-step-v { min-width: 34px; text-align: center; font-size: 12px; color: var(--awd-text); }
.etb-step-v.z { min-width: 42px; }
</style>
