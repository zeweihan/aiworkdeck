<template>
  <view class="page-project-home">
    <view class="home-topbar">
      <text class="home-back btn-project-list" @tap="goProjectList">{{ $t('projects.backToList') }}</text>
      <text class="home-topbar-title">{{ $t('projects.overviewPageTitle') }}</text>
      <view class="home-enter btn-workbench" @tap="goWorkbench">{{ $t('projects.enterWorkbench') }}</view>
    </view>

    <ProjectHomePane
      v-if="projectId"
      ref="pane"
      :project-id="projectId"
      @open-conversation="onOpenConversation"
    />
  </view>
</template>

<script>
// 项目概览页（产品语言里的「项目概览页」）。注意与 pages/project-overview 区分：
// 后者在代码里是**工作台**，同名不同物。
//
// 2026-08 起本页**不再是产品流程的必经一站**：项目列表点卡片直接 reLaunch 进工作台，
// 概览的主用法是工作台 rail 上的「项目概览」标签（同一个 ProjectHomePane，
// 宿主换成中栏 tab）。本页只留给直链与深链——用户可能有收藏、AI 可能给出链接，
// 路由删掉就是一堆死链，所以薄壳保留。
//
// 内容本体与取数全在 components/project-home/ProjectHomePane.vue，本页只做三件事：
// 读 query、提供顶栏的两个出口、把「打开某条对话」转成进工作台的跳转。
import ProjectHomePane from '@/components/project-home/ProjectHomePane.vue'
import { recordProjectVisit } from '@/utils/recentProjects.js'

export default {
  name: 'ProjectHome',
  components: { ProjectHomePane },
  data() {
    return {
      projectId: 0,
      openFileId: '',
      firstShowDone: false,
    }
  },
  onLoad(query) {
    const id = Number((query && query.id) || 0)
    if (!id) {
      uni.showToast({ title: this.$t('projects.missingProjectParam'), icon: 'none' })
      uni.redirectTo({ url: '/pages/project-list/project-list' })
      return
    }
    this.projectId = id
    // openFileId 本页自己不消费，原样透传给工作台
    this.openFileId = query && query.openFileId ? String(query.openFileId) : ''
    recordProjectVisit(id)
  },
  onShow() {
    if (typeof window !== 'undefined') window.__checkbaProjectHomeVm = this
    // 挂载时 pane 已经拉过一轮，第一次 onShow 跳过，之后每次切回刷一次
    if (!this.firstShowDone) {
      this.firstShowDone = true
      return
    }
    if (this.$refs.pane) this.$refs.pane.refresh()
  },
  mounted() {
    if (typeof window !== 'undefined') window.__checkbaProjectHomeVm = this
  },
  beforeUnmount() {
    // 多实例守卫：只清指向自己的指针。用本页自己的指针名，复用工作台的
    // __checkbaActiveOverviewVm 会让工作台的全局事件被本页拦掉。
    if (typeof window !== 'undefined' && window.__checkbaProjectHomeVm === this) {
      window.__checkbaProjectHomeVm = null
    }
  },
  methods: {
    goWorkbench() {
      let url = `/pages/project-overview/project-overview?id=${this.projectId}`
      if (this.openFileId) url += `&openFileId=${encodeURIComponent(this.openFileId)}`
      uni.reLaunch({ url })
    },
    onOpenConversation(conversationId) {
      if (!conversationId) return
      // 把会话 id 带到工作台；工作台 onLoad 读到 conversationId 会调既有的
      // loadHistoryChat。本页绝不内嵌 ChatInterface —— loadHistoryChat 是完整
      // 切换会话，会在用户还没进工作台时就抢占当前会话。
      const url = `/pages/project-overview/project-overview?id=${this.projectId}`
        + `&conversationId=${encodeURIComponent(conversationId)}`
      uni.reLaunch({ url })
    },
    goProjectList() {
      // 列表与概览会被反复来回点：上一页就是列表时回退，否则 redirectTo 换掉本页。
      // 双向 navigateTo 会在页面栈里堆出多个列表实例（页面栈多实例地雷）。
      const pages = typeof getCurrentPages === 'function' ? getCurrentPages() : []
      const prev = pages.length >= 2 ? pages[pages.length - 2] : null
      if (prev && prev.route === 'pages/project-list/project-list') {
        uni.navigateBack({ delta: 1 })
      } else {
        uni.redirectTo({ url: '/pages/project-list/project-list' })
      }
    },
  },
}
</script>

<!-- 样式单一来源：./project-home.scss（与 project-overview.vue 同形制） -->
<style lang="scss" scoped src="./project-home.scss"></style>
