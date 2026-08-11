<template>
  <!-- Vue 3 多根节点：与 DdFilesPanel / ShareholderMeetingPanel 同构 -->

  <view class="lv-panel-header">
    <text class="lv-panel-title">诉讼可视化</text>
    <view class="lv-refresh-btn" @tap="reload" title="刷新">
      <svg viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
        <path d="M21 12a9 9 0 1 1-2.64-6.36" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" />
        <path d="M21 3v6h-6" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" />
      </svg>
    </view>
  </view>

  <!-- 环境降级提示。graphviz 缺失只挡流程图一种布局，不能说成整体不可用 -->
  <view class="lv-notice" v-if="status && !status.available">
    <text class="lv-notice-text">{{ status.reason }}</text>
  </view>
  <view class="lv-notice subtle" v-else-if="status && !status.graphviz">
    <text class="lv-notice-text">这台机器没有 graphviz，流程图暂时画不了；时间轴、关系图、股权结构树等六种照常。</text>
  </view>

  <!-- 出图 -->
  <view class="lv-section">
    <text class="lv-section-title">出一张新图</text>

    <view class="lv-scope" @tap="pickScope">
      <text class="lv-scope-label">材料</text>
      <text class="lv-scope-value" :class="{ placeholder: !scopeLabel }">
        {{ scopeLabel || '本项目全部材料（点击改为指定文件夹或文件）' }}
      </text>
    </view>

    <text class="lv-hint-label">图种</text>
    <view class="lv-kinds">
      <view
        v-for="k in KINDS"
        :key="k.value"
        class="lv-kind"
        :class="{ active: diagramHint === k.value }"
        @tap="diagramHint = diagramHint === k.value ? '' : k.value"
      >{{ k.label }}</view>
    </view>
    <text class="lv-kind-tip">不选 = 让 AI 按材料自己判断该画哪种。</text>

    <view class="lv-btn primary" :class="{ disabled: starting }" @tap="start">
      {{ starting ? '正在交给 AI…' : '开始出图' }}
    </view>
  </view>

  <!-- 图廊 -->
  <view class="lv-section">
    <text class="lv-section-title">本项目的图（{{ diagrams.length }}）</text>

    <view class="lv-empty" v-if="!loading && diagrams.length === 0">
      <text class="lv-empty-text">还没有图。上面选好材料点「开始出图」。</text>
    </view>

    <view v-for="d in diagrams" :key="d.folderId" class="lv-card">
      <view class="lv-card-head" @tap="openDiagram(d)">
        <view class="lv-card-main">
          <text class="lv-card-name">{{ d.name }}</text>
          <text class="lv-card-meta">
            {{ layoutLabel(d.layout) }}<template v-if="d.mode"> · {{ d.mode }}</template>
          </text>
        </view>
        <text class="lv-draft-badge" v-if="d.draft">草稿</text>
        <text class="lv-edited-badge" v-else-if="d.handEdited">手工改过</text>
      </view>

      <view class="lv-card-formats">
        <text v-for="f in d.formats" :key="f" class="lv-format-chip">{{ f }}</text>
      </view>

      <view class="lv-card-actions">
        <view class="lv-btn ghost" @tap="openDiagram(d)">打开</view>
        <!-- 「编辑」是 .drawio 这份产物在面板里的唯一入口。没有它，可编辑版就只能
             从文件树里翻出来，等于大多数人不知道它存在。 -->
        <view class="lv-btn ghost" v-if="d.drawioFileId" @tap="editDiagram(d)">编辑</view>
        <view
          v-for="m in MODES"
          :key="m"
          class="lv-btn ghost"
          :class="{ disabled: restylingId === d.folderId }"
          @tap="restyle(d, m)"
        >{{ m }}</view>
      </view>
    </view>
  </view>

  <!-- MIT 许可要求保留版权声明：出图引擎 vendor 自 mqc-litigation-visual-redraw，见 litviz/UPSTREAM.md -->
  <view class="lv-credit">
    <text class="lv-credit-text">出图引擎 mqc-litigation-visual-redraw · Copyright (c) 2026 缪奇川 (Miao Qichuan) · MIT License</text>
  </view>
</template>

<script>
import {
  getLitigationVisualStatus,
  getLitigationDiagrams,
  restyleLitigationDiagram,
  getLitigationKickoffPrompt
} from '@/services/api.js'

// 与引擎的三种视觉模式一一对应（litviz/engine/references/visual-style.md）
const MODES = ['奇川风', '歸藏风', '白描']

// 面板只给"用户会用自己的话说出来"的图种。七种布局里 comparison_table
// 不单列——它是关系族的 A/B 变体，用户想要时在对话里说就行，
// 摆在这里会让这排按钮看起来像一份需要先学习的分类表。
const KINDS = [
  { value: '事实经过时间轴', label: '事实时间轴' },
  { value: '诉讼时效/保证期间甘特图', label: '时效期间图' },
  { value: '案件流程图', label: '流程图' },
  { value: '当事人法律关系图', label: '关系图' },
  { value: '股权控制结构树', label: '股权结构' }
]

const LAYOUT_LABELS = {
  numbered_point_timeline: '时间轴 · 编号型',
  dated_point_timeline: '时间轴 · 日期型',
  proportional_gantt: '期间图 · 甘特',
  graphviz_flow: '流程图',
  graphviz_relation: '关系图',
  relation_tree: '层级结构树',
  comparison_table: '对比表'
}

export default {
  name: 'LitigationVisualPanel',
  props: {
    projectId: { type: [String, Number], default: null }
  },
  emits: ['start-drawing', 'open-file', 'request-scope-select'],
  data() {
    return {
      MODES,
      KINDS,
      status: null,
      diagrams: [],
      loading: false,
      starting: false,
      restylingId: null,
      diagramHint: '',
      scope: null            // { label, description } —— 由父页面的文件选择器回填
    }
  },
  computed: {
    scopeLabel() {
      return this.scope ? this.scope.label : ''
    }
  },
  watch: {
    projectId: {
      immediate: true,
      handler() { this.reload() }
    }
  },
  methods: {
    layoutLabel(layout) {
      return LAYOUT_LABELS[layout] || '图'
    },

    async reload() {
      if (!this.projectId) return
      this.loading = true
      try {
        // 两件事互不依赖，环境自检慢一点不该拖住图廊
        const [st, list] = await Promise.all([
          getLitigationVisualStatus().catch(() => null),
          getLitigationDiagrams(this.projectId).catch(() => [])
        ])
        this.status = st
        this.diagrams = Array.isArray(list) ? list : []
      } finally {
        this.loading = false
      }
    },

    pickScope() {
      // 文件/文件夹选择器归父页面管（FilePickerDialog 挂在 project-overview 上）
      this.$emit('request-scope-select', (picked) => { this.scope = picked })
    },

    async start() {
      if (this.starting || !this.projectId) return
      if (this.status && !this.status.available) {
        uni.showToast({ title: this.status.reason || '出图环境不可用', icon: 'none' })
        return
      }
      this.starting = true
      try {
        // prompt 由服务端拼：触发词必须原样在正文里才能命中 skill 注入
        const res = await getLitigationKickoffPrompt(this.projectId, {
          scope: this.scope ? this.scope.description : '',
          diagramHint: this.diagramHint
        })
        if (res && res.prompt) {
          this.$emit('start-drawing', { prompt: res.prompt })
        }
      } catch (e) {
        uni.showToast({ title: (e && e.message) || '启动失败', icon: 'none' })
      } finally {
        this.starting = false
      }
    },

    openDiagram(d) {
      if (!d || !d.svgFileId) return
      this.$emit('open-file', { fileId: d.svgFileId, name: d.name })
    },

    // 打开可继续编辑的那份（.drawio，走内嵌 draw.io）
    editDiagram(d) {
      if (!d || !d.drawioFileId) return
      this.$emit('open-file', { fileId: d.drawioFileId, name: d.name })
    },

    async restyle(d, mode) {
      if (this.restylingId) return
      // 换风格是拿语义地图重画，会整份覆盖产物。图在 draw.io 里手工改过的话，
      // 那些改动不在地图里，重画就等于丢掉——必须先问一句。
      if (d.handEdited) {
        const ok = await new Promise((resolve) => {
          uni.showModal({
            title: '这张图手工改过',
            content: `换成${mode}会用语义地图重新画一遍，你在 draw.io 里做的修改会被覆盖。继续吗？`,
            confirmText: '继续重画',
            cancelText: '取消',
            success: (res) => resolve(!!res.confirm),
            fail: () => resolve(false)
          })
        })
        if (!ok) return
      }
      this.restylingId = d.folderId
      uni.showLoading({ title: '重画中…', mask: true })
      try {
        await restyleLitigationDiagram(this.projectId, d.folderId, mode)
        await this.reload()
        // 图变了但文件 ID 没变，已打开的标签要重新拉一次
        uni.$emit('awd:litviz-restyled', { folderId: d.folderId, svgFileId: d.svgFileId })
        uni.showToast({ title: `已换成${mode}`, icon: 'none' })
      } catch (e) {
        uni.showToast({ title: (e && e.message) || '重画失败', icon: 'none' })
      } finally {
        uni.hideLoading()
        this.restylingId = null
      }
    }
  }
}
</script>

<style scoped>
.lv-panel-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 12px 14px 10px;
  border-bottom: 1px solid #ebedf0;
}
.lv-panel-title { font-size: 13px; font-weight: 600; color: #1f2329; letter-spacing: .02em; }
.lv-refresh-btn {
  width: 22px; height: 22px; display: flex; align-items: center; justify-content: center;
  color: #8a9099; cursor: pointer; border-radius: 4px;
}
.lv-refresh-btn:hover { background: #f2f4f6; color: #1f2329; }
.lv-refresh-btn svg { width: 14px; height: 14px; }

.lv-notice { margin: 10px 14px 0; padding: 8px 10px; border-radius: 6px; background: #fdf3f2; border: 1px solid #f3d9d6; }
.lv-notice.subtle { background: #f7f8fa; border-color: #e8eaed; }
.lv-notice-text { font-size: 11px; line-height: 1.6; color: #6b6560; }

.lv-section { padding: 12px 14px; border-bottom: 1px solid #f0f2f4; }
.lv-section-title { display: block; font-size: 11px; font-weight: 600; color: #8a9099; letter-spacing: .06em; margin-bottom: 10px; }

.lv-scope {
  padding: 8px 10px; border: 1px solid #e3e6ea; border-radius: 6px; background: #fff;
  cursor: pointer; margin-bottom: 12px;
}
.lv-scope:hover { border-color: #c9ced6; }
.lv-scope-label { display: block; font-size: 10px; color: #9aa0a8; margin-bottom: 3px; }
.lv-scope-value { font-size: 12px; color: #1f2329; line-height: 1.5; }
.lv-scope-value.placeholder { color: #9aa0a8; }

.lv-hint-label { display: block; font-size: 10px; color: #9aa0a8; margin-bottom: 6px; }
.lv-kinds { display: flex; flex-wrap: wrap; gap: 6px; margin-bottom: 6px; }
.lv-kind {
  padding: 4px 9px; font-size: 11px; border-radius: 4px; cursor: pointer;
  border: 1px solid #e3e6ea; color: #4a5058; background: #fff;
}
.lv-kind:hover { border-color: #c9ced6; }
.lv-kind.active { border-color: #2b6a4d; color: #2b6a4d; background: #f2f8f5; }
.lv-kind-tip { display: block; font-size: 10px; color: #9aa0a8; margin-bottom: 12px; }

.lv-btn {
  display: inline-flex; align-items: center; justify-content: center;
  padding: 6px 12px; font-size: 12px; border-radius: 5px; cursor: pointer; user-select: none;
}
.lv-btn.primary { width: 100%; background: #2b6a4d; color: #fff; padding: 8px 12px; }
.lv-btn.primary:hover { background: #245b42; }
.lv-btn.ghost { border: 1px solid #e3e6ea; color: #4a5058; background: #fff; padding: 4px 10px; font-size: 11px; }
.lv-btn.ghost:hover { border-color: #c9ced6; background: #fafbfc; }
.lv-btn.disabled { opacity: .5; pointer-events: none; }

.lv-empty { padding: 16px 0; text-align: center; }
.lv-empty-text { font-size: 11px; color: #9aa0a8; }

.lv-card { border: 1px solid #ebedf0; border-radius: 6px; padding: 10px; margin-bottom: 8px; background: #fff; }
.lv-card:hover { border-color: #dfe3e8; }
.lv-card-head { display: flex; align-items: flex-start; justify-content: space-between; cursor: pointer; }
.lv-card-main { flex: 1; min-width: 0; }
.lv-card-name {
  display: block; font-size: 12px; color: #1f2329; font-weight: 500; line-height: 1.4;
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
.lv-card-meta { display: block; font-size: 10px; color: #9aa0a8; margin-top: 2px; }
.lv-edited-badge {
  flex-shrink: 0; margin-left: 8px; padding: 1px 6px; font-size: 10px;
  color: #2b6a4d; background: #f2f8f5; border: 1px solid #d6e7de; border-radius: 3px;
}
.lv-draft-badge {
  flex-shrink: 0; margin-left: 8px; padding: 1px 6px; font-size: 10px;
  color: #8a5a2b; background: #fdf6ec; border: 1px solid #f2e3cc; border-radius: 3px;
}

.lv-card-formats { display: flex; flex-wrap: wrap; gap: 4px; margin: 8px 0; }
.lv-format-chip { font-size: 10px; color: #7a8189; background: #f4f6f8; border-radius: 3px; padding: 1px 5px; }

.lv-card-actions { display: flex; flex-wrap: wrap; gap: 6px; }

.lv-credit { padding: 10px 14px 16px; }
.lv-credit-text { display: block; font-size: 10px; color: #b0b5bb; text-align: center; }
</style>
