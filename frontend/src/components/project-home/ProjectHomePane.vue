<!--
  项目概览的内容本体（一页纸卷轴：档案头 / 统计条 / 动态 / 日程 / 对话）。

  从 pages/project-home/project-home.vue 整体抽出来，为的是**同一份内容有两个宿主**：
   · 工作台**左栏**的「项目概览」面板（主用法，rail 第一个按钮；2026-08-19 之前
     是中栏标签，维护者认为「rail 点了开中栏标签」与其余 rail 项语义不一致）；
   · pages/project-home 独立页（只留给直链与深链，产品流程里不再经过）。

  左栏那个宿主传 compact：260px 起步的窄栏，一页纸的两列布局在那里全会折行，
  统一收成单列并压掉一档字号与间距（样式在 project-home-pane.scss）。

  取数与轮询纪律原样搬来，一条没改：只在挂载与宿主显式调 refresh() 时各刷一次，
  不起定时器；**绝不调 /version/status** —— 它在 enabled 时会一路跑两次 git add，
  工作台已有 7 处触发点在喂同一份状态，这里再打一次是纯浪费且会争 per-project 锁。

  「打开某条对话」不在本组件里决定去向：独立页要 reLaunch 进工作台，工作台里则是
  就地切会话。组件只 emit，宿主自己接。
-->
<template>
  <view class="project-home-pane" :class="{ 'is-compact': compact }">
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
        <text class="home-section-title">{{ $t('projects.activitySectionTitle') }}</text>
        <ActivityFeed
          :versions="versions"
          :background-runs="backgroundRuns"
          :loading="activityLoading"
          :unavailable="activityUnavailable"
        />
      </view>

      <view class="home-section">
        <text class="home-section-title">{{ $t('projects.taskSectionTitle') }}</text>
        <TaskSchedule :tasks="tasks" :loading="tasksLoading" />
      </view>

      <view class="home-section">
        <text class="home-section-title">{{ $t('projects.conversationSectionTitle') }}</text>
        <ConversationList
          :conversations="conversations"
          :loading="conversationsLoading"
          :has-more="!!nextBefore"
          @open="$emit('open-conversation', $event)"
          @load-more="onLoadMoreConversations"
        />
      </view>
    </view>
  </view>
</template>

<script>
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
import { canEditProfile } from '@/utils/projectHomeFormat.js'

export default {
  name: 'ProjectHomePane',
  components: { ProfileHeader, OverviewStatsBar, ActivityFeed, TaskSchedule, ConversationList },
  props: {
    projectId: { type: Number, required: true },
    /** true = 嵌在工作台左栏里（窄栏形态：铺满并自己滚动，内容收成单列） */
    compact: { type: Boolean, default: false },
  },
  emits: ['open-conversation'],
  data() {
    return {
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
      // 请求代：每轮 loadAll() 自增一次。挡的是同一实例内两轮 loadAll() 之间的乱序——
      // 弱网下第一轮的慢请求可能在第二轮已经刷新完之后才姗姗来迟地 resolve，
      // 用旧数据覆盖刚刷新的新数据。各 loadX 进方法体第一行记下当时的代号，
      // 写回 data 之前比对是否还是当前代，不是就丢弃这次响应。
      loadGeneration: 0,
    }
  },
  computed: {
    backgroundRuns() {
      return Array.isArray(this.stats.backgroundRuns) ? this.stats.backgroundRuns : []
    },
  },
  watch: {
    // 工作台里换项目不会重建组件（tab 复用），换了 id 就整轮重取
    projectId() {
      this.loadAll()
    },
  },
  mounted() {
    this.loadAll()
  },
  methods: {
    /** 宿主显式刷新的入口（独立页的 onShow、工作台切回该 tab） */
    refresh() {
      this.loadAll()
    },
    loadAll() {
      if (!this.projectId) return
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
        if (gen !== this.loadGeneration) return
        this.projectName = (card && card.name) || ''
        this.canEdit = canEditProfile(card && card.myRole)
      } catch (e) {
        console.warn('[ProjectHomePane] 读取项目卡片失败', e)
      }
    },
    async loadProfile() {
      const gen = this.loadGeneration
      try {
        const res = await getProjectProfile(this.projectId)
        if (gen !== this.loadGeneration) return
        this.profileFields = (res && res.data && res.data.fields) || []
      } catch (e) {
        console.warn('[ProjectHomePane] 读取项目档案失败', e)
      }
    },
    async loadStats() {
      const gen = this.loadGeneration
      this.statsLoading = true
      try {
        const res = await getProjectOverviewStats(this.projectId)
        if (gen !== this.loadGeneration) return
        this.stats = (res && res.data) || {}
      } catch (e) {
        console.warn('[ProjectHomePane] 读取统计失败', e)
        // 过期代的失败响应不许清掉后来那轮已经写好的新数据
        if (gen !== this.loadGeneration) return
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
        if (gen !== this.loadGeneration) return
        this.versions = (res && res.data && res.data.versions) || []
      } catch (e) {
        // VersionController.requireMember 显式拒 CLIENT，客户身份一定走到这里；
        // 未开启版本记录的项目也走这里。新建项目十有八九没开版本记录，
        // 一进概览就弹错是最差的第一印象：落成引导态，不弹 toast。
        if (gen !== this.loadGeneration) return
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
        if (gen !== this.loadGeneration) return
        this.tasks = (res && res.data && res.data.tasks) || []
      } catch (e) {
        console.warn('[ProjectHomePane] 读取任务失败', e)
        if (gen !== this.loadGeneration) return
        this.tasks = []
      } finally {
        this.tasksLoading = false
      }
    },
    async loadConversations(options) {
      const reset = !!(options && options.reset)
      // 翻页（reset=false）不自增代号，只记下发起时的代号：响应回来时代号变了，
      // 说明中途整页被 loadAll() 重刷过，这次翻页追加的结果确实该丢，
      // 否则会拼出一份一半新一半旧的列表。
      const gen = this.loadGeneration
      this.conversationsLoading = true
      try {
        // 复合游标成对传：只带 before 会让服务端退化成严格小于，
        // 同一时刻落库的两个会话仍然会丢一条。
        const before = reset ? null : this.nextBefore
        const beforeId = reset ? null : this.nextBeforeId
        const res = await getProjectConversations(this.projectId, { limit: 20, before, beforeId })
        if (gen !== this.loadGeneration) return
        const data = (res && res.data) || {}
        const page = data.conversations || []
        this.conversations = reset ? page : this.conversations.concat(page)
        this.nextBefore = data.nextBefore || null
        this.nextBeforeId = data.nextBeforeId || null
      } catch (e) {
        console.warn('[ProjectHomePane] 读取对话历史失败', e)
        if (gen !== this.loadGeneration) return
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
        uni.showToast({ title: (e && e.message) || this.$t('projects.saveFailed'), icon: 'none' })
      }
    },
  },
}
</script>

<style lang="scss" scoped src="./project-home-pane.scss"></style>
