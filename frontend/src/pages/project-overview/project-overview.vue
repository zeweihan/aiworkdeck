<template>
  <view class="page-project-overview" :class="{ 'compact-mode': isCompactLayout, 'is-resizing': resizing && resizing.active }">
    <!-- 顶部固定项目信息 -->
    <view class="project-header">
      <view class="header-left">
        <!-- Windows 自绘菜单栏：那边没有系统全局菜单栏，无边框后原生菜单也一并
             消失，只能自绘（spec §6.4）。读的是与 mac 完全同一份命令表数据。
             mac 上组件内部 visible=false，不渲染。 -->
        <AppMenuBar :refresh-key="menuBarRefreshKey" />

        <view class="project-info">
          <!-- Logo moved to center -->
          <view class="project-title-row">
            <view v-if="isRenamingProject" class="rename-container">
              <input
                class="rename-input"
                v-model="renameProjectName"
                :focus="true"
                @confirm="confirmRenameProject"
                @blur="cancelRenameProject"
              />
            </view>
            <!-- IDE 化：最近项目切换器（VS Code 的 Open Recent 语义）。
                 整块「项目名 + ▾」都是切换器的命中区：启动落点改成项目列表页之后，
                 「换一个项目」成了高频动作，只把一个 11px 的箭头当热区没人找得到。
                 重命名随之移进菜单（点名字改名是隐藏交互，本来也没人知道）。 -->
            <view v-else class="project-switcher" @tap.stop="toggleProjectSwitcher" :title="$t('workbench.switchRecentProject')">
              <text class="project-name">{{ project.name || $t('workbench.unnamedProject') }}</text>
              <text class="switcher-arrow" :class="{ 'is-open': projectSwitcherOpen }">▾</text>
            </view>
            <view v-if="projectSwitcherOpen" class="switcher-mask" @tap.stop="projectSwitcherOpen = false"></view>
            <view v-if="projectSwitcherOpen" class="switcher-menu" @tap.stop>
              <view class="switcher-title"><text>{{ $t('workbench.recentProjects') }}</text></view>
              <view
                v-for="p in switcherProjects"
                :key="p.id"
                class="switcher-item"
                @tap="switchToProject(p)"
              >
                <text class="switcher-item-name">{{ p.name }}</text>
              </view>
              <view v-if="switcherLoadFailed" class="switcher-item switcher-error" @tap="loadSwitcherProjects">
                <text>{{ $t('workbench.recentProjectsLoadFailed') }}</text>
              </view>
              <view v-else-if="!switcherProjects.length" class="switcher-item switcher-empty">
                <text>{{ $t('workbench.noOtherRecentProjects') }}</text>
              </view>
              <view class="switcher-item switcher-home" @tap="goProjectHome">
                <text>{{ $t('workbench.projectHome') }}</text>
              </view>
              <view class="switcher-item switcher-rename" @tap="startRenameFromSwitcher">
                <text>{{ $t('workbench.renameProject') }}</text>
              </view>
              <view class="switcher-item switcher-all" @tap="goAllProjects">
                <text>{{ $t('workbench.allProjects') }}</text>
              </view>
            </view>
            <view class="project-status-badge">
              <text class="status-text">{{ $t('workbench.statusInProgress') }}</text>
            </view>
            <!-- IDE 化：常驻工作状态点（版本记录开着且有未收尾工作/稿时可见，点击直达版本面板） -->
            <view
              v-if="versionWorkStatus.enabled && (versionWorkStatus.working || versionWorkStatus.onDraft)"
              class="work-status-chip"
              @tap.stop="goHandleAdoptConflict"
            >
              <view class="work-status-dot"></view>
              <text class="work-status-text">{{ versionWorkStatusLabel }}</text>
            </view>
            <!-- 协作状态 chip：只在这份案卷真的放进过团队案件库时才渲染。
                 没连案件库的律师（绝大多数）在界面上看不到任何协作元素——
                 「以自己工作为主」的产品定位要求协作 UI 零打扰。 -->
            <view
              v-if="collabLinked"
              class="collab-chip"
              :class="'collab-chip-' + collabTone"
              @tap.stop="openCollab('casefile')"
              :title="$t('workbench.collab')"
            >
              <view class="collab-chip-dot"></view>
              <text class="collab-chip-text">{{ collabStateText }}</text>
            </view>
          </view>
          <view class="project-meta">
            <text class="meta-item">{{ $t('workbench.managerLabel', { name: project.manager || userDisplayName || $t('workbench.me') }) }}</text>

            <block v-if="project.listedCompanyName && project.listedCompanyName !== '-'">
                <text class="meta-divider">|</text>
                <text class="meta-item">{{ $t('workbench.listedCompanyLabel', { name: project.listedCompanyName }) }}</text>
            </block>

            <block v-if="project.createdAt">
                <text class="meta-divider">|</text>
                <text class="meta-item">{{ $t('workbench.createdAtLabel', { time: formatTime(project.createdAt) }) }}</text>
            </block>
          </view>
        </view>
      </view>

      <!-- Center Logo -->
      <view class="header-center">
         <image src="/static/logo_full_v2.png" mode="heightFix" class="project-logo" />
      </view>

      <view class="header-right">
        <!-- 授权标识（低调 chip）。优先级：宽限预警 > 已连接账户 > 试用版。
             预警排最前是因为它是三者里唯一「不处理就会被挡在门外」的一条；
             另外两个都只是状态标注（试用码解锁后再连账户的用户该看到账户状态）。 -->
        <view
          v-if="graceKind"
          class="trial-chip grace-chip"
          @tap.stop="showTrialInfo = true"
          :title="graceTitle"
        >
          <text class="trial-chip-text">{{ graceChipText }}</text>
        </view>
        <view
          v-else-if="accountConnected"
          class="trial-chip account-chip"
          @tap.stop="goToAccountPanel"
          :title="$t('workbench.accountUsage')"
        >
          <text class="trial-chip-text">{{ $t('workbench.accountConnected') }}</text>
        </view>
        <view
          v-else-if="licenseMode === 'trial'"
          class="trial-chip"
          @tap.stop="showTrialInfo = true"
          :title="$t('workbench.trialInfo')"
        >
          <text class="trial-chip-text">{{ $t('workbench.trialBadge') }}</text>
        </view>
        <!-- 顶部工具区（IDE 风格）：分屏 / 浏览器 / 摘录 / AI / 工具 -->
        <view class="header-tools" v-if="!isClientView">
          <!-- 1. Left Sidebar -->
          <view
            class="top-bar-btn"
            :class="{ active: !sidebarCollapsed }"
            @tap="toggleSidebar"
            :title="sidebarCollapsed ? $t('workbench.expandSidebar') : $t('workbench.collapseSidebar')"
          >
            <svg class="tool-icon-svg" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
              <path v-for="(d, gi) in GLYPHS.panelLeft" :key="gi" :d="d" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round" />
            </svg>
          </view>

          <!-- 2. Bottom Sidebar (Tools Panel) -->
          <view
            class="top-bar-btn"
            :class="{ active: showToolsPanel }"
            @tap="toggleToolsPanel"
            :title="$t('workbench.toolsPanel')"
          >
            <svg class="tool-icon-svg" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
              <path v-for="(d, gi) in GLYPHS.panelBottom" :key="gi" :d="d" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round" />
            </svg>
          </view>

          <!-- 3. Right Sidebar (AI Panel) -->
          <view
            class="top-bar-btn"
            :class="{ active: showAiPanel }"
            @tap="toggleAiPanel"
            :title="$t('workbench.aiAssistant')"
          >
            <svg class="tool-icon-svg" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
              <path v-for="(d, gi) in GLYPHS.panelRight" :key="gi" :d="d" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round" />
            </svg>
          </view>

          <!-- 4. Split View -->
          <view
            class="top-bar-btn split-btn"
            :class="{ active: splitMode }"
            @tap="toggleSplitMode"
            :title="splitMode ? $t('workbench.closeSplit') : $t('workbench.openSplit')"
          >
            <svg class="tool-icon-svg" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
              <path v-for="(d, gi) in GLYPHS.splitCols" :key="gi" :d="d" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round" />
            </svg>
          </view>

          <!-- 5. Screenshot (OCR) -->
          <view
            class="top-bar-btn"
            @tap="startOcrCapture"
            :title="$t('workbench.ocrCapture')"
          >
            <svg class="tool-icon-svg" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
              <path v-for="(d, gi) in GLYPHS.camera" :key="gi" :d="d" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round" />
            </svg>
          </view>

          <!-- 6. Browser (New Web) -->
          <view
            class="top-bar-btn"
            @tap="openBrowserTab()"
            :title="$t('workbench.browser')"
          >
            <svg class="tool-icon-svg" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
              <path v-for="(d, gi) in GLYPHS.web" :key="gi" :d="d" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round" />
            </svg>
          </view>

          <!-- Activity Record Toggle -->
          <view
            class="top-bar-btn"
            :class="{ active: isRecording, recording: isRecording }"
            @tap="toggleRecording"
            :title="$t('workbench.recordActivity')"
          >
            <svg class="tool-icon-svg" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
              <path v-for="(d, gi) in GLYPHS.record" :key="gi" :d="d" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round" />
            </svg>
          </view>
        </view>
        <view v-else>
            <!-- Client View Only Tools -->
            <view class="header-tools">
               <view class="icon-btn" @tap="handleLogout" :title="$t('workbench.logout')">
                   <text class="tool-icon">×</text>
               </view>
            </view>
        </view>

        <!-- 用户头像 + 下拉（2026-08-19 从 rail 底部搬上来）。
             刻意放在 isClientView 分支之外：rail 上那个头像本来就对客户也渲染，
             收进下拉时不能顺手把客户的「个人中心」入口砍掉。系统设置那一项才是
             律师专有的（客户视图里整片工具区都收起来了）。

             「个人中心」仍是整页 navigateTo（它依赖页面栈保留工作台实例以便
             onShow 回流刷新）；「系统设置」改成中栏标签，不再把工作台整个换掉。
             顶栏里每一个能点的东西都必须在 App.vue 的 no-drag 名单里，
             下拉菜单本身也要——它画在 .project-header 这条 drag 区之内。 -->
        <view class="header-account">
          <view class="avatar-btn" @tap.stop="toggleAvatarMenu" :title="$t('workbench.accountMenu')">
            <image v-if="currentUser && currentUser.avatarUrl" :src="currentUser.avatarUrl" class="avatar-img" />
            <text v-else class="avatar-text">{{ getInitial(userDisplayName || currentUser?.displayName) || 'U' }}</text>
          </view>
          <view v-if="avatarMenuOpen" class="avatar-menu-mask" @tap.stop="avatarMenuOpen = false"></view>
          <view v-if="avatarMenuOpen" class="avatar-menu" @tap.stop>
            <view class="avatar-menu-item" @tap="goToUserProfile">
              <text>{{ $t('workbench.profile') }}</text>
            </view>
            <view v-if="!isClientView" class="avatar-menu-item" @tap="goToSystemSettings">
              <text>{{ $t('workbench.settingsTabName') }}</text>
            </view>
          </view>
        </view>
      </view>
    </view>

    <!-- 主体布局 -->
    <view class="main-layout" :class="{ 'is-compact': isCompactLayout }">
      <!-- Cursor 风格：最左常驻栏（Activity Bar） -->
      <view class="left-rail">
        <!-- rail 的顺序就是 config/leftSidebarPlugins.js 里数组的顺序（项目概览 →
             资源管理器 → 搜索 → 插件中心 → 语音 → 脱敏 → 门控项）。
             2026-08-19 起「项目概览」和「插件中心」也在这个数组里：它们走的都是
             普通的 toggleLeftPane 语义，单独硬编码成 rail 按钮只会让顺序有两个出处。 -->
        <view
          v-for="p in LEFT_SIDEBAR_PLUGINS"
          :key="p.key"
          class="rail-btn"
          :class="{ active: (leftPaneKey === p.key && !sidebarCollapsed) || (p.key === 'staging' && stagingPinned) }"
          :title="p.label"
          @tap="toggleLeftPane(p.key)"
        >
          <view v-if="p.svgPaths" class="rail-icon-wrapper">
            <svg class="rail-icon-svg" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
              <path
                v-for="(path, idx) in p.svgPaths"
                :key="idx"
                :d="path.d"
                stroke="currentColor"
                stroke-width="2"
                stroke-linecap="round"
                stroke-linejoin="round"
                class="rail-icon-path"
              />
            </svg>
          </view>
          <image
            v-else-if="p.activeIcon && p.icon"
            :src="((leftPaneKey === p.key && !sidebarCollapsed) || (p.key === 'staging' && stagingPinned)) ? p.activeIcon : p.icon"
            class="rail-icon-img"
            mode="aspectFit"
          />
          <text v-else class="rail-icon">{{ p.icon }}</text>
        </view>

        <!-- Spacer -->
        <view style="flex: 1"></view>

        <!-- Staging Area (Moved to bottom) -->
        <view
          class="rail-btn"
          :class="{ active: (leftPaneKey === 'staging' && !sidebarCollapsed) || stagingPinned }"
          :title="$t('workbench.stagingArea')"
          @tap="toggleLeftPane('staging')"
        >
          <view class="rail-icon-wrapper">
            <svg class="rail-icon-svg" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
              <path v-for="(d, gi) in GLYPHS.inbox" :key="gi" :d="d" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" class="rail-icon-path" />
            </svg>
          </view>
        </view>

        <!-- 插件中心与系统设置都不在 rail 底部了（2026-08-19）：前者升成 rail 数组
             里的一项（排在搜索之后），后者与个人中心一起收进顶栏右上角的头像下拉。
             rail 底部现在是「暂存区」「版本记录」「成员堆叠（协作）」三件跟当前
             案卷有关的东西——版本记录挪到这里（原先在 rail 数组里，见
             config/leftSidebarPlugins.js 的 VERSION_PLUGIN），视觉上放在项目成员
             与暂存区之间。 -->

        <!-- Version History -->
        <view
          class="rail-btn"
          :class="{ active: leftPaneKey === 'version' && !sidebarCollapsed }"
          :title="VERSION_PLUGIN.label"
          @tap="toggleLeftPane('version')"
        >
          <view class="rail-icon-wrapper">
            <svg class="rail-icon-svg" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
              <path v-for="(path, idx) in VERSION_PLUGIN.svgPaths" :key="idx" :d="path.d" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" class="rail-icon-path" />
            </svg>
          </view>
        </view>

        <!-- Project Members Stack -->
        <view class="rail-members-container" v-if="projectMembers && projectMembers.length > 0">
           <view class="members-stack-icon">
              <!-- Stacked avatars -->
              <view class="stack-preview">
                   <view
                      v-for="(member, index) in projectMembers.slice(0, 3)"
                      :key="member.id"
                      class="stack-avatar-mini"
                      :style="{
                        zIndex: 3 - index,
                        top: ((index - (Math.min(projectMembers.length, 3) - 1) / 2) * -2) + 'px',
                        left: ((index - (Math.min(projectMembers.length, 3) - 1) / 2) * 8) + 'px'
                      }"
                   >
                      <image v-if="member.avatarUrl" :src="member.avatarUrl" class="avatar-img" />
                      <view v-else class="avatar-placeholder">{{ getInitial(member.displayName) || 'U' }}</view>
                   </view>
              </view>

              <!-- Expanded Panel (Hover) -->
              <view class="members-expand-panel-left">
                  <scroll-view scroll-y class="expand-list">
                      <view v-for="group in groupedMembers" :key="group.label" class="member-group">
                          <view class="group-label">{{ group.label }}</view>
                          <view class="members-grid">
                              <view v-for="member in group.list" :key="member.id" class="member-grid-item" :title="member.displayName">
                                   <!-- Avatar -->
                                   <view class="member-avatar-wrapper">
                                     <image v-if="member.avatarUrl" :src="member.avatarUrl" class="member-avatar-grid" />
                                     <view v-else class="member-avatar-placeholder-grid" :class="{ 'is-client': member.role === 'CLIENT' }">
                                       {{ member.role === 'CLIENT' ? $t('workbench.clientInitial') : (getInitial(member.displayName) || 'U') }}
                                     </view>
                                   </view>
                              </view>

                              <!-- Add Member Button (Only in 'Client' group or at the end if you want) -->
                              <!-- User requested: "成员头像最右侧... 应该有一个空圆圈，里边显示加号" -->
                              <!-- We'll put it at the end of the last group OR as a separate item if we want -->
                              <!-- Let's put it in the last available spot of the last group to allow flow, or just append it to the grid of the last group -->
                          </view>
                      </view>

                      <!-- Add Member Trigger (Appended to the list visually) -->
                      <view class="add-member-row" style="padding: 0 12px 12px;">
                          <view class="add-member-btn" @tap.stop="showInviteModal = true" :title="$t('workbench.addMember')">
                              <text class="add-icon">＋</text>
                          </view>
                      </view>
                  </scroll-view>
              </view>
           </view>
        </view>

        <!-- 用户头像已搬到顶栏右上角（个人中心 / 系统设置的下拉入口）。 -->
      </view>

      <!-- File Picker Dialog (for EasyVoice Import) -->
      <!-- allowFolder 按调用方开关：只有诉讼可视化的「材料范围」需要选文件夹
           （画一批材料本来就是按卷宗文件夹给的），EasyVoice 导入和脱敏都只收单文件。 -->
      <FilePickerDialog
        v-model:visible="showFilePicker"
        :project-id="projectId"
        :allow-folder="filePickerAllowFolder"
        @confirm="handleFilePickerConfirm"
      />

      <!-- Invite Modal (Refactored to AI WorkDeck) -->
      <!-- Invite Member Dialog -->
      <InviteMemberDialog
        v-model:visible="showInviteModal"
        :project-id="projectId"
        @success="loadProjectMembers"
      />

      <!-- 协作抽屉：顶栏 chip 与版本面板状态行共用的唯一动作入口 -->
      <CollabDialog
        v-model:visible="collabDialogVisible"
        :project-id="projectId"
        :project-name="project.name || ''"
        :cloud="collabCloud"
        :conflict-pending="adoptConflictPending"
        :working="!!versionWorkStatus.working"
        :initial-tab="collabInitialTab"
        :inviter-name="userDisplayName || (currentUser && currentUser.displayName) || ''"
        @changed="onCollabChanged"
        @reload-files="onVersionReloadFiles"
        @conflict="onCollabConflict"
      />

      <!-- 文档比较选择对话框 -->
      <CompareDocDialog
        :visible="showCompareDialog"
        :documents="compareDocuments"
        @cancel="showCompareDialog = false"
        @confirm="onCompareDialogConfirm"
      />

    <!-- Custom Recording Toast -->
    <view class="recording-toast" :class="{ visible: showRecordingToast }">
      <text>{{ recordingToastMessage }}</text>
    </view>

      <!-- 左侧文件树（可收起） -->
      <view class="sidebar-left" ref="sidebarLeft" :class="{ collapsed: sidebarCollapsed }" :style="{ width: sidebarCollapsed ? '0px' : sidebarWidth + 'px' }">
        <!-- 批量菜单遮罩：用于点击空白关闭下拉（不弹中间） -->
        <view v-if="showBatchMenu" class="batch-menu-mask" @tap="closeBatchMenu"></view>
        <!-- 左栏标题的唯一出处。此前各面板还各画各的 header，于是「诉讼可视化」
             「会议录音」这类面板的标题在同一屏里出现两次，而搜索面板靠把自己那份
             注释掉躲过去——四种写法并存。现在一律由这里出，面板自己只画分组头。
             dd-files 曾经是个例外（它有自己的 header 带「＋」），那个按钮已经挪进
             面板内部的分组头里，例外随之取消。 -->
        <view v-if="!sidebarCollapsed" class="sidebar-header">
          <view class="sidebar-title-row">
            <text v-if="!fileBatchMode" class="sidebar-title">{{ leftPaneTitle }}</text>
            <view
              v-else
              class="btn-select-all"
              @tap="selectAllFiles"
            >
              <text>{{ $t('workbench.selectAll') }}</text>
            </view>
          </view>

          <view v-if="leftPaneKey === 'files'" class="sidebar-actions-row">
            <view class="sidebar-actions">
              <!-- 1. 新建文件 (普通模式) -->
              <view
                v-if="!fileBatchMode"
                class="icon-btn mini"
                @tap="onFileTreeQuickAction('newFile')"
                :title="$t('workbench.newDoc')"
              >
                <svg class="mini-icon-svg" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
                  <path v-for="(d, gi) in GLYPHS.filePlus" :key="gi" :d="d" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round" />
                </svg>
              </view>

              <!-- 2. 新建文件夹 (普通模式) -->
              <view
                v-if="!fileBatchMode"
                class="icon-btn mini"
                @tap="onFileTreeQuickAction('newFolder')"
                :title="$t('workbench.newFolder')"
              >
                <svg class="mini-icon-svg" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
                  <path v-for="(d, gi) in GLYPHS.folderPlus" :key="gi" :d="d" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round" />
                </svg>
              </view>

              <!-- 3. 批量选择开关 (始终显示) -->
              <view
                class="icon-btn mini"
                :class="{ active: fileBatchMode }"
                @tap="toggleFileBatchMode"
                :title="$t('workbench.batchSelect')"
              >
                <svg class="mini-icon-svg" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
                  <path v-for="(d, gi) in GLYPHS.checkSquare" :key="gi" :d="d" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round" />
                </svg>
              </view>

              <!-- 4. 上传 (普通模式) -->
              <view
                v-if="!fileBatchMode"
                class="icon-btn mini"
                @tap="onFileTreeQuickAction('upload')"
                :title="$t('workbench.uploadFile')"
              >
                <svg class="mini-icon-svg" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
                  <path v-for="(d, gi) in GLYPHS.upload" :key="gi" :d="d" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round" />
                </svg>
              </view>

              <!-- 5. 下载 (批量模式) -->
              <view
                v-if="fileBatchMode"
                class="icon-btn mini"
                @tap="onFileTreeQuickAction('download')"
                :title="$t('workbench.batchDownload')"
                :class="{ disabled: checkedFileCount <= 0 }"
              >
                <svg class="mini-icon-svg" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
                  <path v-for="(d, gi) in GLYPHS.download" :key="gi" :d="d" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round" />
                </svg>
              </view>

              <!-- 6. 排序 (普通模式) -->
              <view
                v-if="!fileBatchMode"
                class="icon-btn mini"
                @tap="onFileTreeQuickAction('sort')"
                :title="$t('workbench.sort')"
              >
                <svg class="mini-icon-svg" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
                  <path v-for="(d, gi) in GLYPHS.sort" :key="gi" :d="d" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round" />
                </svg>
              </view>

              <!-- 7. 复制 (批量模式) -->
              <view
                v-if="fileBatchMode"
                class="icon-btn mini"
                @tap="onFileTreeQuickAction('copy')"
                :title="$t('workbench.batchCopy')"
                :class="{ disabled: checkedFileCount <= 0 }"
              >
                <svg class="mini-icon-svg" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
                  <path v-for="(d, gi) in GLYPHS.copyDoc" :key="gi" :d="d" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round" />
                </svg>
              </view>

              <!-- 8. 回收站 / 删除 (始终显示，功能不同) -->
              <view
                class="icon-btn mini"
                @tap="onFileTreeQuickAction('recycleBin')"
                :title="fileBatchMode ? $t('workbench.deleteSelected') : $t('workbench.recycleBin')"
              >
                <svg class="mini-icon-svg" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
                  <path v-for="(d, gi) in GLYPHS.trash" :key="gi" :d="d" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round" />
                </svg>
              </view>
            </view>
          </view>
        </view>

        <view v-if="!sidebarCollapsed" class="sidebar-content">
          <FileTree
            v-if="leftPaneKey === 'files'"
            ref="fileTree"
            :project-id="projectId"
            :selection-mode="fileBatchMode"
            :show-footer-actions="false"
            :hidden-file-ids="stagedFileIds"
            @checked-change="onFileTreeCheckedChange"
            @file-select="handleFileTreeSelect"
            @file-drag-start="onFileLinkDragStart"
            @file-drag-end="onFileLinkDragEnd"
            @compare-documents="onCompareDocumentsRequest"
            @files-changed="loadStagingFiles"
            @file-deleted="handleFileDeleted"
            @file-history="onFileHistory"
            @reveal-file="onRevealFile"
          />
          <DdFilesPanel
            v-else-if="leftPaneKey === 'dd-files'"
            :project-id="projectId"
            :current-user="currentUser"
            @open-request="handleOpenDdRequest"
          />
          <ShareholderMeetingPanel
            v-else-if="leftPaneKey === 'shareholder-meeting'"
            :project-id="projectId"
            :current-user="currentUser"
            @start-verification="handleShareholderMeetingStart"
          />
          <LitigationVisualPanel
            v-else-if="leftPaneKey === 'litigation-visual'"
            :project-id="projectId"
            @start-drawing="handleLitigationStart"
            @open-file="handleLitigationOpenFile"
            @request-scope-select="handleLitigationScopeSelect"
          />
          <!-- 项目概览：2026-08-19 起在左栏展示（此前是中栏标签）。
               同一个 ProjectHomePane，薄壳页那个宿主一行没改。 -->
          <ProjectHomePane
            v-else-if="leftPaneKey === 'home'"
            :project-id="Number(projectId)"
            compact
            @open-conversation="openConversationInPanel"
          />
          <!-- 语音：语音合成 + 会议录音合并成一个入口，面板内部两个 tab。
               两个组件本身一行没改，这里只做宿主（tab 条 + v-if）。
               两个 tab 各自门控 text-to-speech / meeting-recorder skill 是否启用——
               门控从 rail 位挪到了这里，判据仍是同一份 enabledSkillIds；
               整个 rail 位在两者都停用时才隐藏，见 LEFT_SIDEBAR_PLUGINS 计算属性。
               实际渲染哪个 tab 走 effectiveVoiceTab（voiceTab 记的是用户选择，
               选的那个被停用时兜底落到唯一可用的那个）。 -->
          <view v-else-if="leftPaneKey === 'voice'" class="voice-pane">
            <view class="voice-tabs">
              <view
                v-if="ttsEnabled"
                class="voice-tab"
                :class="{ active: effectiveVoiceTab === 'tts' }"
                @tap="voiceTab = 'tts'"
              >
                <text>{{ $t('workbench.voiceTts') }}</text>
              </view>
              <view
                v-if="meetingRecorderEnabled"
                class="voice-tab"
                :class="{ active: effectiveVoiceTab === 'recorder' }"
                @tap="voiceTab = 'recorder'"
              >
                <text>{{ $t('workbench.voiceRecorder') }}</text>
              </view>
            </view>
            <view class="voice-tab-body">
              <MeetingRecordingPanel
                v-if="effectiveVoiceTab === 'recorder'"
                :project-id="projectId"
                :current-user="currentUser"
                @generate-minutes="handleMeetingMinutesStart"
              />
              <EasyVoicePane
                v-else-if="effectiveVoiceTab === 'tts'"
                @request-doc-text="handleEasyVoiceDocRequest"
                @highlight-sentence="handleTtsHighlight"
                @clear-highlight="handleTtsClearHighlight"
              />
            </view>
          </view>
          <DesensitizePane
             v-else-if="leftPaneKey === 'desensitize'"
             :project-id="projectId"
             @request-file-select="handleDesensitizeSelectFile"
             @request-active-file="handleDesensitizeActiveFile"
             @open-file="handleDesensitizeSuccess"
          />
          <SearchPanel
            v-else-if="leftPaneKey === 'search'"
            :project-id="projectId"
            @open-file="handleSearchOpenFile"
          />
          <VersionPanel
            v-else-if="leftPaneKey === 'version'"
            :project-id="projectId"
            :file-filter="versionFileFilter"
            :collab-refresh-token="collabRefreshToken"
            @compare-file="onVersionCompareFile"
            @clear-file-filter="versionFileFilter = null"
            @reload-files="onVersionReloadFiles"
            @adopt-conflict="adoptConflictPending = $event"
            @open-collab="openCollab"
          />
          <MarketSidebarPanel
            v-else-if="leftPaneKey === 'market'"
            @open-detail="openMarketDetail"
          />
          <PluginPane
            v-else-if="leftPaneKey && dynamicPlugins.some(p => p.key === leftPaneKey)"
            :url="dynamicPlugins.find(p => p.key === leftPaneKey)?.frontendEntry"
            :plugin-id="dynamicPlugins.find(p => p.key === leftPaneKey)?.pluginId || ''"
            :permissions="dynamicPlugins.find(p => p.key === leftPaneKey)?.permissions || []"
            :project-id="projectId"
          />
          <view v-else class="sidebar-plugin-placeholder">
            <text class="placeholder-title">{{ leftPaneTitle }}</text>
            <text class="placeholder-desc">{{ $t('workbench.loadingText') }}</text>
          </view>


        </view>

          <!-- 文件拖拽关联：浮窗落点区域 (移至侧边栏底部) -->
          <!-- 1. 关联区域 (Priority: Dragging + Word 文档已打开) -->
          <FileLinkDropZone
            :visible="showAssociationDropZone"
            :file-name="fileLinkDrag.file ? fileLinkDrag.file.name : ''"
            :split-mode="splitMode"
            @drop="onFileLinkZoneDrop"
          />

          <!-- 2. 文件暂存区 (Visible if Staging has files OR Dragging without a Word doc open) -->
          <FileStagingArea
            :visible="showStagingArea"
            :files="stagingFiles"
            :usage="stagingUsage"
            @drop="onStagingDrop"
            @clear="handleStagingClear"
            @remove="handleStagingRemove"
            @open="handleStagingOpen"
            @compare="handleStagingCompare"
            @collapse="handleStagingCollapse"
          />

        <!-- Sidebar Footer moved to Left Rail -->

        <!-- 拖拽手柄 -->
        <view class="resize-handle" @touchstart="startResize('left', $event)" @mousedown="startResize('left', $event)"></view>
      </view>

      <!-- IDE 工作台：中(编辑) + 右(AI) + 底(工具) -->
      <view class="workbench">
        <view class="workbench-main">
          <!-- 中间内容区 -->
          <view class="content-area">
            <!-- 顶部 Tab 栏 -->
            <view class="tabs-bar">
              <!-- 左侧窗格的 Tabs -->
              <view class="tabs-pane tabs-pane-left" :class="{ 'half-width': splitMode }">
                <scroll-view class="tabs-scroll" scroll-x show-scrollbar="false">
                  <view
                    class="tabs-list"
                    @dragover.prevent="onTabDropZoneDragOver('left')"
                    @drop.prevent="onTabDropOnZone($event, 'left')"
                  >
                    <view
                      v-for="file in leftFiles"
                      :key="file.id"
                      v-show="isTabVisible(file)"
                      class="tab-item"
                      :class="{
                        active: activeFileIdLeft === file.id,
                        'tab-drag-over': tabDragOver && tabDragOver.pane === 'left' && tabDragOver.fileId === file.id,
                        'tab-dual-open': isOpenInOtherPane(file.id, 'left')
                      }"
                      :draggable="true"
                      @tap="activateTab(file, 'left')"
                      @dragstart="onTabDragStart($event, file, 'left')"
                      @dragover.prevent="onTabDragOver($event, file, 'left')"
                      @drop.prevent="onTabDropOnItem($event, file, 'left')"
                      @dragend="onTabDragEnd"
                    >
                      <svg class="tab-icon" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
                        <path v-for="(d, gi) in getFileIconPaths(file.fileType, file.tabType)" :key="gi" :d="d" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round" />
                      </svg>
                      <text class="tab-name">{{ file.name }}</text>
                      <text class="tab-close" @tap.stop="closeFile(file.id, 'left')">×</text>
                    </view>
                  </view>
                </scroll-view>
                <view class="tabs-plus" @tap="onTabsPlusClick('left')" :title="$t('workbench.newOrCopy')">
                  <text class="tabs-plus-icon">＋</text>
                </view>
              </view>

              <!-- 右侧窗格的 Tabs (仅在分屏时显示) -->
              <view v-if="splitMode" class="tabs-pane tabs-pane-right">
                <scroll-view class="tabs-scroll" scroll-x show-scrollbar="false">
                  <view
                    class="tabs-list"
                    @dragover.prevent="onTabDropZoneDragOver('right')"
                    @drop.prevent="onTabDropOnZone($event, 'right')"
                  >
                    <view
                      v-for="file in rightFiles"
                      :key="file.id"
                      v-show="isTabVisible(file)"
                      class="tab-item"
                      :class="{
                        active: activeFileIdRight === file.id,
                        'tab-drag-over': tabDragOver && tabDragOver.pane === 'right' && tabDragOver.fileId === file.id,
                        'tab-dual-open': isOpenInOtherPane(file.id, 'right')
                      }"
                      :draggable="true"
                      @tap="activateTab(file, 'right')"
                      @dragstart="onTabDragStart($event, file, 'right')"
                      @dragover.prevent="onTabDragOver($event, file, 'right')"
                      @drop.prevent="onTabDropOnItem($event, file, 'right')"
                      @dragend="onTabDragEnd"
                    >
                      <svg class="tab-icon" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
                        <path v-for="(d, gi) in getFileIconPaths(file.fileType, file.tabType)" :key="gi" :d="d" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round" />
                      </svg>
                      <text class="tab-name">{{ file.name }}</text>
                      <text class="tab-close" @tap.stop="closeFile(file.id, 'right')">×</text>
                    </view>
                  </view>
                </scroll-view>
                <view class="tabs-plus" @tap="onTabsPlusClick('right')" :title="$t('workbench.newOrCopy')">
                  <text class="tabs-plus-icon">＋</text>
                </view>
              </view>

            </view>

            <!-- 编辑器区域（会被底部工具面板压缩） -->
            <view class="editors-container">
              <!-- 初始空状态 (仅当左侧也没有文件时) -->
              <view v-if="leftFiles.length === 0 && !splitMode" class="empty-workspace">
                <view class="empty-content">
                  <image src="/static/iconmark_v2.png" class="empty-state-img" mode="aspectFit" />
                  <text class="empty-text">{{ $t('workbench.emptyWorkspace') }}</text>
                </view>
              </view>

              <!-- 编辑器视图 -->
              <view v-else class="editors-grid">
                <!-- 左/主 窗格 -->
                <view
                  class="editor-pane pane-left"
                  :class="{
                    'pane-full': !splitMode,
                    'pane-half': splitMode,
                    focused: focusedPane === 'left'
                  }"
                  @tap="focusPane('left')"
                >
                  <!-- Epic #43 Track B / #79: embedded LibreOffice is THE editor
                       for Office docs when available (desktop). Web/h5 falls
                       through to FilePreview (docx 本地只读渲染).
                       Keep-alive pool: one instance per open Office doc (active +
                       LRU 保活，见 leftLibreFiles) hidden via v-show — switching
                       tabs must NOT re-boot the LOWA WASM engine. -->
                  <view
                    v-for="file in leftLibreFiles"
                    :key="'libre-left-' + file.id"
                    v-show="activeFileLeft && activeFileLeft.id === file.id"
                    class="pane-content"
                  >
                    <LibreOfficeEditor
                      :ref="el => setLibreRef('left', file.id, el)"
                      :file="file"
                      @ready="onLibreReady($event, 'left', file.id)"
                      @close="onLibreClose"
                      @open-url="onLibreOpenUrl"
                      @menu-state="pushMenuState"
                    />
                  </view>
                  <!-- 预热备胎实例（librePool.js）：file=null 时是后台预 boot 的
                       空白引擎，首开 Office 文档时过继（file 换成文档）省去整链
                       冷启动。未激活时用绝对定位 + visibility 隐藏而非 v-show：
                       display:none 下 boot 引擎画布无尺寸，风险未验证。 -->
                  <view
                    v-for="sp in libreSpares"
                    :key="'libre-spare-' + sp.key"
                    class="pane-content"
                    :class="{ 'libre-spare-standby': !(sp.file && activeFileLeft && activeFileLeft.id === sp.file.id) }"
                  >
                    <LibreOfficeEditor
                      :ref="el => setLibreSpareRef(sp, el)"
                      :file="sp.file"
                      @ready="onLibreSpareReady(sp, $event)"
                      @close="onLibreClose"
                      @open-url="onLibreOpenUrl"
                      @menu-state="pushMenuState"
                    />
                  </view>
                  <!-- 网页标签保活池（Web/H5）：与上面的编辑器保活池同形制——按标签建实例、
                       v-show 藏。BrowserPane 在 Web 下渲染的是 <iframe>，组件一卸载文档就
                       整个没了，切回来按 tab.url 重新加载（页内跳转、滚动位置、填了一半的
                       表单全丢）。桌面端这个池只留当前激活的那一个，保活由 BrowserView
                       detach 负责，见 webKeepAliveEnabled。 -->
                  <view
                    v-for="tab in leftWebTabs"
                    :key="'web-left-' + tab.id"
                    v-show="activeFileLeft && activeFileLeft.id === tab.id"
                    class="pane-content"
                  >
                    <BrowserPane
                      :tab-id="tab.id"
                      :url="tab.url"
                      @url-change="onBrowserUrlChange('left', tab.id, $event)"
                      @title-change="onBrowserTitleChange('left', tab.id, $event)"
                      @open-new-tab="openBrowserTab($event)"
                    />
                  </view>
                  <view v-if="activeFileLeft && !useLibreEditor(activeFileLeft) && !isBrowserTab(activeFileLeft)" class="pane-content">
                    <MarkdownPreview
                      v-if="isMarkdownTab(activeFileLeft)"
                      :content="activeFileLeft.content"
                      :file="activeFileLeft"
                    />
                    <DocDiffViewer
                      v-else-if="isDiffTab(activeFileLeft)"
                      :source-id="activeFileLeft.diffSource.id"
                      :target-id="activeFileLeft.diffTarget.id"
                      :source-name="activeFileLeft.diffSource.name"
                      :target-name="activeFileLeft.diffTarget.name"
                    />
                    <VersionCompareTab
                      v-else-if="isVersionCompareTab(activeFileLeft)"
                      :key="activeFileLeft.id"
                      :compare-spec="activeFileLeft.compareSpec"
                    />
                    <DocDiffViewer
                      v-else-if="isVersionTextDiffTab(activeFileLeft)"
                      :key="activeFileLeft.id"
                      :version-spec="activeFileLeft.versionSpec"
                    />
                    <DdRequestEditor
                      v-else-if="isDdRequest(activeFileLeft)"
                      :request-id="activeFileLeft.requestId"
                    />
                    <MarketDetailPane
                      v-else-if="activeFileLeft.tabType === 'market-detail'"
                      :key="activeFileLeft.id"
                      :spec="activeFileLeft.marketSpec"
                      @open-url="openBrowserTab($event)"
                    />
                    <!-- 系统设置标签：与 pages/admin 薄壳页共用同一个 AdminPane
                         （照插件广场 market-detail 那套 tab 形制）。 -->
                    <AdminPane
                      v-else-if="activeFileLeft.tabType === 'admin-settings'"
                      :key="activeFileLeft.id"
                      embedded
                      :initial-nav="activeFileLeft.adminNav || ''"
                      :initial-service="activeFileLeft.adminService || ''"
                    />
                    <!-- 个人中心标签：与 pages/userprofile 薄壳页共用同一个 UserProfilePane
                         （同上，照插件广场 market-detail 那套 tab 形制）。 -->
                    <UserProfilePane
                      v-else-if="activeFileLeft.tabType === 'user-profile'"
                      :key="activeFileLeft.id"
                      embedded
                    />
                    <PluginPane
                      v-else-if="activeFileLeft.fileType === 'plugin'"
                      :url="activeFileLeft.frontendEntry"
                      :plugin-id="activeFileLeft.pluginId || ''"
                      :permissions="activeFileLeft.permissions || []"
                      :project-id="projectId"
                    />
                    <!-- .drawio：诉讼可视化四份产物里唯一的可继续编辑版，走内嵌
                         draw.io。没有这条分支它会落进 FilePreview 的「暂不支持
                         预览」兜底，这个格式就白出了。 -->
                    <DrawioEditor
                      v-else-if="isDrawioFile(activeFileLeft)"
                      :key="'drawio-' + activeFileLeft.id"
                      :file="activeFileLeft"
                      :project-id="projectId"
                    />
                    <FilePreview
                      v-else
                      :file="activeFileLeft"
                      :show-edit-btn="false"
                      @extracted="onArchiveExtracted"
                    />
                  </view>
                  <view v-else-if="!activeFileLeft" class="pane-empty">
                    <image src="/static/iconmark_v2.png" class="empty-state-img" mode="aspectFit" />
                    <text class="empty-text">{{ $t('workbench.leftPaneIdle') }}</text>
                  </view>
                </view>

                <!-- 右/副 窗格 (分屏时显示) -->
                <view
                  v-if="splitMode"
                  class="editor-pane pane-right pane-half"
                  :class="{ focused: focusedPane === 'right' }"
                  @tap="focusPane('right')"
                >
                  <!-- Epic #43 Track B / #79: embedded LibreOffice keep-alive pool
                       (see left pane). -->
                  <view
                    v-for="file in rightLibreFiles"
                    :key="'libre-right-' + file.id"
                    v-show="activeFileRight && activeFileRight.id === file.id"
                    class="pane-content"
                  >
                    <LibreOfficeEditor
                      :ref="el => setLibreRef('right', file.id, el)"
                      :file="file"
                      @ready="onLibreReady($event, 'right', file.id)"
                      @close="onLibreClose"
                      @open-url="onLibreOpenUrl"
                      @menu-state="pushMenuState"
                    />
                  </view>
                  <!-- 网页标签保活池（见左窗格同名注释）。跨窗格拖拽是"在另一侧也打开同一
                       标签"（同 id、但 tabDragSplit 复制了对象），所以左右各有自己的实例
                       与自己的 tab.url，互不干扰。 -->
                  <view
                    v-for="tab in rightWebTabs"
                    :key="'web-right-' + tab.id"
                    v-show="activeFileRight && activeFileRight.id === tab.id"
                    class="pane-content"
                  >
                    <BrowserPane
                      :tab-id="tab.id"
                      :url="tab.url"
                      @url-change="onBrowserUrlChange('right', tab.id, $event)"
                      @title-change="onBrowserTitleChange('right', tab.id, $event)"
                      @open-new-tab="openBrowserTab($event)"
                    />
                  </view>
                  <view v-if="activeFileRight && !useLibreEditor(activeFileRight) && !isBrowserTab(activeFileRight)" class="pane-content">
                    <MarkdownPreview
                      v-if="isMarkdownTab(activeFileRight)"
                      :content="activeFileRight.content"
                      :file="activeFileRight"
                    />
                    <DocDiffViewer
                      v-else-if="isDiffTab(activeFileRight)"
                      :source-id="activeFileRight.diffSource.id"
                      :target-id="activeFileRight.diffTarget.id"
                      :source-name="activeFileRight.diffSource.name"
                      :target-name="activeFileRight.diffTarget.name"
                    />
                    <VersionCompareTab
                      v-else-if="isVersionCompareTab(activeFileRight)"
                      :key="activeFileRight.id"
                      :compare-spec="activeFileRight.compareSpec"
                    />
                    <DocDiffViewer
                      v-else-if="isVersionTextDiffTab(activeFileRight)"
                      :key="activeFileRight.id"
                      :version-spec="activeFileRight.versionSpec"
                    />
                    <DdRequestEditor
                      v-else-if="isDdRequest(activeFileRight)"
                      :request-id="activeFileRight.requestId"
                    />
                    <MarketDetailPane
                      v-else-if="activeFileRight.tabType === 'market-detail'"
                      :key="activeFileRight.id"
                      :spec="activeFileRight.marketSpec"
                      @open-url="openBrowserTab($event)"
                    />
                    <!-- 系统设置标签：见左窗格同名注释 -->
                    <AdminPane
                      v-else-if="activeFileRight.tabType === 'admin-settings'"
                      :key="activeFileRight.id"
                      embedded
                      :initial-nav="activeFileRight.adminNav || ''"
                      :initial-service="activeFileRight.adminService || ''"
                    />
                    <!-- 个人中心标签：见左窗格同名注释 -->
                    <UserProfilePane
                      v-else-if="activeFileRight.tabType === 'user-profile'"
                      :key="activeFileRight.id"
                      embedded
                    />
                    <PluginPane
                      v-else-if="activeFileRight.fileType === 'plugin'"
                      :url="activeFileRight.frontendEntry"
                      :plugin-id="activeFileRight.pluginId || ''"
                      :permissions="activeFileRight.permissions || []"
                      :project-id="projectId"
                    />
                    <DrawioEditor
                      v-else-if="isDrawioFile(activeFileRight)"
                      :key="'drawio-' + activeFileRight.id"
                      :file="activeFileRight"
                      :project-id="projectId"
                    />
                    <FilePreview
                      v-else
                      :file="activeFileRight"
                      :show-edit-btn="false"
                      @extracted="onArchiveExtracted"
                    />
                  </view>
                  <view v-else-if="!activeFileRight" class="pane-empty">
                    <image src="/static/iconmark_v2.png" class="empty-state-img" mode="aspectFit" />
                    <text class="empty-text">{{ $t('workbench.rightPaneIdle') }}</text>
                  </view>
                </view>
              </view>

            </view>

            <!-- 底部常用工具面板（仅占中间工作区宽度；右侧 AI 面板优先完整显示） -->
            <view v-if="showToolsPanel" class="bottom-panel" ref="bottomPanel" :style="{ height: toolsPanelHeight + 'px' }">
              <view class="bottom-resize-handle" @touchstart="startResize('bottom', $event)" @mousedown="startResize('bottom', $event)"></view>
              <view class="panel-header panel-header-tools">
                <!-- Group: Tabs + Specific Actions -->
                <view class="header-content-left">
                  <view class="panel-tabs awd-style">
                    <view
                      v-for="t in toolsList"
                      :key="t.key"
                      class="panel-tab"
                      :class="{ active: activeToolKey === t.key }"
                      @tap="switchToolTab(t.key)"
                    >
                      <text class="panel-tab-label">{{ t.label }}</text>
                      <view class="tab-indicator" v-if="activeToolKey === t.key"></view>
                    </view>
                  </view>

                  <!-- Variable Specific Actions (Moved from VariablePanel) -->
                  <view v-if="activeToolKey === 'variables'" class="tool-actions-group">
                    <view class="tool-action-btn" @tap="handleOpenCreateVariable" :title="$t('workbench.setAsVariable')">
                      <text class="btn-icon">＋</text>
                      <text class="btn-text">{{ $t('workbench.setAsVariable') }}</text>
                    </view>
                    <view class="tool-action-btn" @tap="handleSyncVariable" :title="$t('workbench.sync')">
                      <text class="btn-icon">↻</text>
                      <text class="btn-text">{{ $t('workbench.sync') }}</text>
                    </view>
                  </view>
                </view>

                <!-- Centered Search -->
                <view class="tools-search-centered" v-if="activeToolKey === 'variables' || activeToolKey === 'favorites' || activeToolKey === 'clipboard'">
                  <view class="tools-search-wrap">
                    <input
                      class="tools-search-input"
                      v-model="toolsSearchKeyword"
                      :placeholder="toolsSearchPlaceholder"
                      confirm-type="search"
                    />
                    <view v-if="toolsSearchKeyword" class="tools-search-clear" @tap="toolsSearchKeyword = ''">×</view>
                  </view>
                </view>

                <view class="panel-actions">
                  <view class="icon-btn" :title="$t('workbench.collapse')" @tap="toggleToolsPanel">
                    <text class="tool-icon">×</text>
                  </view>
                </view>
              </view>
              <view class="panel-body">
                <view class="tools-content">
                  <VariablePanel
                    v-if="activeToolKey === 'variables'"
                    ref="variablePanel"
                    :project-id="projectId"
                    :get-editor="getLibreVariableBridge"
                    :search-keyword="toolsSearchKeyword"
                  />
                  <ProjectFavoritesPanel
                    v-else-if="activeToolKey === 'favorites'"
                    ref="favoritesPanel"
                    :project-id="projectId"
                    :query="toolsSearchKeyword"
                    @insert="insertPlainTextToWps"
                    @open-url="openBrowserTab($event)"
                  />
                  <ClipboardPanel
                    v-else-if="activeToolKey === 'clipboard'"
                    ref="clipboardPanel"
                    :query="toolsSearchKeyword"
                    @insert="insertPlainTextToWps"
                    @preview-image="openImagePreview"
                  />
                </view>
              </view>
            </view>
          </view>

          <!-- 右侧 AI 面板（可拖拽宽度） -->
          <view
            v-if="showAiPanel"
            ref="aiPanel"
            class="side-panel side-panel-ai"
            :class="{ 'drag-over': dragOverAiPanel }"
            :style="{ width: aiPanelWidth + 'px' }"
            @dragover.prevent="handleAiDragOver"
            @dragleave="handleAiDragLeave"
            @drop="handleAiDrop"
          >
            <!-- ChatInterface Integration -->
            <!-- Note: We leverage ChatInterface for the entire panel content. -->
            <!-- Resize handle is still here in the outer container scope -->

            <ChatInterface
              ref="chatInterface"
              :project-id="String(projectId)"
              :project-name="project.name"
              :recent-history="chatHistoryList.slice(0, 3)"
              :history-badge="historyBadge"
              :active-tab="currentActiveTab"
              :active-tab-pane="focusedPane"
              @close="toggleAiPanel"
              @toggle-history="toggleHistoryDrawer"
              @new-chat="startNewChat"
              @load-history="loadHistoryChat"
              @message-action="handleChatInterfaceAction"
              @client-action="handleClientAction"
              @refresh-history="fetchChatHistory"
              @menu-state="pushMenuState"
              @artifact-open-tab="handleArtifactOpenTab"
              @open-file="handleOpenFileFromChat"
            />

            <view class="side-resize-handle" @touchstart="startResize('right', $event)" @mousedown="startResize('right', $event)"></view>

            <!-- History Drawer (Outside ChatInterface so it can overlay? Or ChatInterface handles it? ChatInterface has header but no drawer content? -->
            <!-- Update: The ChatInterface component I wrote DOES NOT contain the drawer content, it just emits toggle-history. -->
            <!-- The original logic had a dropdown-panel. I should perhaps keep the drawer logic here OR move it to ChatInterface. -->
            <!-- The user requirement was to 'upgrade UI'. -->
            <!-- ChatInterface.vue implementation: -->
            <!-- It has @tap="$emit('toggle-history')". -->
            <!-- It doesn't have the drawer markup. -->
            <!-- So I need to keep the History Drawer (dropdown) here, assuming they are positioned ok. -->

            <!-- 3. History Dropdown (Unified style) -->
            <view v-if="showHistoryDrawer" class="ai-dropdown-panel" @tap.stop style="top: 36px; border-radius: 0 0 8px 8px;">
                <view class="menu-item header">{{ $t('workbench.historyConversations') }}</view>
                <scroll-view scroll-y class="drawer-list" style="max-height: 350px;">
                    <view v-if="loadingHistory" class="menu-item" style="color:#999;">{{ $t('workbench.loadingText') }}</view>
                    <view v-else-if="chatHistoryList.length === 0" class="menu-item" style="color:#999;">{{ $t('workbench.noHistory') }}</view>
                    <view v-else v-for="chat in chatHistoryList" :key="chat.id" class="menu-item" @tap="loadHistoryChat(chat)">
                        <view v-if="convDotClass(chat)" class="conv-dot" :class="convDotClass(chat)"></view>
                        <view style="flex:1; overflow:hidden;">
                            <text class="item-title" style="display:block; font-size:13px; color:#333; margin-bottom:2px;">{{ chat.title || $t('workbench.unnamedConversation') }}</text>
                            <text class="item-preview" style="display:block; font-size:11px; color:#999; white-space:nowrap; overflow:hidden; text-overflow:ellipsis;">{{ chat.lastMessage }}</text>
                        </view>
                        <view style="display:flex; flex-direction:column; align-items:flex-end; margin-left:8px; flex-shrink:0;">
                            <text class="item-time" style="font-size:10px; color:#ccc;">{{ formatTime(chat.updatedAt) }}</text>
                            <text v-if="convStatusLabel(chat)" class="conv-status-label" :class="convDotClass(chat)">{{ convStatusLabel(chat) }}</text>
                        </view>
                    </view>
                </scroll-view>
            </view>
            <view v-if="showHistoryDrawer" class="dropdown-fixed-mask" @tap.stop="toggleHistoryDrawer"></view>
          </view>
        </view>
      </view>

      <!-- AI 导出为 Word 对话框 -->
      <view v-if="showExportDialog" class="upload-mask" @tap="closeExportDialog">
        <view class="folder-modal" @tap.stop>
          <view class="upload-header">
            <text class="upload-title">{{ $t('workbench.exportWordTitle') }}</text>
            <text class="upload-subtitle">{{ $t('workbench.exportWordSubtitle') }}</text>
          </view>
          <view class="folder-body">
            <view class="upload-row">
              <text class="upload-label">{{ $t('workbench.fileNameLabel') }}</text>
              <input
                v-model="exportFileName"
                class="dialog-input"
                :placeholder="$t('workbench.exportFileNamePlaceholder')"
              />
            </view>
            <view class="upload-row export-folder-label-row">
              <text class="upload-label">{{ $t('workbench.saveLocationLabel') }}</text>
            </view>
            <scroll-view class="export-folder-list" scroll-y>
              <view
                class="folder-item root-folder"
                :class="{ active: exportTargetParentId === null }"
                @tap="selectExportFolder(null)"
              >
                <svg class="folder-icon" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
                  <path v-for="(d, gi) in GLYPHS.folder" :key="gi" :d="d" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round" />
                </svg>
                <text class="folder-name">{{ $t('workbench.rootFolder') }}</text>
              </view>
              <view
                v-for="folder in exportFolderTree"
                :key="folder.id"
                class="folder-item"
                :class="{ active: exportTargetParentId === folder.id }"
                @tap="selectExportFolder(folder.id)"
              >
                <view
                  class="folder-indent"
                  :style="{ width: (folder.level * 24) + 'rpx' }"
                ></view>
                <svg class="folder-icon" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
                  <path v-for="(d, gi) in GLYPHS.folderOpen" :key="gi" :d="d" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round" />
                </svg>
                <text class="folder-name">{{ folder.name }}</text>
              </view>
              <view v-if="!exportFolderTree.length" class="empty-tip">
                <text>{{ $t('workbench.noOtherFolders') }}</text>
              </view>
            </scroll-view>
          </view>
          <view class="upload-footer">
            <view class="upload-btn upload-btn-secondary" @tap="closeExportDialog">
              {{ $t('common.cancel') }}
            </view>
            <view
              class="upload-btn upload-btn-primary"
              :class="{ 'upload-btn-disabled': exportLoading || !exportFileName.trim() }"
              @tap="!exportLoading && exportFileName.trim() && confirmExportWord()"
            >
              {{ exportLoading ? $t('workbench.exporting') : $t('workbench.confirmExport') }}
            </view>
          </view>
        </view>
      </view>

      <!-- 图片预览（剪贴板等）：桌面端 BrowserView 盖 DOM，须走 desktopOverlayActive 守卫 -->
      <view v-if="imagePreviewUrl" class="image-preview-mask" @tap="closeImagePreview">
        <image class="image-preview-img" :src="imagePreviewUrl" mode="aspectFit"></image>
        <view class="image-preview-close" @tap.stop="closeImagePreview">✕</view>
      </view>

      <!-- Screenshot Save Dialog -->
      <view v-if="showScreenshotSaveDialog" class="upload-mask" @tap="closeScreenshotSaveDialog">
        <view class="folder-modal" @tap.stop>
          <view class="upload-header">
            <text class="upload-title">{{ $t('workbench.saveScreenshotTitle') }}</text>
          </view>
          <view class="folder-content">
            <view class="upload-row">
              <text class="upload-label">{{ $t('workbench.fileNameLabel') }}</text>
              <input
                v-model="screenshotSaveName"
                class="dialog-input"
                :placeholder="$t('workbench.screenshotNamePlaceholder')"
              />
            </view>
            <view class="upload-row export-folder-label-row">
              <text class="upload-label">{{ $t('workbench.saveLocationLabel') }}</text>
            </view>
            <scroll-view scroll-y class="folder-tree-list">
              <view
                class="folder-item root-folder"
                :class="{ active: screenshotSaveParentId === null }"
                @tap="selectScreenshotFolder(null)"
              >
                <svg class="folder-icon" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
                  <path v-for="(d, gi) in GLYPHS.folderOpen" :key="gi" :d="d" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round" />
                </svg>
                <text class="folder-name">{{ $t('workbench.rootFolder') }}</text>
                <view v-if="!screenshotSaveParentId" class="check-icon">✓</view>
              </view>
              <template v-for="folder in screenshotFolderTree">
                  <view
                    v-if="isFolderVisible(folder)"
                    :key="folder.id"
                    class="folder-item"
                    :class="{ active: screenshotSaveParentId === folder.id }"
                    @tap="selectScreenshotFolder(folder.id)"
                  >
                    <!-- Indent -->
                    <view class="folder-indent" :style="{ width: (folder.level * 20) + 'px' }"></view>

                    <!-- Toggle Arrow -->
                    <view
                        class="folder-toggle"
                        @tap.stop="toggleExportFolder(folder)"
                        :style="{ visibility: (folder.children && folder.children.length) ? 'visible' : 'hidden' }"
                    >
                        <text :class="folder.expanded ? 'arrow-down' : 'arrow-right'">▶</text>
                    </view>

                    <svg class="folder-icon" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
                      <path v-for="(d, gi) in GLYPHS.folder" :key="gi" :d="d" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round" />
                    </svg>
                    <text class="folder-name">{{ folder.name }}</text>
                    <view v-if="screenshotSaveParentId === folder.id" class="check-icon">✓</view>
                  </view>
              </template>
              <view v-if="!screenshotFolderTree.length" class="empty-tip">
                <text>{{ $t('workbench.noSubfolders') }}</text>
              </view>
            </scroll-view>
          </view>
          <view class="upload-footer">
            <view class="upload-btn upload-btn-secondary" @tap="closeScreenshotSaveDialog">
              {{ $t('common.cancel') }}
            </view>
            <view
              class="upload-btn upload-btn-primary"
              :class="{ 'upload-btn-disabled': screenshotSaveLoading || !screenshotSaveName.trim() }"
              @tap="!screenshotSaveLoading && screenshotSaveName.trim() && confirmSaveScreenshot()"
            >
              {{ screenshotSaveLoading ? $t('workbench.savingText') : $t('workbench.confirmSave') }}
            </view>
          </view>
        </view>
      </view>

      <!-- OCR 截图：全屏浮层（单击或 ESC 退出；框选后出快捷命令条） -->
      <view
        v-if="showOcrOverlay"
        class="ocr-overlay"
      >
        <!-- #ifdef H5 -->
        <image
          v-if="ocrFrameUrl"
          class="ocr-frame-img"
          :src="ocrFrameUrl"
          mode="aspectFit"
          :style="ocrFrameImgStyle"
        />
        <view v-else class="ocr-frame-loading">
          <text>{{ $t('workbench.ocrFetchingFrame') }}</text>
        </view>
        <!-- #endif -->
        <view class="ocr-overlay-hintline">
          <text>{{ $t('workbench.ocrHint') }}</text>
        </view>
        <view class="ocr-frame-shade"></view>
        <view v-if="ocrOverlaySelecting || ocrHasSelection" class="ocr-selection" :style="ocrSelectionStyle"></view>

        <!-- 框选后的快捷命令 -->
        <view
          v-if="ocrActionBar.visible"
          class="ocr-actionbar"
          :style="{ left: ocrActionBar.x + 'px', top: ocrActionBar.y + 'px' }"
          @mousedown.stop
          @mouseup.stop
          @click.stop
          @touchstart.stop
          @touchend.stop
        >
          <view class="ocr-actionbar-row">
            <!-- 移除刷新画面 -->
            <view class="ocr-action" @tap="ocrDoCopy" :class="{ disabled: !ocrText && !ocrImageDataUrl }">{{ OCR_ACTION_LABELS.copy }}</view>
            <view class="ocr-action" @tap="ocrDoOpenSaveDialog">{{ OCR_ACTION_LABELS.download }}</view>
            <view class="ocr-action" @tap="ocrDoFavorite" :class="{ disabled: !ocrImageDataUrl || ocrLoading }">{{ OCR_ACTION_LABELS.favorite }}</view>
            <view class="ocr-action" @tap="ocrDoWebLink" :class="{ disabled: ocrLoading }">{{ OCR_ACTION_LABELS.webLink }}</view>
            <view class="ocr-action primary" @tap="ocrDoRecognize" :class="{ disabled: ocrLoading }">{{ OCR_ACTION_LABELS.recognize }}</view>
          </view>
        </view>
      </view>



      <!-- 文件关联选择弹窗：一个文本关联多个文件时，点击超链接弹出选择 -->
      <view v-if="fileLinkPicker.visible" class="upload-mask" @tap="closeFileLinkPicker">
        <view class="folder-modal" @tap.stop>
          <view class="upload-header">
            <text class="upload-title">{{ $t('workbench.chooseFileToOpen') }}</text>
          </view>
          <view class="folder-body">
            <view
              v-for="f in fileLinkPicker.files"
              :key="f.id"
              class="folder-item"
              @tap="openFileLinkTarget(f.id)"
            >
              <svg class="folder-icon" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
                <path v-for="(d, gi) in (f.isFolder ? GLYPHS.folder : GLYPHS.doc)" :key="gi" :d="d" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round" />
              </svg>
              <text class="folder-name">{{ f.name }}</text>
            </view>
            <view v-if="!fileLinkPicker.files || fileLinkPicker.files.length === 0" class="empty-tip">
              <text>{{ $t('workbench.noLinkedFiles') }}</text>
            </view>
          </view>
          <view class="upload-footer">
            <view class="upload-btn upload-btn-secondary" @tap="closeFileLinkPicker">{{ $t('common.close') }}</view>
          </view>
        </view>
      </view>

      <!-- 网核关联拖拽：全屏透明蒙层接管鼠标事件（避免进入 WPS iframe 后 mousemove 丢失导致“卡住”） -->
      <view v-if="webLinkDrag.active" class="webmark-drag-overlay" @mousedown.stop @mouseup.stop @mousemove.stop>
        <view
          class="webmark-drag-ghost"
          :style="{ left: webLinkDrag.x + 'px', top: webLinkDrag.y + 'px' }"
        >
          <image class="webmark-ghost-img" :src="webLinkDrag.imageDataUrl" mode="aspectFill" />
          <view class="webmark-ghost-badge">{{ $t('workbench.webMark') }}</view>
        </view>
      </view>

      <!-- OCR 结果不再使用弹窗：改为框选后的快捷命令条 -->

      <!-- 采纳等待处理：AdoptConflictDialog 只活在版本面板里，律师一切去别的面板
           （资源管理器、AI……）就什么提示都没有了，而这期间后端停在待裁决状态、
           版本捕获整体关闭。这条固定条是面板之外唯一的提示与入口。 -->
      <view
        v-if="adoptConflictPending && leftPaneKey !== 'version'"
        class="adopt-pending-bar"
      >
        <text class="adopt-pending-text">{{ $t('workbench.adoptPendingText') }}</text>
        <text class="adopt-pending-go" @tap="goHandleAdoptConflict">{{ $t('workbench.goHandle') }}</text>
      </view>

      <!-- IDE 化 Cmd+P 快速打开 -->
      <QuickOpenPanel
        v-if="quickOpenVisible"
        :project-id="projectId"
        @open="onQuickOpenFile"
        @close="quickOpenVisible = false"
      />

      <!-- 命令面板（⌥⌘P）：与菜单栏读同一份命令表 -->
      <CommandPalette
        v-if="commandPaletteVisible"
        @run="onCommandPaletteRun"
        @close="commandPaletteVisible = false"
      />

      <!-- 试用版 / 宽限预警说明弹窗（同一个壳，文案与主按钮随 graceKind 切换） -->
      <view v-if="showTrialInfo" class="awd-dialog-mask" @tap="showTrialInfo = false">
        <view class="awd-dialog" @tap.stop>
          <view class="awd-dialog-header">
            <text class="awd-dialog-title">{{ graceTitle }}</text>
          </view>
          <view class="awd-dialog-body">
            <text class="awd-dialog-text">{{ graceBody }}</text>
          </view>
          <view class="awd-dialog-footer">
            <button class="awd-btn awd-btn-secondary" @tap="showTrialInfo = false">{{ $t('workbench.gotIt') }}</button>
            <button class="awd-btn awd-btn-primary" @tap="graceAction">{{ graceActionLabel }}</button>
          </view>
        </view>
      </view>

    </view>

    <!-- 底部状态条（IDE 化：常驻工具入口 + 真实状态信号，等宽字体） -->
    <view class="status-bar" v-if="!isClientView">
      <view
        v-for="t in toolsList"
        :key="'sb-' + t.key"
        class="status-tool"
        :class="{ active: showToolsPanel && activeToolKey === t.key }"
        @tap="openToolFromStatusBar(t.key)"
      >
        <text class="status-tool-label">{{ t.label }}</text>
      </view>
      <view class="status-sep"></view>
      <view v-if="isRecording" class="status-item status-recording">
        <view class="status-dot recording"></view>
        <text>{{ $t('workbench.recordingActive') }}</text>
      </view>
      <view v-if="versionWorkStatus.enabled && (versionWorkStatus.working || versionWorkStatus.onDraft)" class="status-item status-clickable" @tap="goHandleAdoptConflict">
        <view class="status-dot amber"></view>
        <text>{{ versionWorkStatusLabel }}</text>
      </view>
      <view v-if="collabLinked" class="status-item status-clickable" @tap="openCollab('casefile')">
        <view class="status-dot" :class="collabTone"></view>
        <text>{{ collabStateText }}</text>
      </view>
      <view class="status-spacer"></view>
      <view v-if="activeFileLeft" class="status-item status-file">
        <text>{{ activeFileLeft.name }}</text>
      </view>
      <view v-if="splitMode" class="status-item">
        <text>{{ $t('workbench.splitBadge') }}</text>
      </view>
      <view class="status-sep"></view>
      <view class="status-item status-brand">
        <view class="status-dot mint"></view>
        <text>AI WorkDeck</text>
      </view>
    </view>
  </view>
</template>

<script>
import LibreOfficeEditor from '@/components/LibreOfficeEditor.vue'
import { host, isDesktopHost } from '@/services/host.js'
import BrowserPane from '@/components/BrowserPane.vue'
import FileTree from '@/components/FileTree.vue'
import QuickOpenPanel from '@/components/QuickOpenPanel.vue'
import CommandPalette from '@/components/CommandPalette.vue'
import AppMenuBar from '@/components/AppMenuBar.vue'
import FilePreview from '@/components/FilePreview.vue'
import VariablePanel from '@/components/VariablePanel.vue'
import ProjectFavoritesPanel from '@/components/ProjectFavoritesPanel.vue'
import FileLinkDropZone from '@/components/FileLinkDropZone.vue'
import FileStagingArea from '@/components/FileStagingArea.vue'
import PluginPane from '@/components/PluginPane.vue' // Added
import DrawioEditor from '@/components/DrawioEditor.vue'
// 插件广场 VS Code 形态：左栏列表面板 + 中栏详情 tab（整页 MarketPane 仅存于 admin 独立页）
import MarketSidebarPanel from '@/components/MarketSidebarPanel.vue'
import MarketDetailPane from '@/components/MarketDetailPane.vue'
import ProjectHomePane from '@/components/project-home/ProjectHomePane.vue'
import AdminPane from '@/components/admin/AdminPane.vue'
import UserProfilePane from '@/components/userprofile/UserProfilePane.vue'
import EasyVoicePane from '@/components/EasyVoicePane.vue'
import DesensitizePane from '@/components/DesensitizePane.vue'
import ClipboardPanel from '@/components/ClipboardPanel.vue'
import SearchPanel from '@/components/SearchPanel.vue'
import VersionPanel from '@/components/version/VersionPanel.vue'
import InviteMemberDialog from '@/components/InviteMemberDialog.vue'
import CollabDialog from '@/components/collab/CollabDialog.vue'
import { MEMBER_GROUP_LABELS } from '@/config/memberRoles.js'
import { globalOverlayActive } from '@/utils/overlayState.js'
import CompareDocDialog from '@/components/CompareDocDialog.vue'
import DocDiffViewer from '@/components/DocDiffViewer.vue'
import VersionCompareTab from '@/components/version/VersionCompareTab.vue'
import MarkdownPreview from '@/components/MarkdownPreview.vue'
import FilePickerDialog from '@/components/FilePickerDialog.vue'

import {
  getProject,
  renameProject,
  getFileDetail,
  renameFile,
  createProjectFavorite,
  createDocFileLink,
  getDocFileLink,
  saveClipboardText,
  saveProjectVariable,
  getProjectVariables,
  getProjectFiles,
  batchCopyFiles,
  exportAiDocx,
  getProjectMembers,
  logActivity,
  inviteClient,
  removeProjectMember,
  getAiHistory,

  getAiConversations,
  getPlugins, // Added
  resolvePluginEntryUrl,
  getSkills,
  getFileText,
  getVersionStatus, // 版本面板之外也要知道「有没有采纳等待处理」
  promptFeatureNotConfigured, // 功能未配置统一引导（#18 T7）
  getProjectLocalPath, // 在访达中显示（IDE 化）
  getFileLocalPath,
  getMyProjects, // 最近项目切换器
  bindShareholderMeetingConversation, // 股东大会核查：会话绑定
  getLicenseStatus, // 试用版/正式版标识（含 accountConnected 组合口径）
  getCloudStatus, // 协作 chip：这份案卷有没有放进团队案件库、状态如何
  checkCloud, // 协作 chip 的联网刷新（cloudStatus 是不联网的本地快照）
  getCurrentUser as getCurrentUserApi // 顶栏头像：补一次真实接口，本地缓存只是首屏兜底
} from '@/services/api.js'
import { openExternalUrl } from '@/utils/externalLink.js'
import { loadSiteLinks, siteBaseUrl } from '@/utils/siteLinks.js'
import { getCurrentUser } from '@/utils/auth.js'
import { getInitial } from '@/utils/textInitial.js'
import { recordProjectVisit, getRecentProjectIds, syncRecentToMenuFetching } from '@/utils/recentProjects.js'
import { markdownToPlainText } from '@/utils/markdownPlain.js'
import { FILE_BATCH_ACTIONS, FILE_TREE_QUICK_ACTIONS } from '@/config/fileActions.js'
import { WORKBENCH_TOOLS } from '@/config/tools.js'
import { OCR_ACTION_LABELS, INTERNAL_LINK_SCHEMES, WPS_INTERNAL_HTTP_LINK_BASE } from '@/config/workbenchActions.js'
import {
  LEFT_SIDEBAR_PLUGINS,
  VERSION_PLUGIN,
  filterPluginsByEnabledSkills,
  getLeftSidebarPlugin,
  getPluginsForUser,
  migrateLeftPaneKey
} from '@/config/leftSidebarPlugins.js'

import { activityTracker } from '@/utils/activityTracker.js'

import { ICONS as GLYPHS } from '@/config/icons.js'
import DdFilesPanel from '@/components/DdFilesPanel.vue'
import ShareholderMeetingPanel from '@/components/ShareholderMeetingPanel.vue'
import MeetingRecordingPanel from '@/components/MeetingRecordingPanel.vue'
import LitigationVisualPanel from '@/components/LitigationVisualPanel.vue'
import DdRequestEditor from '@/components/DdRequestEditor.vue'
import ChatInterface from '@/components/ChatInterface.vue'
import { panelSwitchingMethods } from './panelSwitching.js'
import { menuCommandsMethods } from './menuCommands.js'
import { agentClientActionMethods } from './agentClientActions.js'
import { librePoolMethods } from './librePool.js'
import { stagingAreaMethods } from './stagingArea.js'
import { tabDragSplitMethods } from './tabDragSplit.js'
import { fileOpenTabsMethods } from './fileOpenTabs.js'
import { clipboardBridgeMethods } from './clipboardBridge.js'
import { ocrActionMethods } from './ocrActions.js'
import { ocrCaptureMethods } from './ocrCapture.js'

// 网页标签保活上限（只在 Web/H5 生效，桌面端保活是 BrowserView 的活，见 leftWebTabs）。
// 为什么要有上限、而桌面端可以不要：从窗口摘下的 BrowserView 会被 Chromium 冻住渲染进程，
// 藏起来的 iframe 不会——它们跟前台页共用同一个渲染进程，定时器照跑、音视频照放。
// 5 = 一件案子同时对着看的参考页大致就这么多；再往后的尾巴是冷的，被淘汰时重新加载
// 也能回到正确的那一页（地址已经跟着导航走了），不会退回默认首页。
const WEB_KEEPALIVE_MAX = 5

export default {
  components: {
    LibreOfficeEditor,
    BrowserPane,
    QuickOpenPanel,
    CommandPalette,
    AppMenuBar,
    FileTree,
    FilePreview,
    VariablePanel,
    ProjectFavoritesPanel,
    FileLinkDropZone,
    FileStagingArea,
    ClipboardPanel,
    DdFilesPanel,
    ShareholderMeetingPanel,
    MeetingRecordingPanel,
    LitigationVisualPanel,
    DdRequestEditor,
    InviteMemberDialog,
    CollabDialog,
    ChatInterface,
    MarkdownPreview,
    PluginPane, // Added
    DrawioEditor,
    MarketSidebarPanel,
    MarketDetailPane,
    ProjectHomePane,
    AdminPane,
    UserProfilePane,
    CompareDocDialog,
    DocDiffViewer,
    VersionCompareTab,
    EasyVoicePane,
    DesensitizePane,
    FilePickerDialog,
    SearchPanel,
    VersionPanel
  },
  data() {
    return {
      projectId: null,
      project: {},
      // Screenshot Save Dialog
      showScreenshotSaveDialog: false,
      imagePreviewUrl: '',
      screenshotSaveName: '',
      screenshotSaveParentId: null,
      screenshotFolderTree: [],
      screenshotSaveLoading: false,
      screenshotSaveDataUrl: '', // Cached for save dialog
      projectMembers: [], // Added
      isRenamingProject: false,
      // File Picker
      showFilePicker: false,
      // 这次打开文件选择器允不允许选文件夹。默认 false，由调用方在打开前置位——
      // 三个调用方共用同一个对话框实例，不区分的话脱敏面板也能选中文件夹了。
      filePickerAllowFolder: false,
      // 已启用的 skill id。null = 还没拉到（按全部启用处理，见 LEFT_SIDEBAR_PLUGINS）。
      enabledSkillIds: null,
      easyVoiceImportCallback: null,
      renameProjectName: '',
      userDisplayName: this.$t('workbench.defaultUserName'),

      // 授权状态（试用版标识，商业化解锁门）
      licenseMode: '',
      showTrialInfo: false,
      // 账户连接状态（商业化 PR-B）：已连接时 chip 改显「已连接账户」
      accountConnected: false,
      // 宽限预警（2026-08 官方版必须账户登录）：'legacyTrial' | 'offlineReverify' | ''
      graceKind: '',
      graceDays: 0,

      // 布局状态
      sidebarWidth: 260, // 侧边栏宽度
      sidebarCollapsed: false,
      isCompactLayout: false,
      leftPaneKey: null, // Initialize to null to prevent premature loading
      // 「语音」面板内部的 tab（语音合成 / 会议录音）。刻意不持久化：
      // 会议录音那个 tab 是 skill 门控的，记住它会让停用 skill 之后再进来落在
      // 一个不渲染的 tab 上（v-else 兜底能救，但 tab 条上没有高亮项，看着像坏了）。
      voiceTab: 'tts',
      // 顶栏右上角头像下拉（个人中心 / 系统设置）
      avatarMenuOpen: false,
      // 单文件历史：右键「这份文件的历史」时设置，version 面板据此只显示这份文件的版本
      versionFileFilter: null,
      // 有一次采纳停在待裁决状态（/status 的 adoptConflict）。版本面板之外也要提示，
      // 见模板里的 .adopt-pending-bar。版本面板打开时由它的 /status 拉取实时同步。
      adoptConflictPending: false,
      // 文件树批量选择模式（由页面控制开关）
      fileBatchMode: false,
      checkedFileIds: [],
      showBatchMenu: false,
      showFileMoreMenu: false,
      FILE_TREE_QUICK_ACTIONS,

      // 文档对比
      showCompareDialog: false,
      compareDocuments: [], // 待比较的文档列表

      // 右侧 AI 面板（IDE 右侧窗格）
      showAiPanel: false,
      aiPanelWidth: 360,
      aiContextPreview: null,
      aiContextLoading: false,

      // 底部常用工具面板（IDE 底部抽屉）
      showToolsPanel: false,
      toolsPanelHeight: 260,
      activeToolKey: 'variables',
      toolsSearchKeyword: '',

      // 拖拽调整尺寸状态（left/right/bottom）
      resizing: {
        active: false,
        target: null, // 'left' | 'right' | 'bottom'
        startX: 0,
        startY: 0,
        startSidebarWidth: 0,
        startAiWidth: 0,
        startToolsHeight: 0
      },
      _resizeRaf: null,
      _resizePendingX: 0,
      _resizePendingY: 0,
      boundResizeMove: null,
      boundStopResize: null,

      aiMessages: [], // { id, role: 'user'|'assistant', content }
      aiLoading: false,
      scrollTop: 0, // Added for scroll control
      aiInput: '',
      pastedImages: [],
      // 注：旧 AI 面板的第二份模型清单（currentModelId/availableModels/showModelDropdown）
      // 已随 v1 通道移除——它写死了 gemini-1.5-pro 与 ollama 两个非白名单 id，
      // 模型选择的唯一入口是 ChatInterface 组件 + GET /api/ai/models。
      // Context
      activeAiFileName: '',
      manualContextFiles: [], // Multi Context Support
      dragOverAiPanel: false,

      // AI New Features
      showHistoryDrawer: false,
      loadingHistory: false,
      chatHistoryList: [],
      currentConversationId: null, // Added for tracking current session
      // 后台任务状态点：上次轮询的 {conversationId: runStatus} 快照 + 跑完未读集合
      convStatusSnapshot: {},
      unreadConversations: [],
      convStatusPollTimer: null,
      selectedContextNode: null, // Picker 中临时选中的节点
      // AI 导出 Word 相关（后端生成 docx）
      showExportDialog: false,
      exportTargetParentId: null,
      exportFolderTree: [], // [{id, name, level, parentId}]
      exportFileName: '',
      exportSourceMessage: null,
      exportLoading: false,

      // OCR 摘录
      ocrLoading: false,
      ocrImageDataUrl: '',
      ocrText: '',
      ocrSourceUrl: '',
      // 全屏截图浮层
      showOcrOverlay: false,
      ocrStream: null,
      ocrVideo: null, // offscreen video
      ocrFrameCanvas: null, // offscreen frame canvas（vw x vh）
      ocrFrameView: null, // { vw, vh, cw, ch, dx, dy, scale }
      ocrFrameLoading: false,
      ocrFrameUrl: '',
      ocrHostRect: null, // Desktop: BrowserView bounds（用于把截图铺到网页区域，确保坐标准确）
      ocrOverlaySelecting: false,
      ocrSel: { x1: 0, y1: 0, x2: 0, y2: 0 },
      ocrActionBar: { visible: false, x: 0, y: 0 },
      ocrDebug: true,
      ocrLastPointer: { x: 0, y: 0 },

      // Invite Modal (Refactored)
      showInviteModal: false,

      // 网核关联拖拽（将截图证据块拖到 WPS 文档中插入标记）
      webLinkDrag: {
        active: false,
        x: 0,
        y: 0,
        favoriteId: null,
        imageDataUrl: '',
        sourceUrl: '',
        title: ''
      },
      _webLinkMoveHandler: null,
      _webLinkUpHandler: null,
      _webLinkKeydownHandler: null,

      // 文件拖拽到 WPS 高亮文本：建立超链接关联
      fileLinkDrag: {
        active: false,
        file: null, // { id, name, fileType, wpsFileId }
        hoverSide: null // 'left' | 'right' | null
      },
      fileLinkPicker: {
        visible: false,
        side: 'left',
        files: [],
        files: [],
        linkKey: ''
      },
      // Desensitize Callback
      desensitizeFileSelectCallback: null,
      // 诉讼可视化面板的材料范围选择回调（复用同一个 FilePickerDialog）
      litigationScopeCallback: null,

      stagingFiles: [], // 文件暂存区列表
      // 免费额度用量 { fileCount, totalBytes, limited, maxFiles, maxBytes }；
      // null = 不限制或取不到（旧后端），此时不显示用量条
      stagingUsage: null,
      stagingOriginalParents: {}, // 记录文件进入暂存区前的原始 parentId: { fileId: originalParentId }
      splitMode: false,
      quickOpenVisible: false, // IDE 化 Cmd+P 快速打开
      commandPaletteVisible: false, // 命令面板（⌥⌘P），读命令注册表
      menuBarRefreshKey: 0, // Windows 自绘菜单栏的重建信号（跟着 pushMenuState 走）
      projectSwitcherOpen: false, // IDE 化最近项目切换器
      switcherProjects: [],
      switcherLoadFailed: false, // 拉取最近项目失败：与"确实没有其他最近项目"的空态区分开，不能吞成同一句文案
      versionWorkStatus: { enabled: false, working: false, changedCount: 0, onDraft: null }, // 顶栏工作状态点
      // 协作（团队案件库）状态：{linked, serverUrl, pendingUpload, remoteAhead, offline}
      collabCloud: null,
      collabDialogVisible: false,
      collabInitialTab: 'casefile',
      collabRefreshToken: 0, // 自增一次 = 让版本面板重拉自己的那份状态
      focusedPane: 'left', // 'left' | 'right'

      // 文件状态 - 分两组管理
      leftFiles: [], // 左侧文件列表
      rightFiles: [], // 右侧文件列表
      activeFileIdLeft: null, // 左侧当前激活ID
      activeFileIdRight: null, // 右侧当前激活ID

      // Members
      currentUser: {},
      pageEnterTime: 0,

      // Tabs 拖拽状态
      draggingTab: null, // { fileId, fromPane }
      tabDragOver: null, // { fileId, pane }

      // Epic #43: embedded LibreOffice editor. When active, backend AI commands
      // route to it (handleEditorCommand).
      libreOfficeActive: false,
      libreOfficeExecutor: null,
      // 内嵌编辑器保活 LRU：'pane:fileId'（left:123），最近激活在前。在池中的
      // Office 标签切走时实例不销毁（v-show 隐藏），切回免重 boot/重载。
      libreLruKeys: [],
      // 预热备胎实例（librePool.js）：{key, file}。file=null 是后台预 boot 的
      // 空白备胎；过继后 file 为真实文档、实例转正（渲染仍在此数组）。
      libreSpares: [],
      // 网页标签保活 LRU：'pane:tabId'，最近激活在前（同 libreLruKeys 的形制）。
      // 只在 Web/H5 用得上——见 leftWebTabs 与 WEB_KEEPALIVE_MAX。
      webKeepAliveKeys: [],
      // 后端 doc_stream_data（旧名 wps_stream_data）流式写入的本地缓冲（#79：LibreOffice 消费端）
      _docStreamBuffer: '',
      _docStreamTimer: null,
      _docStreamBusy: false,
      // Epic #43 Track B: when true, opening an Office document uses the inline
      // embedded LibreOffice editor. Set at init from desktop embed availability
      // — install-and-use, zero config.
      libreOfficePreferred: false,

      // 文件信息轮询定时器
      fileInfoPollingIntervals: {}
      ,
      _desktopWebMarkUnsub: null
      ,
      _desktopRendererOpenUnsub: null
      ,
      _desktopOcrSelectionUnsub: null
      ,
      _desktopOcrSelectionErrUnsub: null,

      // Hover States for File Tree Icons
      hoverActionKey: null,

      // Activity Recording State
      isRecording: false,

      // Recording Toast
      showRecordingToast: false,
      recordingToastMessage: '',
      recordingToastTimer: null,

      // Mode-based active tab persistence
      lastActiveIdsByMode: {
        left: { 'files': null, 'dd-files': null },
        right: { 'files': null, 'dd-files': null }
      },
      dynamicPlugins: [], // Added for dynamic sidebar icons
      stagingPinned: false, // Added: keeps staging area open via sidebar button
      stagingManuallyCollapsed: false, // Track if user explicitly collapsed staging area
      stagingFolderId: null // ID of the .stagezone folder
    }
  },
  computed: {
    GLYPHS() {
      return GLYPHS
    },
    /**
     * 顶栏宽限 chip 与说明弹窗的文案。两种宽限共用一个壳，只有文案与主按钮不同：
     * - legacyTrial：存量试用票据的过渡期，出路是登录账户（去官网注册）；
     * - offlineReverify：账户授权快到 30 天未复验，出路是联网启动一次（或找人工）。
     * graceKind 为空时这些值不会被渲染，但仍要给出合法回退——弹窗壳是共用的，
     * 试用版 chip 点开走的也是同一个弹窗。
     */
    graceChipText() {
      if (this.graceKind === 'legacyTrial') {
        return this.$t('workbench.trialCountdown', { n: this.graceDays })
      }
      return this.$t('workbench.reverifyCountdown', { n: this.graceDays })
    },
    graceTitle() {
      if (this.graceKind === 'legacyTrial') return this.$t('workbench.graceLegacyTitle')
      if (this.graceKind === 'offlineReverify') return this.$t('workbench.graceReverifyTitle')
      return this.$t('workbench.trialBadge')
    },
    graceBody() {
      if (this.graceKind === 'legacyTrial') {
        return this.$t('workbench.graceLegacyBody', { n: this.graceDays })
      }
      if (this.graceKind === 'offlineReverify') {
        return this.$t('workbench.graceReverifyBody', { n: this.graceDays })
      }
      return this.$t('workbench.trialInfoBody')
    },
    graceActionLabel() {
      if (this.graceKind === 'offlineReverify') return this.$t('workbench.openAccountPanel')
      return this.$t('workbench.learnFullVersion')
    },
    /**
     * 「语音」面板里的「会议录音」tab 显不显示。语音两项合并后门控从 rail 位挪到
     * 了这里，判据仍是同一份 enabledSkillIds——**null（还没拉到）按启用处理**，
     * 与 LEFT_SIDEBAR_PLUGINS 那处同一个口径：宁可多显示一瞬，也不要让用户
     * 以为功能没了。
     */
    meetingRecorderEnabled() {
      // enabledSkillIds 是**数组**（loadEnabledSkills 里 .map 出来的），不是 Set
      if (this.enabledSkillIds === null) return true
      return this.enabledSkillIds.includes('meeting-recorder')
    },
    /**
     * 「语音」面板里的「语音合成」tab 显不显示，判据同 meetingRecorderEnabled：
     * text-to-speech skill 默认启用（老用户升级后入口不消失），可在广场停用。
     */
    ttsEnabled() {
      if (this.enabledSkillIds === null) return true
      return this.enabledSkillIds.includes('text-to-speech')
    },
    /**
     * voiceTab 记的是用户上一次点的 tab，与「当前哪个 tab 真的可用」是两回事——
     * 默认值是 'tts'，如果 text-to-speech 被停用而 meeting-recorder 还启用，
     * 直接按 voiceTab 渲染会两个面板都不出现。这里做一次兜底折算：优先尊重用户
     * 选择，选的那个不可用时落到唯一可用的那个，两个都不可用时（rail 位本应已
     * 隐藏）返回 null。
     */
    effectiveVoiceTab() {
      if (this.voiceTab === 'recorder' && this.meetingRecorderEnabled) return 'recorder'
      if (this.voiceTab === 'tts' && this.ttsEnabled) return 'tts'
      if (this.ttsEnabled) return 'tts'
      if (this.meetingRecorderEnabled) return 'recorder'
      return null
    },
    // 历史入口的聚合状态点：等用户操作(黄) > 运行中(绿) > 跑完未读(蓝)
    historyBadge() {
      const list = this.chatHistoryList || []
      // AWAITING_INPUT（模型反问等回答）与待审批同属「球在用户这边」，共用黄点
      if (list.some(c => c.runStatus === 'PAUSED' || c.runStatus === 'AWAITING_APPROVAL'
          || c.runStatus === 'AWAITING_INPUT' || c.runStatus === 'INTERRUPTED')) return 'dot-attention'
      if (list.some(c => c.runStatus === 'RUNNING' && c.conversationId !== this.currentConversationId)) return 'dot-running'
      if (list.some(c => c.unread)) return 'dot-unread'
      return ''
    },
    // 桌面端：任一全屏蒙层/弹窗打开时为 true。BrowserView 是原生层，永远盖在
    // HTML 之上，所以弹窗期间必须隐藏 BrowserView，否则弹窗会被网页挡住"点了没反应"。
    desktopOverlayActive() {
      return !!(
        // 页面树之外的浮层（反馈浮窗）也要能压住 BrowserView：它自己不调
        // setViewsVisible，只置这个全局 ref，避免和下面这一处 watcher 互相打架
        globalOverlayActive.value ||
        this.showOcrOverlay ||
        this.showScreenshotSaveDialog ||
        this.showExportDialog ||
        this.showCompareDialog ||
        this.showFilePicker ||
        this.showInviteModal ||
        !!this.imagePreviewUrl ||
        (this.fileLinkPicker && this.fileLinkPicker.visible)
      )
    },
    LEFT_SIDEBAR_PLUGINS() {
      const user = getCurrentUser()
      const base = (user && user.role === 'CLIENT')
        ? getPluginsForUser('CLIENT')
        : [...LEFT_SIDEBAR_PLUGINS, ...this.dynamicPlugins]
      // 声明了 requiresSkill 的插件位（诉讼可视化）跟着 skill 启停走：默认不安装，
      // 用户在广场里装了才出现在左栏。
      //
      // null = 还没拉到启用列表，此时不过滤。**不能当成空集合**——那会把已装的
      // 功能在每次刚进页面时先从左栏抹掉再冒出来，接口挂了更是永远不见。
      // 宁可多显示一瞬，也不要让用户以为功能没了。
      if (this.enabledSkillIds === null) return base
      const filtered = filterPluginsByEnabledSkills(base, this.enabledSkillIds)
      // 「语音」rail 位本身没有 requiresSkill（门控在面板内部按两个 tab 分别做），
      // 两个 tab 都停用时才整个位隐藏，判据复用同一份 ttsEnabled/meetingRecorderEnabled。
      if (!this.ttsEnabled && !this.meetingRecorderEnabled) {
        return filtered.filter(p => p.key !== 'voice')
      }
      return filtered
    },
    // 版本记录 2026-08-19 挪出 rail 数组、独立渲染在「项目成员」与「暂存区」之间，
    // 模板拿不到裸导入的 VERSION_PLUGIN，包一层 computed 才能在模板里用它的 svgPaths。
    VERSION_PLUGIN() {
      return VERSION_PLUGIN
    },
    toolsSearchPlaceholder() {
      if (this.activeToolKey === 'variables') return this.$t('workbench.searchVariables')
      if (this.activeToolKey === 'favorites') return this.$t('workbench.searchFavorites')
      if (this.activeToolKey === 'clipboard') return this.$t('workbench.searchClipboard')
      return this.$t('workbench.searchDefault')
    },
    OCR_ACTION_LABELS() {
      return OCR_ACTION_LABELS
    },
    INTERNAL_LINK_SCHEMES() {
      return INTERNAL_LINK_SCHEMES
    },
    WPS_INTERNAL_HTTP_LINK_BASE() {
      return WPS_INTERNAL_HTTP_LINK_BASE
    },
    FILE_BATCH_ACTIONS() {
      return FILE_BATCH_ACTIONS
    },
    // 是否为“仅尽调”视图（客户）
    // IDE 化顶栏工作状态点文案
    versionWorkStatusLabel() {
      const s = this.versionWorkStatus
      if (s.onDraft && s.onDraft.name) return this.$t('workbench.workingOnDraft', { name: s.onDraft.name })
      if (s.working) return s.changedCount ? this.$t('workbench.workingChanged', { count: s.changedCount }) : this.$t('workbench.working')
      return ''
    },
    isClientView() {
      const user = getCurrentUser()
      return user && user.role === 'CLIENT'
    },
    // 协作 UI 的总闸：只有这份案卷真的放进过团队案件库才渲染任何协作元素。
    collabLinked() {
      return !!(this.collabCloud && this.collabCloud.linked) && !this.isClientView
    },
    /*
     * 协作状态口径（顶栏 chip / 底部状态条 / 版本面板状态行 / 协作抽屉四处同源同序）：
     *   有文件等你做选择 > 暂时连不上案件库 > 同事交了新稿 > 有改动还没交稿 > 和大家的稿一致
     *
     * 「有改动还没交稿」同时吃两条独立信号：cloudStatus.pendingUpload（有一次交稿被拒
     * 记了待办）与 /version/status 的 working（手头这段活还没收尾）。只看前者的话，
     * 律师改了半天文件还没结束本次工作时 chip 会显示「和大家的稿一致」——技术上没错
     * （还没落成版本，确实没什么可交），律师读起来却是假绿灯。
     */
    collabStateText() {
      if (this.adoptConflictPending) return this.$t('workbench.adoptPendingText')
      const c = this.collabCloud || {}
      if (c.offline) return this.$t('workbench.collabOffline')
      if (c.remoteAhead) return this.$t('workbench.collabRemoteAhead')
      if (c.pendingUpload || this.versionWorkStatus.working) return this.$t('workbench.collabPendingUpload')
      return this.$t('workbench.collabInSync')
    },
    collabTone() {
      if (this.adoptConflictPending) return 'amber'
      const c = this.collabCloud || {}
      if (c.offline) return 'amber'
      if (c.remoteAhead || c.pendingUpload || this.versionWorkStatus.working) return 'blue'
      return 'green'
    },
    // 是否有权管理成员
    canManageMembers() {
      const user = getCurrentUser()
      // Simplified: Admin or owner (backend checks too)
      return user && user.role !== 'CLIENT'
    },
    leftPaneTitle() {
      if (this.leftPaneKey === 'market') return this.$t('workbench.pluginMarket')
      try {
        return getLeftSidebarPlugin(this.leftPaneKey)?.label || this.$t('workbench.explorerFallback')
      } catch (e) {
        return this.$t('workbench.explorerFallback')
      }
    },
    groupedMembers() {
      const groups = {
        admin: { label: MEMBER_GROUP_LABELS.admin, list: [] },
        member: { label: MEMBER_GROUP_LABELS.member, list: [] },
        client: { label: MEMBER_GROUP_LABELS.client, list: [] }
      }

      this.projectMembers.forEach(m => {
        if (m.role === 'ADMIN' || m.role === 'MANAGER' || m.id === this.project.managerId) {
          groups.admin.list.push(m)
        } else if (m.role === 'CLIENT') {
          groups.client.list.push(m)
        } else {
          groups.member.list.push(m)
        }
      })

      return [groups.admin, groups.member, groups.client].filter(g => g.list.length > 0)
    },
    checkedFileCount() {
      return Array.isArray(this.checkedFileIds) ? this.checkedFileIds.length : 0
    },
    activeFileLeft() {
      const file = this.leftFiles.find(f => f.id === this.activeFileIdLeft)
      if (file && !this.isTabVisible(file)) return null
      return file
    },
    activeFileRight() {
      const file = this.rightFiles.find(f => f.id === this.activeFileIdRight)
      if (file && !this.isTabVisible(file)) return null
      return file
    },
    // 内嵌 LibreOffice 保活池（每 pane 一组常驻实例）：当前激活的 Office 文件
    // 必进池（即使 LRU 记账未跟上），其余按 libreLruKeys 保活。文件关闭
    // （出 leftFiles/rightFiles）时自然出池 → 组件卸载走现有 close 流程。
    leftLibreFiles() {
      return this.leftFiles.filter(f => this.useLibreEditor(f) &&
        !this.libreSpares.some(sp => sp.file && sp.file.id === f.id) && // 过继实例渲染自备胎槽
        (f.id === this.activeFileIdLeft || this.libreLruKeys.includes('left:' + f.id)))
    },
    rightLibreFiles() {
      return this.rightFiles.filter(f => this.useLibreEditor(f) &&
        (f.id === this.activeFileIdRight || this.libreLruKeys.includes('right:' + f.id)))
    },
    // 网页标签保活池只在没有 BrowserView 能力的宿主（Web/H5）里开。
    // 桌面端**绝不能**开：那边 BrowserPane 挂载即 browser-create，一次挂 5 个
    // 就是 5 个 BrowserView 同时挂到窗口上（后台那几个会浮到最上层盖住界面，
    // 正是 utility-tools.md「无脑挂回全部」那条地雷），而桌面端的保活早已由
    // BrowserView detach 解决了（PR#401），本来就不需要池。
    webKeepAliveEnabled() {
      try {
        return !host.browser
      } catch (e) {
        return true
      }
    },
    // 与 leftLibreFiles 同形制：当前激活的网页标签必进池，其余按 LRU 保活，
    // 都用 v-show 藏而不是卸载——BrowserPane 在 Web 下是个 <iframe>，组件一卸载
    // 文档就没了，切回来只能按 tab.url 重新加载（那正是「切走再切回来丢内容」）。
    leftWebTabs() {
      if (!this.webKeepAliveEnabled) {
        const active = this.activeFileLeft
        return (active && this.isBrowserTab(active)) ? [active] : []
      }
      return this.leftFiles.filter(f => this.isBrowserTab(f) &&
        (f.id === this.activeFileIdLeft || this.webKeepAliveKeys.includes('left:' + f.id)))
    },
    rightWebTabs() {
      if (!this.webKeepAliveEnabled) {
        const active = this.activeFileRight
        return (active && this.isBrowserTab(active)) ? [active] : []
      }
      return this.rightFiles.filter(f => this.isBrowserTab(f) &&
        (f.id === this.activeFileIdRight || this.webKeepAliveKeys.includes('right:' + f.id)))
    },
    // NEW: Current active tab for AI context (prioritizes focused pane)
    currentActiveTab() {
      if (this.focusedPane === 'right' && this.activeFileRight) {
        return this.activeFileRight
      }
      return this.activeFileLeft || this.activeFileRight
    },
    computedActiveToolName() {
      const target = this.getActiveAiTargetFile()
      return target && target.name ? target.name : ''
    },
    // New Computed for Staging Area Logic
    hasOpenWpsWord() {
       // Helper to check if a file is a Word doc (not PPT/Excel)
       const isWord = (f) => {
          if (!f || !f.name) return false;
          const n = f.name.toLowerCase();
          return n.endsWith('.doc') || n.endsWith('.docx') || n.endsWith('.wps');
       };
       return isWord(this.activeFileLeft) || (this.splitMode && isWord(this.activeFileRight));
    },
    showAssociationDropZone() {
       // "When and only when right (active loop) has open WPS... show association"
       // We use hasOpenWpsWord (Left or Right) as proxy for "Open WPS Document"
       return this.fileLinkDrag.active && this.hasOpenWpsWord;
    },
    // Staging Area Visibility:
    // 1. If files exist in staging -> Resident.
    // 2. If Dragging AND Association Zone is NOT shown -> Show Staging Drop.
    // 3. User Requirement: "In absence of open WPS... position should be a staging area".
    showStagingArea() {
       // 1. User explicitly collapsed - respect their choice
       if (this.stagingManuallyCollapsed) return false;

       // 2. Explicitly pinned by user via sidebar button
       if (this.stagingPinned) return true;

       // 3. If staging area has files, show it (resident behavior)
       if (this.stagingFiles && this.stagingFiles.length > 0) return true;

       // 4. If dragging AND Association not overriding (Auto-expand)
       if (this.showAssociationDropZone) return false;

       return this.fileLinkDrag.active;
    },
    stagedFileIds() {
       return (this.stagingFiles || []).map(f => f.id);
    }
    ,
    ocrHasSelection() {
      const s = this.ocrSel
      return Math.abs(s.x2 - s.x1) >= 6 && Math.abs(s.y2 - s.y1) >= 6
    },
    ocrSelectionStyle() {
      const s = this.ocrSel
      const left = Math.min(s.x1, s.x2)
      const top = Math.min(s.y1, s.y2)
      const w = Math.abs(s.x2 - s.x1)
      const h = Math.abs(s.y2 - s.y1)
      return {
        left: `${left}px`,
        top: `${top}px`,
        width: `${w}px`,
        height: `${h}px`
      }
    }
    ,
    ocrFrameImgStyle() {
      const v = this.ocrFrameView
      if (!v) return {}
      const dx = Number(v.dx)
      const dy = Number(v.dy)
      const vw = Number(v.vw)
      const vh = Number(v.vh)
      const scale = Number(v.scale)
      if (!Number.isFinite(dx) || !Number.isFinite(dy) || !Number.isFinite(vw) || !Number.isFinite(vh) || !Number.isFinite(scale) || scale <= 0) {
        // 兜底：不提供 style，让图片按 inset:0 全屏显示，避免“网页消失但看不到截图底图”
        return {}
      }
      const dw = vw * scale
      const dh = vh * scale
      return {
        left: `${dx}px`,
        top: `${dy}px`,
        width: `${dw}px`,
        height: `${dh}px`
      }
    }
    ,
    toolsList() {
      return WORKBENCH_TOOLS
    }
    ,
    isDesktopApp() {
      try {
        return host.ocr
      } catch (e) {
        return false
      }
    },
    visibleFileActions() {
      // 显示更多操作，因为现在有单独一行了
      return this.FILE_TREE_QUICK_ACTIONS
    },
    moreFileActions() {
      return []
    }
  },
  beforeUnmount() {
    // 多实例守卫：只清掉指向自己的活跃指针；返回上一个本页实例时由其 onShow 重新接管
    if (typeof window !== 'undefined' && window.__checkbaActiveOverviewVm === this) {
      window.__checkbaActiveOverviewVm = null
    }
    // 菜单栏命令订阅必须摘掉，理由同下面的广场订阅（多实例）
    this.unregisterMenuCommands()
    // 后台任务状态轮询清理
    if (this.convStatusPollTimer) { clearInterval(this.convStatusPollTimer); this.convStatusPollTimer = null }
    this.stopCollabPolling()
    // 广场变更订阅必须摘掉：本页是 navigateTo 打开的，页面栈里会存在多个实例，
    // 不退订就每回来一次多一份订阅，同一个事件被处理 N 次（剪贴板那次事故的同款）
    if (this._onMarketChanged) {
      uni.$off('awd:market-changed', this._onMarketChanged)
      uni.$off('awd:market-changed-from-sidebar', this._onMarketChanged)
      this._onMarketChanged = null
    }
    // IDE 化聚焦刷新监听清理（本实例自己加的，直接摘）
    if (typeof window !== 'undefined' && this._localFocusRefresh) {
      window.removeEventListener('focus', this._localFocusRefresh)
      this._localFocusRefresh = null
    }
    if (typeof window !== 'undefined' && this._ideKeymapHandler) {
      window.removeEventListener('keydown', this._ideKeymapHandler, true)
      this._ideKeymapHandler = null
    }
    clearTimeout(this._libreSpareTimer)
    this.teardownResponsiveListener()
    // Epic #43: 解绑 ⌘⇧O 嵌入式编辑器监听
    // 清理轮询定时器
    if (this.fileInfoPollingIntervals) {
      Object.values(this.fileInfoPollingIntervals).forEach(intervalId => {
        if (intervalId) clearInterval(intervalId)
      })
    }
    // 清理拖拽监听
    try {
      this.stopResize()
    } catch (e) {
      // ignore
    }

    // 清理剪贴板监听
    this.unbindClipboardListener()

    // OCR 全局监听清理（防止残留导致无法点击/拖拽）
    try {
      this.unbindOcrGlobalListeners()
    } catch (e) {
      // ignore
    }
    // Desktop：解绑网核标记监听
    try {
      if (this._desktopWebMarkUnsub) this._desktopWebMarkUnsub()
    } catch (e) {
      // ignore
    }
    this._desktopWebMarkUnsub = null

    // Desktop：解绑 window.open(id='renderer') 消费监听
    try {
      if (this._desktopRendererOpenUnsub) this._desktopRendererOpenUnsub()
    } catch (e) {
      // ignore
    }
    this._desktopRendererOpenUnsub = null

    // Desktop：解绑内部链接打开监听
    try {
      if (this._desktopOpenInternalUnsub) this._desktopOpenInternalUnsub()
    } catch (e) {
      // ignore
    }
    this._desktopOpenInternalUnsub = null

    // WPS iframe：内部链接 postMessage 监听清理
    try {
      if (this._wpsInternalMsgHandler && typeof window !== 'undefined') {
        window.removeEventListener('message', this._wpsInternalMsgHandler)
      }
    } catch (e) {
      // ignore
    }
    this._wpsInternalMsgHandler = null
    try {
      // 只删除自己安装的处理器：无条件 delete 会把栈下方旧实例还在用的处理器一并删掉
      // （A→B 再返回后 A 的 WPS 内链失效）；A 的接管在 onShow 里完成
      if (typeof window !== 'undefined' && this._wpsInternalLinkFn && window.__checkbaHandleInternalLink === this._wpsInternalLinkFn) {
        delete window.__checkbaHandleInternalLink
      }
    } catch (e) {
      // ignore
    }
    this._wpsInternalLinkFn = null

    // Desktop：解绑 OCR 选区结果监听
    try {
      if (this._desktopOcrSelectionUnsub) this._desktopOcrSelectionUnsub()
    } catch (e) {
      // ignore
    }
    this._desktopOcrSelectionUnsub = null
    try {
      if (this._desktopOcrSelectionErrUnsub) this._desktopOcrSelectionErrUnsub()
    } catch (e) {
      // ignore
    }
    this._desktopOcrSelectionErrUnsub = null

    // 网核拖拽清理
    try {
      this.stopWebLinkDrag()
    } catch (e) {
      // ignore
    }

    // Stop Activity Tracking
    this.stopActivityTracking()

    // Desktop：本页的浏览器标签随页面一起消失，对应的 BrowserView 必须销毁。
    // BrowserPane 卸载时只 detach（保活，切标签不丢内容），销毁的责任因此落在
    // 「标签真的没了」的两处：closeFile 与这里。漏掉就是主进程里的 view 泄漏。
    this.destroyAllBrowserViews()

    // Cleanup manual event listener removed
  },
  onLoad(query) {
    this.pageEnterTime = Date.now()
    this.loadLicenseMode()
    // 官网链接预热：本页有两处「跳官网」（试用 chip、缓存区满弹窗），都是同步取地址。
    // 不预热的话第一次点击只能拿到兜底站点，国际站用户会被送到没有他账户的站
    loadSiteLinks()
    if (query && query.id) {
      this.projectId = Number(query.id)
      recordProjectVisit(this.projectId) // IDE 化：启动直达/最近项目切换器的数据源
      syncRecentToMenuFetching() // 应用菜单「最近打开」随之更新（静默）
      this.loadProjectInfo()
      this.loadProjectMembers()
      this.checkAdoptConflict()
      // 协作状态：先读本地快照（立刻有结果），再走一次联网检查，之后交给定时器保鲜
      this.fetchCollabState().then(() => {
        if (!this.collabLinked) return
        this.fetchCollabState({ online: true })
        this.startCollabPolling()
      })

      // IDE 化「打开文件」过渡版：稍等页面挂载完成后打开指定文件
      if (query.openFileId) {
        const pendingId = Number(query.openFileId)
        setTimeout(() => this.openPendingLocalFile(pendingId), 600)
      }

      // 概览页的 AI 对话列表点进来时带着 conversationId：把那条历史会话真打开。
      // 右侧 AI 面板默认收起（showAiPanel: false）且 ChatInterface 挂在 v-if 里，
      // 所以先开面板、再等它挂载完调 loadHistoryChat（它要 $refs.chatInterface）——
      // 与上面 openFileId 同一手法，不另造一套时序。
      if (query.conversationId) {
        const pendingConversationId = String(query.conversationId)
        if (!this.showAiPanel) this.toggleAiPanel()
        setTimeout(() => this.loadHistoryChat({ conversationId: pendingConversationId }), 600)
      }

      // Initialize Staging Area (Persistent)
      // We don't await here to avoid blocking page load, but ensuring folder exists is critical
      this.ensureStagingFolder().then(() => {
          this.loadStagingFiles()
      })
    }

    const user = getCurrentUser()
    if (user) {
      this.userDisplayName = user.displayName || user.username
      this.currentUser = user
    }
    // 本地缓存只是首屏兜底：local-mode 免登下 checkba_user 永远为空，头像会
    // 一直停在首字母占位符。照 project-list.vue 的 loadUserInfo 写法补一次真实接口，
    // 失败静默回退本地缓存，不阻塞首屏。
    this.loadRealUserInfo()

    // 面板初始化不能挂在登录态上：桌面免登（PR-A 去登录）后本地存储里没有
    // checkba_user，user 为 null——原先整段包在 if (user) 里，进项目左栏永远停在
    // 占位符（app-e2e J4 抓到：文件树/工具行不渲染，直到手动点一次左栏图标）。
    // CLIENT 角色默认 dd-files 的分支只对浏览器登录态（客户访问码）有意义，保留。
    const savedKey = uni.getStorageSync(`project_${this.projectId}_leftPaneKey`)
    if (savedKey && user && user.role === 'CLIENT') {
        // CLIENT 只有 dd-files 这一个面板，存量值原样用（migrate 会把它映射成
        // files，那对客户是错的——他看不到资源管理器）
        this.leftPaneKey = savedKey
    } else if (savedKey) {
        // 存量值可能指向已经不存在的 key（语音合并前的 easyvoice /
        // meeting-recorder、已下线的 shareholder-meeting、对律师隐藏的 dd-files）。
        // 不映射就会落在一个没有面板分支命中的 key 上：左栏是「加载中…」占位符、
        // rail 上一个高亮的按钮都没有，看上去就是坏了。
        this.leftPaneKey = migrateLeftPaneKey(savedKey)
    } else if (user && user.role === 'CLIENT') {
        this.leftPaneKey = 'dd-files'
    } else {
        this.leftPaneKey = 'files'
    }

    // Restore active tabs for this project/mode
    const savedActiveTabs = uni.getStorageSync(`project_${this.projectId}_activeTabsByMode`)
    if (savedActiveTabs) {
      this.lastActiveIdsByMode = savedActiveTabs
      const mode = this.leftPaneKey || 'files'
      if (savedActiveTabs.left[mode]) this.activeFileIdLeft = savedActiveTabs.left[mode]
      if (savedActiveTabs.right[mode]) this.activeFileIdRight = savedActiveTabs.right[mode]
    }
    // 登录态下启用剪贴板记录（仅记录本应用能感知到的 paste / 复制按钮）
    this.bindClipboardListener()

    this.loadDynamicPlugins() // Fetch dynamic plugins
    this.loadEnabledSkills() // 左栏插件位按 skill 启停过滤（诉讼可视化默认不安装）

    // 广场里装/卸了 skill 之后左栏要立刻跟着变。广场有两个宿主（左栏列表面板、
    // 中栏详情 tab），它们各发一个事件，两个都订上——只订一个的话，从另一个入口
    // 装完 skill，左栏图标要等下次进页面才出现。
    this._onMarketChanged = () => this.loadEnabledSkills()
    uni.$on('awd:market-changed', this._onMarketChanged)
    uni.$on('awd:market-changed-from-sidebar', this._onMarketChanged)
  },
  onShow() {
    // 多实例守卫：本实例重新可见（如从个人中心返回）时接管全局事件与 WPS 内链处理，
    // 否则全局指针仍指向已销毁/被覆盖的后进实例
    if (typeof window !== 'undefined') {
      window.__checkbaActiveOverviewVm = this
      if (this._wpsInternalLinkFn) window.__checkbaHandleInternalLink = this._wpsInternalLinkFn
    }
    // 重新成为活跃实例后确保有预热备胎（mounted 时可能因非活跃被跳过）
    this.scheduleLibreSpare()
    // 菜单栏：重新接管（页面栈里可能有别的实例刚交出去）
    this.registerMenuCommands()

    // 从设置页返回时刷新授权/账户 chip（用户可能刚连接或断开账户）
    this.loadLicenseMode()

    // Sync UI state
    this.isRecording = activityTracker.getRecordingState()

    // Reload members to ensure up-to-date list
    if (this.projectId) {
        this.loadProjectMembers()
    }

    // Start/Resume Activity Tracking
    if (this.projectId && this.project.name) {
         this.startActivityTracking()
    }

    // Desktop：仅在工作区页面展示 BrowserView（否则会“飘”到其它页面）
    // 注意尊重弹窗状态：若返回页面时仍有全屏弹窗打开，保持隐藏
    try {
      if (this.isDesktopApp && host.browser && host.browser.setViewsVisible) {
        host.browser.setViewsVisible({ visible: !this.desktopOverlayActive }).catch(() => {})
      }
    } catch (e) {
      // ignore
    }
  },
  onHide() {
    // 菜单栏：本页被盖住（去了个人中心/设置页）就交出去。实例还活着，
    // onShow 会重新接管——但期间菜单不能继续亮着工作台那一堆条目。
    this.unregisterMenuCommands()
    this.stopActivityTracking()

    // Desktop：离开工作区页面（如去个人中心）必须隐藏 BrowserView
    try {
      if (this.isDesktopApp && host.browser && host.browser.setViewsVisible) {
        host.browser.setViewsVisible({ visible: false }).catch(() => {})
      }
    } catch (e) {
      // ignore
    }
  },
  onUnload() {
    // Replace simple page view log with ActivityTracker stop
    this.stopActivityTracking()

    // 兜底：页面销毁也隐藏
    try {
      if (this.isDesktopApp && host.browser && host.browser.setViewsVisible) {
        host.browser.setViewsVisible({ visible: false }).catch(() => {})
      }
    } catch (e) {
      // ignore
    }
  },
  mounted() {
    // 多实例守卫：本页经 navigateTo 反复进入时页面栈里会有多个存活实例，每个都在
    // mounted 绑定了全局（ipcRenderer/window 级）监听；全局事件只让最近展示的实例
    // 处理，否则一次事件触发 N 份副作用（与 PR#148 剪贴板重复入库同源）
    if (typeof window !== 'undefined') window.__checkbaActiveOverviewVm = this
    this.setupResponsiveListener()
    // IDE 化：窗口重新聚焦时刷新文件树——外部改动（Finder 增删改）都发生在
    // 用户切出去的时候，后端 watcher 已把数据库对齐，聚焦拉一次即可见。
    // 多实例守卫：只让活跃实例刷新（页面栈多实例地雷，PR#148/#151 模式）
    if (typeof window !== 'undefined') {
      this._localFocusRefresh = () => {
        if (!this.isActiveOverviewInstance()) return
        if (this.$refs.fileTree && this.$refs.fileTree.loadFiles) {
          this.$refs.fileTree.loadFiles()
        }
        this.checkAdoptConflict() // 顺带刷新顶栏工作状态点（同一次 /status）
        if (this.collabLinked) this.fetchCollabState({ online: true }) // 切出去期间同事可能交了新稿
      }
      window.addEventListener('focus', this._localFocusRefresh)
      // IDE 化键位：Cmd+P 快速打开 / Cmd+W 关闭当前标签（活跃实例守卫；
      // 焦点在 LOWA webview 内时按键被 webview 吞掉收不到，属已知边界）
      this._ideKeymapHandler = (e) => {
        if (!this.isActiveOverviewInstance()) return
        if (!(e.metaKey || e.ctrlKey) || e.shiftKey || e.altKey) return
        const k = (e.key || '').toLowerCase()
        if (k === 'p') {
          e.preventDefault()
          e.stopPropagation()
          this.quickOpenVisible = !this.quickOpenVisible
        } else if (k === 'w') {
          e.preventDefault()
          e.stopPropagation()
          if (this.quickOpenVisible) {
            this.quickOpenVisible = false
          } else if (this.activeFileIdLeft) {
            this.closeFile(this.activeFileIdLeft, 'left')
          } else if (this.splitMode && this.activeFileIdRight) {
            this.closeFile(this.activeFileIdRight, 'right')
          }
        }
      }
      window.addEventListener('keydown', this._ideKeymapHandler, true)
    }
    // 预热备胎：延迟建（避开项目打开期资源竞争），首开 Office 文档免冷启动
    this.scheduleLibreSpare()
    // 后台任务状态轮询：AI 面板打开时每 15s 刷一次会话状态（驱动历史列表状态点
    // 与入口角标；quiet 模式不弹错误 toast、不动 loading 态）
    this.convStatusPollTimer = setInterval(() => {
      if (this.showAiPanel && this.projectId && this.isActiveOverviewInstance()) {
        this.fetchChatHistory(true)
      }
    }, 15000)
    // Desktop：网页选中“加入网核收藏”（右键菜单触发）
    try {
      if (this.isDesktopApp && host.browser && host.browser.onWebMark) {
        if (!this._desktopWebMarkUnsub) {
          this._desktopWebMarkUnsub = host.browser.onWebMark(async (payload) => {
            // 页面栈里每个实例都订阅了本事件：只让活跃实例入库，否则一次“加入网核收藏”
            // 会按实例数重复 POST，且旧实例还会把收藏写进它自己的 projectId
            if (!this.isActiveOverviewInstance()) return
            try {
              const text = payload && payload.text ? String(payload.text).trim() : ''
              const url = payload && payload.url ? String(payload.url).trim() : ''
              const title = payload && payload.title ? String(payload.title).trim() : ''
              const imageBase64 = payload && payload.imageDataUrl ? String(payload.imageDataUrl) : ''
              if (!text || !this.projectId) return
              const pid = typeof this.projectId === 'string' ? Number(this.projectId) : this.projectId
              await createProjectFavorite(pid, {
                title: title || (url ? (() => { try { return new URL(url).host } catch (e) { return this.$t('workbench.webMark') } })() : this.$t('workbench.webMark')),
                sourceUrl: url,
                content: text,
                imageBase64: imageBase64 || ''
              })
              // 立即刷新网核中心面板（如果可见）
              if (this.$refs.favoritesPanel && typeof this.$refs.favoritesPanel.refresh === 'function') {
                this.$refs.favoritesPanel.refresh()
              }
              uni.showToast({ title: this.$t('workbench.webMarkFavAdded'), icon: 'success' })
            } catch (e) {
              console.error('保存网核收藏失败:', e)
              uni.showToast({ title: e.message || this.$t('workbench.saveFailed'), icon: 'none' })
            }
          })
        }
      }
    } catch (e) {
      // ignore
    }

    // Desktop：消费主进程拦截的 window.open（id='renderer'）。
    // 主进程会把渲染层所有 window.open(http/https) 拦截为
    // checkba:browser-open-new-tab { id: 'renderer' }，此前无人消费，
    // 导致桌面端"文件下载/查看/收藏开网页"等 window.open 全部静默失效。
    try {
      if (this.isDesktopApp && host.browser && host.browser.onOpenNewTab) {
        if (!this._desktopRendererOpenUnsub) {
          this._desktopRendererOpenUnsub = host.browser.onOpenNewTab((data) => {
            if (!this.isActiveOverviewInstance()) return
            try {
              if (!data || data.id !== 'renderer' || !data.url) return
              this.openBrowserTab(String(data.url))
            } catch (e) {
              // ignore
            }
          })
        }
      }
    } catch (e) {
      // ignore
    }

    // Manual binding removed (reverted to native modifier)

    // Epic #43 Track B / #79: 宿主提供内嵌编辑器时，它就是 Office 文档的默认
    // 编辑器（内联，零配置）。桌面壳恒为真；Web 服务器版取决于 /zetaoffice/
    // 有没有随站点部署，所以要探一次——没部署就退回原来的预览路径，而不是
    // 挂一个永远起不来的编辑器（dev server 与只部署了 h5 的站点都属此列）。
    try {
      const zo = host.zetaoffice
      if (!zo || typeof zo.getEditor !== 'function') {
        this.libreOfficePreferred = false
      } else if (isDesktopHost()) {
        // 桌面壳：引擎随包分发，同步置位——mounted 里后续逻辑与自动打开的文件
        // 都依赖它，异步落位会让首个文档按「无编辑器」渲染。
        this.libreOfficePreferred = true
      } else {
        // Web 服务器版：探一次 /zetaoffice/ 是否真部署了，回来再置位。
        zo.isAvailable().then((ok) => { this.libreOfficePreferred = !!ok }).catch(() => {})
      }
    } catch (e) {
      this.libreOfficePreferred = false
    }

    // （⌘⇧O 实验覆盖层已移除：内联编辑器就是产品默认，覆盖层只会在文档上
    // 凭空盖一条开发工具栏——用户报告。）

    // Desktop：拦截 WPS 中点击 “checkba://...” 的内部链接
    try {
      if (this.isDesktopApp && host.app && host.app.onOpenInternal) {
        if (!this._desktopOpenInternalUnsub) {
          this._desktopOpenInternalUnsub = host.app.onOpenInternal((payload) => {
            if (!this.isActiveOverviewInstance()) return
            try {
              const raw0 = payload && payload.url ? String(payload.url) : ''
              if (!raw0 || !raw0.startsWith('checkba:')) return
              const raw = raw0.replace(/^checkba:\/*/i, 'checkba://')
              const q = raw.includes('?') ? raw.split('?')[1] : ''
              const params = new URLSearchParams(q)

              // 1) webfav：定位收藏卡片
              if (raw.startsWith('checkba://webfav')) {
                const favId = params.get('id')
                if (!favId) return
                this.showToolsPanel = true
                this.activeToolKey = 'favorites'
                this.$nextTick(() => {
                  try {
                    const panel = this.$refs.favoritesPanel
                    if (panel && typeof panel.focusFavorite === 'function') {
                      panel.focusFavorite(Number(favId))
                    }
                  } catch (e) {
                    // ignore
                  }
                })
                return
              }

              // 2) filelink：打开关联文件（多文件先弹窗）
              if (raw.startsWith(this.INTERNAL_LINK_SCHEMES.fileLink)) {
                const linkKey = params.get('k') || ''
                if (!linkKey || !this.projectId) return
                const pid = typeof this.projectId === 'string' ? Number(this.projectId) : this.projectId
                getDocFileLink(pid, linkKey)
                  .then((resp) => {
                    const files = resp && resp.files ? resp.files : (resp && resp.data && resp.data.files ? resp.data.files : [])
                    const list = Array.isArray(files) ? files : []
                    if (list.length <= 0) {
                      uni.showToast({ title: this.$t('workbench.linkedFileMissing'), icon: 'none' })
                      return
                    }
                    if (list.length === 1) {
                      this.openFileLinkTarget(list[0].id, this.focusedPane || 'left')
                      return
                    }
                    this.fileLinkPicker = { visible: true, side: this.focusedPane === 'right' && this.splitMode ? 'right' : 'left', files: list, linkKey }
                  })
                  .catch((e) => {
                    uni.showToast({ title: (e && e.message) ? e.message : this.$t('workbench.openFailed'), icon: 'none' })
                  })
                return
              }
            } catch (e) {
              // ignore
            }
          })
        }
      }
    } catch (e) {
      // ignore
    }

    // WPS 官方 onHyperLinkOpen：在 iframe 内无法直接打开 checkba:，
    // 通过 postMessage 把内部链接交给宿主页面处理
    try {
      // 给 WPS SDK onHyperLinkOpen 直接调用：避免 window.open/postMessage 的不确定性
      if (typeof window !== 'undefined') {
        // 记住自己的处理器引用：onShow 重新接管 / beforeUnmount 按引用删除都靠它
        this._wpsInternalLinkFn = (url) => {
          try {
            const raw0 = url ? String(url) : ''
            if (!raw0) return
            let raw = raw0
            // 兼容：WPS 内部包装链接（https://checkba-internal... ?u=checkba://xxx）
            try {
              if (this.WPS_INTERNAL_HTTP_LINK_BASE && raw.startsWith(this.WPS_INTERNAL_HTTP_LINK_BASE)) {
                const q0 = raw.includes('?') ? raw.split('?')[1] : ''
                const p0 = new URLSearchParams(q0)
                const inner = p0.get('u') ? decodeURIComponent(String(p0.get('u'))) : ''
                if (inner) raw = inner
              }
            } catch (e) {}
            if (!raw || !raw.startsWith('checkba:')) return
            // 统一 normalize：兼容 checkba:/xxx 与 checkba://xxx
            raw = raw.replace(/^checkba:\/*/i, 'checkba://')
            const q = raw.includes('?') ? raw.split('?')[1] : ''
            const params = new URLSearchParams(q)

            // eslint-disable-next-line no-console
            console.log('[Host internalLink] received:', raw)

            if (raw.startsWith('checkba://webfav')) {
              const favId = params.get('id')
              if (!favId) return
              this.showToolsPanel = true
              this.activeToolKey = 'favorites'
              this.$nextTick(() => {
                try {
                  const panel = this.$refs.favoritesPanel
                  if (panel && typeof panel.focusFavorite === 'function') panel.focusFavorite(Number(favId))
                } catch (e) {}
              })
              return
            }

            if (raw.startsWith(this.INTERNAL_LINK_SCHEMES.fileLink)) {
              const linkKey = params.get('k') || ''
              if (!linkKey || !this.projectId) return
              const pid = typeof this.projectId === 'string' ? Number(this.projectId) : this.projectId
              getDocFileLink(pid, linkKey)
                .then((resp) => {
                  const files = resp && resp.files ? resp.files : (resp && resp.data && resp.data.files ? resp.data.files : [])
                  const list = Array.isArray(files) ? files : []
                  if (list.length <= 0) {
                    uni.showToast({ title: this.$t('workbench.linkedFileMissing'), icon: 'none' })
                    return
                  }
                  if (list.length === 1) {
                    this.openFileLinkTarget(list[0].id, this.focusedPane || 'left')
                    return
                  }
                  this.fileLinkPicker = { visible: true, side: this.focusedPane === 'right' && this.splitMode ? 'right' : 'left', files: list, linkKey }
                })
                .catch((e) => {
                  uni.showToast({ title: (e && e.message) ? e.message : this.$t('workbench.openFailed'), icon: 'none' })
                })
              return
            }
          } catch (e) {
            // ignore
          }
        }
        window.__checkbaHandleInternalLink = this._wpsInternalLinkFn
        window.addEventListener('message', this._wpsInternalMsgHandler)
      }
    } catch (e) {
      // ignore
    }

    // Desktop：截图框选失败时给用户提示（避免“松手啥也没有”）
    try {
      if (this.isDesktopApp && host.ocr && host.ocr.onSelectionError) {
        if (!this._desktopOcrSelectionErrUnsub) {
          this._desktopOcrSelectionErrUnsub = host.ocr.onSelectionError((data) => {
            // 只让活跃实例弹一次 toast，避免页面栈里 N 个实例连弹 N 次
            if (!this.isActiveOverviewInstance()) return
            const msg = data && data.message ? String(data.message) : this.$t('workbench.screenshotFailed')
            uni.showToast({ title: msg, icon: 'none' })
          })
        }
      }
    } catch (e) {
      // ignore
    }
  },
  watch: {
    // IDE 化窗口标题：「文件名 — 项目名 — AI WorkDeck」（Electron 窗口标题跟随 document.title）
    'project.name'() { this.updateWindowTitle() },
    activeFileIdLeft() { this.updateWindowTitle(); this.pushMenuState() },
    // 菜单栏的勾选/置灰跟着这些走。编辑器与 AI 面板内部的状态走 @menu-state
    // 事件（见对应组件），这里只管工作台自己的。桥那边有浅比较+去抖，
    // 这些 watcher 只管「叫一声」，不必自己节流。
    'project.id'() { this.pushMenuState() },
    activeFileIdRight() { this.pushMenuState() },
    sidebarCollapsed() { this.pushMenuState() },
    showToolsPanel() { this.pushMenuState() },
    showAiPanel() { this.pushMenuState() },
    splitMode() { this.pushMenuState() },
    activeToolKey() { this.pushMenuState() },
    leftPaneKey() { this.pushMenuState() },
    isRecording() { this.pushMenuState() },
    LEFT_SIDEBAR_PLUGINS() { this.pushMenuState() },
    // 桌面端统一守卫：弹窗/蒙层打开 → 隐藏 BrowserView；全部关闭 → 恢复并重同步 bounds
    desktopOverlayActive(open) {
      if (!this.isDesktopApp) return
      try {
        const api = host.browser
        if (!api || !api.setViewsVisible) return
        api.setViewsVisible({ visible: !open }).catch(() => {})
        if (!open) {
          // 恢复后强制一次布局同步，防止蒙层期间的布局变化（如底部面板开合）留下过期 bounds
          this.$nextTick(() => this.triggerWorkbenchResize())
        }
      } catch (e) {
        // ignore
      }
    },
    // 内嵌 LibreOffice 多实例保活：激活的 Office 标签记入 LRU（超上限触发
    // 淘汰），并把 AI 指令路由指针同步到当前活动实例（活跃实例指针，同
    // PR#151 WPS 编辑器模式）。
    activeFileLeft(f) { this.onActiveOfficeFileChanged('left', f); this.touchWebKeepAlive('left', f) },
    activeFileRight(f) { this.onActiveOfficeFileChanged('right', f); this.touchWebKeepAlive('right', f) },
    focusedPane() { this.syncLibreExecutor() },
    // 关闭 tab 后清掉文件已不在左列表的过继备胎条目（closeFile 已 flush）
    'leftFiles.length'() { this.pruneClosedLibreSpares() },
  },
  methods: {
    // Options API 模板拿不到裸导入函数，包一层 method 才能在模板里当 getInitial(...) 调用
    getInitial,
    // 顶栏头像：本地缓存（getCurrentUser，utils/auth.js）只是首屏兜底，
    // local-mode 免登下 checkba_user 永远为空。照 project-list.vue:477-490
    // loadUserInfo 的写法补一次真实接口，成功后与本地缓存合并（接口字段更全），
    // 失败静默回退本地缓存那份，绝不阻塞首屏、绝不 toast 报错。
    async loadRealUserInfo() {
      try {
        const res = await getCurrentUserApi()
        if (res && res.code === 0 && res.data) {
          this.currentUser = { ...this.currentUser, ...res.data }
          this.userDisplayName = this.currentUser.displayName || this.currentUser.username || this.userDisplayName
        }
      } catch (e) {
        // 拿不到就用本地缓存那份，不拦路
        console.error('获取用户信息失败:', e)
      }
    },
    // 授权标识：桌面端查授权模式与账户连接状态
    // （已连接账户 → 「已连接账户」chip；否则 mode=trial → 「试用版」chip）
    async loadLicenseMode() {
      if (!isDesktopHost()) return
      try {
        // 授权状态里已带 accountConnected（后端组合口径），不必再打一次账户端点
        const status = await getLicenseStatus()
        this.licenseMode = (status && status.mode) || ''
        // 旧后端没有该字段：按未连接处理，与改动前查不到账户状态时的行为一致
        this.accountConnected = !!(status && status.accountConnected)
        // 宽限预警：后端只在真的需要提醒时才下发这两个字段（存量试用倒计时 /
        // 账户离线复验剩 ≤7 天），不需要提醒时形状与过去一模一样。
        this.graceKind = (status && status.graceKind) || ''
        this.graceDays = Number((status && status.daysRemaining) || 0)
      } catch (e) {
        // 服务器模式/旧后端没有该端点：静默忽略
        this.accountConnected = false
        this.graceKind = ''
      }
    },
    /** 宽限弹窗的主按钮：联网复验那条去账户设置，其余去官网。 */
    graceAction() {
      if (this.graceKind === 'offlineReverify') {
        this.showTrialInfo = false
        this.goToAccountPanel()
        return
      }
      this.openUpgradeSite()
    },
    // chip 点击直达设置「账户与用量」面板。设置在工作台里是中栏标签，
    // 深链等价物就是 openSettingsTab 的 nav 参数。
    goToAccountPanel() {
      this.openSettingsTab({ nav: 'account' })
    },
    openUpgradeSite() {
      this.showTrialInfo = false
      openExternalUrl(siteBaseUrl())
    },
    // Phase 1 外置的方法组（纯搬移，this 即页面实例）
    ...panelSwitchingMethods,
    ...menuCommandsMethods,
    ...agentClientActionMethods,
    ...librePoolMethods,
    // Phase 2 外置的方法组
    ...stagingAreaMethods,
    ...tabDragSplitMethods,
    ...fileOpenTabsMethods,
    // Phase 3a 外置的方法组
    ...clipboardBridgeMethods,
    // Phase 3b 外置的方法组
    ...ocrActionMethods,
    // Phase 3c 外置的方法组
    ...ocrCaptureMethods,
    // 右键「这份文件的历史」：切到版本面板并只显示这份文件的版本
    onFileHistory(file) {
      this.versionFileFilter = { fileId: file.id, name: file.name }
      if (this.leftPaneKey !== 'version') this.toggleLeftPane('version')
    },
    // IDE 化窗口标题
    updateWindowTitle() {
      if (typeof document === 'undefined') return
      try {
        const active = (this.leftFiles || []).find((f) => f.id === this.activeFileIdLeft)
        const parts = []
        if (active && active.name) parts.push(active.name)
        if (this.project && this.project.name) parts.push(this.project.name)
        parts.push('AI WorkDeck')
        document.title = parts.join(' — ')
      } catch (e) { /* 标题失败不影响功能 */ }
    },
    // 最近项目切换器：展开时按本地最近顺序解析项目名（排除当前项目）
    async toggleProjectSwitcher() {
      this.projectSwitcherOpen = !this.projectSwitcherOpen
      if (!this.projectSwitcherOpen) return
      await this.loadSwitcherProjects()
    },
    // 拉取失败时不能显示"没有其他最近项目"——那是把请求失败静默吞成了真实的
    // 空态。改成独立的失败态 + 可点重试；这里单独抽出方法是因为菜单里的重试
    // 项要能重新拉取而不去动 projectSwitcherOpen（它已经是 true）。
    async loadSwitcherProjects() {
      this.switcherLoadFailed = false
      try {
        const projects = await getMyProjects()
        const list = Array.isArray(projects) ? projects : (projects && projects.data) || []
        const byId = new Map(list.map((p) => [Number(p.id), p]))
        this.switcherProjects = getRecentProjectIds()
          .filter((id) => id !== Number(this.projectId))
          .map((id) => byId.get(id))
          .filter(Boolean)
          .slice(0, 8)
      } catch (e) {
        console.warn('[project-overview] failed to load recent projects for switcher', e)
        this.switcherProjects = []
        this.switcherLoadFailed = true
      }
    },
    switchToProject(p) {
      this.projectSwitcherOpen = false
      if (!p || Number(p.id) === Number(this.projectId)) return
      // reLaunch：切项目不叠页面栈（多实例地雷）
      uni.reLaunch({ url: `/pages/project-overview/project-overview?id=${p.id}` })
    },
    // 工作台里的「项目概览」= 打开左栏的 home 面板。
    // 顶栏切换器里那一项与 rail 第一个按钮是同一个动作。
    //
    // 沿革：最早这里 reLaunch 到 pages/project-home 独立页（等于把整个工作台
    // 拆掉换成一页只读卷轴）；2026-08 改成中栏标签；2026-08-19 再改成左栏面板——
    // rail 上的按钮点了应该开左栏，这是 rail 其余每一项的语义，概览不该例外。
    goProjectHome() {
      this.projectSwitcherOpen = false
      this.toggleLeftPane('home')
    },
    /** 概览标签里点某条历史对话：已经在工作台里了，就地切会话，不跳页 */
    openConversationInPanel(conversationId) {
      if (!conversationId) return
      const wasOpen = this.showAiPanel
      if (!wasOpen) this.toggleAiPanel()
      // loadHistoryChat 要 $refs.chatInterface；面板刚打开时它还没挂上，
      // 等一拍（与 onLoad 里消费 query.conversationId 那处同一个口径）
      this.$nextTick(() => {
        if (wasOpen) this.loadHistoryChat({ conversationId })
        else setTimeout(() => this.loadHistoryChat({ conversationId }), 600)
      })
    },
    goAllProjects() {
      this.projectSwitcherOpen = false
      // 工作台参与的跳转一律 reLaunch：navigateTo 会把工作台留在页面栈里，
      // 从列表页再进另一个项目就出现两个存活的工作台实例（全局监听多实例地雷）
      uni.reLaunch({ url: '/pages/project-list/project-list' })
    },
    // Cmd+P 快速打开面板选中文件
    onQuickOpenFile(file) {
      this.quickOpenVisible = false
      if (file) this.openFile(file)
    },
    /**
     * 命令面板选中一条：关面板，然后走和菜单栏**完全同一条**派发链
     * （appMenuBridge.handleAction）——两个入口共用一份 when 判定与一份实现。
     */
    async onCommandPaletteRun(item) {
      this.commandPaletteVisible = false
      if (!item || !item.id) return
      const { runCommandById } = await import('@/utils/appMenuBridge.js')
      await runCommandById(item.id)
    },
    // 文件树右键「在访达中显示」：后端解析物理路径（localRoot 感知），桌面壳高亮
    async onRevealFile(file) {
      if (!file) return
      const shellApi = host.fs && host.fs.showItemInFolder
      if (!shellApi) return
      try {
        let path = null
        if (file.isFolder) {
          // 文件夹没有独立物理路径记录：用项目根 + 无法精确时退回项目根
          const r = await getProjectLocalPath(this.projectId)
          path = r && r.data && r.data.path
        } else {
          const r = await getFileLocalPath(file.id)
          path = r && r.data && r.data.path
          if (!(r && r.data && r.data.exists)) {
            uni.showToast({ title: this.$t('workbench.fileNotOnDisk'), icon: 'none' })
            return
          }
        }
        if (path) await host.fs.showItemInFolder(path)
      } catch (e) {
        uni.showToast({ title: (e && e.message) || this.$t('workbench.revealFailed'), icon: 'none' })
      }
    },
    // 「有一次采纳等待处理」固定条的入口：切到版本面板，AdoptConflictDialog 会随
    // 面板的 /status 自动弹出（它本来就是这么起来的，含崩溃后重开的场景）。
    goHandleAdoptConflict() {
      if (this.leftPaneKey !== 'version') this.toggleLeftPane('version')
    },
    // 进页面时问一次「有没有停在待裁决的采纳」：版本面板可能整个会话都没被打开过
    // （比如上次崩在裁决窗口里、这次进来直接停在资源管理器），那样就没有任何东西
    // 会去拉 /status，律师看不到任何提示。面板打开后由它的 adopt-conflict 事件接管。
    async checkAdoptConflict() {
      if (!this.projectId) return
      try {
        const res = await getVersionStatus(this.projectId)
        const d = (res && res.data) || {}
        this.adoptConflictPending = !!(d.adoptConflict || d.cloudConflict || d.sessionEndConflict)
        // IDE 化顶栏工作状态点（同一次 /status，不多打接口）
        this.versionWorkStatus = {
          enabled: !!d.enabled,
          working: !!d.working,
          changedCount: Number(d.changedCount || 0),
          onDraft: d.onDraft || null,
        }
      } catch (e) {
        console.warn('[Version] 读取采纳状态失败', e)
      }
    },

    // ==================== 协作（团队案件库） ====================
    // cloudStatus 是**不联网**的本地快照：它读的 origin/master 只在有人显式取回后
    // 才前移。进页面拉一次它（便宜、立刻有结果），随后再走一次联网的 checkCloud，
    // 之后靠定时器保鲜——不刷新的话，同事交了新稿本机可能几小时都显示「和大家的稿
    // 一致」，那是比不显示更糟的假绿灯。
    async fetchCollabState({ online = false } = {}) {
      if (!this.projectId) return
      try {
        const res = online ? await checkCloud(this.projectId) : await getCloudStatus(this.projectId)
        this.collabCloud = (res && res.data) || null
      } catch (e) {
        console.warn('[Collab] 读取协作状态失败', e)
      }
    },
    // 定时保鲜。三道守卫缺一不可：只在活跃实例上跑（页面栈多实例地雷，PR#148/#151）、
    // 窗口不可见时不打网络、没放进案件库就整个不起定时器。
    startCollabPolling() {
      if (this._collabPollTimer) return
      this._collabPollTimer = setInterval(() => {
        if (!this.isActiveOverviewInstance()) return
        if (typeof document !== 'undefined' && document.hidden) return
        if (!this.collabLinked) return
        this.fetchCollabState({ online: true })
      }, 120000)
    },
    stopCollabPolling() {
      if (this._collabPollTimer) { clearInterval(this._collabPollTimer); this._collabPollTimer = null }
    },
    openCollab(tab) {
      this.collabInitialTab = typeof tab === 'string' ? tab : 'casefile'
      this.collabDialogVisible = true
    },
    // 抽屉里做完动作：页面自己的状态、版本面板那份状态、以及「有没有等着做选择的
    // 文件」三处都要跟着走一遍。
    //
    // 补起定时器这一步不能省：onLoad 那次遇到还没放进案件库的案卷会直接 return，
    // 定时器根本没起过；律师随后在抽屉里点「放进团队案件库」，chip 就此定格在这一刻，
    // 同事再交多少稿也不会刷新——假绿灯比不显示更糟（口径见 fetchCollabState 的注释）。
    // startCollabPolling 自带 _collabPollTimer 幂等守卫，每次动作后都调一遍是安全的。
    onCollabChanged() {
      this.fetchCollabState().then(() => {
        if (this.collabLinked) this.startCollabPolling()
      })
      this.checkAdoptConflict()
      this.collabRefreshToken += 1
    },
    // 交稿/取回撞上了两边都改过同一处：把人送到裁决现场（版本面板，三选一弹窗随
    // 面板的 /status 自动弹出）。
    onCollabConflict() {
      this.checkAdoptConflict()
      this.collabRefreshToken += 1
      this.goHandleAdoptConflict()
    },

    // EasyVoice Integration
    async handleEasyVoiceDocRequest(callback) {
      console.log('[EasyVoice] Requesting doc text...')
      this.easyVoiceImportCallback = callback
      this.filePickerAllowFolder = false
      this.showFilePicker = true
    },

    // ==================== Desensitize Handlers ====================
    handleDesensitizeSelectFile(callback) {
        this.desensitizeFileSelectCallback = callback
        this.filePickerAllowFolder = false
        this.showFilePicker = true
    },
    handleDesensitizeActiveFile(callback) {
        const active = this.focusedPane === 'left' ? this.activeFileLeft : this.activeFileRight
        // If no file focused but files open, pick the first one from left
        const target = active || (this.leftFiles.length > 0 ? this.activeFileLeft : null)
        callback(target)
    },
    handleDesensitizeSuccess(file) {
        if (!file) return
        
        // Refresh file tree to show the new file
        if (this.$refs.fileTree && this.$refs.fileTree.refresh) {
            this.$refs.fileTree.refresh()
        }
        
        // Open the file directly
        // The backend returns the full ProjectFile object now
        this.openFile(file)
    },

    async handleFilePickerConfirm(file) {
        if (!file || !file.id) return

        // Desensitize File Picker callback
        if (this.desensitizeFileSelectCallback) {
            this.desensitizeFileSelectCallback(file)
            this.desensitizeFileSelectCallback = null
            return
        }

        // 诉讼可视化的材料范围选择：只取名字，不导入内容——AI 自己会去读。
        //
        // 文件与文件夹必须分开说。以前一律拼成《名字》（fileId=N），模型读起来就是
        // 一份文档，于是拿去调 extract_file_text，撞上「这是个文件夹」的错误就卡死了。
        // 文件夹要明说是文件夹、并把下一步（先列目录再逐份读）交代清楚。
        if (this.litigationScopeCallback) {
            const isFolder = file.fileType === 'folder' || file.isFolder
            this.litigationScopeCallback({
                label: isFolder ? this.$t('workbench.folderScopeLabel', { name: file.name }) : file.name,
                isFolder,
                description: isFolder
                    ? `文件夹「${file.name}」（folderId=${file.id}）及其下全部材料。`
                      + `先对 ${file.id} 调 extract_file_text 拿到该文件夹的文件清单，再逐份通读。`
                    : `《${file.name}》（fileId=${file.id}）`
            })
            this.litigationScopeCallback = null
            return
        }

        try {
            uni.showLoading({ title: this.$t('workbench.importing') })

            // Check if it is a text file -> use content directly if available/loaded?
            // Better to call backend always for consistency, or check file extension.
            // Backend endpoint handles doc/docx decoding which is the main point.

            const res = await getFileText(file.id)
            if (res && res.code === 0) {
                const text = res.data
                if (this.easyVoiceImportCallback) {
                    this.easyVoiceImportCallback(text)
                }
                // P0: 打开文件到右侧标签页
                
                // Extract fileType from name if missing
                let fileType = file.fileType
                if (!fileType && file.name) {
                  const ext = file.name.split('.').pop()
                  if (ext && ext !== file.name) {
                    fileType = ext.toLowerCase()
                  }
                }
                
                this.openFile({
                  id: file.id,
                  wpsFileId: file.wpsFileId,
                  name: file.name,
                  fileType: fileType,
                  filePath: file.filePath
                })
                uni.showToast({ title: this.$t('workbench.importSuccess'), icon: 'success' })
            } else {
                throw new Error(res.message || this.$t('workbench.importFailed'))
            }
        } catch (e) {
            console.error('Failed to import doc text', e)
            uni.showToast({ title: this.$t('workbench.importFailedWithMsg', { msg: e.message }), icon: 'none' })
        } finally {
            uni.hideLoading()
            this.easyVoiceImportCallback = null
        }
    },

    // TTS Karaoke Highlighting（#79：经 LibreOffice 执行器 find+select 实现）
    async handleTtsHighlight(sentence) {
      console.log('[TTS Highlight] Highlighting:', sentence?.substring(0, 50) + '...')

      if (!this.libreOfficeActive || !this.libreOfficeExecutor) {
        console.warn('[TTS] No embedded editor available for highlighting')
        return
      }
      try {
        const keyword = String(sentence || '').trim()
        if (!keyword) return
        const found = await this.libreOfficeExecutor.executeCommand('find_text_locations', { keyword })
        const first = found && Array.isArray(found.matches) && found.matches[0]
        const anchor = first && first.anchorId
        if (!anchor) {
          console.warn('[TTS] Sentence not found in document')
          return
        }
        await this.libreOfficeExecutor.executeCommand('set_selection', { anchor })
      } catch (e) {
        console.warn('[TTS] Failed to highlight:', e && e.message)
      }
    },

    async handleTtsClearHighlight() {
      console.log('[TTS] Clearing highlight')

      if (!this.libreOfficeActive || !this.libreOfficeExecutor) return
      try {
        await this.libreOfficeExecutor.executeCommand('collapse_selection', { to: 'start' })
      } catch (e) {
        // 无选区时忽略
      }
    },

    async removeMember(member) {
      if (!this.projectId) return
      if (!this.canRemoveMember(member)) {
         uni.showToast({ title: this.$t('workbench.noPermissionRemoveMember'), icon: 'none' })
         return
      }
      uni.showModal({
        title: this.$t('workbench.removeMemberTitle'),
        content: this.$t('workbench.removeMemberConfirm', { name: member.displayName }),
        success: async (res) => {
          if (res.confirm) {
            try {
              await removeProjectMember(this.projectId, member.userId)
              uni.showToast({ title: this.$t('workbench.removed'), icon: 'success' })
              this.loadProjectMembers()
            } catch (e) {
              console.error(e)
              uni.showToast({ title: e.message || this.$t('workbench.removeFailed'), icon: 'none' })
            }
          }
        }
      })
    },
    canRemoveMember(targetMember) {
       if (!this.currentUser || !targetMember) return false
       if (this.currentUser.id === targetMember.userId) return false // Cannot remove self

       // Find my role in the project
       const myMember = this.projectMembers.find(m => m.userId === this.currentUser.id)
       if (!myMember) return false // Not a member?

       const myRole = myMember.role
       const targetRole = targetMember.role

       if (myRole === 'ADMIN') return true
       if (myRole === 'PARTICIPANT') {
           return targetRole === 'READ_ONLY' || targetRole === 'CLIENT'
       }
       return false
    },
    isDdRequest(file) {
      return file && file.type === 'dd-request'
    },
    handleOpenDdRequest(req) {
      // 切换到尽调清单模式以便查看
      if (this.leftPaneKey !== 'dd-files') {
        this.toggleLeftPane('dd-files')
      }
      const file = {
        id: 'dd-' + req.id,
        requestId: req.id,
        name: req.name,
        type: 'dd-request',
        fileType: 'dd',
        isFolder: false
      }
      this.openFile(file)
    },
    /**
     * 左栏面板要往 AI 面板发一句 kick-off prompt 时的统一前置。
     *
     * 此前三个面板（股东大会核查 / 会议纪要 / 诉讼可视化）各自写
     * `if (!$refs.chatInterface) toast('AI 面板未就绪')` —— 而 AI 面板默认是收起的，
     * 于是律师在面板里点「开始核查」，绝大多数情况下拿到的就是这句 toast，
     * 而且它不告诉人该怎么办。面板收着就先替他打开：
     *   1. showAiPanel 为 false 时走既有的 toggleAiPanel()（它顺带刷 AI 上下文 +
     *      拉历史，不能绕过去直接改标志位）；
     *   2. 有界轮询等 ChatInterface 挂上并暴露 sendExternalPrompt（$refs 要等一次
     *      渲染，组件内部还要初始化，$nextTick 一拍不够——实测这条路径上
     *      openConversationInPanel 用的是 600ms 的固定等待）；
     *   3. 真等不到才 toast 兜底。
     *
     * 上限 ~3s（30 × 100ms）：比固定 600ms 宽容，又不会在真出问题时把人挂住。
     */
    async resolveChatInterface() {
      if (!this.showAiPanel) this.toggleAiPanel()
      for (let i = 0; i < 30; i++) {
        await this.$nextTick()
        const chat = this.$refs.chatInterface
        if (chat && chat.sendExternalPrompt) return chat
        await new Promise((r) => setTimeout(r, 100))
      }
      uni.showToast({ title: this.$t('workbench.aiPanelNotReady'), icon: 'none' })
      return null
    },
    // 股东大会核查「开始核查」：把 kick-off prompt 交给 AI 面板以 AGENT 模式发送，
    // 并把返回的会话 ID 绑定回核查会话（面板据此展示 RUNNING 状态）
    async handleShareholderMeetingStart({ check, prompt }) {
      const chat = await this.resolveChatInterface()
      if (!chat) return
      const conversationId = await chat.sendExternalPrompt(prompt)
      if (conversationId && check && check.id) {
        try {
          await bindShareholderMeetingConversation(check.id, conversationId, 'RUNNING')
        } catch (e) {
          console.error('绑定核查会话失败', e)
        }
      }
    },

    // 会议录音「生成纪要」：prompt 由服务端拼好（触发词「会议纪要」开头才命中 skill 注入），
    // 这里只负责以 AGENT 模式发出去——与股东大会核查同一条路。
    async handleMeetingMinutesStart({ prompt }) {
      const chat = await this.resolveChatInterface()
      if (!chat) return
      await chat.sendExternalPrompt(prompt)
    },

    // ==================== 诉讼可视化面板 ====================

    // 出图那句话由服务端拼好（触发词必须原样在正文里才命中 skill 注入），
    // 这里只负责以 AGENT 模式发出去——与股东大会核查同一条路。
    async handleLitigationStart({ prompt }) {
      const chat = await this.resolveChatInterface()
      if (!chat) return
      await chat.sendExternalPrompt(prompt)
    },

    async handleLitigationOpenFile({ fileId }) {
      if (!fileId) return
      try {
        const pid = typeof this.projectId === 'string' ? Number(this.projectId) : this.projectId
        const file = await getFileDetail(pid, fileId)
        if (file && file.id) this.openFile(file)
      } catch (e) {
        uni.showToast({ title: this.$t('workbench.openFailed'), icon: 'none' })
      }
    },

    // 材料范围选择：复用页面上那个 FilePickerDialog（与脱敏面板同一套回调约定）。
    // 只有这一个调用方开 allowFolder——律师给材料的自然单位就是卷宗文件夹。
    handleLitigationScopeSelect(callback) {
      this.litigationScopeCallback = callback
      this.filePickerAllowFolder = true
      this.showFilePicker = true
    },

    isTabVisible(file) {
      if (!file) return false
      // dd-request 或者 fileType 为 dd 的属于尽调清单类标签
      const isDd = file.type === 'dd-request' || file.fileType === 'dd'
      if (isDd) {
        return this.leftPaneKey === 'dd-files'
      }
      // 其他文件标签（资源管理器打开的文件、浏览器标签等）仅在资源管理器模式下显示
      // 插件标签也只在插件模式下显示
      const isPlugin = file.fileType === 'plugin'
      if (isPlugin) {
        return this.leftPaneKey === file.id // Assuming plugin ID is its key
      }
      // 版本对比标签（修订稿 / 文本降级两种）：唯一入口是版本面板里的「和上一版
      // 对比」，所以必须在 version 面板下可见——否则点开的标签被 v-show 藏死，
      // 编辑区显示空闲态，功能等于不存在。同时也在资源管理器面板下保持可见：
      // 它展示的是项目文档的衍生视图，律师切回文件树不该让对比凭空消失。
      if (file.tabType === 'version-compare' || file.tabType === 'version-text-diff') {
        return this.leftPaneKey === 'version' || this.leftPaneKey === 'files'
      }
      // 插件广场详情 tab：与左栏模式无关，常显（VS Code 扩展详情页语义）
      if (file.tabType === 'market-detail') {
        return true
      }
      // 系统设置 tab：同理常显。它的入口是顶栏头像下拉，不属于任何左栏模式，
      // 被 v-show 藏死的话点了菜单什么也不会发生。
      if (file.tabType === 'admin-settings') {
        return true
      }
      // 个人中心 tab：同理常显，入口同样是顶栏头像下拉。
      if (file.tabType === 'user-profile') {
        return true
      }
      // 普通文件在资源管理器、搜索或语音模式下都可见（语音合成要在编辑器里
      // 取正文，看不见文档就没法用）。
      // 诉讼可视化面板也要放行：图廊的「打开」是那个面板唯一的出图入口，
      // 不放行的话点了之后标签被 v-show 藏死、编辑区显示空闲态，功能等于不存在
      // （与上面版本对比标签同一类问题）。
      return this.leftPaneKey === 'files' || this.leftPaneKey === 'search'
        || this.leftPaneKey === 'voice' || this.leftPaneKey === 'litigation-visual'
    },
    startRenameProject() {
      this.renameProjectName = this.project.name || ''
      this.isRenamingProject = true
    },
    startRenameFromSwitcher() {
      this.projectSwitcherOpen = false
      this.startRenameProject()
    },
    async confirmRenameProject() {
      if (!this.renameProjectName || !this.renameProjectName.trim()) {
        uni.showToast({ title: this.$t('workbench.projectNameEmpty'), icon: 'none' })
        return
      }
      try {
        await renameProject(this.projectId, this.renameProjectName.trim())
        this.project.name = this.renameProjectName.trim()
        this.isRenamingProject = false
        uni.showToast({ title: this.$t('workbench.renameSuccess'), icon: 'success' })
      } catch (e) {
        console.error('重命名失败', e)
        uni.showToast({ title: this.$t('workbench.renameFailed'), icon: 'none' })
      }
    },
    cancelRenameProject() {
      this.isRenamingProject = false
      this.renameProjectName = ''
    },
    toggleFileMoreMenu() {
      this.showFileMoreMenu = !this.showFileMoreMenu
    },
    handleLogout() {
      try {
         clearSession()
      } catch (e) {}
      uni.reLaunch({ url: '/pages/login/login' })
    },
    onFileTreeQuickAction(actionKey) {
      const tree = this.$refs.fileTree
      if (!tree) return
      if (actionKey === 'newFolder' && typeof tree.showCreateFolderDialog === 'function') {
        tree.showCreateFolderDialog()
        return
      }
      if (actionKey === 'newFile' && typeof tree.handleCreateWord === 'function') {
        tree.handleCreateWord()
        return
      }
      if (actionKey === 'upload' && typeof tree.handleUploadFile === 'function') {
        tree.handleUploadFile()
        return
      }
      if (actionKey === 'recycleBin') {
        if (this.fileBatchMode) {
           if (typeof tree.openBatchAction === 'function') {
             tree.openBatchAction('delete')
           }
        } else {
           if (typeof tree.openRecycleBin === 'function') {
             tree.openRecycleBin()
           }
        }
        return
      }

      if (actionKey === 'download') {
         if (typeof tree.openBatchAction === 'function') {
             tree.openBatchAction('download')
         }
         return
      }

      if (actionKey === 'copy') {
         if (typeof tree.openBatchAction === 'function') {
             tree.openBatchAction('copy')
         }
         return
      }

      if (actionKey === 'sort' && typeof tree.toggleSortOrder === 'function') {
        tree.toggleSortOrder()
        return
      }
    },
    wrapWpsInternalLink(innerUrl) {
      const inner = String(innerUrl || '').trim()
      if (!inner) return ''
      const base = this.WPS_INTERNAL_HTTP_LINK_BASE || ''
      if (!base) return inner
      // 写入到文档里的超链接必须是 http/https，才能稳定触发 onHyperLinkOpen（对照官方 demo）
      return `${base}?u=${encodeURIComponent(inner)}`
    },
    // === 文件拖拽到文档选区建立关联（超链接）===
    // #79 债已还：原 WPS 实例能力（选区轮询 + setHyperlinkAtRange）现由 LibreOffice
    // 执行器原语实现（get/set_selection_hyperlink，见 createWpsSelectionFileLink）。
    onFileLinkDragStart(file) {
      if (!file || !file.id) return
      this.fileLinkDrag.active = true
      this.fileLinkDrag.file = file
      this.fileLinkDrag.hoverSide = null
      console.log('onFileLinkDragStart:', file)
    },

    // bindNativeDropEvents 已移除，逻辑迁移至 FileLinkDropZone 组件

    onFileLinkDragEnd() {
      console.log('onFileLinkDragEnd')
      this.fileLinkDrag.active = false
      this.fileLinkDrag.file = null
      this.fileLinkDrag.hoverSide = null
    },

    async onFileLinkZoneDrop({ side }) {
      console.log('onFileLinkZoneDrop triggered:', side)
      let file = this.fileLinkDrag.file

      // 这里的 file 应该是从 state 中获取的，因为 drop 主要是为了触发 action，
      // 如果需要从 DataTransfer 恢复 (跨组件丢失 state)，组件内部其实拿不到 DataTransfer 数据 (dropzone 一般只暴露 event)，
      // 但因为我们是同页面拖拽，state 应该是保持的。

      if (!file || !file.id) {
        console.warn('onFileLinkZoneDrop: no file in state')
        // 尝试兜底？组件可以传回更多信息吗？
        // 暂时先这样，因为 FileTree 就在同一个页面，state 不会丢
      }

      // 先关闭浮窗
      this.onFileLinkDragEnd()

      if (!file || !file.id) return
      await this.createWpsSelectionFileLink(side, file)
    },
    closeFileLinkPicker() {
      this.fileLinkPicker.visible = false
      this.fileLinkPicker.files = []
      this.fileLinkPicker.linkKey = ''
    },
    async createWpsSelectionFileLink(side, file) {
      // #79 债已还：经 LibreOffice 执行器实现（get_selection_hyperlink 复用已有
      // linkKey + set_selection_hyperlink 写入），后端 DocFileLink 契约不变。
      console.log('createWpsSelectionFileLink start:', { side, fileId: file && file.id })
      if (!this.libreOfficeActive || !this.libreOfficeExecutor) {
        uni.showToast({ title: this.$t('workbench.openDocFirst'), icon: 'none' })
        return
      }
      const exec = (action, params) => this.libreOfficeExecutor.executeCommand(action, params)

      // 1) 读选区（顺带取选区上已有的超链接，用于复用 linkKey）
      let selText = ''
      let existingUrl = ''
      try {
        const cur = await exec('get_selection_hyperlink', {})
        if (cur && cur.success) {
          selText = String(cur.text || '').trim()
          existingUrl = cur.url ? String(cur.url) : ''
        }
      } catch (e) {
        console.warn('get_selection_hyperlink failed:', e)
      }
      if (!selText) {
        uni.showToast({ title: this.$t('workbench.highlightTextFirst'), icon: 'none' })
        return
      }

      // 2) 生成/复用 linkKey：选区已带内部链接时从中解析（裸 checkba:// 或包装 https 均兼容）
      let linkKey = ''
      try {
        let raw = existingUrl
        if (raw && this.WPS_INTERNAL_HTTP_LINK_BASE && raw.startsWith(this.WPS_INTERNAL_HTTP_LINK_BASE)) {
          const q0 = raw.includes('?') ? raw.split('?')[1] : ''
          const p0 = new URLSearchParams(q0)
          raw = p0.get('u') ? decodeURIComponent(String(p0.get('u'))) : ''
        }
        if (raw && raw.startsWith(this.INTERNAL_LINK_SCHEMES.fileLink)) {
          const q = raw.includes('?') ? raw.split('?')[1] : ''
          linkKey = new URLSearchParams(q).get('k') || ''
        }
      } catch (e) {
        // ignore
      }
      if (!linkKey) {
        linkKey = `lk_${Date.now()}_${Math.random().toString(36).slice(2, 8)}`
        // 与 WPS 时代同款“包装后的 https 链接”：点击经编辑器 open-url 事件回宿主解包，
        // 文档导出到真实 Word 时也仍是合法链接
        const inner = `${this.INTERNAL_LINK_SCHEMES.fileLink}?k=${encodeURIComponent(linkKey)}&projectId=${encodeURIComponent(String(this.projectId || ''))}`
        const url = this.wrapWpsInternalLink(inner)
        const r = await exec('set_selection_hyperlink', { url })
        if (!r || !r.success) {
          console.error('设置超链接失败:', r && r.message)
          uni.showToast({ title: this.$t('workbench.setHyperlinkFailed'), icon: 'none' })
          return
        }
      }

      // 3) 入库：按 fileId 关联（文件移动/重命名不影响打开）
      try {
        const pid = typeof this.projectId === 'string' ? Number(this.projectId) : this.projectId
        const doc = side === 'right' ? this.activeFileRight : this.activeFileLeft
        const docWpsFileId = doc && this.isEditorOpenableFile(doc) ? (doc.wpsFileId || '') : ''
        if (!docWpsFileId) throw new Error(this.$t('workbench.docNotReady'))
        const payload = await createDocFileLink(pid, {
          linkKey,
          docWpsFileId,
          anchorText: selText || '',
          // LibreOffice 路径没有整数偏移（§0.2 锚点语义）；后端字段可空
          rangeStart: null,
          rangeEnd: null,
          fileIds: [Number(file.id)]
        })
        if (payload && payload.linkKey) linkKey = payload.linkKey
        uni.showToast({ title: this.$t('workbench.linkCreated'), icon: 'success' })
      } catch (e) {
        uni.showToast({ title: e.message || this.$t('workbench.linkFailed'), icon: 'none' })
      }
    },

    // === Staging Area Methods ===
    // 文件暂存区方法组已外置 → ./stagingArea.js（Phase 2）

    async openFileLinkTarget(fileId, sideOverride = null) {
      const fid = Number(fileId)
      if (!fid || !this.projectId) return
      const side = sideOverride || this.fileLinkPicker.side || 'left'
      this.closeFileLinkPicker()
      try {
        const pid = typeof this.projectId === 'string' ? Number(this.projectId) : this.projectId
        const file = await getFileDetail(pid, fid)
        if (!file) throw new Error(this.$t('workbench.fileMissing'))
        const old = this.focusedPane
        this.focusedPane = side === 'right' && this.splitMode ? 'right' : 'left'
        this.openFile(file)
        this.focusedPane = old
      } catch (e) {
        uni.showToast({ title: e.message || this.$t('workbench.openFailed'), icon: 'none' })
      }
    },
    // OCR 采集与浮层生命周期方法组已外置（Phase 3c） → ./ocrCapture.js
    getActiveWebTab() {
      // 优先取当前聚焦窗格的激活 tab
      const focused = this.focusedPane === 'right' ? this.activeFileRight : this.activeFileLeft
      if (focused && focused.tabType === 'web') return focused
      // 再取另一个窗格的激活 tab
      const other = this.focusedPane === 'right' ? this.activeFileLeft : this.activeFileRight
      if (other && other.tabType === 'web') return other
      // 兜底：找任意一个 web tab（优先右侧）
      const anyRight = Array.isArray(this.rightFiles) ? this.rightFiles.find(t => t && t.tabType === 'web') : null
      if (anyRight) return anyRight
      const anyLeft = Array.isArray(this.leftFiles) ? this.leftFiles.find(t => t && t.tabType === 'web') : null
      return anyLeft || null
    },
    async onTabsPlusClick(pane) {
      // 规则：
      // - 如果当前激活的是网页 Tab：新建网页 Tab
      // - 否则：复制当前文件（后端 batchCopy），并打开新文件
      const active = pane === 'right' ? this.activeFileRight : this.activeFileLeft
      if (active && this.isBrowserTab(active)) {
        this.openBrowserTab('https://www.baidu.com', pane)
        return
      }
      if (!active || !active.id || !this.projectId) {
        this.openBrowserTab('https://www.baidu.com', pane)
        return
      }
      try {
        const targetParentId = active.parentId != null ? active.parentId : null
        const res = await batchCopyFiles(this.projectId, [active.id], targetParentId)
        const created = (res && res.data && Array.isArray(res.data.files)) ? res.data.files : (res && res.files) || []
        const createdFile = Array.isArray(created) ? created[0] : null
        if (this.$refs.fileTree && this.$refs.fileTree.loadFiles) {
          this.$refs.fileTree.loadFiles()
        }
        if (createdFile) {
          // 强制在当前 pane 打开：临时聚焦 pane
          const oldFocus = this.focusedPane
          this.focusedPane = pane === 'right' && this.splitMode ? 'right' : 'left'
          this.openFile(createdFile)
          this.focusedPane = oldFocus
        } else {
          uni.showToast({ title: this.$t('workbench.copyFailedNoNewFile'), icon: 'none' })
        }
      } catch (e) {
        console.error('复制文件失败', e)
        uni.showToast({ title: e.message || this.$t('workbench.copyFailed'), icon: 'none' })
      }
    },
    switchToolTab(key) {
      this.activeToolKey = key
      // 如果用户切到剪贴板，且之前有 pending，则立刻刷新
      this.$nextTick(() => {
        if (key === 'clipboard') {
          this.triggerClipboardRefresh()
        }
      })
    },
    isActiveOverviewInstance() {
      // 多实例守卫：navigateTo 进入本页不销毁旧实例，页面栈里每个实例都持有一份
      // 全局监听。全局事件只让“最近展示的实例”处理（onShow/mounted 时登记，
      // beforeUnmount 注销）。指针缺失时放行，避免误杀单实例场景。
      if (typeof window === 'undefined') return true
      return !window.__checkbaActiveOverviewVm || window.__checkbaActiveOverviewVm === this
    },
    setupResponsiveListener() {
      if (typeof window === 'undefined') return
      if (this._windowResizeHandler) return
      this._windowResizeHandler = () => this.handleResponsiveResize()
      window.addEventListener('resize', this._windowResizeHandler, { passive: true })
      this.$nextTick(() => this.handleResponsiveResize())
    },
    teardownResponsiveListener() {
      if (typeof window === 'undefined') return
      if (this._windowResizeHandler) {
        window.removeEventListener('resize', this._windowResizeHandler)
        this._windowResizeHandler = null
      }
    },
    handleResponsiveResize() {
      if (typeof window === 'undefined') return
      const viewportWidth = window.innerWidth || 1920
      const compact = viewportWidth <= 1360
      this.isCompactLayout = compact
      // 按 Cursor 体验：不在窄屏时强行限制面板宽度（遮挡就遮挡），只切换样式密度
    },
    // 左栏面板切换方法组已外置 → ./panelSwitching.js（Phase 1）
    // OCR 采集与浮层生命周期方法组已外置（Phase 3c） → ./ocrCapture.js
    // 剪贴板捕获桥方法组已外置 → ./clipboardBridge.js（Phase 3a）
    // OCR 采集与浮层生命周期方法组已外置（Phase 3c） → ./ocrCapture.js
    isBrowserTab(tab) {
      return !!tab && tab.tabType === 'web'
    },
    // 销毁某个浏览器标签对应的 BrowserView。BrowserPane 卸载只 detach（保活），
    // 所以「标签关闭」必须显式销毁，否则主进程里的 view 一直留着。
    destroyBrowserView(tabId) {
      try {
        const api = host.browser
        if (api && api.destroy && tabId) {
          Promise.resolve(api.destroy({ id: String(tabId) })).catch(() => {})
        }
      } catch (e) {
        // ignore
      }
    },
    destroyAllBrowserViews() {
      const all = [...(this.leftFiles || []), ...(this.rightFiles || [])]
      for (const t of all) {
        if (this.isBrowserTab(t)) this.destroyBrowserView(t.id)
      }
    },
    // 网页标签保活之后，后台那些标签的 BrowserPane 也活着（站点自己 302、SPA 换路由
    // 都会报上来），所以这两个回调必须认 tabId——按"当前激活的那个标签"收，会把
    // 后台标签的地址写到用户正看着的标签上。
    findBrowserTab(pane, tabId) {
      const list = pane === 'left' ? this.leftFiles : this.rightFiles
      const tab = (list || []).find(f => String(f.id) === String(tabId))
      return (tab && this.isBrowserTab(tab)) ? tab : null
    },
    isActiveBrowserTab(pane, tabId) {
      const activeId = pane === 'left' ? this.activeFileIdLeft : this.activeFileIdRight
      return String(activeId) === String(tabId)
    },
    onBrowserUrlChange(pane, tabId, url) {
      const tab = this.findBrowserTab(pane, tabId)
      if (!tab) return
      tab.url = url
      // 标签名称：尽量短（host）
      try {
        const u = new URL(url)
        tab.name = u.host || url
      } catch (e) {
        tab.name = url
      }
      this.$forceUpdate()

      // 工作记录只跟"用户正在看的那一页"：后台标签自己跳走不该被记成浏览行为
      if (!this.isActiveBrowserTab(pane, tabId)) return
      // Track URL Session (flush previous, start new)
      const meta = this.project && this.project.name ? `Project: ${this.project.name}` : ''
      activityTracker.trackActivePage('OPEN_URL', 0, url, meta)
    },
    onBrowserTitleChange(pane, tabId, title) {
      const active = this.findBrowserTab(pane, tabId)
      if (!active) return
      const t = String(title || '').trim()
      if (!t) return

      // Update session meta with title?
      // trackActivePage will flush and restart. This might be noisy if title changes often.
      // But user requested "record url and web title".
      // If we don't restart, we can't update the log meta.
      // Let's check if title is significantly different or just loaded.

      const url = active.url || ''
      const meta = (this.project && this.project.name ? `Project: ${this.project.name}. ` : '') + `Title: ${t}`
      // 同 onBrowserUrlChange：后台标签换标题不算浏览行为
      if (url && this.isActiveBrowserTab(pane, tabId)) {
          // Restart session to capture title in the new segment
          activityTracker.trackActivePage('OPEN_URL', 0, url, meta)
      }

      // 避免过长：保留前 18 字符
      active.name = t.length > 18 ? (t.slice(0, 18) + '…') : t
      this.$forceUpdate()
    },
    // 激活的网页标签变化：记进保活 LRU（超上限的尾巴直接出池 = 组件卸载 = iframe 收掉，
    // 不像编辑器那样需要先落盘，网页没有我们负责保存的状态）。顺带清掉已关标签的残留记账。
    touchWebKeepAlive(pane, file) {
      if (!this.webKeepAliveEnabled || !file || !this.isBrowserTab(file)) return
      const key = pane + ':' + file.id
      const stillOpen = (k) => {
        const sep = k.indexOf(':')
        const list = k.slice(0, sep) === 'right' ? this.rightFiles : this.leftFiles
        const id = k.slice(sep + 1)
        return (list || []).some(f => this.isBrowserTab(f) && String(f.id) === id)
      }
      this.webKeepAliveKeys = [key]
        .concat(this.webKeepAliveKeys.filter(k => k !== key && stillOpen(k)))
        .slice(0, WEB_KEEPALIVE_MAX)
    },
    openBrowserTab(url = 'https://www.baidu.com', pane = null) {
      // 默认在当前聚焦窗格打开；未分屏则左侧
      const targetPane = pane ? (pane === 'right' && this.splitMode ? 'right' : 'left') : (this.splitMode ? this.focusedPane : 'left')
      const list = targetPane === 'left' ? this.leftFiles : this.rightFiles
      const idProp = targetPane === 'left' ? 'activeFileIdLeft' : 'activeFileIdRight'

      const id = `web_${Date.now()}_${Math.random().toString(36).slice(2, 8)}`
      let name = this.$t('workbench.browser')
      try {
        const u = new URL(url)
        name = u.host || this.$t('workbench.browser')
      } catch (e) {
        // ignore
      }
      list.push({
        id,
        tabType: 'web',
        name,
        url
      })
      this[idProp] = id
      this.focusedPane = targetPane
      this.$nextTick(() => this.triggerWorkbenchResize())
    },

    // ===== OCR 摘录：屏幕共享抓帧 -> 框选 -> 后端 OCR（阿里云） =====
    // OCR 采集与浮层生命周期方法组已外置（Phase 3c） → ./ocrCapture.js

    // OCR 帧处理与动作方法组已外置（Phase 3b） → ./ocrActions.js
    startActivityTracking() {
        if (activityTracker.getRecordingState()) {
             activityTracker.start()
             this.isRecording = true
        }
    },

    stopActivityTracking() {
        activityTracker.stop()
        activityTracker.setRecording(false) // Force stop recording state
        this.isRecording = false
    },

    toggleRecording() {
        const newState = activityTracker.toggleRecording()
        this.isRecording = newState

        // Custom Toast
        this.recordingToastMessage = newState ? this.$t('workbench.recordingStarted') : this.$t('workbench.recordingStopped')
        this.showRecordingToast = true
        if (this.recordingToastTimer) clearTimeout(this.recordingToastTimer)
        this.recordingToastTimer = setTimeout(() => {
            this.showRecordingToast = false
        }, 2000)

        if (newState) {
             // uni.showToast({ title: '开始录制工作', icon: 'none' }) // Replaced
             // If we have an active file/tab, start tracking it immediately
             const pane = this.focusedPane
             const file = pane === 'left' ? this.activeFileLeft : this.activeFileRight
             if (file) {
                 const meta = this.project && this.project.name ? `Project: ${this.project.name}` : ''
                 if (this.isBrowserTab(file)) {
                     const url = file.url || ''
                     const title = file.name || ''
                     const fullMeta = meta + (title ? `. Title: ${title}` : '')
                     activityTracker.trackActivePage('OPEN_URL', 0, url, fullMeta)
                 } else {
                     activityTracker.trackActivePage('OPEN_FILE', file.id, file.name, meta)
                 }
             }
        } else {
             // uni.showToast({ title: '已停止录制', icon: 'none' }) // Replaced
        }
    },

    // OCR 采集与浮层生命周期方法组已外置（Phase 3c） → ./ocrCapture.js

    // OCR 帧处理与动作方法组已外置（Phase 3b） → ./ocrActions.js
    async insertClipboardAndCopy(text, options = {}) {
      const t = (text || '').trim()
      if (!t) return
      // 记录到剪贴板历史（best-effort）
      if (options.saveToHistory) {
        try {
          await saveClipboardText(t)
          if (this.$refs.clipboardPanel && typeof this.$refs.clipboardPanel.refresh === 'function') {
            this.$refs.clipboardPanel.refresh()
          }
        } catch (e) {
          // ignore
        }
      }
      // #ifdef H5
      try {
        if (navigator.clipboard && navigator.clipboard.writeText) {
          await navigator.clipboard.writeText(t)
        } else {
          uni.setClipboardData({ data: t })
        }
      } catch (e) {
        uni.setClipboardData({ data: t })
      }
      // #endif
      // #ifndef H5
      uni.setClipboardData({ data: t })
      // #endif
    },

    // copyOcrText / insertOcrToWps 已被快捷命令条替代

    async insertPlainTextToWps(payload) {
      // 兼容旧的 pure string 调用
      let text = ''
      let type = 'TEXT'
      let content = ''

      if (typeof payload === 'string') {
        text = payload
        content = payload
      } else if (payload && typeof payload === 'object') {
        type = payload.type || 'TEXT'
        content = payload.content || ''
        text = (type === 'TEXT') ? content : ''
      }

      if (type === 'TEXT') {
         const t = (text || '').trim()
         if (!t) return
         if (!this.libreOfficeActive || !this.libreOfficeExecutor) {
           uni.showToast({ title: this.$t('workbench.openDocFirst'), icon: 'none' })
           return
         }
         try {
           await this.libreOfficeExecutor.executeCommand('insert_at_cursor', { text: t })
           uni.showToast({ title: this.$t('workbench.insertedToDoc'), icon: 'success' })
         } catch (e) {
           console.error(e)
           uni.showToast({ title: this.$t('workbench.insertFailed'), icon: 'none' })
         }
      } else if (type === 'IMAGE') {
         // #79 债已还：经执行器 insert_image 在光标处插入（data URL → UNO 图形对象）
         if (!content) return
         if (!this.libreOfficeActive || !this.libreOfficeExecutor) {
           uni.showToast({ title: this.$t('workbench.openDocFirst'), icon: 'none' })
           return
         }
         try {
           // 剪贴板/收藏夹面板给的是图片 HTTP URL（/api/...?token=...），
           // insert_image 只认 data URL/base64，先拉字节转 data URL
           let dataUrl = content
           if (!/^data:/i.test(dataUrl)) {
             const resp = await fetch(dataUrl)
             if (!resp.ok) throw new Error(this.$t('workbench.imageDownloadFailed'))
             const blob = await resp.blob()
             dataUrl = await new Promise((resolve, reject) => {
               const reader = new FileReader()
               reader.onload = () => resolve(reader.result)
               reader.onerror = () => reject(new Error(this.$t('workbench.imageReadFailed')))
               reader.readAsDataURL(blob)
             })
           }
           const r = await this.libreOfficeExecutor.executeCommand('insert_image', { dataUrl })
           if (!r || !r.success) throw new Error((r && r.message) || this.$t('workbench.insertImageFailed'))
           uni.showToast({ title: this.$t('workbench.imageInserted'), icon: 'success' })
         } catch (e) {
           console.error(e)
           uni.showToast({ title: e.message || this.$t('workbench.insertImageFailed'), icon: 'none' })
         }
      }
    },

    // OCR 帧处理与动作方法组已外置（Phase 3b） → ./ocrActions.js
    startWebLinkDrag(payload) {
      const p = payload || {}
      this.webLinkDrag = {
        active: true,
        x: p.x ?? (this.ocrLastPointer?.x || 0),
        y: p.y ?? (this.ocrLastPointer?.y || 0),
        favoriteId: p.favoriteId || null,
        imageDataUrl: p.imageDataUrl || '',
        sourceUrl: p.sourceUrl || '',
        title: p.title || ''
      }
      // 鼠标移动跟随
      this._webLinkMoveHandler = (ev) => {
        const p2 = this.getOcrPoint(ev)
        this.webLinkDrag.x = p2.x + 10
        this.webLinkDrag.y = p2.y + 10
      }
      this._webLinkUpHandler = (ev) => {
        const p2 = this.getOcrPoint(ev)
        this.handleWebLinkDrop(p2.x, p2.y)
      }
      this._webLinkKeydownHandler = (ev) => {
        if (ev && ev.key === 'Escape') {
          ev.preventDefault()
          this.stopWebLinkDrag()
        }
      }
      // #ifdef H5
      document.addEventListener('mousemove', this._webLinkMoveHandler, true)
      document.addEventListener('mouseup', this._webLinkUpHandler, true)
      window.addEventListener('keydown', this._webLinkKeydownHandler, true)
      // #endif
    },

    stopWebLinkDrag() {
      if (this._webLinkMoveHandler) document.removeEventListener('mousemove', this._webLinkMoveHandler, true)
      if (this._webLinkUpHandler) document.removeEventListener('mouseup', this._webLinkUpHandler, true)
      if (this._webLinkKeydownHandler) window.removeEventListener('keydown', this._webLinkKeydownHandler, true)
      this._webLinkMoveHandler = null
      this._webLinkUpHandler = null
      this._webLinkKeydownHandler = null
      this.webLinkDrag.active = false
    },

    async handleWebLinkDrop(x, y) {
      // #79 债已还：落点命中内置 LibreOffice 编辑器时，经执行器
      // insert_link_with_bookmark 在光标处插入网核标记（书签+内部超链接）。
      const hitEditor = () => {
        if (typeof document === 'undefined') return false
        const els = document.querySelectorAll('.libre-editor-wrapper')
        for (const el of els) {
          const r = el.getBoundingClientRect()
          if (x >= r.left && x <= r.right && y >= r.top && y <= r.bottom) return true
        }
        return false
      }
      try {
        if (!hitEditor()) {
          uni.showToast({ title: this.$t('workbench.dragToDocArea'), icon: 'none' })
          return
        }
        if (!this.libreOfficeActive || !this.libreOfficeExecutor) {
          uni.showToast({ title: this.$t('workbench.openDocFirst'), icon: 'none' })
          return
        }
        const favId = this.webLinkDrag.favoriteId
        const host = this.webLinkDrag.sourceUrl ? (() => { try { return new URL(this.webLinkDrag.sourceUrl).host } catch (e) { return this.$t('workbench.webMark') } })() : this.$t('workbench.webMark')
        const ts = new Date().toLocaleString()
        const text = this.$t('workbench.webMarkEvidence', { host, time: ts })
        const bookmarkName = `WEB_EVID_${favId || Date.now()}`
        const internalUrl = this.wrapWpsInternalLink(`checkba://webfav?id=${encodeURIComponent(String(favId || ''))}&projectId=${encodeURIComponent(String(this.projectId || ''))}`)
        const r = await this.libreOfficeExecutor.executeCommand('insert_link_with_bookmark', { text, bookmarkName, url: internalUrl })
        if (!r || !r.success) throw new Error((r && r.message) || this.$t('workbench.insertFailed'))
        uni.showToast({ title: this.$t('workbench.webMarkInserted'), icon: 'success' })
      } catch (e) {
        console.error('插入网核标记失败:', e)
        uni.showToast({ title: e.message || this.$t('workbench.insertFailed'), icon: 'none' })
      } finally {
        this.stopWebLinkDrag()
      }
    },
    onFileTreeCheckedChange(ids) {
      this.checkedFileIds = Array.isArray(ids) ? ids : []
    },
    toggleFileBatchMode() {
      this.fileBatchMode = !this.fileBatchMode
      if (!this.fileBatchMode) {
        this.checkedFileIds = []
        this.showBatchMenu = false
        if (this.$refs.fileTree && typeof this.$refs.fileTree.clearChecked === 'function') {
          this.$refs.fileTree.clearChecked()
        }
      }
    },
    selectAllFiles() {
       if (this.$refs.fileTree && typeof this.$refs.fileTree.selectAll === 'function') {
         this.$refs.fileTree.selectAll()
       }
    },
    toggleBatchMenu() {
      if (!this.fileBatchMode) return
      if (this.checkedFileCount <= 0) return
      this.showBatchMenu = !this.showBatchMenu
    },
    closeBatchMenu() {
      this.showBatchMenu = false
    },
    onBatchMenuSelect(actionKey) {
      if (this.checkedFileCount <= 0) return
      this.showBatchMenu = false
      if (this.$refs.fileTree && typeof this.$refs.fileTree.openBatchAction === 'function') {
        this.$refs.fileTree.openBatchAction(actionKey)
      }
    },
    // --- 导航与初始化 ---
    async loadProjectInfo() {
      try {
        const data = await getProject(this.projectId)
        if (data) {
          this.project = data
        }
        this.loadProjectMembers() // Load members
      } catch (e) {
        console.error('加载项目详情失败', e)
      }
    },
    async loadProjectMembers() {
        if (!this.projectId) return
        try {
            const res = await getProjectMembers(this.projectId)
            this.projectMembers = res.data || []
        } catch (e) {
            console.error('Failed to load project members', e)
        }
    },
    goBack() {
      uni.navigateBack()
    },
    toggleAvatarMenu() {
      this.avatarMenuOpen = !this.avatarMenuOpen
    },
    goToUserProfile() {
      this.avatarMenuOpen = false
      this.openUserProfileTab()
    },
    /**
     * 中栏开「个人中心」标签（单例；任一窗格已开则激活）。照 openSettingsTab 的形制——
     * 整页跳转会把工作台（标签、编辑器、AI 会话）整个换掉，改个头像/工作记录的代价
     * 是回来重开一遍文件。pages/userprofile 薄壳页仍在，直链与浏览器端走那条。
     */
    openUserProfileTab() {
      const tabId = 'user-profile'
      for (const pane of ['left', 'right']) {
        const list = pane === 'left' ? this.leftFiles : this.rightFiles
        const existing = list.find(f => f.id === tabId)
        if (existing) {
          this[pane === 'left' ? 'activeFileIdLeft' : 'activeFileIdRight'] = existing.id
          this.focusedPane = pane
          this.$nextTick(() => this.triggerWorkbenchResize())
          return
        }
      }
      const targetPane = this.splitMode ? this.focusedPane : 'left'
      const list = targetPane === 'left' ? this.leftFiles : this.rightFiles
      list.push({
        id: tabId,
        tabType: 'user-profile',
        name: this.$t('workbench.profile'),
      })
      this[targetPane === 'left' ? 'activeFileIdLeft' : 'activeFileIdRight'] = tabId
      this.focusedPane = targetPane
      this.$nextTick(() => this.triggerWorkbenchResize())
    },
    /**
     * 系统设置：中栏开标签，不再整页跳转（2026-08-19）。
     * 整页跳转会把工作台（标签、编辑器、AI 会话）整个换掉，改个 API Key 的代价
     * 是回来重开一遍文件——照插件广场详情 tab 那套形制改成标签。
     * pages/admin 薄壳页仍在，直链与浏览器端走那条。
     */
    goToSystemSettings(opts) {
      this.avatarMenuOpen = false
      this.openSettingsTab(opts)
    },
    /**
     * 中栏开「系统设置」标签（单例；任一窗格已开则激活）。
     * opts.nav / opts.service 等价于薄壳页的 ?nav=xxx&service=yyy 深链——
     * 网关错误提示的逃生门指着它，tab 形态下必须一样能一步定位到那一项。
     * 已开着的标签再带深链进来时就地改 props（key 不变，组件不重建）。
     */
    openSettingsTab(opts) {
      const nav = (opts && opts.nav) || ''
      const service = (opts && opts.service) || ''
      const tabId = 'admin-settings'
      for (const pane of ['left', 'right']) {
        const list = pane === 'left' ? this.leftFiles : this.rightFiles
        const existing = list.find(f => f.id === tabId)
        if (existing) {
          if (nav) existing.adminNav = nav
          if (service) existing.adminService = service
          this[pane === 'left' ? 'activeFileIdLeft' : 'activeFileIdRight'] = existing.id
          this.focusedPane = pane
          this.$nextTick(() => this.triggerWorkbenchResize())
          return
        }
      }
      const targetPane = this.splitMode ? this.focusedPane : 'left'
      const list = targetPane === 'left' ? this.leftFiles : this.rightFiles
      list.push({
        id: tabId,
        tabType: 'admin-settings',
        name: this.$t('workbench.settingsTabName'),
        adminNav: nav,
        adminService: service,
      })
      this[targetPane === 'left' ? 'activeFileIdLeft' : 'activeFileIdRight'] = tabId
      this.focusedPane = targetPane
      this.$nextTick(() => this.triggerWorkbenchResize())
    },
    goToPluginMarket() {
      // VS Code 扩展栏形态：rail 按钮开左栏列表面板（保留标签页与编辑区），
      // 点列表项再在中栏开详情 tab。独立页面路由仍保留给 admin 入口与直链兜底。
      this.toggleLeftPane('market')
    },
    // 左栏列表点行 → 中栏开该项详情 tab（同 id 单例，任一窗格已开则激活）
    openMarketDetail(spec) {
      const tabId = `market-detail_${spec.kind}_${spec.id}`
      for (const pane of ['left', 'right']) {
        const list = pane === 'left' ? this.leftFiles : this.rightFiles
        const existing = list.find(f => f.id === tabId)
        if (existing) {
          const idProp = pane === 'left' ? 'activeFileIdLeft' : 'activeFileIdRight'
          this[idProp] = existing.id
          this.focusedPane = pane
          this.$nextTick(() => this.triggerWorkbenchResize())
          return
        }
      }
      const targetPane = this.splitMode ? this.focusedPane : 'left'
      const list = targetPane === 'left' ? this.leftFiles : this.rightFiles
      const idProp = targetPane === 'left' ? 'activeFileIdLeft' : 'activeFileIdRight'
      list.push({
        id: tabId,
        tabType: 'market-detail',
        name: spec.name || spec.id,
        marketSpec: spec
      })
      this[idProp] = tabId
      this.focusedPane = targetPane
      this.$nextTick(() => this.triggerWorkbenchResize())
    },
    formatTime(timeStr) {
  if (!timeStr) return '-'

  // Parse the timestamp
  const date = new Date(timeStr)
  const now = new Date()
  const diffMs = now - date
  const diffMins = Math.floor(diffMs / (1000 * 60))
  const diffHrs = Math.floor(diffMs / (1000 * 60 * 60))
  const diffDays = Math.floor(diffMs / (1000 * 60 * 60 * 24))

  // Return relative time format
  if (diffMins < 1) {
    return this.$t('workbench.justNow')
  } else if (diffMins < 60) {
    return this.$t('workbench.minutesAgo', { n: diffMins })
  } else if (diffHrs < 24) {
    return this.$t('workbench.hoursAgo', { n: diffHrs })
  } else if (diffDays < 7) {
    return this.$t('workbench.daysAgo', { n: diffDays })
  } else {
    // Fallback to MM/DD format for older dates
    return `${date.getMonth() + 1}/${date.getDate()}`
    }
    },

    // --- 布局控制 ---
    toggleSidebar() {
      this.sidebarCollapsed = !this.sidebarCollapsed
    },

    toggleAiPanel() {
      this.showAiPanel = !this.showAiPanel
      this.$nextTick(() => {
        this.triggerWorkbenchResize()
        if (this.showAiPanel) {
          this.refreshAiContextPreview()
          // Auto-load recent chat history when panel opens
          this.fetchChatHistory()
        }
      })
    },

    toggleToolsPanel() {
      this.showToolsPanel = !this.showToolsPanel
      this.$nextTick(() => this.triggerWorkbenchResize())
    },

    // 底部状态条工具入口：点当前已打开的 tab 则收起抽屉，否则切换/打开到该 tab
    openToolFromStatusBar(key) {
      if (this.showToolsPanel && this.activeToolKey === key) {
        this.toggleToolsPanel()
        return
      }
      this.switchToolTab(key)
      if (!this.showToolsPanel) {
        this.toggleToolsPanel()
      }
    },

    triggerWorkbenchResize() {
      // WPS SDK 通常监听 window resize 来调整内部 iframe 大小
      if (typeof window !== 'undefined') {
        window.dispatchEvent(new Event('resize'))
        // 给过渡动画留时间，保证最终尺寸正确
        setTimeout(() => window.dispatchEvent(new Event('resize')), 250)
      }
    },

    // ================= Tabs 拖拽：同窗格换序 + 分屏跨窗格移动 =================
    // 标签页拖拽与分栏方法组已外置 → ./tabDragSplit.js（Phase 2）

    // --- 文件管理逻辑 ---
    // 文件打开与标签页方法组已外置 → ./fileOpenTabs.js（Phase 2）

    // 处理文件重命名（历史：WPS 时代由文件信息轮询触发；现无调用方，保留为通用逻辑）
    async handleFileRename(pane, data) {
      const activeFile = pane === 'left' ? this.activeFileLeft : this.activeFileRight
      if (!activeFile) return

      const newName = data.name || data.fileName
      if (!newName) return

      const oldName = activeFile.name
      if (oldName === newName) return

      console.log(`文件重命名(WPS): ${oldName} -> ${newName}`)

      try {
        // 1) 先落库：调用后端重命名（同步物理文件）
        const updated = await renameFile(this.projectId, activeFile.id, newName)
        const finalName = updated?.name || newName

        // 2) 同步更新 Tabs（左右可能同时打开同一文件）
        this.leftFiles.forEach(f => {
          if (f.id === activeFile.id) f.name = finalName
        })
        this.rightFiles.forEach(f => {
          if (f.id === activeFile.id) f.name = finalName
        })

        // 3) 同步文件树（不要求整页刷新）
        if (this.$refs.fileTree) {
          // 优先局部更新（如果提供了方法），否则 fallback 重新拉取
          if (typeof this.$refs.fileTree.updateFileName === 'function') {
            this.$refs.fileTree.updateFileName(activeFile.id, finalName)
          } else {
            await this.$refs.fileTree.loadFiles()
          }
        }

        // 4) 触发响应式更新，确保 Tab 与编辑器的 fileName prop 立即刷新
        this.$forceUpdate()
      } catch (e) {
        console.error('WPS 重命名同步到后端失败:', e)
        // 回滚前端显示，避免出现“看起来改了但后端没改”
        this.leftFiles.forEach(f => {
          if (f.id === activeFile.id) f.name = oldName
        })
        this.rightFiles.forEach(f => {
          if (f.id === activeFile.id) f.name = oldName
        })
        this.$forceUpdate()
        uni.showToast({ title: this.$t('workbench.renameSyncFailed'), icon: 'none' })
      }
    },

    // 同步文件信息（从后端获取最新信息）
    async syncFileInfo(pane) {
      const activeFile = pane === 'left' ? this.activeFileLeft : this.activeFileRight
      if (!activeFile || !activeFile.id) return

      // Skip sync for virtual tabs (they have string IDs like 'artifact-xxx' or 'web_xxx')
      if (typeof activeFile.id === 'string' && (activeFile.id.startsWith('artifact-') || activeFile.id.startsWith('web_'))) {
        return
      }

      try {
        const fileDetail = await getFileDetail(this.projectId, activeFile.id)
        if (fileDetail && fileDetail.name) {
          const oldName = activeFile.name
          activeFile.name = fileDetail.name

          // 如果文件名变化了，刷新文件树
          if (oldName !== fileDetail.name) {
            console.log(`检测到文件名变化: ${oldName} -> ${fileDetail.name}`)
            if (this.$refs.fileTree) {
              await this.$refs.fileTree.loadFiles()
            }
            this.$forceUpdate()
          }
        }
      } catch (e) {
        // 如果遇到权限错误（deleted）或文件不存在，停止轮询
        const errStr = (e && e.toString()) || ''
        const shouldStop = errStr.includes('403') || errStr.includes('404') || errStr.includes('无权访问')
        if (shouldStop) {
           console.log(`[ProjectOverview] Stop polling due to error: ${errStr}`)
           if (this.fileInfoPollingIntervals && this.fileInfoPollingIntervals[pane]) {
                clearInterval(this.fileInfoPollingIntervals[pane])
                delete this.fileInfoPollingIntervals[pane]
           }
        } else {
            console.error('同步文件信息失败:', e)
        }
      }
    },

    // 启动文件信息轮询（用于检测重命名）
    startFileInfoPolling(pane) {
      // 防止重复创建
      if (this.fileInfoPollingIntervals && this.fileInfoPollingIntervals[pane]) {
        clearInterval(this.fileInfoPollingIntervals[pane])
      }

      // 每5秒轮询一次文件信息
      const intervalId = setInterval(() => {
        const activeFile = pane === 'left' ? this.activeFileLeft : this.activeFileRight
        if (!activeFile) {
          // 不清除，等待下次有文件时继续（或者也可以选择清除）
          // 这里保持原逻辑：仅仅是return，不clearInterval，
          // 因为 activeFile 可能会因为用户关闭标签变为空，但后续又打开
          // 但其实 activeFile 变化很大... 简单的做法是 keep checking
          return
        }
        this.syncFileInfo(pane)
      }, 5000)

      // 存储intervalId以便清理
      if (!this.fileInfoPollingIntervals) {
        this.fileInfoPollingIntervals = {}
      }
      this.fileInfoPollingIntervals[pane] = intervalId
    },

    // 生命周期钩子：组件销毁前清理定时器
    beforeDestroy() {
      if (this.fileInfoPollingIntervals) {
        Object.values(this.fileInfoPollingIntervals).forEach(id => clearInterval(id))
        this.fileInfoPollingIntervals = {}
      }
    },

    getActiveAiTargetFile() {
      // AI 仅对“当前激活的文档”生效，避免出现“浏览器Tab名 + 文档上下文”错配
      let candidate = null
      if (this.focusedPane === 'right' && this.splitMode) {
        candidate = this.activeFileRight || this.activeFileLeft || null
      } else {
        candidate = this.activeFileLeft || this.activeFileRight || null
      }
      if (!candidate) return null
      if (typeof this.isEditorOpenableFile === 'function' && !this.isEditorOpenableFile(candidate)) {
        return null
      }
      return candidate
    },

    // 变量库交互：书签版实现随 WPS 移除删除（#79，本 methods 对象后部的同名方法
    // 一直是实际生效者——对象字面量后键覆盖前键）。书签/文档域的 LibreOffice 等价记债。

    normalizeContextText(text, maxLen = 8000) {
      const raw = (text || '')
        .replace(/\u00A0/g, ' ')
        .replace(/\r\n/g, '\n')
      const cleaned = raw
        .replace(/[ \t]{2,}/g, ' ')
        .replace(/\n{3,}/g, '\n\n')
        .trim()
      if (!cleaned) return ''
      if (!maxLen || cleaned.length <= maxLen) return cleaned
      return `${cleaned.slice(0, maxLen)}\n...[上下文已截断 ${cleaned.length - maxLen} 字]`
    },
    buildAiContextPreview(context) {
      if (!context) return null
      return {
        fileName: context.fileName || this.activeAiFileName || this.$t('workbench.unnamedFile'),
        selection: this.normalizeContextText(context.selectionText || '', 160),
        snippet: this.normalizeContextText(context.documentText || '', 200),
        updatedAt: Date.now()
      }
    },
    async collectAiContextForChat(options = {}) {
      const { updatePreview = false } = options

      let contexts = []

      // 1. Manual Contexts (Multiple)
      if (this.manualContextFiles && this.manualContextFiles.length > 0) {
        for (const file of this.manualContextFiles) {
             const ctx = await this.buildSingleFileContext(file, true)
             if (ctx) contexts.push(ctx)
        }
      }
      // 2. Automatic Context (Active File)
      else {
        const active = this.getActiveAiTargetFile()
        if (active) {
            const ctx = await this.buildSingleFileContext(active, false)
            if (ctx) contexts.push(ctx)
        }
      }

      if (contexts.length === 0) {
        if (updatePreview) this.aiContextPreview = null
        return null
      }

      // Update Preview (Simple count or first file)
      if (updatePreview) {
        if (contexts.length > 0) {
            this.aiContextPreview = this.buildAiContextPreview(contexts[0])
            if (contexts.length > 1) {
                // Determine logic for multi-file preview if needed, or just let UI show tags
            }
        } else {
            this.aiContextPreview = null
        }
      }

      return contexts
    },

    // Helper to build context for a single file
    async buildSingleFileContext(file, isManual) {
        if (!file) return null
        const context = {
            fileId: file.id || file.fileId || null,
            fileName: file.fileName || file.name || '',
            fileType: file.fileType || file.tabType || '',
            wpsFileId: file.wpsFileId || null,
            selectionText: '',
            documentText: ''
        }

        // 从内置 LibreOffice 编辑器读选区/正文（#79：经执行器命令，替代原 WPS 实例方法）
        let useEditor = false
        if (!isManual) {
            useEditor = true
        } else {
             const active = this.getActiveAiTargetFile()
             // Verify ID match
             const fid = file.id || file.fileId
             if (active && active.id === fid) {
                 useEditor = true
             }
        }

        if (useEditor && this.libreOfficeActive && this.libreOfficeExecutor) {
            try {
                 const sel = await this.libreOfficeExecutor.executeCommand('get_selection', {})
                 context.selectionText = this.normalizeContextText((sel && sel.text) || '', 1500)
            } catch(e) {}
            try {
                 const doc = await this.libreOfficeExecutor.executeCommand('get_document_text', {})
                 const docText = doc && Array.isArray(doc.paragraphs)
                   ? doc.paragraphs.map(p => (p && (p.text !== undefined ? p.text : p)) || '').join('\n')
                   : (doc && doc.text) || ''
                 context.documentText = this.normalizeContextText(docText, 8000)
            } catch(e) {}
        }

        // Fallback or Summary
        if (!context.selectionText && !context.documentText && file.summary) {
             context.documentText = this.normalizeContextText(file.summary, 2000)
        }

        return context
    },
    async refreshAiContextPreview(manualTrigger = false) {
      if (!this.showAiPanel) return null
      try {
        this.aiContextLoading = true
        const contexts = await this.collectAiContextForChat({ updatePreview: true })

        if (manualTrigger) {
           if (!contexts || contexts.length === 0) {
              if (this.manualContextFiles.length === 0) {
                 uni.showToast({ title: this.$t('workbench.noActiveContext'), icon: 'none' })
              }
           } else {
              uni.showToast({ title: this.$t('workbench.contextUpdated'), icon: 'none' })
           }
        }
        return contexts
      } catch (e) {
        console.error('刷新 AI 上下文失败', e)
        if (manualTrigger) {
          uni.showToast({ title: this.$t('workbench.syncFailed'), icon: 'none' })
        }
        return null
      } finally {
        this.aiContextLoading = false
      }
    },
    handleChatInterfaceAction({ type, msg }) {
      if (type === 'insert') {
        this.insertAiMessageToDoc(msg)
      } else if (type === 'replace') {
        this.applyAiMessageToSelection(msg)
      } else if (type === 'export') {
        this.openExportDialog(msg)
      }
    },

    // Handle artifact open-tab event from ChatInterface
    // Creates a virtual .md tab in the left pane with typewriter effect
    handleArtifactOpenTab(artifactInfo) {
      console.log('[ProjectOverview] Opening artifact in tab:', artifactInfo)

      // Check if tab already exists
      const existingTab = this.leftFiles.find(f => f.artifactId === artifactInfo.id)
      if (existingTab) {
        // Activate existing tab
        this.activateTab(existingTab, 'left')
        return
      }

      // Create virtual markdown file object
      const virtualFile = {
        id: `artifact-${artifactInfo.id}`,
        artifactId: artifactInfo.id,
        name: artifactInfo.fileName || this.$t('workbench.aiWorkPlanFileName'),
        tabType: 'markdown',
        fileType: 'md',
        content: artifactInfo.content || artifactInfo.data?.content || '',

        createdAt: Date.now()
      }

      // Add to leftFiles and activate
      this.leftFiles.push(virtualFile)
      this.activeFileIdLeft = virtualFile.id

      // TODO: Persist to backend /项目根目录/AI助手工作计划/ if needed
      console.log('[ProjectOverview] Created markdown tab:', virtualFile.name)
    },
    // AI 指令路由方法组已外置 → ./agentClientActions.js（Phase 1）

    // 压缩包解压完成：刷新资源管理器，让新文件夹立即可见。
    onArchiveExtracted() {
      if (this.$refs.fileTree && this.$refs.fileTree.loadFiles) {
        this.$refs.fileTree.loadFiles()
      }
    },

    // #104: getEditor() adapter for VariablePanel — the five document-field
    // methods it expects, implemented over the LibreOffice executor's var_*
    // commands. Returns null while no editor is active so the panel keeps its
    // own “请先点击激活一个编辑窗口” fallback.
    getLibreVariableBridge() {
      if (!this.libreOfficeActive || !this.libreOfficeExecutor) return null
      const exec = (action, params) => this.libreOfficeExecutor.executeCommand(action, params)
      const t = (key) => this.$t(key)
      return {
        async getSelectionText() {
          const r = await exec('get_selection', {})
          return (r && r.success && r.text) || ''
        },
        async listVariableFields() {
          const r = await exec('var_list', {})
          if (!r || !r.success) throw new Error((r && r.message) || t('workbench.varListFailed'))
          return r.fields || []
        },
        async insertTextWithDocumentField(value, scope, name) {
          const r = await exec('var_insert', { text: value == null ? '' : String(value), scope, name })
          if (!r || !r.success) throw new Error((r && r.message) || t('workbench.varInsertFailed'))
        },
        async updateDocumentField(fieldId, nextText) {
          const r = await exec('var_update', { id: fieldId, text: nextText == null ? '' : String(nextText) })
          if (!r || !r.success) throw new Error((r && r.message) || t('workbench.varUpdateFailed'))
        },
        // resolver 是面板本地回调，无法跨 worker 传递：在这一侧枚举字段、逐个求值并回写。
        // 空值不回写——后端变量缺失时 resolver 返回 ''，同步不应清空文档里的内容。
        async syncAllDocumentFields(resolver) {
          const lr = await exec('var_list', {})
          if (!lr || !lr.success) throw new Error((lr && lr.message) || t('workbench.varListFailed'))
          let updated = 0
          for (const f of lr.fields || []) {
            let next
            try { next = resolver(f.scope, f.varName, f.text) } catch (e) { continue }
            next = next == null ? '' : String(next)
            if (!next || next === f.text) continue
            const ur = await exec('var_update', { id: f.id, text: next })
            if (ur && ur.success) updated++
          }
          return { updated }
        },
      }
    },

    // --- 文件选择/上传 ---

    insertAiMessageToDoc(message) {
      if (!message || !message.content) return
      // AI 回复是 Markdown，纯文本原语会把 **、# 原样落字——先剥离标记
      this.insertPlainTextToWps(markdownToPlainText(message.content))
    },
    async applyAiMessageToSelection(message) {
      if (!message || !message.content) return
      if (!this.libreOfficeActive || !this.libreOfficeExecutor) {
        uni.showToast({ title: this.$t('workbench.openDocFirst'), icon: 'none' })
        return
      }
      try {
        const sel = await this.libreOfficeExecutor.executeCommand('get_selection', {})
        if (!String((sel && sel.text) || '').trim()) {
          uni.showToast({ title: this.$t('workbench.selectReplaceContentFirst'), icon: 'none' })
          return
        }
        await this.libreOfficeExecutor.executeCommand('replace_selection', { text: markdownToPlainText(message.content) })
        uni.showToast({ title: this.$t('workbench.selectionReplaced'), icon: 'success' })
        return
      } catch (e) {
        console.error('替换选区失败', e)
        uni.showToast({ title: e.message || this.$t('workbench.replaceFailed'), icon: 'none' })
      }
    },
    // --- AI Context Drag & Drop ---
    handleAiDragOver(e) {
        if (e && e.preventDefault) e.preventDefault()
        this.dragOverAiPanel = true
    },
    handleAiDragLeave(e) {
        if (e && e.preventDefault) e.preventDefault()
        this.dragOverAiPanel = false
    },
    handleAiDrop(e) {
        if (e && e.preventDefault) e.preventDefault()
        this.dragOverAiPanel = false

        let fileData = null
        try {
             // 1. Try standard json format
             const raw = e.dataTransfer.getData('application/x-checkba-file')
             if (raw) fileData = JSON.parse(raw)
        } catch(e) {}

        if (!fileData) {
             try {
                  // 2. Try fallback text format
                  const raw2 = e.dataTransfer.getData('text/checkba-file-json')
                  if (raw2) fileData = JSON.parse(raw2)
             } catch(e) {}
        }

        // 3. Try global fallback (WebView/Browser safe)
        if (!fileData && typeof document !== 'undefined' && document.__checkbaDraggedFile) {
             fileData = { ...document.__checkbaDraggedFile }
        }

        if (fileData) {
             const file = {
                 id: fileData.fileId || fileData.id,
                 name: fileData.name || fileData.fileName,
                 fileType: fileData.fileType,
                 wpsFileId: fileData.wpsFileId,
                 isDir: fileData.fileType === 'folder' || fileData.isDir
             }

             // Check for folder file count limit (>10)
             if (file.isDir && this.$refs.fileTree && Array.isArray(this.$refs.fileTree.allFiles)) {
                 const allFiles = this.$refs.fileTree.allFiles
                 // Helper to count non-folder files recursively
                 const countDescendants = (pid) => {
                     let count = 0
                     const children = allFiles.filter(f => f.parentId == pid) // use fuzzy match for potential string/int diff
                     for (const child of children) {
                         if (!child.isFolder) {
                             count++
                         } else {
                             count += countDescendants(child.id)
                         }
                     }
                     return count
                 }

                 const totalFiles = countDescendants(file.id)
                 if (totalFiles > 10) {
                     uni.showToast({ title: this.$t('workbench.folderTooManyFiles', { count: totalFiles }), icon: 'none' })
                     return
                 }
             }

             if (this.$refs.chatInterface) {
                 this.$refs.chatInterface.addFile(file)
             }

             // Note: Visual tag display is now handled within ChatInterface
             uni.showToast({ title: this.$t('workbench.fileAdded', { name: fileData.name }), icon: 'none' })

        } else {
             uni.showToast({ title: this.$t('workbench.noDragData'), icon: 'none' })
        }
    },
    removeContextFile(index) {
        this.manualContextFiles.splice(index, 1)
    },
    removeAttachment(index) {
        this.pastedImages.splice(index, 1)
    },
    // --- Rich Input Support ---
    focusRichInput(e) {
      if (e && e.target && (e.target.classList.contains('attachment-remove') || e.target.tagName === 'IMAGE')) return
      if(this.$refs.aiRichInput) this.$refs.aiRichInput.focus()
    },
    onRichInput(e) {
       // Sync text
       const el = e.target
       this.aiInput = el.innerText
    },
    // Paste Handler
    handleRichPaste(e) {
       // Check for clipboard items (images)
       const items = (e.clipboardData || e.originalEvent.clipboardData).items
       let hasImage = false
       for (let i = 0; i < items.length; i++) {
          if (items[i].type.indexOf('image') !== -1) {
              const file = items[i].getAsFile()
              if (file) {
                 hasImage = true
                 e.preventDefault() // Stop default paste (img tag)

                 // Read file to create preview
                 const reader = new FileReader()
                 reader.onload = (evt) => {
                     // Add to pastedImages
                     this.pastedImages.push({
                         file: file, // Keep blob for sending
                         path: evt.target.result // Base64 for preview
                     })
                 }
                 reader.readAsDataURL(file)
              }
          }
       }
       // If mixed content (text + image), usually only one "paste" event fires for the primary data.
       // If no image found, let default text paste handle it.
    },
    getContextColor(type) {
       const t = (type || '').toLowerCase()
       // Colors from FileTree
       const colors = {
          word: '#7E94B3',
          doc: '#7E94B3',
          docx: '#7E94B3',

          ppt: '#B38F7E',
          pptx: '#B38F7E',

          pdf: '#B37E7E',

          excel: '#5CA67D',
          xls: '#5CA67D',
          xlsx: '#5CA67D',

          image: '#7EABB3',
          png: '#7EABB3',
          jpg: '#7EABB3',
          jpeg: '#7EABB3',

          video: '#947EB3',
          mp4: '#947EB3',

          audio: '#B3B37E',
          mp3: '#B3B37E',

          default: '#6C757D'
       }
       return colors[t] || colors.default
    },
    insertContextTag(file) {
       if (!this.$refs.aiRichInput) return

       const color = this.getContextColor(file.fileType)
       // Style: Italic, Serif-ish, small font, custom background (light version of color)
       // Using style string directly for contenteditable safety
       // Converting hex to rgba for background (simple approx or just use heavy opacity)
       // Actually simpler: Use the color as text color, and a very light background.
       // Let's use opacity 0.1 for bg

       // Hex to RGB helper (inline simplification)
       let r=0,g=0,b=0
       if(color.length === 7) {
           r = parseInt(color.slice(1,3), 16)
           g = parseInt(color.slice(3,5), 16)
           b = parseInt(color.slice(5,7), 16)
       }
       const bg = `rgba(${r},${g},${b},0.1)`

       // Truncate filename to max 10 characters
       const maxLen = 10
       const displayName = file.name.length > maxLen
           ? file.name.substring(0, maxLen) + '...'
           : file.name

       // Use AI WorkDeck brand colors for the tag
       const tagHtml = `<span class="ai-tag" contenteditable="false" data-file-id="${file.id || file.fileId}" data-full-name="${file.name}" title="${file.name}" style="background: linear-gradient(135deg, #1A5336 0%, #2D7A52 100%); color: #FFFFFF; font-size: 11px; font-weight: 500; padding: 2px 8px; border-radius: 4px; box-shadow: 0 1px 3px rgba(26,83,54,0.2);">@${displayName}</span>&nbsp;`


       const sel = window.getSelection()
       if (sel.rangeCount > 0) {
           const range = sel.getRangeAt(0)
           // Check if range is inside our input
           if (this.$refs.aiRichInput.contains(range.commonAncestorContainer)) {
               range.deleteContents()
               const fragment = range.createContextualFragment(tagHtml)
               range.insertNode(fragment)
               range.collapse(false) // Move cursor after
           } else {
               // Append to end
               this.$refs.aiRichInput.innerHTML += tagHtml
           }
       } else {
           this.$refs.aiRichInput.innerHTML += tagHtml
       }
       // Update text model
       this.aiInput = this.$refs.aiRichInput.innerText
    },
    clearRichInput() {
       if (this.$refs.aiRichInput) {
           this.$refs.aiRichInput.innerHTML = ''
           this.aiInput = ''
       }
       this.pastedImages = [] // Clear images too
    },
    async loadDynamicPlugins() {
      try {
        const res = await getPlugins()
        if (res && res.data) {
          // Map backend PluginMetadata to frontend plugin structure
          this.dynamicPlugins = res.data.map(p => ({
            key: `plugin-${p.id}`,
            // 左栏面板 key 是 plugin-<id>，桥要的是原始 id（握手上下文 + KV 分区键），
            // 两者别混用
            pluginId: p.id,
            label: p.name,
            icon: p.icon || '/static/plugin_default.png',
            activeIcon: p.icon || '/static/plugin_default.png',
            isDynamic: true,
            // manifest.permissions：PluginPane 的桥按它逐调用裁剪能力
            permissions: p.permissions || [],
            // web/ 相对路径映射成后端静态服务地址；绝对 URL 原样保留（旧形态）
            frontendEntry: resolvePluginEntryUrl(p.id, p.frontendEntry)
          }))
          console.log('Dynamic plugins loaded:', this.dynamicPlugins)
        }
      } catch (e) {
        console.error('Failed to load dynamic plugins:', e)
      }
    },

    // 拉「哪些 skill 是启用的」，供左栏插件位过滤（诉讼可视化默认不安装，装了才显示）。
    // 失败时保持 null = 不过滤：把已装功能藏起来的代价，远大于多显示一个入口。
    async loadEnabledSkills() {
      try {
        const res = await getSkills()
        // /api/skills/list 裸返回数组（无 {code,data} 信封，request() 原样透传）；
        // 旧写法只认 res.data，导致这里恒为空数组 = requiresSkill 门控入口装了也不出现
        const list = Array.isArray(res) ? res : ((res && res.data) || [])
        if (!Array.isArray(list)) return
        this.enabledSkillIds = list.filter(s => s && s.enabled).map(s => s.id)
      } catch (e) {
        console.warn('拉取已启用 skill 失败，左栏不做过滤:', e)
      }
    },

    // --- AI 对话 ---
    // 注：旧面板的 initAiModel / toggleModelDropdown / switchModel 已移除。
    // switchModel 模板零引用，所以它写的 activeAiProvider 这个 storage 键
    // 从来没被真正写入过，无历史数据需要迁移。
    scrollToBottom() {
      this.scrollTop = this.scrollTop + 1 // trigger value change for watcher if needed?
      // Actually uni-app scroll-top works better when set to a large value
      this.$nextTick(() => {
        this.scrollTop = 99999
      })
    },

    // --- AI 导出为 Word ---
    async openExportDialog(message) {
      if (!this.projectId) {
        uni.showToast({ title: this.$t('workbench.projectNotReady'), icon: 'none' })
        return
      }
      if (!message || !message.content) {
        uni.showToast({ title: this.$t('workbench.noExportContent'), icon: 'none' })
        return
      }
      this.exportSourceMessage = message
      // 默认文件名：项目名 + 时间
      const baseName = this.project.name || this.$t('workbench.aiReplyBaseName')
      const ts = new Date()
      const pad = n => (n < 10 ? `0${n}` : `${n}`)
      const defaultName = `${baseName}-${ts.getFullYear()}${pad(
        ts.getMonth() + 1
      )}${pad(ts.getDate())}`
      this.exportFileName = `${defaultName}.docx`
      this.exportTargetParentId = null
      this.exportFolderTree = []
      this.showExportDialog = true

      try {
        const allFiles = await getProjectFiles(this.projectId, null, true)
        this.exportFolderTree = this.buildExportFolderTree(allFiles || [])
      } catch (e) {
        console.error('加载文件夹列表失败', e)
        uni.showToast({ title: this.$t('workbench.loadFoldersFailed'), icon: 'none' })
      }
    },

    buildExportFolderTree(allFiles) {
      if (!Array.isArray(allFiles) || !allFiles.length) return []
      const folders = allFiles.filter(f => f && f.isFolder)
      if (!folders.length) return []

      const map = new Map()
      // Init map
      folders.forEach(f => {
        const isRoot = !f.parentId
        map.set(f.id, {
          ...f,
          children: [],
          level: 0,
          expanded: isRoot // Default: Root expanded, others collapsed
        })
      })

      const roots = []
      // Build hierarchy
      folders.forEach(f => {
        const node = map.get(f.id)
        if (node.parentId != null && map.has(node.parentId)) {
          map.get(node.parentId).children.push(node)
        } else {
          roots.push(node)
        }
      })

      // Flatten for v-for
      const result = []
      const traverse = (nodes, level) => {
        if (!Array.isArray(nodes)) return
        nodes
          .slice()
          .sort((a, b) => (a.name || '').localeCompare(b.name || '', 'zh-CN', { numeric: true }))
          .forEach(node => {
            node.level = level
            result.push(node)
            if (node.children && node.children.length) {
              traverse(node.children, level + 1)
            }
          })
      }

      traverse(roots, 0)
      return result
    },

    toggleExportFolder(folder) {
        if (!folder) return
        folder.expanded = !folder.expanded
        this.$forceUpdate()
    },

    isFolderVisible(folder) {
        if (!folder) return false
        if (!this.screenshotFolderTree || !this.screenshotFolderTree.length) return true

        let parentId = folder.parentId
        while (parentId) {
            const parent = this.screenshotFolderTree.find(f => f.id === parentId)
            if (!parent) return true
            if (!parent.expanded) return false
            parentId = parent.parentId
        }
        return true
    },

    selectExportFolder(folderId) {
      this.exportTargetParentId = folderId
    },

    closeExportDialog() {
      if (this.exportLoading) return
      this.showExportDialog = false
      this.exportSourceMessage = null
    },

    async confirmExportWord() {
      if (!this.projectId || !this.exportSourceMessage) {
        uni.showToast({ title: this.$t('workbench.projectNotReady'), icon: 'none' })
        return
      }
      let name = (this.exportFileName || '').trim()
      if (!name) {
        uni.showToast({ title: this.$t('workbench.enterFileName'), icon: 'none' })
        return
      }
      if (!/\.docx$/i.test(name)) {
        name = `${name}.docx`
      }
      const projectId = this.projectId
      const parentId = this.exportTargetParentId

      this.exportLoading = true
      try {
        const createdFile = await exportAiDocx({
          projectId,
          parentId,
          fileName: name,
          markdown: this.exportSourceMessage.content
        })

        this.showExportDialog = false
        this.exportSourceMessage = null

        // 刷新文件树
        if (this.$refs.fileTree && this.$refs.fileTree.loadFiles) {
          this.$refs.fileTree.loadFiles()
        }

        // 打开到当前聚焦窗格
        if (createdFile) {
          this.openFile(createdFile)
        }
        uni.showToast({ title: this.$t('workbench.docGenerated'), icon: 'none' })
      } catch (e) {
        console.error('导出 Word 失败', e)
        uni.showToast({ title: e.message || this.$t('workbench.exportFailed'), icon: 'none' })
      } finally {
        this.exportLoading = false
      }
    },
    // --- AI Header Actions ---
    toggleHistoryDrawer() {
        this.showHistoryDrawer = !this.showHistoryDrawer
        if (this.showHistoryDrawer) {
            this.fetchChatHistory()
        }
    },
    startNewChat() {
      this.aiMessages = []
      this.currentConversationId = null
      this.scrollToBottom()
      this.aiContextPreview = null
      // Retain current assistant/model settings? Yes.
      this.showHistoryDrawer = false // Close drawer if open
    },

    handleOpenCreateVariable() {
      if (this.$refs.variablePanel) this.$refs.variablePanel.openCreateModal()
    },
    handleSyncVariable() {
      if (this.$refs.variablePanel) this.$refs.variablePanel.syncDocument()
    },
    async fetchChatHistory(quiet = false) {
      if (!this.projectId) return
      if (!quiet) this.loadingHistory = true
      // Note: Do NOT set showHistoryDrawer = true here
      // The drawer should only open when user clicks the toggle button (toggleHistoryDrawer)
      try {
          const res = await getAiConversations(this.projectId)
          // 后台任务「跑完未读」检测：上次快照 RUNNING → 本次终态，且不是当前正看的
          // 会话 → 记为未读（蓝点），点开该会话时清除
          const prevStatuses = this.convStatusSnapshot || {}
          for (const item of (res || [])) {
              const cid = item.conversationId
              if (!cid) continue
              if (prevStatuses[cid] === 'RUNNING' && item.runStatus && item.runStatus !== 'RUNNING'
                      && cid !== this.currentConversationId && !this.unreadConversations.includes(cid)) {
                  this.unreadConversations.push(cid)
              }
          }
          this.convStatusSnapshot = Object.fromEntries((res || []).filter(i => i.conversationId).map(i => [i.conversationId, i.runStatus]))
          // Map to display format
          // Backend returns: [{conversationId, updatedAt, lastMessage, runStatus}, ...]
          // We map to: { id, title, date }
          this.chatHistoryList = (res || []).map(item => ({
              id: item.conversationId,
              title: item.title ? item.title.replace(/<[^>]+>/g, '').trim() : (item.lastMessage ? (item.lastMessage.substring(0, 20) + (item.lastMessage.length > 20 ? '...' : '')) : this.$t('workbench.newConversation')),
              updatedAt: item.updatedAt,
              lastMessage: item.lastMessage ? item.lastMessage.replace(/<[^>]+>/g, '').replace(/\s+/g, ' ').substring(0, 60) + (item.lastMessage.length > 60 ? '...' : '') : '',
              conversationId: item.conversationId,
              runStatus: item.runStatus || null,
              unread: this.unreadConversations.includes(item.conversationId)
          }))
      } catch (e) {
        console.error('Fetch history failed', e)
        if (!quiet) uni.showToast({ title: this.$t('workbench.loadHistoryFailed'), icon: 'none' })
      } finally {
        if (!quiet) this.loadingHistory = false
      }
    },

    async loadHistoryChat(chat) {
        if (!chat || !chat.conversationId) return
        this.currentConversationId = chat.conversationId
        // 点开即视为已读：清掉「后台跑完未读」蓝点
        const unreadIdx = this.unreadConversations.indexOf(chat.conversationId)
        if (unreadIdx >= 0) this.unreadConversations.splice(unreadIdx, 1)
        this.loadingHistory = true // Reuse loading state or local
        try {
            const msgs = await getAiHistory({
                projectId: this.projectId,
                conversationId: chat.conversationId
            })
            // 竞态防护：快速切换会话时，丢弃已不是当前选中会话的旧响应，避免旧数据覆盖新会话
            if (this.currentConversationId !== chat.conversationId) return
            // Pass conversationId and messages to ChatInterface via $refs
            if (this.$refs.chatInterface && typeof this.$refs.chatInterface.loadMessages === 'function') {
                this.$refs.chatInterface.loadMessages(chat.conversationId, msgs)
                // Load metadata (file changes + token usage) for historical display
                if (typeof this.$refs.chatInterface.loadConversationMetadata === 'function') {
                    this.$refs.chatInterface.loadConversationMetadata(chat.conversationId)
                }
            } else {
                // Fallback for legacy - populate aiMessages directly
                // 契约 D：用户看 displayContent、为空回退 content（模型永远只看 content）
                this.aiMessages = (msgs || []).map(m => ({
                    id: m.id,
                    role: m.role ? m.role.toLowerCase() : 'user',
                    content: m.displayContent || m.content
                }))
            }
            this.showHistoryDrawer = false
        } catch (e) {
            console.error('Load chat failed', e)
            uni.showToast({ title: this.$t('workbench.loadConversationFailed'), icon: 'none' })
        } finally {
            this.loadingHistory = false
        }
    },
    // 会话状态 → 状态点样式类。黄=等用户（暂停/待审批）、蓝=后台跑完未读、
    // 动画绿=运行中、红=出错；无任务/已读完成不打点。
    convDotClass(chat) {
        if (!chat) return ''
        if (chat.runStatus === 'RUNNING') return 'dot-running'
        if (chat.runStatus === 'PAUSED' || chat.runStatus === 'AWAITING_APPROVAL'
            || chat.runStatus === 'AWAITING_INPUT' || chat.runStatus === 'INTERRUPTED') return 'dot-attention'
        if (chat.runStatus === 'ERROR') return 'dot-error'
        if (chat.unread) return 'dot-unread'
        return ''
    },
    convStatusLabel(chat) {
        if (!chat) return ''
        if (chat.runStatus === 'RUNNING') return this.$t('workbench.statusRunning')
        if (chat.runStatus === 'PAUSED') return this.$t('workbench.statusPaused')
        if (chat.runStatus === 'INTERRUPTED') return this.$t('workbench.statusInterrupted')
        if (chat.runStatus === 'AWAITING_APPROVAL') return this.$t('workbench.statusAwaitingApproval')
        // 「待回答」必须与「待审批」分开：这是新增 AWAITING_INPUT（而不复用
        // AWAITING_APPROVAL）的全部目的——用户要能在列表上分出「AI 在问我」
        // 和「AI 要我点头」两件事
        if (chat.runStatus === 'AWAITING_INPUT') return this.$t('workbench.statusAwaitingInput')
        if (chat.runStatus === 'ERROR') return this.$t('workbench.statusError')
        if (chat.unread) return this.$t('workbench.statusDone')
        return ''
    },
  }
}
</script>

<!-- 样式单一来源：./project-overview.scss（Phase 0 外置）。新增样式写进该文件，不要在此处内联。 -->
<style lang="scss" scoped src="./project-overview.scss"></style>
