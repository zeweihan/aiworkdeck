<template>
  <view class="cloud-bar">
    <template v-if="!linked">
      <text class="cloud-text cloud-unlinked">{{ unlinkedText }}</text>
      <text class="cloud-open-link" @tap="open(hasConnection ? 'casefile' : 'library')">
        {{ hasConnection ? '放进案件库' : '连接团队案件库' }}
      </text>
    </template>
    <template v-else>
      <text class="cloud-dot" :class="stateClass"></text>
      <text class="cloud-text">{{ stateText }}</text>
      <text class="cloud-open-link" @tap="open('casefile')">打开协作</text>
    </template>
  </view>
</template>

<script>
/**
 * 版本记录面板里的协作状态行——**只读**。
 *
 * 交稿/取回/加人/连案件库四类动作全部收到页面级的协作抽屉（CollabDialog）里，这里
 * 不再放按钮：同一件事有两个入口时，律师分不清「侧栏这个上传」和「顶栏那个交稿」
 * 是不是一回事。留在这里的理由是「案卷同步状态」和「版本记录」在心智上连着——
 * 看得到状态，动作去抽屉。
 *
 * `.cloud-bar` / `.cloud-dot` 是 app-e2e J11 的稳定断言锚点，改 UI 形态时要保留
 * （或成对更新 frontend/tests/app-e2e/run.mjs）。
 */
export default {
  name: 'CloudSyncBar',
  props: {
    // VersionPanel 下发的 cloudStatus 对象：{linked, serverUrl, pendingUpload, remoteAhead, offline}。
    cloud: { type: Object, default: null },
    hasConnection: { type: Boolean, default: false },
    // 有没有等着做选择的文件（三语境任一）与本机手头有没有未收尾的活；
    // 两者都参与状态判定，口径与顶栏协作 chip 完全一致（见 project-overview.collabState）。
    conflictPending: { type: Boolean, default: false },
    working: { type: Boolean, default: false },
  },
  emits: ['open-collab'],
  computed: {
    linked() {
      return !!(this.cloud && this.cloud.linked)
    },
    unlinkedText() {
      return this.hasConnection
        ? '这份案卷还没放进团队案件库'
        : '还没连团队案件库，这份案卷只在你这台电脑上'
    },
    stateText() {
      if (this.conflictPending) return '有文件等你做选择'
      if (this.cloud.offline) return '暂时连不上案件库'
      if (this.cloud.remoteAhead) return '同事交了新稿'
      if (this.cloud.pendingUpload || this.working) return '有改动还没交稿'
      return '和大家的稿一致'
    },
    stateClass() {
      if (this.conflictPending || this.cloud.offline) return 'cloud-dot-yellow'
      if (this.cloud.remoteAhead || this.cloud.pendingUpload || this.working) return 'cloud-dot-blue'
      return 'cloud-dot-green'
    },
  },
  methods: {
    open(tab) {
      this.$emit('open-collab', tab)
    },
  },
}
</script>

<style lang="scss" scoped>
/* 一行里是状态点 + 文字 + 一个链接；窄侧栏下仍然可能挤，沿用 flex-wrap 兜底
   （v1 地雷 #24：flex 默认不换行时溢出的元素仍在 DOM 里、也仍"可见"，但视觉上
   被别的内容盖住，真实点击落空）。 */
.cloud-bar {
  display: flex; align-items: center; flex-wrap: wrap; gap: 12rpx;
  padding: 16rpx 20rpx; border-bottom: 1px solid #eee;
}
.cloud-text { font-size: 26rpx; color: #333; flex: 1; min-width: 200rpx; }
.cloud-unlinked { color: #666; }
.cloud-open-link {
  font-size: 23rpx; color: #12344D; text-decoration: underline; flex-shrink: 0;
}

.cloud-dot {
  width: 14rpx; height: 14rpx; border-radius: 50%; background: #C8A45D; flex-shrink: 0;
}
.cloud-dot-yellow { background: #C8A45D; }
.cloud-dot-blue { background: #3E7CB1; }
.cloud-dot-green { background: #4C9A6A; }
</style>
