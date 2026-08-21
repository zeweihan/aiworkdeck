<template>
  <scroll-view class="timeline" scroll-y>
    <view v-if="loadError" class="timeline-error">
      <text class="timeline-error-desc">{{ $t('version.loadFailedDesc') }}</text>
      <text class="timeline-error-retry" @tap="load">{{ $t('common.retry') }}</text>
    </view>
    <view v-else-if="!versions.length" class="timeline-empty">{{ $t('version.timelineEmpty') }}</view>

    <template v-for="group in grouped" :key="group.head.sha">
      <view
        class="timeline-node"
        :class="{
          'is-session': group.head.kind === 'session',
          'is-merge': isMerge(group.head),
          'is-current': isCurrentHead(group.head),
        }"
      >
        <view class="node-rail">
          <view class="node-rail-line" />
          <svg class="node-marker" viewBox="0 0 40 40">
            <path
              v-if="isMerge(group.head)"
              class="merge-curve"
              d="M 36 2 C 36 18, 22 18, 20 20"
            />
            <circle
              v-if="isCurrentHead(group.head)"
              class="node-dot-halo"
              cx="20" cy="20" r="11"
            />
            <circle
              class="node-dot"
              cx="20" cy="20"
              :r="isCurrentHead(group.head) ? 7 : 6"
            />
          </svg>
        </view>
        <view class="node-main" @tap="select(group.head)">
          <view class="node-title" :class="{ 'has-milestone': group.head.milestone }">
            <text v-if="group.head.milestone" class="milestone-flag">{{ $t('version.milestoneFlag') }}</text>
            {{ group.head.milestone || titleOf(group.head) }}
          </view>
          <view class="node-meta">{{ group.head.authorName }} · {{ timeOf(group.head) }}</view>
        </view>

        <view
          v-if="group.autos.length"
          class="node-autos-toggle"
          @tap="toggle(group.head.sha)"
        >
          {{ expanded[group.head.sha] ? $t('version.collapse') : $t('version.autoSaveCount', { count: group.autos.length }) }}
        </view>
        <view v-if="expanded[group.head.sha]" class="node-autos">
          <view
            v-for="a in group.autos"
            :key="a.sha"
            class="node-auto"
            @tap="select(a)"
          >
            <text class="auto-time">{{ timeOf(a) }}</text>
            <text class="auto-msg" :class="{ 'has-milestone': a.milestone }">
              <text v-if="a.milestone" class="milestone-flag">{{ $t('version.milestoneFlag') }}</text>
              {{ a.milestone || a.message }}
            </text>
          </view>
        </view>
      </view>

      <!-- Phase B：进行中稿件的分叉线。只有当稿的分叉点落在当前拉到的主线历史范围内
           才画得出来（后端端点未就绪/出错/分叉点在窗口外都优雅降级为不画，见 loadDraftBranches）。-->
      <view
        v-if="draftBranchesFor(group.head.sha).length"
        class="draft-branch-row"
      >
        <view
          v-for="db in draftBranchesFor(group.head.sha)"
          :key="db.draft.id"
          class="draft-branch"
        >
          <svg class="draft-branch-fork" viewBox="0 0 80 40" preserveAspectRatio="none">
            <path class="draft-fork-curve" d="M 20 0 C 20 24, 60 16, 60 40" />
          </svg>
          <view class="draft-branch-body">
            <view class="draft-branch-name">{{ db.draft.name || $t('version.unnamedDraft') }}</view>
            <view
              v-for="dv in db.versions"
              :key="dv.sha"
              class="draft-branch-node"
            >
              <view class="draft-node-rail">
                <view class="draft-node-rail-line" />
                <view class="draft-node-dot" />
              </view>
              <view class="draft-node-main">
                <view class="draft-node-title">{{ titleOf(dv) }}</view>
                <view class="draft-node-meta">{{ dv.authorName }} · {{ timeOf(dv) }}</view>
              </view>
            </view>
          </view>
        </view>
      </view>
    </template>

    <VersionNodeDetail
      v-if="selected"
      :project-id="projectId"
      :version="selected"
      @close="selected = null"
      @reload-files="onReload"
      @compare-file="$emit('compare-file', $event)"
      @milestoned="onMilestoned"
      @draft-created="onDraftCreated"
    />
  </scroll-view>
</template>

<script>
import { getVersionTimeline, getDraftTimeline } from '@/services/api.js'
import VersionNodeDetail from './VersionNodeDetail.vue'

export default {
  name: 'VersionTimeline',
  components: { VersionNodeDetail },
  props: {
    projectId: { type: [String, Number], required: true },
    fileFilter: { type: Object, default: null },
    // 进行中的稿列表（{id,name,startedAt}），来自 VersionPanel 的 /drafts。
    // 用来在主线 graph 上画出各自的分叉线（Phase B）。
    drafts: { type: Array, default: () => [] },
  },
  emits: ['reload-files', 'compare-file', 'draft-created'],
  data() {
    return { versions: [], expanded: {}, selected: null, loadError: false, draftBranches: [], loadSeq: 0 }
  },
  watch: {
    fileFilter() {
      this.load()
    },
    // /drafts 可能在本组件挂载之后才更新（VersionPanel.refresh() 里 timelineKey 先
    // 自增触发重新挂载，稿列表随后才 await 回来），挂载时那次 load() 里读到的可能
    // 是旧值，靠这个 watcher 兜底重算分叉线。
    drafts() {
      this.loadDraftBranches()
    },
  },
  computed: {
    // 工作段是主线节点，自动存档折进它下面。
    grouped() {
      const out = []
      let current = null
      for (const v of this.versions) {
        if (v.kind === 'session' || !current) {
          current = { head: v, autos: [] }
          out.push(current)
        } else {
          current.autos.push(v)
        }
      }
      return out
    },
  },
  mounted() {
    this.load()
  },
  methods: {
    // fileFilter 每变一次就重新 load()，前一次在途的请求不会被取消。先点文件过滤、
    // 紧接着点「查看全部」时，两个请求带着不同 fileId 前后脚发出，先发的那个若后回，
    // 会把已经渲染好的全量时间线覆盖成过滤结果——列表内容和当前过滤状态对不上，
    // isCurrentHead 拿实时 fileFilter 配陈旧 versions 还会高亮错节点。用自增序号
    // 只认最后一次发出的请求的响应。
    async load() {
      const seq = ++this.loadSeq
      try {
        const res = await getVersionTimeline(this.projectId, 50, this.fileFilter && this.fileFilter.fileId)
        if (seq !== this.loadSeq) return
        this.versions = ((res && res.data && res.data.versions) || [])
        this.loadError = false
      } catch (e) {
        if (seq !== this.loadSeq) return
        console.warn('[Version] 读取时间线失败', e)
        this.loadError = true
        uni.showToast({ title: this.$t('version.loadFailedToast'), icon: 'none' })
      }
      await this.loadDraftBranches()
    },
    // sha -> 它所属那个主线节点（session 头）的 sha，覆盖 head 自己与折叠在它下面的
    // 自动存档，用来判断某个稿的分叉点落在 graph 上的哪一行。
    buildShaToGroupHeadMap() {
      const map = {}
      for (const g of this.grouped) {
        map[g.head.sha] = g.head.sha
        for (const a of g.autos) map[a.sha] = g.head.sha
      }
      return map
    },
    // 找每个进行中稿的分叉点：稿自己时间线里最早一条（数组最后一个，log 从新到旧）
    // 的 parent，就是它从主线分出去的那一版。只有这个 sha 落在当前拉到的主线历史
    // 范围内才画得出线——404/出错/分叉点在窗口外一律优雅降级为不画，不报错弹窗。
    async loadDraftBranches() {
      if (!this.drafts.length || !this.versions.length) {
        this.draftBranches = []
        return
      }
      const shaToGroupHead = this.buildShaToGroupHeadMap()
      const mainShas = new Set(Object.keys(shaToGroupHead))
      const results = []
      for (const d of this.drafts) {
        try {
          const res = await getDraftTimeline(this.projectId, d.id, 50)
          const draftVersions = ((res && res.data && res.data.versions) || [])
          if (!draftVersions.length) continue
          const earliest = draftVersions[draftVersions.length - 1]
          const forkSha = earliest.parents && earliest.parents[0]
          if (!forkSha) continue
          const anchorSha = shaToGroupHead[forkSha]
          if (!anchorSha) continue
          const ownVersions = draftVersions.filter(v => !mainShas.has(v.sha))
          if (!ownVersions.length) continue
          results.push({ draft: d, anchorSha, versions: ownVersions })
        } catch (e) {
          // 后端 drafts/{id}/timeline 端点可能还没上线，或这一次请求失败——
          // 不影响主线 graph 渲染，静默跳过这个稿的分叉线。
          console.warn('[Version] 读取稿时间线失败，降级为不画分叉线', d && d.id, e)
        }
      }
      this.draftBranches = results
    },
    draftBranchesFor(sha) {
      return this.draftBranches.filter(db => db.anchorSha === sha)
    },
    isMerge(v) {
      return !!(v && v.parents && v.parents.length === 2)
    },
    // 只在未按文件过滤的完整主线视图里高亮"当前版本"——过滤视图里排在最上面的
    // 只是"最近一次动过这份文件的版本"，不一定是真正的 HEAD。
    isCurrentHead(v) {
      return !this.fileFilter && !!this.grouped.length && this.grouped[0].head.sha === v.sha
    },
    titleOf(v) {
      return v.note || v.message
    },
    timeOf(v) {
      const d = new Date(v.when)
      const pad = (n) => String(n).padStart(2, '0')
      return this.$t('common.dateTimeMdHm', {
        month: d.getMonth() + 1, day: d.getDate(), time: `${pad(d.getHours())}:${pad(d.getMinutes())}`,
      })
    },
    toggle(sha) {
      this.expanded = { ...this.expanded, [sha]: !this.expanded[sha] }
    },
    select(v) {
      this.selected = v
    },
    // 标记重要版本之后：load() 会把 versions 整个换成新对象，而 selected 还指着
    // 旧对象——弹窗上的按钮仍写着「标为重要版本」，看起来像没生效。重拉之后把
    // selected 重新指到同一个 sha 的新条目上（拿到的 milestone 字段是服务端权威值）。
    async onMilestoned() {
      const sha = this.selected && this.selected.sha
      await this.load()
      if (!sha) return
      const fresh = this.versions.find(v => v.sha === sha)
      if (fresh) this.selected = fresh
    },
    onReload(affectedFileIds) {
      this.selected = null
      this.load()
      this.$emit('reload-files', affectedFileIds || [])
    },
    // 从某个历史版本另起一稿：HEAD 切到了新稿分支，本页时间线（按 HEAD 取历史）
    // 要重新拉取，否则还停在切线前那条线的历史上。
    onDraftCreated(affectedFileIds) {
      this.selected = null
      this.load()
      this.$emit('draft-created', affectedFileIds || [])
    },
  },
}
</script>

<style lang="scss" scoped>
$rail-main: #1A5336;
$rail-main-line: rgba(26, 83, 54, .28);
$rail-draft: #5BD197;
$rail-draft-line: rgba(91, 209, 151, .45);

.timeline { flex: 1; padding: 12rpx 0; }
.timeline-empty { padding: 24rpx; color: #999; font-size: 26rpx; }
.timeline-error { padding: 24rpx; display: flex; align-items: center; gap: 16rpx; }
.timeline-error-desc { font-size: 26rpx; color: #b23; }
.timeline-error-retry { font-size: 26rpx; color: #12344D; text-decoration: underline; }

.timeline-node { position: relative; padding: 16rpx 20rpx 16rpx 56rpx; }
.timeline-node.is-current { background: rgba(26, 83, 54, .05); }

.node-rail { position: absolute; left: 8rpx; top: 0; bottom: 0; width: 40rpx; pointer-events: none; }
.node-rail-line {
  position: absolute; left: 20rpx; top: 0; bottom: 0; width: 4rpx; background: $rail-main-line;
}
.node-marker { position: absolute; left: 0; top: 6rpx; width: 40rpx; height: 40rpx; }
.merge-curve { fill: none; stroke: $rail-main-line; stroke-width: 3; }
.node-dot { fill: $rail-main; stroke: #fff; stroke-width: 2; }
.node-dot-halo { fill: none; stroke: $rail-main; stroke-width: 2; opacity: .3; }

.timeline-node.is-session .node-title { font-weight: 600; }
.timeline-node.is-current .node-title { color: $rail-main; }
.node-title { font-size: 27rpx; color: #222; }
.node-meta { font-size: 23rpx; color: #999; margin-top: 6rpx; }
.node-autos-toggle { font-size: 23rpx; color: #12344D; margin-top: 10rpx; }
.node-autos { margin-top: 10rpx; padding-left: 12rpx; border-left: 2rpx dashed #ddd; }
.node-auto { display: flex; gap: 12rpx; padding: 8rpx 0; }
.auto-time { font-size: 23rpx; color: #aaa; flex-shrink: 0; }
.auto-msg { font-size: 23rpx; color: #666; }
.milestone-flag { font-size: 20rpx; color: #C8A45D; border: 1px solid #C8A45D; border-radius: 4rpx; padding: 2rpx 8rpx; margin-right: 8rpx; }
.has-milestone { font-weight: 600; }

// ---- Phase B：进行中稿件的第二泳道，从分叉点所在的主线节点行下方长出来 ----
.draft-branch-row { position: relative; padding-left: 56rpx; }
.draft-branch { position: relative; padding-left: 40rpx; }
.draft-branch-fork {
  position: absolute; left: 8rpx; top: 0; width: 80rpx; height: 40rpx; overflow: visible;
  pointer-events: none;
}
.draft-fork-curve { fill: none; stroke: $rail-draft-line; stroke-width: 3; }
.draft-branch-body {
  padding: 4rpx 16rpx 12rpx 16rpx; margin-top: 4rpx;
  border-left: 2rpx dashed $rail-draft-line;
}
.draft-branch-name {
  font-size: 23rpx; color: $rail-draft; font-weight: 600; margin-bottom: 6rpx;
}
.draft-branch-node { position: relative; display: flex; padding: 6rpx 0 6rpx 32rpx; }
.draft-node-rail { position: absolute; left: 0; top: 0; bottom: 0; width: 24rpx; }
.draft-node-rail-line {
  position: absolute; left: 10rpx; top: 0; bottom: 0; width: 3rpx; background: $rail-draft-line;
}
.draft-node-dot {
  position: absolute; left: 4rpx; top: 10rpx; width: 14rpx; height: 14rpx; border-radius: 50%;
  background: $rail-draft; border: 2rpx solid #fff;
}
.draft-node-main { flex: 1; min-width: 0; }
.draft-node-title { font-size: 24rpx; color: #333; }
.draft-node-meta { font-size: 21rpx; color: #999; margin-top: 4rpx; }
</style>
