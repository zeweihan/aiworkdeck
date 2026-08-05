<template>
  <view class="page-project-overview" :class="{ 'compact-mode': isCompactLayout, 'is-resizing': resizing && resizing.active }">
    <!-- 顶部固定项目信息 -->
    <view class="project-header">
      <view class="header-left">

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
            <text v-else class="project-name" @tap="startRenameProject" title="点击重命名">{{ project.name || '未命名项目' }}</text>
            <!-- IDE 化：最近项目切换器（VS Code 的 Open Recent 语义） -->
            <view class="project-switcher" @tap.stop="toggleProjectSwitcher" title="切换到最近项目">
              <text class="switcher-arrow" :class="{ 'is-open': projectSwitcherOpen }">▾</text>
            </view>
            <view v-if="projectSwitcherOpen" class="switcher-mask" @tap.stop="projectSwitcherOpen = false"></view>
            <view v-if="projectSwitcherOpen" class="switcher-menu" @tap.stop>
              <view class="switcher-title"><text>最近项目</text></view>
              <view
                v-for="p in switcherProjects"
                :key="p.id"
                class="switcher-item"
                @tap="switchToProject(p)"
              >
                <text class="switcher-item-name">{{ p.name }}</text>
              </view>
              <view v-if="!switcherProjects.length" class="switcher-item switcher-empty">
                <text>没有其他最近项目</text>
              </view>
              <view class="switcher-item switcher-all" @tap="goAllProjects">
                <text>全部项目…</text>
              </view>
            </view>
            <view class="project-status-badge">
              <text class="status-text">进行中</text>
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
          </view>
          <view class="project-meta">
            <text class="meta-item">负责人：{{ project.manager || userDisplayName || '我' }}</text>

            <block v-if="project.listedCompanyName && project.listedCompanyName !== '-'">
                <text class="meta-divider">|</text>
                <text class="meta-item">上市公司：{{ project.listedCompanyName }}</text>
            </block>

            <block v-if="project.createdAt">
                <text class="meta-divider">|</text>
                <text class="meta-item">创建时间：{{ formatTime(project.createdAt) }}</text>
            </block>
          </view>
        </view>
      </view>

      <!-- Center Logo -->
      <view class="header-center">
         <image src="/static/logo_full_v2.png" mode="heightFix" class="project-logo" />
      </view>

      <view class="header-right">
        <!-- 授权标识（低调 chip）：已连接账户优先于试用版——
             试用码解锁后再连账户的用户，此时该看到的是账户状态 -->
        <view
          v-if="accountConnected"
          class="trial-chip account-chip"
          @tap.stop="goToAccountPanel"
          title="账户与用量"
        >
          <text class="trial-chip-text">已连接账户</text>
        </view>
        <view
          v-else-if="licenseMode === 'trial'"
          class="trial-chip"
          @tap.stop="showTrialInfo = true"
          title="试用版说明"
        >
          <text class="trial-chip-text">试用版</text>
        </view>
        <!-- 顶部工具区（IDE 风格）：分屏 / 浏览器 / 摘录 / AI / 工具 -->
        <view class="header-tools" v-if="!isClientView">
          <!-- 1. Left Sidebar -->
          <view
            class="top-bar-btn"
            :class="{ active: !sidebarCollapsed }"
            @tap="toggleSidebar"
            :title="sidebarCollapsed ? '展开左侧栏' : '收起左侧栏'"
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
            title="常用工具"
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
            title="AI 助手"
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
            :title="splitMode ? '关闭分屏' : '开启分屏'"
          >
            <svg class="tool-icon-svg" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
              <path v-for="(d, gi) in GLYPHS.splitCols" :key="gi" :d="d" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round" />
            </svg>
          </view>

          <!-- 5. Screenshot (OCR) -->
          <view
            class="top-bar-btn"
            @tap="startOcrCapture"
            title="截图摘录（OCR）"
          >
            <svg class="tool-icon-svg" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
              <path v-for="(d, gi) in GLYPHS.camera" :key="gi" :d="d" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round" />
            </svg>
          </view>

          <!-- 6. Browser (New Web) -->
          <view
            class="top-bar-btn"
            @tap="openBrowserTab()"
            title="浏览器"
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
            title="录制活动"
          >
            <svg class="tool-icon-svg" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
              <path v-for="(d, gi) in GLYPHS.record" :key="gi" :d="d" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round" />
            </svg>
          </view>
        </view>
        <view v-else>
            <!-- Client View Only Tools -->
            <view class="header-tools">
               <view class="icon-btn" @tap="handleLogout" title="退出登录">
                   <text class="tool-icon">×</text>
               </view>
            </view>
        </view>
        <!-- User Avatar moved to Left Rail -->
      </view>
    </view>

    <!-- 主体布局 -->
    <view class="main-layout" :class="{ 'is-compact': isCompactLayout }">
      <!-- Cursor 风格：最左常驻栏（Activity Bar） -->
      <view class="left-rail">
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
          title="文件暂存区"
          @tap="toggleLeftPane('staging')"
        >
          <view class="rail-icon-wrapper">
            <svg class="rail-icon-svg" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
              <path v-for="(d, gi) in GLYPHS.inbox" :key="gi" :d="d" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" class="rail-icon-path" />
            </svg>
          </view>
        </view>

        <!-- 插件广场：IDE 扩展市场式直达入口（浏览/安装不该藏在系统设置两跳之下） -->
        <view
          class="rail-btn"
          :class="{ active: leftPaneKey === 'market' && !sidebarCollapsed }"
          title="插件广场"
          @tap="goToPluginMarket"
        >
          <view class="rail-icon-wrapper">
            <svg class="rail-icon-svg" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
              <path d="M4 4h7v7H4z" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" class="rail-icon-path" />
              <path d="M4 13h7v7H4z" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" class="rail-icon-path" />
              <path d="M13 13h7v7h-7z" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" class="rail-icon-path" />
              <path d="M14.5 2.5h7v7h-7z" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" class="rail-icon-path" />
            </svg>
          </view>
        </view>

        <!-- 系统设置：AI 提供商 / API Key 等随时可改（不再只藏在首次向导里）。
             页面与接口仅管理员可用（后端 requireAdmin），入口对所有人可见便于发现。 -->
        <view class="rail-btn" title="系统设置（AI 提供商 / API Key）" @tap="goToSystemSettings">
          <view class="rail-icon-wrapper">
            <svg class="rail-icon-svg" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
              <path d="M12 15a3 3 0 1 0 0-6 3 3 0 0 0 0 6Z" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" class="rail-icon-path" />
              <path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 1 1-2.83 2.83l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 1 1-4 0v-.09a1.65 1.65 0 0 0-1-1.51 1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 1 1-2.83-2.83l.06-.06a1.65 1.65 0 0 0 .33-1.82 1.65 1.65 0 0 0-1.51-1H3a2 2 0 1 1 0-4h.09a1.65 1.65 0 0 0 1.51-1 1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 1 1 2.83-2.83l.06.06a1.65 1.65 0 0 0 1.82.33h.01a1.65 1.65 0 0 0 1-1.51V3a2 2 0 1 1 4 0v.09a1.65 1.65 0 0 0 1 1.51h.01a1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 1 1 2.83 2.83l-.06.06a1.65 1.65 0 0 0-.33 1.82v.01a1.65 1.65 0 0 0 1.51 1H21a2 2 0 1 1 0 4h-.09a1.65 1.65 0 0 0-1.51 1Z" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" class="rail-icon-path" />
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
                      <view v-else class="avatar-placeholder">{{ member.displayName?.charAt(0) || 'U' }}</view>
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
                                       {{ member.role === 'CLIENT' ? '客' : (member.displayName?.charAt(0) || 'U') }}
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
                          <view class="add-member-btn" @tap.stop="showInviteModal = true" title="添加成员">
                              <text class="add-icon">＋</text>
                          </view>
                      </view>
                  </scroll-view>
              </view>
           </view>
        </view>

        <!-- User Avatar (Bottom) -->
        <!-- User Avatar (Bottom) -->
        <view class="rail-user-avatar" @tap="goToUserProfile" title="个人中心">
           <view class="rail-user-avatar-inner">
               <image v-if="currentUser && currentUser.avatarUrl" :src="currentUser.avatarUrl" class="avatar-img" />
               <text v-else class="avatar-text">{{ (userDisplayName || currentUser?.displayName)?.charAt(0) || 'U' }}</text>
           </view>
        </view>
      </view>

      <!-- File Picker Dialog (for EasyVoice Import) -->
      <FilePickerDialog
        v-model:visible="showFilePicker"
        :project-id="projectId"
        @confirm="handleFilePickerConfirm"
      />

      <!-- Invite Modal (Refactored to AI Workdeck) -->
      <!-- Invite Member Dialog -->
      <InviteMemberDialog
        v-model:visible="showInviteModal"
        :project-id="projectId"
        @success="loadProjectMembers"
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

      <!-- Assistant Config Dialog Overlay (Moved to Root) -->
      <view v-if="showAssistantConfigDialog" class="dialog-overlay" style="z-index: 9999;" @tap="closeAssistantConfigDialog">
         <view class="config-dialog" @tap.stop>
            <view class="dialog-header">
               <text class="dialog-title">配置助手</text>
               <text class="dialog-close" @tap="closeAssistantConfigDialog">×</text>
            </view>
            <view class="dialog-content">
               <view class="form-item">
                  <text class="label">助手名称 (System)</text>
                  <input class="input readonly" :value="editingAssistant.name" disabled />
               </view>
               <view class="form-item">
                  <text class="label">预设 Prompt (System)</text>
                  <textarea class="textarea readonly" :value="editingAssistant.systemPrompt" disabled></textarea>
               </view>
               <view class="form-item">
                  <text class="label">用户自定义 Prompt</text>
                  <textarea class="textarea" v-model="editingAssistant.userPrompt" placeholder="输入自定义指令..."></textarea>
                  <text class="hint">注意：如果设置了自定义 Prompt，预设 Prompt 将被忽略（User Prompt Prevails）。</text>
               </view>
            </view>
            <view class="dialog-footer">
               <button class="btn-cancel" @tap="closeAssistantConfigDialog">取消</button>
               <button class="btn-save" @tap="saveAssistantConfig">保存</button>
            </view>
         </view>
      </view>

      <!-- 左侧文件树（可收起） -->
      <view class="sidebar-left" ref="sidebarLeft" :class="{ collapsed: sidebarCollapsed }" :style="{ width: sidebarCollapsed ? '0px' : sidebarWidth + 'px' }">
        <!-- 批量菜单遮罩：用于点击空白关闭下拉（不弹中间） -->
        <view v-if="showBatchMenu" class="batch-menu-mask" @tap="closeBatchMenu"></view>
        <view v-if="!sidebarCollapsed && leftPaneKey !== 'dd-files'" class="sidebar-header">
          <view class="sidebar-title-row">
            <text v-if="!fileBatchMode" class="sidebar-title">{{ leftPaneTitle }}</text>
            <view
              v-else
              class="btn-select-all"
              @tap="selectAllFiles"
            >
              <text>选择全部</text>
            </view>
          </view>

          <view v-if="leftPaneKey === 'files'" class="sidebar-actions-row">
            <view class="sidebar-actions">
              <!-- 1. 新建文件 (普通模式) -->
              <view
                v-if="!fileBatchMode"
                class="icon-btn mini"
                @tap="onFileTreeQuickAction('newFile')"
                title="新建文档"
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
                title="新建文件夹"
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
                title="批量选择"
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
                title="上传文件"
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
                title="批量下载"
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
                title="排序"
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
                title="批量复制"
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
                :title="fileBatchMode ? '删除选中' : '回收站'"
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
          <EasyVoicePane
             v-else-if="leftPaneKey === 'easyvoice'"
             @request-doc-text="handleEasyVoiceDocRequest"
             @highlight-sentence="handleTtsHighlight"
             @clear-highlight="handleTtsClearHighlight"
          />
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
            @compare-file="onVersionCompareFile"
            @clear-file-filter="versionFileFilter = null"
            @reload-files="onVersionReloadFiles"
            @adopt-conflict="adoptConflictPending = $event"
          />
          <MarketSidebarPanel
            v-else-if="leftPaneKey === 'market'"
            @open-detail="openMarketDetail"
          />
          <PluginPane
            v-else-if="leftPaneKey && dynamicPlugins.some(p => p.key === leftPaneKey)"
            :url="dynamicPlugins.find(p => p.key === leftPaneKey)?.frontendEntry"
            :plugin-id="leftPaneKey"
          />
          <view v-else class="sidebar-plugin-placeholder">
            <text class="placeholder-title">{{ leftPaneTitle }}</text>
            <text class="placeholder-desc">加载中...</text>
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
                <view class="tabs-plus" @tap="onTabsPlusClick('left')" title="新建/复制">
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
                <view class="tabs-plus" @tap="onTabsPlusClick('right')" title="新建/复制">
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
                  <text class="empty-text">选择文件开始工作</text>
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
                    />
                  </view>
                  <view v-if="activeFileLeft && !useLibreEditor(activeFileLeft)" class="pane-content">
                    <BrowserPane
                      v-if="isBrowserTab(activeFileLeft)"
                      :key="activeFileLeft.id"
                      :tab-id="activeFileLeft.id"
                      :url="activeFileLeft.url"
                      @url-change="onBrowserUrlChange('left', $event)"
                      @title-change="onBrowserTitleChange('left', $event)"
                      @open-new-tab="openBrowserTab($event)"
                    />
                    <MarkdownPreview
                      v-else-if="isMarkdownTab(activeFileLeft)"
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
                    <PluginPane
                      v-else-if="activeFileLeft.fileType === 'plugin'"
                      :url="activeFileLeft.frontendEntry"
                      :plugin-id="activeFileLeft.id"
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
                    <text class="empty-text">左侧空闲</text>
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
                    />
                  </view>
                  <view v-if="activeFileRight && !useLibreEditor(activeFileRight)" class="pane-content">
                    <BrowserPane
                      v-if="isBrowserTab(activeFileRight)"
                      :key="activeFileRight.id"
                      :tab-id="activeFileRight.id"
                      :url="activeFileRight.url"
                      @url-change="onBrowserUrlChange('right', $event)"
                      @title-change="onBrowserTitleChange('right', $event)"
                      @open-new-tab="openBrowserTab($event)"
                    />
                    <MarkdownPreview
                      v-else-if="isMarkdownTab(activeFileRight)"
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
                    <PluginPane
                      v-else-if="activeFileRight.fileType === 'plugin'"
                      :url="activeFileRight.frontendEntry"
                      :plugin-id="activeFileRight.id"
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
                    <text class="empty-text">右侧空闲</text>
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
                    <view class="tool-action-btn" @tap="handleOpenCreateVariable" title="设为变量">
                      <text class="btn-icon">＋</text>
                      <text class="btn-text">设为变量</text>
                    </view>
                    <view class="tool-action-btn" @tap="handleSyncVariable" title="同步">
                      <text class="btn-icon">↻</text>
                      <text class="btn-text">同步</text>
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
                  <view class="icon-btn" title="收起" @tap="toggleToolsPanel">
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
              :assistants="assistants"
              v-model:current-assistant-id="currentAssistantId"
              :active-tab="currentActiveTab"
              :active-tab-pane="focusedPane"
              @close="toggleAiPanel"
              @toggle-history="toggleHistoryDrawer"
              @new-chat="startNewChat"
              @load-history="loadHistoryChat"
              @message-action="handleChatInterfaceAction"
              @config-assistant="openAssistantConfig"
              @client-action="handleClientAction"
              @refresh-history="fetchChatHistory"
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
                <view class="menu-item header">历史对话</view>
                <scroll-view scroll-y class="drawer-list" style="max-height: 350px;">
                    <view v-if="loadingHistory" class="menu-item" style="color:#999;">加载中...</view>
                    <view v-else-if="chatHistoryList.length === 0" class="menu-item" style="color:#999;">暂无历史记录</view>
                    <view v-else v-for="chat in chatHistoryList" :key="chat.id" class="menu-item" @tap="loadHistoryChat(chat)">
                        <view v-if="convDotClass(chat)" class="conv-dot" :class="convDotClass(chat)"></view>
                        <view style="flex:1; overflow:hidden;">
                            <text class="item-title" style="display:block; font-size:13px; color:#333; margin-bottom:2px;">{{ chat.title || '未命名对话' }}</text>
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
            <text class="upload-title">导出为 Word</text>
            <text class="upload-subtitle">选择存放位置并输入文件名</text>
          </view>
          <view class="folder-body">
            <view class="upload-row">
              <text class="upload-label">文件名</text>
              <input
                v-model="exportFileName"
                class="dialog-input"
                placeholder="例如：AI回复.docx"
              />
            </view>
            <view class="upload-row export-folder-label-row">
              <text class="upload-label">存放位置</text>
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
                <text class="folder-name">根目录</text>
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
                <text>暂无其他文件夹，将保存到根目录</text>
              </view>
            </scroll-view>
          </view>
          <view class="upload-footer">
            <view class="upload-btn upload-btn-secondary" @tap="closeExportDialog">
              取消
            </view>
            <view
              class="upload-btn upload-btn-primary"
              :class="{ 'upload-btn-disabled': exportLoading || !exportFileName.trim() }"
              @tap="!exportLoading && exportFileName.trim() && confirmExportWord()"
            >
              {{ exportLoading ? '导出中...' : '确定导出' }}
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
            <text class="upload-title">保存截图</text>
          </view>
          <view class="folder-content">
            <view class="upload-row">
              <text class="upload-label">文件名</text>
              <input
                v-model="screenshotSaveName"
                class="dialog-input"
                placeholder="例如：screenshot.png"
              />
            </view>
            <view class="upload-row export-folder-label-row">
              <text class="upload-label">存放位置</text>
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
                <text class="folder-name">根目录</text>
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
                <text>暂无子文件夹</text>
              </view>
            </scroll-view>
          </view>
          <view class="upload-footer">
            <view class="upload-btn upload-btn-secondary" @tap="closeScreenshotSaveDialog">
              取消
            </view>
            <view
              class="upload-btn upload-btn-primary"
              :class="{ 'upload-btn-disabled': screenshotSaveLoading || !screenshotSaveName.trim() }"
              @tap="!screenshotSaveLoading && screenshotSaveName.trim() && confirmSaveScreenshot()"
            >
              {{ screenshotSaveLoading ? '保存中...' : '确定保存' }}
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
          <text>正在获取画面…</text>
        </view>
        <!-- #endif -->
        <view class="ocr-overlay-hintline">
          <text>拖动框选 · 单击/ESC 退出</text>
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
            <text class="upload-title">选择要打开的文件</text>
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
              <text>无可用关联文件</text>
            </view>
          </view>
          <view class="upload-footer">
            <view class="upload-btn upload-btn-secondary" @tap="closeFileLinkPicker">关闭</view>
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
          <view class="webmark-ghost-badge">网核</view>
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
        <text class="adopt-pending-text">有文件等着你做选择</text>
        <text class="adopt-pending-go" @tap="goHandleAdoptConflict">去处理</text>
      </view>

      <!-- IDE 化 Cmd+P 快速打开 -->
      <QuickOpenPanel
        v-if="quickOpenVisible"
        :project-id="projectId"
        @open="onQuickOpenFile"
        @close="quickOpenVisible = false"
      />

      <!-- 试用版说明弹窗 -->
      <view v-if="showTrialInfo" class="awd-dialog-mask" @tap="showTrialInfo = false">
        <view class="awd-dialog" @tap.stop>
          <view class="awd-dialog-header">
            <text class="awd-dialog-title">试用版</text>
          </view>
          <view class="awd-dialog-body">
            <text class="awd-dialog-text">当前为试用版，全部功能均可正常使用。升级正式版可连接 AI Workdeck 账户，同步已购内容并使用平台 AI 通道。</text>
          </view>
          <view class="awd-dialog-footer">
            <button class="awd-btn awd-btn-secondary" @tap="showTrialInfo = false">知道了</button>
            <button class="awd-btn awd-btn-primary" @tap="openUpgradeSite">了解正式版</button>
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
        <text>活动录制中</text>
      </view>
      <view v-if="versionWorkStatus.enabled && (versionWorkStatus.working || versionWorkStatus.onDraft)" class="status-item status-clickable" @tap="goHandleAdoptConflict">
        <view class="status-dot amber"></view>
        <text>{{ versionWorkStatusLabel }}</text>
      </view>
      <view class="status-spacer"></view>
      <view v-if="activeFileLeft" class="status-item status-file">
        <text>{{ activeFileLeft.name }}</text>
      </view>
      <view v-if="splitMode" class="status-item">
        <text>分屏</text>
      </view>
      <view class="status-sep"></view>
      <view class="status-item status-brand">
        <view class="status-dot mint"></view>
        <text>AI Workdeck</text>
      </view>
    </view>
  </view>
</template>

<script>
import LibreOfficeEditor from '@/components/LibreOfficeEditor.vue'
import BrowserPane from '@/components/BrowserPane.vue'
import FileTree from '@/components/FileTree.vue'
import QuickOpenPanel from '@/components/QuickOpenPanel.vue'
import FilePreview from '@/components/FilePreview.vue'
import VariablePanel from '@/components/VariablePanel.vue'
import ProjectFavoritesPanel from '@/components/ProjectFavoritesPanel.vue'
import FileLinkDropZone from '@/components/FileLinkDropZone.vue'
import FileStagingArea from '@/components/FileStagingArea.vue'
import PluginPane from '@/components/PluginPane.vue' // Added
// 插件广场 VS Code 形态：左栏列表面板 + 中栏详情 tab（整页 MarketPane 仅存于 admin 独立页）
import MarketSidebarPanel from '@/components/MarketSidebarPanel.vue'
import MarketDetailPane from '@/components/MarketDetailPane.vue'
import EasyVoicePane from '@/components/EasyVoicePane.vue'
import DesensitizePane from '@/components/DesensitizePane.vue'
import ClipboardPanel from '@/components/ClipboardPanel.vue'
import SearchPanel from '@/components/SearchPanel.vue'
import VersionPanel from '@/components/version/VersionPanel.vue'
import InviteMemberDialog from '@/components/InviteMemberDialog.vue'
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
  aiChat,
  exportAiDocx,
  getProjectMembers,
  logActivity,
  inviteClient,
  removeProjectMember,
  getAiHistory,

  getAiConversations,
  getAiConfig,
  getAssistants, // Added
  getPlugins, // Added
  getFileText,
  getVersionStatus, // 版本面板之外也要知道「有没有采纳等待处理」
  promptFeatureNotConfigured, // 功能未配置统一引导（#18 T7）
  getProjectLocalPath, // 在访达中显示（IDE 化）
  getFileLocalPath,
  getMyProjects, // 最近项目切换器
  bindShareholderMeetingConversation, // 股东大会核查：会话绑定
  getLicenseStatus, // 试用版标识（商业化解锁门）
  getAccountStatus // 账户连接标识（商业化 PR-B）
} from '@/services/api.js'
import { openExternalUrl } from '@/utils/externalLink.js'
import { getCurrentUser } from '@/utils/auth.js'
import { recordProjectVisit, getRecentProjectIds, syncRecentToMenuFetching } from '@/utils/recentProjects.js'
import { markdownToPlainText } from '@/utils/markdownPlain.js'
import { FILE_BATCH_ACTIONS, FILE_TREE_QUICK_ACTIONS } from '@/config/fileActions.js'
import { WORKBENCH_TOOLS } from '@/config/tools.js'
import { OCR_ACTION_LABELS, INTERNAL_LINK_SCHEMES, WPS_INTERNAL_HTTP_LINK_BASE } from '@/config/workbenchActions.js'
import {
  LEFT_SIDEBAR_PLUGINS,
  getLeftSidebarPlugin,
  getPluginsForUser
} from '@/config/leftSidebarPlugins.js'

import { activityTracker } from '@/utils/activityTracker.js'

import { ICONS as GLYPHS } from '@/config/icons.js'
import DdFilesPanel from '@/components/DdFilesPanel.vue'
import ShareholderMeetingPanel from '@/components/ShareholderMeetingPanel.vue'
import DdRequestEditor from '@/components/DdRequestEditor.vue'
import ChatInterface from '@/components/ChatInterface.vue'
import { panelSwitchingMethods } from './panelSwitching.js'
import { agentClientActionMethods } from './agentClientActions.js'
import { librePoolMethods } from './librePool.js'
import { stagingAreaMethods } from './stagingArea.js'
import { tabDragSplitMethods } from './tabDragSplit.js'
import { fileOpenTabsMethods } from './fileOpenTabs.js'
import { clipboardBridgeMethods } from './clipboardBridge.js'
import { ocrActionMethods } from './ocrActions.js'
import { ocrCaptureMethods } from './ocrCapture.js'


export default {
  components: {
    LibreOfficeEditor,
    BrowserPane,
    QuickOpenPanel,
    FileTree,
    FilePreview,
    VariablePanel,
    ProjectFavoritesPanel,
    FileLinkDropZone,
    FileStagingArea,
    ClipboardPanel,
    DdFilesPanel,
    ShareholderMeetingPanel,
    DdRequestEditor,
    InviteMemberDialog,
    ChatInterface,
    MarkdownPreview,
    PluginPane, // Added
    MarketSidebarPanel,
    MarketDetailPane,
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
      easyVoiceImportCallback: null,
      renameProjectName: '',
      userDisplayName: '用户',

      // 授权状态（试用版标识，商业化解锁门）
      licenseMode: '',
      showTrialInfo: false,
      // 账户连接状态（商业化 PR-B）：已连接时 chip 改显「已连接账户」
      accountConnected: false,

      // 布局状态
      sidebarWidth: 260, // 侧边栏宽度
      sidebarCollapsed: false,
      isCompactLayout: false,
      leftPaneKey: null, // Initialize to null to prevent premature loading
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
      currentModelId: 'gemini-1.5-pro',
      showModelDropdown: false,
      availableModels: [
        { id: 'gemini-1.5-pro', name: 'Gemini 1.5 Pro' },
        { id: 'ollama', name: 'Local (Ollama)' }
      ],
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
      showAssistantMenu: false,
      currentAssistantId: 'default',
      showAssistantConfigDialog: false,
      editingAssistant: null,
      assistants: [], // Dynamic now
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

      stagingFiles: [], // 文件暂存区列表
      stagingOriginalParents: {}, // 记录文件进入暂存区前的原始 parentId: { fileId: originalParentId }
      splitMode: false,
      quickOpenVisible: false, // IDE 化 Cmd+P 快速打开
      projectSwitcherOpen: false, // IDE 化最近项目切换器
      switcherProjects: [],
      versionWorkStatus: { enabled: false, working: false, changedCount: 0, onDraft: null }, // 顶栏工作状态点
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
    // 历史入口的聚合状态点：等用户操作(黄) > 运行中(绿) > 跑完未读(蓝)
    historyBadge() {
      const list = this.chatHistoryList || []
      if (list.some(c => c.runStatus === 'PAUSED' || c.runStatus === 'AWAITING_APPROVAL')) return 'dot-attention'
      if (list.some(c => c.runStatus === 'RUNNING' && c.conversationId !== this.currentConversationId)) return 'dot-running'
      if (list.some(c => c.unread)) return 'dot-unread'
      return ''
    },
    // 桌面端：任一全屏蒙层/弹窗打开时为 true。BrowserView 是原生层，永远盖在
    // HTML 之上，所以弹窗期间必须隐藏 BrowserView，否则弹窗会被网页挡住"点了没反应"。
    desktopOverlayActive() {
      return !!(
        this.showOcrOverlay ||
        this.showScreenshotSaveDialog ||
        this.showExportDialog ||
        this.showAssistantConfigDialog ||
        this.showCompareDialog ||
        this.showFilePicker ||
        this.showInviteModal ||
        !!this.imagePreviewUrl ||
        (this.fileLinkPicker && this.fileLinkPicker.visible)
      )
    },
    LEFT_SIDEBAR_PLUGINS() {
      const user = getCurrentUser()
      if (user && user.role === 'CLIENT') {
        const clientPlugins = getPluginsForUser('CLIENT')
        // Append client-visible dynamic plugins if any (optional)
        return clientPlugins
      }
      return [...LEFT_SIDEBAR_PLUGINS, ...this.dynamicPlugins]
    },
    toolsSearchPlaceholder() {
      if (this.activeToolKey === 'variables') return '搜索变量…'
      if (this.activeToolKey === 'favorites') return '搜索收藏…'
      if (this.activeToolKey === 'clipboard') return '搜索剪贴板…'
      return '搜索…'
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
      if (s.onDraft && s.onDraft.name) return `正在稿《${s.onDraft.name}》上修改`
      if (s.working) return s.changedCount ? `工作中 · 已改 ${s.changedCount} 份` : '工作中'
      return ''
    },
    isClientView() {
      const user = getCurrentUser()
      return user && user.role === 'CLIENT'
    },
    // 是否有权管理成员
    canManageMembers() {
      const user = getCurrentUser()
      // Simplified: Admin or owner (backend checks too)
      return user && user.role !== 'CLIENT'
    },
    leftPaneTitle() {
      if (this.leftPaneKey === 'market') return '插件广场'
      try {
        return getLeftSidebarPlugin(this.leftPaneKey)?.label || '文件树'
      } catch (e) {
        return '文件树'
      }
    },
    groupedMembers() {
      const groups = {
        admin: { label: '项目管理员', list: [] },
        member: { label: '项目成员', list: [] },
        client: { label: '客户', list: [] }
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
    // NEW: Current active tab for AI context (prioritizes focused pane)
    currentActiveTab() {
      if (this.focusedPane === 'right' && this.activeFileRight) {
        return this.activeFileRight
      }
      return this.activeFileLeft || this.activeFileRight
    },
    currentModelName() {
      const m = this.availableModels.find(item => item.id === this.currentModelId)
      return m ? m.name : '选择模型'
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
        return typeof window !== 'undefined' && window.checkbaDesktop && window.checkbaDesktop.ocr
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
    // 后台任务状态轮询清理
    if (this.convStatusPollTimer) { clearInterval(this.convStatusPollTimer); this.convStatusPollTimer = null }
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

    // Cleanup manual event listener removed
  },
  onLoad(query) {
    this.pageEnterTime = Date.now()
    this.loadLicenseMode()
    if (query && query.id) {
      this.projectId = Number(query.id)
      recordProjectVisit(this.projectId) // IDE 化：启动直达/最近项目切换器的数据源
      syncRecentToMenuFetching() // 应用菜单「最近打开」随之更新（静默）
      this.loadProjectInfo()
      this.loadProjectMembers()
      this.checkAdoptConflict()

      // IDE 化「打开文件」过渡版：稍等页面挂载完成后打开指定文件
      if (query.openFileId) {
        const pendingId = Number(query.openFileId)
        setTimeout(() => this.openPendingLocalFile(pendingId), 600)
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

    // 面板初始化不能挂在登录态上：桌面免登（PR-A 去登录）后本地存储里没有
    // checkba_user，user 为 null——原先整段包在 if (user) 里，进项目左栏永远停在
    // 占位符（app-e2e J4 抓到：文件树/工具行不渲染，直到手动点一次左栏图标）。
    // CLIENT 角色默认 dd-files 的分支只对浏览器登录态（客户访问码）有意义，保留。
    const savedKey = uni.getStorageSync(`project_${this.projectId}_leftPaneKey`)
    if (savedKey) {
        this.leftPaneKey = savedKey
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

    // Initialize AI Model (Persistence > System Default)
    this.initAiModel()
    this.loadAssistants() // Fetch assistants
    this.loadDynamicPlugins() // Fetch dynamic plugins
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
      if (this.isDesktopApp && window.checkbaDesktop && window.checkbaDesktop.browser && window.checkbaDesktop.browser.setViewsVisible) {
        window.checkbaDesktop.browser.setViewsVisible({ visible: !this.desktopOverlayActive }).catch(() => {})
      }
    } catch (e) {
      // ignore
    }
  },
  onHide() {
    this.stopActivityTracking()

    // Desktop：离开工作区页面（如去个人中心）必须隐藏 BrowserView
    try {
      if (this.isDesktopApp && window.checkbaDesktop && window.checkbaDesktop.browser && window.checkbaDesktop.browser.setViewsVisible) {
        window.checkbaDesktop.browser.setViewsVisible({ visible: false }).catch(() => {})
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
      if (this.isDesktopApp && window.checkbaDesktop && window.checkbaDesktop.browser && window.checkbaDesktop.browser.setViewsVisible) {
        window.checkbaDesktop.browser.setViewsVisible({ visible: false }).catch(() => {})
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
      if (this.isDesktopApp && window.checkbaDesktop && window.checkbaDesktop.browser && window.checkbaDesktop.browser.onWebMark) {
        if (!this._desktopWebMarkUnsub) {
          this._desktopWebMarkUnsub = window.checkbaDesktop.browser.onWebMark(async (payload) => {
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
                title: title || (url ? (() => { try { return new URL(url).host } catch (e) { return '网核' } })() : '网核'),
                sourceUrl: url,
                content: text,
                imageBase64: imageBase64 || ''
              })
              // 立即刷新网核中心面板（如果可见）
              if (this.$refs.favoritesPanel && typeof this.$refs.favoritesPanel.refresh === 'function') {
                this.$refs.favoritesPanel.refresh()
              }
              uni.showToast({ title: '已加入网核收藏', icon: 'success' })
            } catch (e) {
              console.error('保存网核收藏失败:', e)
              uni.showToast({ title: e.message || '保存失败', icon: 'none' })
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
      if (this.isDesktopApp && window.checkbaDesktop && window.checkbaDesktop.browser && window.checkbaDesktop.browser.onOpenNewTab) {
        if (!this._desktopRendererOpenUnsub) {
          this._desktopRendererOpenUnsub = window.checkbaDesktop.browser.onOpenNewTab((data) => {
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

    // Epic #43 Track B / #79: when the desktop app exposes the embedded editor,
    // make it THE editor for Office documents (inline, no ⌘⇧O needed) —
    // install-and-use, zero config.
    try {
      this.libreOfficePreferred = !!(
        this.isDesktopApp &&
        window.checkbaDesktop &&
        window.checkbaDesktop.zetaoffice &&
        typeof window.checkbaDesktop.zetaoffice.getEditor === 'function'
      )
    } catch (e) {
      this.libreOfficePreferred = false
    }

    // （⌘⇧O 实验覆盖层已移除：内联编辑器就是产品默认，覆盖层只会在文档上
    // 凭空盖一条开发工具栏——用户报告。）

    // Desktop：拦截 WPS 中点击 “checkba://...” 的内部链接
    try {
      if (this.isDesktopApp && window.checkbaDesktop && window.checkbaDesktop.app && window.checkbaDesktop.app.onOpenInternal) {
        if (!this._desktopOpenInternalUnsub) {
          this._desktopOpenInternalUnsub = window.checkbaDesktop.app.onOpenInternal((payload) => {
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
                      uni.showToast({ title: '关联文件不存在', icon: 'none' })
                      return
                    }
                    if (list.length === 1) {
                      this.openFileLinkTarget(list[0].id, this.focusedPane || 'left')
                      return
                    }
                    this.fileLinkPicker = { visible: true, side: this.focusedPane === 'right' && this.splitMode ? 'right' : 'left', files: list, linkKey }
                  })
                  .catch((e) => {
                    uni.showToast({ title: (e && e.message) ? e.message : '打开失败', icon: 'none' })
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
                    uni.showToast({ title: '关联文件不存在', icon: 'none' })
                    return
                  }
                  if (list.length === 1) {
                    this.openFileLinkTarget(list[0].id, this.focusedPane || 'left')
                    return
                  }
                  this.fileLinkPicker = { visible: true, side: this.focusedPane === 'right' && this.splitMode ? 'right' : 'left', files: list, linkKey }
                })
                .catch((e) => {
                  uni.showToast({ title: (e && e.message) ? e.message : '打开失败', icon: 'none' })
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
      if (this.isDesktopApp && window.checkbaDesktop && window.checkbaDesktop.ocr && window.checkbaDesktop.ocr.onSelectionError) {
        if (!this._desktopOcrSelectionErrUnsub) {
          this._desktopOcrSelectionErrUnsub = window.checkbaDesktop.ocr.onSelectionError((data) => {
            // 只让活跃实例弹一次 toast，避免页面栈里 N 个实例连弹 N 次
            if (!this.isActiveOverviewInstance()) return
            const msg = data && data.message ? String(data.message) : '截图失败'
            uni.showToast({ title: msg, icon: 'none' })
          })
        }
      }
    } catch (e) {
      // ignore
    }
  },
  watch: {
    // IDE 化窗口标题：「文件名 — 项目名 — AI Workdeck」（Electron 窗口标题跟随 document.title）
    'project.name'() { this.updateWindowTitle() },
    activeFileIdLeft() { this.updateWindowTitle() },
    // 桌面端统一守卫：弹窗/蒙层打开 → 隐藏 BrowserView；全部关闭 → 恢复并重同步 bounds
    desktopOverlayActive(open) {
      if (!this.isDesktopApp) return
      try {
        const api = window.checkbaDesktop && window.checkbaDesktop.browser
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
    activeFileLeft(f) { this.onActiveOfficeFileChanged('left', f) },
    activeFileRight(f) { this.onActiveOfficeFileChanged('right', f) },
    focusedPane() { this.syncLibreExecutor() },
    // 关闭 tab 后清掉文件已不在左列表的过继备胎条目（closeFile 已 flush）
    'leftFiles.length'() { this.pruneClosedLibreSpares() },
  },
  methods: {
    // 授权标识：桌面端查授权模式与账户连接状态
    // （已连接账户 → 「已连接账户」chip；否则 mode=trial → 「试用版」chip）
    async loadLicenseMode() {
      if (typeof window === 'undefined' || !window.checkbaDesktop) return
      try {
        const status = await getLicenseStatus()
        this.licenseMode = (status && status.mode) || ''
      } catch (e) {
        // 服务器模式/旧后端没有该端点：静默忽略
      }
      try {
        const account = await getAccountStatus()
        this.accountConnected = !!(account && account.connected)
      } catch (e) {
        // 同上：查不到就按未连接处理，不影响试用版 chip
        this.accountConnected = false
      }
    },
    // chip 点击直达设置页「账户与用量」面板
    goToAccountPanel() {
      uni.navigateTo({ url: '/pages/admin/admin?nav=account' })
    },
    openUpgradeSite() {
      this.showTrialInfo = false
      openExternalUrl('https://www.aiworkdeck.com')
    },
    // Phase 1 外置的方法组（纯搬移，this 即页面实例）
    ...panelSwitchingMethods,
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
        parts.push('AI Workdeck')
        document.title = parts.join(' — ')
      } catch (e) { /* 标题失败不影响功能 */ }
    },
    // 最近项目切换器：展开时按本地最近顺序解析项目名（排除当前项目）
    async toggleProjectSwitcher() {
      this.projectSwitcherOpen = !this.projectSwitcherOpen
      if (!this.projectSwitcherOpen) return
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
        this.switcherProjects = []
      }
    },
    switchToProject(p) {
      this.projectSwitcherOpen = false
      if (!p || Number(p.id) === Number(this.projectId)) return
      // reLaunch：切项目不叠页面栈（多实例地雷）
      uni.reLaunch({ url: `/pages/project-overview/project-overview?id=${p.id}` })
    },
    goAllProjects() {
      this.projectSwitcherOpen = false
      uni.navigateTo({ url: '/pages/userprofile/userprofile' })
    },
    // Cmd+P 快速打开面板选中文件
    onQuickOpenFile(file) {
      this.quickOpenVisible = false
      if (file) this.openFile(file)
    },
    // 文件树右键「在访达中显示」：后端解析物理路径（localRoot 感知），桌面壳高亮
    async onRevealFile(file) {
      if (!file) return
      const shellApi = typeof window !== 'undefined' && window.checkbaDesktop
        && window.checkbaDesktop.fs && window.checkbaDesktop.fs.showItemInFolder
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
            uni.showToast({ title: '磁盘上没有这份文件', icon: 'none' })
            return
          }
        }
        if (path) await window.checkbaDesktop.fs.showItemInFolder(path)
      } catch (e) {
        uni.showToast({ title: (e && e.message) || '无法在访达中显示', icon: 'none' })
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

    // EasyVoice Integration
    async handleEasyVoiceDocRequest(callback) {
      console.log('[EasyVoice] Requesting doc text...')
      this.easyVoiceImportCallback = callback
      this.showFilePicker = true
    },

    // ==================== Desensitize Handlers ====================
    handleDesensitizeSelectFile(callback) {
        this.desensitizeFileSelectCallback = callback
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

        try {
            uni.showLoading({ title: '正在导入...' })

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
                uni.showToast({ title: '导入成功', icon: 'success' })
            } else {
                throw new Error(res.message || '导入失败')
            }
        } catch (e) {
            console.error('Failed to import doc text', e)
            uni.showToast({ title: '导入失败: ' + e.message, icon: 'none' })
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
         uni.showToast({ title: '无权移除该成员', icon: 'none' })
         return
      }
      uni.showModal({
        title: '确认移除',
        content: `确定要将 ${member.displayName} 移出项目吗？`,
        success: async (res) => {
          if (res.confirm) {
            try {
              await removeProjectMember(this.projectId, member.userId)
              uni.showToast({ title: '已移除', icon: 'success' })
              this.loadProjectMembers()
            } catch (e) {
              console.error(e)
              uni.showToast({ title: e.message || '移除失败', icon: 'none' })
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
    // 股东大会核查「开始核查」：把 kick-off prompt 交给 AI 面板以 AGENT 模式发送，
    // 并把返回的会话 ID 绑定回核查会话（面板据此展示 RUNNING 状态）
    async handleShareholderMeetingStart({ check, prompt }) {
      const chat = this.$refs.chatInterface
      if (!chat || !chat.sendExternalPrompt) {
        uni.showToast({ title: 'AI 面板未就绪，请稍后重试', icon: 'none' })
        return
      }
      const conversationId = await chat.sendExternalPrompt(prompt)
      if (conversationId && check && check.id) {
        try {
          await bindShareholderMeetingConversation(check.id, conversationId, 'RUNNING')
        } catch (e) {
          console.error('绑定核查会话失败', e)
        }
      }
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
      // 普通文件在资源管理器、搜索或EasyVoice模式下都可见
      return this.leftPaneKey === 'files' || this.leftPaneKey === 'search' || this.leftPaneKey === 'easyvoice'
    },
    startRenameProject() {
      this.renameProjectName = this.project.name || ''
      this.isRenamingProject = true
    },
    async confirmRenameProject() {
      if (!this.renameProjectName || !this.renameProjectName.trim()) {
        uni.showToast({ title: '项目名称不能为空', icon: 'none' })
        return
      }
      try {
        await renameProject(this.projectId, this.renameProjectName.trim())
        this.project.name = this.renameProjectName.trim()
        this.isRenamingProject = false
        uni.showToast({ title: '重命名成功', icon: 'success' })
      } catch (e) {
        console.error('重命名失败', e)
        uni.showToast({ title: '重命名失败', icon: 'none' })
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
        uni.showToast({ title: '请先打开一个文档', icon: 'none' })
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
        uni.showToast({ title: '请先在文档中高亮一段文本（蓝色选区）', icon: 'none' })
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
          uni.showToast({ title: '设置超链接失败', icon: 'none' })
          return
        }
      }

      // 3) 入库：按 fileId 关联（文件移动/重命名不影响打开）
      try {
        const pid = typeof this.projectId === 'string' ? Number(this.projectId) : this.projectId
        const doc = side === 'right' ? this.activeFileRight : this.activeFileLeft
        const docWpsFileId = doc && this.isEditorOpenableFile(doc) ? (doc.wpsFileId || '') : ''
        if (!docWpsFileId) throw new Error('文档未就绪')
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
        uni.showToast({ title: '已建立关联', icon: 'success' })
      } catch (e) {
        uni.showToast({ title: e.message || '关联失败', icon: 'none' })
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
        if (!file) throw new Error('文件不存在')
        const old = this.focusedPane
        this.focusedPane = side === 'right' && this.splitMode ? 'right' : 'left'
        this.openFile(file)
        this.focusedPane = old
      } catch (e) {
        uni.showToast({ title: e.message || '打开失败', icon: 'none' })
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
          uni.showToast({ title: '复制失败：未返回新文件', icon: 'none' })
        }
      } catch (e) {
        console.error('复制文件失败', e)
        uni.showToast({ title: e.message || '复制失败', icon: 'none' })
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
    onBrowserUrlChange(pane, url) {
      const active = pane === 'left' ? this.activeFileLeft : this.activeFileRight
      if (active && this.isBrowserTab(active)) {
        active.url = url
        // 标签名称：尽量短（host）
        try {
          const u = new URL(url)
          active.name = u.host || url
        } catch (e) {
          active.name = url
        }
        this.$forceUpdate()

        // Track URL Session (flush previous, start new)
        const meta = this.project && this.project.name ? `Project: ${this.project.name}` : ''
        activityTracker.trackActivePage('OPEN_URL', 0, url, meta)
      }
    },
    onBrowserTitleChange(pane, title) {
      const active = pane === 'left' ? this.activeFileLeft : this.activeFileRight
      if (!active || !this.isBrowserTab(active)) return
      const t = String(title || '').trim()
      if (!t) return

      // Update session meta with title?
      // trackActivePage will flush and restart. This might be noisy if title changes often.
      // But user requested "record url and web title".
      // If we don't restart, we can't update the log meta.
      // Let's check if title is significantly different or just loaded.

      const url = active.url || ''
      const meta = (this.project && this.project.name ? `Project: ${this.project.name}. ` : '') + `Title: ${t}`
      if (url) {
          // Restart session to capture title in the new segment
          activityTracker.trackActivePage('OPEN_URL', 0, url, meta)
      }

      // 避免过长：保留前 18 字符
      active.name = t.length > 18 ? (t.slice(0, 18) + '…') : t
      this.$forceUpdate()
    },
    openBrowserTab(url = 'https://www.baidu.com', pane = null) {
      // 默认在当前聚焦窗格打开；未分屏则左侧
      const targetPane = pane ? (pane === 'right' && this.splitMode ? 'right' : 'left') : (this.splitMode ? this.focusedPane : 'left')
      const list = targetPane === 'left' ? this.leftFiles : this.rightFiles
      const idProp = targetPane === 'left' ? 'activeFileIdLeft' : 'activeFileIdRight'

      const id = `web_${Date.now()}_${Math.random().toString(36).slice(2, 8)}`
      let name = '浏览器'
      try {
        const u = new URL(url)
        name = u.host || '浏览器'
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
        this.recordingToastMessage = newState ? '开始录制工作' : '已停止录制工作'
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
           uni.showToast({ title: '请先打开一个文档', icon: 'none' })
           return
         }
         try {
           await this.libreOfficeExecutor.executeCommand('insert_at_cursor', { text: t })
           uni.showToast({ title: '已插入文档', icon: 'success' })
         } catch (e) {
           console.error(e)
           uni.showToast({ title: '插入失败', icon: 'none' })
         }
      } else if (type === 'IMAGE') {
         // #79 债已还：经执行器 insert_image 在光标处插入（data URL → UNO 图形对象）
         if (!content) return
         if (!this.libreOfficeActive || !this.libreOfficeExecutor) {
           uni.showToast({ title: '请先打开一个文档', icon: 'none' })
           return
         }
         try {
           // 剪贴板/收藏夹面板给的是图片 HTTP URL（/api/...?token=...），
           // insert_image 只认 data URL/base64，先拉字节转 data URL
           let dataUrl = content
           if (!/^data:/i.test(dataUrl)) {
             const resp = await fetch(dataUrl)
             if (!resp.ok) throw new Error('图片下载失败')
             const blob = await resp.blob()
             dataUrl = await new Promise((resolve, reject) => {
               const reader = new FileReader()
               reader.onload = () => resolve(reader.result)
               reader.onerror = () => reject(new Error('图片读取失败'))
               reader.readAsDataURL(blob)
             })
           }
           const r = await this.libreOfficeExecutor.executeCommand('insert_image', { dataUrl })
           if (!r || !r.success) throw new Error((r && r.message) || '插入图片失败')
           uni.showToast({ title: '已插入图片', icon: 'success' })
         } catch (e) {
           console.error(e)
           uni.showToast({ title: e.message || '插入图片失败', icon: 'none' })
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
          uni.showToast({ title: '请拖拽到文档区域进行关联', icon: 'none' })
          return
        }
        if (!this.libreOfficeActive || !this.libreOfficeExecutor) {
          uni.showToast({ title: '请先打开一个文档', icon: 'none' })
          return
        }
        const favId = this.webLinkDrag.favoriteId
        const host = this.webLinkDrag.sourceUrl ? (() => { try { return new URL(this.webLinkDrag.sourceUrl).host } catch (e) { return '网核' } })() : '网核'
        const ts = new Date().toLocaleString()
        const text = `【网核证据：${host}｜${ts}】`
        const bookmarkName = `WEB_EVID_${favId || Date.now()}`
        const internalUrl = this.wrapWpsInternalLink(`checkba://webfav?id=${encodeURIComponent(String(favId || ''))}&projectId=${encodeURIComponent(String(this.projectId || ''))}`)
        const r = await this.libreOfficeExecutor.executeCommand('insert_link_with_bookmark', { text, bookmarkName, url: internalUrl })
        if (!r || !r.success) throw new Error((r && r.message) || '插入失败')
        uni.showToast({ title: '已插入网核标记', icon: 'success' })
      } catch (e) {
        console.error('插入网核标记失败:', e)
        uni.showToast({ title: e.message || '插入失败', icon: 'none' })
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
    goToUserProfile() {
      uni.navigateTo({ url: '/pages/userprofile/userprofile' })
    },
    goToSystemSettings() {
      uni.navigateTo({ url: '/pages/admin/admin' })
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
    return '刚刚'
  } else if (diffMins < 60) {
    return `${diffMins}分钟前`
  } else if (diffHrs < 24) {
    return `${diffHrs}小时前`
  } else if (diffDays < 7) {
    return `${diffDays}天前`
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
        uni.showToast({ title: '重命名同步失败', icon: 'none' })
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
        fileName: context.fileName || this.activeAiFileName || '未命名文件',
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
                 uni.showToast({ title: '没有激活的上下文', icon: 'none' })
              }
           } else {
              uni.showToast({ title: '上下文已更新', icon: 'none' })
           }
        }
        return contexts
      } catch (e) {
        console.error('刷新 AI 上下文失败', e)
        if (manualTrigger) {
          uni.showToast({ title: '同步失败', icon: 'none' })
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
        name: artifactInfo.fileName || 'AI工作计划.md',
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
      return {
        async getSelectionText() {
          const r = await exec('get_selection', {})
          return (r && r.success && r.text) || ''
        },
        async listVariableFields() {
          const r = await exec('var_list', {})
          if (!r || !r.success) throw new Error((r && r.message) || '读取文档变量域失败')
          return r.fields || []
        },
        async insertTextWithDocumentField(value, scope, name) {
          const r = await exec('var_insert', { text: value == null ? '' : String(value), scope, name })
          if (!r || !r.success) throw new Error((r && r.message) || '插入文档变量域失败')
        },
        async updateDocumentField(fieldId, nextText) {
          const r = await exec('var_update', { id: fieldId, text: nextText == null ? '' : String(nextText) })
          if (!r || !r.success) throw new Error((r && r.message) || '更新文档变量域失败')
        },
        // resolver 是面板本地回调，无法跨 worker 传递：在这一侧枚举字段、逐个求值并回写。
        // 空值不回写——后端变量缺失时 resolver 返回 ''，同步不应清空文档里的内容。
        async syncAllDocumentFields(resolver) {
          const lr = await exec('var_list', {})
          if (!lr || !lr.success) throw new Error((lr && lr.message) || '读取文档变量域失败')
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
        uni.showToast({ title: '请先打开一个文档', icon: 'none' })
        return
      }
      try {
        const sel = await this.libreOfficeExecutor.executeCommand('get_selection', {})
        if (!String((sel && sel.text) || '').trim()) {
          uni.showToast({ title: '请先在文档中选择要替换的内容', icon: 'none' })
          return
        }
        await this.libreOfficeExecutor.executeCommand('replace_selection', { text: markdownToPlainText(message.content) })
        uni.showToast({ title: '已替换选区', icon: 'success' })
        return
      } catch (e) {
        console.error('替换选区失败', e)
        uni.showToast({ title: e.message || '替换失败', icon: 'none' })
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
                     uni.showToast({ title: `文件夹含${totalFiles}个文件(超出10个限制)，请减少数量`, icon: 'none' })
                     return
                 }
             }

             if (this.$refs.chatInterface) {
                 this.$refs.chatInterface.addFile(file)
             }

             // Note: Visual tag display is now handled within ChatInterface
             uni.showToast({ title: '已添加: ' + fileData.name, icon: 'none' })

        } else {
             uni.showToast({ title: '未获取到拖拽数据', icon: 'none' })
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
    onRichKeydown(e) {
       // Handle Enter: Enter = newline, Cmd/Ctrl+Enter = send
       if (e.key === 'Enter') {
          if (e.metaKey || e.ctrlKey) {
             // Cmd/Ctrl+Enter: Send message
             e.preventDefault()
             this.handleAiSend()
          }
          // Otherwise: let default behavior (newline) happen
       }
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

       // Use AI Workdeck brand colors for the tag
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
    async loadAssistants() {
      try {
          const list = await getAssistants()
          if (Array.isArray(list) && list.length > 0) {
              this.assistants = list
          } else {
              // Fallback default if needed, or keep empty
              this.assistants = [
                  { id: 'default', name: '默认助手', systemPrompt: '你是一个专业的助手。' }
              ]
          }
      } catch (e) {
          console.error('Failed to load assistants', e)
          this.assistants = [
              { id: 'default', name: '默认助手', systemPrompt: '你是一个专业的助手。' }
          ]
      }
  },
    async loadDynamicPlugins() {
      try {
        const res = await getPlugins()
        if (res && res.data) {
          // Map backend PluginMetadata to frontend plugin structure
          this.dynamicPlugins = res.data.map(p => ({
            key: `plugin-${p.id}`,
            label: p.name,
            icon: p.icon || '/static/plugin_default.png',
            activeIcon: p.icon || '/static/plugin_default.png',
            isDynamic: true,
            frontendEntry: p.frontendEntry
          }))
          console.log('Dynamic plugins loaded:', this.dynamicPlugins)
        }
      } catch (e) {
        console.error('Failed to load dynamic plugins:', e)
      }
    },

  // --- AI 相关 ---
  async initAiModel() {
      // 1. Try to recover from local storage (User Preference)
      const savedProvider = uni.getStorageSync('activeAiProvider')
      if (savedProvider) {
        this.currentModelId = savedProvider
        return
      }

      // 2. Fallback to System Default (Public Config)
      try {
        const res = await getAiConfig()
        if (res && res.activeProvider) {
          // Map backend enum to frontend model ID
          const provider = res.activeProvider.toUpperCase()
          if (provider === 'OLLAMA') {
            this.currentModelId = 'ollama'
          } else if (provider === 'GEMINI') {
            this.currentModelId = 'gemini-1.5-pro'
          }
        }
      } catch (e) {
        console.warn('Failed to load AI config, using default.', e)
      }
    },

    toggleModelDropdown() {
      this.showModelDropdown = !this.showModelDropdown
      if (this.showModelDropdown) this.showContextDropdown = false
    },
    switchModel(modelId) {
      this.currentModelId = modelId
      this.showModelDropdown = false
      // Persistence: Remember user's choice
      uni.setStorageSync('activeAiProvider', modelId)
    },
    // --- AI 对话 ---
    scrollToBottom() {
      this.scrollTop = this.scrollTop + 1 // trigger value change for watcher if needed?
      // Actually uni-app scroll-top works better when set to a large value
      this.$nextTick(() => {
        this.scrollTop = 99999
      })
    },

    async handleAiSend() {
      if (this.aiLoading || !this.aiInput.trim()) return

      // Logic: Send message to backend
      this.aiLoading = true
      // Push user message immediately for responsiveness
      const tempId = Date.now()
      const text = this.aiInput.trim()
      this.aiMessages.push({
        id: tempId,
        role: 'user',
        content: text
      })
      this.aiInput = '' // Clear input
      this.clearRichInput() // Clear rich div

      // Scroll to bottom
      this.$nextTick(() => {
        this.scrollToBottom()
      })

      try {
        // Collect fresh context (List of contexts)
        const activeContexts = await this.collectAiContextForChat()

        const res = await aiChat({
          projectId: this.projectId,
          message: text,
          contexts: activeContexts, // Updated to List
          model: this.currentModelId,
          assistantId: this.currentAssistantId,
          conversationId: this.currentConversationId
        })

        // Update current conversation ID if it was new
        if (res && res.conversationId) {
             this.currentConversationId = res.conversationId
        }

        const responseText = res.response || ''

        this.aiMessages.push({
          id: Date.now() + 1,
          role: 'assistant',
          content: responseText
        })
      } catch (e) {
        console.error('AI Chat Error:', e)
        this.aiMessages.push({
          id: Date.now() + 1,
          role: 'assistant',
          content: `出错啦：${e.message || '网络异常'}`
        })
      } finally {
        this.aiLoading = false
      }
    },

    // --- AI 导出为 Word ---
    async openExportDialog(message) {
      if (!this.projectId) {
        uni.showToast({ title: '项目未就绪', icon: 'none' })
        return
      }
      if (!message || !message.content) {
        uni.showToast({ title: '暂无可导出内容', icon: 'none' })
        return
      }
      this.exportSourceMessage = message
      // 默认文件名：项目名 + 时间
      const baseName = this.project.name || 'AI回复'
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
        uni.showToast({ title: '加载文件夹失败', icon: 'none' })
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
        uni.showToast({ title: '项目未就绪', icon: 'none' })
        return
      }
      let name = (this.exportFileName || '').trim()
      if (!name) {
        uni.showToast({ title: '请输入文件名', icon: 'none' })
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
        uni.showToast({ title: '文档已生成', icon: 'none' })
      } catch (e) {
        console.error('导出 Word 失败', e)
        uni.showToast({ title: e.message || '导出失败', icon: 'none' })
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
    handleInputKeydown(e) {
      // Enter to send, Shift + Enter to newline
      this.checkKeySend(e)
    },

    handleWrapperKeydown(e) {
      // Capture phase backup
      this.checkKeySend(e)
    },

    checkKeySend(e) {
      // Enter to send, Shift + Enter to newline
      const isEnter = e.key === 'Enter' || e.keyCode === 13

      if (isEnter) {
        if (!e.shiftKey) {
          // Enter only: Send
          e.preventDefault()
          e.stopPropagation()
          if (!this.aiLoading && this.aiInput.trim()) {
            this.handleAiSend()
          }
        }
        // Shift + Enter: Default behavior (newline), do nothing
      }
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
              title: item.title ? item.title.replace(/<[^>]+>/g, '').trim() : (item.lastMessage ? (item.lastMessage.substring(0, 20) + (item.lastMessage.length > 20 ? '...' : '')) : '新对话'),
              updatedAt: item.updatedAt,
              lastMessage: item.lastMessage ? item.lastMessage.replace(/<[^>]+>/g, '').replace(/\s+/g, ' ').substring(0, 60) + (item.lastMessage.length > 60 ? '...' : '') : '',
              conversationId: item.conversationId,
              runStatus: item.runStatus || null,
              unread: this.unreadConversations.includes(item.conversationId)
          }))
      } catch (e) {
        console.error('Fetch history failed', e)
        if (!quiet) uni.showToast({ title: '加载历史失败', icon: 'none' })
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
                this.aiMessages = (msgs || []).map(m => ({
                    id: m.id,
                    role: m.role ? m.role.toLowerCase() : 'user',
                    content: m.content
                }))
            }
            this.showHistoryDrawer = false
        } catch (e) {
            console.error('Load chat failed', e)
            uni.showToast({ title: '加载对话失败', icon: 'none' })
        } finally {
            this.loadingHistory = false
        }
    },
    // 会话状态 → 状态点样式类。黄=等用户（暂停/待审批）、蓝=后台跑完未读、
    // 动画绿=运行中、红=出错；无任务/已读完成不打点。
    convDotClass(chat) {
        if (!chat) return ''
        if (chat.runStatus === 'RUNNING') return 'dot-running'
        if (chat.runStatus === 'PAUSED' || chat.runStatus === 'AWAITING_APPROVAL') return 'dot-attention'
        if (chat.runStatus === 'ERROR') return 'dot-error'
        if (chat.unread) return 'dot-unread'
        return ''
    },
    convStatusLabel(chat) {
        if (!chat) return ''
        if (chat.runStatus === 'RUNNING') return '运行中'
        if (chat.runStatus === 'PAUSED') return '待继续'
        if (chat.runStatus === 'AWAITING_APPROVAL') return '待审批'
        if (chat.runStatus === 'ERROR') return '出错'
        if (chat.unread) return '已完成'
        return ''
    },
    toggleAssistantMenu() {
        this.showAssistantMenu = !this.showAssistantMenu
    },
    switchAssistant(id) {
        this.currentAssistantId = id
        this.showAssistantMenu = false
        const ast = this.assistants.find(a => a.id === id)
        if (ast) {
             uni.showToast({ title: `已切换为：${ast.name}`, icon: 'none' })
             // Inject system prompt notification (hidden or visible)
             this.aiMessages.push({
                 id: Date.now(),
                 role: 'system', // Display as special notice
                 content: `助手切换为：${ast.name}`
             })
        }
    },
    // Helper for icons
    getAssistantIcon(id) {
        // User requested to remove emoji icons
        return ''
    },
    openAssistantConfig(assistant) {
        if (!assistant) return
        this.editingAssistant = JSON.parse(JSON.stringify(assistant)) // Deep copy
        this.showAssistantMenu = false // Close menu when opening dialog
        this.showAssistantConfigDialog = true
    },
    closeAssistantConfigDialog() {
        this.showAssistantConfigDialog = false
        this.editingAssistant = null
    },
    saveAssistantConfig() {
        if (!this.editingAssistant) return

        // Update local list
        const idx = this.assistants.findIndex(a => a.id === this.editingAssistant.id)
        if (idx !== -1) {
             this.assistants.splice(idx, 1, this.editingAssistant)
             // Sync to backend would happen here
             uni.showToast({ title: '配置已保存', icon: 'success' })
        }
        this.closeAssistantConfigDialog()
    },
  }
}
</script>

<!-- 样式单一来源：./project-overview.scss（Phase 0 外置）。新增样式写进该文件，不要在此处内联。 -->
<style lang="scss" scoped src="./project-overview.scss"></style>
