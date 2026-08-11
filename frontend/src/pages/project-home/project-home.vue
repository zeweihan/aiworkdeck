<template>
  <view class="page-project-home">
    <view class="home-topbar">
      <text class="home-back btn-project-list" @tap="goProjectList">返回项目列表</text>
      <text class="home-topbar-title">项目概览</text>
      <view class="home-enter btn-workbench" @tap="goWorkbench">进入工作台</view>
    </view>

    <view class="home-scroll">
      <view class="home-column">
        <ProfileHeader
          ref="profileHeader"
          :project-id="projectId"
          :project-name="projectName"
          :fields="profileFields"
          :can-edit="canEdit"
          @save="onProfileSave"
        />

        <OverviewStatsBar :stats="stats" :loading="statsLoading" />

        <view class="home-section">
          <text class="home-section-title">动态</text>
          <ActivityFeed
            :versions="versions"
            :background-runs="backgroundRuns"
            :loading="activityLoading"
            :unavailable="activityUnavailable"
          />
        </view>

        <view class="home-section">
          <text class="home-section-title">日程与任务</text>
          <TaskSchedule :tasks="tasks" :loading="tasksLoading" />
        </view>

        <view class="home-section">
          <text class="home-section-title">AI 对话</text>
          <ConversationList
            :conversations="conversations"
            :loading="conversationsLoading"
            :has-more="!!nextBefore"
            @open="onOpenConversation"
            @load-more="onLoadMoreConversations"
          />
        </view>
      </view>
    </view>
  </view>
</template>

<script>
// 项目概览页（产品语言里的「项目概览页」）。注意与 pages/project-overview 区分：
// 后者在代码里是**工作台**，同名不同物。
//
// 本页只做三件事：取数、按卷轴顺序排五个子组件、把子组件事件转成导航或写入。
//
// 轮询纪律：只在 onLoad 与 onShow 各刷一次，A 期不起任何定时器；
// 并且绝不调 /version/status —— 它在 enabled 时会一路跑两次 git add，
// 工作台已有 7 处触发点在喂同一份状态。
import ProfileHeader from '@/components/project-home/ProfileHeader.vue'
import OverviewStatsBar from '@/components/project-home/OverviewStatsBar.vue'
import ActivityFeed from '@/components/project-home/ActivityFeed.vue'
import TaskSchedule from '@/components/project-home/TaskSchedule.vue'
import ConversationList from '@/components/project-home/ConversationList.vue'
import {
  getMyProjects,
  getProjectOverviewStats,
  getProjectProfile,
  saveProjectProfileField,
  getProjectConversations,
  getProjectTasks,
  getVersionTimeline,
} from '@/services/api.js'
import { recordProjectVisit } from '@/utils/recentProjects.js'
import { canEditProfile } from '@/utils/projectHomeFormat.js'

export default {
  name: 'ProjectHome',
  components: { ProfileHeader, OverviewStatsBar, ActivityFeed, TaskSchedule, ConversationList },
  data() {
    return {
      projectId: 0,
      openFileId: '',
      projectName: '',
      canEdit: false,
      profileFields: [],
      stats: {},
      statsLoading: true,
      versions: [],
      activityLoading: true,
      activityUnavailable: false,
      tasks: [],
      tasksLoading: true,
      conversations: [],
      conversationsLoading: true,
      nextBefore: null,
      nextBeforeId: null,
      firstShowDone: false,
      // 请求代：每轮 loadAll() 自增一次。isActiveInstance() 只挡跨实例（切到别的项目）的
      // 过期写入，挡不住同一实例内两轮 loadAll() 之间的乱序——弱网下第一轮的慢请求
      // 可能在第二轮已经刷新完之后才姗姗来迟地 resolve，用旧数据覆盖刚刷新的新数据。
      // 各 loadX 进方法体第一行就记下当时的代号，写回 data 之前比对是否还是当前代，
      // 不是就丢弃这次响应。
      loadGeneration: 0,
    }
  },
  computed: {
    backgroundRuns() {
      return Array.isArray(this.stats.backgroundRuns) ? this.stats.backgroundRuns : []
    },
  },
  onLoad(query) {
    const id = Number((query && query.id) || 0)
    if (!id) {
      uni.showToast({ title: '缺少项目参数', icon: 'none' })
      uni.redirectTo({ url: '/pages/project-list/project-list' })
      return
    }
    this.projectId = id
    // openFileId 本页自己不消费，原样透传给工作台
    this.openFileId = query && query.openFileId ? String(query.openFileId) : ''
    recordProjectVisit(id)
    this.loadAll()
  },
  onShow() {
    if (typeof window !== 'undefined') window.__checkbaProjectHomeVm = this
    // onLoad 已经拉过一轮，第一次 onShow 跳过，之后每次切回刷一次
    if (!this.firstShowDone) {
      this.firstShowDone = true
      return
    }
    if (this.projectId) this.loadAll()
  },
  mounted() {
    if (typeof window !== 'undefined') window.__checkbaProjectHomeVm = this
  },
  beforeUnmount() {
    // 多实例守卫：只清指向自己的指针。用本页自己的指针名，复用工作台的
    // __checkbaActiveOverviewVm（project-overview.vue:2049-2052/:2231/:2288）
    // 会让工作台的全局事件被本页拦掉。
    if (typeof window !== 'undefined' && window.__checkbaProjectHomeVm === this) {
      window.__checkbaProjectHomeVm = null
    }
  },
  methods: {
    isActiveInstance() {
      if (typeof window === 'undefined') return true
      return !window.__checkbaProjectHomeVm || window.__checkbaProjectHomeVm === this
    },
    loadAll() {
      // 每轮取数递增请求代，配合各 loadX 里的比对丢弃过期响应
      this.loadGeneration++
      this.loadProjectCard()
      this.loadProfile()
      this.loadStats()
      this.loadActivity()
      this.loadTasks()
      this.loadConversations({ reset: true })
    },
    async loadProjectCard() {
      const gen = this.loadGeneration
      try {
        // GET /api/projects/my 返回**裸数组**（ProjectController 直接返 List<ProjectCardDTO>），
        // 不是信封。写 res.data 会恒空 —— admin.vue 就是这么坏掉的，别照抄。
        const res = await getMyProjects()
        const list = Array.isArray(res) ? res : []
        const card = list.find((p) => Number(p.id) === this.projectId)
        if (!this.isActiveInstance() || gen !== this.loadGeneration) return
        this.projectName = (card && card.name) || ''
        this.canEdit = canEditProfile(card && card.myRole)
      } catch (e) {
        console.warn('[ProjectHome] 读取项目卡片失败', e)
      }
    },
    async loadProfile() {
      const gen = this.loadGeneration
      try {
        const res = await getProjectProfile(this.projectId)
        if (!this.isActiveInstance() || gen !== this.loadGeneration) return
        this.profileFields = (res && res.data && res.data.fields) || []
      } catch (e) {
        console.warn('[ProjectHome] 读取项目档案失败', e)
      }
    },
    async loadStats() {
      const gen = this.loadGeneration
      this.statsLoading = true
      try {
        const res = await getProjectOverviewStats(this.projectId)
        if (!this.isActiveInstance() || gen !== this.loadGeneration) return
        this.stats = (res && res.data) || {}
      } catch (e) {
        console.warn('[ProjectHome] 读取统计失败', e)
        // 过期代的失败响应不许清掉后来那轮已经写好的新数据
        if (!this.isActiveInstance() || gen !== this.loadGeneration) return
        this.stats = {}
      } finally {
        this.statsLoading = false
      }
    },
    async loadActivity() {
      const gen = this.loadGeneration
      this.activityLoading = true
      this.activityUnavailable = false
      try {
        const res = await getVersionTimeline(this.projectId, 5)
        if (!this.isActiveInstance() || gen !== this.loadGeneration) return
        this.versions = (res && res.data && res.data.versions) || []
      } catch (e) {
        // VersionController.requireMember:562-564 显式拒 CLIENT，客户身份一定走到这里；
        // 后端若还没做「未开仓早退回空 versions」那条修复，未开启版本记录的项目也走这里。
        // 新建项目十有八九没开版本记录，一进概览页就弹错是最差的第一印象：
        // 落成引导态，不弹 toast。
        // 过期代的失败响应不许清掉后来那轮已经写好的新数据
        if (!this.isActiveInstance() || gen !== this.loadGeneration) return
        this.versions = []
        this.activityUnavailable = true
      } finally {
        this.activityLoading = false
      }
    },
    async loadTasks() {
      const gen = this.loadGeneration
      this.tasksLoading = true
      try {
        const res = await getProjectTasks(this.projectId)
        if (!this.isActiveInstance() || gen !== this.loadGeneration) return
        this.tasks = (res && res.data && res.data.tasks) || []
      } catch (e) {
        console.warn('[ProjectHome] 读取任务失败', e)
        if (!this.isActiveInstance() || gen !== this.loadGeneration) return
        this.tasks = []
      } finally {
        this.tasksLoading = false
      }
    },
    async loadConversations(options) {
      const reset = !!(options && options.reset)
      // 翻页（reset=false）不自增代号，只是记下发起时的代号：如果响应回来时代号已经变了，
      // 说明中途整页被 loadAll() 重刷过，这次翻页追加的结果确实该丢，不然会拼出一份
      // 一半新一半旧的列表。
      const gen = this.loadGeneration
      this.conversationsLoading = true
      try {
        // 复合游标成对传：只带 before 会让服务端退化成严格小于，
        // 同一时刻落库的两个会话仍然会丢一条。
        const before = reset ? null : this.nextBefore
        const beforeId = reset ? null : this.nextBeforeId
        const res = await getProjectConversations(this.projectId, { limit: 20, before, beforeId })
        if (!this.isActiveInstance() || gen !== this.loadGeneration) return
        const data = (res && res.data) || {}
        const page = data.conversations || []
        this.conversations = reset ? page : this.conversations.concat(page)
        this.nextBefore = data.nextBefore || null
        this.nextBeforeId = data.nextBeforeId || null
      } catch (e) {
        console.warn('[ProjectHome] 读取对话历史失败', e)
        if (!this.isActiveInstance() || gen !== this.loadGeneration) return
        if (reset) this.conversations = []
        this.nextBefore = null
        this.nextBeforeId = null
      } finally {
        this.conversationsLoading = false
      }
    },
    onLoadMoreConversations() {
      if (!this.nextBefore) {
        // 没有下一页了，第二维游标也不许留在手里
        this.nextBeforeId = null
        return
      }
      this.loadConversations()
    },
    async onProfileSave(payload) {
      try {
        const res = await saveProjectProfileField(this.projectId, payload.fieldKey, payload.value)
        const row = (res && res.data) || null
        if (!row) return
        this.profileFields = this.profileFields.map((f) => (f.fieldKey === row.fieldKey ? row : f))
      } catch (e) {
        // ProfileHeader.commitEdit 是乐观退出：emit('save') 后立刻清空编辑态回退显示旧值。
        // 请求真失败时必须把编辑态和刚敲的字还给用户，否则律师改完「下一步」失焦后
        // 请求一失败，输入就静默消失，界面只回退显示旧值，他不会知道自己丢了什么。
        if (this.$refs.profileHeader) {
          this.$refs.profileHeader.restoreEdit(payload.fieldKey, payload.value)
        }
        uni.showToast({ title: (e && e.message) || '保存失败', icon: 'none' })
      }
    },
    goWorkbench() {
      let url = `/pages/project-overview/project-overview?id=${this.projectId}`
      if (this.openFileId) url += `&openFileId=${encodeURIComponent(this.openFileId)}`
      uni.reLaunch({ url })
    },
    onOpenConversation(conversationId) {
      if (!conversationId) return
      // 把会话 id 带到工作台；工作台侧读 query 走既有 loadHistoryChat 的那一改
      // 由导航组在 Task 16-23 区间落地。概览页绝不内嵌 ChatInterface ——
      // loadHistoryChat 是完整切换会话，会在用户还没进工作台时就抢占当前会话。
      const url = `/pages/project-overview/project-overview?id=${this.projectId}`
        + `&conversationId=${encodeURIComponent(conversationId)}`
      uni.reLaunch({ url })
    },
    goProjectList() {
      // 列表与概览会被反复来回点：上一页就是列表时回退，否则 redirectTo 换掉本页。
      // 双向 navigateTo 会在页面栈里堆出多个列表实例（页面栈多实例地雷）。
      // getCurrentPages 的存在性判定照抄 components/FeedbackWidget.vue:414。
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

<!-- 样式单一来源：./project-home.scss（与 project-overview.vue:4841 同形制） -->
<style lang="scss" scoped src="./project-home.scss"></style>
