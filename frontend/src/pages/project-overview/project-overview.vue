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
            <view class="project-status-badge">
              <text class="status-text">进行中</text>
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
        <!-- 顶部工具区（IDE 风格）：分屏 / 浏览器 / 摘录 / AI / 工具 -->
        <view class="header-tools" v-if="!isClientView">
          <!-- 1. Left Sidebar -->
          <view
            class="top-bar-btn"
            :class="{ active: !sidebarCollapsed }"
            @tap="toggleSidebar"
            :title="sidebarCollapsed ? '展开左侧栏' : '收起左侧栏'"
            @mouseenter="hoverLeft = true"
            @mouseleave="hoverLeft = false"
          >
            <image
              :src="(!sidebarCollapsed || hoverLeft) ? '/static/left-bar_selected.png' : '/static/left-bar.png'"
              class="tool-icon-img"
              mode="aspectFit"
            />
          </view>

          <!-- 2. Bottom Sidebar (Tools Panel) -->
          <view
            class="top-bar-btn"
            :class="{ active: showToolsPanel }"
            @tap="toggleToolsPanel"
            title="常用工具"
            @mouseenter="hoverBottom = true"
            @mouseleave="hoverBottom = false"
          >
            <image
              :src="(showToolsPanel || hoverBottom) ? '/static/bottom-bar_selected.png' : '/static/bottom-bar.png'"
              class="tool-icon-img"
              mode="aspectFit"
            />
          </view>

          <!-- 3. Right Sidebar (AI Panel) -->
          <view
            class="top-bar-btn"
            :class="{ active: showAiPanel }"
            @tap="toggleAiPanel"
            title="AI 助手"
            @mouseenter="hoverRight = true"
            @mouseleave="hoverRight = false"
          >
            <image
              :src="(showAiPanel || hoverRight) ? '/static/right-bar_selected.png' : '/static/right-bar.png'"
              class="tool-icon-img"
              mode="aspectFit"
            />
          </view>

          <!-- 4. Split View -->
          <view
            class="top-bar-btn split-btn"
            :class="{ active: splitMode }"
            @tap="toggleSplitMode"
            :title="splitMode ? '关闭分屏' : '开启分屏'"
            @mouseenter="hoverSplit = true"
            @mouseleave="hoverSplit = false"
          >
             <image
              :src="(splitMode || hoverSplit) ? '/static/square_selected.png' : '/static/square_split_2x1.png'"
              class="tool-icon-img"
              mode="aspectFit"
            />
          </view>

          <!-- 5. Screenshot (OCR) -->
          <view
            class="top-bar-btn"
            @tap="startOcrCapture"
            title="截图摘录（OCR）"
            @mouseenter="hoverCapture = true"
            @mouseleave="hoverCapture = false"
          >
             <image
              :src="hoverCapture ? '/static/screenshop_selected.png' : '/static/screenshop.png'"
              class="tool-icon-img"
              mode="aspectFit"
            />
          </view>

          <!-- 6. Browser (New Web) -->
          <view
            class="top-bar-btn"
            @tap="openBrowserTab()"
            title="浏览器"
            @mouseenter="hoverWeb = true"
            @mouseleave="hoverWeb = false"
          >
             <image
              :src="hoverWeb ? '/static/new-web_selected.png' : '/static/new-web.png'"
              class="tool-icon-img"
              mode="aspectFit"
            />
          </view>

          <!-- Activity Record Toggle -->
          <view
            class="top-bar-btn"
            :class="{ active: isRecording }"
            @tap="toggleRecording"
            title="录制活动"
            @mouseenter="hoverRecord = true"
            @mouseleave="hoverRecord = false"
          >
            <image
              :src="(isRecording || hoverRecord) ? '/static/record-rec_selected.png' : '/static/record-rec.png'"
              class="tool-icon-img"
              mode="aspectFit"
            />
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
          <image
            :src="((leftPaneKey === 'staging' && !sidebarCollapsed) || stagingPinned) ? '/static/temporary_selected.png' : '/static/temporary.png'"
            class="rail-icon-img"
            mode="aspectFit"
          />
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
                  <text class="hint">⚠️ 注意：如果设置了自定义 Prompt，预设 Prompt 将被忽略（User Prompt Prevails）。</text>
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
        @mouseenter="hoverNewFile = true"
        @mouseleave="hoverNewFile = false"
      >
        <image
          :src="hoverNewFile ? '/static/new-document.png' : '/static/new-document_unselected.png'"
          class="tool-icon-img"
          mode="contain"

        />
      </view>

      <!-- 2. 新建文件夹 (普通模式) -->
      <view
        v-if="!fileBatchMode"
        class="icon-btn mini"
        @tap="onFileTreeQuickAction('newFolder')"
        title="新建文件夹"
        @mouseenter="hoverNewFolder = true"
        @mouseleave="hoverNewFolder = false"
      >
        <image
          :src="hoverNewFolder ? '/static/icon_new_folder.png' : '/static/icon_new_folder_unselected.png'"
          class="tool-icon-img"
          mode="contain"

        />
      </view>

      <!-- 3. 批量选择开关 (始终显示) -->
      <view
        class="icon-btn mini"
        :class="{ active: fileBatchMode }"
        @tap="toggleFileBatchMode"
        title="批量选择"
        @mouseenter="hoverBatchSelect = true"
        @mouseleave="hoverBatchSelect = false"
      >
        <image
          :src="fileBatchMode || hoverBatchSelect ? '/static/batch_select.png' : '/static/batch_select_unselected.png'"
          class="tool-icon-img"
          mode="contain"

        />
      </view>

      <!-- 4. 上传 (普通模式) -->
      <view
        v-if="!fileBatchMode"
        class="icon-btn mini"
        @tap="onFileTreeQuickAction('upload')"
        title="上传文件"
        @mouseenter="hoverUpload = true"
        @mouseleave="hoverUpload = false"
      >
        <image
          :src="hoverUpload ? '/static/upload.png' : '/static/upload_unselected.png'"
          class="tool-icon-img"
          mode="contain"

        />
      </view>

      <!-- 5. 下载 (批量模式) -->
      <view
        v-if="fileBatchMode"
        class="icon-btn mini"
        @tap="onFileTreeQuickAction('download')"
        title="批量下载"
        :class="{ disabled: checkedFileCount <= 0 }"
        @mouseenter="hoverDownload = true"
        @mouseleave="hoverDownload = false"
      >
        <image
          :src="hoverDownload ? '/static/download_selected.png' : '/static/download.png'"
          class="tool-icon-img"
          mode="contain"

        />
      </view>

      <!-- 6. 排序 (普通模式) -->
      <view
        v-if="!fileBatchMode"
        class="icon-btn mini"
        @tap="onFileTreeQuickAction('sort')"
        title="排序"
        @mouseenter="hoverSort = true"
        @mouseleave="hoverSort = false"
      >
        <image
          :src="hoverSort ? '/static/sort.png' : '/static/sort_unselected.png'"
          class="tool-icon-img"
          mode="contain"

        />
      </view>

      <!-- 7. 复制 (批量模式) -->
      <view
        v-if="fileBatchMode"
        class="icon-btn mini"
        @tap="onFileTreeQuickAction('copy')"
        title="批量复制"
        :class="{ disabled: checkedFileCount <= 0 }"
        @mouseenter="hoverCopy = true"
        @mouseleave="hoverCopy = false"
      >
        <image
          :src="hoverCopy ? '/static/copy_selected.png' : '/static/copy.png'"
          class="tool-icon-img"
          mode="contain"

        />
      </view>

      <!-- 8. 回收站 / 删除 (始终显示，功能不同) -->
      <view
        class="icon-btn mini"
        @tap="onFileTreeQuickAction('recycleBin')"
        :title="fileBatchMode ? '删除选中' : '回收站'"
        @mouseenter="hoverRecycleBin = true"
        @mouseleave="hoverRecycleBin = false"
      >
        <image
          :src="hoverRecycleBin ? '/static/recycle-bin.png' : '/static/recycle-bin_unselected.png'"
          class="tool-icon-img"
          mode="contain"

        />
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
          />
          <DdFilesPanel
            v-else-if="leftPaneKey === 'dd-files'"
            :project-id="projectId"
            :current-user="currentUser"
            @open-request="handleOpenDdRequest"
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
                      <text class="tab-icon">{{ file.tabType === 'web' ? '🌐' : getFileIcon(file.fileType) }}</text>
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
                      <text class="tab-icon">{{ file.tabType === 'web' ? '🌐' : getFileIcon(file.fileType) }}</text>
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
                  <image src="/static/iconmark.png" class="empty-state-img" mode="aspectFit" />
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
                      variant="default"
                      :file="file"
                      @ready="onLibreReady($event, 'left', file.id)"
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
                    <DdRequestEditor
                      v-else-if="isDdRequest(activeFileLeft)"
                      :request-id="activeFileLeft.requestId"
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
                    <image src="/static/iconmark.png" class="empty-state-img" mode="aspectFit" />
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
                      variant="default"
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
                    <DdRequestEditor
                      v-else-if="isDdRequest(activeFileRight)"
                      :request-id="activeFileRight.requestId"
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
                    <image src="/static/iconmark.png" class="empty-state-img" mode="aspectFit" />
                    <text class="empty-text">右侧空闲</text>
                  </view>
                </view>
              </view>

              <!-- Epic #43: embedded LibreOffice editor overlay (legacy ⌘⇧O).
                   Scoped to the document area so the AI chat panel stays usable —
                   a real backend AI command can be triggered while it's active.
                   Dormant: only mounts when explicitly opened. -->
              <view v-if="showLibreEmbed" class="libre-embed-overlay">
                <LibreOfficeEditor @ready="onLibreReady" @close="onLibreClose" @open-url="onLibreOpenUrl" />
              </view>
            </view>

            <!-- 底部常用工具面板（仅占中间工作区宽度；右侧 AI 面板优先完整显示） -->
            <view v-if="showToolsPanel" class="bottom-panel" ref="bottomPanel" :style="{ height: toolsPanelHeight + 'px' }">
              <view class="bottom-resize-handle" @touchstart="startResize('bottom', $event)" @mousedown="startResize('bottom', $event)"></view>
              <view class="panel-header panel-header-tools">
                <!-- Group: Tabs + Specific Actions -->
                <view class="header-content-left">
                  <view class="panel-tabs king-style">
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
                    :get-wps="getLibreVariableBridge"
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
                        <view style="flex:1; overflow:hidden;">
                            <text class="item-title" style="display:block; font-size:13px; color:#333; margin-bottom:2px;">{{ chat.title || '未命名对话' }}</text>
                            <text class="item-preview" style="display:block; font-size:11px; color:#999; white-space:nowrap; overflow:hidden; text-overflow:ellipsis;">{{ chat.lastMessage }}</text>
                        </view>
                        <text class="item-time" style="font-size:10px; color:#ccc; margin-left:8px;">{{ formatTime(chat.updatedAt) }}</text>
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
                <text class="folder-icon">📁</text>
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
                <text class="folder-icon">📂</text>
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
                <text class="folder-icon">📂</text>
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

                    <text class="folder-icon">📁</text>
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
              <text class="folder-icon">{{ f.isFolder ? '📁' : '📄' }}</text>
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

    </view>
  </view>
</template>

<script>
import LibreOfficeEditor from '@/components/LibreOfficeEditor.vue'
import BrowserPane from '@/components/BrowserPane.vue'
import FileTree from '@/components/FileTree.vue'
import FilePreview from '@/components/FilePreview.vue'
import VariablePanel from '@/components/VariablePanel.vue'
import ProjectFavoritesPanel from '@/components/ProjectFavoritesPanel.vue'
import FileLinkDropZone from '@/components/FileLinkDropZone.vue'
import FileStagingArea from '@/components/FileStagingArea.vue'
import PluginPane from '@/components/PluginPane.vue' // Added
import EasyVoicePane from '@/components/EasyVoicePane.vue'
import DesensitizePane from '@/components/DesensitizePane.vue'
import ClipboardPanel from '@/components/ClipboardPanel.vue'
import SearchPanel from '@/components/SearchPanel.vue'
import InviteMemberDialog from '@/components/InviteMemberDialog.vue'
import CompareDocDialog from '@/components/CompareDocDialog.vue'
import DocDiffViewer from '@/components/DocDiffViewer.vue'
import MarkdownPreview from '@/components/MarkdownPreview.vue'
import FilePickerDialog from '@/components/FilePickerDialog.vue'

import {
  getProject,
  renameProject,
  getFileDetail,
  renameFile,
  ocrRecognize,
  createProjectFavorite,
  createDocFileLink,
  getDocFileLink,
  saveClipboardText,
  saveClipboardFile,
  saveProjectVariable,
  getProjectVariables,
  getProjectFiles,
  batchCopyFiles,
  createFolder,
  batchMoveFiles,
  aiChat,
  exportAiDocx,
  getProjectMembers,
  logActivity,
  inviteClient,
  removeProjectMember,
  getAiHistory,

  getAiConversations,
  createFile,
  getApiBaseUrl,
  getAiConfig,
  getAssistants, // Added
  getPlugins, // Added
  sendEditorResult, // 编辑器命令结果回调（POST /wps-result，路由名为前后端契约）
  getFileText,
  promptFeatureNotConfigured // 功能未配置统一引导（#18 T7）
} from '@/services/api.js'
import { getCurrentUser, getSessionId } from '@/utils/auth.js'
import { FILE_BATCH_ACTIONS, FILE_TREE_QUICK_ACTIONS } from '@/config/fileActions.js'
import { WORKBENCH_TOOLS } from '@/config/tools.js'
import { OCR_ACTION_LABELS, INTERNAL_LINK_SCHEMES, WPS_INTERNAL_HTTP_LINK_BASE } from '@/config/workbenchActions.js'
import {
  LEFT_SIDEBAR_PLUGINS,
  getLeftSidebarPlugin,
  getPluginsForUser
} from '@/config/leftSidebarPlugins.js'

import { activityTracker } from '@/utils/activityTracker.js'
import DdFilesPanel from '@/components/DdFilesPanel.vue'
import DdRequestEditor from '@/components/DdRequestEditor.vue'
import ChatInterface from '@/components/ChatInterface.vue'

// 内嵌 LibreOffice 实例保活上限（每个 LOWA 实例数百 MB 内存）。超过后按
// LRU 淘汰最久未激活的实例——淘汰前自动保存（见 evictLibreInstance）。
const LIBRE_KEEPALIVE_MAX = 3

export default {
  components: {
    LibreOfficeEditor,
    BrowserPane,
    FileTree,
    FilePreview,
    VariablePanel,
    ProjectFavoritesPanel,
    FileLinkDropZone,
    FileStagingArea,
    ClipboardPanel,
    DdFilesPanel,
    DdRequestEditor,
    InviteMemberDialog,
    ChatInterface,
    MarkdownPreview,
    PluginPane, // Added
    CompareDocDialog,
    DocDiffViewer,
    EasyVoicePane,
    DesensitizePane,
    FilePickerDialog,
    SearchPanel
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

      // 布局状态
      sidebarWidth: 260, // 侧边栏宽度
      sidebarCollapsed: false,
      isCompactLayout: false,
      leftPaneKey: null, // Initialize to null to prevent premature loading
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
      showLibreEmbed: false,
      libreOfficeActive: false,
      libreOfficeExecutor: null,
      // 内嵌编辑器保活 LRU：'pane:fileId'（left:123），最近激活在前。在池中的
      // Office 标签切走时实例不销毁（v-show 隐藏），切回免重 boot/重载。
      libreLruKeys: [],
      // 后端 wps_stream_data 流式写入的本地缓冲（#79：LibreOffice 消费端）
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
      hoverBatchSelect: false,
      hoverRecycleBin: false,

      // Activity Recording State
      isRecording: false,

      // Toolbar Hover States
      hoverLeft: false,
      hoverBottom: false,
      hoverRight: false,
      hoverSplit: false,
      hoverCapture: false,
      hoverWeb: false,
      hoverRecord: false,

      // Sidebar Action Hovers
      hoverNewFile: false,
      hoverNewFolder: false,
      hoverUpload: false,
      hoverSort: false,
      hoverDownload: false,
      hoverCopy: false,

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
    this.teardownResponsiveListener()
    // Epic #43: 解绑 ⌘⇧O 嵌入式编辑器监听
    try { if (this._zetaOpenEmbedUnsub) { this._zetaOpenEmbedUnsub(); this._zetaOpenEmbedUnsub = null } } catch (e) { /* ignore */ }
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
    if (query && query.id) {
      this.projectId = Number(query.id)
      this.loadProjectInfo()
      this.loadProjectMembers()

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

      // Try to restore previous state from localStorage
      const savedKey = uni.getStorageSync(`project_${this.projectId}_leftPaneKey`)

      if (savedKey) {
          this.leftPaneKey = savedKey
      } else {
          // Set default plugin based on user role
          if (user.role === 'CLIENT') {
            this.leftPaneKey = 'dd-files'
          } else {
            this.leftPaneKey = 'files'
          }
      }

      // Restore active tabs for this project/mode
      const savedActiveTabs = uni.getStorageSync(`project_${this.projectId}_activeTabsByMode`)
      if (savedActiveTabs) {
        this.lastActiveIdsByMode = savedActiveTabs
        const mode = this.leftPaneKey || 'files'
        if (savedActiveTabs.left[mode]) this.activeFileIdLeft = savedActiveTabs.left[mode]
        if (savedActiveTabs.right[mode]) this.activeFileIdRight = savedActiveTabs.right[mode]
      }
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

    // Epic #43: ⌘⇧O 打开嵌入式 LibreOffice 编辑器覆盖层（实验）。当嵌入式编辑器已是
    // 文档的默认内联编辑器时（libreOfficePreferred），文档区已经承载它，⌘⇧O 变为
    // 空操作以避免双重挂载（两个 webview/executor）；仅在未启用内联默认时回退到覆盖层。
    try {
      if (this.isDesktopApp && window.checkbaDesktop && window.checkbaDesktop.zetaoffice && window.checkbaDesktop.zetaoffice.onOpenEmbed) {
        if (!this._zetaOpenEmbedUnsub) {
          this._zetaOpenEmbedUnsub = window.checkbaDesktop.zetaoffice.onOpenEmbed(() => {
            // 非活跃实例不响应：否则被覆盖的旧实例也置 showLibreEmbed，返回时凭空弹出编辑器
            if (!this.isActiveOverviewInstance()) return
            if (!this.libreOfficePreferred) this.showLibreEmbed = true
          })
        }
      }
    } catch (e) {
      // ignore
    }

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
    showLibreEmbed() { this.syncLibreExecutor() }
  },
  methods: {
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
    async ensureStagingFolder() {
      if (this.stagingFolderId) return
      try {
        // Changed from .stagezone to __staging_area__ to avoid dotfile filtering
        const folderName = '__staging_area__'
        console.log('EnsureStaging: Fetching root files...')
        const response = await getProjectFiles(this.projectId, null)

        // Normalize response: API returns { code: 0, data: [...] } or possibly just array
        const files = Array.isArray(response) ? response : (response?.data || [])
        console.log('EnsureStaging: Got files count:', files.length, 'Looking for:', folderName)

        let folder = files.find ? files.find(f => f.name === folderName) : null
        console.log('EnsureStaging: Found folder?', folder ? folder.id : null)

        if (!folder) {
          console.log('EnsureStaging: Creating folder...')
          try {
             const createResp = await createFolder(this.projectId, null, folderName)
             // createFolder may also return wrapped response
             folder = createResp?.data || createResp
          } catch(err) {
             console.error('EnsureStaging: Create failed:', err)

             // Retry: Maybe it exists now (concurrency) or previous fetch missed it?
             // Or maybe create failed because it already exists.
             console.log('EnsureStaging: Retrying fetch...')
             const retryResponse = await getProjectFiles(this.projectId, null)
             const retryFiles = Array.isArray(retryResponse) ? retryResponse : (retryResponse?.data || [])
             folder = retryFiles.find ? retryFiles.find(f => f.name === folderName) : null
          }
        }

        if (folder) {
           this.stagingFolderId = folder.id
           console.log('EnsureStaging: stagingFolderId set to:', folder.id)
        }
      } catch (e) {
        console.error('Failed to ensure staging folder:', e)
      }
    },
    async loadStagingFiles() {
      console.log('LoadStaging: Starting...')
      if (!this.stagingFolderId) await this.ensureStagingFolder()
      if (!this.stagingFolderId) {
          console.warn('LoadStaging: No folder ID, aborting.')
          return
      }
      try {
        console.log('LoadStaging: Fetching files for ID:', this.stagingFolderId)
        const response = await getProjectFiles(this.projectId, this.stagingFolderId)
        // Normalize response: API returns { code: 0, data: [...] } or possibly just array
        const files = Array.isArray(response) ? response : (response?.data || [])
        console.log('LoadStaging: Got files count:', files.length)
        this.stagingFiles = files
        // Debug: log file names
        if (files.length > 0) {
          console.log('LoadStaging: Files:', files.map(f => f.name).join(', '))
        }
      } catch (e) {
        console.error('Failed to load staging files:', e)
        uni.showToast({ title: '加载暂存区文件失败', icon: 'none' })
      }
    },
    async onStagingDrop(files) {
      console.log('onStagingDrop received:', files)

      // Critical Fix: Ensure files is an array.
      // If event object passed accidentally, return.
      if (!files) return

      // Check if this is a native DOM Event object (not a Vue emitted payload)
      // This can happen if the native @drop event bubbles up
      if (files instanceof Event || (files.type && files.target && files.currentTarget)) {
        // Silently ignore - the actual files should be processed by FileStagingArea's onDrop
        return
      }

      if (!Array.isArray(files)) {
         // Try to recover if it's a single file object
         if (files.id && files.name) {
             files = [files]
         } else {
             console.warn('onStagingDrop: Unexpected argument format, ignoring:', typeof files)
             return
         }
      }

      if (files.length === 0) return
      if (!this.stagingFolderId) await this.ensureStagingFolder()

      const fileIds = files.map(f => f.id)

      // 记录每个文件进入暂存区前的原始 parentId
      for (const file of files) {
        if (file.id && file.parentId !== undefined) {
          this.stagingOriginalParents[file.id] = file.parentId
        }
      }

      try {
        // Move to staging folder
        await batchMoveFiles(this.projectId, fileIds, this.stagingFolderId)

        // Reload staging files
        await this.loadStagingFiles()
        // Reload file tree (to remove from original location)
        if (this.$refs.fileTree) {
            this.$refs.fileTree.loadFiles()
        }

        // Auto-pin
        this.stagingPinned = true
        uni.showToast({ title: '已加入暂存区', icon: 'success' })
      } catch (e) {
        console.error('Failed to move files to staging:', e)
        uni.showToast({ title: '加入暂存区失败', icon: 'none' })
      }
    },
    handleStagingClear() {
       // Optional: Move all back to root? Or just clear list (which creates orphans in .stagezone)?
       // User didn't specify, but "Clear" usually means empty the list.
       // Logic: Move all files in staging back to root.
       if (this.stagingFiles.length === 0) return
       const ids = this.stagingFiles.map(f => f.id)

       batchMoveFiles(this.projectId, ids, null).then(() => {
          this.loadStagingFiles()
          this.$refs.fileTree.loadFiles()
       })
    },
    async handleStagingRemove(id) {
       // 恢复到文件原来的目录，如果找不到则移动到根目录
       if (!this.stagingFolderId) return

       // 获取原始 parentId（如果有记录的话）
       const originalParentId = this.stagingOriginalParents[id]
       // 注意：originalParentId 可能是 null（原本就在根目录）或 undefined（未记录）
       // 两者都应该移动到根目录
       const targetParentId = originalParentId !== undefined ? originalParentId : null

       try {
         await batchMoveFiles(this.projectId, [id], targetParentId)
         // 清理该文件的原始目录记录
         delete this.stagingOriginalParents[id]
         await this.loadStagingFiles()
         this.$refs.fileTree.loadFiles()
       } catch (e) {
         console.error('Failed to remove from staging:', e)
         // 如果恢复失败（可能原目录已删除），尝试移动到根目录
         if (targetParentId !== null) {
           try {
             await batchMoveFiles(this.projectId, [id], null)
             delete this.stagingOriginalParents[id]
             await this.loadStagingFiles()
             this.$refs.fileTree.loadFiles()
             uni.showToast({ title: '原目录已不存在，已移至根目录', icon: 'none' })
             return
           } catch (e2) {
             console.error('Fallback to root also failed:', e2)
           }
         }
         uni.showToast({ title: '移出暂存区失败', icon: 'none' })
       }
    },
    handleStagingCompare(files) {
        if (!files || files.length !== 2) return;
        this.onCompareDocumentsRequest(files);
    },
    handleStagingOpen(file) {
      if (!file) return
      this.openFile(file)
    },
    handleStagingCollapse() {
      // User explicitly collapsed staging area
      this.stagingPinned = false
      this.stagingManuallyCollapsed = true
    },

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
    async applyDesktopOcrSelection(payload) {
      if (!payload || !payload.dataUrl || !payload.selection) return
      // 选区完成后再隐藏 BrowserView：此时用户不需要看真实网页了
      try {
        if (window.checkbaDesktop && window.checkbaDesktop.browser && window.checkbaDesktop.browser.setViewsVisible) {
          await window.checkbaDesktop.browser.setViewsVisible({ visible: false })
        }
      } catch (e) {
        // ignore
      }

      this.ocrText = ''
      this.ocrImageDataUrl = ''
      this.ocrOverlaySelecting = false
      this.ocrActionBar = { visible: false, x: 0, y: 0 }
      this.showOcrOverlay = true
      this.bindOcrHotkeys()
      this.bindOcrGlobalListeners()

      this.ocrSourceUrl = payload.url ? String(payload.url) : (this.ocrSourceUrl || '')
      const b = payload.bounds || null
      const hostRect = b ? { x: Number(b.x) || 0, y: Number(b.y) || 0, width: Number(b.width) || 0, height: Number(b.height) || 0 } : null
      this.ocrHostRect = hostRect
      await this.ocrSetFrameFromDataUrl(String(payload.dataUrl), hostRect)

      const s = payload.selection
      this.ocrSel = { x1: Number(s.x1) || 0, y1: Number(s.y1) || 0, x2: Number(s.x2) || 0, y2: Number(s.y2) || 0 }

      try {
        this.ocrImageDataUrl = this.cropOcrSelection()
        const left = Math.min(this.ocrSel.x1, this.ocrSel.x2)
    const right = Math.max(this.ocrSel.x1, this.ocrSel.x2)
    const bottom = Math.max(this.ocrSel.y1, this.ocrSel.y2)
    const centerX = (left + right) / 2
    this.ocrActionBar = { visible: true, x: centerX, y: bottom + 12 }
      } catch (e) {
        console.error('截图裁剪失败:', e)
      }
    },
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
    toggleLeftPane(key) {
      if (key === 'staging') {
        // Toggle staging visibility
        if (this.showStagingArea) {
          // Currently showing, collapse it
          this.stagingPinned = false
          this.stagingManuallyCollapsed = true
        } else {
          // Currently hidden, expand it
          this.stagingPinned = true
          this.stagingManuallyCollapsed = false
          this.sidebarCollapsed = false
        }
        return
      }

      // 记录当前活跃 tab 到当前模式
      const oldKey = this.leftPaneKey
      if (oldKey) {
        this.lastActiveIdsByMode.left[oldKey] = this.activeFileIdLeft
        this.lastActiveIdsByMode.right[oldKey] = this.activeFileIdRight
      }

      if (this.leftPaneKey === key) {
        this.sidebarCollapsed = !this.sidebarCollapsed
      } else {
        this.leftPaneKey = key
        this.sidebarCollapsed = false

        // Check if it's a dynamic plugin and open its tab
        const plugin = this.dynamicPlugins.find(p => p.key === key)
        if (plugin) {
          this.openFile({
            id: plugin.key,
            name: plugin.label,
            fileType: 'plugin',
            frontendEntry: plugin.frontendEntry
          })
        }

        // 恢复新模式下的活跃 tab
        const savedLeft = this.lastActiveIdsByMode.left[key]
        const savedRight = this.lastActiveIdsByMode.right[key]

        if (savedLeft) {
          this.activeFileIdLeft = savedLeft
        } else {
          // 如果新模式没有记录，且当前 active 的 tab 在新模式下不可见，则设为 null
          const curLeft = this.leftFiles.find(f => f.id === this.activeFileIdLeft)
          if (curLeft && !this.isTabVisible(curLeft)) {
            // 尝试找一个在新模式下可见的 tab
            const firstVisible = this.leftFiles.find(f => this.isTabVisible(f))
            this.activeFileIdLeft = firstVisible ? firstVisible.id : null
          }
        }

        if (savedRight) {
          this.activeFileIdRight = savedRight
        } else {
          const curRight = this.rightFiles.find(f => f.id === this.activeFileIdRight)
          if (curRight && !this.isTabVisible(curRight)) {
            const firstVisible = this.rightFiles.find(f => this.isTabVisible(f))
            this.activeFileIdRight = firstVisible ? firstVisible.id : null
          }
        }
      }

      // Persistence
      if (this.projectId) {
        uni.setStorageSync(`project_${this.projectId}_leftPaneKey`, key)
        this.saveActiveIdsByMode()
      }
    },
    saveActiveIdsByMode() {
      if (this.projectId) {
        uni.setStorageSync(`project_${this.projectId}_activeTabsByMode`, this.lastActiveIdsByMode)
      }
    },
    onLeftPluginClick(key) {
      // 兼容旧调用（若仍有地方使用）
      this.toggleLeftPane(key)
    },
    getOcrPoint(e) {
      const te = e && (e.touches && e.touches[0])
      const ce = e && (e.changedTouches && e.changedTouches[0])
      const p = te || ce || e || {}
      return { x: Number(p.clientX || p.pageX || 0), y: Number(p.clientY || p.pageY || 0) }
    },
    getOcrCanvasEl() {
      // uniapp H5 下 ref 可能不是原生 canvas；兜底用 id 取真实 DOM
      let c = this.$refs.ocrCanvas
      if (c && c.$el) c = c.$el
      if (c && typeof c.getContext === 'function') return c
      // #ifdef H5
      const dom = document.getElementById('ocr-overlay-canvas')
      if (dom && typeof dom.getContext === 'function') return dom
      // #endif
      return null
    },
    ocrLog(...args) {
      if (!this.ocrDebug) return
      // eslint-disable-next-line no-console
      console.log('[OCR]', ...args)
    },
    // uniapp H5 下：用 document capture 事件接管拖拽，避免 view 合成事件不触发
    bindOcrGlobalListeners() {
      // #ifdef H5
      if (this._ocrGlobalBound) return
      this._ocrGlobalBound = true
      this._ocrMoveLogTs = 0

      this._ocrDocDown = (ev) => {
        if (!this.showOcrOverlay) return
        const p0 = this.getOcrPoint(ev)
        this.ocrLastPointer = p0
        // 右键不处理
        if (ev && ev.button !== undefined && ev.button !== 0) return
        // actionbar 内点击不触发框选
        if (ev && ev.target && ev.target.closest && ev.target.closest('.ocr-actionbar')) return
        this.onOcrOverlayDown(ev)
        if (ev && ev.cancelable) ev.preventDefault()
      }
      this._ocrDocMove = (ev) => {
        if (!this.showOcrOverlay) return
        const p0 = this.getOcrPoint(ev)
        this.ocrLastPointer = p0
        this.onOcrOverlayMove(ev)
        const now = Date.now()
        if (this.ocrDebug && now - this._ocrMoveLogTs > 250) {
          const p = this.getOcrPoint(ev)
          this.ocrLog('move', p.x, p.y, 'selecting=', this.ocrOverlaySelecting)
          this._ocrMoveLogTs = now
        }
        if (ev && ev.cancelable) ev.preventDefault()
      }
      this._ocrDocUp = (ev) => {
        if (!this.showOcrOverlay) return
        const p0 = this.getOcrPoint(ev)
        this.ocrLastPointer = p0
        this.onOcrOverlayUp(ev)
        if (ev && ev.cancelable) ev.preventDefault()
      }

      document.addEventListener('mousedown', this._ocrDocDown, true)
      document.addEventListener('mousemove', this._ocrDocMove, true)
      document.addEventListener('mouseup', this._ocrDocUp, true)
      document.addEventListener('touchstart', this._ocrDocDown, { capture: true, passive: false })
      document.addEventListener('touchmove', this._ocrDocMove, { capture: true, passive: false })
      document.addEventListener('touchend', this._ocrDocUp, { capture: true, passive: false })
      // #endif
    },
    unbindOcrGlobalListeners() {
      // #ifdef H5
      if (!this._ocrGlobalBound) return
      document.removeEventListener('mousedown', this._ocrDocDown, true)
      document.removeEventListener('mousemove', this._ocrDocMove, true)
      document.removeEventListener('mouseup', this._ocrDocUp, true)
      document.removeEventListener('touchstart', this._ocrDocDown, true)
      document.removeEventListener('touchmove', this._ocrDocMove, true)
      document.removeEventListener('touchend', this._ocrDocUp, true)
      this._ocrDocDown = null
      this._ocrDocMove = null
      this._ocrDocUp = null
      this._ocrGlobalBound = false
      // #endif
    },
    bindClipboardListener() {
      // #ifdef H5
      if (this._clipboardBound) return
      const user = getCurrentUser()
      if (!user) return
      this._clipboardBound = true
      // 统一的“写库 + 立即更新 UI”入口：避免 copy 事件多次触发导致重复入库
      // 去重状态挂 window 而非组件实例：本页经 navigateTo 反复进入时页面栈里会存在
      // 多个实例，每个实例都绑定过监听；实例级去重挡不住“一次复制、多实例各入库一条”
      if (typeof window !== 'undefined' && !window.__checkbaClipLastText) {
        window.__checkbaClipLastText = { ts: 0, text: '' }
      }
      const recordClipboardOnce = async (rawText, source = 'doc') => {
        // 1. Electron Payload Object (IMAGE / FILE)
        if (rawText && typeof rawText === 'object') {
           const payload = rawText
           // 同一事件（ts 相同）会被页面栈里每个实例的监听器各收到一次，只处理第一次；
           // 图片/文件没有下方文本那样的内容去重，全靠这里挡住重复入库
           const evKey = String(payload.type || '') + '_' + String(payload.ts || '')
           if (typeof window !== 'undefined') {
             if (window.__checkbaClipLastEventKey === evKey) return null
             window.__checkbaClipLastEventKey = evKey
           }
           if (payload.type === 'TEXT') {
             return await recordClipboardOnce(payload.text, source)
           } else if (payload.type === 'IMAGE' && payload.data) {
             try {
               const arr = payload.data.split(',')
               const match = arr[0].match(/:(.*?);/)
               const mime = match ? match[1] : 'image/png'
               // Decode base64
               const bstr = atob(arr[1])
               let n = bstr.length
               const u8arr = new Uint8Array(n)
               while (n--) { u8arr[n] = bstr.charCodeAt(n) }
               const blob = new Blob([u8arr], { type: mime })

               // Create File object
               const f = new File([blob], `image_${Date.now()}.png`, { type: mime })

               const res = await saveClipboardFile({ file: f }, 'IMAGE')
               const saved = (res && res.data) ? res.data : res
               this.onClipboardSaved(saved)
               uni.showToast({ title: '已捕获图片', icon: 'success' })
               return saved
             } catch (e) {
               console.error('Image upload failed', e)
               return null
             }
           } else if (payload.type === 'FILE' && payload.filePath) {
             try {
               // Must verify API exists (Electron only)
                // eslint-disable-next-line
               if (window.checkbaDesktop && window.checkbaDesktop.utils && window.checkbaDesktop.utils.readFile) {
                  // eslint-disable-next-line
                  const resp = await window.checkbaDesktop.utils.readFile(payload.filePath)
                  if (resp && resp.ok && resp.data) {
                     // resp.data is usually Uint8Array or serialized Buffer
                     const u8arr = new Uint8Array(resp.data)

                     const name = payload.filePath.split(/[/\\]/).pop() || 'file'
                     const blob = new Blob([u8arr])
                     const f = new File([blob], name)


                     const res = await saveClipboardFile({ file: f }, 'FILE')
                     const saved = (res && res.data) ? res.data : res
                     this.onClipboardSaved(saved)
                     uni.showToast({ title: '已捕获文件', icon: 'success' })
                     return saved
                  }
               }
             } catch (e) {
               console.error('File upload failed', e)
             }
             return null
           }
           return null
        }

        // 2. Normal Text Logic
        let t = (rawText || '').trim()
        if (
          !t &&
          typeof navigator !== 'undefined' &&
          navigator.clipboard &&
          typeof navigator.clipboard.readText === 'function'
        ) {
          try {
            const latest = await navigator.clipboard.readText()
            t = (latest || '').trim()
          } catch (clipErr) {
            // ignore permission errors
          }
        }
        if (!t) return null

        const now = Date.now()
        // 仅用于防止同一次用户动作被多路监听重复触发（不是业务去重）
        const lastText = (typeof window !== 'undefined' && window.__checkbaClipLastText) || { ts: 0, text: '' }
        if (lastText.text === t && now - (lastText.ts || 0) < 600) {
          return null
        }
        if (typeof window !== 'undefined') {
          window.__checkbaClipLastText = { ts: now, text: t, source }
        }

        try {
          const res = await saveClipboardText(t)
          const saved = (res && res.data) ? res.data : res
          this.onClipboardSaved(saved)
          return saved
        } catch (saveErr) {
          console.error('记录剪贴板失败:', saveErr)
          return null
        }
      }
      this._recordClipboardOnce = recordClipboardOnce

      // Desktop：由 Electron 主进程捕获 copy/cut，并直接推送剪贴板文本（更稳定，不依赖浏览器权限）
      if (this.isDesktopApp && window.checkbaDesktop && window.checkbaDesktop.clipboard) {
        try {
          if (!this._desktopClipboardUnsub) {
            this._desktopClipboardUnsub = window.checkbaDesktop.clipboard.onCopied(async (payload) => {
              try {
                // Pass full payload object to support IMAGE/FILE
                await recordClipboardOnce(payload, 'desktop')
              } catch (e) {
                // ignore
              }
            })
          }
        } catch (e) {
          // ignore
        }
        return
      }

      this._pasteHandler = async (e) => {
        try {
          const cd = e && e.clipboardData
          const text = cd && typeof cd.getData === 'function' ? (cd.getData('text/plain') || '') : ''
          await recordClipboardOnce(text, 'paste')
        } catch (err) {
          // ignore
        }
      }

      this._copyHandler = async (e) => {
        try {
          let text = ''
          const cd = e && e.clipboardData
          if (cd && typeof cd.getData === 'function') {
            text = cd.getData('text/plain') || ''
          }
          if (!text && typeof window !== 'undefined' && window.getSelection) {
            const selection = window.getSelection()
            text = selection ? selection.toString() : ''
          }
          await recordClipboardOnce(text, 'copy')
        } catch (err) {
          // ignore
        }
      }

      // 键盘兜底：覆盖部分“网页内复制/iframe 内复制”导致外层收不到 copy 事件的场景（best-effort）
      this._clipboardKeydownHandler = async (e) => {
        try {
          const key = e && (e.key || '')
          const isCopy = (key === 'c' || key === 'C') && (e.metaKey || e.ctrlKey)
          if (!isCopy) return
          // 不从事件里取文本，直接尝试读剪贴板（权限失败则忽略）
          await recordClipboardOnce('', 'keydown')
        } catch (err) {
          // ignore
        }
      }

      document.addEventListener('paste', this._pasteHandler)
      document.addEventListener('copy', this._copyHandler, true)
      window.addEventListener('keydown', this._clipboardKeydownHandler, true)
      // #endif
    },
    onClipboardSaved(item) {
      // 1) 面板打开时：立即新增一张卡片（不等刷新）
      if (this.$refs.clipboardPanel && typeof this.$refs.clipboardPanel.prependItem === 'function') {
        this.$refs.clipboardPanel.prependItem(item, 80)
      } else {
        this.pendingClipboardRefresh = true
      }
      // 2) 兜底：如果面板当前可见，做一次 refresh 对齐服务端（避免时间/格式差异）
      this.triggerClipboardRefresh()
    },
    triggerClipboardRefresh() {
      if (!this.pendingClipboardRefresh) return
      // 仅在剪贴板面板已渲染时刷新；否则保持 pending，等用户切到剪贴板再刷新
      const panel = this.$refs.clipboardPanel
      if (panel && typeof panel.refresh === 'function') {
        this.pendingClipboardRefresh = false
        try {
          panel.refresh()
        } catch (e) {
          // ignore
        }
      }
    },
    unbindClipboardListener() {
      // #ifdef H5
      try {
        if (this._desktopClipboardUnsub) this._desktopClipboardUnsub()
      } catch (e) {
        // ignore
      }
      this._desktopClipboardUnsub = null
      if (this._pasteHandler) {
        document.removeEventListener('paste', this._pasteHandler)
      }
      if (this._copyHandler) {
        document.removeEventListener('copy', this._copyHandler, true)
      }
      if (this._clipboardKeydownHandler) {
        window.removeEventListener('keydown', this._clipboardKeydownHandler, true)
      }
      this._pasteHandler = null
      this._copyHandler = null
      this._clipboardKeydownHandler = null
      this._recordClipboardOnce = null
      this._clipboardBound = false
      // #endif
    },
    bindOcrHotkeys() {
      if (this._ocrKeydownBound) return
      this._ocrKeydownBound = true
      this._ocrKeydownHandler = (e) => {
        if (!this.showOcrOverlay) return
        if (e.key === 'Escape') {
          e.preventDefault()
          this.closeOcrOverlay()
        }
      }
      window.addEventListener('keydown', this._ocrKeydownHandler)
    },
    unbindOcrHotkeys() {
      if (this._ocrKeydownHandler) {
        window.removeEventListener('keydown', this._ocrKeydownHandler)
      }
      this._ocrKeydownHandler = null
      this._ocrKeydownBound = false
    },
    closeOcrOverlay() {
      // 关闭蒙层：H5 使用屏幕共享时可能涉及授权；Desktop 不需要授权
      // Desktop：BrowserView 的恢复由 desktopOverlayActive watcher 统一处理
      // （若此时还有其它弹窗打开，则保持隐藏，避免弹窗被网页遮挡）
      this.showOcrOverlay = false
      // #ifdef H5
      this.unbindOcrGlobalListeners()
      // #endif
      this.ocrOverlaySelecting = false
      this.ocrSel = { x1: 0, y1: 0, x2: 0, y2: 0 }
      this.ocrActionBar = { visible: false, x: 0, y: 0 }
      this.ocrText = ''
      this.ocrImageDataUrl = ''
      this.ocrFrameCanvas = null
      this.ocrFrameView = null
      if (this.ocrFrameUrl) {
        try { URL.revokeObjectURL(this.ocrFrameUrl) } catch (e) { /* ignore */ }
      }
      this.ocrFrameUrl = ''
      this.unbindOcrHotkeys()
    },
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
    async startOcrCapture() {
      // #ifdef H5
      try {
        this.ocrLoading = false
        this.ocrText = ''
        this.ocrImageDataUrl = ''
        this.ocrHostRect = null
        this.ocrOverlaySelecting = false
        this.ocrSel = { x1: 0, y1: 0, x2: 0, y2: 0 }
        this.ocrFrameCanvas = null
        this.ocrFrameView = null
        this.ocrFrameLoading = false

        // 尽量绑定当前浏览器 tab 的 URL（用于收藏）
        const active = this.focusedPane === 'right' ? this.activeFileRight : this.activeFileLeft
        this.ocrSourceUrl = active && active.tabType === 'web' ? (active.url || '') : ''

        // Desktop：直接抓屏做底图，不需要浏览器授权
        if (this.isDesktopApp && window.checkbaDesktop && window.checkbaDesktop.ocr) {
          // 桌面端（方案 B）：使用主进程 OverlayWindow 进行框选（选区期间不隐藏 BrowserView）
          const activeWebTab = this.getActiveWebTab()
          const viewId = activeWebTab && activeWebTab.id ? String(activeWebTab.id) : ''
          if (!window.checkbaDesktop.ocr.startSelection) {
            uni.showToast({ title: '桌面端截图能力不可用', icon: 'none' })
            return
          }
          // 全局截图：无网页 tab 时走 window 模式（两边都是文档也能截图）
          const resp = viewId
            ? await window.checkbaDesktop.ocr.startSelection({ viewId })
            : await window.checkbaDesktop.ocr.startSelection({ mode: 'window' })
          if (!resp || resp.ok !== true) {
            if (resp && resp.cancelled) return
            uni.showToast({ title: (resp && resp.message) ? String(resp.message) : '截图失败', icon: 'none' })
            return
          }
          if (resp && resp.payload) {
            await this.applyDesktopOcrSelection(resp.payload)
          } else {
            // 兼容旧事件回调（但不再依赖）
            uni.showToast({ title: '截图完成，但未收到结果', icon: 'none' })
          }
          return
        }

        if (!navigator.mediaDevices || !navigator.mediaDevices.getDisplayMedia) {
          uni.showToast({ title: '当前浏览器不支持屏幕共享', icon: 'none' })
          return
        }

        // 关键：授权一次后保持 stream，用户在全屏浮层上随时框选
        if (!this.ocrStream) {
          this.ocrStream = await navigator.mediaDevices.getDisplayMedia({ video: true, audio: false })
        }

        this.showOcrOverlay = true
        this.ocrActionBar = { visible: false, x: 0, y: 0 }
        this.bindOcrHotkeys()
        this.bindOcrGlobalListeners()
        // 用 offscreen video + canvas 实时渲染（避免出现“播放器三角/黑屏”）
        if (!this.ocrVideo) {
          this.ocrVideo = document.createElement('video')
          this.ocrVideo.muted = true
          this.ocrVideo.playsInline = true
          this.ocrVideo.autoplay = true
        }
        this.ocrVideo.srcObject = this.ocrStream
        await this.ocrVideo.play()

        // 改为“冻结帧”模式：抓一帧作为底图，用户在底图上框选，裁剪/下载/识别都基于同一帧
        await this.$nextTick()
        await this.ocrRefreshFrame()
      } catch (e) {
        console.error('启动 OCR 截图失败:', e)
        // 桌面端不需要浏览器授权：避免误导
        const title = this.isDesktopApp ? (e.message || '截图失败') : '截图失败（请允许共享标签页/窗口）'
        uni.showToast({ title, icon: 'none' })
        // 失败时统一走 closeOcrOverlay 复位（BrowserView 恢复由 watcher 处理）
        try {
          this.closeOcrOverlay()
        } catch (err) {
          // ignore
        }
      }
      // #endif
      // #ifndef H5
      uni.showToast({ title: '仅 H5 支持截图摘录', icon: 'none' })
      // #endif
    },

    // OCR 不再使用弹窗

    hideOcrOverlay() {
      this.closeOcrOverlay()
    },

    stopOcrCapture() {
      this.closeOcrOverlay()
      try {
        if (this.ocrStream) {
          this.ocrStream.getTracks().forEach(t => t.stop())
        }
      } catch (e) {
        // ignore
      }
      this.ocrStream = null
      this.ocrVideo = null
    },

    async ensureOcrFrozenFrame() {
      // 若底图未就绪：等待 DOM 挂载并重试抓帧，避免松开时报“画面未就绪”
      if (this.ocrFrameCanvas && this.ocrFrameView) return
      if (this.ocrFrameLoading) {
        const start = Date.now()
        while (Date.now() - start < 1200) {
          if (this.ocrFrameCanvas && this.ocrFrameView) return
          await new Promise(r => setTimeout(r, 30))
        }
        throw new Error('截图画面未就绪')
      }
      this.ocrFrameLoading = true
      try {
        // 等 canvas 挂载
        await this.$nextTick()
        await new Promise(r => requestAnimationFrame(r))
        // 最多重试 2 次
        for (let i = 0; i < 2; i++) {
          try {
            await this.ocrRefreshFrame()
            if (this.ocrFrameCanvas && this.ocrFrameView) return
          } catch (e) {
            if (i === 1) throw e
            await new Promise(r => setTimeout(r, 80))
          }
        }
      } finally {
        this.ocrFrameLoading = false
      }
    },

    async ocrRefreshFrame() {
      // 抓一帧作为底图，并绘制到 overlay canvas（用户框选基于该底图）
      // #ifdef H5
      if (this.isDesktopApp && window.checkbaDesktop && window.checkbaDesktop.ocr) {
        // 桌面端：抓“当前激活网页 Tab 的 BrowserView”（包含网页内容；不需要屏幕录制权限）
        const activeWebTab = this.getActiveWebTab()
        const viewId = activeWebTab && activeWebTab.id ? String(activeWebTab.id) : ''

        // 获取 BrowserView 的 bounds，用于把截图图像铺到网页区域，确保框选坐标正确
        try {
          const api = window.checkbaDesktop && window.checkbaDesktop.browser
          const b = api && viewId ? await api.getBounds({ id: viewId }) : null
          if (b && b.ok && b.bounds) {
            this.ocrHostRect = {
              x: Number(b.bounds.x) || 0,
              y: Number(b.bounds.y) || 0,
              width: Number(b.bounds.width) || 0,
              height: Number(b.bounds.height) || 0
            }
          } else {
            this.ocrHostRect = null
          }
        } catch (e) {
          this.ocrHostRect = null
        }

        // 若当前没有网页 tab，则回退抓当前窗口（此时 hostRect 为空，图片会铺满全屏）
        const resp = viewId
          ? await window.checkbaDesktop.ocr.captureScreen({ viewId })
          : await window.checkbaDesktop.ocr.captureScreen({ mode: 'window' })
        if (!resp || resp.ok !== true || !resp.dataUrl) {
          const m = resp && resp.message ? String(resp.message) : ''
          throw new Error(m || '截图失败')
        }
        await this.ocrSetFrameFromDataUrl(resp.dataUrl, this.ocrHostRect)
        return
      }
      const video = this.ocrVideo
      if (!video) throw new Error('截图视频未就绪')
      await this.ensureOcrFrameReady()

      const vw = video.videoWidth || 0
      const vh = video.videoHeight || 0
      if (!vw || !vh) throw new Error('截图视频尺寸异常')

      const frame = document.createElement('canvas')
      frame.width = vw
      frame.height = vh
      const fctx = frame.getContext('2d')
      fctx.drawImage(video, 0, 0, vw, vh)
      this.ocrFrameCanvas = frame

      // viewport 内展示：aspectFit（contain）
      const cw = window.innerWidth
      const ch = window.innerHeight
      const scale = Math.min(cw / vw, ch / vh)
      const dw = vw * scale
      const dh = vh * scale
      const dx = (cw - dw) / 2
      const dy = (ch - dh) / 2
      this.ocrFrameView = { vw, vh, cw, ch, dx, dy, scale }

      // 生成可展示的 URL（避免依赖 canvas DOM）
      if (this.ocrFrameUrl) {
        try { URL.revokeObjectURL(this.ocrFrameUrl) } catch (e) { /* ignore */ }
      }
      const blob = await new Promise((resolve) => {
        try {
          frame.toBlob((b) => resolve(b), 'image/png')
        } catch (e) {
          resolve(null)
        }
      })
      if (blob) {
        this.ocrFrameUrl = URL.createObjectURL(blob)
      } else {
        // fallback
        this.ocrFrameUrl = frame.toDataURL('image/png')
      }
      // #endif
    },

    async ocrSetFrameFromDataUrl(dataUrl, hostRect = null) {
      const url = String(dataUrl || '')
      if (!url) throw new Error('截图失败')
      const img = await new Promise((resolve, reject) => {
        const im = new Image()
        im.onload = () => resolve(im)
        im.onerror = () => reject(new Error('截图图片加载失败'))
        im.src = url
      })
      const vw = img.naturalWidth || img.width || 0
      const vh = img.naturalHeight || img.height || 0
      if (!vw || !vh) throw new Error('截图图片尺寸异常')

      const frame = document.createElement('canvas')
      frame.width = vw
      frame.height = vh
      const fctx = frame.getContext('2d')
      fctx.drawImage(img, 0, 0, vw, vh)
      this.ocrFrameCanvas = frame

      // Desktop：如果传入 hostRect（BrowserView bounds），就把画面铺到该区域内；否则回退到全屏
      const cw = hostRect && hostRect.width ? Number(hostRect.width) : window.innerWidth
      const ch = hostRect && hostRect.height ? Number(hostRect.height) : window.innerHeight
      const ox = hostRect && typeof hostRect.x === 'number' ? Number(hostRect.x) : 0
      const oy = hostRect && typeof hostRect.y === 'number' ? Number(hostRect.y) : 0
      const scale = Math.min(cw / vw, ch / vh)
      const dw = vw * scale
      const dh = vh * scale
      const dx = ox + (cw - dw) / 2
      const dy = oy + (ch - dh) / 2
      this.ocrFrameView = { vw, vh, cw, ch, dx, dy, scale }

      // 展示用：直接使用 dataUrl（桌面端无需 objectURL）
      this.ocrFrameUrl = url
    },

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

    onOcrOverlayDown(e) {
      if (!this.showOcrOverlay) return
      // 只处理左键
      if (e && e.button !== 0) return
      // 如果已经有 actionbar，重新开始框选
      this.ocrActionBar.visible = false
      this.ocrOverlaySelecting = true
      const p = this.getOcrPoint(e)
      this.ocrSel = { x1: p.x, y1: p.y, x2: p.x, y2: p.y }
      this.ocrLog('down', p.x, p.y, 'type=', e && e.type)
    },
    onOcrOverlayMove(e) {
      if (!this.ocrOverlaySelecting) return
      const p = this.getOcrPoint(e)
      this.ocrSel = { ...this.ocrSel, x2: p.x, y2: p.y }
    },
    async onOcrOverlayUp(e) {
      if (!this.ocrOverlaySelecting) return
      this.ocrOverlaySelecting = false
      const p = this.getOcrPoint(e)
      this.ocrLog('up', p.x, p.y, 'hasSelection=', this.ocrHasSelection)
      // 单击（无明显拖动）直接退出蒙层
      if (!this.ocrHasSelection) {
        this.closeOcrOverlay()
        return
      }

      // 框选结束：先裁剪生成图片，再显示快捷命令条（不自动识别）
      // #ifdef H5
      try {
        this.ocrText = ''
        await this.ensureOcrFrozenFrame()
        this.ocrImageDataUrl = this.cropOcrSelection()
        const left = Math.min(this.ocrSel.x1, this.ocrSel.x2)
    const right = Math.max(this.ocrSel.x1, this.ocrSel.x2)
    const bottom = Math.max(this.ocrSel.y1, this.ocrSel.y2)

    // Center X: Use the midpoint
    const centerX = (left + right) / 2

    // Y: Below the selection
    const topY = bottom + 12

    // 命令条位置：居中显示在框选区域下方
    this.ocrActionBar = {
      visible: true,
      x: centerX,
      y: topY
    }
      } catch (e) {
        console.error('截图裁剪失败:', e)
        uni.showToast({ title: e.message || '截图失败', icon: 'none' })
      }
      // #endif
    },

    async ensureOcrFrameReady() {
      // 确保 videoWidth/videoHeight 与 cover 已就绪（防止第一下松开太快）
      const videoEl = this.ocrVideo
      if (!videoEl) throw new Error('截图视频未就绪')
      const start = Date.now()
      const timeoutMs = 900
      while (Date.now() - start < timeoutMs) {
        const vw = videoEl.videoWidth || 0
        const vh = videoEl.videoHeight || 0
        if (vw && vh) return
        await new Promise(r => setTimeout(r, 30))
      }
      // 即使超时，也让 crop 自己兜底一次（会检查 vw/vh）
    },

    cropOcrSelection() {
      const left = Math.min(this.ocrSel.x1, this.ocrSel.x2)
      const top = Math.min(this.ocrSel.y1, this.ocrSel.y2)
      const w = Math.abs(this.ocrSel.x2 - this.ocrSel.x1)
      const h = Math.abs(this.ocrSel.y2 - this.ocrSel.y1)

      const frame = this.ocrFrameCanvas
      const view = this.ocrFrameView
      if (!frame || !view) throw new Error('截图画面未就绪')

      // 将用户在 viewport 的框选，映射到“冻结帧”像素坐标
      const clamp = (v, min, max) => Math.max(min, Math.min(max, v))
      const sx = (left - view.dx) / view.scale
      const sy = (top - view.dy) / view.scale
      const sw = w / view.scale
      const sh = h / view.scale

      const csx = clamp(sx, 0, view.vw - 1)
      const csy = clamp(sy, 0, view.vh - 1)
      const csw = clamp(sw, 1, view.vw - csx)
      const csh = clamp(sh, 1, view.vh - csy)

      const out = document.createElement('canvas')
      out.width = Math.max(1, Math.floor(csw))
      out.height = Math.max(1, Math.floor(csh))
      const ctx = out.getContext('2d')
      ctx.drawImage(
        frame,
        Math.floor(csx),
        Math.floor(csy),
        Math.floor(csw),
        Math.floor(csh),
        0,
        0,
        out.width,
        out.height
      )
      return out.toDataURL('image/png')
    },

    // H5：用 window 级事件保证拖拽框选必然可用
    async ocrDoRecognize() {
      if (!this.ocrImageDataUrl || this.ocrLoading) return

      const imageData = this.ocrImageDataUrl // Cache data
      // Close overlay immediately
      this.closeOcrOverlay()

      this.ocrLoading = true // Should I use global loading? closeOcrOverlay resets ocrLoading.
      // Resetting ocrLoading via closeOcrOverlay is correct?
      // Wait, `closeOcrOverlay` resets `ocrText`, `ocrImageDataUrl`.

      // Since UI is gone, I should use `uni.showLoading`
      uni.showLoading({ title: '识别中…' })

      try {
        const res = await ocrRecognize(imageData)
        const text = (res?.data?.text || '').trim()
        if (text) {
          // Auto copy
          await this.insertClipboardAndCopy(text, { saveToHistory: true })
          uni.showToast({ title: '识别并复制成功', icon: 'success' })
        } else {
          uni.showToast({ title: '未识别到文字', icon: 'none' })
        }
      } catch (e) {
        console.error('OCR 识别失败:', e)
        if (e && e.featureNotConfigured) {
          // OCR 未配置：引导去设置而非报"识别失败"（#18 T7）
          promptFeatureNotConfigured(e)
        } else {
          uni.showToast({ title: e.message || '识别失败', icon: 'none' })
        }
      } finally {
        uni.hideLoading()
        this.ocrLoading = false
      }
    },

    // Old ocrDoDownload (Removed/Replaced)
    // ocrDoDownload() { ... }

    async ocrDoCopy() {
      if (!this.ocrImageDataUrl) return

      const dataUrl = this.ocrImageDataUrl

      // Close immediately
      this.closeOcrOverlay()

      // 1. Copy Image to Clipboard
      try {
          const blob = await (await fetch(dataUrl)).blob()

          // Use Clipboard API
          if (navigator.clipboard && navigator.clipboard.write) {
              const item = new ClipboardItem({ [blob.type]: blob })
              await navigator.clipboard.write([item])
              uni.showToast({ title: '已复制图片', icon: 'success' })
          } else {
              throw new Error('Clipboard API unavailable')
          }
      } catch (e) {
          console.error('Copy Image Failed', e)
          uni.showToast({ title: '复制失败', icon: 'none' })
      }
    },

    ocrDoOpenSaveDialog() {
       if (!this.ocrImageDataUrl) return

       // Cache data for dialog
       this.screenshotSaveDataUrl = this.ocrImageDataUrl

       // Close overlay immediately
       this.closeOcrOverlay()

       this.showScreenshotSaveDialog = true
       this.screenshotSaveName = `screenshot_${Date.now()}.png`
       this.screenshotSaveParentId = null // Root
       // Load folders
       this.loadScreenshotFolders()
    },

    async loadScreenshotFolders() {
        this.screenshotFolderTree = []
        try {
            const allFiles = await getProjectFiles(this.projectId, null, true)
            if (this.buildExportFolderTree) {
                 this.screenshotFolderTree = this.buildExportFolderTree(allFiles || [])
            }
        } catch (e) {
            console.error('加载文件夹失败', e)
        }
    },

    selectScreenshotFolder(id) {
        this.screenshotSaveParentId = id
    },

    closeScreenshotSaveDialog() {
        this.showScreenshotSaveDialog = false
        this.screenshotSaveDataUrl = ''
    },

    openImagePreview(url) {
        if (url) this.imagePreviewUrl = url
    },

    closeImagePreview() {
        this.imagePreviewUrl = ''
    },

    async confirmSaveScreenshot() {
        if (!this.screenshotSaveDataUrl) return
        let name = (this.screenshotSaveName || '').trim()
        if (!name) {
            uni.showToast({ title: '请输入文件名', icon: 'none' })
            return
        }
        if (!/\.(png|jpg|jpeg)$/i.test(name)) {
            name = `${name}.png`
        }

        this.screenshotSaveLoading = true
        try {
        const dataUrl = this.screenshotSaveDataUrl
        const res = await fetch(dataUrl)
        const blob = await res.blob()
        const fileSize = blob.size

        // 1. Create File Metadata
        // Auto-generate wpsFileId and proper fileType
        const timestamp = Date.now()
        const randomStr = Math.random().toString(36).substring(2, 9)
        const wpsFileId = `project_${this.projectId}_doc_${timestamp}_${randomStr}`
        const fileType = 'png' // Simplify to png for screenshots
        // Ensure name ends with .png
        if (!name.toLowerCase().endsWith('.png')) {
            name += '.png'
        }

        // Call backend to create metadata
        const metadata = await createFile(
            this.projectId,
            this.screenshotSaveParentId || null,
            name,
            fileType,
            fileSize,
            null, // filePath (backend handles)
            wpsFileId
        )

        if (!metadata || !metadata.id) {
            throw new Error('Failed to create file record')
        }

        // 2. Upload File Content
        const fileToUpload = new File([blob], name, { type: 'image/png' })
        const token = getSessionId()
        const baseUrl = getApiBaseUrl()

        await new Promise((resolve, reject) => {
             uni.uploadFile({
                 url: `${baseUrl}/api/files/${wpsFileId}/upload`,
                 name: 'file', // Param name expected by backend
                 file: fileToUpload,
                 header: {
                     'X-Session-Id': token || ''
                 },
                 success: (res) => {
                     if (res.statusCode >= 200 && res.statusCode < 300) {
                         resolve(res.data)
                     } else {
                         reject(new Error(`Upload failed: ${res.statusCode}`))
                     }
                 },
                 fail: (err) => reject(err)
             })
        })

        uni.showToast({ title: '保存成功', icon: 'success' })
        this.showScreenshotSaveDialog = false
        this.screenshotSaveDataUrl = ''
        // Refresh items: Switch to files pane and reload
        this.leftPaneKey = 'files'
        this.sidebarCollapsed = false
        this.$nextTick(() => {
             const ft = this.$refs.fileTree
             if (ft) {
                 if (this.screenshotSaveParentId) {
                     ft.expandedFolders.add(this.screenshotSaveParentId)
                 }
                 if (ft.loadFiles) {
                     ft.loadFiles()
                 }
             }
        })
    } catch (e) {
        console.error('保存失败', e)
        uni.showToast({ title: '保存失败: ' + (e.message || '未知错误'), icon: 'none' })
    } finally {
        this.screenshotSaveLoading = false
    }
    },

    async ocrDoInsert() {
      if (!this.ocrText) return
      await this.insertPlainTextToWps(this.ocrText)
    },

    async ocrDoRefreshSelection() {
      // 统一为：重新发起一次截图框选（比“刷新底图”更符合用户预期）
      try {
        this.closeOcrOverlay()
      } catch (e) {
        // ignore
      }
      await this.startOcrCapture()
    },

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

    async ocrDoFavorite() {
      if (!this.ocrImageDataUrl) return

      const imgData = this.ocrImageDataUrl
      const srcUrl = this.ocrSourceUrl
      // Close overlay immediately
      this.closeOcrOverlay()

      try {
        uni.showToast({ title: '正在加入收藏…', icon: 'loading', duration: 1200 })
        const created = await createProjectFavorite(this.projectId, {
          title: srcUrl ? srcUrl : '网页摘录',
          sourceUrl: srcUrl,
          content: '',
          imageBase64: imgData
        })
        const favId = created && created.id ? created.id : null
        // 立即给用户可见反馈：打开收藏夹并高亮新卡片
        this.showToolsPanel = true
        this.activeToolKey = 'favorites'
        this.$nextTick(async () => {
          try {
            const panel = this.$refs.favoritesPanel
            // 先等列表刷新完成，新卡片进入列表后再定位高亮，否则高亮必然落空
            if (panel && typeof panel.refresh === 'function') await panel.refresh()
            if (favId && panel && typeof panel.focusFavorite === 'function') panel.focusFavorite(Number(favId))
          } catch (e) {
            // ignore
          }
        })
        uni.showToast({ title: '收藏成功', icon: 'success' })
      } catch (e) {
        console.error('收藏失败:', e)
        uni.showToast({ title: e.message || '收藏失败', icon: 'none' })
      }
    },

    async ocrDoWebLink() {
      // 目标：将框选截图作为“网核证据”入库，并进入“拖拽关联到文档”模式
      if (this.ocrLoading) return
      if (!this.ocrImageDataUrl) {
        uni.showToast({ title: '请先框选区域', icon: 'none' })
        return
      }
      // 1) Cache State
      const sel = { ...this.ocrSel }
      const imgData = this.ocrImageDataUrl
      const srcUrl = this.ocrSourceUrl
      const lastPtr = this.ocrLastPointer ? { ...this.ocrLastPointer } : { x: 0, y: 0 }

      // 2) Close UI immediately
      this.closeOcrOverlay()

      uni.showLoading({ title: '处理中…' })

      try {
        // 1) 采集网页上下文
        const metaObj = {
          kind: 'webmark',
          capturedAt: new Date().toISOString(),
          sourceUrl: srcUrl || '',
          title: '',
          selection: {
            x1: sel.x1,
            y1: sel.y1,
            x2: sel.x2,
            y2: sel.y2
          }
        }
        // 补齐卡片展示需要的关键元信息（站点 / 关联文档）
        try {
          if (metaObj.sourceUrl) {
            metaObj.sourceHost = (() => { try { return new URL(metaObj.sourceUrl).host } catch (e) { return '' } })()
          }
          const activeDoc = this.focusedPane === 'right' ? this.activeFileRight : this.activeFileLeft
          metaObj.docFileName = activeDoc && this.isEditorOpenableFile && this.isEditorOpenableFile(activeDoc) ? (activeDoc.name || '') : ''
          metaObj.docSide = this.focusedPane || 'left'
        } catch (e) {
          // ignore
        }
        try {
          const active = this.focusedPane === 'right' ? this.activeFileRight : this.activeFileLeft
          const viewId = active && active.tabType === 'web' ? active.id : ''
          if (this.isDesktopApp && viewId && window.checkbaDesktop && window.checkbaDesktop.browser && window.checkbaDesktop.browser.getSnapshot) {
            const snap = await window.checkbaDesktop.browser.getSnapshot({ id: viewId })
            if (snap && snap.ok) {
              metaObj.sourceUrl = snap.url || metaObj.sourceUrl
              metaObj.title = snap.title || ''
              // 完整页面 HTML 快照
              metaObj.html = snap.html || ''
              if (!metaObj.sourceHost && metaObj.sourceUrl) {
                metaObj.sourceHost = (() => { try { return new URL(metaObj.sourceUrl).host } catch (e) { return '' } })()
              }
            }
          }
        } catch (e) {
          // ignore snapshot failure
        }

        const pid = typeof this.projectId === 'string' ? Number(this.projectId) : this.projectId
        const title = metaObj.title || (metaObj.sourceUrl ? (() => { try { return new URL(metaObj.sourceUrl).host } catch (e) { return '网核' } })() : '网核')

        // 2) 入库
        const res = await createProjectFavorite(pid, {
          title,
          sourceUrl: metaObj.sourceUrl,
          content: '',
          imageBase64: imgData,
          meta: JSON.stringify(metaObj)
        })
        const saved = res && res.id ? res : (res && res.data ? res.data : null)
        const favId = saved && saved.id ? saved.id : null

        if (this.$refs.favoritesPanel && typeof this.$refs.favoritesPanel.refresh === 'function') {
          this.$refs.favoritesPanel.refresh()
        }

        // 3) 进入拖拽模式（把证据块拖到 WPS 插入标记）
        this.startWebLinkDrag({
          favoriteId: favId,
          imageDataUrl: imgData,
          sourceUrl: metaObj.sourceUrl,
          title,
          docFileName: metaObj.docFileName || '',
          x: lastPtr.x,
          y: lastPtr.y
        })
      } catch (e) {
        console.error('网核关联失败:', e)
        uni.showToast({ title: e.message || '网核关联失败', icon: 'none' })
      } finally {
        uni.hideLoading()
      }
    },

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

    triggerWorkbenchResize() {
      // WPS SDK 通常监听 window resize 来调整内部 iframe 大小
      if (typeof window !== 'undefined') {
        window.dispatchEvent(new Event('resize'))
        // 给过渡动画留时间，保证最终尺寸正确
        setTimeout(() => window.dispatchEvent(new Event('resize')), 250)
      }
    },

    // ================= Tabs 拖拽：同窗格换序 + 分屏跨窗格移动 =================
    onTabDragStart(evt, file, fromPane) {
      this.draggingTab = { fileId: file.id, fromPane }
      this.tabDragOver = null
      try {
        if (evt && evt.dataTransfer) {
          evt.dataTransfer.effectAllowed = 'move'
          evt.dataTransfer.setData('application/json', JSON.stringify({ fileId: file.id, fromPane }))
        }
      } catch (e) {
        // ignore
      }
    },

    onTabDragOver(_evt, file, pane) {
      this.tabDragOver = { fileId: file.id, pane }
    },

    onTabDropZoneDragOver(pane) {
      // 空白区域的 hover 提示（只记录 pane，避免 tab-drag-over 误高亮）
      if (!this.draggingTab) return
      this.tabDragOver = null
      // 预激活：拖到右侧区域时，先聚焦右侧，避免“未激活窗格无法接收拖拽”的体验
      if (pane === 'right' && this.splitMode) {
        this.focusedPane = 'right'
      }
    },

    onTabDropOnZone(evt, targetPane) {
      const payload = this.getTabDragPayload(evt) || this.draggingTab
      if (!payload || !payload.fileId) return
      // drop 到空白区域：插入到末尾
      this.moveTabTo(payload.fileId, payload.fromPane, targetPane, null)
      this.onTabDragEnd()
    },

    onTabDropOnItem(evt, targetFile, targetPane) {
      const payload = this.getTabDragPayload(evt) || this.draggingTab
      if (!payload || !payload.fileId) return

      this.moveTabTo(payload.fileId, payload.fromPane, targetPane, targetFile.id)
      this.onTabDragEnd()
    },

    getTabDragPayload(evt) {
      try {
        const raw = evt?.dataTransfer?.getData('application/json')
        if (!raw) return null
        return JSON.parse(raw)
      } catch (e) {
        return null
      }
    },

    moveTabTo(fileId, fromPane, toPane, beforeFileId) {
      if (!fileId || !fromPane || !toPane) return
      if (!this.splitMode && toPane === 'right') return

      const fromList = fromPane === 'left' ? this.leftFiles : this.rightFiles
      const toList = toPane === 'left' ? this.leftFiles : this.rightFiles

      const fromIdx = fromList.findIndex(f => f.id === fileId)
      if (fromIdx < 0) return

      const source = fromList[fromIdx]
      // 关键修复：跨窗格拖拽不“移动”，而是“在另一侧打开同一文件”（允许左右双开）
      const isCrossPane = fromPane !== toPane
      const moved = isCrossPane ? { ...source } : fromList.splice(fromIdx, 1)[0]

      // 目标索引：插入到 beforeFileId 前面（如果没有则追加末尾）
      let toIdx = -1
      if (beforeFileId) {
        toIdx = toList.findIndex(f => f.id === beforeFileId)
      }
      // 若目标窗格已存在同文件，则仅激活，不重复插入
      const existedIdx = toList.findIndex(f => f.id === moved.id)
      if (existedIdx >= 0) {
        // 如果目标还指定了 beforeFileId 且是同窗格换序，可以做排序调整
        if (!isCrossPane && beforeFileId && existedIdx !== toIdx && toIdx >= 0) {
          const [existing] = toList.splice(existedIdx, 1)
          toList.splice(toIdx, 0, existing)
        }
      } else {
        if (toIdx < 0) {
          toList.push(moved)
        } else {
          toList.splice(toIdx, 0, moved)
        }
      }

      // 激活态跟随：如果是跨窗格移动，更新 activeFileId
      if (toPane === 'left') {
        this.activeFileIdLeft = moved.id
        this.focusedPane = 'left'
      } else {
        this.activeFileIdRight = moved.id
        this.focusedPane = 'right'
      }

      // 拖拽换序/跨窗格会改变容器尺寸分配，给 WPS 一个 resize
      this.$nextTick(() => this.triggerWorkbenchResize())
    },

    onTabDragEnd() {
      this.draggingTab = null
      this.tabDragOver = null
    },

    isOpenInOtherPane(fileId, pane) {
      if (!fileId) return false
      if (pane === 'left') return this.rightFiles.some(f => f.id === fileId)
      return this.leftFiles.some(f => f.id === fileId)
    },

    startResize(target, evt) {
      // target: 'left' | 'right' | 'bottom'
      this.resizing.active = true
      this.resizing.target = target
      const e = evt && evt.touches && evt.touches[0] ? evt.touches[0] : evt
      this.resizing.startX = e?.clientX || 0
      this.resizing.startY = e?.clientY || 0
      this.resizing.startSidebarWidth = this.sidebarWidth
      this.resizing.startAiWidth = this.aiPanelWidth
      this.resizing.startToolsHeight = this.toolsPanelHeight

      // Cache DOM element for direct manipulation
      this.resizing.element = null
      if (target === 'left') {
        this.resizing.element = this.$refs.sidebarLeft ? (this.$refs.sidebarLeft.$el || this.$refs.sidebarLeft) : null
      } else if (target === 'right') {
        this.resizing.element = this.$refs.aiPanel ? (this.$refs.aiPanel.$el || this.$refs.aiPanel) : null
      } else if (target === 'bottom') {
         this.resizing.element = this.$refs.bottomPanel ? (this.$refs.bottomPanel.$el || this.$refs.bottomPanel) : null
      }

      if (evt && typeof evt.preventDefault === 'function') {
        evt.preventDefault()
      }

      if (typeof window !== 'undefined') {
        if (!this.boundResizeMove) this.boundResizeMove = (e2) => this.onResizeMove(e2)
        if (!this.boundStopResize) this.boundStopResize = () => this.stopResize()

        window.addEventListener('mousemove', this.boundResizeMove, { passive: false })
        window.addEventListener('mouseup', this.boundStopResize, { passive: true })
        window.addEventListener('touchmove', this.boundResizeMove, { passive: false })
        window.addEventListener('touchend', this.boundStopResize, { passive: true })
        // 兜底：鼠标移出窗口/窗口失焦时 mouseup 可能丢失，避免 is-resizing 卡死
        window.addEventListener('blur', this.boundStopResize, { passive: true })
      }
    },

    onResizeMove(evt) {
      if (!this.resizing.active) return
      const e = evt && evt.touches && evt.touches[0] ? evt.touches[0] : evt
      const clientX = e?.clientX || 0
      const clientY = e?.clientY || 0

      // 防止页面滚动
      if (evt && typeof evt.preventDefault === 'function') {
        evt.preventDefault()
      }

      // rAF 节流：让拖拽跟手更稳（避免 move 事件过密导致抖动/延迟）
      this._resizePendingX = clientX
      this._resizePendingY = clientY
      if (this._resizeRaf) return
      this._resizeRaf = requestAnimationFrame(() => {
        this._resizeRaf = null
        const vw = typeof window !== 'undefined' ? window.innerWidth : 1200
        const vh = typeof window !== 'undefined' ? window.innerHeight : 800

        // Cursor 体验：拖动范围尽量大，不强行保证中间工作区可用（遮挡就遮挡）
        const leftMin = 160
        const leftMax = Math.max(leftMin, Math.floor(vw * 0.75))
        const rightMin = 240
        const rightMax = Math.max(rightMin, Math.floor(vw * 0.75))
        const headerH = 56
        const tabsH = 40
        const bottomMin = 140
        const bottomMax = Math.max(bottomMin, Math.floor((vh - headerH - tabsH) * 0.85))

        const dx = (this._resizePendingX || 0) - this.resizing.startX
        const dy = (this._resizePendingY || 0) - this.resizing.startY

        let finalValue = 0

        if (this.resizing.target === 'left') {
          const next = this.resizing.startSidebarWidth + dx
          finalValue = Math.max(leftMin, Math.min(leftMax, next))
          // Direct DOM update
          if (this.resizing.element) {
              this.resizing.element.style.width = finalValue + 'px'
          }
          // Store for sync on stop
          this.resizing.currentValue = finalValue
        } else if (this.resizing.target === 'right') {
          const next = this.resizing.startAiWidth - dx
          finalValue = Math.max(rightMin, Math.min(rightMax, next))
           // Direct DOM update
           if (this.resizing.element) {
              this.resizing.element.style.width = finalValue + 'px'
          }
          this.resizing.currentValue = finalValue
        } else if (this.resizing.target === 'bottom') {
          const next = this.resizing.startToolsHeight - dy
          finalValue = Math.max(bottomMin, Math.min(bottomMax, next))
           // Direct DOM update
           if (this.resizing.element) {
              this.resizing.element.style.height = finalValue + 'px'
          }
           this.resizing.currentValue = finalValue
        }
      })
    },

    stopResize() {
      if (!this.resizing.active) return

      // Save target and currentValue BEFORE nullifying
      const target = this.resizing.target
      const finalValue = this.resizing.currentValue

      this.resizing.active = false
      this.resizing.target = null
      this.resizing.element = null
      this.resizing.currentValue = null

      if (this._resizeRaf) {
        cancelAnimationFrame(this._resizeRaf)
        this._resizeRaf = null
      }

      if (typeof window !== 'undefined') {
        if (this.boundResizeMove) {
          window.removeEventListener('mousemove', this.boundResizeMove)
          window.removeEventListener('touchmove', this.boundResizeMove)
        }
        if (this.boundStopResize) {
          window.removeEventListener('mouseup', this.boundStopResize)
          window.removeEventListener('touchend', this.boundStopResize)
          window.removeEventListener('blur', this.boundStopResize)
        }
      }

      // Sync final value to Vue state using saved target
      if (finalValue) {
          if (target === 'left') {
              this.sidebarWidth = finalValue
          } else if (target === 'right') {
              this.aiPanelWidth = finalValue
          } else if (target === 'bottom') {
              this.toolsPanelHeight = finalValue
          }
      }

      this.$nextTick(() => this.triggerWorkbenchResize())
    },

    toggleSplitMode() {
      this.splitMode = !this.splitMode

      // 关键修复：触发 resize 事件通知 WPS SDK 调整布局
      // WPS SDK 监听 window resize 来调整内部 iframe 大小
      this.$nextTick(() => this.triggerWorkbenchResize())

      if (!this.splitMode) {
        // 关闭分屏时，重置 focus 到左侧
        this.focusedPane = 'left'
      } else {
        // 开启分屏时，默认聚焦右侧，方便用户立即选择文件
        this.focusedPane = 'right'
      }
    },
    focusPane(pane) {
      // 只有在分屏模式下才允许聚焦右侧
      if (!this.splitMode && pane === 'right') return

      const oldPane = this.focusedPane
      this.focusedPane = pane

      if (oldPane !== pane) {
          // Switch active tracking to the file in the new pane
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
      }
    },

    // --- 文件管理逻辑 ---
    handleFileTreeSelect(file) {
      if (!file || file.isFolder) return
      this.openFile(file)
    },

    // Handle file open from SearchPanel
    handleSearchOpenFile(file) {
      if (!file) return
      console.log('[project-overview] Open file from search:', file)
      this.openFile({
        id: file.id,
        wpsFileId: file.wpsFileId,
        name: file.name,
        fileType: file.fileType,
        filePath: file.filePath
      })
    },

    // Handle file open request from ChatInterface (file changes popup)
    async handleOpenFileFromChat({ name }) {
      if (!name) return
      console.log('[project-overview] Open file from chat:', name)

      // Refresh project files first to ensure we have latest
      try {
        const resp = await getProjectFiles(this.projectId)
        // Normalize response: API returns { code: 0, data: [...] } or possibly just array
        const files = Array.isArray(resp) ? resp : (resp?.data || [])
        console.log('[project-overview] Got files for search:', files.length)

        // Find file by name (case-insensitive, match basename)
        const targetFile = files.find(f => {
          if (f.isFolder) return false
          // Match exact name or name without extension
          return f.name === name || f.name.toLowerCase() === name.toLowerCase()
        })

        if (targetFile) {
          console.log('[project-overview] Found file:', targetFile.id, targetFile.name)
          this.openFile(targetFile)
        } else {
          console.warn('File not found:', name, 'in', files.map(f => f.name))
          uni.showToast({ title: '未找到文件: ' + name, icon: 'none' })
        }
      } catch (e) {
        console.error('Failed to fetch files for open:', e)
        uni.showToast({ title: '获取文件列表失败', icon: 'none' })
      }
    },

    openFile(file) {
      // 检查文件类型是否支持打开
      if (!this.isFileTypeSupported(file)) {
        uni.showModal({
          title: '无法打开文件',
          content: `暂不支持打开此类型文件：${file.name}\n\n文件类型：${file.fileType || '无后缀名'}\n\n支持的文件类型：\n• 文档：doc, docx, xls, xlsx, ppt, pptx, pdf\n• 图片：jpg, jpeg, png, gif, bmp, webp, svg\n• 视频：mp4, webm, ogg, mov, mkv, avi\n• 音频：mp3, wav, m4a, flac, aac\n• 文本：txt, md, json, xml, html等`,
          showCancel: false,
          confirmText: '我知道了',
          success: (res) => {
            if (res.confirm) {
              console.log('用户确认无法打开文件')
            }
          }
        })
        return
      }

      const meta = this.project && this.project.name ? `Project: ${this.project.name}` : ''
      // Start session tracking for this file
      activityTracker.trackActivePage('OPEN_FILE', file.id, file.name, meta)

      // 1. 如果已经在某个 pane 打开，则聚焦该 pane
      const existingLeft = this.leftFiles.find(f => f.id === file.id)
      // - 如果当前聚焦窗格未打开该文件，则在当前窗格打开
      // - 若当前窗格已打开，则仅激活
      const targetPane = this.splitMode ? this.focusedPane : 'left'
      const targetList = targetPane === 'left' ? this.leftFiles : this.rightFiles
      const targetIdProp = targetPane === 'left' ? 'activeFileIdLeft' : 'activeFileIdRight'

      const existing = targetList.find(f => f.id === file.id)
      if (existing) {
        Object.assign(existing, file)
        this[targetIdProp] = file.id
        this.focusedPane = targetPane
      } else {
        targetList.push({ ...file })
        this[targetIdProp] = file.id
      }

      // Persist active ID for current mode
      const mode = this.leftPaneKey || 'files'
      this.lastActiveIdsByMode[targetPane][mode] = file.id
      this.saveActiveIdsByMode()

      // 打开/激活后，给 WPS 一个机会刷新（避免容器尺寸/激活状态不对）
      this.$nextTick(() => this.triggerWorkbenchResize())
    },

    activateTab(file, pane) {
      // 点击 Tab 时，切换对应窗格的激活文件，并聚焦该窗格
      this.focusPane(pane)
      if (pane === 'left') {
        this.activeFileIdLeft = file.id
      } else {
        this.activeFileIdRight = file.id
      }

      // Persist active ID for current mode
      const mode = this.leftPaneKey || 'files'
      this.lastActiveIdsByMode[pane][mode] = file.id
      this.saveActiveIdsByMode()

      // 同步更新 FileTree 的选中状态
      // 如果是文件类型（非浏览器标签、非特殊标签类型），需要更新资源管理器的选中状态
      if (!this.isBrowserTab(file) && file.id && !file.tabType) {
        // 展开侧边栏并切换到文件模式
        if (this.sidebarCollapsed) {
          this.sidebarCollapsed = false
        }
        if (this.leftPaneKey !== 'files') {
          this.leftPaneKey = 'files'
        }

        // 确保在下一个 tick 中执行，此时 FileTree 组件已经更新
        this.$nextTick(() => {
          if (this.$refs.fileTree) {
            // 使用 revealFile 方法来定位并选中文件（会展开父目录并滚动到文件）
            if (this.$refs.fileTree.revealFile) {
              this.$refs.fileTree.revealFile(file.id)
            } else {
              // 如果没有 revealFile 方法，直接设置 selectedFileId
              this.$refs.fileTree.selectedFileId = file.id
            }
          }
        })
      } else {
        // 如果是浏览器标签或其他特殊类型，清空 FileTree 的选中状态
        this.$nextTick(() => {
          if (this.$refs.fileTree) {
            this.$refs.fileTree.selectedFileId = null
            this.$refs.fileTree.multiSelectedIds = []
          }
        })
      }

      // Track switch
      const meta = this.project && this.project.name ? `Project: ${this.project.name}` : ''
      if (this.isBrowserTab(file)) {
          // If browser tab, we need to track URL session
          const url = file.url || ''
          const title = file.name || ''
          const fullMeta = meta + (title ? `. Title: ${title}` : '')
          activityTracker.trackActivePage('OPEN_URL', 0, url, fullMeta)
      } else {
          // File
          activityTracker.trackActivePage('OPEN_FILE', file.id, file.name, meta)
      }
    },

    handleFileDeleted(payload) {
      if (!payload || !payload.ids || !Array.isArray(payload.ids)) return
      const ids = new Set(payload.ids)

      // Close tabs if they match deleted IDs
      // Iterate backwards to avoid index issues when closing (though closeFile uses findIndex, safety first)
      
      // Right Pane
      for (let i = this.rightFiles.length - 1; i >= 0; i--) {
        if (ids.has(this.rightFiles[i].id)) {
           this.closeFile(this.rightFiles[i].id, 'right')
        }
      }
      
      // Left Pane
      for (let i = this.leftFiles.length - 1; i >= 0; i--) {
        if (ids.has(this.leftFiles[i].id)) {
           this.closeFile(this.leftFiles[i].id, 'left')
        }
      }
    },

    async closeFile(fileId, pane) {
      const list = pane === 'left' ? this.leftFiles : this.rightFiles
      const idProp = pane === 'left' ? 'activeFileIdLeft' : 'activeFileIdRight'

      let idx = list.findIndex(f => f.id === fileId)
      if (idx === -1) return

      const file = list[idx]

      // (autosave) Office 文档出池即卸载，卸载后 webview 没了就没法导出——
      // 有未保存修改（或保存在途）的实例先落盘再关。加载失败的实例跳过
      // （画布是空白原型，保存会覆盖真文件，同 evictLibreInstance）。
      if (file && this.useLibreEditor(file)) {
        const inst = (this._libreRefs || {})[pane + ':' + fileId]
        if (inst && inst.ready && !inst.isError && inst.file && (inst.dirty || inst.saving)) {
          try { await inst.flushSave() } catch (e) { console.warn('[ProjectOverview] close flush-save failed:', e) }
        }
        // 落盘期间列表可能已变（并发关闭）——重新定位，已被移除则到此为止
        idx = list.findIndex(f => f.id === fileId)
        if (idx === -1) return
      }
      const activeId = this[idProp]

      // If closing the active file/tab, the activityTracker.trackActivePage in activateTab or openFile will handle the switch.
      // But if we close the *currently active* file and no other file becomes active (e.g. empty list), we should stop session?
      // Actually, if we close active file, we usually switch to another one (logic below).
      // So we don't need to manually stop session here, UNLESS the list becomes empty.

      // const meta = this.project && this.project.name ? `Project: ${this.project.name}` : ''
      // activityTracker.logAction('CLOSE_FILE', file.id, file.name, 0, meta)

      list.splice(idx, 1)

      // 如果关闭的是当前激活的文件，尝试切换到临近的文件
      if (activeId === fileId) {
        const newActiveId = list.length > 0
          ? list[Math.min(idx, list.length - 1)].id
          : null
        this[idProp] = newActiveId

        // Update persisted record
        const mode = this.leftPaneKey || 'files'
        this.lastActiveIdsByMode[pane][mode] = newActiveId
        this.saveActiveIdsByMode()
      }
    },

    getFileIcon(type) {
      if (!type) return '📄'
      const t = type.toLowerCase()
      if (['doc','docx'].includes(t)) return '📝'
      if (['pdf'].includes(t)) return '📕'
      return '📄'
    },
    isFileTypeSupported(file) {
      if (!file || file.isFolder) return true
      if (!file.fileType) return false

      const type = file.fileType.toLowerCase()

      // 支持的文件类型列表
      const supportedTypes = [
        // Office文档
        'doc', 'docx', 'xls', 'xlsx', 'ppt', 'pptx', 'pdf',
        // 图片
        'jpg', 'jpeg', 'png', 'gif', 'bmp', 'svg', 'webp',
        // 视频
        'mp4', 'webm', 'ogg', 'mov', 'mkv', 'avi',
        // 音频
        'mp3', 'wav', 'm4a', 'flac', 'aac',
        // 文本文件
        'txt', 'md', 'markdown', 'json', 'xml', 'html', 'css', 'js', 'java', 'py', 'sh', 'sql', 'log',
        // 压缩包（FilePreview 条目预览 + 解压）
        'zip', 'rar', '7z',
        // 尽调清单
        'dd'
      ]

      return supportedTypes.includes(type)
    },
    isEditorOpenableFile(file) {
      // 判断是否为「文档编辑器可打开」的 Office 类文件
      if (!file || file.tabType === 'web' || file.tabType === 'markdown' || !file.fileType) return false

      const type = file.fileType.toLowerCase()

      // 1. Force native preview for media types (Images, Video, Audio)
      const mediaTypes = [
          // Images
          'jpg','jpeg','png','gif','bmp','svg','webp',
          // Video
          'mp4','webm','ogg','mov','mkv','avi',
          // Audio
          'mp3','wav','m4a','flac','aac'
      ]
      if (mediaTypes.includes(type)) return false

      // 2. 排除 Markdown 文件，使用专门的 Markdown 预览组件
      if (type === 'md' || type === 'markdown') return false

      // 3. Office 文档格式 —— 仅限自建 LOWA 引擎真正编入的模块（probe_modules
      // 实测：仅 Writer + Calc；Impress/Draw 已裁）。
      // - Presentation 一律走 FilePreview（pptx = pptx-preview 前端渲染）；
      // - PDF 走 FilePreview 的 Chromium 原生渲染——LOWA 无 Draw 时 Writer 会把
      //   PDF 二进制当文本导入，满屏乱码（2026-07 真机截图证实）。
      const wpsFormats = [
          // Writer
          'wps', 'wpt', 'doc', 'dot', 'docx', 'dotx', 'docm', 'dotm', 'rtf', 'odt',
          // Spreadsheet
          'et', 'ett', 'ets', 'xls', 'xlsx', 'xlt', 'xltx', 'xlsm', 'xltm', 'xlsb', 'csv'
      ]

      // Office 类型或带文件 ID（非媒体/markdown）即视为文档编辑器可打开
      return wpsFormats.includes(type) || (file.wpsFileId && !mediaTypes.includes(type) && type !== 'md' && type !== 'markdown')
    },

    // Epic #43 Track B / #79: should this Office file open in the embedded
    // LibreOffice editor? True only when the desktop embed is available
    // (libreOfficePreferred). On web/h5 or when unavailable, returns false and
    // the file falls through to FilePreview (docx 本地只读渲染).
    useLibreEditor(file) {
      return this.libreOfficePreferred && this.isEditorOpenableFile(file)
    },

    // Check if file is a markdown tab (for AI artifacts or real .md files)
    isMarkdownTab(file) {
      if (!file) return false
      // 1. AI 创建的虚拟 markdown 标签
      if (file.tabType === 'markdown') return true
      // 2. 真正的 .md 文件（从文件树打开）
      if (file.fileType && (file.fileType.toLowerCase() === 'md' || file.fileType.toLowerCase() === 'markdown')) return true
      return false
    },

    isDiffTab(file) {
      return file && file.tabType === 'diff'
    },

    // --- 文档对比逻辑 ---
    onCompareDocumentsRequest(docs) {
      // FileTree 发起的文档对比请求
      if (!docs || docs.length !== 2) {
        uni.showToast({ title: '请选择两个文档进行对比', icon: 'none' })
        return
      }
      this.compareDocuments = docs
      this.showCompareDialog = true
    },

    onCompareDialogConfirm({ source, target }) {
      // 用户确认了源文档和目标文档，打开 diff 标签页
      this.showCompareDialog = false
      this.openDiffTab(source, target)
    },

    openDiffTab(source, target) {
      // 创建 diff 类型的虚拟标签页
      const diffId = `diff-${source.id}-${target.id}-${Date.now()}`
      const diffFile = {
        id: diffId,
        name: `${source.name} ↔ ${target.name}`,
        tabType: 'diff',
        fileType: 'diff',
        diffSource: {
          id: source.id,
          name: source.name
        },
        diffTarget: {
          id: target.id,
          name: target.name
        },
        createdAt: Date.now()
      }

      // 添加到当前窗格
      const targetPane = this.splitMode ? this.focusedPane : 'left'
      const targetList = targetPane === 'left' ? this.leftFiles : this.rightFiles
      const targetIdProp = targetPane === 'left' ? 'activeFileIdLeft' : 'activeFileIdRight'

      targetList.push(diffFile)
      this[targetIdProp] = diffFile.id

      console.log('[ProjectOverview] 打开文档对比标签:', diffFile.name)
    },

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
      console.log('[ProjectOverview] 📄 Opening artifact in tab:', artifactInfo)

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
      console.log('[ProjectOverview] ✓ Created markdown tab:', virtualFile.name)
    },

    handleClientAction(action) {
        console.log('[ProjectOverview] Client Action:', action)

        if (action.action === 'refresh_files') {
            if (this.$refs.fileTree && this.$refs.fileTree.loadFiles) {
                console.log('[ProjectOverview] Refreshing File Tree...')
                this.$refs.fileTree.loadFiles()
                uni.showToast({ title: '文件已更新', icon: 'none' })
            }
        }
        // AI Agent 请求打开文件
        else if (action.action === 'wps_open_file') {
            this.handleEditorOpenFile(action)
        }
        // AI Agent 请求重新加载文件（用于后端修改文件后刷新 WPS）
        else if (action.action === 'wps_reload_file') {
            this.handleEditorReloadFile(action)
        }
        // AI Agent 请求执行编辑器命令（事件名沿用 wps_command，前后端契约；双轨迁移见 docs/AI_ARCHITECTURE.md Phase 3）
        else if (action.tool === 'wps_command') {
            // 特殊处理 wps_open_file_sync 命令（新建文件流式写入）
            if (action.action === 'wps_open_file_sync') {
                this.handleEditorOpenFileSync(action)
            } else {
                this.handleEditorCommand(action)
            }
        }
        // 后端流式写入数据（wps_start_stream 工具）：缓冲后经 LibreOffice 执行器落字
        else if (action.action === 'wps_stream_data') {
            this.handleDocStreamData(action.content || '')
        }
    },

    // --- 流式写入（#79：LibreOffice 消费端，替代原 useWpsBridge.handleWpsStreamData）---
    // 与原 WPS 实现同构：本地缓冲 + 定时批量 flush，减少 worker 往返。
    handleDocStreamData(content) {
        if (!content) return
        this._docStreamBuffer = (this._docStreamBuffer || '') + content
        if (!this._docStreamTimer) {
            this._docStreamTimer = setTimeout(() => {
                this._docStreamTimer = null
                this.flushDocStreamBuffer()
            }, 150)
        }
    },
    async flushDocStreamBuffer() {
        if (!this._docStreamBuffer || this._docStreamBusy) return
        if (!this.libreOfficeActive || !this.libreOfficeExecutor) return
        this._docStreamBusy = true
        const text = this._docStreamBuffer
        this._docStreamBuffer = ''
        try {
            await this.libreOfficeExecutor.executeCommand('insert_at_cursor', { text })
        } catch (e) {
            console.error('[ProjectOverview] doc stream insert error:', e)
        } finally {
            this._docStreamBusy = false
            if (this._docStreamBuffer && !this._docStreamTimer) {
                this._docStreamTimer = setTimeout(() => {
                    this._docStreamTimer = null
                    this.flushDocStreamBuffer()
                }, 150)
            }
        }
    },

    /**
     * 处理 AI Agent 的同步打开文件请求 (用于流式写入)
     * 打开文件，等待内置 LibreOffice 编辑器就绪后返回结果给后端（#79）
     */
    async handleEditorOpenFileSync(action) {
        console.log('[ProjectOverview] Open File Sync:', action)
        const { params, requestId, conversationId } = action

        try {
            if (!params || !params.fileId) {
                console.error('[ProjectOverview] No fileId in wps_open_file_sync')
                await sendEditorResult(conversationId, requestId, false, null, '缺少文件ID')
                return
            }

            // 1. 刷新文件列表以获取最新文件
            if (this.$refs.fileTree && this.$refs.fileTree.loadFiles) {
                await this.$refs.fileTree.loadFiles()
            }

            // 2. 获取文件详情
            const file = await getFileDetail(this.projectId, params.fileId)
            if (!file) {
                console.error('[ProjectOverview] File not found:', params.fileId)
                await sendEditorResult(conversationId, requestId, false, null, '文件不存在')
                return
            }

            console.log('[ProjectOverview] Opening file for streaming:', file.name)

            // 3. 打开文件（挂载/激活内置 LibreOffice 编辑器）
            await this.openFile(file)

            // 4. 等待编辑器就绪（onLibreReady 置位，最多等待 90 秒——LOWA 首次 boot 较慢）
            let editorReady = false
            for (let i = 0; i < 180; i++) {
                await new Promise(resolve => setTimeout(resolve, 500))
                if (this.libreOfficeActive && this.libreOfficeExecutor) {
                    editorReady = true
                    console.log('[ProjectOverview] LibreOffice editor ready after', (i + 1) * 500, 'ms')
                    break
                }
            }

            if (!editorReady) {
                console.error('[ProjectOverview] LibreOffice editor not ready after timeout')
                await sendEditorResult(conversationId, requestId, false, null, '编辑器未就绪')
                return
            }

            // 5. 重置流式缓冲，准备接收新的流式数据
            this._docStreamBuffer = ''
            if (this._docStreamTimer) { clearTimeout(this._docStreamTimer); this._docStreamTimer = null }
            this._docStreamBusy = false
            console.log('[ProjectOverview] Stream state reset, ready for streaming')

            // 6. 返回成功给后端
            console.log('[ProjectOverview] Open File Sync success')
            await sendEditorResult(conversationId, requestId, true, {
                fileId: file.id,
                fileName: file.name,
                status: 'ready'
            }, null)

            uni.showToast({ title: `已打开: ${file.name}`, icon: 'none' })

        } catch (e) {
            console.error('[ProjectOverview] handleEditorOpenFileSync error:', e)
            await sendEditorResult(conversationId, requestId, false, null, e.message)
        }
    },

    /**
     * 处理 AI Agent 的打开文件请求
     */
    async handleEditorOpenFile(action) {
        console.log('[ProjectOverview] WPS Open File:', action)
        try {
            const fileId = action.fileId
            if (!fileId) {
                console.warn('[ProjectOverview] No fileId in wps_open_file action')
                return
            }

            // 获取文件详情
            const file = await getFileDetail(this.projectId, fileId)
            if (!file) {
                console.error('[ProjectOverview] File not found:', fileId)
                uni.showToast({ title: '文件不存在', icon: 'none' })
                return
            }

            // 打开文件
            this.openFile(file)

            // 提示用户
            uni.showToast({ title: `已打开: ${file.name}`, icon: 'none' })

        } catch (e) {
            console.error('[ProjectOverview] handleEditorOpenFile error:', e)
            uni.showToast({ title: '打开文件失败', icon: 'none' })
        }
    },

    /**
     * 处理 AI Agent 的重新加载文件请求
     * 当后端修改了文件后，需要通知前端刷新编辑器以显示最新内容
     *
     * 工作原理：
     * 1. 后端修改文件后会更新 wpsFileId（通用文件 ID，添加版本时间戳）
     * 2. 前端获取最新文件信息，更新 leftFiles/rightFiles 中的 wpsFileId
     * 3. LibreOfficeEditor 以文件为 key/prop，检测到变化后重新加载
     */
    async handleEditorReloadFile(action) {
        console.log('[ProjectOverview] WPS Reload File:', action)
        try {
            const fileId = action.fileId
            if (!fileId) {
                console.warn('[ProjectOverview] No fileId in wps_reload_file action')
                return
            }

            // 获取文件详情（确保获取最新信息，包括新的 wpsFileId）
            const file = await getFileDetail(this.projectId, fileId)
            if (!file) {
                console.error('[ProjectOverview] File not found:', fileId)
                uni.showToast({ title: '文件不存在', icon: 'none' })
                return
            }

            console.log('[ProjectOverview] Got updated file info:', {
                id: file.id,
                name: file.name,
                wpsFileId: file.wpsFileId
            })

            // 同时更新 leftFiles 和 rightFiles 中的文件信息
            // 这样可以确保所有打开的相同文件都能获得新的 wpsFileId
            let updated = false

            // 更新左侧窗格
            const existingLeft = this.leftFiles.find(f => f.id === file.id)
            if (existingLeft) {
                const oldWpsFileId = existingLeft.wpsFileId
                Object.assign(existingLeft, file)
                console.log('[ProjectOverview] Updated leftFiles:', {
                    oldWpsFileId,
                    newWpsFileId: file.wpsFileId
                })
                updated = true
            }

            // 更新右侧窗格
            const existingRight = this.rightFiles.find(f => f.id === file.id)
            if (existingRight) {
                const oldWpsFileId = existingRight.wpsFileId
                Object.assign(existingRight, file)
                console.log('[ProjectOverview] Updated rightFiles:', {
                    oldWpsFileId,
                    newWpsFileId: file.wpsFileId
                })
                updated = true
            }

            // 保活池实例不再"切回即重挂载"：后台常驻实例里还是旧内容。把该
            // 文件的非活动保活实例逐出 LRU（卸载），下次激活时重挂载并拉取
            // 新 wpsFileId 的字节。当前正显示的实例保持改前行为（不强刷）。
            if (updated) {
                this.libreLruKeys = this.libreLruKeys.filter(k => {
                    if (!k.endsWith(':' + file.id)) return true
                    return k === 'left:' + this.activeFileIdLeft || k === 'right:' + this.activeFileIdRight
                })
            }

            // 如果文件不在任何窗格中打开，则打开它
            if (!updated) {
                console.log('[ProjectOverview] File not open, opening:', file.name)
                this.openFile(file)
            }

            uni.showToast({ title: `文件已更新: ${file.name}`, icon: 'success' })

            // 刷新文件树以更新文件信息
            if (this.$refs.fileTree && this.$refs.fileTree.loadFiles) {
                this.$refs.fileTree.loadFiles()
            }

        } catch (e) {
            console.error('[ProjectOverview] handleEditorReloadFile error:', e)
            uni.showToast({ title: '刷新文件失败', icon: 'none' })
        }
    },

    /**
     * 处理 AI Agent 的编辑器命令请求（#79：LibreOffice 是唯一执行器；
     * 结果经 sendEditorResult 回传后端，路由 /wps-result 为前后端契约）
     */
    async handleEditorCommand(action) {
        console.log('[ProjectOverview] ========== Editor Command Start ==========')
        console.log('[ProjectOverview] Editor Command:', JSON.stringify(action))

        const { action: commandAction, params, requestId, conversationId } = action
        console.log('[ProjectOverview] commandAction:', commandAction, 'requestId:', requestId)

        if (!this.libreOfficeActive || !this.libreOfficeExecutor) {
            console.error('[ProjectOverview] No embedded editor available')
            await sendEditorResult(conversationId, requestId, false, null, '编辑器未就绪，请先打开一个文档')
            return
        }

        try {
            const result = await this.libreOfficeExecutor.executeCommand(commandAction, params)
            const successFlag = result && result.success !== false
            await sendEditorResult(conversationId, requestId, successFlag, result, (result && result.error) || null)
        } catch (e) {
            console.error('[ProjectOverview] LibreOffice command error:', e)
            await sendEditorResult(conversationId, requestId, false, null, e.message)
        }
        console.log('[ProjectOverview] ========== Editor Command End ==========')
    },

    // Epic #43: embedded LibreOffice editor lifecycle. While ready, backend AI
    // commands route to it (see handleEditorCommand). Used by both the inline
    // keep-alive pool (Track B) and the legacy ⌘⇧O overlay.
    // pane/fileId 由保活池模板内联传入；overlay 不带（落到 'overlay' 键）。
    onLibreReady(executor, pane, fileId) {
        const key = pane ? pane + ':' + fileId : 'overlay'
        this.getLibreExecutorMap()[key] = executor
        this.syncLibreExecutor()
        console.log('[ProjectOverview] LibreOffice editor ready (' + key + ') — agent commands routed to LibreOffice')
    },
    // 实例注册表（非响应式）：executor 按 'pane:fileId' 存，供活跃实例指针
    // 同步；组件实例经函数 ref 存 _libreRefs，供 LRU 淘汰前自动保存。
    getLibreExecutorMap() {
        return this._libreExecMap || (this._libreExecMap = {})
    },
    setLibreRef(pane, fileId, el) {
        const refs = this._libreRefs || (this._libreRefs = {})
        const key = pane + ':' + fileId
        if (el) refs[key] = el
        else delete refs[key]
    },
    // 活跃实例指针（同 PR#151 WPS 编辑器模式）：AI 指令路由到焦点 pane 的
    // 活动 Office 编辑器；焦点 pane 不是 Office 文档时回退另一 pane（保持
    // 旧的"唯一打开的文档也能收指令"行为）；legacy overlay 打开时优先。
    // 活动编辑器尚未 ready（boot 中）时指针为 null，handleEditorCommand
    // 照旧回"编辑器未就绪"。
    syncLibreExecutor() {
        const map = this.getLibreExecutorMap()
        const pick = (pane) => {
            const f = pane === 'right' ? this.activeFileRight : this.activeFileLeft
            return (f && this.useLibreEditor(f)) ? (map[pane + ':' + f.id] || null) : null
        }
        let exec = this.showLibreEmbed ? (map['overlay'] || null) : null
        if (!exec) exec = this.focusedPane === 'right' ? (pick('right') || pick('left')) : (pick('left') || pick('right'))
        this.libreOfficeExecutor = exec || null
        this.libreOfficeActive = !!exec
    },
    // 激活的标签变化：Office 文档记入保活 LRU（超上限触发淘汰），并同步指针。
    onActiveOfficeFileChanged(pane, file) {
        if (file && this.useLibreEditor(file)) this.touchLibreLru(pane, file.id)
        this.syncLibreExecutor()
    },
    touchLibreLru(pane, fileId) {
        const key = pane + ':' + fileId
        // 触达置顶，顺带清掉已关闭文件的残留记账
        const keys = [key].concat(this.libreLruKeys.filter(k => k !== key && this.isLibreKeyOpen(k)))
        this.libreLruKeys = keys
        keys.slice(LIBRE_KEEPALIVE_MAX).forEach(k => { this.evictLibreInstance(k) })
    },
    isLibreKeyOpen(key) {
        const sep = key.indexOf(':')
        const pane = key.slice(0, sep)
        const fileId = key.slice(sep + 1)
        const list = pane === 'right' ? this.rightFiles : this.leftFiles
        return list.some(f => String(f.id) === fileId && this.useLibreEditor(f))
    },
    // LRU 淘汰：先自动保存再出池（出池即卸载，走组件自身的 dispose 流程）。
    async evictLibreInstance(key) {
        const inst = (this._libreRefs || {})[key]
        // 未就绪/加载失败的实例跳过保存——画布上是空白原型，保存会覆盖真文件。
        // flushSave：等在途自动保存结束，仍有脏改动才再存（没改动就不空传）。
        if (inst && inst.ready && !inst.isError && inst.file) {
            try { await inst.flushSave() } catch (e) { console.warn('[ProjectOverview] evict auto-save failed:', e) }
        }
        // 保存耗时期间可能又被激活/关闭：仍在上限内或已是活动文件则不淘汰
        const idx = this.libreLruKeys.indexOf(key)
        if (idx === -1 || idx < LIBRE_KEEPALIVE_MAX) return
        if (key === 'left:' + this.activeFileIdLeft || key === 'right:' + this.activeFileIdRight) return
        this.libreLruKeys = this.libreLruKeys.filter(k => k !== key)
        console.log('[ProjectOverview] LibreOffice keep-alive evicted (LRU):', key)
    },
    // 压缩包解压完成：刷新资源管理器，让新文件夹立即可见。
    onArchiveExtracted() {
      if (this.$refs.fileTree && this.$refs.fileTree.loadFiles) {
        this.$refs.fileTree.loadFiles()
      }
    },
    // (#79) 文档内超链接点击：编辑器把 LO 的 window.open 经 lo-relay 转发上来。
    // 内部链接（包装 https 或裸 checkba:）走 __checkbaHandleInternalLink（关联
    // 文件/网核定位，含解包），普通网页开工作区浏览器 tab。
    onLibreOpenUrl(url) {
      const u = String(url || '')
      if (!u) return
      const isWrapped = this.WPS_INTERNAL_HTTP_LINK_BASE && u.startsWith(this.WPS_INTERNAL_HTTP_LINK_BASE)
      if (isWrapped || u.startsWith('checkba:')) {
        try {
          if (typeof window !== 'undefined' && window.__checkbaHandleInternalLink) window.__checkbaHandleInternalLink(u)
        } catch (e) {
          console.error('内部链接处理失败:', e)
        }
        return
      }
      if (/^https?:\/\//i.test(u)) this.openBrowserTab(u)
    },
    onLibreClose(executor) {
        // The overlay close button emits no executor: collapse the overlay; the
        // unmount that follows re-enters here WITH the executor for real cleanup.
        // An inline pool editor unmount (tab close / LRU evict) emits its own —
        // drop it from the registry by identity and re-sync the active pointer,
        // so closing a background instance can't clobber the active one.
        const map = this.getLibreExecutorMap()
        if (executor) {
            for (const k of Object.keys(map)) {
                if (map[k] === executor) {
                    delete map[k]
                    if (k === 'overlay') this.showLibreEmbed = false
                }
            }
        } else {
            this.showLibreEmbed = false
        }
        this.syncLibreExecutor()
        if (!this.libreOfficeActive) console.log('[ProjectOverview] LibreOffice editor closed — agent commands unavailable until reopened')
    },

    // #104: WPS-era getWps() adapter for VariablePanel — the five document-field
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
      this.insertPlainTextToWps(message.content)
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
        await this.libreOfficeExecutor.executeCommand('replace_selection', { text: message.content })
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

    async fetchChatHistory() {
      if (!this.projectId) return
      this.loadingHistory = true
      // Note: Do NOT set showHistoryDrawer = true here
      // The drawer should only open when user clicks the toggle button (toggleHistoryDrawer)
      try {
          const res = await getAiConversations(this.projectId)
          // Map to display format
          // Backend returns: [{conversationId, updatedAt, lastMessage}, ...]
          // We map to: { id, title, date }
          this.chatHistoryList = (res || []).map(item => ({
              id: item.conversationId,
              title: item.title ? item.title.replace(/<[^>]+>/g, '').trim() : (item.lastMessage ? (item.lastMessage.substring(0, 20) + (item.lastMessage.length > 20 ? '...' : '')) : '新对话'),
              updatedAt: item.updatedAt,
              lastMessage: item.lastMessage ? item.lastMessage.replace(/<[^>]+>/g, '').replace(/\s+/g, ' ').substring(0, 60) + (item.lastMessage.length > 60 ? '...' : '') : '',
              conversationId: item.conversationId
          }))
      } catch (e) {
        console.error('Fetch history failed', e)
        uni.showToast({ title: '加载历史失败', icon: 'none' })
      } finally {
        this.loadingHistory = false
      }
    },

    async loadHistoryChat(chat) {
        if (!chat || !chat.conversationId) return
        this.currentConversationId = chat.conversationId
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

<style lang="scss" scoped>
/* Color Config - AI Workdeck Palette */
$color-primary: #1A5336; // Forest Green
$color-primary-light: #2D7A52; // Hover
$color-primary-dark: #123A26; // Active
$color-accent: #5BD197; // Mint Green
$color-accent-light: #84E0B3; // Hover
$color-accent-pale: #E6F9F0; // Lightest (Selection)
$color-text-main: #2C3338; // Gray-Dark
$color-text-light: #6C757D; // Gray-Medium
$color-border: #E9ECEF; // Gray-Light
$bg-dark: #212629; // Dark BG
$bg-pale: #F8F9FA; // Pale BG
$bg-white: #FFFFFF;

/* Mixins */
@mixin flex-center {
  display: flex;
  align-items: center;
  justify-content: center;
}

@mixin text-ellipsis {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.page-project-overview {
  display: flex;
  flex-direction: column;
  height: 100vh;
  background-color: $bg-pale;
  color: $color-text-main;
  font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, "Helvetica Neue", Arial, sans-serif;
  overflow: hidden;

  &.compact-mode {
    .project-header {
      height: 48px;
      padding: 0 16px;
    }
  }
}

/* ================= Header ================= */
.project-header {
  height: 42px;
  background-color: $bg-white;
  border-bottom: 1px solid $color-border;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 18px;
  position: relative; // For absolute centering
  flex-shrink: 0;
  z-index: 200;
  box-shadow: 0 1px 3px rgba(0,0,0,0.02); // Subtle shadow
  transition: all 0.3s ease;
}

.header-left {
  display: flex;
  align-items: center;
  gap: 16px;
}

.header-center {
  position: absolute;
  left: 50%;
  top: 50%;
  transform: translate(-50%, -50%);
  display: flex;
  align-items: center;
  justify-content: center;
  pointer-events: none; // Let clicks pass through if needed, though logo usually isn't interactive here
}

.back-btn {
  width: 32px;
  height: 32px;
  @include flex-center;
  border-radius: 6px;
  cursor: pointer;
  color: $color-text-light;
  transition: all 0.2s;

  &:hover {
    background-color: $bg-pale;
    color: $color-primary;
  }
}

.back-icon {
  font-size: 18px;
  font-weight: 500;
}

.project-logo {
  height: 20px;
  width: 100px; /* approximiate aspect ratio for heightFix */
  margin-right: 0;
  display: block;
}

.project-info {
  display: flex;
  flex-direction: column;
  justify-content: center;
}

.project-title-row {
  display: flex;
  align-items: center;
  gap: 8px;
}

.project-name {
  font-size: 16px;
  font-weight: 600;
  color: $color-primary; // Forest Green for title
  letter-spacing: -0.2px;
  cursor: pointer;

  &:hover {
    color: $color-primary-light;
  }
}

.rename-input {
  font-size: 16px;
  font-weight: 600;
  color: $color-text-main;
  border: 1px solid $color-accent;
  border-radius: 4px;
  padding: 0 4px;
  width: 200px;
  background: $bg-white;
}

.project-status-badge {
  background-color: $color-accent-pale;
  border: 1px solid transparent; // Cleaner look
  border-radius: 4px;
  padding: 1px 6px;

  .status-text {
    font-size: 10px;
    font-weight: 500;
    color: $color-primary; // Dark green text on light green bg
  }
}

.project-meta {
  display: flex;
  align-items: center;
  font-size: 11px;
  color: $color-text-light;
  margin-top: 2px;
  gap: 6px;
}

.meta-divider {
  color: #dee2e6;
  font-size: 10px;
}

.header-right {
  display: flex;
  align-items: center;
  gap: 12px;
}

.header-tools {
  display: flex;
  align-items: center;
  gap: 4px;
  margin-right: 0;
  padding-right: 0;
  border-right: none;
}

.top-bar-btn {
  width: 19.2px; /* Resized to 4/5 of 24px */
  height: 19.2px;
  display: flex; /* mixin replacement */
  align-items: center;
  justify-content: center;
  border-radius: 4px; /* Adjusted radius */
  cursor: pointer;
  color: #374151; /* Cool Gray 700 - explicitly dark gray */
  background-color: transparent;
  border: 1px solid transparent; /* Explicitly transparent to kill default borders */
  transition: all 0.2s ease;

  &:hover {
    background-color: #f3f4f6; /* Cool Gray 100 - very neutral */
    color: #111827; /* Gray 900 */
  }

  &.active {
    background-color: #e5e7eb; /* Cool Gray 200 */
    color: #111827;
  }

  .tool-icon {
    font-size: 16px;
  }
}

.tool-icon-img {
  width: 21px;
  height: 21px;
  display: block;
}

.icon-btn {
  width: 32px;
  height: 32px;
  @include flex-center;
  border-radius: 6px;
  cursor: pointer;
  color: #4b5563; /* Cool Gray 600 - explicit cool tone */
  border: 1px solid transparent; /* Enforce no default border */
  transition: all 0.2s;
  position: relative; // For red dot or badge

  &:hover {
    background-color: $color-accent-pale;
    color: $color-primary;
  }

  &.active {
    background-color: $color-accent-pale;
    color: $color-primary;
  }

  .tool-icon {
    font-size: 16px;
    // Specific adjustment for font icons if needed
  }
}


.user-avatar {
  width: 32px;
  height: 32px;
  background: $color-primary;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  color: white;
  font-size: 14px;
  cursor: pointer;
}

/* 主布局 */
.main-layout {
  flex: 1;
  display: flex;
  overflow: hidden;
  position: relative;
}

/* Left Rail (Activity Bar) - Match Doc Dark BG */
.left-rail {
  width: 50px;
  background-color: #F8F9FA; // AI Workdeck Gray-Pale (Light Mode)
  display: flex;
  flex-direction: column;
  align-items: center;
  padding: 12px 0;
  z-index: 150;
  flex-shrink: 0;
  border-right: 1px solid #E9ECEF; // Gray-Light
}

.rail-btn {
  width: 40px;
  height: 40px;
  @include flex-center;
  margin-bottom: 8px;
  border-radius: 8px;
  cursor: pointer;
  color: #64748b; // Slate-500 (Gray-Medium equivalent)
  transition: all 0.2s;

  &:hover {
    color: #1A5336; // Forest Green
    background-color: #f1f5f9; // Slate-100
  }

  &.active {
    background-color: rgba(91, 209, 151, 0.1); // Mint with opacity
    // Use pseudo-element for border to avoid layout shift
    position: relative;
    border-radius: 0; // Remove radius for active state to match square-ish look or keep? Original had left radius removed.
    // Actually, original had: border-top-left-radius: 0; border-bottom-left-radius: 0;

    &::before {
        content: '';
        position: absolute;
        left: 0;
        top: 0;
        bottom: 0;
        width: 3px;
        background-color: #1A5336; // Forest Green (Accent)
        border-top-right-radius: 2px;
        border-bottom-right-radius: 2px;
    }
  }
}

.rail-icon-wrapper {
  width: 40px;
  height: 40px;
  @include flex-center;
}

.rail-icon-svg {
  width: 18px;
  height: 18px;
  display: block;
}

.rail-icon-path {
  transition: stroke 0.2s ease;
}

.rail-icon-img {
    width: 18px; /* Resized to 3/4 of 24px */
    height: 18px;
}

.rail-icon {
  font-size: 20px;
}

/* ================= Tabs Bar ================= */
.tabs-bar {
  height: 36px;
  background: $bg-pale; // Match sidebar/bg
  border-bottom: 1px solid $color-border;
  display: flex;
  flex-shrink: 0;

  /* Remove old shadow/z-index if present */
  box-shadow: none;
}

.tabs-pane {
  flex: 1;
  display: flex;
  align-items: flex-end; // Align tabs with bottom border
  min-width: 0;
  border-right: 1px solid transparent;

  &.half-width {
    width: 50%;
    border-right: 1px solid $color-border;
  }
}

.tabs-scroll {
  flex: 1;
  white-space: nowrap;
  overflow: hidden;
  height: 100%;
}

.tabs-list {
  display: flex;
  height: 100%;
  align-items: flex-end; // Tabs sit on the bottom line
  padding-left: 8px; // Slight offset
}

.tab-item {
  height: 32px; // slightly shorter than bar
  display: flex;
  align-items: center;
  padding: 0 12px;
  max-width: 180px;
  background: transparent;
  border-top-left-radius: 6px;
  border-top-right-radius: 6px;
  cursor: pointer;
  color: $color-text-light;
  font-size: 12px;
  transition: all 0.2s;
  border: 1px solid transparent;
  border-bottom: none;
  margin-right: 2px;
  position: relative;

  &:hover {
    background: darken($bg-pale, 3%);
    color: $color-text-main;
  }

  &.active {
    background: $bg-white;
    color: $color-primary; // Forest Green for active text
    font-weight: 500;
    border-color: $color-border;
    border-bottom: 1px solid $bg-white; // Merge with content below
    margin-bottom: -1px; // Push down to cover border
    z-index: 10;

    // Top highlight line for active tab
    &::after {
      content: '';
      position: absolute;
      top: 0;
      left: 2px;
      right: 2px;
      height: 2px;
      background: $color-accent; // Mint Green highlight
      border-top-left-radius: 6px;
      border-top-right-radius: 6px;
    }
  }

  &.tab-drag-over {
    background: $color-accent-pale;
  }
}

.tab-icon {
  margin-right: 6px;
  font-size: 14px;
  opacity: 0.8;
}

.tab-name {
  @include text-ellipsis;
  flex: 1;
}

.tab-close {
  margin-left: 8px;
  font-size: 14px;
  width: 16px;
  height: 16px;
  @include flex-center;
  border-radius: 50%;
  color: transparent; // Hidden by default

  &:hover {
    background: rgba(0,0,0,0.1);
    color: $color-text-main;
  }
}

.tab-item:hover .tab-close,
.tab-item.active .tab-close {
  color: $color-text-light; // Show on hover/active
}

.tabs-plus {
  width: 32px;
  height: 100%;
  @include flex-center;
  cursor: pointer;
  color: $color-text-light;
  transition: color 0.2s;

  &:hover {
    color: $color-primary;
  }
}

.tabs-plus-icon {
  font-size: 16px;
}

/* ================= Editors Grid ================= */
.editors-container {
  flex: 1;
  display: flex;
  flex-direction: column;
  position: relative;
  overflow: hidden;
  background: $bg-white; // Content bg
}

.empty-workspace {
  flex: 1;
  @include flex-center;
  flex-direction: column;
  color: $color-text-light;
  background: $bg-pale;
}

.empty-state-img {
  width: 64px;
  height: 64px;
  margin-bottom: 16px;
  opacity: 0.8;
}

.empty-icon { font-size: 48px; margin-bottom: 16px; opacity: 0.5; } // Keep for fallback/refs if any
.empty-text { font-size: 14px; }

.editors-grid {
  flex: 1;
  display: flex;
  overflow: hidden;
}

.editor-pane {
  display: flex;
  flex-direction: column;
  overflow: hidden;
  position: relative;
  background: $bg-white;

  &.pane-full {
    flex: 1;
    width: 100%;
  }

  &.pane-half {
    flex: 1;
    width: 50%;
    &:first-child { border-right: 1px solid $color-border; }
  }

  &.focused {
    background-color: rgba(91, 209, 151, 0.05);
  }
}

.pane-content {
  flex: 1;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  position: relative;
}

.pane-empty {
  flex: 1;
  @include flex-center;
  flex-direction: column; /* Ensure stacked layout for icon + text */
  color: $color-text-light;
  font-size: 13px;
  background: $bg-pale;
}

/* ================= Bottom Panel ================= */
.bottom-panel {
  flex-shrink: 0;
  display: flex;
  flex-direction: column;
  background: $bg-white;
  border-top: 1px solid $color-border;
  position: relative;
  z-index: 100;
}

.bottom-resize-handle {
  position: absolute;
  top: -4px;
  left: 0;
  right: 0;
  height: 8px;
  cursor: row-resize;
  z-index: 101;

  &:hover {
    background: rgba($color-accent, 0.2); // Visual feedback
  }
}

.panel-header-tools {
  height: 32px; // Compact header
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 12px;
  background: $bg-pale;
  border-bottom: 1px solid $color-border;
}

.panel-tabs {
  display: flex;
  gap: 16px;
}

.panel-tab {
  font-size: 12px;
  color: $color-text-light;
  cursor: pointer;
  padding: 4px 0;
  position: relative;

  &.active {
    color: $color-primary;
    font-weight: 600;

    &::after {
      content: '';
      position: absolute;
      bottom: -1px; // On the line
      left: 0; right: 0;
      height: 2px;
      background: $color-accent;
    }
  }

  &:hover {
    color: $color-text-main;
  }
}

.panel-actions {
  display: flex;
  align-items: center;
}

.panel-body {
  flex: 1;
  overflow: hidden;
  position: relative;
}

.tools-content {
  width: 100%;
  height: 100%;
}


.rail-members-container {
  margin-bottom: 16px;
  position: relative;
  width: 40px;
  height: 40px;
  @include flex-center;

  &:hover {
     .members-expand-panel-left {
        display: flex;
     }
  }
}

.rail-user-avatar {
  width: 36px;
  height: 36px;
  border-radius: 50%;
  background: rgba(255,255,255,0.1);
  @include flex-center;
  cursor: pointer;
  margin-top: 8px;
  border: 2px solid transparent;
  transition: border-color 0.2s;

  &:hover {
    border-color: $color-accent;
  }
}

.avatar-img {
  width: 100%; height: 100%; border-radius: 50%;
}
.avatar-text {
  color: #fff; font-size: 14px; font-weight: 600;
}

/* Sidebar Left (File Tree) */
.sidebar-left {
  background-color: $bg-pale; // Light gray for sidebar
  border-right: 1px solid $color-border;
  display: flex;
  flex-direction: column;
  position: relative;
  transition: width 0.1s ease-out; // Faster transition

  &.collapsed {
    border-right: none;
  }
}

.sidebar-header {
  height: 40px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 12px;
  background-color: $bg-pale;
  border-bottom: 1px solid darken($bg-pale, 5%);
}

.sidebar-title {
  font-size: 11px;
  font-weight: 600;
  text-transform: uppercase;
  color: $color-text-light;
  letter-spacing: 0.5px;
}

.sidebar-actions-row {
  display: flex;
}

.sidebar-actions {
  display: flex;
  gap: 2px;

  .icon-btn.mini {
    width: 18px;
    height: 16px;


    .tool-icon { font-size: 14px; }

    &.active {
       color: $color-primary;
    }
  }
}
/* IDE 工作台（中/右 + 底部面板） */
.workbench {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  background: #ffffff;
  border: none;
  border-radius: 0;
  box-shadow: none;
  overflow: hidden;
}

.workbench-main {
  flex: 1;
  min-width: 0;
  min-height: 0;
  display: flex;
  flex-direction: row;
  gap: 0;
  padding: 0;
  box-sizing: border-box;
}

.page-project-overview.compact-mode .workbench-main {
  padding: 0;
  gap: 0;
}

.side-panel {
  position: relative;
  flex-shrink: 0;
  min-height: 0;
  min-width: 0; /* Allow flex shrinking */
  background: #ffffff;
  border: none;
  border-left: 1px solid rgba(18, 52, 77, 0.08);
  border-radius: 0;
  box-shadow: none;
  display: flex;
  flex-direction: column;
  overflow-x: hidden; /* Prevent horizontal overflow */
  overflow-y: visible; /* Allow vertical overflow for dropdowns */
  box-sizing: border-box;
}

.side-resize-handle {
  position: absolute;
  left: -3px;
  top: 0;
  bottom: 0;
  width: 6px;
  cursor: col-resize;
  z-index: 20;
}

.bottom-panel {
  position: relative;
  flex-shrink: 0;
  min-height: 0;
  background: #f8f9fa;
  border-top: 1px solid rgba(18, 52, 77, 0.08);
  border-radius: 0;
  box-shadow: none;
  display: flex;
  flex-direction: column;
}

.bottom-resize-handle {
  position: absolute;
  top: -3px;
  left: 0;
  right: 0;
  height: 6px;
  cursor: row-resize;
  z-index: 20;
}

.panel-header {
  height: 32px; /* 更紧凑，接近 IDE */
  flex-shrink: 0;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 12px;
  border-bottom: 1px solid $color-border;
  background: #f8f9fa;
}

.panel-title {
  font-size: 13px;
  font-weight: 600;
  color: $color-text-main;
}

.panel-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}

.panel-body {
  flex: 1;
  min-height: 0;
  overflow: hidden;
}

.panel-header-tools {
  height: 30px;
}

.panel-tabs {
  display: flex;
  height: 100%;
  gap: 24px; /* 增加间距 */
}

.panel-tab {
  height: 100%;
  display: flex;
  align-items: center;
  font-size: 13px;
  color: #64748b;
  cursor: pointer;
  position: relative;
  font-weight: 500;
  transition: all 0.2s;
}

.panel-tab:hover {
  color: #1e293b;
}

.panel-tab.active {
  color: #0f172a;
  font-weight: 600;
}

.panel-tab.active::after {
  content: '';
  position: absolute;
  bottom: -1px;
  left: 0;
  right: 0;
  height: 2px;
  background: #0f172a; /* 黑色下划线，更专业 */
}

.tools-search {
  flex: 1;
  max-width: 400px; /* 限制最大宽度 */
  display: flex;
  align-items: center;
}

.tools-search-wrap {
  width: 100%;
  position: relative;
}

.tools-search-input {
  width: 100%;
  height: 32px;
  background: #f1f5f9; /* 浅灰背景 */
  border: 1px solid transparent; /* 默认无边框 */
  border-radius: 6px;
  padding: 0 12px;
  font-size: 13px;
  color: #334155;
  transition: all 0.2s;
}

.tools-search-input:focus {
  background: #fff;
  border-color: #3b82f6; /* 聚焦蓝框 */
  box-shadow: 0 0 0 2px rgba(59, 130, 246, 0.1);
}
.tools-search-clear {
  position: absolute;
  right: 6px;
  top: 50%;
  transform: translateY(-50%);
  width: 18px;
  height: 18px;
  line-height: 18px;
  text-align: center;
  border-radius: 4px;
  color: $color-text-light;
}

.tools-content {
  flex: 1;
  min-height: 0;
  overflow: hidden;
}

.panel-body-ai {
  flex: 1;
  min-height: 0;
  overflow: hidden;
}

/* AI Dropdown Panels (Full width below header) */
.ai-dropdown-panel {
    position: absolute;
    top: 36px; /* Exactly below header (Reverted to 36px) */
    left: 0;
    right: 0;
    background: #fff;
    border-bottom: 1px solid #e2e8f0;
    box-shadow: 0 4px 12px rgba(0,0,0,0.08);
    z-index: 1001; /* Higher than mask (999) to ensure clicks work */
    display: flex;
    flex-direction: column;
    max-height: 400px;
    overflow-y: auto;
    animation: slideDown 0.15s ease-out;
}

@keyframes slideDown {
    from { opacity: 0; transform: translateY(-5px); }
    to { opacity: 1; transform: translateY(0); }
}

.menu-item {
    display: flex;
    align-items: center;
    padding: 10px 12px;
    font-size: 13px;
    color: #334155;
    cursor: pointer;
    border-bottom: 1px solid #f8f9fa;
}
.menu-item:hover { background: #f1f5f9; }
.menu-item.header {
    background: #f8f9fa;
    color: #64748b;
    font-size: 11px;
    font-weight: 600;
    padding: 6px 12px;
    cursor: default;
}
.menu-item.active {
    background: #eff6ff;
    color: #2563eb;
    font-weight: 500;
}
.menu-divider {
    height: 1px;
    background: #e2e8f0;
    margin: 4px 0;
}

.panel-header-ai {
  /* Inherit shared panel-header styles, plus specifics */
  position: relative;
  height: 36px; /* Reverted to 36px per user request */
  padding: 0 12px;
  background: $bg-white;
  border-bottom: 1px solid $color-border;
  flex-shrink: 0; /* Ensure it doesn't shrink */
  display: flex;
  align-items: center;
  justify-content: space-between;
}

/* Ensure Side Panel AI handles absolute children correctly */
.side-panel-ai {
    position: relative;
    display: flex;
    flex-direction: column;
    height: 100%;
    min-width: 0; /* Allow flex shrinking */
    overflow: hidden; /* Contain children within bounds */
    box-sizing: border-box;
    /* Child ChatInterface now handles its own overflow */
}

.ai-history-drawer {
    position: absolute;
    top: 36px; /* Match panel-header-ai height */
    left: 0;
    right: 0;
    bottom: 0;
    background: #fff;
    z-index: 50;
    display: flex;
    flex-direction: column;
    border-top: none; /* Remove border since it's directly under header */
}

/* Dialog Styles */
.dialog-overlay {
    position: fixed;
    top: 0;
    left: 0;
    right: 0;
    bottom: 0;
    background: rgba(0,0,0,0.5);
    z-index: 200;
    display: flex;
    align-items: center;
    justify-content: center;
}
.config-dialog {
    width: 500px;
    background: #fff;
    border-radius: 8px;
    box-shadow: 0 4px 20px rgba(0,0,0,0.15);
    display: flex;
    flex-direction: column;
}
.dialog-header {
    padding: 16px;
    border-bottom: 1px solid #f0f0f0;
    display: flex;
    justify-content: space-between;
    align-items: center;
}
.dialog-title { font-size: 16px; font-weight: 600; color: #333; }
.dialog-close { font-size: 20px; color: #999; cursor: pointer; }
.dialog-content { padding: 16px; display: flex; flex-direction: column; gap: 16px; }
.form-item { display: flex; flex-direction: column; gap: 6px; }
.label { font-size: 13px; color: #666; font-weight: 500; }
.input, .textarea {
    border: 1px solid #e2e8f0;
    border-radius: 4px;
    padding: 8px;
    font-size: 13px;
    width: 100%;
    height: 36px; /* Explicit height fixed */
    box-sizing: border-box;
}
.input.readonly, .textarea.readonly {
    background: #f8f9fa;
    color: #64748b;
    cursor: not-allowed;
}
.textarea { height: 80px; }
.hint { font-size: 12px; color: #f59e0b; margin-top: 4px; }
.dialog-footer {
    padding: 16px;
    border-top: 1px solid #f0f0f0;
    display: flex;
    justify-content: flex-end;
    gap: 12px;
}
.btn-cancel {
    padding: 6px 16px;
    background: #f1f5f9;
    color: #475569;
    border: none;
    border-radius: 4px;
    font-size: 13px;
    cursor: pointer;
}
.btn-save {
    padding: 6px 16px;
    background: $color-primary;
    color: #fff;
    border: none;
    border-radius: 4px;
    font-size: 13px;
    cursor: pointer;
}

.drawer-header {
    padding: 12px;
    border-bottom: 1px solid #f0f0f0;
    display: flex;
    justify-content: space-between;
    align-items: center;
}
.drawer-title {
    font-size: 14px;
    font-weight: 600;
    color: #333;
}
.drawer-close {
    font-size: 18px;
    color: #999;
    cursor: pointer;
}
.drawer-list {
    flex: 1;
    overflow-y: auto;
}
.drawer-item {
    padding: 12px;
    border-bottom: 1px solid #f5f5f5;
    cursor: pointer;
}
.drawer-item:hover {
    background: #f9fafb;
}
.item-title {
    font-size: 13px;
    font-weight: 500;
    color: #333;
    display: block;
    margin-bottom: 4px;
}
.item-time {
    font-size: 11px;
    color: #999;
}
.item-preview {
    font-size: 12px;
    color: #666;
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
    display: block;
    margin-top: 4px;
}
.drawer-empty, .drawer-loading {
    padding: 24px;
    text-align: center;
    color: #999;
    font-size: 13px;
}

.panel-body-ai {
  display: flex;
  flex-direction: column;
  height: 100%;
  overflow: hidden;
}

/* 左侧 Sidebar */
.sidebar-left {
  width: 260px;
  background: #fbfcfe;
  border: none;
  border-right: 1px solid rgba(18, 52, 77, 0.08);
  border-radius: 0;
  box-shadow: none;
  display: flex;
  flex-direction: column;
  transition: width 0.3s ease;
  flex-shrink: 0;
  position: relative;
  overflow: hidden;

  &.collapsed {
    width: 0;
    border-right: none;
    box-shadow: none;
  }
}

/* 拖拽期间禁用过渡：提升跟手性，避免“抖动/滞后” */
.page-project-overview.is-resizing .sidebar-left,
.page-project-overview.is-resizing .side-panel,
.page-project-overview.is-resizing .bottom-panel {
  transition: none !important;
}

.sidebar-header {
  display: flex;
  flex-direction: row;
  align-items: center;
  justify-content: space-between;
  padding: 0 12px;
  height: 36px; /* Align with tabs-bar height */
  border-bottom: 1px solid $color-border;
  background: $bg-white;
  flex-shrink: 0;
  gap: 8px;
}

.sidebar-title-row {
  display: flex;
  align-items: center;
  flex: 1;
  min-width: 0;
  line-height: 1;
  margin-bottom: 0;
}

.sidebar-actions-row {
  display: flex;
  align-items: center;
  width: auto;
  line-height: 1;
}

.sidebar-actions {
  display: flex;
  align-items: center;
  gap: 1px;

  width: auto;
}

.sidebar-actions-divider {
  width: 1px;
  height: 14px;
  background: rgba(18, 52, 77, 0.12);
  margin: 0 4px;
  flex-shrink: 0;
}

.batch-menu-wrapper {
  position: relative;
}

.batch-menu-mask {
  position: fixed;
  inset: 0;
  background: transparent;
  z-index: 90;
}

.batch-menu {
  position: absolute;
  top: 24px;
  right: 0;
  width: 140px;
  background: #fff;
  border: 1px solid $color-border;
  border-radius: 6px;
  box-shadow: 0 4px 12px rgba(0,0,0,0.1);
  padding: 4px;
  z-index: 100;
}

.batch-menu-item {
  height: 30px;
  padding: 0 8px;
  border-radius: 4px;
  display: flex;
  align-items: center;
  cursor: pointer;
  color: $color-text-main;
}

.batch-menu-item:hover {
  background: rgba(148, 163, 184, 0.14);
}

.batch-menu-item.danger {
  color: #dc2626;
}

.batch-menu-item.disabled {
  opacity: 0.45;
  pointer-events: none;
}

.batch-menu-label {
  font-size: 12px;
}

.icon-btn.disabled {
  opacity: 0.45;
  pointer-events: none;
}

.icon-btn.mini {
  width: 18px;
  height: 16px;

  border-radius: 4px;
  display: flex;
  align-items: center;
  justify-content: center;
  transition: all 0.1s;
  background: transparent;
  border: none !important;
  outline: none !important;
  box-shadow: none !important;
}

.icon-btn.mini:hover {
  background-color: #E8F3ED; /* Mint Green Lightest */
  border: none !important;
  outline: none !important;
}

.icon-btn.mini.active {
  background: rgba(91, 209, 151, 0.2) !important;
  border: 1px solid rgba(26, 83, 54, 0.2) !important;
  box-shadow: none !important;
  outline: none !important;
}


.icon-btn.mini:focus {
    outline: none !important;
    border: none !important;
}

.icon-btn.mini .tool-icon {
  font-size: 14px;
  font-weight: 400;
  color: #666;
}

.icon-btn.mini .tool-icon-img {
  width: 17px;
  height: 17px;
  display: block;
}

.sidebar-title {
  font-size: 13px;
  font-weight: 600;
  color: $color-text-light; /* Lighter color */
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  flex: 1;
  min-width: 0;
  transform: scale(0.95);
  transform-origin: left center;
}

/* 左侧收起态：Cursor 风格插件栏 */
/* 左侧收起态：Cursor 风格插件栏 */
.sidebar-collapsed-bar {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  padding: 10px 0;
  gap: 10px;
  background: $bg-white;
}

.sidebar-title-row {
  flex: 1;
  min-width: 0;
  display: flex;
  align-items: center;
}

.btn-select-all {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  padding: 4px 8px;
  background-color: #E8F3ED; /* Mint Green Lightest */
  color: #1A5336; /* Forest Green */
  font-size: 11px;
  font-weight: 600;
  border-radius: 4px;
  cursor: pointer;
  line-height: 1;
  transition: all 0.2s;
  white-space: nowrap; /* Prevent vertical text */
  flex-shrink: 0;
}

.btn-select-all:hover {
    opacity: 0.8;
}

.sidebar-plugin-btn {
  width: 34px;
  height: 34px;
  border-radius: 10px;
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  border: 1px solid transparent;
  color: $color-text-light;
  background: transparent;
  transition: background 0.18s ease, border-color 0.18s ease;
}

.sidebar-plugin-btn:hover {
  background: rgba(18, 52, 77, 0.06);
}

.sidebar-plugin-btn.active {
  background: rgba(18, 52, 77, 0.08);
  border-color: rgba(18, 52, 77, 0.12);
  box-shadow: inset 0 -2px 0 $color-accent;
}

.plugin-icon {
  font-size: 16px;
  line-height: 1;
}

.sidebar-plugin-placeholder {
  padding: 16px 12px;
}

.placeholder-title {
  display: block;
  font-size: 13px;
  font-weight: 600;
  color: $color-text-main;
}

.placeholder-desc {
  display: block;
  margin-top: 6px;
  font-size: 12px;
  line-height: 1.5;
  color: $color-text-light;
}

.sidebar-content {
  flex: 1;
  overflow-y: auto;
  overflow-x: hidden;

  &::-webkit-scrollbar {
    width: 6px;
  }

  &::-webkit-scrollbar-track {
    background: transparent;
  }

  &::-webkit-scrollbar-thumb {
    background: #CBD5E1;
    border-radius: 3px;
    transition: background 0.15s ease;

    &:hover {
      background: #64748B;
    }
  }
}

.resize-handle {
  position: absolute;
  right: -3px;
  top: 0;
  bottom: 0;
  width: 6px;
  cursor: col-resize;
  z-index: 20;
}

.resize-handle:hover,
.side-resize-handle:hover,
.bottom-resize-handle:hover {
  background-color: rgba(18, 52, 77, 0.06);
}

/* 中间内容区 */
.content-area {
  flex: 1;
  display: flex;
  flex-direction: column;
  min-width: 0;
  background: #ffffff;
  border: none;
  border-radius: 0;
  box-shadow: none;
  overflow: hidden;
}

/* 标签栏 Tabs */
.tabs-bar {
  height: 36px;
  background: #f8f9fa;
  border-bottom: 1px solid rgba(18, 52, 77, 0.08);
  backdrop-filter: none;
  display: flex;
  align-items: center;
  justify-content: flex-start;
}

.tabs-pane {
  flex: 1;
  height: 100%;
  min-width: 0;
  display: flex;
  border-right: 1px solid transparent;

  &.half-width {
    flex: 0 0 50%; /* 强制占50% */
    max-width: 50%;
    border-right-color: $color-border;
  }
}

.tabs-plus {
  flex: 0 0 auto;
  width: 28px;
  height: 28px;
  margin: 0 8px 0 0;
  align-self: center;
  border-radius: 10px;
  border: 1px solid rgba(15, 23, 42, 0.08);
  background: rgba(15, 23, 42, 0.02);
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
}

.tabs-plus:hover {
  background-color: rgba(255, 255, 255, 0.8);
  border-color: rgba(200, 164, 93, 0.4);
}

.tabs-plus-icon {
  font-size: 16px;
  font-weight: 800;
  color: $color-primary;
  line-height: 1;
}

.tabs-scroll {
  width: 100%;
  height: 100%;
}

.tabs-list {
  display: flex;
  flex-direction: row;
  height: 100%;
  padding: 0;
  align-items: center;
}

/* tabs-tools 仍可能存在旧样式：隐藏（按钮已上移至 header-tools） */
.tabs-tools {
  display: none;
}

/* 底部工具面板：按钮不随宽度拉伸（覆盖 VariablePanel 的 width:100%） */
.bottom-panel :deep(.panel-actions) {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
}

.bottom-panel :deep(.action-btn) {
  width: auto !important;
  max-width: 360px;
  min-width: 240px;
}

.bottom-panel :deep(.var-tools button) {
  flex: 0 0 auto;
  min-width: 72px;
}

.tab-item {
  position: relative;
  height: 28px;
  padding: 0 10px;
  display: flex;
  align-items: center;
  gap: 8px;
  background: rgba(15, 23, 42, 0.02);
  border-radius: 10px;
  margin-right: 6px;
  cursor: pointer;
  min-width: 100px;
  max-width: 180px;
  user-select: none;
  border: 1px solid rgba(15, 23, 42, 0.06);
  transition: background-color 0.18s ease, border-color 0.18s ease;

  &:hover {
    border-color: rgba(200, 164, 93, 0.55);
    background: rgba(200, 164, 93, 0.06);
  }

  .tab-name {
    flex: 1;
    font-size: 13px;
    color: $color-text-light;
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
  }

  .tab-close {
    font-size: 16px;
    color: #999;
    width: 16px;
    height: 16px;
    display: flex;
    align-items: center;
    justify-content: center;
    border-radius: 50%;

    &:hover {
      background: rgba(0,0,0,0.1);
      color: #333;
    }
  }

  &.active {
    background: rgba(18, 52, 77, 0.06);
    border-color: rgba(18, 52, 77, 0.18);
    box-shadow: inset 0 -2px 0 rgba($color-accent, 0.8);

    .tab-name { color: $color-text-main; font-weight: 600; }
  }
}

.tab-item.tab-drag-over {
  border-color: rgba(200, 164, 93, 0.55);
  background: rgba(200, 164, 93, 0.08);
}

.tab-item.tab-dual-open {
  position: relative;
}

.tab-item.tab-dual-open::after {
  content: '';
  position: absolute;
  right: 8px;
  bottom: 6px;
  width: 6px;
  height: 6px;
  border-radius: 999px;
  background: rgba(37, 99, 235, 0.75);
}

.page-project-overview.compact-mode .tab-item {
  min-width: 88px;
  padding: 0 10px;
}

.tabs-tools {
  display: flex;
  align-items: center;
  padding-left: 8px;
}

.icon-btn {
  width: 28px;
  height: 28px;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 8px;
  border: 1px solid rgba(15, 23, 42, 0.08);
  cursor: pointer;
  color: $color-text-light;
  background: rgba(15, 23, 42, 0.02);
  transition: all 0.18s ease;

  &:hover {
    background-color: rgba(255, 255, 255, 0.8);
    border-color: rgba(200, 164, 93, 0.4);
    color: $color-primary;
  }

  &.active {
    background: linear-gradient(120deg, rgba(255,255,255,0.95), rgba(248, 241, 228, 0.9));
    color: $color-primary;
    border-color: rgba(200, 164, 93, 0.6);
    box-shadow: 0 4px 10px rgba(200, 164, 93, 0.16);
  }

  .tool-icon {
    font-size: 16px;
  }
}

/* 编辑器区域 */
.editors-container {
  flex: 1;
  overflow: hidden;
  position: relative;
  min-height: 0;
}

.editors-grid {
  width: 100%;
  height: 100%;
  display: flex;
  flex-direction: row;
}

.editor-pane {
  flex: 1; /* 默认占满 */
  height: 100%;
  display: flex;
  flex-direction: column;
  position: relative;
  min-width: 0; /* 防止内容撑大 */
  transition: all 0.2s ease-in-out; /* 更好的过渡 */
  overflow: hidden;
  border-radius: 18px;
  border: 1px solid rgba(15, 23, 42, 0.05);
  background: rgba(249, 250, 255, 0.82);
  box-shadow: inset 0 1px 0 rgba(255,255,255,0.6);

  &.focused {
    /* 聚焦时的高亮提示 */
    box-shadow: inset 0 0 0 2px rgba(18, 52, 77, 0.1);
    z-index: 1;
  }

  /* 显式控制宽度 */
  &.pane-full {
    width: 100%;
    flex: 0 0 100%;
    max-width: 100%;
  }

  &.pane-half {
    width: 50%;
    flex: 0 0 50%;
    max-width: 50%;
    border-right: 1px solid $color-border;

    &:last-child {
      border-right: none;
    }
  }
}

.pane-content {
  width: 100%;
  height: 100%;
  overflow: hidden; /* 确保内部组件不溢出 */
  border-radius: 16px;
  background: #fff;
  box-shadow: inset 0 0 0 1px rgba(15, 23, 42, 0.03);
}

.pane-empty, .empty-workspace {
  width: 100%;
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  background: #fcfcfc;
  color: #ccc;
  font-size: 14px;
}

.empty-content {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 16px;
}

.empty-icon {
  font-size: 48px;
  color: #eee;
}

/* 右侧吸附抽屉 */
.drawer-container {
  position: absolute;
  top: 0;
  right: 0;
  bottom: 0;
  display: flex;
  flex-direction: row;
  z-index: 50;
  transform: translateX(300px); /* 默认收起内容，只留把手 */
  transition: transform 0.3s cubic-bezier(0.4, 0, 0.2, 1);
  pointer-events: none; /* 收起时不遮挡底层点击，把手单独开启 pointer-events */

  &.expanded {
    transform: translateX(0);
    pointer-events: auto;
    box-shadow: -4px 0 16px rgba(0,0,0,0.08);
  }
}

.drawer-handle {
  width: 24px;
  height: 64px; /* 加高一点 */
  background: $color-primary; /* 使用主色调 */
  border-radius: 8px 0 0 8px;
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;

  /* 垂直居中定位 */
  position: absolute;
  left: -24px; /* 把它移到 content 左边 */
  top: 50%;
  transform: translateY(-50%);

  box-shadow: -2px 2px 4px rgba(0,0,0,0.1);
  pointer-events: auto; /* 始终允许点击 */
  z-index: 51;

  .handle-icon {
    font-size: 18px;
    color: #fff; /* 白色箭头 */
    line-height: 1;
  }

  &:hover {
    background-color: #1a4a6b; /* hover 变亮一点 */
    width: 28px; /* hover 变宽一点点作为反馈 */
    left: -28px;
  }
}

/* 收起时的标签容器（气泡） */
.handle-label-container {
  position: absolute;
  right: 36px; /* 距离把手左侧一点 */
  background: #333;
  color: #fff;
  font-size: 12px;
  padding: 6px 10px;
  border-radius: 4px;
  white-space: nowrap;
  opacity: 0;
  transition: opacity 0.2s;
  pointer-events: none;

  /* 小三角 */
  &::after {
    content: '';
    position: absolute;
    right: -4px;
    top: 50%;
    transform: translateY(-50%);
    border-left: 4px solid #333;
    border-top: 4px solid transparent;
    border-bottom: 4px solid transparent;
  }
}

.drawer-handle:hover .handle-label-container {
  opacity: 1;
}

.drawer-content {
  width: 300px;
  background: $bg-white; /* 确保不透明 */
  border-left: 1px solid $color-border;
  display: flex;
  flex-direction: column;
  height: 100%;
  pointer-events: auto;
  position: relative;
  z-index: 50;
  box-shadow: -2px 0 10px rgba(0,0,0,0.05); /* 内部阴影增强层次 */
}

/* 抽屉顶部 Tabs */
.drawer-tabs-bar {
  height: 44px;
  display: flex;
  flex-direction: row;
  align-items: flex-end;
  padding: 0 12px;
  border-bottom: 1px solid #eee;
  background: #fff;
  flex-shrink: 0;
}

.drawer-tab-item {
  margin-right: 16px;
  padding: 8px 4px;
  font-size: 13px;
  color: $color-text-light;
  position: relative;
  cursor: pointer;

  &.active {
    color: $color-primary;
    font-weight: 600;
  }

  &.active::after {
    content: '';
    position: absolute;
    left: 0;
    right: 0;
    bottom: 0;
    height: 2px;
    background: linear-gradient(90deg, $color-accent, $color-primary);
    border-radius: 999px;
  }
}

.drawer-tab-text {
  white-space: nowrap;
}

/* AI 对话 + 工具布局 */
.drawer-ai-layout {
  flex: 1;
  display: flex;
  flex-direction: row;
  min-height: 0;
}

.ai-chat-pane {
  flex: 3;
  display: flex;
  flex-direction: column;
  border-right: 1px solid rgba($color-border, 0.7);
  min-width: 0;
  overflow: hidden; /* Prevent children from overflowing */
  box-sizing: border-box;
}

.ai-tool-pane {
  flex: 2;
  display: flex;
  flex-direction: column;
  min-width: 0;
}

.ai-context-card {
  padding: 10px 10px;
  border: 1px solid rgba($color-border, 0.85);
  background: #fbfcfe;
  border-radius: 12px;
  margin: 0 10px 8px;
  box-shadow: none;
}

.context-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 10px;
}

.context-file {
  display: flex;
  flex-direction: column;
}

.context-label {
  font-size: 12px;
  color: $color-text-light;
  text-transform: uppercase;
  letter-spacing: 0.06em;
}

.context-value {
  font-size: 13px;
  font-weight: 600;
  color: $color-text-main;
  margin-top: 2px;
  max-width: 220px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.context-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  justify-content: flex-end;
}

.context-status {
  font-size: 12px;
  color: $color-text-light;
  white-space: nowrap;
}

.context-status.synced {
  color: $color-accent;
}

.context-action-btn {
  padding: 0 10px;
  height: 24px;
  border-radius: 999px;
  border: 1px solid rgba($color-border, 0.75);
  background: rgba(15, 23, 42, 0.02);
  font-size: 12px;
  color: $color-primary;
  display: flex;
  align-items: center;
  cursor: pointer;
  white-space: nowrap;
}

.context-action-btn.disabled {
  opacity: 0.6;
  pointer-events: none;
}

.context-snippet {
  margin-top: 12px;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.context-snippet-row {
  display: flex;
  gap: 8px;
}

.context-snippet-label {
  flex-shrink: 0;
  font-size: 12px;
  color: $color-text-light;
  text-transform: uppercase;
}

.context-snippet-text {
  font-size: 13px;
  line-height: 1.5;
  color: $color-text-main;
  white-space: pre-wrap;
}

/* refined close button */
.panel-actions .icon-btn {
  width: 28px; height: 28px;
  display: flex; align-items: center; justify-content: center;
  border-radius: 6px;
  color: #94a3b8;
  cursor: pointer;
  background: transparent; /* Remove background */
  transition: all 0.2s;
  border: none;
}
.panel-actions .icon-btn:hover {
  background: #f1f5f9;
  color: #ef4444; /* Red on hover for close */
}

/* refined search bar */
/* refined search bar */
.tools-search {
  flex: 1;
  max-width: 240px; /* Even smaller */
  display: flex; /* Make it a flex container */
  align-items: center;
  position: relative; /* For absolute clear btn */
}

/* Parent container (panel-header-tools) needs alignment update via previous steps or verify current flex.
 * Assuming panel-header-tools is flex with justify-space-between or explicit gaps.
 * We want the search bar to sit near the tabs.
 */
.panel-header-tools .panel-tabs {
  margin-right: 20px; /* gap between tabs and search */
}
.panel-header-tools .tools-search {
  margin: 0; /* Remove auto margins */
}

.tools-search-input {
  width: 100%;
  height: 30px; /* Slightly smaller */
  background: #fff; /* White background */
  border: 1px solid #e2e8f0; /* Subtle border */
  border-radius: 999px; /* Pill shape for modern look */
  padding: 0 16px;
  font-size: 13px;
  color: #334155;
  transition: all 0.2s;
  box-shadow: 0 1px 2px rgba(0,0,0,0.03); /* Subtle shadow */
}

.tools-search-input:focus {
  border-color: #94a3b8;
  box-shadow: 0 0 0 3px rgba(148, 163, 184, 0.1);
}

.context-snippet.placeholder {
  font-size: 12px;
  color: $color-text-light;
}

/* Removed duplicate .panel-header-ai and .ai-tabs styles to avoid conflict */
/* Removed duplicate .panel-header-ai and .ai-tabs styles to avoid conflict */

.ai-context-drawer:hover {
  background: #f1f5f9;
}

.context-label { font-size: 12px; color: #94a3b8; margin-right: 4px; }
.context-current-name { font-size: 12px; color: #334155; font-weight: 500; flex: 1; }
.context-arrow { font-size: 10px; color: #94a3b8; margin-left: 8px; }

/* Dropdown Menu shared styles */
.context-dropdown-menu, .model-dropdown-menu {
  position: absolute;
  background: #fff;
  border: 1px solid #e2e8f0;
  border-radius: 8px;
  box-shadow: 0 10px 25px -5px rgba(0, 0, 0, 0.1), 0 8px 10px -6px rgba(0, 0, 0, 0.1);
  z-index: 9999; /* Max z-index */
  padding: 4px 0;
  animation: fadeIn 0.1s ease-out;
}

.context-dropdown-menu {
  top: 100%; left: 16px; width: 320px; /* Widened for file tree */
  max-height: 400px;
  display: flex;
  flex-direction: column;
}

.context-file-tree-wrapper {
  flex: 1;
  min-height: 150px;
  overflow: hidden;
  border-top: 1px solid #f1f5f9;
  display: flex;
  flex-direction: column;
}

.model-dropdown-menu {
  bottom: 100%; left: 0; width: 200px; margin-bottom: 6px;
}

.menu-item, .model-option {
  padding: 8px 12px;
  font-size: 13px;
  color: #334155;
  cursor: pointer;
}

.menu-item:hover, .model-option:hover { background: #f1f5f9; }
.menu-item.header { font-size: 11px; color: #94a3b8; pointer-events: none; padding-bottom: 4px; }
.menu-item.active, .model-option.active { color: #3b82f6; background: #eff6ff; font-weight: 500; }
.menu-divider { height: 1px; background: #f1f5f9; margin: 4px 0; }

.dropdown-fixed-mask {
  position: fixed;
  top: 0; left: 0; right: 0; bottom: 0;
  z-index: 999; /* Higher than page content, lower than dropdown */
  background: transparent;
  cursor: default;
}

/* Chat Body Refinements */
.ai-chat-body {
  flex: 1;
  background: #fff;
  min-height: 0;
  overflow-y: auto;
}

.ai-chat-padding {
  padding: 20px 20px 40px; /* Add breathing room around chat bubbles */
}

.ai-message {
  margin-bottom: 20px; /* More space between messages */
  display: flex;
}

/* Model Dropdown */
.ai-model-select {
  position: relative;
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 4px 8px;
  border-radius: 4px;
  cursor: pointer;
  background: #f8f9fa;
  transition: background 0.1s;
}

.model-dropdown-menu {
  position: absolute;
  bottom: 100%; /* Show ABOVE the input */
  left: 0;
  margin-bottom: 8px;
  width: 180px;
  background: #fff;
  border: 1px solid #e2e8f0;
  border-radius: 8px;
  box-shadow: 0 4px 12px rgba(0,0,0,0.08);
  z-index: 1000;
  padding: 4px 0;
}

.model-option {
  padding: 8px 12px;
  font-size: 12px;
  color: #334155;
  cursor: pointer;
}

.model-option:hover { background: #f1f5f9; }
.model-option.active { color: #3b82f6; background: #eff6ff; }

/* ... existing styles ... */

.ai-message-user {
  justify-content: flex-end;
}

.ai-message-assistant {
  justify-content: flex-start;
}

.ai-message-bubble {
  max-width: 92%;
  padding: 10px 14px;
  border-radius: 10px; /* Slightly squarer */
  background: #f8f9fa;
  border: 1px solid #f1f5f9; /* Subtle border */
  box-shadow: none; /* Flat design */
}

.ai-message-user .ai-message-bubble {
  background: #eff6ff; /* Very light blue */
  color: #1e293b;
  border-color: #dbeafe;
}

.ai-message-role {
  display: none; /* Hide role for cleaner look, bubbles imply it */
}

.ai-message-content {
  display: block;
  font-size: 13px;
  line-height: 1.6;
  color: #334155;
  white-space: pre-wrap;
  text-align: justify; /* Two-ended alignment */
}

.ai-message-user .ai-message-content {
  color: #1e293b;
}

.ai-message-actions {
  margin-top: 8px;
  display: flex;
  justify-content: flex-start; /* Organize actions */
  gap: 12px;
  border-top: 1px solid rgba(0,0,0,0.03);
  padding-top: 6px;
}

.ai-export-btn {
  font-size: 11px;
  color: #64748b;
  cursor: pointer;
  transition: color 0.1s;
}

.ai-export-btn:hover {
  color: #0f172a;
}

.ai-export-btn.primary {
  color: #3b82f6;
  font-weight: 500;
}

.ai-empty-tip {
  margin-top: 40px;
  font-size: 13px;
  color: #94a3b8;
  text-align: center;
}

/* Input Area (Cursor Style) */
.ai-input-wrapper {
  margin: 16px;
  background: #fff;
  border: 1px solid #e2e8f0;
  border-radius: 8px;
  box-shadow: 0 4px 12px rgba(0,0,0,0.02);
  display: flex;
  flex-direction: column;
  position: relative; /* Anchor for dropdown */
  /* max-height: 400px;  Previous value */
  max-height: 200px; /* User requested 200px */
}

.ai-input-wrapper:focus-within {
  border-color: #cbd5e1;
  box-shadow: 0 4px 12px rgba(0,0,0,0.05);
}

.ai-input-scroller {
  flex: 1;
  overflow-y: auto;
  overflow-x: hidden; /* Disable horizontal scroll */
  display: flex;
  flex-direction: column;
  min-height: 40px;
  width: 100%; /* Ensure width constraint */
}

/* Pasted Images */
.ai-attachments-preview {
    display: flex;
    flex-wrap: wrap;
    gap: 8px;
    padding: 8px 12px 0;
}
.attachment-thumb-wrap {
    position: relative;
    width: 38px; /* 4/5 of 48px */
    height: 38px;
    border-radius: 4px;
    overflow: hidden;
    border: 1px solid #e2e8f0;
}
.attachment-thumb {
    width: 100%;
    height: 100%;
    object-fit: cover;
}
.attachment-remove {
    position: absolute;
    top: 0;
    right: 0;
    background: rgba(0,0,0,0.5);
    color: #fff;
    width: 14px;
    height: 14px;
    font-size: 10px;
    display: flex;
    align-items: center;
    justify-content: center;
    cursor: pointer;
    border-bottom-left-radius: 4px;
}

/* Replaces old textarea style */
.ai-rich-input {
  width: 100%;
  min-height: 40px;
  padding: 12px;
  font-size: 14px;
  line-height: 1.6;
  color: #1e293b;
  outline: none;
  white-space: pre-wrap;
  word-wrap: break-word;
  word-break: break-all; /* Force break to prevent horizontal scroll */
  text-align: justify;
  overflow-x: hidden;
}

.ai-rich-input.empty::before {
  content: '输入指令 (Enter 换行，⌘/Ctrl + Enter 发送)...';
  color: #94a3b8;
  pointer-events: none;
  display: block;
}

/* Inline Tag Style - AI Workdeck Brand Colors */
.ai-tag {
  display: inline-flex;
  align-items: center;
  background: linear-gradient(135deg, #1A5336 0%, #2D7A52 100%); /* Forest Green gradient */
  color: #FFFFFF;
  padding: 2px 8px;
  border-radius: 4px;
  margin: 0 4px 2px 0;
  font-size: 12px;
  font-weight: 500;
  vertical-align: middle;
  border: none;
  user-select: none;
  max-width: 10em; /* Max ~10 characters */
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  flex-shrink: 1;
  min-width: 2.5em;
  box-shadow: 0 1px 3px rgba(26, 83, 54, 0.2);
  transition: all 0.15s ease;
}

.ai-tag:hover {
  background: linear-gradient(135deg, #2D7A52 0%, #1A5336 100%);
  box-shadow: 0 2px 6px rgba(26, 83, 54, 0.3);
}

.ai-input-footer {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 8px 12px;
  border-top: 1px solid #f8f9fa;
}

.ai-model-select {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 4px 8px;
  border-radius: 4px;
  cursor: pointer;
  background: #f8f9fa;
  transition: background 0.1s;
}

.ai-model-select:hover {
  background: #f1f5f9;
}

.model-name { font-size: 11px; color: #64748b; font-weight: 500; }
.model-arrow { font-size: 10px; color: #94a3b8; }

.ai-send-round-btn {
  width: 26px; height: 26px;
  border-radius: 999px;
  background: #0f172a;
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  transition: all 0.2s;
}

.ai-send-round-btn:hover { background: #334155; }
.ai-send-round-btn.disabled { opacity: 0.5; cursor: not-allowed; }
.send-icon { color: #fff; font-size: 14px; line-height: 1; }

.drawer-header {
  height: 48px;
  border-bottom: 1px solid #eee;
  display: flex;
  align-items: center;
  padding: 0 16px;
  background: #fff;
  flex-shrink: 0;

  &.with-back {
    justify-content: space-between;
  }
}

.back-link {
  display: flex;
  align-items: center;
  gap: 4px;
  cursor: pointer;
  color: $color-accent;
  font-size: 13px;

  &:hover {
    opacity: 0.8;
  }
}

.back-arrow {
  font-size: 16px;
}

.drawer-title {
  font-weight: 600;
  color: $color-text-main;
  font-size: 14px;
}

.drawer-body {
  flex: 1;
  overflow-y: auto;
  background: #fff;

  &.empty-body {
    display: flex;
    align-items: center;
    justify-content: center;
    color: #999;
    font-size: 12px;
  }
}

@media (max-width: 768px) {
  .project-header {
    flex-direction: column;
    align-items: flex-start;
  }

  .header-right {
    width: 100%;
    justify-content: flex-end;
    gap: 8px;
  }
}

/* 工具菜单样式 */
.tool-menu-view {
  display: flex;
  flex-direction: column;
  height: 100%;
}

.tool-list {
  padding: 16px;
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.tool-item {
  display: flex;
  flex-direction: column;
  background: #fcfcfc;
  border: 1px solid #eee;
  border-radius: 8px;
  padding: 16px;
  cursor: pointer;
  transition: all 0.2s;
  position: relative;

  &:hover {
    border-color: $color-accent;
    box-shadow: 0 2px 8px rgba(91, 209, 151, 0.2);
    background: #fff;
  }
}

.tool-icon-box {
  font-size: 24px;
  margin-bottom: 8px;
}

.tool-name {
  font-size: 14px;
  font-weight: 600;
  color: $color-text-main;
  margin-bottom: 4px;
}

.tool-desc {
  font-size: 12px;
  color: $color-text-light;
}

/* 详情视图容器 */
.tool-detail-view {
  display: flex;
  flex-direction: column;
  height: 100%;
}

/* 导出 Word 对话框复用上传/文件夹样式 */
.upload-mask {
  position: fixed;
  top: 0;
  left: 0;
  right: 0;
  bottom: 0;
  background-color: rgba(0, 0, 0, 0.35);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 1000;
}

.image-preview-mask {
  position: fixed;
  top: 0;
  left: 0;
  right: 0;
  bottom: 0;
  background-color: rgba(0, 0, 0, 0.8);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 1000;
  cursor: zoom-out;
}

.image-preview-img {
  width: 90%;
  height: 90%;
}

.image-preview-close {
  position: absolute;
  top: 24px;
  right: 32px;
  width: 36px;
  height: 36px;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 50%;
  background-color: rgba(255, 255, 255, 0.15);
  color: #fff;
  font-size: 18px;
  cursor: pointer;
}

.ocr-modal {
  width: 880px;
  max-width: 92vw;
}

.ocr-body {
  max-height: 70vh;
  overflow-y: auto;
}

.ocr-overlay {
  position: fixed;
  inset: 0;
  z-index: 9999;
  background: rgba(0, 0, 0, 0.35);
  cursor: crosshair !important;
}

.ocr-frame-img {
  position: fixed;
  inset: 0;
  width: 100%;
  height: 100%;
  pointer-events: none;
  /* 让“网页截图区域”也有明确的截图态视觉（避免看起来像仍在操作真实网页） */
  filter: brightness(0.88) contrast(0.98);
}

.ocr-frame-loading {
  position: fixed;
  inset: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  color: rgba(255,255,255,0.9);
  font-size: 13px;
  pointer-events: none;
}

.ocr-frame-shade {
  position: fixed;
  inset: 0;
  background: rgba(0,0,0,0.18);
  pointer-events: none;
}

.ocr-overlay-topbar {
  position: absolute;
  top: 14px;
  left: 50%;
  transform: translateX(-50%);
  background: rgba(255, 255, 255, 0.92);
  border: 1px solid rgba(224, 224, 224, 0.8);
  border-radius: 12px;
  padding: 10px 12px;
  display: flex;
  align-items: center;
  gap: 10px;
  max-width: 92vw;
}

.ocr-overlay-title {
  font-size: 13px;
  font-weight: 700;
  color: #12344D;
}

.ocr-overlay-hint {
  font-size: 12px;
  color: #64748b;
  max-width: 520px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.ocr-overlay-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}

.ocr-overlay-btn {
  height: 28px;
  line-height: 28px;
  padding: 0 10px;
  border-radius: 10px;
  border: 1px solid rgba(148, 163, 184, 0.35);
  background: #fff;
  font-size: 12px;
  color: #12344D;
}

.ocr-selection {
  position: fixed;
  border: 2px solid rgba(37, 99, 235, 0.75);
  background: rgba(37, 99, 235, 0.12);
  border-radius: 6px;
  pointer-events: none;
}

.ocr-overlay-hintline {
  position: fixed;
  left: 14px;
  top: 14px;
  padding: 6px 10px;
  background: rgba(255, 255, 255, 0.88);
  border: 1px solid rgba(224, 224, 224, 0.7);
  border-radius: 10px;
  font-size: 12px;
  color: #12344D;
  z-index: 10000;
  pointer-events: none;
}

.ocr-actionbar {
  position: fixed;
  z-index: 10001;
  background: #FFFFFF;
  border: 1px solid #E9ECEF;
  border-radius: 8px;
  padding: 6px;
  box-shadow: 0 4px 12px rgba(0,0,0,0.08); /* Soft shadow */
  transform: translateX(-50%); /* Center horizontally based on center point */
}

.ocr-actionbar-row {
  display: flex;
  align-items: center;
  gap: 4px; /* Tighter gap */
  flex-wrap: wrap;
}

.ocr-action {
  height: 32px;
  line-height: 32px;
  padding: 0 12px;
  border-radius: 6px;
  border: none;
  background: transparent;
  font-size: 13px;
  color: #1A5336; /* Forest Green */
  cursor: pointer;
  user-select: none;
  font-weight: 500;
  transition: all 0.2s;
}

.ocr-action:hover {
  background: #E6F9F0; /* Mint Lightest */
  color: #1A5336;
}

.ocr-action.primary {
  background: #1A5336;
  border-color: transparent;
  color: #fff;
}

.ocr-action.primary:hover {
  background: #2D7A52; /* Lighter Forest */
}

.ocr-action.disabled {
  opacity: 0.45;
  pointer-events: none;
}

.webmark-drag-overlay {
  position: fixed;
  inset: 0;
  z-index: 10003; /* 高于 ghost（10002），确保拖拽时事件不落到 WPS iframe */
  background: transparent;
  pointer-events: auto;
  cursor: grabbing;
}



.webmark-drag-ghost {
  position: fixed;
  z-index: 10002; /* 高于 OCR hintline */
  width: 180px;
  height: 120px;
  border-radius: 12px;
  overflow: hidden;
  box-shadow: 0 12px 35px rgba(15, 23, 42, 0.18);
  border: 1px solid rgba(37, 99, 235, 0.25);
  pointer-events: none; /* 不挡鼠标 hit-test */
  opacity: 0.82;
}

.webmark-ghost-img {
  width: 100%;
  height: 100%;
}

.webmark-ghost-badge {
  position: absolute;
  left: 8px;
  top: 8px;
  padding: 4px 8px;
  border-radius: 999px;
  background: rgba(17, 24, 39, 0.72);
  color: #fff;
  font-size: 12px;
  font-weight: 700;
}


.folder-modal {
  width: 800rpx; /* Increased to 800rpx */
  max-width: 95vw;
  background-color: #ffffff;
  border-radius: 16rpx;
  box-shadow: 0 12rpx 32rpx rgba(0, 0, 0, 0.08);
  display: flex;
  flex-direction: column;
}

.upload-header {
  padding: 32rpx 40rpx 16rpx;
  border-bottom: 1rpx solid #E0E0E0;
}

.upload-title {
  font-size: 32rpx;
  font-weight: 600;
  color: #12344D;
}

.upload-subtitle {
  margin-top: 8rpx;
  font-size: 24rpx;
  color: #666666;
}

.folder-content {
  padding: 8rpx 32rpx 24rpx;
  background-color: #ffffff;
}

.folder-tree-list {
  max-height: 480rpx; /* Fixed height for the list */
  overflow-y: auto;
  margin-top: 8rpx;
  border: 1px solid #F1F5F9;
  border-radius: 8rpx;
  padding: 4rpx;
}

.export-folder-label-row {
  margin-top: 16rpx;
}

.export-folder-list {
  max-height: 360rpx;
}

.folder-item {
  display: flex;
  align-items: center;
  padding: 10px 12px;
  margin-bottom: 4px;
  border-radius: 6px;
  cursor: pointer;
  transition: all 0.2s;
  background-color: transparent;
}

.folder-item:hover {
  background-color: #e9ecef;
}

.folder-item.active {
  background-color: #E6F9F0; /* Mint Green Lightest */
  color: #1A5336; /* Forest Green */
  font-weight: 500;
}

.folder-item.root-folder {
  border-bottom: 1px solid #dee2e6;
  margin-bottom: 8px;
  font-weight: bold;
}

.folder-icon {
  margin-right: 8px;
  font-size: 18px;
}

.folder-indent {
  flex-shrink: 0;
}

.folder-name {
  flex: 1;
  font-size: 28rpx;
  color: #334155; /* Slate 700 */
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.folder-toggle {
  width: 24px;
  height: 24px;
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  color: #94A3B8;
  font-size: 10px;
}

.arrow-right {
  display: inline-block;
  transform: rotate(0deg);
  transition: transform 0.2s;
}

.arrow-down {
  display: inline-block;
  transform: rotate(90deg);
  transition: transform 0.2s;
}

.check-icon {
  color: #1A5336; /* Forest Green */
  font-weight: bold;
  font-size: 14px;
  margin-left: 8px;
}

/* Custom Scrollbar for Folder List */
.folder-tree-list::-webkit-scrollbar {
  width: 6px;
  height: 6px;
}
.folder-tree-list::-webkit-scrollbar-thumb {
  background: #CBD5E1;
  border-radius: 3px;
}
.folder-tree-list::-webkit-scrollbar-track {
  background: transparent;
}
.empty-tip {
  text-align: center;
  color: #adb5bd;
  padding: 20px;
  font-size: 14px;
}

.upload-footer {
  padding: 16rpx 40rpx 24rpx;
  border-top: 1rpx solid #F1F5F9; /* Neutral Lightest */
  background-color: #FFFFFF;
  display: flex;
  justify-content: flex-end;
  gap: 16rpx;
  border-bottom-left-radius: 12px;
  border-bottom-right-radius: 12px;
}

.upload-btn {
  min-width: 140rpx;
  padding: 14rpx 32rpx;
  text-align: center;
  border-radius: 6px; /* Rounded corners */
  font-size: 26rpx;
  cursor: pointer;
  transition: all 0.2s;
  font-weight: 500;
}

.upload-btn-secondary {
  background-color: #FFFFFF;
  color: #64748B; /* Slate 500 */
  border: 1rpx solid #CBD5E1;
}

.upload-btn-secondary:hover {
  background-color: #f8f9fa;
  color: #1A5336;
  border-color: #1A5336;
}

.upload-btn-primary {
  background-color: #1A5336; /* Forest Green */
  color: #ffffff;
  border: 1px solid transparent;
}

.upload-btn-primary:hover {
  background-color: #123A26; /* Darker Forest */
}

.upload-btn-primary.upload-btn-disabled {
  background-color: #E2E8F0;
  color: #94A3B8;
  cursor: not-allowed;
}

.dialog-input {
  width: 100%;
  height: 80rpx;
  padding: 0 16rpx;
  border: 1rpx solid #E2E8F0;
  border-radius: 8rpx;
  font-size: 28rpx;
  box-sizing: border-box;
  color: #1E293B;
  transition: border-color 0.2s;
}

.dialog-input:focus {
  border-color: #5BD197; /* Mint Green */
  outline: none;
}

.record-btn {
  margin-left: 8px;
  position: relative;

  .record-dot {
    width: 10px;
    height: 10px;
    border-radius: 50%;
    background: #e11d48; // Red for stop/record
    transition: all 0.3s ease;
  }

  &.recording {
    .record-dot {
      background: #e11d48;
      box-shadow: 0 0 0 2px rgba(225, 29, 72, 0.2);
      animation: breathe 1.5s infinite ease-in-out;
      border: 2px solid transparent; // for outer ring
      width: 12px;
      height: 12px;
      position: relative;

      &::after {
         content: '';
         position: absolute;
         top: -4px;
         left: -4px;
         right: -4px;
         bottom: -4px;
         border-radius: 50%;
         border: 1px solid #e11d48;
         opacity: 0.6;
         animation: ripple 1.5s infinite ease-out;
      }
    }
  }
}

@keyframes breathe {
  0% { transform: scale(1); opacity: 1; }
  50% { transform: scale(0.9); opacity: 0.8; }
  100% { transform: scale(1); opacity: 1; }
}

@keyframes ripple {
  0% { transform: scale(1); opacity: 0.6; }
  100% { transform: scale(1.6); opacity: 0; }
}

/* Sidebar Footer */
.sidebar-footer {
    padding: 12px;
    border-top: 1px solid #e1e4e8;
    background: #f7f9fc;
}
.current-user-info {
    display: flex;
    align-items: center;
    gap: 8px;
    margin-top: 12px;
}
.user-avatar-small {
    width: 24px;
    height: 24px;
    border-radius: 50%;
}
.user-avatar-placeholder-small {
    width: 24px;
    height: 24px;
    border-radius: 50%;
    background: #eef2f5;
    color: #666;
    font-size: 12px;
    display: flex;
    align-items: center;
    justify-content: center;
}
.user-name {
    font-size: 13px;
    color: #333;
    font-weight: 500;
}
.project-members-stack {
    position: relative;
    height: 30px;
    cursor: pointer;
}
.members-avatars {
    position: relative;
    height: 100%;
}
.member-avatar-stack-item {
    position: absolute;
    top: 0;
    width: 24px;
    height: 24px;
    border-radius: 50%;
    border: 2px solid #fff;
    transition: transform 0.2s;
    background: #fff;
}
.member-avatar-mini, .member-avatar-placeholder-mini {
    width: 100%;
    height: 100%;
    border-radius: 50%;
}
.member-avatar-placeholder-mini {
    background: #ccc;
    color: #fff;
    font-size: 10px;
    display: flex;
    align-items: center;
    justify-content: center;
}
.members-expand-panel {
    display: none;
    position: absolute;
    left: 100%;
    bottom: 0;
    width: 200px;
    background: #fff;
    box-shadow: 0 4px 12px rgba(0,0,0,0.15);
    border-radius: 8px;
    padding: 12px;
    z-index: 1000;
    margin-left: 12px;
}
.project-members-stack:hover .members-expand-panel {
    display: block;
}
.upload-label {
  font-size: 26rpx;
  color: #12344D; /* King Dark Blue/Gray */
  font-weight: 600;
  margin-bottom: 12rpx;
  display: block;
}
.expand-header {
    font-size: 12px;
    color: #999;
    margin-bottom: 8px;
}
.expand-list {
    max-height: 200px;
}
.member-row {
    display: flex;
    align-items: center;
    gap: 8px;
    margin-bottom: 8px;
}
.member-avatar-row, .member-avatar-placeholder-row {
    width: 24px;
    height: 24px;
    border-radius: 50%;
}
.member-avatar-placeholder-row {
    background: #eef2f5;
    color: #666;
    font-size: 12px;
    display: flex;
    align-items: center;
    justify-content: center;
}
.member-info {
    display: flex;
    flex-direction: column;
}
.member-name {
    font-size: 13px;
    color: #333;
}
.member-role {
    font-size: 11px;
    color: #999;
}
/* Sidebar Member Stack & User Avatar (Left Rail) */
.rail-members-container {
  width: 40px;
  height: 40px;
  position: relative;
  display: flex;
  align-items: center;
  justify-content: center;
  margin-bottom: 8px; /* Consistent margin */
  border-radius: 8px; /* Match rail-btn */
  transition: all 0.2s;
  cursor: pointer;

  &:hover {
    background-color: #f1f5f9; /* Match rail-btn hover */
  }
}

.members-stack-icon {
  width: 24px;
  height: 24px;
  position: relative;
  cursor: pointer;
}

/* Stacked avatars */
.stack-preview {
  position: relative;
  width: 100%;
  height: 100%;
}

.stack-avatar-mini {
  position: absolute;
  width: 24px;
  height: 24px;
  border-radius: 50%;
  background: #fff;
  border: 1px solid #fff;
  overflow: hidden;
  box-shadow: 0 2px 4px rgba(0,0,0,0.1);
  display: flex;
  align-items: center;
  justify-content: center;
}

/* Member Floating Panel */
.members-expand-panel-left {
  display: none;
  position: absolute;
  left: 100%; /* Position right next to the icon */
  bottom: 0px;
  width: 280px;
  background: #fff;
  box-shadow: 0 4px 16px rgba(0,0,0,0.12);
  border-radius: 12px;
  padding: 16px;
  z-index: 1000;
  cursor: default;
  border: 1px solid #f1f5f9;
  margin-left: 10px; /* Visual gap */
}

/* Invisible bridge to prevent hover loss */
.members-expand-panel-left::before {
  content: '';
  position: absolute;
  top: 0;
  bottom: 0;
  left: -20px; /* Cover the gap and overlap with icon */
  width: 20px;
  background: transparent;
}

.members-stack-icon:hover .members-expand-panel-left,
.members-expand-panel-left:hover {
  display: block;
}

.member-group {
  margin-bottom: 16px;
}

.group-label {
  font-size: 11px;
  color: #94a3b8;
  margin-bottom: 8px;
  font-weight: 600;
  text-transform: uppercase;
  letter-spacing: 0.5px;
}

.members-grid {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
}

.member-grid-item {
  width: 32px;
  height: 32px;
  position: relative;
}

.member-avatar-wrapper {
  width: 100%;
  height: 100%;
  position: relative;
}

.member-avatar-grid {
  width: 100%;
  height: 100%;
  border-radius: 50%;
  object-fit: cover;
  border: 1px solid #e2e8f0;
}

.member-avatar-placeholder-grid {
  width: 100%;
  height: 100%;
  border-radius: 50%;
  background: #f1f5f9; /* Slate 100 */
  color: #64748b; /* Slate 500 */
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 13px;
  font-weight: 500;
  border: 1px solid #e2e8f0;
}

.member-avatar-placeholder-grid.is-client {
  background: #F0FDF4; /* Mint 50 */
  color: #1A5336; /* Forest */
  border-color: #DCFCE7;
}

/* Add Member Round Button */
.add-member-row {
  display: flex;
  justify-content: flex-end; /* Align right or flow with grid if in grid */
  margin-top: 8px;
}

/* If we want it in the grid flow, we place it in the grid.
   But user asked for "most right" (超出最大列宽后可以换行).
   If we put it as a separate block after lists, it's easier.
   Or we can append it to the last group's grid.
   Currently implemented as a separate row at bottom right or flow.
*/
.add-member-btn {
  width: 32px;
  height: 32px;
  border-radius: 50%;
  border: 1px dashed #cbd5e1;
  display: flex;
  align-items: center;
  justify-content: center;
  color: #64748b;
  cursor: pointer;
  transition: all 0.2s;
  background: #fff;
}

.add-member-btn:hover {
  border-color: #1A5336;
  color: #1A5336;
  background: #F0FDF4;
}

.add-icon {
  font-size: 16px;
  line-height: 1;
  font-weight: 300;
}

/* Stacked avatars */
.stack-preview {
  position: relative;
  width: 100%;
  height: 100%;
}

.stack-avatar-mini {
  position: absolute;
  width: 24px;
  height: 24px;
  border-radius: 50%;
  background: #fff;
  border: 1px solid #fff;
  overflow: hidden;
  box-shadow: 0 2px 4px rgba(0,0,0,0.1);
  display: flex;
  align-items: center;
  justify-content: center;
}

.avatar-img {
  width: 100%;
  height: 100%;
}

.avatar-placeholder {
  width: 100%;
  height: 100%;
  background: #cbd5e1;
  color: #fff;
  font-size: 10px;
  display: flex;
  align-items: center;
  justify-content: center;
}

/* AI Workdeck Dialog System (Copied for project-overview.vue usage) */
.king-dialog-mask {
  position: fixed;
  top: 0;
  left: 0;
  right: 0;
  bottom: 0;
  background-color: rgba(0, 0, 0, 0.4);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 9999;
  backdrop-filter: blur(2px);
}

.king-dialog {
  width: 618px; /* Golden Ratio approx */
  background: #ffffff;
  border-radius: 12px;
  box-shadow: 0 20px 25px -5px rgba(0, 0, 0, 0.1), 0 10px 10px -5px rgba(0, 0, 0, 0.04);
  display: flex;
  flex-direction: column;
  overflow: hidden;
  box-sizing: border-box;
}

.king-dialog * {
  box-sizing: border-box;
}

.king-dialog-large {
  width: 750px;
}

.king-dialog-header {
  padding: 24px 32px 16px;
  border-bottom: 1px solid #f1f5f9;
}

.king-dialog-title {
  font-size: 20px;
  font-weight: 600;
  color: #0f172a;
  display: block;
}

.king-dialog-subtitle {
  font-size: 14px;
  color: #64748b;
  margin-top: 6px;
  display: block;
}

.king-dialog-body {
  padding: 24px 32px;
  color: #334155;
  font-size: 15px;
  line-height: 1.6;
}

.king-dialog-text {
  font-size: 15px;
  color: #334155;
  line-height: 1.6;
}

.king-dialog-footer {
  padding: 20px 32px 24px;
  background: #f8f9fa;
  display: flex;
  justify-content: center; /* Centered buttons */
  gap: 16px;
  border-top: 1px solid #f1f5f9;
}

.king-btn {
  height: 44px;
  padding: 0 32px;
  border-radius: 8px;
  font-size: 15px;
  font-weight: 500;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  transition: all 0.2s;
}

.king-btn-primary {
  background: #1A5336; /* Forest Green */
  color: #ffffff;
  border: 1px solid transparent;
}

.king-btn-primary:hover {
  background: #14422b;
  box-shadow: 0 4px 6px -1px rgba(26, 83, 54, 0.2);
}

.king-btn-secondary {
  background: #ffffff;
  color: #475569;
  border: 1px solid #cbd5e1;
}

.king-btn-secondary:hover {
  background: #f1f5f9;
  border-color: #94a3b8;
  color: #1e293b;
}

.king-btn-danger {
  background: #dc2626; /* Red */
  color: #ffffff;
  border: 1px solid transparent;
}

.king-btn-danger:hover {
  background: #b91c1c;
}

.king-input {
  width: 100%;
  height: 48px;
  padding: 0 16px;
  border: 1px solid #cbd5e1;
  border-radius: 8px;
  font-size: 15px;
  color: #0f172a;
  transition: all 0.2s;
}

.king-input:focus {
  border-color: #1A5336;
  outline: none;
  box-shadow: 0 0 0 3px rgba(26, 83, 54, 0.1);
}

.king-field {
  display: flex;
  align-items: center;
  padding: 12px 16px;
  border: 1px solid #cbd5e1;
  border-radius: 8px;
  background: #fff;
  min-height: 48px;
}

.form-group {
  margin-bottom: 20px;
}

.form-label {
  display: block;
  font-size: 14px;
  font-weight: 500;
  color: #0f172a;
  margin-bottom: 8px;
}

/* Hover Expand Panel */
.members-expand-panel-left {
  position: absolute;
  left: 100%; /* To the right of the rail */
  bottom: 0;
  width: 180px; /* Slightly wider for grid */
  background: #fff;
  border: 1px solid #e2e8f0;
  border-radius: 8px;
  box-shadow: 0 4px 12px rgba(0,0,0,0.1);
  padding: 12px;
  z-index: 1000;
  opacity: 0;
  visibility: hidden;
  transform: translateX(10px);
  transition: all 0.2s ease;
  margin-left: 12px;
}

/* Show on hover of parent container */
.rail-members-container:hover .members-expand-panel-left {
  opacity: 1;
  visibility: visible;
  transform: translateX(0);
}

.expand-header {
  font-size: 12px;
  font-weight: 600;
  color: #64748b;
  margin-bottom: 8px;
  padding-bottom: 4px;
  border-bottom: 1px solid #f1f5f9;
}

.expand-list {
  max-height: 200px;
}

.members-grid {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.member-grid-item {
  position: relative;
  cursor: pointer;
  transition: transform 0.1s;
}

.member-grid-item:hover {
  transform: scale(1.1);
}

.member-avatar-grid {
  width: 32px;
  height: 32px;
  border-radius: 50%;
  border: 1px solid #e2e8f0;
}

.member-avatar-placeholder-grid {
  width: 32px;
  height: 32px;
  border-radius: 50%;
  background: #cbd5e1;
  color: #fff;
  font-size: 12px;
  display: flex;
  align-items: center;
  justify-content: center;
  border: 1px solid #e2e8f0;
}


/* Rail User Avatar (Bottom) */
/* Rail User Avatar (Bottom) */
.rail-user-avatar {
  width: 40px;
  height: 40px;
  border-radius: 8px; /* Match rail-btn container */
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  margin-bottom: 8px; /* Consistent margin */
  transition: all 0.2s;

  &:hover {
    background-color: #f1f5f9; /* Match rail-btn hover */
  }
}

.rail-user-avatar-inner {
  width: 32px;
  height: 32px;
  border-radius: 50%;
  overflow: hidden;
  display: flex;
  align-items: center;
  justify-content: center;
  background: #0f172a;
  color: white;
  font-size: 14px;
}

.rail-user-avatar:hover {
  transform: scale(1.05);
}
/* --- Refined Resource Manager Header Styles --- */
.panel-header-tools {
  position: relative; /* For absolute search centering */
  justify-content: space-between; /* Override */
}

.header-content-left {
  display: flex;
  align-items: center;
  gap: 16px;
  flex: 1; /* Allow taking space left of search */
}

/* King Style Tabs */
.panel-tabs.king-style {
  background: transparent;
  padding: 0;
  gap: 24px;
}

.panel-tabs.king-style .panel-tab {
  background: transparent;
  padding: 0 4px;
  height: 100%;
  display: flex;
  align-items: center;
  position: relative;
  font-weight: 500;
  color: #6C757D;
  transition: all 0.2s;
}

.panel-tabs.king-style .panel-tab.active {
  background: transparent;
  color: #2C3338;
  font-weight: 600;
}

.tab-indicator {
  position: absolute;
  bottom: 0px;
  left: 0;
  width: 100%;
  height: 2px;
  background: #5BD197; /* Mint Green */
  border-radius: 2px 2px 0 0;
}

/* Tool Actions Group (Variable Buttons) */
.tool-actions-group {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-left: 8px;
  padding-left: 16px;
  border-left: 1px solid #E9ECEF;
  height: 24px; /* Divider height */
}

.tool-action-btn {
  display: flex;
  align-items: center;
  gap: 4px;
  padding: 4px 8px;
  border-radius: 4px;
  cursor: pointer;
  transition: all 0.2s;
  color: #6C757D;

  &:hover {
    background: #E6F9F0;
    color: #1A5336;
  }
}

.btn-icon {
  font-size: 14px;
}

.btn-text {
  font-size: 12px;
}

/* Centered Search */
.tools-search-centered {
  position: absolute;
  left: 50%;
  top: 50%;
  transform: translate(-50%, -50%);
  width: 320px;
  display: flex;
  justify-content: center;
}

.tools-search-centered .tools-search-wrap {
  width: 100%;
  background: #F8F9FA;
  border: 1px solid #E9ECEF;
}

.tools-search-centered .tools-search-input {
  text-align: left;
}
.icon-btn.active {
    background-color: $color-accent-pale;
    color: $color-primary;
}

/* Custom Recording Toast */
.recording-toast {
  position: fixed;
  top: 50%;
  left: 50%;
  transform: translate(-50%, -50%);
  background-color: rgba(44, 51, 56, 0.9); /* AI Workdeck Gray-Dark with opacity */
  color: #fff;
  padding: 12px 24px;
  border-radius: 8px;
  font-size: 16px;
  font-weight: 500;
  pointer-events: none;
  opacity: 0;
  transition: opacity 0.3s ease;
  z-index: 9999;
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.15);
  display: flex;
  align-items: center;
  justify-content: center;

  &.visible {
    opacity: 1;
  }
}
/* AI Context Drag & Drop */
.side-panel-ai.drag-over {
  background-color: rgba(91, 209, 151, 0.1);

  &::after {
    content: "";
    position: absolute;
    top: 0;
    left: 0;
    right: 0;
    bottom: 0;
    border: 2px dashed #1A5336;
    pointer-events: none;
    z-index: 100;
    box-sizing: border-box;
  }
}

.ai-context-tags-row {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  padding: 12px 12px 0; /* Top/Side padding, 0 bottom (close to text) */
  background: #fff; /* Ensure bg */
}

.context-tag-pill {
  display: flex;
  align-items: center;
  background: #E6F9F0; /* Pale Mint */
  padding: 2px 8px;
  border-radius: 4px;
  font-size: 11px;
  color: #1A5336;
  cursor: default;
}

.tag-name {
  max-width: 150px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.tag-close {
  margin-left: 4px;
  font-size: 14px;
  cursor: pointer;
  color: #1A5336;
  opacity: 0.6;
}

.tag-close:hover {
  opacity: 1;
}

/* Epic #43: embedded LibreOffice editor (experimental, ⌘⇧O).
   Absolute within .editors-container (position:relative) so it covers the
   document area only — the AI chat panel stays usable. */
.libre-embed-overlay {
  position: absolute;
  top: 0;
  left: 0;
  right: 0;
  bottom: 0;
  z-index: 50;
  background: #fff;
}
</style>
