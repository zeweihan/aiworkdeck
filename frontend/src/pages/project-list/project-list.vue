<template>
  <view class="page-project-list">
    <view class="project-list-container">
      <view class="main-content">
        <view class="content-header">
          <text class="header-title">{{ $t('projects.myProjects') }}</text>
          <!-- header-actions 本身不受角色/项目数门控：CLIENT 或零项目新用户否则在本页
               找不到任何通往个人中心的入口（登出/设置/解除授权全部不可达）。
               门控只收窄到「新建项目/取案卷」这两个写操作按钮上。 -->
          <view class="header-actions">
            <!-- 视图切换：方块 / 列表。案卷多起来之后方块视图一屏放不下几个，
                 也塞不进客户与时间；列表视图是「一眼扫完」的形态。选择记在本机。 -->
            <view v-if="projects.length > 0" class="view-toggle">
              <view
                class="view-toggle-btn"
                :class="{ active: viewMode === 'grid' }"
                :title="$t('projects.gridView')"
                @tap="setViewMode('grid')"
              >
                <svg class="view-toggle-glyph" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
                  <path v-for="(d, gi) in ICONS.gridView" :key="gi" :d="d" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round" />
                </svg>
              </view>
              <view
                class="view-toggle-btn"
                :class="{ active: viewMode === 'list' }"
                :title="$t('projects.listView')"
                @tap="setViewMode('list')"
              >
                <svg class="view-toggle-glyph" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
                  <path v-for="(d, gi) in ICONS.listView" :key="gi" :d="d" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round" />
                </svg>
              </view>
            </view>
            <!-- 「详情」：把档案里其余四项补出来。做成开关而不是逐行展开，是为了
                 让每一行等高、整列能对齐扫读——逐行展开的表格扫起来最费眼。 -->
            <view
              v-if="projects.length > 0"
              class="detail-toggle"
              :class="{ active: showDetail }"
              :title="$t('projects.detailToggleHint')"
              @tap="setShowDetail(!showDetail)"
            >
              <text class="detail-toggle-text">{{ $t('projects.detailToggle') }}</text>
            </view>
            <!-- 新建：页头一个主按钮（桌面端弹「打开文件夹 / 新建项目文件夹」两选一），
                 列表下方那两张卡片保留——那里是零项目新用户的落点，页头这个是
                 「已经有一堆案卷、想再开一个」的人的落点，两处服务的不是同一刻。 -->
            <button v-if="!isClientUser" class="btn-create-primary" :disabled="busy" @tap="onCreateProject">
              <text class="btn-create-plus">＋</text>{{ $t('projects.newProject') }}
            </button>
            <template v-if="!isClientUser && projects.length > 0">
              <button class="btn-secondary-small" @tap="openCloudAccept">{{ $t('projects.pullFromTeamLibrary') }}</button>
            </template>
            <button class="btn-secondary-small" @tap="goToCalendar">{{ $t('projects.calendarEntry') }}</button>
            <button class="btn-secondary-small" @tap="goToUserProfile">{{ $t('projects.personalCenter') }}</button>
          </view>
        </view>

        <view class="panel-projects">
          <!-- 只留「全部项目」一张卡：原先的「进行中」「已完成」是写死的字面量 0，
               Project 实体根本没有状态字段，搬迁时按 spec §4.3 删掉，不把假数字带过来 -->
          <view class="projects-stats-row">
            <view class="stat-card">
              <text class="stat-value">{{ projects.length }}</text>
              <text class="stat-label">{{ $t('projects.allProjects') }}</text>
            </view>
          </view>

          <view v-if="projectsLoading" class="loading-state">
            <text class="loading-text">{{ $t('projects.loading') }}</text>
          </view>

          <view v-else-if="projects.length === 0">
            <!-- CLIENT 没有建项目/取案卷的入口，空态只作说明 -->
            <template v-if="isClientUser">
              <view class="empty-state-dashed client-empty">
                <view class="dashed-content">
                  <text class="dashed-text">{{ $t('projects.clientEmptyHint') }}</text>
                </view>
              </view>
            </template>
            <template v-else>
              <view class="empty-state-dashed">
                <view class="dashed-content">
                  <text class="dashed-text">{{ $t('projects.emptyHint') }}</text>
                </view>
              </view>
              <!-- 协作的唯一入口。CollabDialog 的邀请话术写死指向这里，别删 -->
              <view class="cloud-accept-entry" @tap="openCloudAccept">
                <text class="cloud-accept-entry-text">{{ $t('projects.pullFromTeamLibrary') }}</text>
              </view>
            </template>
          </view>

          <view v-else-if="viewMode === 'grid'" class="project-grid">
            <view
              v-for="project in projects"
              :key="project.id"
              class="project-item-card"
              :class="getProjectCardClass(project.projectType)"
              @tap="goToProject(project.id)"
            >
              <view class="card-deco-header"></view>

              <view class="card-top-row">
                <!-- 「空白项目」这个标签不再渲染：绝大多数案卷都是 BLANK，一屏
                     全是同一个词，占着卡片最显眼的一行却什么也没说。非 BLANK 的
                     历史项目（重组/收购/定增…）保留标签，那里它确实是信息。 -->
                <view v-if="project.projectType && project.projectType !== 'BLANK'" class="project-type-badge-new">
                  <text class="badge-text-new">{{ getProjectTypeLabel(project.projectType) }}</text>
                </view>
                <view v-else class="card-top-spacer"></view>
                <view v-if="!isClientUser" class="card-actions">
                  <view class="action-btn-icon danger" @tap.stop="handleDeleteProject(project.id)" :title="$t('projects.delete')"><svg class="act-glyph" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg"><path v-for="(d, gi) in ICONS.trash" :key="gi" :d="d" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round" /></svg></view>
                </view>
              </view>

              <view class="card-main-content">
                <view class="project-title-area">
                  <view v-if="renamingProjectId === project.id" class="rename-box" @tap.stop>
                    <input
                      class="rename-input"
                      v-model="renameValue"
                      :focus="true"
                      @confirm="confirmRename"
                      @blur="cancelRename"
                    />
                  </view>
                  <view v-else class="title-row-flex">
                    <text class="project-title-new" @tap.stop="startRename(project)">{{ project.name }}</text>
                    <view class="project-role-badge" :class="getRoleClass(project.myRole)">
                      <text class="role-text">{{ getRoleLabel(project.myRole) }}</text>
                    </view>
                  </view>
                </view>

                <!-- 卡片正文：原先 BLANK 项目这里只有一句「通用项目工作区」，
                     等于一张没有任何有效信息的卡。改成真实字段——客户与最近修改。 -->
                <view class="company-info-area">
                  <view class="info-row-new" v-if="shouldShowListedCompany(project.projectType)">
                    <text class="info-label-new">{{ $t('projects.listedCompany') }}</text>
                    <text class="info-val-new highlight">{{ project.listedCompanyName || '-' }}</text>
                  </view>
                  <view class="info-row-new" v-if="shouldShowTargetCompany(project.projectType)">
                    <text class="info-label-new">{{ $t('projects.targetCompany') }}</text>
                    <text class="info-val-new">{{ project.targetCompanyName || '-' }}</text>
                  </view>
                  <view class="info-row-new" v-if="showClientRow(project)">
                    <text class="info-label-new">{{ $t('projects.clientColumn') }}</text>
                    <text class="info-val-new">{{ clientText(project) }}</text>
                  </view>
                  <view class="info-row-new">
                    <text class="info-label-new">{{ $t('projects.updatedColumn') }}</text>
                    <text class="info-val-new">{{ formatTime(project.lastActivityAt) || '—' }}</text>
                  </view>
                  <!-- 档案其余四项：开了「详情」且这一项真填过才出现 -->
                  <view v-if="showDetail" v-for="f in detailFields(project)" :key="f.key" class="info-row-new">
                    <text class="info-label-new">{{ f.label }}</text>
                    <text class="info-val-new">{{ f.value }}</text>
                  </view>
                </view>
              </view>

              <view class="card-footer-new">
                <view class="members-area-new">
                  <view class="manager-avatar-wrapper" v-if="project.managerId" :title="$t('projects.managerLabel', { name: project.managerName || $t('projects.unknown') })">
                    <image v-if="project.managerAvatarUrl" :src="project.managerAvatarUrl" class="manager-avatar-img" />
                    <view v-else class="manager-avatar-placeholder">{{ project.managerName?.charAt(0) || 'M' }}</view>
                    <view class="manager-badge-icon"><svg class="badge-glyph" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg"><path v-for="(d, gi) in ICONS.crown" :key="gi" :d="d" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round" /></svg></view>
                  </view>
                  <view class="members-divider" v-if="project.managerId && getInternalMembers(project).length > 0"></view>

                  <view class="members-split-container">
                    <view class="members-group">
                      <view v-for="member in getInternalMembers(project)" :key="member.id" class="member-avatar-new" :title="member.displayName">
                        <image v-if="member.avatarUrl" :src="member.avatarUrl" class="avatar-img-new" />
                        <view v-else class="avatar-placeholder-new">{{ member.displayName?.charAt(0) || 'U' }}</view>
                        <view v-if="canManageMembers(project) && member.userId !== userInfo.id" class="member-remove-overlay" @tap.stop="removeMember(project.id, member.userId)">×</view>
                      </view>
                      <view v-if="canManageMembers(project)" class="add-member-btn-new" @tap.stop="openInviteModal(project.id)">+</view>
                    </view>

                    <view class="members-vertical-divider" v-if="getClientMembers(project).length > 0"></view>

                    <view class="members-group clients-group" v-if="getClientMembers(project).length > 0">
                      <text class="client-group-label">{{ $t('projects.clientLabel') }}</text>
                      <view v-for="member in getClientMembers(project)" :key="member.id" class="member-avatar-new client-avatar" :title="$t('projects.clientMemberTitle', { name: member.displayName })">
                        <image v-if="member.avatarUrl" :src="member.avatarUrl" class="avatar-img-new" />
                        <view v-else class="avatar-placeholder-new client-placeholder">{{ member.displayName?.charAt(0) || $t('projects.clientInitial') }}</view>
                        <view v-if="canManageMembers(project)" class="member-remove-overlay" @tap.stop="removeMember(project.id, member.userId)">×</view>
                      </view>
                    </view>
                  </view>
                </view>
                <view class="footer-meta">
                  <text class="time-text-new">{{ $t('projects.createdAtShort', { time: formatTime(project.createdAt) }) }}</text>
                  <view class="enter-btn-arrow">
                    <text class="arrow-char">→</text>
                  </view>
                </view>
              </view>
            </view>
          </view>

          <!-- 列表视图。方块视图一行只放得下三张卡、每张卡还只承载两三个字段；
               案卷上了两位数之后要找一份具体的案卷，列表才是能一眼扫完的形态。
               列名与方块视图承载的是同一批字段，没有哪个视图独占信息。 -->
          <view v-else class="project-table">
            <view class="ptable-head">
              <text class="ptable-col col-name">{{ $t('projects.nameColumn') }}</text>
              <text class="ptable-col col-client">{{ $t('projects.clientColumn') }}</text>
              <text class="ptable-col col-time col-created">{{ $t('projects.createdColumn') }}</text>
              <text class="ptable-col col-time col-updated">{{ $t('projects.updatedColumn') }}</text>
              <text class="ptable-col col-members">{{ $t('projects.membersColumn') }}</text>
              <text class="ptable-col col-ops"></text>
            </view>
            <!-- 一行案卷 = 主行（常显字段）+ 可选的详情行。v-for 挂在外层 .ptable-item
                 上而不是主行上，两行才能共用同一次悬停与同一条下边框。 -->
            <view
              v-for="project in projects"
              :key="project.id"
              class="ptable-item"
            >
            <view class="ptable-row" @tap="goToProject(project.id)">
              <view class="ptable-col col-name">
                <view v-if="renamingProjectId === project.id" class="rename-box" @tap.stop>
                  <input
                    class="rename-input"
                    v-model="renameValue"
                    :focus="true"
                    @confirm="confirmRename"
                    @blur="cancelRename"
                  />
                </view>
                <template v-else>
                  <text class="ptable-name">{{ project.name }}</text>
                  <view class="project-role-badge" :class="getRoleClass(project.myRole)">
                    <text class="role-text">{{ getRoleLabel(project.myRole) }}</text>
                  </view>
                  <view v-if="project.projectType && project.projectType !== 'BLANK'" class="project-type-badge-new">
                    <text class="badge-text-new">{{ getProjectTypeLabel(project.projectType) }}</text>
                  </view>
                </template>
              </view>
              <view class="ptable-col col-client">
                <text class="ptable-sub" :class="{ 'is-inferred': clientIsInferred(project) }">{{ clientText(project) }}</text>
                <text v-if="clientIsInferred(project)" class="inferred-tag">{{ $t('projects.clientInferred') }}</text>
              </view>
              <text class="ptable-col col-time col-created ptable-sub">{{ formatTime(project.createdAt) || '—' }}</text>
              <text class="ptable-col col-time col-updated ptable-sub">{{ formatTime(project.lastActivityAt) || '—' }}</text>
              <view class="ptable-col col-members">
                <view class="manager-avatar-wrapper" v-if="project.managerId" :title="$t('projects.managerLabel', { name: project.managerName || $t('projects.unknown') })">
                  <image v-if="project.managerAvatarUrl" :src="project.managerAvatarUrl" class="manager-avatar-img" />
                  <view v-else class="manager-avatar-placeholder">{{ project.managerName?.charAt(0) || 'M' }}</view>
                  <view class="manager-badge-icon"><svg class="badge-glyph" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg"><path v-for="(d, gi) in ICONS.crown" :key="gi" :d="d" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round" /></svg></view>
                </view>
                <view v-for="member in getInternalMembers(project)" :key="member.id" class="member-avatar-new" :title="member.displayName">
                  <image v-if="member.avatarUrl" :src="member.avatarUrl" class="avatar-img-new" />
                  <view v-else class="avatar-placeholder-new">{{ member.displayName?.charAt(0) || 'U' }}</view>
                </view>
                <view v-for="member in getClientMembers(project)" :key="member.id" class="member-avatar-new client-avatar" :title="$t('projects.clientMemberTitle', { name: member.displayName })">
                  <image v-if="member.avatarUrl" :src="member.avatarUrl" class="avatar-img-new" />
                  <view v-else class="avatar-placeholder-new client-placeholder">{{ member.displayName?.charAt(0) || $t('projects.clientInitial') }}</view>
                </view>
                <view v-if="canManageMembers(project)" class="add-member-btn-new" @tap.stop="openInviteModal(project.id)">+</view>
              </view>
              <view class="ptable-col col-ops">
                <!-- 列表里点行是「打开」，所以改名不能再挂在名字上（方块视图那边
                     点标题改名的老交互保留不动），单独给一个动作按钮。 -->
                <view v-if="!isClientUser" class="action-btn-icon" @tap.stop="startRename(project)" :title="$t('projects.rename')">
                  <svg class="act-glyph" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg"><path v-for="(d, gi) in ICONS.pencil" :key="gi" :d="d" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round" /></svg>
                </view>
                <view v-if="!isClientUser" class="action-btn-icon danger" @tap.stop="handleDeleteProject(project.id)" :title="$t('projects.delete')">
                  <svg class="act-glyph" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg"><path v-for="(d, gi) in ICONS.trash" :key="gi" :d="d" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round" /></svg>
                </view>
              </view>
            </view>
            <!-- 详情行：开了开关、且这份案卷的档案里真有东西才出现。
                 一项没填的案卷不留空行——那只会让列表高低不齐还什么都没说。 -->
            <view
              v-if="showDetail && detailFields(project).length"
              class="ptable-detail"
              @tap="goToProject(project.id)"
            >
              <view v-for="f in detailFields(project)" :key="f.key" class="detail-chip">
                <text class="detail-chip-label">{{ f.label }}</text>
                <text class="detail-chip-value">{{ f.value }}</text>
              </view>
            </view>
            </view>
          </view>

          <!-- 新建：放在列表下方。桌面端就是「打开一个已有文件夹」与「新建一个项目
               文件夹」两件事——本产品的项目 == 磁盘上的一个文件夹（localRoot），
               所以「单独打开一个文件」那条已经去掉：它造出的是个没有归属的临时项目，
               律师下次找不到它在哪。浏览器版没有系统文件夹对话框，降级为托管空白项目。 -->
          <view v-if="!isClientUser" class="create-section">
            <text class="create-section-title">{{ $t('projects.createSectionTitle') }}</text>
            <view class="create-row">
              <template v-if="isDesktop">
                <view class="create-card" :class="{ 'is-busy': busy }" @tap="onOpenFolder">
                  <svg class="create-glyph" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
                    <path v-for="(d, gi) in ICONS.folderOpen" :key="gi" :d="d" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round" />
                  </svg>
                  <view class="create-text">
                    <text class="create-title">{{ $t('account.openFolderTitle') }}</text>
                    <text class="create-desc">{{ $t('account.openFolderDesc') }}</text>
                  </view>
                </view>
                <view class="create-card" :class="{ 'is-busy': busy }" @tap="onCreateFolder">
                  <svg class="create-glyph" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
                    <path v-for="(d, gi) in ICONS.folderPlus" :key="gi" :d="d" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round" />
                  </svg>
                  <view class="create-text">
                    <text class="create-title">{{ $t('account.createFolderTitle') }}</text>
                    <text class="create-desc">{{ $t('account.createFolderDesc') }}</text>
                  </view>
                </view>
              </template>
              <view v-else class="create-card" @tap="goToNewProject">
                <svg class="create-glyph" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
                  <path v-for="(d, gi) in ICONS.folderPlus" :key="gi" :d="d" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round" />
                </svg>
                <view class="create-text">
                  <text class="create-title">{{ $t('projects.newProject') }}</text>
                  <text class="create-desc">{{ $t('account.webHint') }}</text>
                </view>
              </view>
            </view>
            <text v-if="busy" class="create-busy-hint">{{ busyText }}</text>
          </view>
        </view>
      </view>
    </view>

    <!-- 新建项目文件夹：命名弹窗（与原 newproject 页同一套流程） -->
    <view v-if="namingVisible" class="naming-mask" @tap.self="namingVisible = false">
      <view class="naming-dialog">
        <text class="naming-title">{{ $t('account.createFolderDialogTitle') }}</text>
        <text class="naming-location">{{ $t('account.locationLabel', { path: namingParentDir }) }}</text>
        <input
          class="naming-input"
          type="text"
          :placeholder="$t('account.folderNamePlaceholder')"
          :value="namingName"
          :focus="namingVisible"
          @input="e => { namingName = e.detail && e.detail.value }"
          @confirm="confirmCreateFolder"
        />
        <view class="naming-actions">
          <button class="btn-secondary-small" @tap="namingVisible = false">{{ $t('common.cancel') }}</button>
          <button class="btn-primary-small" :disabled="!namingNameValid || busy" @tap="confirmCreateFolder">{{ $t('account.createBtn') }}</button>
        </view>
      </view>
    </view>

    <InviteMemberDialog
      v-model:visible="showInviteModal"
      :project-id="currentInviteProjectId"
      @success="loadProjects"
      @close="closeInviteModal"
    />

    <CloudAcceptDialog
      v-model:visible="showCloudAccept"
      @accepted="onCloudAccepted"
    />
  </view>
</template>

<script>
/**
 * 项目列表页（三级导航第一级）。
 *
 * 2026-08-08 从 pages/userprofile/userprofile.vue 的「我的项目」tab 整块搬出：
 * 项目寄居在个人中心的一个 tab 里，是前端把项目概念弱化掉的历史遗留（后端 Project
 * 一直是一等公民）。搬出来之后个人中心只管人，这里只管案卷。
 *
 * 点卡片主体进的是**项目概览页**（pages/project-home），不是工作台——工作台是第三级。
 *
 * 本页不做 host.browser.setViewsVisible(false)：非工作台页面不必自己隐藏 BrowserView，
 * 兜底在工作台的 onHide/onUnload（admin / plugin-market / variable-library 都没做）。
 */
import { getMyProjects, deleteProject, renameProject, getProjectMembers, removeProjectMember, getCurrentUser as getCurrentUserApi } from '@/services/api.js'
import { getProjectTypeLabel } from '@/config/projectTypes.js'
import { roleLabel, ROLE_LABELS } from '@/config/memberRoles.js'
import { getCurrentUser, getSessionId } from '@/utils/auth.js'
import { isDesktopHost, host } from '@/services/host.js'
import { openFolderFlow, createFolderFlow } from '@/utils/ideOpen.js'
import { ICONS } from '@/config/icons.js'
import InviteMemberDialog from '@/components/InviteMemberDialog.vue'
import CloudAcceptDialog from '@/components/CloudAcceptDialog.vue'

const VIEW_MODE_KEY = 'checkba_project_list_view'
const DETAIL_KEY = 'checkba_project_list_detail'

export default {
  name: 'ProjectList',
  components: {
    InviteMemberDialog,
    CloudAcceptDialog,
  },
  computed: {
    ICONS() {
      return ICONS
    },
    isDesktop() {
      // 判据是「有没有系统文件夹对话框」而不是「是不是桌面壳」：新建入口用的正是它，
      // 老版本壳没有 fs 命名空间时该降级到浏览器路径而不是给出点不动的按钮
      return isDesktopHost() && !!(host.fs && host.fs.showOpenDialog)
    },
    namingNameValid() {
      const n = (this.namingName || '').trim()
      return !!n && !n.includes('/') && !n.includes('\\') && n !== '.' && n !== '..'
    },
    // CLIENT 看得见别人分享给他的案卷（ProjectService.getUserProjects 把成员身份的项目
    // 也算进去），但建项目/取案卷/删除/重命名/邀请全部对他隐藏。
    // 角色在一次会话里不会变，computed 无响应式依赖只算一次正合适。
    isClientUser() {
      const u = getCurrentUser()
      return !!u && u.role === 'CLIENT'
    },
  },
  data() {
    return {
      userInfo: {
        id: null,
        username: '',
        displayName: '用户',
        avatarUrl: null,
      },
      projects: [],
      projectsLoading: false,
      deletingProjectId: null,
      renamingProjectId: null,
      renameValue: '',

      showInviteModal: false,
      currentInviteProjectId: null,
      showCloudAccept: false,

      // 视图模式：'grid' 方块 / 'list' 列表。默认方块（与改造前形态一致），
      // 选择记在本机，不进后端——它是这台机器上这个人的习惯，不是账户设置。
      viewMode: 'grid',

      // 「详情」：把档案里其余四项（事项类型/对方/立项时间/下一步）补出来。
      // 默认关——绝大多数案卷这四项是空的，常显只会让列表变松散；
      // 两个视图共用这一个开关，同样记本机。
      showDetail: false,

      // 新建项目文件夹（原 newproject 页的流程，随新建入口一起搬过来）
      busy: false,
      busyText: '',
      namingVisible: false,
      namingParentDir: '',
      namingName: '',
    }
  },
  onLoad() {
    if (!this.ensureLoggedIn()) return
    this.restoreViewMode()
    this.loadUserInfo()
  },
  onShow() {
    // 从概览页 navigateBack、从新建项目页回来都要看到最新结果（改名/删除都在这一页做）
    if (!this.ensureLoggedIn()) return
    this.loadProjects()
  },
  methods: {
    // 浏览器端未登录直接回登录页；桌面 local-mode 免登，跳过该检查（同 userprofile.vue:565-578）
    ensureLoggedIn() {
      if (isDesktopHost()) return true
      if (getSessionId() && getCurrentUser()) return true
      uni.reLaunch({ url: '/pages/login/login' })
      return false
    },
    async loadUserInfo() {
      const user = getCurrentUser()
      if (user) this.userInfo = user
      // isProjectAdmin 要靠 userInfo.id，缓存里可能没有，拉一次 /api/auth/me 补齐
      try {
        const res = await getCurrentUserApi()
        if (res && res.code === 0 && res.data) {
          this.userInfo = { ...this.userInfo, ...res.data }
        }
      } catch (e) {
        // 拿不到就用缓存那份，不拦路
        console.error('获取用户信息失败:', e)
      }
    },
    async loadProjects() {
      this.projectsLoading = true
      try {
        // getMyProjects 返回的是裸数组（ProjectController 直接返 List<ProjectCardDTO>，
        // 无信封）。这里写 res.data 会恒空——admin.vue 现在就踩着这个坑。
        const projects = await getMyProjects()
        // 每个项目一次成员查询（N+1）。这是既有行为，spec §9 第 11 条已记为前置修复，
        // 属 Plan 3；本次原样搬，不要顺手优化（改 ProjectCardDTO 会牵动
        // ProjectService 的 BeanUtils.copyProperties 那条静默失败链）。
        const projectsWithMembers = await Promise.all(projects.map(async (p) => {
          try {
            const res = await getProjectMembers(p.id)
            let members = res.data || []
            // getProjectMembers 返回 project_member 裸行，owner 可能另有一行，去重
            const seen = new Set()
            members = members.filter((m) => {
              if (seen.has(m.userId)) return false
              seen.add(m.userId)
              return true
            })
            return { ...p, members }
          } catch (e) {
            console.error(`Failed to load members for project ${p.id}`, e)
            return { ...p, members: [] }
          }
        }))
        this.projects = projectsWithMembers
      } catch (error) {
        console.error('加载项目列表失败:', error)
        // 桌面端免登：绝不跳 login（launch 分流已保证桌面不进登录页，这里若跳就是死胡同），
        // 只提示错误。浏览器端保留原「登录失效回登录页」兜底。
        if (!this.isDesktop && error && error.code === 4010) {
          uni.reLaunch({ url: '/pages/login/login' })
        } else {
          uni.showToast({
            title: error.message || this.$t('projects.loadFailedRetry'),
            icon: 'none',
            duration: 2000,
          })
        }
      } finally {
        this.projectsLoading = false
      }
    },

    // ---- 视图模式 ----
    restoreViewMode() {
      try {
        const saved = uni.getStorageSync(VIEW_MODE_KEY)
        if (saved === 'grid' || saved === 'list') this.viewMode = saved
        this.showDetail = uni.getStorageSync(DETAIL_KEY) === '1'
      } catch (e) { /* 存储不可用就用默认值，不拦路 */ }
    },
    setViewMode(mode) {
      if (mode !== 'grid' && mode !== 'list') return
      this.viewMode = mode
      try { uni.setStorageSync(VIEW_MODE_KEY, mode) } catch (e) { /* ignore */ }
    },

    // ---- 成员 ----
    openInviteModal(projectId) {
      this.currentInviteProjectId = projectId
      this.showInviteModal = true
    },
    closeInviteModal() {
      this.showInviteModal = false
      this.currentInviteProjectId = null
    },
    async removeMember(projectId, userId) {
      uni.showModal({
        title: this.$t('projects.removeConfirmTitle'),
        content: this.$t('projects.removeConfirmContent'),
        cancelText: this.$t('projects.cancel'),
        confirmText: this.$t('projects.confirm'),
        success: async (res) => {
          if (res.confirm) {
            try {
              await removeProjectMember(projectId, userId)
              uni.showToast({ title: this.$t('projects.removeSuccess'), icon: 'success' })
              this.loadProjects()
            } catch (e) {
              uni.showToast({ title: e.message || this.$t('projects.removeFailed'), icon: 'none' })
            }
          }
        },
      })
    },
    isProjectAdmin(project) {
      if (!this.userInfo || !project) return false
      if (project.userId === this.userInfo.id) return true
      const member = project.members?.find((m) => m.userId === this.userInfo.id)
      return member && member.role === 'ADMIN'
    },
    canManageMembers(project) {
      return !this.isClientUser && this.isProjectAdmin(project)
    },
    // 角色文案唯一来源是 config/memberRoles.js（源页面自己硬编码了一份「管理员/成员」，
    // 与唯一来源里的「案件管理员/协作人」不一致，搬迁时收敛）
    getRoleLabel(role) {
      return roleLabel(role) || ROLE_LABELS.PARTICIPANT
    },
    getRoleClass(role) {
      if (role === 'OWNER') return 'role-owner'
      if (role === 'ADMIN') return 'role-admin'
      if (role === 'CLIENT') return 'role-client'
      return 'role-member'
    },
    getInternalMembers(project) {
      if (!project.members) return []
      return project.members.filter((m) => {
        const isClient = ['CLIENT', 'CLIENT_NAMED', 'CLIENT_GENERIC'].includes(m.role)
        const isManager = project.managerId && m.userId === project.managerId
        return !isClient && !isManager
      })
    },
    getClientMembers(project) {
      if (!project.members) return []
      return project.members.filter((m) => ['CLIENT', 'CLIENT_NAMED', 'CLIENT_GENERIC'].includes(m.role))
    },
    /**
     * 「客户」列。**权威来源是项目档案的 client 字段**——概览页档案头里律师手填的那个
     * （project_profile_field，写入即锁 source='user'，AI 抽取只写 pending 永不覆盖）。
     * 后端在 ProjectCardDTO.clientName 里一次批量带出来。
     *
     * 没填过才回落到推断，优先级：
     *   ① 项目里 CLIENT 系角色的成员（真把客户拉进来协作了，那就是客户）
     *   ② 上市公司 / 标的公司（重组时代的旧项目类型才有）
     *   ③ 都没有就显示 —，不编一个
     * **推断值不写回档案**：档案字段的语义是「谁说的算」，把猜的混进去会稀释掉手填锁。
     */
    /**
     * 方块视图要不要单列一行「客户」。列表视图没有上市/标的公司那两行，永远显示。
     * 这里的条件只为躲一种重复：旧类型项目已经把上市公司列在上面，而
     * clientText 在没有客户成员时正好回落到同一个名字，两行会一模一样。
     */
    showClientRow(project) {
      // 律师自己填过就一定显示，哪怕上面已经列了上市公司——那是他指定的客户
      if (this.profileValue(project, 'client')) return true
      if (this.getClientMembers(project).length) return true
      // 剩下的是推断值，已单列上市公司时不再重复一遍
      return !this.shouldShowListedCompany(project.projectType)
    },
    /** 这一行的客户是律师自己填的，还是我们推出来的——列表视图据此给个弱提示 */
    clientIsInferred(project) {
      return !this.profileValue(project, 'client') && this.clientText(project) !== '—'
    },
    /** 档案里某个键的值；未填/空白一律回空串，调用处只需判真假 */
    profileValue(project, key) {
      const p = project && project.profile
      return ((p && p[key]) || '').trim()
    },
    /**
     * 「详情」开关打开时补充显示的档案字段。客户不在这里——它是一等列，常显。
     *
     * 顺序不照搬后端的 FIELD_KEYS：那是档案头的排版顺序。列表里按「扫一眼要什么」
     * 排——先认事项与对方（这是哪一类活、跟谁打），再看立项时间，最后是下一步
     * （可能是一整句话，放末位才不会把前面几项挤没）。
     * 未填的键整条不渲染：一行「下一步 —」除了占地方什么也没说。
     */
    detailFields(project) {
      return [
        ['matterType', this.$t('projects.matterTypeField')],
        ['counterparty', this.$t('projects.counterpartyField')],
        ['openedAt', this.$t('projects.openedAtField')],
        ['nextStep', this.$t('projects.nextStepField')],
      ]
        .map(([key, label]) => ({ key, label, value: this.profileValue(project, key) }))
        .filter((f) => !!f.value)
    },
    setShowDetail(v) {
      this.showDetail = !!v
      try { uni.setStorageSync(DETAIL_KEY, this.showDetail ? '1' : '0') } catch (e) { /* ignore */ }
    },
    clientText(project) {
      // 权威来源是档案的 client 字段（下同）
      const filled = this.profileValue(project, 'client')
      if (filled) return filled
      const names = this.getClientMembers(project)
        .map((m) => m.displayName || m.username)
        .filter(Boolean)
      if (names.length) return names.join('、')
      const listed = project.listedCompanyName
      if (listed && listed !== '-') return listed
      const target = project.targetCompanyName
      if (target && target !== '-') return target
      return '—'
    },

    // ---- 卡片展示 ----
    // Project.projectType 是重大资产重组时代的遗留列，只用于旧数据回显；
    // 概览页的「事项类型」以 project_profile_field.matterType 为准，两者冲突时不提示
    getProjectTypeLabel(projectType) {
      return getProjectTypeLabel(projectType) || projectType
    },
    getProjectCardClass(type) {
      if (['MAJOR_ASSET_RESTRUCTURING', 'ACQUISITION'].includes(type)) {
        return 'card-style-restructuring'
      } else if (['PRIVATE_PLACEMENT', 'PUBLIC_PLACEMENT'].includes(type)) {
        return 'card-style-refinancing'
      } else if (type === 'BLANK') {
        return 'card-style-blank'
      }
      return 'card-style-default'
    },
    shouldShowListedCompany(type) {
      return type !== 'BLANK'
    },
    shouldShowTargetCompany(type) {
      return ['MAJOR_ASSET_RESTRUCTURING', 'ACQUISITION'].includes(type)
    },
    formatTime(timeStr) {
      if (!timeStr) return ''
      try {
        const date = new Date(timeStr)
        const year = date.getFullYear()
        const month = String(date.getMonth() + 1).padStart(2, '0')
        const day = String(date.getDate()).padStart(2, '0')
        return `${year}-${month}-${day}`
      } catch (e) {
        return timeStr
      }
    },

    // ---- 导航与写操作 ----
    // 列表页 → 概览页：两端都不是工作台，用 navigateTo（工作台参与的跳转才 reLaunch）
    // 点卡片直接进工作台。2026-08 之前中间还隔着一页 project-home 概览——
    // 概览现在是工作台里的一个标签（rail 第一个按钮），列表 → 概览 → 工作台
    // 那一跳纯属多余。工作台参与的跳转一律 reLaunch（navigateTo 会把列表页
    // 留在栈里，再进另一个项目就出现两个存活的工作台实例）。
    goToProject(projectId) {
      uni.reLaunch({
        url: `/pages/project-overview/project-overview?id=${projectId}`,
      })
    },
    openCloudAccept() {
      this.showCloudAccept = true
    },
    onCloudAccepted(localProjectId) {
      this.loadProjects()
      if (localProjectId) this.goToProject(localProjectId)
    },
    async handleDeleteProject(projectId) {
      uni.showModal({
        title: this.$t('projects.deleteConfirmTitle'),
        content: this.$t('projects.deleteConfirmContent'),
        cancelText: this.$t('projects.cancel'),
        confirmText: this.$t('projects.confirm'),
        success: async (res) => {
          if (res.confirm) {
            this.deletingProjectId = projectId
            try {
              await deleteProject(projectId)
              uni.showToast({ title: this.$t('projects.deleteSuccess'), icon: 'success', duration: 2000 })
              await this.loadProjects()
            } catch (error) {
              console.error('删除项目失败:', error)
              uni.showToast({
                title: error.message || this.$t('projects.deleteFailedRetry'),
                icon: 'none',
                duration: 2000,
              })
            } finally {
              this.deletingProjectId = null
            }
          }
        },
      })
    },
    /**
     * 页头「＋ 新建项目」。桌面端的「新建」本来就是两件事（打开一个已有文件夹 /
     * 新建一个项目文件夹），所以主按钮弹一次两选一，而不是替用户猜一个。
     * 浏览器端没有系统文件夹对话框，直接走托管空白项目表单。
     */
    onCreateProject() {
      if (this.busy) return
      if (!this.isDesktop) {
        this.goToNewProject()
        return
      }
      uni.showActionSheet({
        itemList: [this.$t('account.openFolderTitle'), this.$t('account.createFolderTitle')],
        success: (res) => {
          if (res.tapIndex === 0) this.onOpenFolder()
          else if (res.tapIndex === 1) this.onCreateFolder()
        },
        fail: () => { /* 用户取消 */ },
      })
    },
    // 浏览器降级路径：没有系统文件夹对话框，仍走 newproject 页的托管空白项目表单
    goToNewProject() {
      uni.navigateTo({ url: '/pages/newproject/index' })
    },

    // ---- 新建入口（桌面）：与 utils/ideOpen.js 共用同一套流程 ----
    async onOpenFolder() {
      if (this.busy) return
      await this.withBusy(this.$t('account.busyOpeningFolder'), () => openFolderFlow())
    },
    async onCreateFolder() {
      if (this.busy) return
      const res = await host.fs.showOpenDialog({
        title: this.$t('account.selectLocationTitle'),
        buttonLabel: this.$t('account.selectHereBtn'),
        properties: ['openDirectory', 'createDirectory'],
      })
      if (!res || res.canceled || !res.filePaths || !res.filePaths.length) return
      this.namingParentDir = res.filePaths[0]
      this.namingName = ''
      this.namingVisible = true
    },
    async confirmCreateFolder() {
      if (!this.namingNameValid || this.busy) return
      const parentDir = this.namingParentDir
      const name = this.namingName.trim()
      this.namingVisible = false
      await this.withBusy(this.$t('account.busyCreatingProject'), () => createFolderFlow(parentDir, name))
    },
    async withBusy(busyText, flow) {
      this.busy = true
      this.busyText = busyText || this.$t('account.busyOpeningProject')
      try {
        await flow()
      } catch (err) {
        uni.showToast({ title: (err && err.message) || this.$t('common.openProjectFailed'), icon: 'none' })
      } finally {
        this.busy = false
      }
    },
    // 本页与个人中心两端都不是工作台，用 navigateTo（工作台参与的跳转才 reLaunch）
    goToUserProfile() {
      uni.navigateTo({ url: '/pages/userprofile/userprofile' })
    },
    // 日历页同样不是工作台，同一模式
    goToCalendar() {
      uni.navigateTo({ url: '/pages/calendar/calendar' })
    },
    startRename(project) {
      if (this.isClientUser) return
      this.renamingProjectId = project.id
      this.renameValue = project.name
    },
    async confirmRename() {
      if (!this.renameValue || !this.renameValue.trim()) {
        uni.showToast({ title: this.$t('projects.projectNameEmpty'), icon: 'none' })
        return
      }
      try {
        await renameProject(this.renamingProjectId, this.renameValue.trim())
        const project = this.projects.find((p) => p.id === this.renamingProjectId)
        if (project) {
          project.name = this.renameValue.trim()
        }
        this.renamingProjectId = null
        this.renameValue = ''
        uni.showToast({ title: this.$t('projects.renameSuccess'), icon: 'success' })
      } catch (e) {
        console.error('重命名失败', e)
        uni.showToast({ title: this.$t('projects.renameFailed'), icon: 'none' })
      }
    },
    cancelRename() {
      this.renamingProjectId = null
      this.renameValue = ''
    },
  },
}
</script>

<style lang="scss" scoped src="./project-list.scss"></style>
