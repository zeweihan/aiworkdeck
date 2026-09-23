<!-- SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors -->
<!-- SPDX-License-Identifier: AGPL-3.0-or-later -->
<template>
  <view class="chat-interface" :class="{ 'is-empty': bubbles.length === 0 && !isStreaming }">

    <MemoryBrowser :open="showMemoryBrowser" :project-id="projectId" @close="showMemoryBrowser = false" />

    <!-- Upload File Modal (reused from FileTree pattern) -->
    <view v-if="showUploadDialog" class="awd-dialog-mask" @tap="cancelUpload">
      <view class="awd-dialog awd-dialog-large" @tap.stop>
        <view class="awd-dialog-header">
          <view class="header-row">
            <text class="awd-dialog-title">{{ $t('chat.uploadFileTitle') }}</text>
            <text class="awd-dialog-subtitle">{{ $t('chat.uploadFileSubtitle') }}</text>
          </view>
          <!-- 两个来源：传一份新的 / 从项目里挑一份已有的（dev-board#794 K15 ③） -->
          <view class="pick-tabs" role="tablist">
            <view class="pick-tab" :class="{ active: uploadTab === 'local' }" role="tab" tabindex="0"
                  :aria-selected="uploadTab === 'local' ? 'true' : 'false'"
                  @tap="uploadTab = 'local'"
                  @keydown.enter="onOptionKey($event, () => (uploadTab = 'local'))"
                  @keydown.space="onOptionKey($event, () => (uploadTab = 'local'))">
              {{ $t('chat.uploadTabLocal') }}
            </view>
            <view class="pick-tab" :class="{ active: uploadTab === 'project' }" role="tab" tabindex="0"
                  :aria-selected="uploadTab === 'project' ? 'true' : 'false'"
                  @tap="uploadTab = 'project'"
                  @keydown.enter="onOptionKey($event, () => (uploadTab = 'project'))"
                  @keydown.space="onOptionKey($event, () => (uploadTab = 'project'))">
              {{ $t('chat.uploadTabProject') }}
            </view>
          </view>
        </view>
        <view v-if="uploadTab === 'project'" class="awd-dialog-body">
          <input class="pick-search" type="text" :maxlength="-1"
                 :placeholder="$t('chat.projectPickPlaceholder')"
                 :value="projectPickQuery" @input="onProjectPickInput" />
          <scroll-view v-if="projectPickMatches.length" class="pick-list" scroll-y>
            <view v-for="item in projectPickMatches" :key="item.id" class="pick-row"
                  :class="{ picked: isPicked(item) }" role="option"
                  :aria-selected="isPicked(item) ? 'true' : 'false'" tabindex="0"
                  @tap="pickProjectFile(item)"
                  @keydown.enter="onOptionKey($event, () => pickProjectFile(item))"
                  @keydown.space="onOptionKey($event, () => pickProjectFile(item))">
              <image class="pick-icon" :src="item.isDir ? '/static/folder-closed.png' : '/static/document.png'" mode="aspectFit" />
              <text class="pick-name">{{ item.name }}</text>
              <text v-if="item.dirLabel" class="pick-path">{{ item.dirLabel }}</text>
              <text v-if="isPicked(item)" class="pick-done">{{ $t('chat.projectPickAdded') }}</text>
            </view>
          </scroll-view>
          <view v-else class="pick-empty">
            <text>{{ projectPickQuery ? $t('files.noMatchingFiles') : $t('chat.projectPickEmpty') }}</text>
          </view>
        </view>
        <view v-else class="awd-dialog-body">
          <view class="form-group">
            <text class="form-label">{{ $t('chat.uploadLocation') }}</text>
            <view class="awd-field clickable" @tap="openFolderSelector">
              <image src="/static/folder-closed.png" class="field-icon-img" mode="aspectFit" />
              <text class="field-value">
                {{ selectedUploadParent ? getFolderPath(selectedUploadParent) : $t('chat.rootFolder') }}
              </text>
            </view>
          </view>

          <!-- H5 Folder Upload -->
          <!-- #ifdef H5 -->
          <view class="form-group">
            <text class="form-label">{{ $t('chat.uploadFolder') }}</text>
            <view class="awd-field clickable" @tap="triggerFolderUploadInput">
               <view v-if="isFolderUpload && uploadSelectedFiles.length > 0" class="field-content-row">
                  <text class="field-value">{{ $t('chat.filesSelected', { count: uploadSelectedFiles.length }) }}</text>
               </view>
               <view v-else>
                  <text class="field-placeholder">{{ $t('chat.clickSelectFolder') }}</text>
               </view>
            </view>
          </view>
          <!-- #endif -->

          <view class="form-group">
            <text class="form-label">{{ $t('chat.uploadFileTitle') }}</text>
            <view class="awd-field clickable" @tap="selectFilesForUpload">
              <view v-if="uploadSelectedFiles.length === 0 || isFolderUpload">
                <text class="field-placeholder">{{ $t('chat.selectFilesMulti') }}</text>
              </view>
              <view v-else class="selected-files-list">
                <text v-for="(file, index) in uploadSelectedFiles" :key="index" class="selected-file-tag">
                  {{ file.name }}
                </text>
              </view>
            </view>
          </view>
        </view>
        <view class="awd-dialog-footer">
          <view v-if="uploadTab === 'project'" class="awd-btn awd-btn-primary" @tap="cancelUpload">{{ $t('chat.projectPickDone') }}</view>
          <template v-else>
            <view class="awd-btn awd-btn-secondary" @tap="cancelUpload">{{ $t('chat.cancel') }}</view>
            <view
              class="awd-btn awd-btn-primary"
              :class="{ disabled: !uploadSelectedFiles.length }"
              @tap="uploadSelectedFiles.length ? confirmUploadAndAddContext() : null"
            >
              {{ $t('chat.confirmUpload') }}
            </view>
          </template>
        </view>
      </view>
    </view>

    <!-- Folder Selector Popup (Nested) - Matching FileTree design -->
    <view v-if="showFolderSelector" class="awd-dialog-mask" style="z-index: 3000;" @tap="showFolderSelector = false">
      <view class="awd-dialog" @tap.stop>
        <view class="awd-dialog-header">
          <view class="header-row folder-selector-header">
            <text class="awd-dialog-title">{{ $t('chat.selectFolderTitle') }}</text>
            <view class="new-folder-btn" @tap="handleSelectorCreateFolder">
              <text class="btn-plus">+</text>
              <text>{{ $t('chat.newFolder') }}</text>
            </view>
          </view>
        </view>
        <view class="awd-dialog-body scrollable-body">
          <view
            class="folder-tree-item root"
            :class="{ active: tempSelectedParent === null }"
            @tap="selectUploadParent(null)"
          >
            <view class="tree-expand-icon-wrapper" @tap.stop="toggleFolderSelectorExpand('root')">
              <image
                class="tree-expand-icon-img"
                :src="folderSelectorExpanded['root'] !== false ? '/static/down.png' : '/static/right.png'"
                mode="aspectFit"
              />
            </view>
            <image
              :src="folderSelectorExpanded['root'] !== false ? '/static/folder-opened.png' : '/static/folder-closed.png'"
              class="folder-icon-img"
              :class="{ 'is-opened': folderSelectorExpanded['root'] !== false }"
              style="margin-right: 8px;"
              mode="aspectFit"
            />
            <text class="folder-name">{{ $t('chat.rootFolder') }}</text>
          </view>

          <view
            v-for="folder in folderTree"
            :key="folder.id"
            class="folder-tree-item"
            :class="{ active: tempSelectedParent === folder.id }"
            @tap="selectUploadParent(folder.id)"
          >
            <view class="indent" :style="{ width: (folder.level * 20) + 'px' }"></view>
            <view class="tree-expand-icon-wrapper" @tap.stop="toggleFolderSelectorExpand(folder.id)">
              <image
                class="tree-expand-icon-img"
                :src="folderSelectorExpanded[String(folder.id)] === true ? '/static/down.png' : '/static/right.png'"
                mode="aspectFit"
              />
            </view>
            <image
              :src="folderSelectorExpanded[String(folder.id)] === true ? '/static/folder-opened.png' : '/static/folder-closed.png'"
              class="folder-icon-img"
              :class="{ 'is-opened': folderSelectorExpanded[String(folder.id)] === true }"
              mode="aspectFit"
            />
            <text class="folder-name">{{ folder.name }}</text>
          </view>
          <view v-if="folderTree.length === 0" class="empty-tip">{{ $t('chat.noOtherFolders') }}</view>
        </view>
        <view class="awd-dialog-footer">
          <view class="awd-btn awd-btn-secondary" @tap="showFolderSelector = false">{{ $t('chat.cancel') }}</view>
          <view class="awd-btn awd-btn-primary" @tap="confirmFolderSelection">{{ $t('chat.confirm') }}</view>
        </view>
      </view>
    </view>

    <!-- Rollback Confirmation Dialog -->
    <view v-if="showRollbackDialog" class="awd-dialog-mask" style="z-index: 3100;" @tap="cancelRollback">
      <view class="awd-dialog" @tap.stop>
        <view class="awd-dialog-header warning-header">
          <text class="awd-dialog-title warning-title">{{ rollbackResend ? $t('chat.regenerateConfirmTitle') : $t('chat.rollbackConfirmTitle') }}</text>
        </view>
        <view class="awd-dialog-body">
          <view class="rollback-warning-content">
            <text class="warning-text">{{ rollbackResend ? $t('chat.regenerateWarning') : $t('chat.rollbackWarning') }}</text>
            <!-- 存档说明对两种用法都成立：重新生成走的就是这条截断通道（dev-board#790） -->
            <text class="warning-text rollback-archive-note">{{ $t('chat.rollbackArchiveNote') }}</text>
            <view class="doc-tip-box">
              <svg class="doc-tip-icon" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
                <path d="M12 3a6 6 0 0 0-3.5 10.9V17h7v-3.1A6 6 0 0 0 12 3Z" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round" />
                <path d="M10 20h4" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" />
              </svg>
              <view class="doc-tip-text">
                <text>{{ $t('chat.rollbackDocTip') }}</text>
                <text class="doc-link-text">{{ $t('chat.rollbackDocTipLink') }}</text>
              </view>
            </view>
            <view class="rollback-preview">
              <text class="preview-label">{{ rollbackResend ? $t('chat.regeneratePreviewLabel') : $t('chat.rollbackPreviewLabel') }}</text>
              <text class="preview-content">"{{ truncateName(rollbackTargetContent, 50) }}"</text>
            </view>
          </view>
        </view>
        <view class="awd-dialog-footer">
          <view class="awd-btn awd-btn-secondary" @tap="cancelRollback">{{ $t('chat.cancel') }}</view>
          <view class="awd-btn awd-btn-danger" data-rollback-confirm @tap="confirmRollback">{{ rollbackResend ? $t('chat.regenerateConfirmTitle') : $t('chat.rollbackConfirmTitle') }}</view>
        </view>
      </view>
    </view>

    <!-- PPT Config Dialog -->
    <view v-if="showPptConfigDialog" class="awd-dialog-mask" style="z-index: 3200;" @tap="cancelPptConfig">
      <view class="awd-dialog" @tap.stop>
        <view class="awd-dialog-header">
           <text class="awd-dialog-title">{{ $t('chat.pptConfigTitle') }}</text>
        </view>
        <view class="awd-dialog-body">
           <view class="ppt-config-section">
              <text class="section-title">{{ $t('chat.pptSelectFormat') }}</text>

              <!-- Option 1: Editable (Beta) -->
              <view class="ppt-option-card"
                   :class="{ active: pptExportEditable === true }"
                   @tap="pptExportEditable = true">
                 <view class="option-header">
                    <svg class="option-icon" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
                       <path d="M4 20h4L19 9a2.8 2.8 0 0 0-4-4L4 16v4Z" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round" />
                       <path d="M14.5 5.5 18.5 9.5" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" />
                    </svg>
                    <text class="option-name">{{ $t('chat.pptEditableName') }}</text>
                    <text v-if="pptExportEditable === true" class="check-mark">✔</text>
                 </view>
                 <view class="option-desc">
                    {{ $t('chat.pptEditableDesc') }}
                    <text class="warning-text">{{ $t('chat.pptEditableWarn') }}</text>
                 </view>
              </view>

              <!-- Option 2: Image (Stable) -->
              <view class="ppt-option-card"
                   :class="{ active: pptExportEditable === false }"
                   @tap="pptExportEditable = false">
                 <view class="option-header">
                    <svg class="option-icon" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
                       <path d="M4 5h16v14H4z" stroke="currentColor" stroke-width="1.6" stroke-linejoin="round" />
                       <path d="m4 16 4.5-4.5 3 3L15 11l5 5" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round" />
                       <path d="M9 9.5h.01" stroke="currentColor" stroke-width="2" stroke-linecap="round" />
                    </svg>
                    <text class="option-name">{{ $t('chat.pptImageName') }}</text>
                    <text v-if="pptExportEditable === false" class="check-mark">✔</text>
                 </view>
                 <view class="option-desc">
                    {{ $t('chat.pptImageDesc') }}
                    <text class="highlight-text">{{ $t('chat.pptImageHighlight') }}</text>
                 </view>
              </view>
           </view>
        </view>
        <view class="awd-dialog-footer">
           <view class="awd-btn awd-btn-secondary" @tap="cancelPptConfig">{{ $t('chat.cancel') }}</view>
           <view class="awd-btn awd-btn-primary" @tap="confirmPptGeneration">{{ $t('chat.pptStart') }}</view>
        </view>
      </view>
    </view>

    <!-- 1. Header Actions -->
    <view class="chat-header">
       <view class="header-left">
          <text class="project-name-display" :title="projectName">{{ projectName }}</text>
       </view>
       <view class="header-actions">
          <view class="memory-header-btn" @tap="showMemoryBrowser = true">{{ $t('chat.memoryButton') }}</view>
          <view class="icon-btn" @tap="$emit('toggle-history')" title="History">
             <image class="btn-icon default" src="/static/history.png" />
             <image class="btn-icon hover" src="/static/history_hover.png" />
             <view v-if="historyBadge" class="conv-dot header-dot" :class="historyBadge"></view>
          </view>
          <view class="icon-btn" @tap="startNewChat" title="New Chat">
             <image class="btn-icon default" src="/static/plus.png" />
             <image class="btn-icon hover" src="/static/plus_hover.png" />
          </view>
          <view class="icon-btn" @tap="$emit('close')" title="Close">
             <image class="btn-icon default" src="/static/close.png" />
             <image class="btn-icon hover" src="/static/close_hover.png" />
          </view>
       </view>
    </view>

    <!-- Thinking, tools and replies stay in chronological order in the transcript.
         .message-area 是为钢琴键导航列加的定位层（dev-board#791）：滚动容器自己
         overflow 两层全裁，浮层只能挂在它外面。 -->
    <div v-if="bubbles.length > 0 || isStreaming" class="message-area">
      <div
        class="message-list"
        ref="messageList"
        @scroll="handleHistoryScroll"
      >
      <view ref="messageContent" class="message-list-content">
        <view
          v-for="turn in chatTurns"
          :key="turn.key"
          v-memo="[turn, isStreaming, bubbles.length,
                   turn.user && turn.user.bubble.contentHtml,
                   turn.user && turn.user.bubble.content,
                   turn.user && turn.user.bubble.displayContent,
                   turn.user && turn.user.bubble.timestamp,
                   turn.user && turn.user.bubble.receiptState,
                   turn.user && turn.user.bubble.submissionMode,
                   turn.user && turn.user.bubble.wasPendingInbox,
                   turn.user && turn.user.bubble.dbMessageId,
                   turn.user && turn.user.bubble.clientRequestId,
                   turn.user && turn.user.bubble.images,
                   turn.user && turn.user.bubble.images && turn.user.bubble.images.length,
                   turn.user && turn.user.bubble.contextFiles,
                   turn.user && turn.user.bubble.contextFiles && turn.user.bubble.contextFiles.length,
                   turn.user && turn.user.bubble.contextNotices,
                   turn.user && turn.user.bubble.contextNotices && turn.user.bubble.contextNotices.length]"
          :data-turn-key="turn.key"
          class="conversation-turn"
        >
        <view
          v-for="{ bubble: msg, index } in (turn.user ? [turn.user, ...turn.assistants] : turn.assistants)"
          :key="msg.id || index"
          :data-message-index="index"
          class="message-row"
          :class="msg.role.toLowerCase()"
        >
          <!-- User Message -->
          <div v-if="msg.role === 'USER'" class="user-bubble" :class="{ 'is-unread': msg.receiptState === 'pending' }">
            <!-- Image Thumbnails (above message) -->
            <view v-if="msg.images && msg.images.length > 0" class="user-bubble-images">
               <image v-for="(img, idx) in msg.images" :key="idx" :src="img.path" mode="aspectFill" class="bubble-image-thumb" />
            </view>
            <!-- Content with inline file tags preserved at their original positions -->
            <!-- displayContent（契约 D）：点按钮产生的消息里，模型要的细节在 content，
                 用户气泡只显示那句人话；为空则回退 content，与今天行为完全一致。
                 contentHtml 仍优先——它只在用户手打输入那条路上存在（带内联文件标签）。 -->
            <div
              class="user-bubble-content"
              v-html="msg.contentHtml || escapeHtml(msg.displayContent || msg.content)"
            ></div>
            <!-- 历史里那一轮带过的附件（dev-board#793 K14 ④）。
                 只在没有 contentHtml 时渲染：手打输入那条路的附件是**内联标签**，
                 已经在正文里了，再挂一排就是显示两遍。历史回灌拿到的是纯 content，
                 附件清单来自 GET /api/ai/history 的 attachments。 -->
            <view v-if="!msg.contentHtml && msg.contextFiles && msg.contextFiles.length" class="bubble-attachments">
              <text v-for="(f, fi) in msg.contextFiles" :key="fi" class="bubble-attachment">{{ '@' + (f.name || f.id) }}</text>
            </view>
            <!-- 本轮附件的降级/截断/丢弃（dev-board#801 K21 ⑦）。
                 一行小字，不弹 toast——它说的是既成事实，不需要用户点确认；
                 历史回灌不重放（刷新后再弹一次只是噪音）。 -->
            <view v-if="msg.contextNotices && msg.contextNotices.length" class="context-notices">
              <text v-for="(n, ni) in msg.contextNotices" :key="ni" class="context-notice">{{ contextNoticeText(n) }}</text>
            </view>
            <div class="bubble-footer">
              <!-- 从此分叉（dev-board#779 K18，审查 D-06/F4）：非破坏。原对话一个字不动，
                   只把「到这条为止」复制成一条新对话并切过去。可用性判据与回退同源
                   （rollbackLocator），因为两者用的是同一套定位键。排在回退左边：
                   不销毁任何东西的那个动作应该先被读到。 -->
              <view v-if="!isStreaming" class="branch-btn"
                    :class="{ 'is-disabled': !rollbackLocator(msg) }"
                    @tap.stop="branchFromMessage(msg)"
                    :title="rollbackLocator(msg) ? $t('chat.branchBtnTitle') : $t('chat.branchUnavailable')">
                 <!-- 内层沿用 rollback-icon-svg / rollback-text 两个类：两个键在 footer 里
                      是同一种视觉物件，另起一套一模一样的 CSS 只会多一处要同步的地方 -->
                 <div class="rollback-icon-svg">
                    <svg xmlns="http://www.w3.org/2000/svg" width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round">
                        <line x1="6" y1="3" x2="6" y2="15"></line>
                        <circle cx="18" cy="6" r="3"></circle>
                        <circle cx="6" cy="18" r="3"></circle>
                        <path d="M18 9a9 9 0 0 1-9 9"></path>
                    </svg>
                 </div>
                 <text class="rollback-text">{{ $t('chat.branchBtn') }}</text>
              </view>
              <!-- Rollback Button -->
              <!-- 拿不到定位键（消息还没落库、也没有客户端幂等键）时置灰：点下去注定失败，
                   而那恰恰是最想用它的时刻——刚发现自己问错了（审查 D-02） -->
              <view v-if="!isStreaming" class="rollback-btn"
                    :class="{ 'is-disabled': !rollbackLocator(msg) }"
                    @tap.stop="openRollbackDialog(msg, index)"
                    :title="rollbackLocator(msg) ? $t('chat.rollbackBtnTitle') : $t('chat.rollbackUnavailable')">
                 <div class="rollback-icon-svg">
                    <svg xmlns="http://www.w3.org/2000/svg" width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round">
                        <path d="M9 14 4 9l5-5"></path>
                        <path d="M4 9h12a5 5 0 0 1 5 5v3"></path>
                    </svg>
                 </div>
                 <text class="rollback-text">{{ $t('chat.rollbackBtn') }}</text>
              </view>
              <!-- 送达状态（dev-board#779 K7②）：判据只从 receiptState / submissionMode 取，
                   不另起一份状态。AI 正在跑工具时插话，消息立刻以普通气泡出现，和一条
                   模型已经读过的消息长得一模一样——真正被读取要等到下一个工具边界。 -->
              <span v-if="receiptLabel(msg)" class="bubble-receipt" :class="{ 'is-pending': msg.receiptState === 'pending' }">{{ receiptLabel(msg) }}</span>
              <span v-if="msg.timestamp" class="bubble-timestamp user">{{ msg.timestamp }}</span>
            </div>
          </div>

          <!-- Assistant Message (Root Bubble) -->
          <div v-else-if="msg.role === 'ASSISTANT'" class="assistant-root-wrapper">
             <RootBubble
               :bubble="msg"
               :is-latest="index === bubbles.length - 1"
               @open-artifact-tab="handleArtifactOpenTab"
               @approve="handleArtifactApprove"
               @answer-question="handleQuestionAnswer"
               @message-action="$emit('message-action', $event)"
               @regenerate="openRegenerateDialog(index)"
             />
             <!-- <span v-if="msg.timestamp" class="bubble-timestamp assistant">{{ msg.timestamp }}</span> -->
          </div>
        </view>
      </view>
      </view>
      </div>
      <ChatTurnRail :turns="chatTurns" :active-key="activeTurnKey" @jump="handleTurnJump" />
    </div>
    <view v-if="bubbles.length && (attentionNotice || !followLatest)" class="return-to-latest">
      <view class="locator-row">
        <button v-if="attentionNotice" class="attention-locator" @click="jumpToAttention">{{ $t(attentionNotice.key, { n: attentionNotice.n }) }}</button>
        <button v-if="!followLatest" class="back-to-latest" @click="scrollToBottom">{{ $t('chat.activityBackToLatest') }} ↓</button>
      </view>
    </view>

    <!-- 3. Integrated Empty & Input Layout -->
    <view v-if="bubbles.length === 0 && !isStreaming" class="empty-flow-container">
       <!-- Top: Welcome Text (between header and input) -->
       <view class="empty-top-section">
          <text class="welcome-text">{{ $t('chat.welcome') }}</text>
          <text class="welcome-subtitle">{{ $t('chat.welcomeSubtitle') }}</text>
       </view>

       <!-- Center: Input -->
       <view class="empty-middle-section">
          <view class="input-card centered-style" :class="{ 'is-drop-target': dragActive }">
               <!-- Image Thumbnails Preview (top-left) -->
               <view v-if="pastedImages.length > 0" class="input-images-preview">
                  <view v-for="(img, index) in pastedImages" :key="index" class="preview-image-item">
                     <image v-if="img.path" :src="img.path" mode="aspectFill" class="preview-thumb" />
                     <text class="preview-remove" @tap="removePastedImage(index)">×</text>
                  </view>
               </view>
               <!-- 当前文档 chip（K14 ①）：看得见、摘得掉。摘掉只对这一轮生效 -->
               <view v-if="activeDocChip" class="active-doc-chip" :title="$t('chat.activeDocChipTitle')">
                  <text class="active-doc-label">{{ $t('chat.activeDocChipLabel') }}</text>
                  <text class="active-doc-name">{{ activeDocChip.name }}</text>
                  <text class="active-doc-remove" @tap.stop="dismissActiveDoc">×</text>
               </view>
               <!-- 「模型看不了图」常驻提示（K21 ⑨）：粘的、拖的图片都覆盖；能力未知一律不提示 -->
               <text v-if="visionNotice" class="input-images-note">{{ $t(visionNotice) }}</text>
              <div
                ref="richInput"
                class="chat-input-rich"
                contenteditable="true"
                @input="handleRichInput"
                @paste="handlePaste"
                @keydown="handleInputKeydown"
                @click="handleInputClick"
                :data-placeholder="$t('chat.inputPlaceholderEmpty')"
              ></div>
              <!-- `@` 引用选择器（dev-board#794 K15）：浮在输入卡上沿 -->
              <MentionPicker v-if="mentionOpen" ref="mentionPicker" :files="mentionCandidates"
                             :query="mentionQuery" :loading="mentionLoading" @select="chooseMentionFile" />
              <!-- Note: Context files are now shown as inline tags inside the rich input -->
              <!-- 本轮生效的 Skill：手动选的带 × 可移除，自动命中的新出现时闪一下 -->
              <view v-if="skillChips.length" class="skill-chip-row">
                 <view v-for="chip in skillChips" :key="chip.id"
                       class="skill-chip"
                       :class="{ auto: chip.source === 'auto', flash: chip.justActivated }">
                    <text class="skill-chip-name">{{ chip.name }}</text>
                    <text v-if="chip.source === 'manual'" class="skill-chip-remove"
                          @tap.stop="removeSelectedSkill(chip.id)">×</text>
                 </view>
              </view>
              <view class="input-footer">
                 <view class="action-bar-left">
                    <view class="icon-btn mini file-add-btn" @tap="triggerFileSelect" title="Add File">
                   <svg class="plus-svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><line x1="12" y1="5" x2="12" y2="19"/><line x1="5" y1="12" x2="19" y2="12"/></svg>
                 </view>
                    <!-- Agent Mode Selector -->
                    <view class="mode-selector" @tap="toggleModeDropdown">
                       <text class="mode-icon" v-if="currentModeIcon">{{ currentModeIcon }}</text>
                       <text class="mode-name">{{ currentModeName }}</text>
                       <text class="dropdown-arrow">▼</text>
                       <view v-if="showModeDropdown" class="mode-dropdown down" role="listbox">
                          <view v-for="mode in availableModes" :key="mode.id"
                                class="mode-option"
                                :class="{ active: currentModeId === mode.id }"
                                role="option" tabindex="0" :aria-selected="currentModeId === mode.id ? 'true' : 'false'"
                                @keydown.enter.stop="onOptionKey($event, () => selectMode(mode))"
                                @keydown.space.stop="onOptionKey($event, () => selectMode(mode))"
                                @tap.stop="selectMode(mode)">
                            <text class="mode-option-icon" v-if="mode.icon">{{ mode.icon }}</text>
                             <view class="mode-option-text">
                                <text class="mode-option-name">{{ mode.name }}</text>
                                <text class="mode-option-desc">{{ mode.desc }}</text>
                             </view>
                          </view>
                          <view v-if="localModeNotice" class="mode-note">{{ localModeNotice }}</view>
                       </view>
                    </view>
                    <!-- Model Selector：清单来自 GET /api/ai/models，按厂商分组、国际档在后 -->
                    <view class="model-selector" @tap="toggleModelDropdown">
                       <text class="model-name">{{ currentModelName }}</text>
                       <text class="dropdown-arrow">▼</text>
                       <ModelSelectorDropdown v-if="showModelDropdown" placement="down"
                          :groups="modelGroups" :current-model-id="currentModelId"
                          :price-display="modelPriceDisplay" :network-region-basis="networkRegionBasis"
                          @select="selectModel" />
                    </view>
                    <!-- Skill Selector：触发词自动匹配始终生效，这里是「额外主动加载」的多选入口 -->
                    <view class="skill-selector" :class="{ pinned: selectedSkillIds.length > 0, muted: skillDisabledByMode }" :title="skillDisabledByMode ? $t('chat.skillAskDisabled') : $t('chat.skillDefaultTitle')" @tap="toggleSkillDropdown">
                       <svg class="skill-glyph-svg" viewBox="0 0 24 24" fill="none">
                          <path v-for="(d, gi) in ICONS.skill" :key="gi" :d="d" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round" />
                       </svg>
                       <text v-if="selectedSkillIds.length && !skillDisabledByMode" class="skill-count">{{ selectedSkillIds.length }}</text>
                       <view v-if="showSkillDropdown" class="skill-dropdown down" role="listbox">
                          <view class="skill-dropdown-head">
                             <text class="skill-dropdown-title">{{ $t('chat.skillPickerTitle') }}</text>
                             <text class="skill-dropdown-hint">{{ skillDisabledByMode ? $t('chat.skillAskDisabled') : $t('chat.skillPickerHint') }}</text>
                          </view>
                          <view v-if="availableSkills.length" class="skill-divider"></view>
                          <view v-for="s in availableSkills" :key="s.id"
                                class="skill-option"
                                :class="{ active: selectedSkillIds.includes(s.id), muted: skillDisabledByMode }"
                                role="option" tabindex="0" :aria-selected="selectedSkillIds.includes(s.id) ? 'true' : 'false'"
                                @keydown.enter.stop="onOptionKey($event, () => (skillDisabledByMode ? null : toggleSkillSelection(s.id)))"
                                @keydown.space.stop="onOptionKey($event, () => (skillDisabledByMode ? null : toggleSkillSelection(s.id)))"
                                @tap.stop="skillDisabledByMode ? null : toggleSkillSelection(s.id)">
                             <text class="skill-check">{{ selectedSkillIds.includes(s.id) ? '✓' : '' }}</text>
                             <view class="skill-option-text">
                                <text class="skill-option-name">{{ skillDisplayName(s) }}</text>
                                <text class="skill-option-desc">{{ s.activationMode === 'manual' ? $t('chat.skillManualOnly') : (s.triggers || []).join(' / ') || $t('chat.skillNoTriggers') }}</text>
                             </view>
                          </view>
                          <view v-if="!availableSkills.length" class="skill-empty">{{ $t('chat.skillNoneInstalled') }}</view>
                          <view class="skill-divider"></view>
                          <view class="skill-manage" @tap.stop="goToSkillManagement">{{ $t('chat.skillManage') }}</view>
                       </view>
                    </view>
                 </view>
                 <view class="composer-actions">
                    <view v-if="isStreaming" class="follow-mode" @tap="toggleFollowUpMode">
                       {{ followUpMode === 'steer' ? $t('chat.followUpSteer') : $t('chat.followUpQueue') }}
                    </view>
                    <view v-if="isStreaming" class="alternate-send" @tap="handleSubmit(followUpMode === 'steer' ? 'queue' : 'steer')">
                       {{ followUpMode === 'steer' ? $t('chat.queueInstead') : $t('chat.steerInstead') }}
                    </view>
                    <button v-if="isStreaming" type="button" class="stop-btn" :aria-label="$t('chat.stop')" @click="handleAbort"><text>■</text></button>
                    <button type="button" class="send-btn" :class="{ disabled: !inputPrompt.trim() || isUploadingPasted }" :aria-label="$t('chat.sendAria')" @click="handleSubmit(followUpMode)">
                       <text class="send-icon">↑</text>
                    </button>
                 </view>
              </view>
          </view>
          <DecisionAssistControl :enabled="decisionAssistEnabled" :local-only="isLocalOnlyProvider" @toggle="toggleDecisionAssist" />
          <view v-if="showModelDropdown || showModeDropdown || showSkillDropdown" class="dropdown-mask model-mask" @tap="showModelDropdown = false; showModeDropdown = false; showSkillDropdown = false"></view>
       </view>

       <!-- Bottom: History (pushed to bottom with flexbox) -->
       <view class="empty-bottom-section">
          <view class="recent-history-header">{{ $t('chat.recentChats') }}</view>
          <view class="recent-history" v-if="recentHistory && recentHistory.length > 0">
             <view v-for="h in recentHistory" :key="h.id" class="history-item" @tap="$emit('load-history', h)">
                <view v-if="recentDotClass(h)" class="conv-dot" :class="recentDotClass(h)"></view>
                <text class="history-title">{{ cleanTitle(h.title) }}</text>
                <text class="history-time">{{ formatRelativeTime(h.updatedAt) }}</text>
             </view>
          </view>
          <view v-else class="history-empty-placeholder">
             <text>{{ $t('chat.recentChatsEmpty') }}</text>
          </view>
          <view class="history-disclaimer">{{ $t('chat.aiDisclaimer') }}</view>
       </view>
    </view>

    <!-- 4. Regular Bottom Input -->
    <view v-else class="input-area-wrapper">
       <!-- 插件镜像会话只读（dev-board#298）：输入区整体换成说明条，
            唯一动作是「另起分支继续」（fork 后由宿主切到新会话并解除只读） -->
       <view v-if="externalReadOnly" class="readonly-bar">
          <text class="readonly-text">{{ $t('chat.pluginReadOnlyNotice', { source: externalReadOnly }) }}</text>
          <view class="readonly-fork-btn" @tap="$emit('fork-conversation')">{{ $t('chat.forkToContinue') }}</view>
       </view>
       <template v-else>
       <!-- 任务清单进度卡已随消息流内联展示（RootBubble），不再常驻输入框上方，
            避免与气泡内的步骤分组重复（用户反馈：线性时序结构） -->
       <!-- 步数超限暂停 / 上次进程被杀：一键继续，免得用户手动输入「继续」 -->
       <view v-if="agentPaused && !isStreaming" class="continue-bar">
          <text class="continue-hint">{{ continueHint }}</text>
          <view class="continue-btn" @tap="handleContinue">{{ $t('chat.continueRun') }}</view>
       </view>
       <!-- SSE 断连提示（dev-board#364）：心跳 45s 没到或流意外结束时后台在自动重连；
            之前只写 console.warn，用户看到的是思考计时器一直走、分不清模型在想还是连接死了 -->
       <view v-if="linkStatus && linkStatus.state === 'reconnecting'" class="link-bar">
          <text class="link-hint">{{ $t('chat.linkReconnecting', { attempt: linkStatus.attempt }) }}</text>
       </view>
       <!-- 同一条会话被另一个窗口接上了（dev-board#803）：这里已经停止重连，
            不说一句的话用户看到的是一个永远不再更新、也不报错的窗口 -->
       <view v-else-if="linkStatus && linkStatus.state === 'superseded'" class="link-bar">
          <text class="link-hint">{{ $t('chat.linkSuperseded') }}</text>
       </view>
       <!-- 长任务可控：进度条在浮窗里（BackgroundTaskIndicator），控制放在输入框上方——
            用户想停的时候手在输入区，不该先去浮窗里找按钮。
            文案只说「正在停止」：取消打不断已经发出去的调用（PPT 服务那边还会跑完）。 -->
       <view v-if="runningTasks.length > 0" class="task-control-bar">
          <view v-for="t in runningTasks" :key="t.taskId" class="task-control-row">
             <text class="task-control-name">{{ taskTypeName(t.type) }}</text>
             <text class="task-control-msg">{{ t.message }}</text>
             <view class="task-control-btn" :class="{ pending: !!stoppingTasks[t.taskId] }" @tap.stop="handleCancelTask(t)">
                <text>{{ stoppingTasks[t.taskId] ? $t('chat.stoppingEllipsis') : $t('chat.stop') }}</text>
             </view>
          </view>
       </view>
       <!-- NEW: File Changes & Token Usage Bar (Always visible) -->
       <view class="status-bar-row">
           <!-- Left: File Changes -->
           <view class="status-bar-left">
               <!-- Modified Files -->
                <view class="status-btn-wrapper">
                    <view class="status-btn modified" :class="{ empty: modifiedFiles.length === 0 }" @tap.stop="modifiedFiles.length > 0 ? toggleModifiedPopup() : null">
                        <svg class="status-icon" xmlns="http://www.w3.org/2000/svg" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M12 20h9"/><path d="M16.5 3.5a2.121 2.121 0 0 1 3 3L7 19l-4 1 1-4L16.5 3.5z"/></svg>
                        <text>{{ $t('chat.modifiedCount', { count: modifiedFiles.length }) }}</text>
                    </view>
                   <view v-if="showModifiedPopup && modifiedFiles.length > 0" class="status-popup up">
                       <view v-for="(f, i) in modifiedFiles" :key="i" class="status-popup-item" @tap.stop="handleOpenFile(f)">
                           <image src="/static/file.png" class="file-icon-mini"/>
                           <text class="file-name-text">{{ fileChangeLabel(f) }}</text>
                       </view>
                   </view>
                   <view v-if="showModifiedPopup && modifiedFiles.length > 0" class="popup-mask-transparent" @tap.stop="showModifiedPopup = false"></view>
               </view>

               <!-- New Files -->
               <view class="status-btn-wrapper">
                   <view class="status-btn created" :class="{ empty: createdFiles.length === 0 }" @tap.stop="createdFiles.length > 0 ? toggleNewPopup() : null">
                       <svg class="status-icon" xmlns="http://www.w3.org/2000/svg" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><line x1="12" y1="5" x2="12" y2="19"></line><line x1="5" y1="12" x2="19" y2="12"></line></svg>
                       <text>{{ $t('chat.createdCount', { count: createdFiles.length }) }}</text>
                   </view>
                   <view v-if="showNewPopup && createdFiles.length > 0" class="status-popup up">
                       <view v-for="(f, i) in createdFiles" :key="i" class="status-popup-item" @tap.stop="handleOpenFile(f)">
                           <image src="/static/file.png" class="file-icon-mini"/>
                           <text class="file-name-text">{{ fileChangeLabel(f) }}</text>
                       </view>
                   </view>
                   <view v-if="showNewPopup && createdFiles.length > 0" class="popup-mask-transparent" @tap.stop="showNewPopup = false"></view>
               </view>
           </view>

           <!-- Right: Token Usage
                本轮用量（dev-board#792 / 审查 F3①）。采集一直都在，此前整块被注释掉，
                于是「这一轮花了多少」用户完全看不见——而 Credits 是站内唯一计价单位。
                低调一行、只在有数时出现：为 0 说明后端还没回 token_usage（Ollama 档不回），
                挂一个 0 会像是「这一轮不要钱」。 -->
           <view v-if="tokenUsage && tokenUsage.totalTokens > 0" class="status-bar-right">
               <text class="token-value">{{ $t('chat.tokenUsageLine', { n: tokenUsage.totalTokens.toLocaleString() }) }}</text>
           </view>
       </view>
       <AgentInbox
         :items="pendingInbox"
         :stream-ids="inboxStreamIds"
         :run-active="inboxRunActive"
         @edit="handleInboxEdit"
         @delete="handleInboxDelete"
         @move="handleInboxMove"
         @send-now="handleInboxSendNow"
         @locate="handleInboxLocate"
       />
       <!-- 音频附件没有转写稿（dev-board#814）：模型读的是转写稿，不是音频本身。
            不说的话用户会以为 AI 听过这段录音，然后照着一个凭空的回答往下走。
            「转写」走的就是文件树右键那条动作，用户不必先去把文件找出来。 -->
       <view v-if="pendingAudioFiles.length > 0" class="audio-transcribe-bar">
          <text class="audio-transcribe-hint">{{ pendingAudioFiles.length > 1
             ? $t('chat.audioNotTranscribedMore', { name: pendingAudioFiles[0].name, count: pendingAudioFiles.length - 1 })
             : $t('chat.audioNotTranscribed', { name: pendingAudioFiles[0].name }) }}</text>
          <view class="audio-transcribe-btn" @tap="handleTranscribeAudio(pendingAudioFiles[0])">{{ $t('chat.audioTranscribeAction') }}</view>
       </view>
       <view class="input-card" :class="{ 'is-drop-target': dragActive }">
           <!-- Image Thumbnails Preview (top-left) -->
           <view v-if="pastedImages.length > 0" class="input-images-preview">
              <view v-for="(img, index) in pastedImages" :key="index" class="preview-image-item">
                 <image v-if="img.path" :src="img.path" mode="aspectFill" class="preview-thumb" />
                 <text class="preview-remove" @tap="removePastedImage(index)">×</text>
              </view>
           </view>
           <!-- 当前文档 chip（K14 ①）：看得见、摘得掉。摘掉只对这一轮生效 -->
           <view v-if="activeDocChip" class="active-doc-chip" :title="$t('chat.activeDocChipTitle')">
              <text class="active-doc-label">{{ $t('chat.activeDocChipLabel') }}</text>
              <text class="active-doc-name">{{ activeDocChip.name }}</text>
              <text class="active-doc-remove" @tap.stop="dismissActiveDoc">×</text>
           </view>
           <!-- 「模型看不了图」常驻提示（K21 ⑨）：粘的、拖的图片都覆盖；能力未知一律不提示 -->
           <text v-if="visionNotice" class="input-images-note">{{ $t(visionNotice) }}</text>
          <div
            ref="richInput"
            class="chat-input-rich"
            contenteditable="true"
            @input="handleRichInput"
            @paste="handlePaste"
            @keydown="handleInputKeydown"
            @click="handleInputClick"
            :data-placeholder="$t('chat.inputPlaceholder')"
          ></div>
          <!-- `@` 引用选择器（dev-board#794 K15）：浮在输入卡上沿 -->
          <MentionPicker v-if="mentionOpen" ref="mentionPicker" :files="mentionCandidates"
                         :query="mentionQuery" :loading="mentionLoading" @select="chooseMentionFile" />
          <!-- Note: Context files are now shown as inline tags inside the rich input -->
          <!-- 本轮生效的 Skill：手动选的带 × 可移除，自动命中的新出现时闪一下 -->
          <view v-if="skillChips.length" class="skill-chip-row">
             <view v-for="chip in skillChips" :key="chip.id"
                   class="skill-chip"
                   :class="{ auto: chip.source === 'auto', flash: chip.justActivated }">
                <text class="skill-chip-name">{{ chip.name }}</text>
                <text v-if="chip.source === 'manual'" class="skill-chip-remove"
                      @tap.stop="removeSelectedSkill(chip.id)">×</text>
             </view>
          </view>
          <view class="input-footer">
             <view class="action-bar-left">
                <view class="icon-btn mini" @tap="triggerFileSelect" title="Add File">
                   <svg class="plus-svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><line x1="12" y1="5" x2="12" y2="19"/><line x1="5" y1="12" x2="19" y2="12"/></svg>
                </view>
                <!-- Agent Mode Selector -->
                <view class="mode-selector" @tap="toggleModeDropdown">
                   <text class="mode-icon" v-if="currentModeIcon">{{ currentModeIcon }}</text>
                   <text class="mode-name">{{ currentModeName }}</text>
                   <text class="dropdown-arrow">▲</text>
                   <view v-if="showModeDropdown" class="mode-dropdown up" role="listbox">
                      <view v-for="mode in availableModes" :key="mode.id"
                            class="mode-option"
                            :class="{ active: currentModeId === mode.id }"
                            role="option" tabindex="0" :aria-selected="currentModeId === mode.id ? 'true' : 'false'"
                            @keydown.enter.stop="onOptionKey($event, () => selectMode(mode))"
                            @keydown.space.stop="onOptionKey($event, () => selectMode(mode))"
                            @tap.stop="selectMode(mode)">
                         <text class="mode-option-icon" v-if="mode.icon">{{ mode.icon }}</text>
                         <view class="mode-option-text">
                            <text class="mode-option-name">{{ mode.name }}</text>
                            <text class="mode-option-desc">{{ mode.desc }}</text>
                         </view>
                      </view>
                      <view v-if="localModeNotice" class="mode-note">{{ localModeNotice }}</view>
                   </view>
                </view>
                <!-- Model Selector：清单来自 GET /api/ai/models，按厂商分组、国际档在后 -->
                <view class="model-selector" @tap="toggleModelDropdown">
                   <text class="model-name">{{ currentModelName }}</text>
                   <text class="dropdown-arrow">▲</text>
                   <ModelSelectorDropdown v-if="showModelDropdown" placement="up"
                      :groups="modelGroups" :current-model-id="currentModelId"
                      :price-display="modelPriceDisplay" :network-region-basis="networkRegionBasis"
                      @select="selectModel" />
                </view>
                <!-- Skill Selector：触发词自动匹配始终生效，这里是「额外主动加载」的多选入口 -->
                <view class="skill-selector" :class="{ pinned: selectedSkillIds.length > 0, muted: skillDisabledByMode }" :title="skillDisabledByMode ? $t('chat.skillAskDisabled') : $t('chat.skillDefaultTitle')" @tap="toggleSkillDropdown">
                   <svg class="skill-glyph-svg" viewBox="0 0 24 24" fill="none">
                      <path v-for="(d, gi) in ICONS.skill" :key="gi" :d="d" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round" />
                   </svg>
                   <text v-if="selectedSkillIds.length && !skillDisabledByMode" class="skill-count">{{ selectedSkillIds.length }}</text>
                   <view v-if="showSkillDropdown" class="skill-dropdown up" role="listbox">
                      <view class="skill-dropdown-head">
                         <text class="skill-dropdown-title">{{ $t('chat.skillPickerTitle') }}</text>
                         <text class="skill-dropdown-hint">{{ skillDisabledByMode ? $t('chat.skillAskDisabled') : $t('chat.skillPickerHint') }}</text>
                      </view>
                      <view v-if="availableSkills.length" class="skill-divider"></view>
                      <view v-for="s in availableSkills" :key="s.id"
                            class="skill-option"
                            :class="{ active: selectedSkillIds.includes(s.id), muted: skillDisabledByMode }"
                            role="option" tabindex="0" :aria-selected="selectedSkillIds.includes(s.id) ? 'true' : 'false'"
                            @keydown.enter.stop="onOptionKey($event, () => (skillDisabledByMode ? null : toggleSkillSelection(s.id)))"
                            @keydown.space.stop="onOptionKey($event, () => (skillDisabledByMode ? null : toggleSkillSelection(s.id)))"
                            @tap.stop="skillDisabledByMode ? null : toggleSkillSelection(s.id)">
                         <text class="skill-check">{{ selectedSkillIds.includes(s.id) ? '✓' : '' }}</text>
                         <view class="skill-option-text">
                            <text class="skill-option-name">{{ skillDisplayName(s) }}</text>
                            <text class="skill-option-desc">{{ s.activationMode === 'manual' ? $t('chat.skillManualOnly') : (s.triggers || []).join(' / ') || $t('chat.skillNoTriggers') }}</text>
                         </view>
                      </view>
                      <view v-if="!availableSkills.length" class="skill-empty">{{ $t('chat.skillNoneInstalled') }}</view>
                      <view class="skill-divider"></view>
                      <view class="skill-manage" @tap.stop="goToSkillManagement">{{ $t('chat.skillManage') }}</view>
                   </view>
                </view>
             </view>
             <view class="composer-actions">
                <view v-if="isStreaming" class="follow-mode" @tap="toggleFollowUpMode">
                   {{ followUpMode === 'steer' ? $t('chat.followUpSteer') : $t('chat.followUpQueue') }}
                </view>
                <view v-if="isStreaming" class="alternate-send" @tap="handleSubmit(followUpMode === 'steer' ? 'queue' : 'steer')">
                   {{ followUpMode === 'steer' ? $t('chat.queueInstead') : $t('chat.steerInstead') }}
                </view>
                <button v-if="isStreaming" type="button" class="stop-btn" :aria-label="$t('chat.stop')" @click="handleAbort"><text>■</text></button>
                <button type="button" class="send-btn" :class="{ disabled: !inputPrompt.trim() || isUploadingPasted }" :aria-label="$t('chat.sendAria')" @click="handleSubmit(followUpMode)">
                   <text class="send-icon">↑</text>
                </button>
             </view>
          </view>
          <view v-if="showModelDropdown || showModeDropdown || showSkillDropdown" class="dropdown-mask" @tap="showModelDropdown = false; showModeDropdown = false; showSkillDropdown = false"></view>
       </view>
          <DecisionAssistControl :enabled="decisionAssistEnabled" :local-only="isLocalOnlyProvider" @toggle="toggleDecisionAssist" />
       </template>
    </view>

    <!-- Background Task Progress Indicator -->
    <BackgroundTaskIndicator
      :backgroundTasks="backgroundTasks"
      :lastHeartbeat="lastHeartbeat"
      @dismiss="dismissBackgroundTask"
    />

    <!-- 可选组件缺失（设计 §4.2）：确认前把体积、解锁什么、不装则什么不可用都说全，
         确认后卡片就地跳进度，装完自动重发原消息。下载中可「后台下载」收起卡片继续用对话
         （dev-board#581），装完是否重发见 useComponentRequired.shouldAutoResend。 -->
    <view v-if="componentGateItem" class="chat-component-gate">
      <view class="cg-panel">
        <text class="cg-title">{{ $t('components.chatTitle') }}</text>
        <OptionalComponentCard :item="componentGateItem" :selectable="false" :busy="true" />
        <view v-if="componentGateResolved" class="cg-installing">
          <text class="cg-installing-text">{{ $t('components.chatInstalling') }}</text>
          <view class="cg-actions">
            <view class="cg-btn cg-background" @tap="backgroundComponentGate">{{ $t('components.backgroundDownload') }}</view>
          </view>
        </view>
        <view v-else class="cg-actions">
          <view class="cg-btn primary" @tap="resolveComponentGate(true)">{{ $t('components.chatConfirm') }}</view>
          <view class="cg-btn" @tap="resolveComponentGate(false)">{{ $t('components.chatCancel') }}</view>
        </view>
      </view>
    </view>

  </view>
</template>

<script>
import RootBubble from './AgentMessage/RootBubble.vue'
import ChatTurnRail from './AgentMessage/ChatTurnRail.vue'
import MentionPicker from './AgentMessage/MentionPicker.vue'
import { buildChatTurns, isPlanSnapshotCall, pendingAttention, recoverPlanTodos } from './AgentMessage/chatTurns.mjs'
import { useChatReadingPosition } from '@/composables/useChatReadingPosition.js'
import BackgroundTaskIndicator from './BackgroundTaskIndicator.vue'
import AgentInbox from './AgentInbox.vue'
import MemoryBrowser from './MemoryBrowser.vue'
import { useAgentStream } from '@/composables/useAgentStream.js'
import { ref, watch, onMounted, onBeforeUnmount, nextTick, getCurrentInstance, computed } from 'vue'
import { createFile, getProjectFiles, getApiBaseUrl, getAiHistory, rollbackConversation, performPptGeneration, getSkills, fetchAiModels, getAiConfig, cancelBackgroundTask, listPluginJobs, cancelPluginJob, getMeetingRecordings } from '@/services/api.js'
import { audioNeedingTranscription, isAudioFile, transcribedAudioFileIds } from '@/utils/audioAttachment.js'
import { getAuthHeaders, getCurrentUser } from '@/utils/auth.js'
import DecisionAssistControl from './DecisionAssistControl.vue'
import ModelSelectorDropdown from './ModelSelectorDropdown.vue'
import { decisionAssistPreferenceKey, readDecisionAssistPreference, writeDecisionAssistPreference } from '@/utils/decisionAssistPreference.js'
import { getAppLanguage } from '@/utils/appLanguage.js'
import { t } from '@/i18n'
import { ICONS } from '@/config/icons.js'
import OptionalComponentCard from '@/components/OptionalComponentCard.vue'
import { componentDownloads } from '@/services/componentDownloads.js'
import { createComponentRequiredHandler, shouldAutoResend } from '@/composables/useComponentRequired.js'
import { pendingInboxItems } from '@/composables/agentInboxState.mjs'
import { saveLastConversation } from '@/utils/lastConversation.js'
import { isContextEligibleTab } from '@/pages/project-overview/activeTabContext.js'
import { isCurrentDocSentinel } from '@/utils/chatFileChange.js'
import {
  DEFAULT_CONTEXT_LIMITS,
  normalizeContextLimits,
  visionNoticeKey,
  admitFileToContext,
  admitPastedImage,
  formatBytes,
} from '@/utils/chatContextLimits.js'
import { attachmentRecord, attachmentsFromHistory, fileListFromBubble } from '@/utils/chatAttachments.js'
import {
  AI_CONTEXT_FOLDER_FILE_LIMIT,
  countDescendantFiles,
  dirLabelOf,
  excludeSystemFolders,
  matchProjectFiles,
} from '@/utils/aiContextFiles.js'
import {
  beginChatSubmission,
  failChatSubmission,
  receiptChatSubmission,
  shouldClearChatDraft,
  submitChatAttempt,
} from '@/composables/chatSubmissionState.mjs'

export default {
  name: 'ChatInterface',
  components: { DecisionAssistControl, ModelSelectorDropdown, RootBubble, ChatTurnRail, MentionPicker, BackgroundTaskIndicator, AgentInbox, MemoryBrowser, OptionalComponentCard },
  props: {
    projectId: String,
    projectName: String,
    recentHistory: {
      type: Array,
      default: () => []
    },
    // 历史入口聚合状态点：'' | 'dot-attention' | 'dot-running' | 'dot-unread'（宿主计算）
    historyBadge: {
      type: String,
      default: ''
    },
    // NEW: Current active tab for auto-context injection
    activeTab: {
      type: Object,
      default: null
    },
    activeTabPane: {
      type: String,
      default: null // 'left' | 'right' | null
    },
    // 插件镜像会话只读态（dev-board#298）：非空 = 当前会话是插件同步过来的镜像，
    // 值是来源文案（如「Word 插件」，由宿主用 utils/conversationSource.js 算好传入）。
    // 输入区整体换成说明条 +「另起分支继续」按钮（emit 'fork-conversation'）。
    externalReadOnly: {
      type: String,
      default: ''
    },
    // 宿主（project-overview 的 .side-panel-ai）正在被拖拽悬停。高亮画在输入框卡片上，
    // 与占位文案「拖拽文件/文件夹至此」指向同一处（dev-board#779 K6 ④）。
    dragActive: {
      type: Boolean,
      default: false
    },
    /**
     * 发消息前把当前活跃文档落盘（dev-board#793 K14 ⑤）。宿主传进来的函数，
     * 签名 `(fileId, { timeoutMs }) => Promise<boolean>`，返回 false = 没能落盘。
     *
     * 做成 prop 而不是 emit：emit 拿不到结果，而这里必须知道成没成
     * （没成就把活跃文档降级成「只带壳」，让模型走编辑器桥读实时正文）。
     * 不传（插件/测试宿主）时整段跳过，行为与改动前一致。
     */
    flushActiveDocument: {
      type: Function,
      default: null
    }
  },
  setup(props, { emit, expose }) {
    const {
      bubbles,
      isStreaming,
      sendMessage: sendAgentMessage,
      abort,
      setConversationId,
      clearBubbles,
      parseAssistantHistory,
      onClientAction,
      onTitleUpdate,
      backgroundTasks,
      dismissBackgroundTask,
      upsertPluginJob,
      lastHeartbeat,
      tokenUsage,
      fileChanges,
      agentPaused,
      agentRunStatus,
      linkStatus,
      activeSkills,
      skillNotice,
      reattachSSE,
      rollbackToMessage,
      currentConversationId,
      loadConversationMetadata,
      inboxState,
      updateInbox,
      deleteInbox,
    } = useAgentStream()

    // 可选组件缺失闸（设计 §4.2）。下载走应用级单例（dev-board#581）：与首次登录面板、
    // 组件管理页同一份编排、同一份进度，顺序 pack → 模型 → ensure(service) 不能换。
    const componentGateItem = ref(null)
    const componentGateResolved = ref(false)
    const componentGateResolve = ref(null)
    // 前台认领：对话组件活着就由它交代结果（重发或提示可重试）；卸载时释放，交给全局提示
    const componentClaims = new Map()
    const backgroundedPacks = new Set()
    let chatAlive = true
    const userMessageCount = () =>
      bubbles.value.filter((b) => b && String(b.role).toUpperCase() === 'USER').length
    const releaseComponentClaim = (packId) => {
      const release = componentClaims.get(packId)
      if (release) release()
      componentClaims.delete(packId)
      backgroundedPacks.delete(packId)
    }
    /** 只收起属于这个 packId 的卡片：后台装完时卡片上可能已经换成了另一个组件 */
    const closeComponentGate = (packId) => {
      if (componentGateItem.value && componentGateItem.value.packId === packId) componentGateItem.value = null
    }
    const componentRequiredHandler = createComponentRequiredHandler({
      adopt: (item) => componentDownloads.adopt(item),
      isInstalling: (packId) => componentDownloads.isInstalling(packId),
      // 别的入口已经在下这个组件：卡片直接进「下载中」，不再问一遍
      attach: (item) => {
        componentGateItem.value = item
        componentGateResolved.value = true
      },
      installOne: (item) => {
        if (!componentClaims.has(item.packId)) componentClaims.set(item.packId, componentDownloads.claim(item.packId))
        return componentDownloads.installOne(item)
      },
      fillSizes: (item) => componentDownloads.fillSizes(item),
      mark: () => userMessageCount(),
      shouldResend: (mark, item) => shouldAutoResend({
        alive: chatAlive,
        backgrounded: backgroundedPacks.has(item.packId),
        streaming: isStreaming.value,
        userCountAtGate: mark,
        userCountNow: userMessageCount(),
      }),
      readyNotice: (item) => {
        closeComponentGate(item.packId)
        if (chatAlive) uni.showToast({ title: t('components.chatReadyRetry'), icon: 'none', duration: 3500 })
      },
      // 弹窗确认：把 item 挂上去，等模板里的按钮 resolve
      confirm: (item) => new Promise((resolve) => {
        componentGateItem.value = item
        componentGateResolved.value = false
        componentGateResolve.value = resolve
      }),
      lastUserMessage: () => {
        for (let i = bubbles.value.length - 1; i >= 0; i--) {
          const b = bubbles.value[i]
          if (b && String(b.role).toUpperCase() === 'USER') return b.content || ''
        }
        return ''
      },
      resend: async (text, item) => {
        closeComponentGate(item.packId)
        uni.showToast({ title: t('components.chatResending'), icon: 'none' })
        await sendMessage({
          prompt: text,
          projectId: props.projectId,
          modelId: currentModelId.value,
          mode: currentModeId.value,
          skillIds: currentSkillIds()
        })
        scrollToBottom()
      },
      toast: (msg, item) => {
        closeComponentGate(item.packId)
        // 已卸载时认领早已释放，失败由全局提示交代
        if (chatAlive) uni.showToast({ title: t('components.stateFailed', { msg }), icon: 'none' })
      },
    })
    /** 「后台下载」：收起卡片继续用对话；装完时按 shouldAutoResend 决定重发还是只提示 */
    const backgroundComponentGate = () => {
      const item = componentGateItem.value
      if (!item) return
      backgroundedPacks.add(item.packId)
      componentGateItem.value = null
      uni.showToast({ title: t('components.backgroundStarted'), icon: 'none', duration: 3000 })
    }
    onBeforeUnmount(() => {
      chatAlive = false
      for (const packId of [...componentClaims.keys()]) releaseComponentClaim(packId)
    })
    /** 确认走下载（弹窗留着，卡片就地跳进度）；取消则直接收起 */
    const resolveComponentGate = (ok) => {
      const resolve = componentGateResolve.value
      componentGateResolve.value = null
      componentGateResolved.value = !!ok
      if (!ok) componentGateItem.value = null
      if (resolve) resolve(ok)
    }

    // Bridge Stream Events to Component Events
    onClientAction((action) => {
        if (action.action === 'ppt_config_required') {
           // Show PPT config dialog
           pptConfigData.value = action
           pptExportEditable.value = false // Default to safe option
           showPptConfigDialog.value = true
        } else if (action.action === 'component_required') {
           // 可选组件缺失（设计 §4.2）：就地弹窗 → 装 → 自动重发原消息。
           // 刻意不往下 emit：它不是编辑器命令，执行器只会回 Unknown action。
           componentRequiredHandler.onAction(action).then((r) => {
              if (r.duplicate) return
              closeComponentGate(action.packId)
              releaseComponentClaim(action.packId)
           })
        } else {
           emit('client-action', action)
        }
    })

    // Bridge Title Update Event to Parent
    onTitleUpdate((title) => {
        emit('title-update', title)
        emit('refresh-history') // Trigger history refresh to show new title
    })
    const decisionAssistEnabled = ref(false)
    const decisionAssistIdentity = () => decisionAssistPreferenceKey(getCurrentUser(), getApiBaseUrl())
    let decisionAssistOwner = decisionAssistIdentity()
    decisionAssistEnabled.value = readDecisionAssistPreference(uni, decisionAssistOwner)
    const syncDecisionAssistPreference = (reload = false) => {
      const owner = decisionAssistIdentity()
      if (owner !== decisionAssistOwner || reload === true) {
        decisionAssistOwner = owner
        decisionAssistEnabled.value = readDecisionAssistPreference(uni, owner)
      }
      return decisionAssistEnabled.value
    }
    const toggleDecisionAssist = () => {
      // Re-read the identity before writing: switching accounts must never inherit consent.
      syncDecisionAssistPreference()
      if (!decisionAssistOwner) return
      decisionAssistEnabled.value = !decisionAssistEnabled.value
      writeDecisionAssistPreference(uni, decisionAssistOwner, decisionAssistEnabled.value)
    }
    const sendMessage = (options) => sendAgentMessage({
      ...options,
      decisionAssistEnabled: Object.prototype.hasOwnProperty.call(options, 'decisionAssistEnabled')
        ? options.decisionAssistEnabled === true && options.decisionAssistOwner === decisionAssistIdentity()
        : syncDecisionAssistPreference(),
    })
    const refreshDecisionAssistPreference = () => syncDecisionAssistPreference(true)
    onMounted(() => {
      window.addEventListener('focus', refreshDecisionAssistPreference)
      window.addEventListener('storage', refreshDecisionAssistPreference)
    })
    onBeforeUnmount(() => {
      window.removeEventListener('focus', refreshDecisionAssistPreference)
      window.removeEventListener('storage', refreshDecisionAssistPreference)
    })

    const inputPrompt = ref('')
    const richInput = ref(null)
    const showMemoryBrowser = ref(false)
    const submissionTracker = { failed: null, inflight: {} }
    const followUpMode = ref('steer')
    try {
      followUpMode.value = uni.getStorageSync('awd_agent_follow_up_mode') === 'queue' ? 'queue' : 'steer'
    } catch (e) { /* storage unavailable */ }
    const pendingInbox = computed(() => pendingInboxItems(inboxState))
    /**
     * 「这条会话现在有没有轮次在跑」（dev-board#802），决定 steer 项要不要露出「立即发送」。
     *
     * 判据就是 isStreaming：切回一条后台仍在跑的会话时，后端 connect 必发的
     * run_state=RUNNING 会把它置起（见 useAgentStream 的 run_state 分支），所以它不只是
     * 「本窗口从头看到尾的那一轮」。
     *
     * **刻意不再与 agentRunStatus === 'RUNNING' 取或**：用户点停止之后，本地 isStreaming
     * 立刻置 false，而 agentRunStatus 要等后端的 cancelled 事件才落终态——SSE 正好死了的话
     * 它会永远停在 RUNNING。而「点了停止，插话卡住」恰恰是本卡要修的那条链，用一个可能
     * 永远不归位的状态去挡救命按钮，等于把病灶换了个地方。宁可多显示一次：真有轮次在跑时
     * 点它，后端 acceptInboxSubmission 是幂等的（进去先查活跃轮次，有就原样返回）。
     */
    const inboxRunActive = computed(() => isStreaming.value)
    const messageList = ref(null)
    const messageContent = ref(null)
    // 内容没变的轮次要保持同一个 turn 对象（dev-board#811 K31）：模板上那条 v-memo
    // 以它为第一依赖，身份一变就整棵重建。**这个 cache 必须是普通对象，不能进响应式数据**
    // ——它每次求值都会被写一遍，放进 ref/reactive 会让 computed 自己把自己弄脏。
    const turnCache = {}
    const chatTurns = computed(() => buildChatTurns(bubbles.value, {
      isStreaming: isStreaming.value, runStatus: agentRunStatus.value, cache: turnCache
    }))
    // 待处理定位条：长会话里反问卡/审批卡会被滚出视野，用户既看不见也回不去（#663）。
    // 只在「确实测量到它不在可视区」时出现——看得见的卡再挂一条提示只是噪音。
    const attentionTarget = computed(() => pendingAttention(chatTurns.value))
    const attentionOffscreen = ref(false)
    const syncAttentionLocator = () => {
      const target = attentionTarget.value
      // 贴着底读就一定看得见这张卡（它恒是最后一条）——历史回灌那一帧 DOM 已渲染、
      // 自动贴底还没执行，不挡住的话浮条会闪一下再自己消失。
      attentionOffscreen.value = !followLatest.value && !!target && isMessageOffscreen({ index: target.index, target: 'attention' })
    }
    const { followLatest, handleMessageScroll, scrollToBottom, navigateToMessage, isMessageOffscreen } =
      useChatReadingPosition(messageList, messageContent, () => syncAttentionLocator())
    watch(attentionTarget, syncAttentionLocator, { flush: 'post' })
    const attentionNotice = computed(() => {
      const target = attentionOffscreen.value ? attentionTarget.value : null
      if (!target) return null
      return { index: target.index, key: target.kind === 'question' ? 'chat.attentionLocatorQuestion' : 'chat.attentionLocatorApproval', n: target.count }
    })
    const jumpToAttention = () => {
      const notice = attentionNotice.value
      const card = notice && navigateToMessage({ index: notice.index, target: 'attention' })
      if (!card) return
      // 滚到位还不够：长会话里卡片和周围的正文长得一样，不闪一下用户仍要自己找。
      card.classList.add('chat-attention-flash')
      setTimeout(() => card.classList.remove('chat-attention-flash'), 1600)
      syncAttentionLocator()
    }
    // 钢琴键会话导航（dev-board#791 K12）：当前在看哪一轮。
    // observer 只观察轮级元素（几十个），**不观察每条消息**，更不在 scroll 回调里逐轮量
    // getBoundingClientRect——那正是长会话掉帧的写法。root 取滚动容器本身，当前轮 =
    // 视口内最靠上的那一轮（律师读到哪儿，哪一轮就顶在屏幕上沿）；顶边内缩 8% 是为了
    // 让只剩一条边还挂在上沿的上一轮及时让位给真正在读的那一轮。
    const activeTurnKey = ref('')
    const visibleTurnEls = new Map()
    let turnObserver = null
    const syncTurnObserver = () => {
      turnObserver?.disconnect()
      turnObserver = null
      visibleTurnEls.clear()
      const list = messageList.value?.$el || messageList.value
      if (!list || typeof IntersectionObserver === 'undefined') return
      turnObserver = new IntersectionObserver((entries) => {
        for (const entry of entries) {
          const key = entry.target.getAttribute('data-turn-key')
          if (!key) continue
          if (entry.isIntersecting) visibleTurnEls.set(key, entry.target)
          else visibleTurnEls.delete(key)
        }
        // 取「最靠上的那一轮」。这里现读 rect 而不是用回调里那份快照：仍然在屏的条目
        // 不会再来回调，存下来的坐标滚两下就过期了。命中的通常只有一两条。
        let best = null
        for (const [key, el] of visibleTurnEls) {
          const top = el.getBoundingClientRect().top
          if (!best || top < best.top) best = { key, top }
        }
        if (best) activeTurnKey.value = best.key
      }, { root: list, rootMargin: '-8% 0px 0px 0px', threshold: 0 })
      for (const el of list.querySelectorAll('[data-turn-key]')) turnObserver.observe(el)
    }
    // 轮次集合真的变了才重挂：chatTurns 每个 token 都会重算，跟着它重挂 200 个 observer
    // 就等于把「不在 scroll 里量 rect」这条纪律从另一头丢掉。
    const turnSignature = computed(() => {
      const turns = chatTurns.value
      return `${turns.length}|${turns[0]?.key || ''}|${turns.at(-1)?.key || ''}`
    })
    watch([turnSignature, messageList], () => nextTick(syncTurnObserver), { flush: 'post' })
    onBeforeUnmount(() => { turnObserver?.disconnect(); turnObserver = null })
    // 跳转必须复用 navigateToMessage：它和 isMessageOffscreen 共用同一个元素解析，
    // 自己 scrollTo 会变成「量一张卡、滚到另一张」。
    const handleTurnJump = ({ key, index }) => {
      if (!(index >= 0)) return
      activeTurnKey.value = key
      navigateToMessage({ index, target: 'turn' })
    }
    watch(currentConversationId, () => { followLatest.value = true })
    // 刷新后回到上次那段对话（dev-board#779 K7④）：会话 id 归本组件所有——新会话是
    // handleSubmit 现造的，工作台页那边的 currentConversationId 只在点历史时才更新，
    // 所以写在这里、读在工作台。清空（点了「新对话」）即抹掉记录。
    // 刻意不加 immediate：挂载那一刻 currentConversationId 还是 null，立刻回写会把
    // 工作台正要读的那条记录当场抹掉——恢复永远不会发生，而且一点报错都没有。
    watch(currentConversationId, (id) => saveLastConversation(uni, props.projectId, id))

    // Context Files (for drag-drop file context)
    const contextFiles = ref([])

    // 上一轮已经带走、本轮继续沿用的附件 id（dev-board#793 K14 ③）。
    //
    // 病灶（审查 E-2）：原来收到 receipt 就把本次带走的 contextFiles 从草稿里过滤掉、
    // 连输入框里的内联标签一起清空。于是「把这份合同发给 AI → 它答了 → 再问一句
    // 『第 8 条有没有问题』」这个最自然的两轮交互里，第二轮的 system prompt 里
    // 已经没有任何 <file> 段——模型既看不到原文，也不知道那份文件的 fileId。
    // Office 插件那一侧的 attachedFiles 本来就跨轮保留，两端行为分叉。
    //
    // 现在：附件留着（下一轮照样带上，这才是 E-2 要的），只是渲染成淡态让用户知道
    // 「这是上一轮带过的」，随时可以点 × 摘掉、或点标签本体确认沿用（回到常态）。
    const carriedFileIds = ref([])

    // Pasted Images (for paste/drop images)
    const pastedImages = ref([])
    // 发送时把粘贴图片上传成项目文件的那一小段窗口（此时 isStreaming 还是 false）
    const isUploadingPasted = ref(false)

    // 上下文层的各项上限（GET /api/ai/config 下发，长期原则 5「单一事实来源」）。
    // 拉不到时用与后端一致的兜底值——前端写死一份就是第二处事实来源。
    const contextLimits = ref({ ...DEFAULT_CONTEXT_LIMITS })

    // 当前文档 chip 是否被用户摘掉（dev-board#793 K14 ①，审查 E-8）。
    // **只活在组件里、不持久化**：摘除是「这一轮别带」的意思，不是一项设置。
    // 换会话/换项目时跟着组件状态一起复位。
    const activeDocDismissed = ref(false)
    watch(() => props.activeTab && props.activeTab.id, () => { activeDocDismissed.value = false })
    // 换项目：附件草稿里的 fileId 属于上一个项目，带过去后端 ToolFileGuard 必拒
    watch(() => props.projectId, () => { clearAttachmentDraft() })

    /**
     * 输入框上方那枚「当前文档 · <名称>」chip 的数据（null = 不显示）。
     *
     * 合格性判据复用 activeTabContext.js 的 isContextEligibleTab（#914 K8）：
     * 浏览器标签、AI 计划 artifact、设置页这些虚拟标签不是文档，带给后端只会让
     * read_document 抛 NumberFormatException，异常文案被当成正文注进 <active_document>。
     */
    const activeDocChip = computed(() => {
      if (activeDocDismissed.value) return null
      const tab = props.activeTab
      if (!isContextEligibleTab(tab)) return null
      return { id: String(tab.id), name: tab.name || '' }
    })

    const dismissActiveDoc = () => { activeDocDismissed.value = true }

    /**
     * 一条 context_notice 的人话（dev-board#801 K21 ⑦）。
     *
     * 六种 kind 对用户是六句不同的话，混成一句「未能处理」等于什么都没说：
     * 该换模型的、该少贴几张的、该压缩图片的、该删掉几份材料的，处置完全不同。
     * 认不出的 kind 回退成一句通用说明——后端将来加新 kind 时，
     * 老前端也不该把它整条吞掉（那就又变回静默降级了）。
     */
    const contextNoticeText = (n) => {
      const name = n && n.name ? n.name : t('chat.contextNoticeThisFile')
      switch (n && n.kind) {
        case 'truncated': return t('chat.contextNoticeTruncated', { name, chars: n.detail || '' })
        case 'dropped': return t('chat.contextNoticeDropped', { name, max: n.detail || '' })
        case 'ocr_fallback': return t('chat.contextNoticeOcrFallback', { name })
        case 'image_limit': return t('chat.contextNoticeImageLimit', { name, max: n.detail || '' })
        case 'image_too_large': return t('chat.contextNoticeImageTooLarge', { name })
        case 'unreadable': return t('chat.contextNoticeUnreadable', { name })
        // 本轮材料正文的合计额度用完了（dev-board#812 K32 ⑦）。刻意不并进 'dropped'：
        // 那句话说的是「一轮最多带 N 份材料」、detail 是份数，而这里的 detail 是字数，
        // 套进去会渲染成「一轮最多带 120000 份材料」。
        case 'budget_exhausted': return t('chat.contextNoticeBudgetExhausted', { name, max: n.detail || '' })
        // 整轮的处境而不是某一份材料：上下文超出模型窗口且压不动了（K32 ⑥）。
        // 它不带 name，所以文案里不出现文件名。
        case 'overflow': return t('chat.contextNoticeOverflow')
        default: return t('chat.contextNoticeGeneric', { name })
      }
    }

    // Model Selection
    const showModelDropdown = ref(false)
    // 模型清单唯一来源是后端 GET /api/ai/models（后端 AllowedModels 白名单派生）。
    // 这里曾经硬编码过 8 条，是「三份互不同步的事实来源」之一：前端写的 id 一旦
    // 不在白名单里，工厂会静默回落成默认模型——用户以为在用贵模型，实际不是。
    const availableModels = ref([])
    const defaultModelId = ref('')
    // 网络区域判定依据（后端本机 JVM 信号判的，不是官网回传、也不是 navigator.language）：
    // 境内清单里不含国际档模型，这句人读的判据用来解释「国际模型为什么不见了」
    const networkRegionBasis = ref('')
    // 价格显示口径（dev-board#853）：实付价（平台通道，站点币种）还是厂商美元标价，
    // 由后端按供应商与官网扣费汇率决定，前端只负责写出来（见 ModelSelectorDropdown）
    const modelPriceDisplay = ref(null)

    const currentModelId = ref('')
    const currentModelName = ref(t('chat.selectModel'))

    // 模型选择必须持久化：AI 面板挂在 v-if 上，关掉右栏再打开组件会重建，
    // 不落盘就会静默复位成清单第一条——这是有计费含义的选择，不能悄悄改。
    const MODEL_STORAGE_KEY = 'ai_selected_model'

    const readPersistedModelId = () => {
      try {
        const v = uni.getStorageSync(MODEL_STORAGE_KEY)
        // uni 的 storage 会按写入类型还原，非字符串一律视为脏数据丢弃
        return typeof v === 'string' ? v.trim() : ''
      } catch (e) {
        console.warn('[ChatInterface] 读取模型选择失败:', e)
        return ''
      }
    }

    const persistModelId = (id) => {
      try {
        uni.setStorageSync(MODEL_STORAGE_KEY, id || '')
      } catch (e) {
        console.warn('[ChatInterface] 保存模型选择失败:', e)
      }
    }

    // 按厂商分组；region=INTERNATIONAL 的组排在后面并标注「需国际网络」
    const modelGroups = computed(() => {
      const groups = []
      const index = new Map()
      for (const m of availableModels.value) {
        const key = `${m.region}|${m.vendor}`
        let g = index.get(key)
        if (!g) {
          g = { key, vendor: m.vendor || t('chat.vendorOther'), region: m.region, models: [] }
          index.set(key, g)
          groups.push(g)
        }
        g.models.push(m)
      }
      // 组内顺序保持后端下发顺序（白名单里已按国内在前、同厂商相邻排好）
      return groups.sort((a, b) => (a.region === 'INTERNATIONAL' ? 1 : 0) - (b.region === 'INTERNATIONAL' ? 1 : 0))
    })

    const applyModelSelection = (id) => {
      const hit = availableModels.value.find(m => m.id === id)
      currentModelId.value = hit ? hit.id : (id || '')
      currentModelName.value = hit ? hit.name : (id || t('chat.selectModel'))
    }

    // 当前模型能不能直接读图。**三态**：true 支持 / false 不支持 / null 未知。
    // 「未知」不许并到 false：拉不到模型目录时 availableModels 是空数组而 currentModelId
    // 还留着上次的值，applyModelSelection 也允许选中清单外的旧 id——把 undefined 当不支持，
    // 就会在这两种情况下对所有模型误报「不支持读图」。未知一律不提示。
    const currentModelVision = computed(() => {
      const hit = availableModels.value.find(m => m.id === currentModelId.value)
      if (!hit || typeof hit.vision !== 'boolean') return null
      return hit.vision
    })

    /**
     * 「当前模型看不了图」的常驻提示文案键（空串 = 不提示）。
     *
     * 判据在 chatContextLimits.js，与 Office 插件的 visionNotice 同一套：
     * **只要附件里有图就恒提示**。原来这条提示嵌在 `v-if="pastedImages.length > 0"`
     * 的缩略图块里，从文件树拖进来的项目图片（走 contextFiles）完全不触发——
     * 用户把一张现场照片拖进对话、模型读不了图时界面上没有任何线索，
     * 而模型收到的是 OCR 转写文本，回答里的数字可能是识别错的（审查 E-6）。
     */
    const visionNotice = computed(() => visionNoticeKey({
      modelVision: currentModelVision.value,
      pastedImages: pastedImages.value,
      contextFiles: contextFiles.value,
    }))

    // 选中读不了图的模型时说一声：降级是后端自动做的，不说用户会以为模型看到了图
    const noticeIfNoVision = (m) => {
      if (!m || m.vision !== false) return
      uni.showToast({ title: t('chat.modelNoVisionToast'), icon: 'none', duration: 3000 })
    }

    const selectModel = (m) => {
      console.log('Switching model to:', m.name)
      applyModelSelection(m.id)
      persistModelId(m.id)
      showModelDropdown.value = false
      // 只提示不换模型：静默改用户的计价对象是这个面板治理过一轮的老毛病
      noticeIfNoVision(m)
    }

    const loadModelCatalog = async () => {
      try {
        const res = await fetchAiModels()
        const list = Array.isArray(res?.models) ? res.models : []
        availableModels.value = list
        defaultModelId.value = res?.defaultModel || ''
        networkRegionBasis.value = res?.networkRegionBasis || ''
        modelPriceDisplay.value = res?.priceDisplay || null

        if (!list.length) {
          // 清单为空只有配置异常一种可能，此时不要伪造一个 id 发出去
          applyModelSelection('')
          return
        }

        // 默认模型取端点回的 defaultModel（DB 的 ai.defaultModel 优先于 yml），
        // 不能自己取清单第一条：那会与后端实际发出去的模型不一致
        const fallbackId = list.some(m => m.id === defaultModelId.value)
          ? defaultModelId.value
          : list[0].id

        const saved = readPersistedModelId()
        if (saved && list.some(m => m.id === saved)) {
          applyModelSelection(saved)
          return
        }

        applyModelSelection(fallbackId)
        persistModelId(fallbackId)
        if (saved) {
          // 存过的模型已不在可用集合（被移出白名单，或换了网络区域后拿不到国际档）：
          // 换了模型就必须说一声，静默改计价对象是这次要修的老毛病
          uni.showToast({
            title: t('chat.modelUnavailableSwitch', { name: currentModelName.value }),
            icon: 'none',
            duration: 3000
          })
        } else {
          // 用户从没手动选过，默认模型是自动落到他头上的——今天的默认档恰好读不了图，
          // 「不支持看图」是常态而不是边缘情况，第一次落定就得说清楚。
          // 与上面那条互斥：两条 toast 叠在一起，后一条会顶掉前一条。
          noticeIfNoVision(list.find(m => m.id === fallbackId))
        }
      } catch (e) {
        // 拉不到目录不该让面板不可用：保留上次选择（可能为空），由发送时的后端校验兜底
        console.warn('[ChatInterface] 加载模型目录失败:', e)
        const saved = readPersistedModelId()
        if (saved && !currentModelId.value) applyModelSelection(saved)
      }
    }

    // Agent Mode Selection (Ask, Plan, Agent)
    const showModeDropdown = ref(false)
    const ALL_MODES = [
      { id: 'AGENT', name: 'Agent', icon: '', desc: t('chat.modeAgentDesc') },
      { id: 'ASK', name: 'Ask', icon: '', desc: t('chat.modeAskDesc') },
      { id: 'PLAN', name: 'Plan', icon: '', desc: t('chat.modePlanDesc') }
    ]
    // 当前供应商（GET /api/ai/config 的 activeProvider）：模型目录端点不回 provider，
    // 而模式可选范围是按供应商定的，只能另取这个信号
    const activeProvider = ref('')
    // 本地 Ollama 只支持 ASK：langchain4j 0.36 的 OllamaStreamingChatModel 没有三参
    // generate，选 AGENT/PLAN 会在流式过程中抛英文异常，不如在选择器里就不给
    const isLocalOnlyProvider = computed(() => String(activeProvider.value).toUpperCase() === 'OLLAMA')
    const availableModes = computed(() =>
      isLocalOnlyProvider.value ? ALL_MODES.filter(m => m.id === 'ASK') : ALL_MODES
    )
    const localModeNotice = computed(() =>
      isLocalOnlyProvider.value ? t('chat.localModeNotice') : ''
    )

    const currentModeId = ref(ALL_MODES[0].id)
    const currentModeName = ref(ALL_MODES[0].name)
    const currentModeIcon = ref(ALL_MODES[0].icon)

    const selectMode = (mode) => {
      console.log('Switching agent mode to:', mode.name)
      currentModeId.value = mode.id
      currentModeName.value = mode.name
      currentModeIcon.value = mode.icon
      showModeDropdown.value = false
    }

    const loadAiProvider = async () => {
      try {
        const res = await getAiConfig()
        activeProvider.value = res?.activeProvider || ''
        // 上下文上限随同一条配置下发（dev-board#801 K21 ⑧）：前端拦截用的数字
        // 与后端真正执行的必须是同一份，各写一份的表现是「界面说还能加、后端已经在丢」
        contextLimits.value = normalizeContextLimits(res?.contextLimits)
      } catch (e) {
        // 取不到供应商时按云端处理（不缩减模式），避免误把云端用户锁成只能 Ask
        console.warn('[ChatInterface] 加载 AI 供应商配置失败:', e)
        activeProvider.value = ''
        contextLimits.value = { ...DEFAULT_CONTEXT_LIMITS }
      }
      // 供应商是本地档时把当前模式收回 ASK：默认值是 AGENT，不收就会一发即报错
      if (isLocalOnlyProvider.value && currentModeId.value !== 'ASK') {
        selectMode(ALL_MODES.find(m => m.id === 'ASK'))
      }
    }

    const toggleModeDropdown = () => {
      showModeDropdown.value = !showModeDropdown.value
      // 关闭其他下拉菜单
      if (showModeDropdown.value) {
        showModelDropdown.value = false
        showSkillDropdown.value = false
      }
    }

    // ---- Skill：本轮生效清单 + 主动选择 ----
    // 两个来源刻意分开：
    // - 手动选的（selectedSkillIds）是本地状态，勾上立刻可见、可以 × 掉，不必等发完消息；
    // - 自动命中的（activeSkills 里 source==='auto'）只能由后端在轮次开始时告诉我们，
    //   前端没有触发词表也不该有第二份（那是又一份会漂移的副本）。
    // 后端 skill_update 里的 manual 条目只是回执，渲染仍以本地选择为准——否则第一条消息发出去
    // 之前，用户勾了却什么都看不见。
    const showSkillDropdown = ref(false)
    const availableSkills = ref([])
    const selectedSkillIds = ref([])

    // ASK 模式下 skill 整体不生效（不传工具、也不注入指引），选择器禁用并给出说明，
    // 而不是让用户勾一堆东西然后什么都不发生。
    const skillDisabledByMode = computed(() => currentModeId.value === 'ASK')

    // 英文界面优先 name_en：/api/skills/list 不做语言过滤，展示名要自己按语言挑
    const skillDisplayName = (s) => {
      if (!s) return ''
      return (getAppLanguage() === 'en-US' && s.nameEn) || s.name || s.id
    }

    const selectedSkills = computed(() =>
      selectedSkillIds.value
        .map(id => availableSkills.value.find(s => s.id === id) || { id, name: id })
        .map(s => ({ id: s.id, name: skillDisplayName(s), source: 'manual', justActivated: false }))
    )
    // 自动命中的技能：手动已选的不重复出条（后端也会把重叠的那枚标成 manual）
    const autoSkills = computed(() =>
      (activeSkills.value || []).filter(
        s => s.source === 'auto' && !selectedSkillIds.value.includes(s.id)
      )
    )
    // chip 行：手动在前（可移除），自动在后（新出现的会闪一下）
    const skillChips = computed(() =>
      skillDisabledByMode.value ? [] : [...selectedSkills.value, ...autoSkills.value]
    )

    // 已安装 Skill 为 0 时不显示选择器，避免输入区堆无用控件。
    // available=false 的一律不列：那些在当前应用语言下永远不会生效，能勾但不生效比看不见更糟。
    const loadAvailableSkills = async () => {
      try {
        const res = await getSkills()
        const list = Array.isArray(res) ? res : (res?.data || [])
        availableSkills.value = list.filter(
          s => s.activationMode !== 'disabled' && s.enabled !== false && s.available !== false
        )
        // 列表变了（管理员停用/卸载）就把选不中的清掉，别留一个永远不生效的 chip
        selectedSkillIds.value = selectedSkillIds.value.filter(
          id => availableSkills.value.some(s => s.id === id)
        )
      } catch (e) {
        // Skill 列表拉取失败不该影响对话，静默降级为"无可选 Skill"
        console.warn('[ChatInterface] 加载 Skill 列表失败:', e)
        availableSkills.value = []
      }
    }

    // 每个 sendMessage 出口都要带上它。「继续」「按此推进」「点选项」都是同一件任务的后续轮次，
    // 漏带的话用户选的技能会在这些路径上静默掉线（旧的 pinnedSkillId 就只有主发送路径带）。
    const currentSkillIds = () => (skillDisabledByMode.value ? [] : [...selectedSkillIds.value])

    const toggleSkillSelection = (skillId) => {
      if (!skillId) return
      const idx = selectedSkillIds.value.indexOf(skillId)
      if (idx >= 0) selectedSkillIds.value.splice(idx, 1)
      else selectedSkillIds.value.push(skillId)
    }

    const removeSelectedSkill = (skillId) => {
      const idx = selectedSkillIds.value.indexOf(skillId)
      if (idx >= 0) selectedSkillIds.value.splice(idx, 1)
    }

    const toggleSkillDropdown = () => {
      showSkillDropdown.value = !showSkillDropdown.value
      if (showSkillDropdown.value) {
        showModelDropdown.value = false
        showModeDropdown.value = false
        loadAvailableSkills()
      }
    }

    // 自动命中新技能时给一句轻提示：用户只是说了句话就被加载了一个技能，
    // 不吭声就是黑箱（chip 上的闪现动画是同一件事的视觉表达）。
    watch(skillNotice, (n) => {
      if (!n) return
      try {
        if (typeof uni !== 'undefined' && uni.showToast) {
          uni.showToast({ title: t('chat.skillAutoLoadedToast', { name: n.name }), icon: 'none', duration: 2500 })
        }
      } catch (e) { /* toast 失败不影响对话 */ }
    })

    const goToSkillManagement = () => {
      showSkillDropdown.value = false
      uni.navigateTo({ url: '/pages/plugin-market/plugin-market' })
    }

    // Rollback Dialog State
    const showRollbackDialog = ref(false)
    const rollbackTargetIndex = ref(-1)
    const rollbackTargetContent = ref('')
    const rollbackTargetId = ref(null)
    // 这次确认框是「重新生成」而不是「回退」：只改结尾那一步（重发 vs 回填输入框）
    // 与三处文案，截断与存档完全共用（见 openRegenerateDialog）
    const rollbackResend = ref(false)

    // Upload Dialog State
    const showUploadDialog = ref(false)
    // 「+」对话框的两个页签：上传本机文件 / 从项目里挑一份已有的（dev-board#794 K15 ③）
    const uploadTab = ref('local') // 'local' | 'project'
    const projectPickQuery = ref('')
    const uploadSelectedFiles = ref([])
    const selectedUploadParent = ref(null)
    const isFolderUpload = ref(false)
    const showFolderSelector = ref(false)
    const tempSelectedParent = ref(null)
    const allProjectFiles = ref([])
    const isUploading = ref(false)
    const folderSelectorExpanded = ref({}) // Folder expand state for selector

    // Computed: Folder tree for selector (matching FileTree logic with expand/collapse)
    const folderTree = computed(() => {
      if (!Array.isArray(allProjectFiles.value) || allProjectFiles.value.length === 0) {
        return []
      }

      // 只取文件夹
      const folders = allProjectFiles.value.filter(f => f && f.isFolder)
      if (folders.length === 0) return []

      // 构建 id -> 节点 映射
      const nodeMap = new Map()
      folders.forEach(f => {
        nodeMap.set(String(f.id), {
          ...f,
          children: [],
          level: 0
        })
      })

      // 构建树结构
      const roots = []
      folders.forEach(f => {
        const node = nodeMap.get(String(f.id))
        const pId = node.parentId ? String(node.parentId) : null
        if (pId && nodeMap.has(pId)) {
          const parent = nodeMap.get(pId)
          parent.children.push(node)
        } else {
          roots.push(node)
        }
      })

      const result = []
      // 默认只展开根目录（即显示第一层级）
      const isRootExpanded = folderSelectorExpanded.value['root'] !== false

      if (isRootExpanded) {
        const traverse = (nodes, level) => {
          if (!Array.isArray(nodes)) return
          nodes
            .slice()
            .sort((a, b) => (a.name || '').localeCompare(b.name || '', 'zh-CN', { numeric: true }))
            .forEach(node => {
              node.level = level
              result.push(node)
              const hasChildren = node.children && node.children.length > 0
              // 一级及以下文件夹默认收起，必须显式标记为 true 才展示下级
              const expanded = folderSelectorExpanded.value[String(node.id)] === true
              if (hasChildren && expanded) {
                traverse(node.children, level + 1)
              }
            })
        }
        traverse(roots, 1)
      }
      return result
    })

    // Computed: Selected folder name (for backward compatibility)
    const selectedUploadParentName = computed(() => {
      if (selectedUploadParent.value === null) return t('chat.rootFolder')
      const folder = allProjectFiles.value.find(f => f.id === selectedUploadParent.value)
      return folder ? folder.name : t('chat.rootFolder')
    })

    // --- File Changes Logic ---
    const showModifiedPopup = ref(false)
    const showNewPopup = ref(false)

    const createdFiles = computed(() => {
        return (fileChanges.value || []).filter(f => f.changeType === 'ADDED')
    })

    const modifiedFiles = computed(() => {
        return (fileChanges.value || []).filter(f => f.changeType === 'MODIFIED')
    })

    const toggleModifiedPopup = () => {
        showModifiedPopup.value = !showModifiedPopup.value
        if (showModifiedPopup.value) showNewPopup.value = false
    }

    const toggleNewPopup = () => {
        showNewPopup.value = !showNewPopup.value
        if (showNewPopup.value) showModifiedPopup.value = false
    }

    // 「当前文档」占位（含历史会话里的 "Current Document"）按界面语言显示
    const fileChangeLabel = (f) => isCurrentDocSentinel(f && f.fileName)
        ? t('chat.activeDocChipLabel') : (f && f.fileName)

    const handleOpenFile = (f) => {
        // Emit open-file event to parent
        // f.fileName is the name. Backend might need full path if it's nested.
        // But for now we just emit what we have.
        // Assuming parent can handle opening by name or request details if needed.
        // Or send { name: f.fileName, path: f.fileName }
        console.log('Opening file:', f.fileName, f.fileId)
        // fileId（dev-board#852）一并抛出：父级按 id 优先找，找不到再按名字
        emit('open-file', { name: f.fileName, fileId: f.fileId ?? null })
        showModifiedPopup.value = false
        showNewPopup.value = false
    }

    // 模型目录与供应商在组件挂载时拉一次：面板挂在 v-if 上，每次打开都会重建，
    // 这也正是恢复持久化模型选择的时机
    onMounted(() => {
      loadModelCatalog()
      loadAiProvider()
      restoreRunningPluginJobs()
    })

    // 插件后台任务跨页面/跨重连仍在跑：SSE 只在进度变化时推一次，刷新页面或切回工作台的用户
    // 要等下一次 progress 才能看到它。挂载时按项目拉一次在跑的，接回浮窗。
    const restoreRunningPluginJobs = async () => {
      if (!props.projectId) return
      try {
        const list = await listPluginJobs(props.projectId)
        if (!Array.isArray(list)) return
        list.filter(j => j && (j.status === 'queued' || j.status === 'running')).forEach(upsertPluginJob)
      } catch (e) {
        console.warn('[ChatInterface] restore plugin jobs failed:', e)
      }
    }

    // Follow new messages only while the reader is already at the bottom.
    watch(() => bubbles.value.length, () => {
      if (followLatest.value) scrollToBottom()
    })

    // --- PPT Config Logic ---
    const showPptConfigDialog = ref(false)
    const pptConfigData = ref(null)
    const pptExportEditable = ref(false)

    const cancelPptConfig = () => {
       showPptConfigDialog.value = false
       pptConfigData.value = null
       // Optionally notify backend of cancellation? Not strictly needed as AI task handles timeout or just hangs.
       // Ideally we should tell user "Cancelled".
       // 形状必须与 useAgentStream.createAssistantBubble 一致：RootBubble 对
       // thinking.status / processes.length / artifacts.length 都是裸解引用，
       // 少字段就在渲染时抛 TypeError，Vue 3 把这条气泡换成空注释节点——
       // 用户根本看不到「已取消」。
       bubbles.value.push({
          role: 'ASSISTANT',
          thinking: { status: 'done', content: '', duration: 0 },
          title: '',
          planTodos: [],
          processes: [],
          artifacts: [],
          walkthrough: '',
          question: null,
          isStreaming: false,
          content: t('chat.pptCancelled'),
          timestamp: new Date().toLocaleTimeString()
       })
    }

    const confirmPptGeneration = async () => {
       if (!pptConfigData.value) return

       const params = {
          ...pptConfigData.value, // contains topic, projectId etc.
          exportEditable: pptExportEditable.value,
          conversationId: currentConversationId.value
       }

       // Close dialog immediately
       showPptConfigDialog.value = false

       try {
          // Call backend API
          await performPptGeneration(params)

          // Add a system bubble saying "Starting generation..."
          // 同上：字段少了这条提示会被 Vue 的渲染错误兜底吞成空节点。
          bubbles.value.push({
             role: 'ASSISTANT',
             thinking: { status: 'done', content: '', duration: 0 },
             title: '',
             planTodos: [],
             processes: [],
             artifacts: [],
             walkthrough: '',
             question: null,
             isStreaming: false,
             content: t('chat.pptStarting', { variant: pptExportEditable.value ? t('chat.pptVariantEditable') : t('chat.pptVariantImage') }),
             timestamp: new Date().toLocaleTimeString()
          })

       } catch (err) {
          console.error("Failed to start PPT generation:", err)
          uni.showToast({ title: t('chat.pptStartFailed'), icon: 'none' })
       }
    }

    // --- Rollback Functions ---
    /**
     * 「回退到这条消息」的定位键，拿不到就返回 null（按钮据此置灰）。
     *
     * 两种气泡两种键：从 GET /api/ai/history 回灌的有 dbMessageId（project_ai_message 主键），
     * 本次会话内发出的只有 clientRequestId——主键要等编排器在 turnExecutor 线程上落库才生成，
     * 而 POST /api/agent/chat 的回执早就发走了。气泡自己的 id 是前端自造的 msg-<毫秒>-<序号>，
     * 任何时候都不许拿它当定位键（那正是 D-02：请求在进 handler 之前就被 Jackson 拒掉）。
     */
    const rollbackLocator = (msg) => {
      if (!msg) return null
      const messageId = msg.dbMessageId == null ? null : String(msg.dbMessageId)
      const clientRequestId = msg.clientRequestId || null
      return (messageId || clientRequestId) ? { messageId, clientRequestId } : null
    }

    /**
     * 从此分叉（dev-board#779 K18）：非破坏，所以<b>不弹确认框</b>——原对话一个字不动，
     * 没有什么需要用户点头承担的后果。真正的动作在宿主：fork 出新会话再切过去
     * （复用插件镜像「另起分支继续」那条已有的切换路径）。
     */
    const branchFromMessage = (msg) => {
      if (isStreaming.value) {
        uni.showToast({ title: t('chat.waitCurrentChat'), icon: 'none' })
        return
      }
      const locator = rollbackLocator(msg)
      if (!locator) {
        uni.showToast({ title: t('chat.branchUnavailable'), icon: 'none' })
        return
      }
      if (!currentConversationId.value) return
      emit('fork-from-message', { conversationId: currentConversationId.value, ...locator })
    }

    const openRollbackDialog = (msg, index) => {
      if (isStreaming.value) {
        uni.showToast({ title: t('chat.waitCurrentChat'), icon: 'none' })
        return
      }
      const locator = rollbackLocator(msg)
      if (!locator) {
        uni.showToast({ title: t('chat.rollbackUnavailable'), icon: 'none' })
        return
      }
      rollbackTargetIndex.value = index
      // 预览与「回填到输入框重发」都用用户看到的那份（契约 D）：把回喂给模型的
      // 长文案塞回输入框，用户没法在上面继续编辑，只会一头雾水
      rollbackTargetContent.value = msg.displayContent || msg.content || ''
      rollbackTargetId.value = locator
      showRollbackDialog.value = true
    }

    /**
     * 「重新生成」：换一份回答，走的是和「回退到这条消息」<b>完全同一条链路</b>——
     * 同一个确认框、同一次 rollbackConversation（后端先把原路径整条存成一条存档会话再截断），
     * 唯一的区别在结尾：确认之后不是把原文回填输入框等用户改，而是原样重发一次（审查 D-07）。
     *
     * <p>刻意不另起一条「重放这一轮」的通道：那会变成第二份截断语义，
     * 而截断是会删用户数据的动作，两份实现迟早在存档这件事上漂移。
     *
     * @param assistantIndex 被点的那条助手气泡在 bubbles 里的下标
     */
    const openRegenerateDialog = (assistantIndex) => {
      if (isStreaming.value) {
        uni.showToast({ title: t('chat.waitCurrentChat'), icon: 'none' })
        return
      }
      // 往回找这条回答对应的提问。找不到（开场白、系统确认气泡）就不做——
      // 没有提问就没有「再问一次」可言。
      let userIndex = Number(assistantIndex)
      while (userIndex >= 0 && bubbles.value[userIndex] && bubbles.value[userIndex].role !== 'USER') userIndex--
      const target = userIndex >= 0 ? bubbles.value[userIndex] : null
      if (!target) {
        uni.showToast({ title: t('chat.regenerateNoSource'), icon: 'none' })
        return
      }
      openRollbackDialog(target, userIndex)
      // 只有对话框真开了才算数：openRollbackDialog 可能在流式中 / 定位不到时提前返回，
      // 那时把标志留成 true，下一次普通回退就会莫名其妙地自动重发。
      rollbackResend.value = showRollbackDialog.value
    }

    const cancelRollback = () => {
      showRollbackDialog.value = false
      rollbackTargetIndex.value = -1
      rollbackTargetContent.value = ''
      rollbackTargetId.value = null
      rollbackResend.value = false
    }

    const confirmRollback = async () => {
      const resendDecisionAssist = syncDecisionAssistPreference()
      const resendDecisionAssistOwner = decisionAssistOwner
      const targetIndex = rollbackTargetIndex.value
      const locator = rollbackTargetId.value
      const content = rollbackTargetContent.value
      // 「重新生成」与「回退」的唯一分叉点，先取下来：下面重置状态时它会被清掉
      const resend = rollbackResend.value
      // 这条气泡上的东西**必须在截断之前全部取下来**：rollbackToMessage 会把它摘掉。
      // prompt 取 content（模型当初读到的那份），displayText 取 displayContent，
      // 契约 D 的两条通道各归各位。
      const source = bubbles.value[targetIndex] || null
      const resendPrompt = resend && source ? (source.content || '') : ''
      const resendDisplay = resend && source ? (source.displayContent || '') : ''
      // 那一轮带过的材料（dev-board#793 K14 ④）。两条路都要它：
      // 回退要把 @附件标签 还原回输入框——只还原文字的话，用户改一个字重发材料就悄悄少了；
      // 重新生成要原样再带一次——同一个问题重问一次而材料没跟着走，模型当然给出不一样的答案，
      // 用户却以为这是「换一份回答」的正常波动。
      const rolledBackAttachments = fileListFromBubble(source)

      // 关闭对话框
      showRollbackDialog.value = false

      try {
        // 1. 后端：先把原路径整条存档（不可丢），再删掉目标及其之后的记录。
        //    两步在服务端同一个事务里——存档没成就不截断。
        let archived = null
        if (locator && currentConversationId.value) {
          const res = await rollbackConversation(currentConversationId.value, locator)
          archived = res && (res.archivedConversationId || (res.data && res.data.archivedConversationId))
        }

        // 2. 在前端删除bubbles（目标一起删——与后端同语义，用户接着在输入框里改了重发）
        const rolledBackContent = rollbackToMessage(targetIndex)

        // 3. 回退：把原文连同那一轮带过的附件标签放回输入框等用户改；
        //    重新生成：原样再问一次，不碰输入框（用户此刻可能已经在里面打了别的东西，
        //    覆盖掉就是丢他的字）。
        //    回填必须等重渲染落地：模板里有两个 ref="richInput" 的 contenteditable
        //    （空状态的欢迎输入框、有对话时的底部输入框）。回退到第一条时 bubbles 变空、
        //    两者互换，紧接着同步写 innerHTML 只会写进马上被销毁的那一个——
        //    表现是「回退了，但输入框是空的，原文没了」。
        await nextTick()
        if (!resend && richInput.value && content) {
          richInput.value.innerHTML = escapeHtml(content)
          inputPrompt.value = content
        }
        if (!resend) restoreAttachmentsToInput(rolledBackAttachments)

        // 4. 通知父组件刷新历史（存档会话要在「近期对话」里立刻看得见）
        emit('refresh-history')

        uni.showToast({
          title: resend
            ? t('chat.regenerateSending')
            : (archived ? t('chat.rollbackDoneArchived') : t('chat.rollbackDone')),
          icon: 'none'
        })

        // 5. 重新生成：重发原提问。放在最后——前面任何一步抛异常都不该再发出去
        //    （历史没截断就重发，等于同一个问题在库里问了两遍）。
        //    发的是 resendPrompt 而不是 content：content 是「用户看到的那份」
        //    （displayContent 优先，回填输入框用），而重发要给模型的是它当初读到的那份
        //    （契约 D）。点计划审批卡产生的那类消息两者差一整篇修订稿。
        if (resend && resendPrompt) {
          await sendMessage({
            prompt: resendPrompt,
            decisionAssistEnabled: resendDecisionAssist,
            decisionAssistOwner: resendDecisionAssistOwner,
            displayText: resendDisplay,
            // 原问带过的材料原样再带一次（dev-board#793 K14 ④）：传空数组的话
            // 「重新生成」就成了「换一个问题」——模型手上没有当初那几份材料
            fileList: rolledBackAttachments,
            projectId: props.projectId,
            modelId: currentModelId.value,
            mode: currentModeId.value,
            skillIds: currentSkillIds()
          })
          scrollToBottom()
        }
      } catch (err) {
        console.error('[ChatInterface] Rollback failed:', err)
        uni.showToast({ title: t('chat.rollbackFailed', { error: err.message || t('chat.unknownError') }), icon: 'none' })
      }

      // 重置状态
      rollbackTargetIndex.value = -1
      rollbackTargetContent.value = ''
      rollbackTargetId.value = null
      rollbackResend.value = false
    }

    /**
     * 清掉输入框里的附件草稿（dev-board#793 K14 ③）。
     *
     * **附件跨轮保留只在同一段对话里成立**：换会话/换项目时必须清干净。
     * 不清的话，上一段对话挂着的材料会跟着进下一段——换项目更糟，
     * 那个 fileId 属于别的项目，后端 ToolFileGuard 会拒，用户看到的是
     * 「该附件内容暂不可读」，而他压根不知道自己带了这份东西。
     */
    const clearAttachmentDraft = () => {
      contextFiles.value = []
      carriedFileIds.value = []
      pastedImages.value = []
      if (richInput.value) {
        richInput.value.querySelectorAll('[data-file-id]').forEach((el) => el.remove())
        inputPrompt.value = richInput.value.innerText
      }
    }

    const startNewChat = () => {
      // New conversation detaches this panel from the old SSE. The server run keeps working
      // and remains visible from history; Stop is the explicit cancellation action.
      setConversationId(null)  // This now triggers resetSSE internally
      clearBubbles()           // Use composable method
      selectedSkillIds.value = [] // 手动选的技能属于这一段对话，新会话从干净状态开始
      clearAttachmentDraft()   // 附件跨轮保留只在同一段对话里成立
      emit('new-chat')
    }

    const handleSubmit = async (requestedMode = 'steer') => {
      // 插件镜像会话只读（dev-board#298）：输入区已换成说明条，这里再拦一道
      // 兜住空态输入框等旁路（后端对镜像会话追加也会拒，这是省一次报错）
      if (props.externalReadOnly) return
      if (isUploadingPasted.value) return
      // Create a clone to safely manipulate and extract text without tags
      let text = ''
      let contentHtml = ''
      if (richInput.value) {
        // 1. First, capture the HTML with inline tags for display in bubble
        // Clone and sanitize for display, keeping file tags
        const displayClone = richInput.value.cloneNode(true)
        // Clean up contenteditable artifacts but keep file tags
        let rawHtml = displayClone.innerHTML
        // Replace <br> with <br/> for consistency
        rawHtml = rawHtml.replace(/<br\s*>/gi, '<br/>')
        // Replace <div> blocks with <br/> + content (preserve line breaks)
        rawHtml = rawHtml.replace(/<div[^>]*>/gi, '<br/>')
        rawHtml = rawHtml.replace(/<\/div>/gi, '')
        // Clean leading <br/> if starts with one
        rawHtml = rawHtml.replace(/^<br\/?>/, '')
        contentHtml = rawHtml.trim()

        // 2. Extract plain text (without tags) for sending to backend
        const textClone = richInput.value.cloneNode(true)
        // Remove file tags to avoid duplicating their name in the text
        const tags = textClone.querySelectorAll('[data-file-id]')
        tags.forEach(t => t.remove())

        // Manual Text Extraction to preserve newlines
        let html = textClone.innerHTML
        // Replace <br> with newline
        html = html.replace(/<br\s*\/?>/gi, '\n')
        // Replace <div> and <p> with newline (start of block)
        html = html.replace(/<(?:div|p)[^>]*>/gi, '\n')
        // Remove closing tags (implicit newline separation handled by start tags)
        html = html.replace(/<\/(?:div|p)>/gi, '')

        // Decode entities and strip remaining tags
        const temp = document.createElement('div')
        temp.innerHTML = html
        text = temp.textContent.trim()
      }

      const hasImages = pastedImages.value.length > 0
      const hasFiles = contextFiles.value.length > 0

      // 附件跨轮保留之后，「只剩上一轮带过的附件、没有任何新内容」也算空消息
      // （dev-board#793 K14 ③）：否则发完一轮之后误按一次回车，就会把同一批材料
      // 顶着一句空 prompt 再发一遍——白烧一轮钱，用户还不知道自己按了什么。
      const onlyCarriedAttachments = hasFiles && !hasImages
        && contextFiles.value.every((f) => carriedFileIds.value.includes(String(f.id)))

      // 禁止发送纯空消息：必须有文本、图片或文件上下文至少其一
      if (!text && !hasImages && (!hasFiles || onlyCarriedAttachments)) {
        if (isStreaming.value) {
          // 如果正在流式传输，允许中断操作
          return
        }
        // 显示提示
        if (typeof uni !== 'undefined') {
          uni.showToast({ title: t('chat.emptyMessageToast'), icon: 'none' })
        }
        return
      }

      // 只有图片、没有文字：图片本身现在会随消息真的发出去（模型支持读图就直送、
      // 不支持则降级 OCR），但 prompt 是空串——用户看着自己的图片气泡等回答，
      // 模型收到的是一条没说要做什么的空消息。先问清楚要干嘛。
      if (!text && hasImages && typeof uni !== 'undefined') {
        uni.showModal({
          title: t('chat.imageNeedsCaptionTitle'),
          content: t('chat.imageNeedsCaptionContent'),
          showCancel: false,
          confirmText: t('chat.gotIt')
        })
        return
      }

      const prompt = text
      const submissionMode = isStreaming.value && requestedMode === 'queue' ? 'queue' : 'steer'
      const editorHtml = richInput.value ? richInput.value.innerHTML : ''
      const selectedSkillSnapshot = currentSkillIds()
      const decisionAssistSnapshot = syncDecisionAssistPreference()
      const decisionAssistOwnerSnapshot = decisionAssistOwner
      const conversationId = currentConversationId.value || `conv-${Date.now()}-${Math.random().toString(36).slice(2)}`
      if (!currentConversationId.value) setConversationId(conversationId)

      // 先定住本次要带走的那几张，再去上传：上传要走网络，其间用户还可能继续粘贴，
      // 拿 pastedImages 的实时值会一边漏掉新贴的、一边把它顺手清掉。
      const pastedBatch = pastedImages.value.slice()

      // 发消息之前先把当前文档落盘（dev-board#793 K14 ⑤，审查 E-9）。
      //
      // 病灶：桌面端只上送 activeContext 的 id/name，后端回落到 read_document 去读
      // **磁盘上已保存的那一版**；而 LOWA 的自动保存是防抖的（最长 2.5 秒 + 一次导出上传）。
      // 用户敲完一段话立刻回车问「我刚改的这段有没有问题」，模型看到的是改动之前的版本，
      // 而末位提醒还斩钉截铁地说「其正文已内联注入…可直接阅读分析」。
      //
      // 超时 1.5 秒：这一步串在用户按下回车到消息发出之间，等不起 10 秒。
      // **失败不阻断发送**——落不了盘也要把消息发出去，只是活跃文档降级成「只带壳」，
      // 让模型用 doc_get_document_text 走编辑器桥拿实时正文（那条路读的是内存里的当前状态）。
      const chipTab = activeDocChip.value
      let activeDocFlushed = true
      if (chipTab && typeof props.flushActiveDocument === 'function') {
        try {
          activeDocFlushed = await props.flushActiveDocument(chipTab.id, { timeoutMs: 1500 }) !== false
        } catch (e) {
          console.warn('[ChatInterface] flush active document failed', e)
          activeDocFlushed = false
        }
      }

      // 互斥已去除（审查 E-4）：挂了附件也照样带上当前文档。
      // 原来的判据是 `(!hasFiles && !hasImages && props.activeTab)`——只要有任何附件，
      // 活跃文档就是 null，后端整个 # Active Document 段与末位 [系统提醒] 都不生成，
      // 「对照这份对方发来的 docx 改一下当前文档第 3 条」这类跨材料工作流整条被切断。
      // 有附件时后端只注入 id/name + readHint 不注入正文（控 token），判据在后端一处。
      //
      // chip 被用户摘掉时 activeDocChip 为 null，本轮就真的不带——
      // 「帮我查一下最新的司法解释」这类与文档无关的提问不该每轮都拖着几万字的合同。
      const activeContext = chipTab ? {
        id: chipTab.id,
        name: chipTab.name,
        fileType: props.activeTab.fileType,
        wpsFileId: props.activeTab.wpsFileId,
        pane: props.activeTabPane,
        // 没能落盘：告诉后端别用磁盘上那份旧正文，改走 readHint 分支
        staleBody: !activeDocFlushed
      } : null
      const attempt = beginChatSubmission(submissionTracker, {
        prompt,
        contentHtml,
        editorHtml,
        fileIds: contextFiles.value.map((file) => file.id),
        imageKeys: pastedBatch.map((image, index) => image.path || `image-${index}`),
        submissionMode,
        conversationId,
        projectId: props.projectId,
        modelId: currentModelId.value,
        mode: currentModeId.value,
        skillIds: selectedSkillSnapshot,
        decisionAssistEnabled: decisionAssistSnapshot,
        decisionAssistOwner: decisionAssistOwnerSnapshot,
        activeContext,
      }, () => {
        try { return crypto.randomUUID() } catch (e) { return `chat-${Date.now()}-${Math.random().toString(36).slice(2)}` }
      })
      let pastedFileList = Array.isArray(attempt.pastedFileList) ? attempt.pastedFileList : []
      if (pastedBatch.length && !attempt.pastedPrepared) {
        // 上传这段时间里 isStreaming 还是 false、输入框也还没清空，再按一次回车
        // 会把同一批图重复上传、同一条消息发两遍——自己上一道闩。
        // 上传结束到 sendMessage 之间只有同步代码，而 sendMessage 是同步置起
        // isStreaming 的，所以这道闩到这里就可以撤。
        isUploadingPasted.value = true
        try {
          const uploaded = await uploadPastedImages(pastedBatch)
          pastedFileList = uploaded.files
          if (uploaded.failed > 0) {
            // 上传失败的不并入附件，这条提示是用户唯一能知道「模型没收到图」的地方
            uni.showToast({
              title: t('chat.pastedImageUploadFailed', { count: uploaded.failed }),
              icon: 'none',
              duration: 3000
            })
          }
        } finally {
          isUploadingPasted.value = false
        }
        attempt.pastedFileList = pastedFileList
        attempt.pastedPrepared = true
      }

      // Use context files as fileList；粘贴的图片走同一条 contextItems 通道
      const fileListToSend = contextFiles.value.map(f => ({
        id: f.id,  // useAgentStream.js uses f.id to extract fileIds
        fileName: f.name,
        fileType: f.fileType,
        wpsFileId: f.wpsFileId,
        isDir: f.isDir
      })).concat(pastedFileList.map(f => ({
        id: f.id,
        fileName: f.name,
        fileType: f.fileType,
        wpsFileId: f.wpsFileId,
        isDir: false
      })))

      // Save images and context files for user bubble display
      const imagesToShow = pastedBatch.map(img => ({ path: img.path }))
      // 挂到用户气泡上的**完整**附件记录（dev-board#793 K14 ④）。
      // 原来只存 {id,name,isDir} 的精简副本，于是「重新生成」（K11）只能传 fileList: []——
      // 同一个问题重问一次、材料却没跟着走，模型当然给出不一样的答案。
      const contextFilesToShow = contextFiles.value.map(attachmentRecord).filter(Boolean)

      if (activeContext) {
        console.log('[ChatInterface] Auto-attaching active context:', activeContext.name)
      }

      const receipt = await submitChatAttempt(attempt, () => sendMessage({
        prompt,
        contentHtml, // Pass HTML with inline tags for bubble display
        fileList: fileListToSend,
        projectId: props.projectId,
        modelId: attempt.modelId,
        mode: attempt.mode, // Agent 模式: ASK, PLAN, AGENT
        activeContext: attempt.activeContext, // NEW: Auto-detected active tab context
        // ASK 模式下 skill 不生效，一律不带——省得后端与面板的状态各说各话
        skillIds: attempt.skillIds,
        decisionAssistEnabled: attempt.decisionAssistEnabled,
        decisionAssistOwner: attempt.decisionAssistOwner,
        submissionMode,
        clientRequestId: attempt.clientRequestId,
        // Pass for user bubble display
        _userImages: imagesToShow,
        _userContextFiles: contextFilesToShow
      }))

      if (!receiptChatSubmission(submissionTracker, attempt, receipt)) {
        failChatSubmission(submissionTracker, attempt)
        uni.showToast({ title: t('chat.sendFailedDraftKept'), icon: 'none' })
        return
      }

      // Receipt is the durability boundary. Only clear the exact draft and attachments that
      // produced it; text/images added while the request was in flight stay for the next send.
      const currentDraft = {
        prompt,
        contentHtml,
        editorHtml: richInput.value ? richInput.value.innerHTML : '',
        fileIds: contextFiles.value.map((file) => file.id),
        imageKeys: pastedImages.value.map((image, index) => image.path || `image-${index}`),
        submissionMode: attempt.submissionMode,
        conversationId: currentConversationId.value,
        projectId: props.projectId,
        modelId: currentModelId.value,
        mode: currentModeId.value,
        skillIds: currentSkillIds(),
        activeContext: attempt.activeContext,
        decisionAssistEnabled: syncDecisionAssistPreference(),
        decisionAssistOwner,
      }
      const draftUnchanged = shouldClearChatDraft(attempt, currentDraft)
      if (draftUnchanged) {
        inputPrompt.value = ''
        // 草稿没了，挂在草稿上的三个瞬态也一并复位（K15/K16）
        closeMention()
        disarmEsc()
        historyRecall.value = { index: -1, text: '' }
        // 附件不在这里清（K14 附件跨轮保留，见下方注释）。
        // 粘贴图发后即清：它们已经被上传成项目文件，要继续用就从文件树拖回来
        // （contextFiles 那条路），把 base64 缩略图一直挂在输入框里既占内存又没有摘除入口
        pastedImages.value = pastedImages.value.filter((image) => !pastedBatch.includes(image))
      }

      // 附件跨轮保留（dev-board#793 K14 ③，审查 E-2）：清文字，**不清附件**。
      //
      // 「把这份合同发给 AI → 它答了 → 再问一句『第 8 条有没有问题』」是最自然的两轮交互，
      // 而原来收到 receipt 就把本轮 contextFiles 过滤掉、连输入框里的内联标签一起清空，
      // 第二轮的 system prompt 里一个 <file> 段都没有——模型既看不到原文、
      // 也不知道那份文件的 fileId（那个 id 只出现在上一轮的 system prompt 里）。
      // Office 插件那一侧本来就跨轮保留，两端行为分叉。
      //
      // **必须挂在 draftUnchanged 外面**：首条消息发出去时输入卡片整块被 v-if 换掉
      //（空状态 ⇄ 有对话），editorHtml 已经变成空串、指纹必然不匹配。挂在里面的话，
      // 表现就是「第一条之后附件全没了，第二条之后才正常」（真机实测到的形态）。
      await restoreCarriedTags(contextFilesToShow, { clearText: draftUnchanged })

      scrollToBottom()
    }

    // ---- 长任务可控（停止本轮 / 停止单个后台任务）----
    // 硬规则：文案一律「正在停止」，不许写「已停止」。后端的取消是
    // future.cancel(true) + 簿记，打不断已经发出去的 HTTP 读——在途的 LLM 调用会跑完，
    // 交给 pptx-service 的活儿也会跑完并落盘。说「已停止」就是骗人。
    const stoppingTasks = ref({}) // taskId -> true（按钮进入「正在停止…」）

    // 停止本轮生成：仍走既有 abort（POST /api/agent/cancel/{cid} + 断前端连接），
    // 这里只补一句诚实的提示。慢工具（dispatch_subtask 能跑 630 秒、AI PPT 十几分钟）
    // 中间的取消响应点已由编排器在每个工具前检查 isCancelled 提供。
    // 返回 abort 的 promise：菜单栏那条「停止当前任务」要等它真发完取消请求再去收
    // 后台任务，两个入口必须是同一条路（menuStop 复用本函数）。
    const handleAbort = () => {
      uni.showToast({ title: t('chat.abortToast'), icon: 'none' })
      return abort()
    }

    const toggleFollowUpMode = () => {
      followUpMode.value = followUpMode.value === 'steer' ? 'queue' : 'steer'
      try { uni.setStorageSync('awd_agent_follow_up_mode', followUpMode.value) } catch (e) { /* ignore */ }
    }

    const inboxAction = async (action) => {
      try {
        await action()
      } catch (e) {
        uni.showToast({ title: e.message || t('chat.inboxUpdateFailed'), icon: 'none' })
      }
    }
    const handleInboxEdit = ({ item, message }) => inboxAction(() =>
      updateInbox(item.id, { message, expectedRevision: item.revision }))
    const handleInboxDelete = (item) => inboxAction(() => deleteInbox(item.id, item.revision))
    const handleInboxMove = ({ item, position }) => inboxAction(() =>
      updateInbox(item.id, { position, expectedRevision: item.revision }))
    const handleInboxSendNow = (item) => inboxAction(() =>
      updateInbox(item.id, { submissionMode: 'steer', expectedRevision: item.revision }))

    /**
     * 用户气泡下那行送达状态（dev-board#779 K7②）。
     *
     * 判据只有 receiptState / submissionMode 这两个既有字段，不另起一份状态机。
     * 只有「曾经排过队」的插话在被读取后才报「已送达」：普通消息发出去就是 applied，
     * 每条下面都挂一行回执只是噪音。历史回灌出来的气泡没有 receiptState，自然无角标。
     */
    const receiptLabel = (msg) => {
      if (!msg || msg.role !== 'USER') return ''
      if (msg.receiptState === 'pending') {
        return msg.submissionMode === 'queue' ? t('chat.receiptPendingQueued') : t('chat.receiptPendingSteer')
      }
      return msg.wasPendingInbox && msg.receiptState === 'applied' ? t('chat.receiptApplied') : ''
    }

    // 待处理区与对话流的关联（dev-board#779 K7③）：正文留在对话流（那是阅读主场，
    // 这条被读取后待处理区就消失了，正文只放在那里等于一读即丢），待处理区退成引用行
    // ——它是操作台（编辑/排序/立即发送/删除），就在输入框上方，越矮越好。
    // 这里只告诉 AgentInbox 哪几条在流里已经有完整气泡了。
    const inboxStreamIds = computed(() => bubbles.value
      .filter((b) => b.role === 'USER' && b.inboxMessageId)
      .map((b) => b.inboxMessageId))
    const handleInboxLocate = (item) => {
      const index = bubbles.value.findIndex((b) => b.role === 'USER' && b.inboxMessageId === item.id)
      if (index < 0) return
      const row = navigateToMessage({ index })
      if (!row) return
      // 滚到位还不够：长会话里自己那条插话和上下文长得一样，不闪一下仍要自己找
      // （同 jumpToAttention 的手法，闪的类名不同是因为那套样式在 RootBubble 的
      // scoped style 里，这里闪的是 ChatInterface 自己渲染的 .message-row）。
      row.classList.add('chat-inbox-flash')
      setTimeout(() => row.classList.remove('chat-inbox-flash'), 1600)
    }

    // 只列还在跑的：已完成/失败的条目留在浮窗里供用户核对结果，控制条不该再给停止按钮
    const runningTasks = computed(() =>
      Object.values(backgroundTasks.value || {}).filter(t => t && t.status === 'running')
    )

    // 与 BackgroundTaskIndicator.getTaskTypeName 同一张表（两处都要改，取值来自后端 TaskInfo.TaskType）
    const taskTypeName = (type) => ({
      'PPTX_GENERATE': t('chat.taskPptGenerate'),
      'PPTX_MODIFY': t('chat.taskPptModify'),
      'FILE_PROCESS': t('chat.taskFileProcess'),
      'WEB_FETCH': t('chat.taskWebFetch'),
      'OTHER': t('chat.taskOther'),
      'PLUGIN_JOB': t('chat.taskPluginJob')
    })[type] || type || t('chat.taskBackgroundFallback')

    const handleCancelTask = async (task) => {
      if (!task || !task.taskId || stoppingTasks.value[task.taskId]) return
      // 插件后台任务（PluginJobService）按 jobId 取消，归属校验是项目成员，不走会话那条路
      if (task.type === 'PLUGIN_JOB') {
        stoppingTasks.value = { ...stoppingTasks.value, [task.taskId]: true }
        try {
          await cancelPluginJob(task.taskId)
          uni.showToast({ title: t('chat.stoppingTask'), icon: 'none' })
        } catch (e) {
          console.warn('[ChatInterface] 停止插件后台任务失败:', e)
          uni.showToast({ title: t('chat.stopNotEffective'), icon: 'none' })
          stoppingTasks.value = { ...stoppingTasks.value, [task.taskId]: false }
        }
        return
      }
      // conversationId 优先取任务自己带的：后台任务跨会话切换仍在跑，
      // 拿当前会话去停别的会话的任务会被后端 403 挡掉
      const cid = task.conversationId || currentConversationId.value
      if (!cid) return
      stoppingTasks.value = { ...stoppingTasks.value, [task.taskId]: true }
      try {
        await cancelBackgroundTask(cid, task.taskId)
        uni.showToast({ title: t('chat.stoppingTask'), icon: 'none' })
      } catch (e) {
        // 后端对「任务已经结束」返 404——那不是故障，只是按晚了；两种情况给同一句可读提示
        console.warn('[ChatInterface] 停止后台任务失败:', e)
        uni.showToast({ title: t('chat.stopNotEffective'), icon: 'none' })
        stoppingTasks.value = { ...stoppingTasks.value, [task.taskId]: false }
      }
    }

    // 续跑提示文案：区分「步数用完」和「上次进程被杀」两种中断来源
    const continueHint = computed(() => {
      return agentPaused.value && agentPaused.value.reason === 'process_interrupted'
        ? t('chat.continueHintInterrupted')
        : t('chat.continueHintPaused')
    })

    // 一键续跑（步数超限暂停 / 进程中断）：等价于用户输入「继续」（后端 depth 归零重新起循环），
    // 复用 sendMessage 的会话/模型/助手上下文。
    const handleContinue = async () => {
      if (isStreaming.value) return
      await sendMessage({
        prompt: t('chat.continuePrompt'),
        projectId: props.projectId,
        modelId: currentModelId.value,
        mode: currentModeId.value,
        skillIds: currentSkillIds()
      })
      scrollToBottom()
    }

    // --- History Loading Logic ---
    /**
     * 历史消息 → 气泡。首屏与「向上翻更早」两条路共用同一份映射：各写一份的话
     * 翻上去的那几轮会和首屏那几轮长得不一样（附件、回退键、时间戳都在这里补）。
     */
    const historyBubbles = (loadedMsgs) => {
       const built = []
       ;(loadedMsgs || []).forEach(msg => {
          const role = msg.role?.toUpperCase() || 'USER'

          if (role === 'USER') {
              built.push({
                  id: msg.id,
                  // 回退定位键：回灌的气泡有真正的主键，用它；clientRequestId 是本字段上线后
                  // 落库的行才有（存量行为 null），两者任给其一就够（见 rollbackLocator）
                  dbMessageId: msg.id,
                  clientRequestId: msg.clientRequestId || null,
                  role: 'USER',
                  content: msg.content,
                  // 契约 D：后端 GET /api/ai/history 带 displayContent（可空）。
                  // 渲染一律 displayContent || content——不带这个字段时与今天完全一致。
                  // 助手消息刻意不走这条回退：那边的 content 是协议 XML，要解析而不是直显，
                  // 而 displayContent 只会写在用户消息上。
                  displayContent: msg.displayContent || '',
                  // 消息 ↔ 附件持久关联（dev-board#793 K14 ④）：后端
                  // GET /api/ai/history 带 attachments（可空）。没有这个字段时是空数组，
                  // 与今天完全一致；有的话历史里就能看见「这一轮我发过哪几份材料」。
                  // 形状必须与 live 那条路一致（attachmentRecord），否则会变成
                  // 「刷新前能重新生成、刷新后不能」，而这种差别不会有任何东西报错
                  contextFiles: attachmentsFromHistory(msg.attachments),
                  // 降级提示刻意不回放：它说的是「那一轮发生的事」
                  contextNotices: [],
                  timestamp: formatTime(msg.createdAt)
              })
          } else {
              const bubble = parseAssistantHistory(msg.content || '')
              bubble.id = msg.id
              bubble.timestamp = formatTime(msg.createdAt)
              const recoveredTodos = recoverPlanTodos(bubble.processes)
              if (recoveredTodos !== null) {
                  bubble.planTodos = recoveredTodos
                  // 判据必须与 recoverPlanTodos 同源：各写一份时失败的 todo_write 也会命中，
                  // 而 findLastIndex 落空（-1）会把计划卡 splice 到整条时间线最前面
                  const planIndex = bubble.timeline.findLastIndex(entry => entry.type === 'process' && entry.data.items.some(isPlanSnapshotCall))
                  bubble.timeline.splice(planIndex < 0 ? bubble.timeline.length : planIndex + 1, 0, { type: 'plan', data: recoveredTodos })
              }
              built.push(bubble)
          }
       })
       return built
    }

    // 问题卡的已回答判定：这条助手消息后面还有用户消息，说明那一问已经答过了。
    // 只写 answered（历史态徽标），不靠它控制可操作性——那条链仍是 RootBubble 的
    // isLatest（仅最新一条助手消息可操作）。两者一致：真正未答的那一问必然是末条。
    const markAnsweredQuestions = () => {
       let seenLaterUser = false
       for (let i = bubbles.value.length - 1; i >= 0; i--) {
          const b = bubbles.value[i]
          if (b.role === 'USER') { seenLaterUser = true; continue }
          if (b.question && seenLaterUser) b.question.answered = true
       }
    }

    // 历史分页（dev-board#811 K31，审查 C-12）。首屏只取最近一页，向上滚再补更早的。
    // hasMore 为假时这三个状态恒定，翻页那条链整条不参与——旧后端（不认 limit，回裸数组）
    // 与夹具直接传数组的调用一样落在这一档。
    // 首屏取多少条。60 ≈ 30 轮问答，桌面端一屏往上翻两三次的量；再大就退回「打开历史
    // 先卡一下」，再小则几乎每次打开都要立刻补一页。
    const HISTORY_PAGE_SIZE = 60
    const historyHasMore = ref(false)
    const historyBefore = ref(null)
    const historyLoadingOlder = ref(false)

    /**
     * @param loaded 整条会话的数组（旧调用形态、夹具、旧后端），或
     *   `{ messages, hasMore, nextBefore }` 信封（带 limit 请求时后端回的形状）
     */
    const loadMessages = (conversationId, loaded) => {
       const page = Array.isArray(loaded) ? { messages: loaded, hasMore: false, nextBefore: null } : (loaded || {})
       const loadedMsgs = page.messages || []
       console.log('[ChatInterface] Loading history...', loadedMsgs.length)
       setConversationId(conversationId)  // This triggers resetSSE internally
       clearBubbles()  // Clear existing using composable method
       selectedSkillIds.value = [] // 切会话即重置手动选择：技能是按轮携带的，不该跨会话粘住
       clearAttachmentDraft()      // 同理：上一段对话挂着的材料不该跟进这一段
       historyHasMore.value = !!page.hasMore
       historyBefore.value = page.nextBefore ?? null
       historyLoadingOlder.value = false

       bubbles.value.push(...historyBubbles(loadedMsgs))
       markAnsweredQuestions()

       // 后台续跑关键一步：切回会话时重连 SSE。后端 connect 会推 run_state
       // （运行中还会推 state_recovery 全量续流 + plan_update 恢复任务清单），
       // 已结束的会话则只是挂一条静默连接，不影响静态历史展示。
       reattachSSE(conversationId)

       scrollToBottom()
    }

    /**
     * 向上补一页更早的消息。
     *
     * **补完必须把阅读位置钉回原处**：prepend 会把内容整体往下推，不补偿的话用户每翻一页
     * 就被弹到一段完全不相干的对话上。先量高度，插完再把 scrollTop 加上长出来的那一截。
     */
    const loadOlderMessages = async () => {
       const conversationId = currentConversationId.value
       if (!historyHasMore.value || historyLoadingOlder.value || !conversationId) return
       historyLoadingOlder.value = true
       const list = messageList.value?.$el || messageList.value
       const heightBefore = list ? list.scrollHeight : 0
       try {
          const page = await getAiHistory({
             projectId: props.projectId,
             conversationId,
             limit: HISTORY_PAGE_SIZE,
             before: historyBefore.value
          })
          // 竞态防护：翻页途中切了会话，这一页就不是这条会话的了，插进去会串会话
          if (currentConversationId.value !== conversationId) return
          // 旧后端不认 limit，回的是整条会话的裸数组——那就是已经全在手上了，收起翻页
          if (Array.isArray(page)) {
             historyHasMore.value = false
             historyBefore.value = null
             return
          }
          const older = (page && page.messages) || []
          if (older.length) {
             bubbles.value.unshift(...historyBubbles(older))
             markAnsweredQuestions()
          }
          historyHasMore.value = !!(page && page.hasMore)
          historyBefore.value = (page && page.nextBefore) ?? historyBefore.value
          await nextTick()
          if (list) list.scrollTop += list.scrollHeight - heightBefore
       } catch (e) {
          console.error('[ChatInterface] load older history failed', e)
       } finally {
          historyLoadingOlder.value = false
       }
    }

    // 滚到顶就补上一页。判据用「离顶不到一屏」而不是 scrollTop===0：等撞到顶再拉，
    // 用户必然先看到一下空白。
    const handleHistoryScroll = (event) => {
       handleMessageScroll(event)
       if (!historyHasMore.value || historyLoadingOlder.value) return
       const list = messageList.value?.$el || messageList.value
       if (list && list.scrollTop < list.clientHeight) loadOlderMessages()
    }

    // 近期对话列表的状态点（数据字段由宿主 fetchChatHistory 映射）
    const recentDotClass = (h) => {
       if (!h) return ''
       if (h.runStatus === 'RUNNING') return 'dot-running'
       // AWAITING_INPUT（模型反问后等回答）与待审批同为「等用户」，共用黄点；
       // 文案上要分开（待回答 / 待审批），文案表在宿主 project-overview.convStatusLabel
       if (h.runStatus === 'PAUSED' || h.runStatus === 'AWAITING_APPROVAL'
           || h.runStatus === 'AWAITING_INPUT' || h.runStatus === 'INTERRUPTED') return 'dot-attention'
       if (h.runStatus === 'ERROR') return 'dot-error'
       if (h.unread) return 'dot-unread'
       return ''
    }

    const formatTime = (ts) => {
       if (!ts) return ''
       const d = new Date(ts)
       return `${d.getMonth()+1}/${d.getDate()} ${d.getHours()}:${d.getMinutes().toString().padStart(2,'0')}`
    }

    // Relative time format for recent history display
    const formatRelativeTime = (ts) => {
       if (!ts) return ''
       const now = new Date()
       const d = new Date(ts)
       const diffMs = now - d
       const diffMins = Math.floor(diffMs / (1000 * 60))
       const diffHours = Math.floor(diffMs / (1000 * 60 * 60))
       const diffDays = Math.floor(diffMs / (1000 * 60 * 60 * 24))

       if (diffMins < 1) return t('chat.justNow')
       if (diffMins < 60) return t('chat.minutesAgo', { n: diffMins })
       if (diffHours < 24) return t('chat.hoursAgo', { n: diffHours })
       if (diffDays < 7) return t('chat.daysAgo', { n: diffDays })
       return `${d.getMonth()+1}/${d.getDate()}`
    }

    // Clean title - strip XML tags like <thinking>, <process>, etc.
    const cleanTitle = (title) => {
       if (!title) return t('chat.newConversation')
       // Remove common XML tags
       let cleaned = title
         .replace(/<thinking>[\s\S]*?<\/thinking>/gi, '')
         .replace(/<process[^>]*>[\s\S]*?<\/process>/gi, '')
         .replace(/<step>[\s\S]*?<\/step>/gi, '')
         .replace(/<tool_code>[\s\S]*?<\/tool_code>/gi, '')
         .replace(/<tool_output>[\s\S]*?<\/tool_output>/gi, '')
         .replace(/<artifact[^>]*>[\s\S]*?<\/artifact>/gi, '')
         .replace(/<final>[\s\S]*?<\/final>/gi, '')
         // 反问块整体剥掉：不剥的话「以纯反问收尾」的那一轮会把问题正文
         // 当成会话标题（下面的兜底只摘标记、留内容）
         .replace(/<question[^>]*>[\s\S]*?<\/question>/gi, '')
         .replace(/<[^>]+>/g, '') // Remove any remaining tags
         .trim()
       return cleaned || t('chat.newConversation')
    }

    const handleRichInput = (e) => {
        inputPrompt.value = e.target.innerText

        // Sync inline tags with contextFiles ref
        // When user deletes a tag from the input, also remove it from contextFiles
        syncContextFilesWithInlineTags()

        // 用户自己改了字 → 退出「翻历史」态、撤掉待确认的 Esc、重算 `@` 引用（K15/K16）
        if (historyRecall.value.index >= 0 && inputPrompt.value !== historyRecall.value.text) {
          historyRecall.value = { index: -1, text: '' }
        }
        disarmEsc()
        detectMention()
    }

    const handleInputClick = (e) => {
      // Check if clicked the close button of a tag
      if (e.target.classList.contains('tag-close')) {
        const tag = e.target.closest('.context-tag-inline')
        if (tag) {
          const removedId = tag.getAttribute('data-file-id')
          tag.remove()
          if (removedId) {
            carriedFileIds.value = carriedFileIds.value.filter((x) => x !== String(removedId))
          }
          syncContextFilesWithInlineTags()
          // Update text model
          if (richInput.value) {
            inputPrompt.value = richInput.value.innerText
          }
        }
        return
      }
      // 点淡态标签本体 = 「是的，这份继续用」，回到常态（dev-board#793 K14 ③ 的「一键沿用」）。
      // 它本来就还在带着，这一下只是把「上一轮带过的」这层提示摘掉。
      const carriedTag = e.target.closest && e.target.closest('.context-tag-inline.is-carried')
      if (carriedTag) {
        confirmCarriedFile(carriedTag.getAttribute('data-file-id'))
      }
    }

    // --- Sync contextFiles with actual inline tags in the input ---
    const syncContextFilesWithInlineTags = () => {
      if (!richInput.value) return

      // Get all file IDs from inline tags currently in the input
      const inlineTagElements = richInput.value.querySelectorAll('[data-file-id]')
      const inlineTagIds = new Set()
      inlineTagElements.forEach(el => {
        const fileId = el.getAttribute('data-file-id')
        if (fileId) {
          inlineTagIds.add(fileId)
        }
      })

      // Remove any contextFiles that no longer have a corresponding inline tag
      contextFiles.value = contextFiles.value.filter(f => inlineTagIds.has(String(f.id)))
    }

    // --- Handle Paste (Images & Plain Text) ---
    const handlePaste = (e) => {
      // Always prevent default to stop rich text/HTML paste
      e.preventDefault()

      const clipboardData = e.clipboardData || (e.originalEvent && e.originalEvent.clipboardData)
      if (!clipboardData) return

      const items = clipboardData.items
      let hasProcessedImage = false

      // 1. Try to handle images from clipboard
      if (items) {
        for (let i = 0; i < items.length; i++) {
          if (items[i].type.indexOf('image') !== -1) {
            const file = items[i].getAsFile()
            if (file) {
              hasProcessedImage = true
              // 张数/体积上限在**贴进来的那一刻**就说（dev-board#801 K21 ⑧，审查 E-7）。
              // 原来这两种情况都是静默的：贴 6 张图，第 5、6 张在后端悄悄变成 OCR 文本；
              // 贴一张 12MB 的扫描件，模型拿到的「正文」是一句 [System: 文件超过大小限制]，
              // 却包在「以下正文由 OCR 从图片转写而来」的前言里。
              const verdict = admitPastedImage({
                size: file.size,
                pastedImages: pastedImages.value,
                contextFiles: contextFiles.value,
                limits: contextLimits.value,
              })
              if (!verdict.ok) {
                if (typeof uni !== 'undefined') {
                  uni.showToast({
                    title: verdict.reason === 'imageBytes'
                      ? t('chat.imageTooLargeToast', { size: formatBytes(verdict.max) })
                      : verdict.reason === 'imageCount'
                        ? t('chat.imageCountCapToast', { max: verdict.max })
                        : t('chat.contextFileCapReached', { max: verdict.max }),
                    icon: 'none',
                    duration: 3000,
                  })
                }
                continue
              }
              // 同步先占位、再异步补 path：path 只用来画缩略图，真正要发出去的是 file 这份 blob。
              // 原来整条 push 都压在 FileReader.onload 里，粘完立刻回车时 onload 还没触发，
              // 这张图就整个丢了——以前丢的只是一张缩略图，现在丢的是要发给模型的附件。
              pastedImages.value.push({ file: file, path: '' })
              // 必须取回数组里那个响应式代理：直接改 push 进去的原对象不会触发视图更新
              const entry = pastedImages.value[pastedImages.value.length - 1]
              const reader = new FileReader()
              reader.onload = (evt) => {
                entry.path = evt.target.result
              }
              reader.readAsDataURL(file)
            }
          }
        }
      }

      // 2. Handle Text (Insert as Plain Text)
      // Only insert text if we didn't just process an image, OR if there is text content
      // (sometimes image paste has no meaning text).
      // But usually we want to allow pasting text AND images if mixed?
      // Safe bet: if there is text data, insert it.
      const text = clipboardData.getData('text/plain')
      if (text) {
        document.execCommand('insertText', false, text)
      }
    }

    // --- Handle Enter Key ---
    const handleEnterKey = (e) => {
      // 输入法组合中按下的 Enter 是「上屏候选词」，不是「发送」。
      // 中文/日文/韩文输入时浏览器照样派发 keydown（isComposing=true，部分浏览器 keyCode=229），
      // 不挡住的话这一下会把还没上屏的拼音直接当成消息发出去——中文用户天天撞。
      // 编辑器侧（zetaOfficeImeOverlay / editor-main）早就为同一类问题做了 composing 闩，
      // 聊天输入框一直漏着。
      if (e.isComposing || e.keyCode === 229) return
      if (!e.shiftKey) {
        // Plain Enter -> Send
        e.preventDefault()
        handleSubmit(followUpMode.value)
      } else {
        // Shift+Enter -> New line (default behavior, do not prevent)
      }
    }

    // ================= `@` 引用选择器（dev-board#794 K15） =================
    // 输入框里敲 `@` 再接字符 → 弹项目文件浮层；选中后删掉 `@查询` 那一段、调 addFile
    // 插内联标签并同步 contextFiles。范围只到项目文件（记忆 / 参考来源另议）。
    const mentionOpen = ref(false)
    const mentionQuery = ref('')
    const mentionLoading = ref(false)
    const mentionPicker = ref(null)
    // `@查询` 在 DOM 里的位置。鼠标点选会把光标带走，所以必须提前记下来而不是选中时现取；
    // 浮层的行用 @mousedown.prevent 正是为了别让 contenteditable 先 blur。
    let mentionAnchor = null

    const projectFileIndexKey = ref('')
    const ensureProjectFileIndex = async (force = false) => {
      if (!props.projectId) return
      if (!force && projectFileIndexKey.value === String(props.projectId) && allProjectFiles.value.length) return
      mentionLoading.value = true
      try {
        await loadProjectFolders()
      } finally {
        mentionLoading.value = false
      }
    }

    // 项目文件与文件夹的扁平候选集（两处共用：`@` 浮层与上传对话框的「从项目选择」页签）
    const mentionCandidates = computed(() => {
      // 文件暂存区是产品内部实现，不该出现在「挑一份文件」的清单里（真机实测第一行就是它）
      const all = excludeSystemFolders(allProjectFiles.value)
      const byId = new Map(all.map((f) => [f.id, f]))
      return all
        .filter((f) => f && f.id != null && !f.isDeleted)
        .map((f) => ({
          id: f.id,
          name: f.name,
          fileType: f.fileType,
          wpsFileId: f.wpsFileId,
          parentId: f.parentId,
          isDir: !!(f.isFolder || f.isDir),
          dirLabel: dirLabelOf(f, byId),
        }))
        .sort((a, b) => String(a.name).localeCompare(String(b.name), 'zh'))
    })

    const closeMention = () => {
      mentionOpen.value = false
      mentionQuery.value = ''
      mentionAnchor = null
    }

    // 光标前那段 `@xxx`：必须落在同一个文本节点里，且 `@` 前面是行首或空白
    //（插完标签补的那个 &nbsp; 也算空白，所以「标签后面接着打 @」照样能触发）。
    // 内联标签里画出来的那个 `@` 在 contenteditable=false 的 span 里，光标进不去，不会误触发。
    const detectMention = () => {
      if (!richInput.value) return closeMention()
      const sel = typeof window !== 'undefined' && window.getSelection ? window.getSelection() : null
      if (!sel || !sel.isCollapsed || sel.rangeCount === 0) return closeMention()
      const node = sel.anchorNode
      if (!node || node.nodeType !== 3 || !richInput.value.contains(node)) return closeMention()
      const before = String(node.textContent || '').slice(0, sel.anchorOffset)
      const m = before.match(/(^|\s)@([^\s@]{0,40})$/)
      if (!m) return closeMention()
      mentionAnchor = { node, start: before.length - m[2].length - 1, end: sel.anchorOffset }
      mentionQuery.value = m[2]
      if (!mentionOpen.value) {
        mentionOpen.value = true
        ensureProjectFileIndex()
      }
    }

    // 文件夹整体挂进来时的后代文件数上限：与文件树拖拽那条路同一个判据
    //（utils/aiContextFiles.js），不然同一个文件夹在两个入口给出两种结论。
    const addFileWithLimit = (file) => {
      if (!file || file.id == null) return false
      if (file.isDir) {
        const total = countDescendantFiles(allProjectFiles.value, file.id)
        if (total > AI_CONTEXT_FOLDER_FILE_LIMIT) {
          uni.showToast({ title: t('workbench.folderTooManyFiles', { count: total }), icon: 'none' })
          return false
        }
      }
      addFile(file)
      return true
    }

    const chooseMentionFile = (file) => {
      const anchor = mentionAnchor
      closeMention()
      if (!file) return
      // 先把 `@查询` 那段删掉并把光标留在原处——addFile → insertContextTagToInput
      // 正是往当前 Range 插标签，位置天然对得上。
      if (anchor && anchor.node && anchor.node.isConnected && richInput.value && richInput.value.contains(anchor.node)) {
        try {
          const len = String(anchor.node.textContent || '').length
          const range = document.createRange()
          range.setStart(anchor.node, Math.min(anchor.start, len))
          range.setEnd(anchor.node, Math.min(anchor.end, len))
          range.deleteContents()
          richInput.value.focus()
          const sel = window.getSelection()
          sel.removeAllRanges()
          sel.addRange(range)
        } catch (err) {
          // 锚点失效（用户在浮层开着时改了结构）：退化成「标签追加到末尾」，
          // 不因为一次定位失败就把选中的文件整个丢掉
          console.warn('[ChatInterface] mention anchor removal failed', err)
        }
      }
      addFileWithLimit(file)
      if (richInput.value) inputPrompt.value = richInput.value.innerText
    }

    // ================= 输入框局部键位（dev-board#795 K16） =================
    // Esc 只挂在输入框上，**不进 config/commands**：那条硬规则说 Esc 一旦成为菜单
    // 加速键就会吞掉编辑器和所有输入框的 Esc（config/commands/ai.js:6）。
    const historyRecall = ref({ index: -1, text: '' })
    const escArmed = ref(false)
    let escArmTimer = null
    const disarmEsc = () => {
      escArmed.value = false
      if (escArmTimer) {
        clearTimeout(escArmTimer)
        escArmTimer = null
      }
    }

    const clearDraft = () => {
      if (richInput.value) richInput.value.innerHTML = ''
      inputPrompt.value = ''
      // 内联标签没了 → contextFiles 跟着空掉（附件本来就是草稿的一部分）。
      // 粘进来的图片刻意不动：它们各自有 × 可以摘，清草稿不该顺手销毁。
      syncContextFilesWithInlineTags()
      historyRecall.value = { index: -1, text: '' }
      closeMention()
      uni.showToast({ title: t('chat.escCleared'), icon: 'none' })
    }

    // 清空草稿是会丢东西的动作，所以要按两次：第一次只给提示并上闩，3 秒内再按才真清。
    const handleEscape = () => {
      if (mentionOpen.value) {
        closeMention()
        return
      }
      if (isStreaming.value) {
        disarmEsc()
        handleAbort()
        return
      }
      const draft = richInput.value ? richInput.value.innerText : ''
      if (!draft.trim() && contextFiles.value.length === 0) {
        disarmEsc()
        return
      }
      if (!escArmed.value) {
        escArmed.value = true
        uni.showToast({ title: t('chat.escClearArmed'), icon: 'none' })
        escArmTimer = setTimeout(() => {
          escArmed.value = false
          escArmTimer = null
        }, 3000)
        return
      }
      disarmEsc()
      clearDraft()
    }

    // 上箭头翻历史：只在输入框为空时起步，之后只要草稿还等于刚回填的那条就继续翻，
    // 用户一改字就退出（handleRichInput 里重置）——不能把人正在写的东西顶掉。
    const recallableUserMessages = () =>
      bubbles.value
        .filter((b) => b && b.role === 'USER')
        .map((b) => String(b.displayContent || b.content || ''))
        .filter((s) => s.trim())

    const applyRecall = (text) => {
      if (!richInput.value) return
      richInput.value.textContent = text
      inputPrompt.value = text
      syncContextFilesWithInlineTags()
      try {
        const range = document.createRange()
        range.selectNodeContents(richInput.value)
        range.collapse(false)
        const sel = window.getSelection()
        sel.removeAllRanges()
        sel.addRange(range)
        richInput.value.focus()
      } catch (err) {
        console.warn('[ChatInterface] recall caret placement failed', err)
      }
    }

    const recallHistory = (direction) => {
      const list = recallableUserMessages()
      if (!list.length) return false
      const cur = historyRecall.value
      const draft = richInput.value ? richInput.value.innerText : ''
      const navigating = cur.index >= 0 && draft === cur.text
      if (!navigating) {
        if (direction !== 'older') return false
        if (draft.trim()) return false
        const text = list[list.length - 1]
        historyRecall.value = { index: list.length - 1, text }
        applyRecall(text)
        return true
      }
      const next = cur.index + (direction === 'older' ? -1 : 1)
      if (next < 0) return true // 已经到最上面，停住（别绕回最新那条）
      if (next > list.length - 1) {
        historyRecall.value = { index: -1, text: '' }
        applyRecall('')
        return true
      }
      historyRecall.value = { index: next, text: list[next] }
      applyRecall(list[next])
      return true
    }

    // 输入框上唯一的 keydown 入口：Enter 仍走 handleEnterKey（isComposing 闩在那里），
    // 这里只多接管 Esc / Cmd+Enter / 上下箭头 / 引用浮层的键盘导航。
    const handleInputKeydown = (e) => {
      if (e.isComposing || e.keyCode === 229) return
      const key = e.key
      if (key !== 'Escape') disarmEsc()
      if (key === 'Escape') {
        e.preventDefault()
        handleEscape()
        return
      }
      const pickerItem = mentionOpen.value && mentionPicker.value ? mentionPicker.value.activeItem() : null
      if (key === 'Enter') {
        // Cmd/Ctrl+Enter 是明确的「发出去」，排在引用浮层之前：浮层开着时按它，
        // 用户要的是发送而不是再选一个文件。
        if (e.metaKey || e.ctrlKey) {
          e.preventDefault()
          closeMention()
          handleSubmit(followUpMode.value)
          return
        }
        if (pickerItem) {
          e.preventDefault()
          chooseMentionFile(pickerItem)
          return
        }
        handleEnterKey(e)
        return
      }
      if (key === 'Tab' && pickerItem) {
        e.preventDefault()
        chooseMentionFile(pickerItem)
        return
      }
      if (key === 'ArrowUp' || key === 'ArrowDown') {
        if (mentionOpen.value && mentionPicker.value) {
          e.preventDefault()
          mentionPicker.value.moveActive(key === 'ArrowDown' ? 1 : -1)
          return
        }
        if (recallHistory(key === 'ArrowUp' ? 'older' : 'newer')) e.preventDefault()
      }
    }

    // 下拉选项的键盘选择（K16 ⑤）：选项本身是 uni <view>，靠 tabindex + role=option
    // 进 Tab 序，Enter/空格等同点击。
    const onOptionKey = (e, handler) => {
      e.preventDefault()
      handler()
    }

    onBeforeUnmount(() => disarmEsc())

    // --- Truncate filename for display ---
    const truncateName = (name, maxLen = 15) => {
      if (!name) return ''
      return name.length > maxLen ? name.slice(0, maxLen) + '...' : name
    }

    // --- Escape HTML for safe rendering (fallback for plain text content) ---
    const escapeHtml = (text) => {
      if (!text) return ''
      return String(text)
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/\"/g, '&quot;')
        .replace(/'/g, '&#039;')
        .replace(/\n/g, '<br/>')
    }

    // --- File Context Methods ---
    const addFile = (file) => {
      // 上限在**加进来的那一刻**就拦住（dev-board#801 K21 ⑧，审查 E-14）。
      //
      // 病灶：前端对 contextFiles 的长度没有任何检查，后端超过 maxFilesPerContext 就 break
      // 并在 prompt 里写一句英文 System Note。用户拖了 15 份材料、界面上 15 个标签都在，
      // 模型只看到前 10 份，而且是按顺序静默砍掉后面的——对「把这批合同交叉比对一下」
      // 这种诉求是直接的错误输出。上限值随 /api/ai/config 下发，不写死。
      const verdict = admitFileToContext({
        file,
        contextFiles: contextFiles.value,
        pastedImages: pastedImages.value,
        limits: contextLimits.value,
      })
      if (!verdict.ok) {
        if (verdict.reason === 'cap' && typeof uni !== 'undefined') {
          uni.showToast({
            title: t('chat.contextFileCapReached', { max: verdict.max }),
            icon: 'none',
            duration: 3000,
          })
        }
        // duplicate 静默：同一份拖两遍是常见误操作，弹提示只是噪音
        return
      }
      const fileData = {
        id: file.id,
        name: file.name,
        fileType: file.fileType,
        wpsFileId: file.wpsFileId,
        isDir: file.isDir || file.fileType === 'folder'
      }
      contextFiles.value.push(fileData)
      // 只有真挂了音频才去问「它转写过没有」（dev-board#814 K34）
      if (isAudioFile(fileData)) refreshTranscribedAudio()

      // Insert inline tag into rich input
      insertContextTagToInput(fileData)

      console.log('[ChatInterface] File added as context:', file.name)
    }

    // --- Insert inline tag into contenteditable ---
    const insertContextTagToInput = (file, carried = false, { append = false } = {}) => {
      if (!richInput.value) return

      const icon = file.isDir ? '/static/folder-closed.png' : '/static/document.png'
      const displayName = truncateName(file.name)
      // 文件名由项目成员自由命名（后端只挡路径分隔符），这段字符串会直接进 DOM，必须转义
      const safeDisplayName = escapeHtml(displayName)
      // 淡态 = 上一轮带过的，仍然会继续带上（这才是 E-2 要的跨轮保留）；
      // title 里明说这件事，否则用户会以为淡态是「已失效」
      const carriedClass = carried ? ' is-carried' : ''
      const tagTitle = escapeHtml(carried ? t('chat.carriedAttachmentTitle', { name: file.name }) : file.name)

      const tagHtml = `
        <span class="context-tag-inline${carriedClass}" contenteditable="false" data-file-id="${file.id}" data-is-dir="${file.isDir ? 'true' : 'false'}" title="${tagTitle}">
          <img src="${icon}" class="tag-icon"/>
          <span class="tag-at">@</span>
          <span class="tag-name">${safeDisplayName}</span>
          <span class="tag-close">×</span>
        </span>&nbsp;`.replace(/\s+/g, ' ').trim()

      // Insert at cursor or append to end
      // append=true：强制挂到末尾。发送后补挂淡态标签走这条——此刻光标可能落在
      // 用户刚打的新字中间，插到那儿会把他的句子从中劈开。
      const sel = append ? null : window.getSelection()
      if (sel && sel.rangeCount > 0) {
        const range = sel.getRangeAt(0)
        if (richInput.value.contains(range.commonAncestorContainer)) {
          range.deleteContents()
          const fragment = range.createContextualFragment(tagHtml)
          range.insertNode(fragment)
          range.collapse(false)
        } else {
          richInput.value.innerHTML += tagHtml
        }
      } else {
        richInput.value.innerHTML += tagHtml
      }

      // Update text model
      inputPrompt.value = richInput.value.innerText
    }

    /**
     * 发送后重建输入框：文字清掉，本轮带走的附件标签留下并置成淡态（dev-board#793 K14 ③）。
     *
     * 为什么要整段重建而不是「只删文字节点」：输入框是 contenteditable，
     * 用户敲进去的内容形态五花八门（div/br/裸文本节点混排），逐节点删既啰嗦又容易删漏；
     * 而标签本身是我们自己生成的、可以按 contextFiles 原样重新插一遍。
     *
     * carriedFileIds 记住「哪些是上一轮带过的」，供 is-carried 淡态与
     * 「只有旧附件、没有新内容」的空发守卫用。
     */
    const restoreCarriedTags = async (sentFiles, { clearText }) => {
      const kept = contextFiles.value.filter((f) =>
        (sentFiles || []).some((sent) => String(sent.id) === String(f.id)))
      carriedFileIds.value = kept.map((f) => String(f.id))
      // 眼前这个输入框先清掉文字（用户刚按下回车，文字不能还杵在那儿）
      if (clearText && richInput.value) richInput.value.innerHTML = ''
      // **必须等重渲染落地再插标签**：模板里有两个 ref="richInput" 的 contenteditable
      // （空状态的欢迎输入框 v-if / 有对话时的底部输入框 v-else）。本会话第一条消息发出去之后
      // bubbles 由空变非空，两者整块互换成一个全新的空 div——同步写只会写进马上被销毁的那一个。
      // （这同时也是 shouldClearChatDraft 在首条消息上恒为 false 的原因：它的指纹含 editorHtml，
      //  而此刻元素已经换了、innerHTML 已经是空串。所以「把附件挂回去」不能挂在那个分支下面。）
      await nextTick()
      if (!richInput.value) return
      // 只补缺的、不重建：用户可能在这一小段时间里已经打了新的字，
      // 整段重写会把他刚打的东西抹掉。
      const present = new Map([...richInput.value.querySelectorAll('[data-file-id]')]
        .map((e) => [e.getAttribute('data-file-id'), e]))
      for (const f of kept) {
        const existing = present.get(String(f.id))
        // 已经在框里的（用户在途中又打了字、没触发清空那一支）只补淡态，
        // 不然 carriedFileIds 说它是上一轮的、界面上却还是常态，两边对不上
        if (existing) existing.classList.add('is-carried')
        else insertContextTagToInput(f, true, { append: true })
      }
      inputPrompt.value = richInput.value.innerText
    }

    /**
     * 把一份附件记录还原成输入框里的 @标签 + contextFiles（dev-board#793 K14 ④）。
     *
     * 用在「回退到这条消息」的回填上：只还原文字、附件不回来的话，用户改一个字重发，
     * 材料就悄悄少了——而他以为自己只是改了个措辞。
     * 「重新生成」不走这里（它不碰输入框，直接把 fileListFromBubble 的结果发出去）。
     *
     * @param fileList fileListFromBubble 的结果（{id, fileName, fileType, wpsFileId, isDir}）
     */
    const restoreAttachmentsToInput = (fileList) => {
      if (!Array.isArray(fileList) || !fileList.length) return
      carriedFileIds.value = []
      for (const f of fileList) {
        addFile({ id: f.id, name: f.fileName, fileType: f.fileType, wpsFileId: f.wpsFileId, isDir: f.isDir })
      }
      if (richInput.value) inputPrompt.value = richInput.value.innerText
    }

    /** 点淡态标签本体 = 确认沿用，回到常态（× 仍然是移除）。 */
    const confirmCarriedFile = (fileId) => {
      const id = String(fileId)
      carriedFileIds.value = carriedFileIds.value.filter((x) => x !== id)
      if (!richInput.value) return
      const el = richInput.value.querySelector(`[data-file-id="${CSS.escape(id)}"]`)
      if (el) el.classList.remove('is-carried')
    }

    const removeContextFile = (index) => {
      contextFiles.value.splice(index, 1)
    }

    // --- 音频附件（dev-board#814）---------------------------------------------------
    //
    // 模型读的是转写稿，不是音频。没有转写稿时后端只会回一句「先转写」，用户却已经把
    // 问题问出去、等着一个基于录音内容的回答。所以判定要提前到发送之前。
    //
    // 判据来自既有的会议记录（audioFileId 就是音频↔转写稿的关联），走既有的
    // GET /api/meetings/projects/{id}，不新增任何出站请求；只在真的挂了音频附件时才拉一次。
    const transcribedAudioIds = ref(new Set())
    const refreshTranscribedAudio = async () => {
      if (!props.projectId) return
      try {
        // 载荷是 { meetings, configured }（MeetingRecordingController.list），
        // 与 MeetingRecordingPanel.loadMeetings 读的是同一个字段
        const res = await getMeetingRecordings(props.projectId)
        transcribedAudioIds.value = transcribedAudioFileIds(res && res.meetings)
      } catch (e) {
        // 拉不到就按「都没转写」处理：多提示一次，好过让用户以为 AI 听过这段录音
        console.warn('[ChatInterface] 会议记录拉取失败，音频附件按未转写提示', e)
      }
    }
    const pendingAudioFiles = computed(
      () => audioNeedingTranscription(contextFiles.value, transcribedAudioIds.value))
    // 「转写」复用文件树右键那条动作（project-overview 的 onTranscribeAudio）：
    // 同一个 register-file 接口、同一个面板落点，不另起一套流程。
    const handleTranscribeAudio = (file) => emit('transcribe-audio', file)

    const removePastedImage = (index) => {
      pastedImages.value.splice(index, 1)
    }

    // --- Upload Dialog Methods ---
    const triggerFileSelect = async () => {
      // Load project folders for folder selector
      await loadProjectFolders()

      // Reset state
      uploadTab.value = 'local'
      projectPickQuery.value = ''
      uploadSelectedFiles.value = []
      selectedUploadParent.value = null
      isFolderUpload.value = false
      showFolderSelector.value = false
      tempSelectedParent.value = null

      // Show upload dialog
      showUploadDialog.value = true
    }

    const loadProjectFolders = async () => {
      if (!props.projectId) return
      try {
        const files = await getProjectFiles(props.projectId, null, true) // tree=true
        allProjectFiles.value = files || []
        projectFileIndexKey.value = String(props.projectId)
        console.log('[ChatInterface] Loaded project files for folder selector:', files?.length)
      } catch (e) {
        console.error('[ChatInterface] Failed to load project folders:', e)
        allProjectFiles.value = []
      }
    }

    // 「从项目选择」页签：与 `@` 浮层同一份候选集、同一个检索（utils/aiContextFiles.js）
    const projectPickMatches = computed(() => matchProjectFiles(mentionCandidates.value, projectPickQuery.value, 60))
    const isPicked = (file) => contextFiles.value.some((f) => String(f.id) === String(file.id))
    const onProjectPickInput = (e) => {
      projectPickQuery.value = (e.detail && e.detail.value) || ''
    }
    const pickProjectFile = (file) => {
      if (isPicked(file)) return
      if (addFileWithLimit(file)) {
        uni.showToast({ title: t('workbench.fileAdded', { name: file.name }), icon: 'none' })
      }
    }

    const selectFilesForUpload = () => {
      // H5/uni-app file selection
      uni.chooseFile({
        count: 9,
        success: (res) => {
          isFolderUpload.value = false
          uploadSelectedFiles.value = res.tempFiles.map(file => ({
            name: file.name,
            path: file.path,
            size: file.size,
            relativePath: file.name,
            fileObject: file
          }))
        },
        fail: (err) => {
          console.error('选择文件失败:', err)
          uni.showToast({ title: t('chat.chooseFileFailed'), icon: 'none' })
        }
      })
    }

    // #ifdef H5
    const triggerFolderUploadInput = () => {
      const input = document.createElement('input')
      input.type = 'file'
      input.webkitdirectory = true
      input.directory = true
      input.multiple = true

      input.onchange = (e) => {
        const files = Array.from(e.target.files || [])
        if (files.length === 0) return

        isFolderUpload.value = true
        uploadSelectedFiles.value = files.map(f => ({
          name: f.name,
          size: f.size,
          path: URL.createObjectURL(f),
          fileObject: f,
          relativePath: f.webkitRelativePath || f.name
        }))
      }

      input.click()
    }
    // #endif

    const selectUploadParent = (parentId) => {
      tempSelectedParent.value = parentId
    }

    const confirmFolderSelection = () => {
      selectedUploadParent.value = tempSelectedParent.value
      showFolderSelector.value = false
    }

    // Open folder selector and reset expand state
    const openFolderSelector = () => {
      folderSelectorExpanded.value = {} // Reset expand state
      tempSelectedParent.value = selectedUploadParent.value
      showFolderSelector.value = true
    }

    // Toggle folder expand/collapse in selector
    const toggleFolderSelectorExpand = (folderId) => {
      const key = String(folderId)
      if (key === 'root') {
        // Root uses reverse logic: undefined/missing means expanded
        if (folderSelectorExpanded.value['root'] === false) {
          folderSelectorExpanded.value = { ...folderSelectorExpanded.value, root: undefined }
        } else {
          folderSelectorExpanded.value = { ...folderSelectorExpanded.value, root: false }
        }
      } else {
        // Non-root: undefined means collapsed, true means expanded
        const current = folderSelectorExpanded.value[key] === true
        folderSelectorExpanded.value = { ...folderSelectorExpanded.value, [key]: !current }
      }
    }

    // Get folder path for display
    const getFolderPath = (folderId) => {
      if (typeof folderId === 'number' || typeof folderId === 'string') {
        const folder = allProjectFiles.value.find(f => f.id === folderId)
        if (folder) {
          return buildFolderPath(folder)
        }
        return t('chat.unknownFolder')
      }
      if (folderId && folderId.name) {
        return buildFolderPath(folderId)
      }
      return t('chat.rootFolder')
    }

    // Build full folder path string
    const buildFolderPath = (folder) => {
      if (!folder) return ''
      const path = [folder.name]
      let current = folder
      while (current && current.parentId !== null) {
        const parent = allProjectFiles.value.find(f => f.id === current.parentId)
        if (parent) {
          path.unshift(parent.name)
          current = parent
        } else {
          break
        }
      }
      return path.join(' / ')
    }

    // Handle create folder in selector
    const handleSelectorCreateFolder = async () => {
      const folderName = await new Promise((resolve) => {
        uni.showModal({
          title: t('chat.newFolder'),
          editable: true,
          placeholderText: t('chat.folderNamePlaceholder'),
          success: (res) => {
            if (res.confirm && res.content) {
              resolve(res.content.trim())
            } else {
              resolve(null)
            }
          },
          fail: () => resolve(null)
        })
      })

      if (!folderName) return

      try {
        const projectId = typeof props.projectId === 'string' ? Number(props.projectId) : props.projectId
        const parentId = tempSelectedParent.value

        const { createFolder } = await import('@/services/api.js')
        const newFolder = await createFolder(projectId, parentId, folderName)

        if (newFolder && newFolder.id) {
          // Add to local list
          allProjectFiles.value = [...allProjectFiles.value, { ...newFolder, isFolder: true }]
          // Select the new folder
          tempSelectedParent.value = newFolder.id
          // Expand parent if collapsed
          if (parentId) {
            folderSelectorExpanded.value = { ...folderSelectorExpanded.value, [String(parentId)]: true }
          }
          uni.showToast({ title: t('chat.folderCreated'), icon: 'success' })
        }
      } catch (error) {
        console.error('[ChatInterface] Create folder failed:', error)
        uni.showToast({ title: error.message || t('chat.folderCreateFailed'), icon: 'none' })
      }
    }

    const cancelUpload = () => {
      showUploadDialog.value = false
      uploadSelectedFiles.value = []
      selectedUploadParent.value = null
      isFolderUpload.value = false
    }

    const getFileTypeFromName = (fileName) => {
      if (!fileName) return 'other'
      const ext = fileName.split('.').pop()?.toLowerCase()
      const typeMap = {
        doc: 'word', docx: 'word',
        xls: 'excel', xlsx: 'excel',
        pdf: 'pdf',
        txt: 'txt',
        ppt: 'ppt', pptx: 'ppt',
        // bmp 是补的：后端的 ocr-extensions 与 vision.extensions 都含 bmp，
        // 这里漏掉会让 .bmp 落成 'other'，与另外两处判图口径对不上
        jpg: 'image', jpeg: 'image', png: 'image', gif: 'image', webp: 'image', bmp: 'image',
        md: 'markdown'
      }
      return typeMap[ext] || 'other'
    }

    // Confirm upload and add to context (like drag-drop)
    const confirmUploadAndAddContext = async () => {
      if (uploadSelectedFiles.value.length === 0) {
        uni.showToast({ title: t('chat.pleaseSelectFiles'), icon: 'none' })
        return
      }

      const parentId = selectedUploadParent.value
      const filesToUpload = [...uploadSelectedFiles.value]

      // Close dialog
      showUploadDialog.value = false
      uploadSelectedFiles.value = []

      await uploadFilesAndAttach(filesToUpload, parentId)
    }

    /**
     * 上传一批文件到项目里，再把它们挂进本轮上下文。
     * 入参形状 { name, size, fileObject }——上传对话框与「本机文件拖进对话区」
     * （dev-board#779 K6 ③）共用这一条路，不另起一套。
     */
    const uploadFilesAndAttach = async (filesToUpload, parentId) => {
      if (!filesToUpload || filesToUpload.length === 0) return

      if (!props.projectId) {
        uni.showToast({ title: t('chat.projectIdMissing'), icon: 'none' })
        return
      }

      isUploading.value = true
      const projectId = typeof props.projectId === 'string' ? Number(props.projectId) : props.projectId

      // 字节上传失败的文件名：这些不并入附件，收尾时要点名告诉用户
      const failedUploads = []
      let addedCount = 0

      try {
        for (const file of filesToUpload) {
          const fileType = getFileTypeFromName(file.name)
          const wpsFileId = `project_${projectId}_doc_${Date.now()}_${Math.random().toString(36).substring(2, 9)}`

          // Create file record in backend
          const createdFile = await createFile(
            projectId,
            parentId,
            file.name,
            fileType,
            file.size,
            null,
            wpsFileId
          )

          if (createdFile && createdFile.id) {
            console.log('[ChatInterface] File created:', createdFile.name, createdFile.id)

            // Upload file content if available (H5)
            if (file.fileObject) {
              try {
                await uploadFileContent(createdFile.id, wpsFileId, file.fileObject, file.size)
              } catch (uploadErr) {
                // 字节没传上去就**不并入附件**。原来这里只 console.warn 然后照样 addFile，
                // 结果是 contextItems 里挂着一个服务器上没有内容的 id：模型收到的是
                // 「文件在这儿但里面什么都没有」，只会回一句「我看不到这份文件」，
                // 而用户以为自己已经把文件发过去了。图片接上视觉直送后这条更要命——
                // 一张没有字节的图既走不了直送也走不了 OCR。
                console.warn('[ChatInterface] File content upload failed, not attaching:', uploadErr)
                failedUploads.push(file.name)
                continue
              }
            }

            // Add to context (same as drag-drop)
            addFile({
              id: createdFile.id,
              name: createdFile.name,
              fileType: createdFile.fileType,
              wpsFileId: createdFile.wpsFileId,
              isDir: false
            })
            addedCount++
          }
        }

        if (failedUploads.length) {
          uni.showToast({
            title: t('chat.uploadContentFailed', { names: failedUploads.join('、') }),
            icon: 'none',
            duration: 3000
          })
        } else {
          uni.showToast({ title: t('chat.filesAdded', { count: addedCount }), icon: 'success' })
        }
      } catch (error) {
        console.error('[ChatInterface] Upload failed:', error)
        uni.showToast({ title: error.message || t('chat.uploadFailed'), icon: 'none' })
      } finally {
        isUploading.value = false
      }
    }

    /**
     * 把本机文件（Finder / 资源管理器拖进对话区）上传进项目并挂上下文（dev-board#779 K6 ③）。
     * 宿主 project-overview 的 handleAiDrop 在三种应用内格式都落空、dataTransfer 里
     * 确实有文件时调这里，走的就是上传对话框那一条路（createFile + uploadFileContent +
     * addFile），不另起一套。
     *
     * 落点固定项目根目录：工作台里没有「当前文件夹」这个概念（文件树的选中项跟着编辑器
     * 标签走，是一份文件不是一个目录），跟着它走会把拖进来的材料随机塞到某份文档旁边。
     * 根目录是上传对话框的默认值，也是用户一眼能找到的地方；toast 里点名落点。
     */
    const uploadLocalFilesAndAddContext = async (fileList) => {
      const files = Array.from(fileList || [])
        .filter(f => f && f.name)
        .map(f => ({ name: f.name, size: f.size, fileObject: f }))
      if (!files.length) return
      await uploadFilesAndAttach(files, null)
    }

    // Upload file content to storage
    const uploadFileContent = async (fileId, wpsFileId, fileObject, totalSize) => {
      return new Promise((resolve, reject) => {
        // #ifdef H5
        const xhr = new XMLHttpRequest()
        xhr.open('POST', `${getApiBaseUrl()}/api/files/${wpsFileId}/upload`)

        const headers = getAuthHeaders()
        for (const key in headers) {
          xhr.setRequestHeader(key, headers[key])
        }
        xhr.setRequestHeader('Content-Type', 'application/octet-stream')
        xhr.setRequestHeader('X-File-Offset', '0')
        xhr.setRequestHeader('X-File-Total-Size', String(totalSize))

        xhr.onload = () => {
          if (xhr.status >= 200 && xhr.status < 300) {
            resolve()
          } else {
            reject(new Error(`HTTP ${xhr.status}`))
          }
        }
        xhr.onerror = () => reject(new Error('Network error'))
        xhr.send(fileObject)
        // #endif

        // #ifndef H5
        resolve() // Non-H5 platforms skip direct upload
        // #endif
      })
    }

    // 剪贴板 MIME → 扩展名。后端判「这是不是可直送的图」先看文件名后缀
    // （ai.context.vision.extensions = jpg/jpeg/png/gif/bmp/webp），后看 fileType，
    // 所以后缀必须与真实字节一致；认不出的 MIME 按 png 落名，不凭空造后缀。
    const PASTED_IMAGE_EXT = {
      'image/png': 'png',
      'image/jpeg': 'jpg',
      'image/gif': 'gif',
      'image/bmp': 'bmp',
      'image/webp': 'webp'
    }

    // 把粘贴进来的图片落成真实项目文件，返回 { files, failed }。
    //
    // 在此之前，粘贴的图片只有一份 dataURL 用来画气泡缩略图，blob 从没上过服务器：
    // 既没进 contextItems，也就既没走视觉直送、也没走 OCR——模型其实什么都没收到。
    // 这里让它走「+」上传的同一条链路（createFile + 字节直传），汇进同一份 fileList，
    // 由后端按模型能力决定直送还是降级。
    const uploadPastedImages = async (images) => {
      const projectId = typeof props.projectId === 'string' ? Number(props.projectId) : props.projectId
      if (!projectId) return { files: [], failed: images.length }

      const d = new Date()
      const p2 = (n) => String(n).padStart(2, '0')
      const stamp = `${d.getFullYear()}${p2(d.getMonth() + 1)}${p2(d.getDate())}-${p2(d.getHours())}${p2(d.getMinutes())}${p2(d.getSeconds())}`

      const files = []
      let failed = 0
      for (let i = 0; i < images.length; i++) {
        const blob = images[i] && images[i].file
        if (!blob) { failed++; continue }
        const ext = PASTED_IMAGE_EXT[String(blob.type).toLowerCase()] || 'png'
        // 同一秒里贴多张会重名，带上序号
        const suffix = images.length > 1 ? `${stamp}-${i + 1}` : stamp
        const name = `${t('chat.pastedImageName', { stamp: suffix })}.${ext}`
        const wpsFileId = `project_${projectId}_doc_${Date.now()}_${Math.random().toString(36).substring(2, 9)}`
        try {
          // 落在项目根目录：粘贴没有「选目标文件夹」这一步，不该替用户猜一个
          const created = await createFile(projectId, null, name, getFileTypeFromName(name), blob.size, null, wpsFileId)
          if (!created || !created.id) throw new Error('createFile returned no id')
          // 字节没传上去就绝不并入附件：contextItems 里挂一个服务器上没有内容的 id，
          // 模型只会回「我看不到这张图」，而用户以为自己已经把图发过去了。
          await uploadFileContent(created.id, wpsFileId, blob, blob.size)
          files.push({
            id: created.id,
            name: created.name,
            fileType: created.fileType,
            wpsFileId: created.wpsFileId,
            isDir: false
          })
        } catch (e) {
          console.warn('[ChatInterface] 粘贴图片上传失败:', e)
          failed++
        }
      }
      return { files, failed }
    }

    // 外部面板（如股东大会核查）注入预设 prompt：强制 AGENT 模式发送
    // （skill 注入依赖 prompt 文本内的触发词；ASK 模式会跳过注入）。
    // 返回本次会话 ID，供调用方把业务对象绑定到该会话。
    const sendExternalPrompt = async (prompt) => {
      if (!prompt) return null
      if (isStreaming.value) {
        uni.showToast({ title: t('chat.busyToast'), icon: 'none' })
        return null
      }
      await sendMessage({
        prompt,
        projectId: props.projectId,
        modelId: currentModelId.value,
        mode: 'AGENT',
        skillIds: currentSkillIds()
      })
      scrollToBottom()
      return currentConversationId.value
    }

    // ---- 菜单栏「AI」命令入口 ----
    // 一律薄转发到面板自己已有的方法，菜单和面板上的按钮走同一条代码路径。
    // 模式切换要过 availableModes：本地 Ollama 只支持 ASK，菜单不能绕过这条闸
    // 让用户选到一个「一发即报错」的模式。
    const menuSetMode = (id) => {
      const m = availableModes.value.find((x) => x.id === id)
      if (!m) {
        uni.showToast({ title: localModeNotice.value || t('chat.modeUnavailable'), icon: 'none' })
        return false
      }
      selectMode(m)
      return true
    }
    /**
     * 停止：先停掉正在生成的那一轮 AI，再取消所有在跑的后台任务。
     *
     * 菜单项的置灰判据 `aiRunning` 把流式生成也算作「在跑」（见下面的 menuState），
     * 所以这里必须真能停下 AI。此前只遍历 runningTasks，于是最常见的那一种情形
     * ——AI 正在生成、没有任何后台任务——菜单是亮的、点得下去，循环却零次，
     * 模型照样在跑、在改文档、在烧 token，而用户以为自己已经停了。
     *
     * 停 AI 走 handleAbort（输入区那个停止键用的同一条路，附带那句诚实的提示）。
     * @returns {Promise<number>} 实际停掉的条数：AI 轮次算 1，加上取消掉的后台任务数。
     */
    const menuStop = async () => {
      const stoppedAi = isStreaming.value
      if (stoppedAi) await handleAbort()
      const list = runningTasks.value.slice()
      for (const t of list) await handleCancelTask(t)
      return (stoppedAi ? 1 : 0) + list.length
    }
    /** 菜单读勾选/置灰用的状态快照。全是布尔或短枚举，不放计数器。 */
    const menuState = () => ({
      aiRunning: !!isStreaming.value || runningTasks.value.length > 0,
      aiMode: currentModeId.value,
    })

    // 菜单栏的「停止当前任务」置灰与「模式」勾选跟着这两个信号走。
    // 不轮询：变了才广播一次，宿主收到后重推菜单状态。
    watch([isStreaming, currentModeId, runningTasks], () => emit('menu-state'))

    // Expose methods for parent ref access
    expose({
      addFile, loadMessages, loadConversationMetadata, sendExternalPrompt,
      startNewChat, menuSetMode, menuStop, menuState,
      uploadLocalFilesAndAddContext,
    })

    return {
       bubbles,
       currentConversationId,
       isStreaming,
       componentGateItem,
       componentGateResolved,
       resolveComponentGate,
       backgroundComponentGate,
       inputPrompt,
       richInput,
       showMemoryBrowser,
       decisionAssistEnabled, toggleDecisionAssist, isLocalOnlyProvider,
       followUpMode,
       toggleFollowUpMode,
       pendingInbox,
       handleInboxEdit,
       handleInboxDelete,
       handleInboxMove,
       handleInboxSendNow,
       tokenUsage,
       messageList, messageContent, chatTurns,
       followLatest, handleMessageScroll, handleHistoryScroll, scrollToBottom, attentionNotice, jumpToAttention,
       historyHasMore, historyLoadingOlder, loadOlderMessages,
       activeTurnKey, handleTurnJump,
       receiptLabel, inboxStreamIds, inboxRunActive, handleInboxLocate,
       contextFiles,
       pendingAudioFiles,
       handleTranscribeAudio,
       pastedImages,
       carriedFileIds,
       // 「重新生成」按用户气泡上的附件记录重建 fileList（dev-board#793 K14 ④）：
       // 同一个问题重问一次，带的材料要和当初一模一样，否则那不是「换一份回答」而是「换一个问题」
       fileListFromBubble,
       activeDocChip,
       dismissActiveDoc,
       visionNotice,
       contextNoticeText,
       isUploadingPasted,
       handleSubmit,
       handleAbort,
       handleRichInput,
       handleInputClick,
       handlePaste,
       handleEnterKey,
       // `@` 引用选择器与输入框键位（dev-board#794 K15 / #795 K16）
       mentionOpen,
       mentionQuery,
       mentionLoading,
       mentionCandidates,
       mentionPicker,
       chooseMentionFile,
       handleInputKeydown,
       onOptionKey,
       startNewChat,
       loadMessages,
       formatTime,
       formatRelativeTime,
       cleanTitle,
       recentDotClass,
       agentRunStatus,
       linkStatus,
       addFile,
       removeContextFile,
       removePastedImage,
       truncateName,
       escapeHtml,
       // Rollback
       showRollbackDialog,
       rollbackTargetContent,
       rollbackLocator,
       rollbackResend,
       openRegenerateDialog,
       branchFromMessage,
       openRollbackDialog,
       cancelRollback,
       confirmRollback,
       // Model
       currentModelId,
       currentModelName,
       toggleModelDropdown: () => {
         showModelDropdown.value = !showModelDropdown.value
         if (showModelDropdown.value) showModeDropdown.value = false
       },
       selectModel,
       showModelDropdown,
       modelGroups,
       modelPriceDisplay,
       networkRegionBasis,
       currentModelVision,
       // Agent Mode
       currentModeId,
       currentModeName,
       currentModeIcon,
       toggleModeDropdown,
       selectMode,
       showModeDropdown,
       availableModes,
       localModeNotice,
       // Skill 选择与本轮生效清单
       ICONS,
       showSkillDropdown,
       availableSkills,
       selectedSkillIds,
       skillChips,
       skillDisabledByMode,
       skillDisplayName,
       toggleSkillDropdown,
       toggleSkillSelection,
       removeSelectedSkill,
       goToSkillManagement,
       // Artifact
       handleArtifactOpenTab: (art) => emit('artifact-open-tab', art),
       handleArtifactApprove: async (art) => {
          console.log('[ChatInterface] Artifact Approved:', art.id, art.revised ? `(revised x${art.changeCount})` : '')
          // 计划卡一键推进：普通批准发确认语；修订版把改动数与修订后全文一并回喂模型
          const prompt = art.revised
             ? `我已修订计划（共 ${art.changeCount} 处改动，${art.diffSummary}）。请以下方修订版计划为准执行，注意修订处的差异：\n\n${art.content}`
             : `已确认${art.type === 'implementation_plan' ? '实施计划' : '计划'}，请按此推进。`
          // 契约 D 要解决的原始病灶就在这里：上面那段是模型需要的细节（尤其修订版全文），
          // 但它此前直接当成用户消息显示，于是用户在自己的气泡里读到一句自己没说过的机器口吻话。
          // 现在细节仍走 message，气泡里只显示 displayText 这句人话。
          const displayText = art.revised ? t('chat.approveDisplayRevised') : t('chat.proceedBtn')
          // 审批后使用 AGENT 模式执行计划
          await sendMessage({
             prompt,
             displayText,
             fileList: [],
             projectId: props.projectId,
             modelId: currentModelId.value,
             mode: 'AGENT', // 审批后使用 Agent 模式执行
             skillIds: currentSkillIds()
          })
          scrollToBottom()
       },
       // 反问选项被点：选项原文本来就短、就像用户自己打的，所以 message 直接用它，
       // **不传 displayText**（同值等于不传）。刻意不拼「我选择了 X」这类机器口吻长句——
       // 那正是契约 D 要消灭的东西。
       handleQuestionAnswer: async (option) => {
          const text = typeof option === 'string' ? option.trim() : ''
          if (!text) return
          await sendMessage({
             prompt: text,
             fileList: [],
             projectId: props.projectId,
             modelId: currentModelId.value,
             mode: currentModeId.value,
             skillIds: currentSkillIds()
          })
          scrollToBottom()
       },
       // Upload Dialog
       showUploadDialog,
       uploadTab,
       projectPickQuery,
       projectPickMatches,
       isPicked,
       onProjectPickInput,
       pickProjectFile,
       uploadSelectedFiles,
       selectedUploadParent,
       selectedUploadParentName,
       isFolderUpload,
       showFolderSelector,
       tempSelectedParent,
       folderTree,
       isUploading,
       triggerFileSelect,
       selectFilesForUpload,
       triggerFolderUploadInput,
       selectUploadParent,
       confirmFolderSelection,
       cancelUpload,
       confirmUploadAndAddContext,
       // New Methods exposed to template
       folderSelectorExpanded,
       openFolderSelector,
       toggleFolderSelectorExpand,
       getFolderPath,
       handleSelectorCreateFolder,
       // Background Task Indicator
       backgroundTasks,
       dismissBackgroundTask,
       lastHeartbeat,
       // PPT Config
       showPptConfigDialog,
       pptExportEditable,
       pptConfigData,
       cancelPptConfig,
       confirmPptGeneration,
       // File Changes Status
       fileChanges,
       // 步数超限 / 进程中断的一键继续
       agentPaused,
       continueHint,
       handleContinue,
       // 长任务可控：后台任务停止
       runningTasks,
       taskTypeName,
       stoppingTasks,
       handleCancelTask,
       modifiedFiles,
       fileChangeLabel,
       createdFiles,
       showModifiedPopup,
       showNewPopup,
       toggleModifiedPopup,
       toggleNewPopup,
       handleOpenFile
    }
  }
}
</script>

<style scoped>
.chat-interface {
  display: flex;
  flex-direction: column;
  height: 100%;
  width: 100%;
  max-width: 100%;
  background: var(--awd-bg);
  position: relative;
  overflow: hidden; /* Prevent children from overflowing */
  box-sizing: border-box;
}

.chat-header {
  height: 36px;
  border-bottom: 1px solid var(--awd-border);
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 0 16px;
  background: var(--awd-bg);
  flex-shrink: 0;
}

.header-left {
  flex: 1;
  min-width: 0;
  margin-right: 8px;
}

.header-left .project-name-display {
  display: block;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-weight: 600;
  color: var(--awd-text);
}

.header-actions {
  display: flex;
  flex-shrink: 0;
  gap: 12px;
  position: relative;
}
.memory-header-btn {
  align-self: center;
  white-space: nowrap;
  padding: 4px 7px;
  border-radius: 5px;
  color: var(--awd-text-2);
  font-size: 11px;
  cursor: pointer;
}
.memory-header-btn:hover { background: var(--awd-surface); color: var(--awd-accent-text); }

/* Wrapper for icon buttons that have dropdowns - prevents layout shift */
.icon-btn-wrapper {
  position: relative;
  display: flex;
  align-items: center;
}

.icon-btn {
  cursor: pointer;
  padding: 6px;
  border-radius: 6px;
  color: var(--awd-text-2);
  display: flex;
  align-items: center;
  justify-content: center;
  position: relative;
  transition: background 0.15s ease;
}
.icon-btn .btn-icon {
  width: 15px;
  height: 15px;
  display: block;
  object-fit: contain;
}
.icon-btn .btn-icon.hover {
  display: none;
}
.icon-btn:hover {
  background: var(--awd-surface);
}
.icon-btn:hover .btn-icon.default {
  display: none;
}
.icon-btn:hover .btn-icon.hover {
  display: block;
}
/* Prevent layout shift when active */
.icon-btn.active {
  background: var(--awd-accent-soft);
  border-radius: 6px;
}
.icon-btn.active .btn-icon.default {
  display: none;
}
.icon-btn.active .btn-icon.hover {
  display: block;
}
.icon-btn.mini {
  padding: 4px;
}
.icon-btn.mini .btn-icon {
  width: 14px;
  height: 14px;
}
/* 加号用内联 SVG（描边风格与发送键一致），hover 走 currentColor 变绿，不再双位图切换 */
.icon-btn .plus-svg {
  width: 15px;
  height: 15px;
  display: block;
  color: var(--awd-text-2);
  transition: color 0.15s ease;
}
.icon-btn.mini .plus-svg {
  width: 14px;
  height: 14px;
}
.icon-btn:hover .plus-svg {
  color: var(--awd-accent-text);
}
/* File add button with border */
.icon-btn.file-add-btn {
  border: 1px solid var(--awd-border);
  border-radius: 6px;
  padding: 3px;
}
.icon-btn.file-add-btn:hover {
  border-color: var(--awd-accent);
}

.message-area {
  position: relative; /* 钢琴键展开层的包含块，见 ChatTurnRail.vue 的定位契约 */
  display: flex;
  flex: 1;
  min-height: 0;
}

.message-list {
  flex: 1;
  min-height: 0;
  overflow-anchor: none;
  overflow-y: auto;
  overflow-x: hidden; /* Prevent horizontal overflow */
  padding: 12px;
  min-width: 0; /* Allow flex shrinking */
  width: 100%;
  box-sizing: border-box;
}

.message-list-content {
  padding-bottom: 20px;
  max-width: 100%; /* Use full available width */
  width: 100%;
  box-sizing: border-box;
  min-width: 0; /* Allow flex shrinking */
  overflow: hidden; /* Prevent children from overflowing */
}

.conversation-turn { margin-bottom: 18px; }
.return-to-latest button::after { border: 0; }
.return-to-latest { position: relative; flex-shrink: 0; height: 0; z-index: 5; }
.return-to-latest .locator-row {
  position: absolute;
  bottom: 10px;
  left: 50%;
  transform: translateX(-50%);
  display: flex;
  gap: 8px;
  align-items: center;
}
.return-to-latest button {
  border: 1px solid var(--awd-border);
  border-radius: 20px;
  padding: 5px 14px;
  color: var(--awd-accent-text);
  background: var(--awd-surface);
  box-shadow: 0 2px 8px rgba(0, 0, 0, .08);
  font-size: 12px;
  line-height: 1.6;
  white-space: nowrap;
  cursor: pointer;
}
.return-to-latest button.attention-locator {
  border-color: var(--awd-warning);
  color: var(--awd-warning-text);
  background: var(--awd-warning-soft);
}
.message-row {
  margin-bottom: 14px;
  display: flex;
  flex-direction: column;
  width: 100%;
  max-width: 100%;
  box-sizing: border-box;
  /* overflow: hidden; Prevent children from overflowing */
}

.message-row.user {
  align-items: flex-end;
}

.user-bubble {
  background: var(--awd-accent-soft); /* AI WorkDeck品牌色 Lightest */
  padding: 8px 12px;
  border-radius: 6px 6px 0 6px;
  max-width: 80%;
  color: var(--awd-text); /* Gray-Dark for text */
  font-size: 13px;
  line-height: 1.5;
  box-shadow: none;
  border: 1px solid var(--awd-border);
  word-wrap: break-word;
  overflow-wrap: break-word;
  box-sizing: border-box;
  user-select: text;
  -webkit-user-select: text;
  position: relative;
}

.assistant-root-wrapper {
  width: 100%;
  max-width: 100%; /* Use full available width */
  min-width: 0; /* Critical: Allow flex shrinking */
  box-sizing: border-box;
  overflow: hidden; /* Prevent children from overflowing */
  user-select: text; /* Allow text selection for copying */
  -webkit-user-select: text;
}

.bubble-timestamp {
  font-size: 11px;
  color: var(--awd-text-3);
  /* margin-top: 4px; */
}
.user-bubble .bubble-timestamp { text-align: right; }

/* 送达状态（dev-board#779 K7②）：模型还没读到的插话先淡一档，让它和已经被读过的
   消息一眼分得开；一行小字说明它在等什么。浅色外壳不变，只降不透明度。 */
.user-bubble.is-unread {
  opacity: 0.62;
  border-style: dashed;
}
.bubble-receipt {
  margin-right: 8px;
  font-size: 11px;
  color: var(--awd-text-3);
  white-space: nowrap;
}
.bubble-receipt.is-pending { color: var(--awd-text-2); }

/* 从待处理区「在对话中查看」跳过来时闪一下（K7③）。跳转目标是 .message-row，
   由本组件渲染，所以样式必须写在这里——RootBubble 那套 chat-attention-flash
   的 scoped 选择器匹配不到这一层。 */
.message-row.chat-inbox-flash .user-bubble {
  animation: chat-inbox-flash 1.6s ease-out;
}
@keyframes chat-inbox-flash {
  0%, 60% { box-shadow: 0 0 0 2px var(--awd-accent); }
  100% { box-shadow: none; }
}
@media (prefers-reduced-motion: reduce) {
  .message-row.chat-inbox-flash .user-bubble { animation: none; box-shadow: 0 0 0 2px var(--awd-accent); }
}

/* Empty State & Input Styles */
.empty-flow-container {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  padding: 0 24px 24px;
  overflow-y: auto;
  min-width: 0;
  box-sizing: border-box;
}

.empty-top-section {
  /* 黄金分割位：38.2% from top，进一步上移 */
  margin-top: calc(30vh - 80px);
  flex-shrink: 0;
  margin-bottom: 28px;
  text-align: center;
}

.empty-middle-section {
  width: 100%;
  max-width: 600px;
  flex-shrink: 0;
  box-sizing: border-box;
}

.empty-bottom-section {
  width: 100%;
  max-width: 600px;
  flex-shrink: 0;
  margin-top: auto; /* Push to bottom */
  padding-top: 24px;
  box-sizing: border-box;
}

.welcome-text {
  font-size: 24px;
  font-weight: 600;
  color: var(--awd-text);
  margin-bottom: 8px;
  display: block;
}

.welcome-subtitle {
  font-size: 15px;
  font-weight: 400;
  color: var(--awd-text-2);
  display: block;
}

.input-card {
  background: var(--awd-surface);
  border: 1px solid var(--awd-border);
  border-radius: 12px;
  padding: 16px;
  width: 100%;
  box-sizing: border-box;
  box-shadow: 0 1px 2px rgba(18, 52, 77, 0.04), 0 4px 16px rgba(18, 52, 77, 0.06);
  position: relative;
  transition: border-color 0.15s ease, box-shadow 0.15s ease;
}
/* 拖拽悬停时的落点提示：虚线描边 + 淡底，告诉用户松手就进上下文 */
.input-card.is-drop-target {
  border-color: var(--awd-accent);
  border-style: dashed;
  background: var(--awd-accent-soft);
}

/* 输入区获得焦点时整卡亮起：品牌绿描边 + mint 光晕（浅色，不做深色 chrome） */
.input-card:focus-within {
  border-color: var(--awd-accent);
  box-shadow: 0 0 0 3px rgba(137, 168, 160, 0.16), 0 1px 2px rgba(18, 52, 77, 0.04), 0 4px 16px rgba(18, 52, 77, 0.06);
}

/* Recent History Section - 紧凑专业样式 */
.recent-history-header {
  font-size: 12px;
  font-weight: 500;
  color: var(--awd-text-3);
  margin-bottom: 8px;
  text-transform: uppercase;
  letter-spacing: 0.5px;
}

.recent-history {
  display: flex;
  flex-direction: column;
  gap: 0; /* 无间距 */
  border: 1px solid var(--awd-border);
  border-radius: 4px; /* 减小圆角 */
  overflow: hidden;
  background: var(--awd-surface);
}

/* 会话后台任务状态点（与宿主抽屉同一套视觉）：
   动画绿=运行中、黄=等用户（暂停/待审批）、蓝=后台跑完未读、红=出错 */
.conv-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  flex-shrink: 0;
  margin-right: 6px;
}
.conv-dot.dot-running { background: var(--awd-mint); animation: conv-dot-pulse 1.2s ease-in-out infinite; }
.conv-dot.dot-attention { background: var(--awd-warning); }
.conv-dot.dot-unread { background: var(--awd-info); }
.conv-dot.dot-error { background: var(--awd-danger); }
.conv-dot.header-dot {
  position: absolute;
  top: 2px;
  right: 2px;
  width: 7px;
  height: 7px;
  margin: 0;
  border: 1px solid var(--awd-surface);
}
@keyframes conv-dot-pulse {
  0%, 100% { opacity: 1; transform: scale(1); }
  50% { opacity: 0.45; transform: scale(0.8); }
}

.history-item {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 10px 14px;
  background: var(--awd-surface);
  border-bottom: 1px solid var(--awd-border-subtle);
  border-radius: 0; /* 无圆角 */
  cursor: pointer;
  transition: background 0.15s ease;
  box-shadow: none;
}

.history-item:last-child {
  border-bottom: none;
}

.history-item:hover {
  background: var(--awd-accent-soft);
}

.history-title {
  font-size: 13px;
  color: var(--awd-text);
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-weight: 500;
  text-align: left;
}

.history-time {
  font-size: 11px;
  color: var(--awd-text-3);
  margin-left: 12px;
  flex-shrink: 0;
  text-align: right;
  min-width: 60px;
}

.history-empty-placeholder {
  font-size: 13px;
  color: var(--awd-text-3);
  text-align: center;
  padding: 24px 0;
}

.history-disclaimer {
  font-size: 12px;
  color: var(--awd-text-3);
  text-align: center;
  padding: 16px 0 0;
  margin-top: 12px;
  border-top: 1px solid var(--awd-border-subtle);
}

.chat-input-rich {
  min-height: 60px;
  max-height: 200px;
  overflow-y: auto;
  outline: none;
  font-size: 15px;
  line-height: 1.5;
  color: var(--awd-text);
}

.chat-input-rich:empty:before {
  content: attr(data-placeholder);
  color: var(--awd-text-3);
}

.input-footer {
  display: flex;
  justify-content: space-between;
  align-items: center;
  flex-wrap: wrap;
  column-gap: 8px;
  row-gap: 6px;
  margin-top: 12px;
  padding-top: 12px;
  border-top: 1px solid var(--awd-border-subtle);
}

.action-bar-left {
  display: flex;
  gap: 8px;
  align-items: center;
  flex: 1 1 140px;
  /* 允许整条工具栏收缩，避免钉选长名 Skill 时把发送按钮挤出面板 */
  min-width: 0;
}

.model-selector {
  font-size: 13px;
  color: var(--awd-text-2);
  cursor: pointer;
  /* 定位基准挪给 .input-card（见 .model-dropdown 注释）——AI 面板最窄 240px，
     锚在这个只有内容宽的选择器上，下拉框固定 min-width 无论往哪边对齐都会被
     .chat-interface 的 overflow:hidden 裁掉一截。 */
  position: static;
  display: flex;
  align-items: center;
  gap: 4px;
  height: 24px;
  box-sizing: border-box;
  padding: 0 6px;
  border: 1px solid transparent;
  border-radius: 6px;
  transition: background 0.15s ease, border-color 0.15s ease;
  white-space: nowrap;
}
.model-selector:hover {
  background: var(--awd-accent-wash);
  border-color: var(--awd-accent-soft);
}

.dropdown-arrow {
  font-size: 8px;
  color: var(--awd-text-3);
  transition: color 0.15s ease;
}
.model-selector:hover .dropdown-arrow {
  color: var(--awd-text-2);
}

/* .model-dropdown 及其内部样式随组件搬到 ModelSelectorDropdown.vue（dev-board#853） */

/* ============= Mode Selector (Agent/Ask/Plan) ============= */
.mode-selector {
  font-size: 13px;
  color: var(--awd-text-2);
  cursor: pointer;
  /* 同 .model-selector：定位基准挪给 .input-card。实测在 240px 最窄面板下，
     min-width:160px 的下拉锚在这个只有内容宽（约 60px）的选择器上，右边缘
     恰好顶着 .chat-interface 的裁切边界、零余量——字体渲染或文案稍长一点
     就会被裁掉一截。 */
  position: static;
  display: flex;
  align-items: center;
  gap: 4px;
  height: 24px;
  box-sizing: border-box;
  padding: 0 8px;
  border-radius: 6px;
  background: var(--awd-accent-wash);
  border: 1px solid var(--awd-accent-soft);
  transition: all 0.15s ease;
}
.mode-selector:hover {
  background: var(--awd-accent-soft);
  border-color: var(--awd-accent);
}

.mode-icon {
  font-size: 14px;
}

.mode-name {
  font-weight: 500;
  color: var(--awd-accent-text);
}

.mode-dropdown {
  position: absolute;
  left: 0;
  right: 0;
  background: var(--awd-surface);
  border: 1px solid var(--awd-border);
  border-radius: 10px;
  box-shadow: 0 4px 20px rgba(0, 0, 0, 0.15);
  z-index: 1001;
  min-width: 0;
  padding: 6px 0;
}

.mode-dropdown.down {
  top: calc(100% + 6px);
}

.mode-dropdown.up {
  bottom: calc(100% + 6px);
}

.mode-option {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 14px;
  cursor: pointer;
  transition: background 0.1s ease;
}
.mode-option:hover {
  background: var(--awd-surface-2);
}
.mode-option.active {
  background: var(--awd-accent-soft);
}
.mode-option.active .mode-option-name {
  color: var(--awd-accent-text);
  font-weight: 600;
}

.mode-option-icon {
  font-size: 18px;
}

.mode-option-text {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.mode-option-name {
  font-size: 13px;
  font-weight: 500;
  color: var(--awd-text);
}

.mode-option-desc {
  font-size: 11px;
  color: var(--awd-text-3);
}

/* 本地供应商（Ollama）只剩 Ask 时的说明行 */
.mode-note {
  border-top: 1px solid var(--awd-border-subtle);
  margin-top: 4px;
  padding: 6px 14px 2px;
  font-size: 10px;
  color: var(--awd-text-3);
  line-height: 1.5;
  max-width: 200px;
}

.dropdown-menu {
  position: absolute;
  top: 100%;
  right: 0;
  background: var(--awd-surface);
  border: 1px solid var(--awd-border);
  border-radius: 10px;
  box-shadow: 0 8px 24px rgba(0, 0, 0, 0.12), 0 2px 8px rgba(0, 0, 0, 0.08);
  z-index: 1000;
  min-width: 240px;
  padding: 8px 0;
  margin-top: 4px;
}

@keyframes dropdownFadeIn {
  from {
    opacity: 0;
    transform: translateY(-4px);
  }
  to {
    opacity: 1;
    transform: translateY(0);
  }
}

.menu-label {
  padding: 6px 12px;
  font-size: 11px;
  font-weight: 600;
  color: var(--awd-text-2);
  background: var(--awd-bg);
  border-bottom: 1px solid var(--awd-border-subtle);
}

.menu-item {
  padding: 10px 12px;
  cursor: pointer;
  display: flex;
  justify-content: space-between;
  align-items: center;
  font-size: 13px;
  color: var(--awd-text);
  transition: all 0.15s ease;
  border-bottom: 1px solid var(--awd-border-subtle);
}
.menu-item:hover {
  background: var(--awd-surface-2);
  color: var(--awd-accent-text);
}
.menu-item.active {
  background: var(--awd-accent-soft);
  color: var(--awd-accent-text);
  font-weight: 500;
}

/* Menu item name (takes up flex space) */
.menu-item-name {
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.dropdown-mask {
  position: fixed;
  top: 0;
  left: 0;
  right: 0;
  bottom: 0;
  z-index: 999;
  background: transparent;
}

/* 发送 / 停止改成真 <button>（K16 ④）：能 Tab 到、能回车按。uni-h5 的 button 自带
   一套默认样式与 ::after 边框，这里整片打平——照 .return-to-latest button 的既有写法。 */
.send-btn,
.stop-btn {
  appearance: none;
  -webkit-appearance: none;
  border: none;
  padding: 0;
  margin: 0;
  font: inherit;
  line-height: 1;
  overflow: visible;
}
.send-btn::after,
.stop-btn::after { border: 0; }

.send-btn {
  background: var(--awd-accent);
  color: var(--awd-text-on-accent);
  width: 32px;
  height: 32px;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  transition: background 0.15s ease;
  flex-shrink: 0;
}
.composer-actions { display: flex; align-items: center; gap: 6px; flex-shrink: 0; margin-left: auto; max-width: 100%; }
.centered-style .composer-actions {
  flex-basis: calc(100% - 56px);
  justify-content: flex-end;
  margin-right: 56px;
}
.follow-mode,.alternate-send { padding: 4px 6px; border-radius: 5px; color: var(--awd-text-2); font-size: 10px; cursor: pointer; }
.follow-mode { background: var(--awd-accent-soft); color: var(--awd-accent-text); }
.alternate-send:hover { background: var(--awd-surface-2); }
.stop-btn { width: 28px; height: 28px; display: flex; align-items: center; justify-content: center; border-radius: 50%; background: var(--awd-danger); color: white; font-size: 11px; cursor: pointer; }
.send-btn:hover {
  background: var(--awd-accent-hover);
}
.send-btn.disabled {
  background: var(--awd-surface-3);
  color: var(--awd-text-3);
  cursor: not-allowed;
}
.send-btn.disabled:hover {
  background: var(--awd-surface-3);
}
.send-btn.stopping {
  background: var(--awd-danger);
}
.send-btn.stopping:hover {
  background: var(--awd-danger);
}
.send-icon {
  font-size: 16px;
  font-weight: bold;
  display: inline-block;
}

.input-area-wrapper {
  padding: 12px 16px 16px;
  background: var(--awd-surface);
  border-top: 1px solid var(--awd-border);
  display: flex;
  flex-direction: column;  /* Fix: Stack children vertically */
  align-items: stretch;    /* Fix: Make children full width */
  flex-shrink: 0;
  min-width: 0;
  box-sizing: border-box;
}

/* 插件镜像会话只读条（dev-board#298） */
.readonly-bar {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 12px;
  background: var(--awd-accent-wash);
  border: 1px solid var(--awd-border);
  border-radius: 8px;
}
.readonly-text {
  flex: 1;
  min-width: 0;
  font-size: 12px;
  line-height: 18px;
  color: var(--awd-text-2);
}
.readonly-fork-btn {
  flex-shrink: 0;
  padding: 5px 12px;
  font-size: 12px;
  color: var(--awd-text-on-accent);
  background: var(--awd-accent);
  border-radius: 6px;
  cursor: pointer;
}
.readonly-fork-btn:hover {
  background: var(--awd-accent-hover);
}

/* Context Files Styles */
.context-files-list {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin: 8px 0;
  padding: 8px 0;
  border-bottom: 1px solid var(--awd-border-subtle);
}

.context-file-tag {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  background: var(--awd-bg);
  border: 1px solid var(--awd-info);
  border-radius: 14px;
  padding: 4px 8px 4px 6px;
  font-size: 12px;
  color: var(--awd-info-text);
}

.context-file-icon {
  font-size: 12px;
}

.context-file-name {
  max-width: 120px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.context-file-remove {
  margin-left: 4px;
  color: var(--awd-text-3);
  cursor: pointer;
  font-size: 14px;
  line-height: 1;
}

.context-file-remove:hover {
  color: var(--awd-danger-text);
}

/* =============================================
   AI WorkDeck Style - Input Image Preview
   ============================================= */
.input-images-preview {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: 12px;
  padding-bottom: 8px;
}

/* flex-basis 100% 让它在缩略图行下面另起一行，紧贴着图走（预览区自己的
   margin-bottom 在整块之外，说明与图之间只隔容器的 gap） */
.input-images-note {
  flex-basis: 100%;
  font-size: 11px;
  line-height: 1.5;
  color: var(--awd-text-3);
  /* 从缩略图块里挪到输入卡片底部常驻（K21 ⑨）：拖进来的项目图片也要覆盖，
     而它们不在 pastedImages 里。display:block 是因为现在它是卡片的直接子元素。 */
  display: block;
  padding: 2px 2px 0;
}

/* 当前文档 chip（K14 ①）：与 @ 附件标签同一行高，但刻意不同色——
   一个是「系统自动带上的」，一个是「你自己挑的」，看一眼要能分清。 */
.active-doc-chip {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  max-width: 100%;
  margin: 0 0 4px;
  padding: 2px 6px 2px 8px;
  border-radius: 4px;
  border: 1px dashed var(--awd-border);
  background: var(--awd-bg-2);
  font-size: 11px;
  line-height: 1.6;
  color: var(--awd-text-2);
}
.active-doc-label {
  color: var(--awd-text-3);
  flex-shrink: 0;
}
.active-doc-name {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: var(--awd-text-1);
}
.active-doc-remove {
  flex-shrink: 0;
  padding: 0 2px;
  color: var(--awd-text-3);
  cursor: pointer;
  font-size: 13px;
  line-height: 1;
}
.active-doc-remove:hover { color: var(--awd-text-1); }

/* 历史里那一轮带过的附件（K14 ④）：只在历史气泡上出现——
   手打输入那条路的附件是正文里的内联标签，再挂一排就是显示两遍。 */
.bubble-attachments {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
  margin-top: 6px;
}
.bubble-attachment {
  font-size: 11px;
  line-height: 1.6;
  padding: 0 6px;
  border-radius: 4px;
  border: 1px solid var(--awd-border);
  color: var(--awd-text-2);
  max-width: 100%;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* 本轮附件的降级/截断/丢弃（K21 ⑦）：一行小字挂在用户气泡下方。
   刻意不做成警告色——降级是后端自动完成的正常路径，用户只是有权知道。 */
.context-notices {
  display: flex;
  flex-direction: column;
  gap: 2px;
  margin-top: 4px;
}
.context-notice {
  font-size: 11px;
  line-height: 1.5;
  color: var(--awd-text-3);
  text-align: left;
}

.preview-image-item {
  position: relative;
  width: 48px;
  height: 48px;
  border-radius: 8px;
  overflow: hidden;
  box-shadow: 0 2px 6px rgba(46, 90, 80, 0.15);
}

.preview-thumb {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.preview-remove {
  position: absolute;
  top: -4px;
  right: -4px;
  width: 18px;
  height: 18px;
  background: linear-gradient(135deg, var(--awd-accent) 0%, var(--awd-accent-hover) 100%);
  color: var(--awd-text-on-accent);
  border-radius: 50%;
  font-size: 12px;
  text-align: center;
  line-height: 18px;
  cursor: pointer;
  box-shadow: 0 1px 3px rgba(46, 90, 80, 0.3);
  transition: all 0.15s ease;
}

.preview-remove:hover {
  background: linear-gradient(135deg, var(--awd-accent-hover) 0%, var(--awd-accent) 100%);
  transform: scale(1.1);
}

 /* =============================================
    AI WorkDeck Style - Inline Context Tags (Input Box)
    Transparent background + border style
    ============================================= */
 :deep(.context-tag-inline) {
   display: inline-flex;
   align-items: center;
   gap: 3px;
   background: transparent;
   color: var(--awd-accent-text);
   padding: 3px 8px;
   border-radius: 4px;
   margin: 0 4px 2px 0;
   font-size: 12px;
   font-weight: 500;
   vertical-align: middle;
   user-select: none;
   max-width: 160px;
   border: 1px solid var(--awd-accent);
   transition: all 0.15s ease;
   position: relative;
 }

 /* 上一轮带过的附件（K14 ③）。淡态只是「这是上一轮的」，它**仍然会继续带上**——
    点标签本体确认沿用（回到常态），点 × 移除。 */
 :deep(.context-tag-inline.is-carried) {
   opacity: 0.55;
   border-style: dashed;
 }
 :deep(.context-tag-inline.is-carried:hover) {
   opacity: 1;
 }

 :deep(.context-tag-inline:hover) {
   background: var(--awd-accent-soft);
   border-color: var(--awd-accent);
   padding-right: 22px; /* Make room for close button */
 }

 :deep(.tag-icon) {
   width: 14px;
   height: 14px;
   flex-shrink: 0;
   border-radius: 2px;
   filter: brightness(0.3);
 }

 :deep(.tag-at) {
   color: var(--awd-accent-text);
   font-weight: 600;
 }

 :deep(.tag-name) {
   white-space: nowrap;
   overflow: hidden;
   text-overflow: ellipsis;
   max-width: 100px;
   color: var(--awd-accent-text);
 }

 :deep(.tag-close) {
   display: none;
   position: absolute;
   right: 6px;
   top: 50%;
   transform: translateY(-50%);
   width: 14px;
   height: 14px;
   background: var(--awd-accent-soft);
   color: var(--awd-accent-text);
   border-radius: 50%;
   align-items: center;
   justify-content: center;
   font-size: 10px;
   cursor: pointer;
   transition: all 0.1s ease;
 }

 :deep(.context-tag-inline:hover .tag-close) {
   display: flex;
 }

 :deep(.tag-close:hover) {
   background: var(--awd-accent);
   color: var(--awd-text-on-accent);
 }

 /* =============================================
    AI WorkDeck Style - Inline Context Tags (User Bubble)
    Lighter/transparent background for visibility
    ============================================= */
.user-bubble .context-tag-inline {
  display: inline-flex;
  align-items: center;
  gap: 3px;
  background: transparent;
  color: var(--awd-accent-text);
  padding: 3px 8px;
  border-radius: 4px;
  margin: 0 4px 2px 0;
  font-size: 12px;
  font-weight: 500;
  vertical-align: middle;
  user-select: none;
  max-width: 160px;
  border: 1px solid var(--awd-accent);
  transition: all 0.15s ease;
}

.user-bubble .tag-icon {
  width: 14px;
  height: 14px;
  flex-shrink: 0;
  border-radius: 2px;
  /* Ensure icon is visible on light background */
  filter: brightness(0.2);
}

.user-bubble .tag-at {
  color: var(--awd-accent-text);
  font-weight: 600;
}

.user-bubble .tag-name {
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  max-width: 100px;
  color: var(--awd-accent-text);
}

/* =============================================
   User Bubble - Image Thumbnails
   ============================================= */
.user-bubble-images {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: 10px;
}

.bubble-image-thumb {
  width: 80px;
  height: 80px;
  border-radius: 8px;
  object-fit: cover;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.1);
}

/* Improve user bubble content display for inline tags */
.user-bubble-content {
  line-height: 1.5;
  word-wrap: break-word;
  overflow-wrap: break-word;
  font-size: 13px;
  white-space: pre-wrap; /* Preserve newlines and spaces */
}

/* =============================================
   Skill：本轮生效清单（chip 行）+ 主动选择（多选下拉）
   ============================================= */
/* chip 行占一行、不换行、横向滚动——输入框的高度是稀缺资源，
   装了六个技能也不该把输入区顶掉半屏 */
.skill-chip-row {
  display: flex;
  flex-wrap: nowrap;
  align-items: center;
  column-gap: 6px;
  overflow-x: auto;
  padding: 2px 2px 4px;
  /* 滚动条在这一行里比内容还高，藏掉 */
  scrollbar-width: none;
}
.skill-chip-row::-webkit-scrollbar {
  display: none;
}

.skill-chip {
  display: flex;
  align-items: center;
  column-gap: 4px;
  flex-shrink: 0;
  height: 20px;
  padding: 0 7px;
  border-radius: 10px;
  background: var(--awd-accent-soft);
  border: 1px solid var(--awd-accent-soft);
}
/* 自动命中的用描边 + 更浅的底：与"我自己选的"在一行里要能一眼分开 */
.skill-chip.auto {
  background: transparent;
  border-style: dashed;
  border-color: var(--awd-accent);
}

.skill-chip-name {
  font-size: 11px;
  color: var(--awd-accent-text);
  line-height: 1;
  white-space: nowrap;
}

.skill-chip-remove {
  font-size: 12px;
  line-height: 1;
  color: var(--awd-accent-text);
  cursor: pointer;
}
.skill-chip-remove:hover {
  color: var(--awd-accent-text);
}

/* 新自动命中的技能闪几秒：用户只是说了句话就被加载了一个技能，得让他看见 */
.skill-chip.flash {
  animation: skillChipFlash 1.1s ease-in-out 3;
}
@keyframes skillChipFlash {
  0%, 100% {
    background: transparent;
    border-color: var(--awd-accent);
    box-shadow: none;
  }
  50% {
    background: var(--awd-accent-soft);
    border-color: var(--awd-mint);
    box-shadow: 0 0 0 2px rgba(137, 168, 160, 0.18);
  }
}

/* AI 面板窄，工具条已有模式/模型两个文字选择器，故 Skill 用定宽图标按钮，
   已选数量用角标表达，生效清单在上方 chip 行，不占横向空间 */
.skill-selector {
  cursor: pointer;
  position: relative;
  display: flex;
  align-items: center;
  justify-content: center;
  column-gap: 2px;
  min-width: 24px;
  height: 24px;
  padding: 0 3px;
  border-radius: 6px;
  transition: background 0.15s ease;
  flex-shrink: 0;
}
.skill-selector:hover {
  background: var(--awd-accent-wash);
}

/* 已选态：绿色实心底 + 计数，让"这轮我额外加载了 N 个技能"一眼可见 */
.skill-selector.pinned {
  background: var(--awd-accent-soft);
}
.skill-selector.pinned .skill-glyph-svg {
  color: var(--awd-accent-text);
}
/* ASK 模式下 skill 不生效，按钮压暗——下拉仍可打开，里面会说明为什么不能选 */
.skill-selector.muted .skill-glyph-svg {
  opacity: 0.45;
}

.skill-count {
  font-size: 10px;
  line-height: 1;
  color: var(--awd-accent-text);
  font-weight: 600;
}

.skill-glyph-svg {
  width: 16px;
  height: 16px;
  color: var(--awd-text-2);
  flex-shrink: 0;
}
.skill-selector:hover .skill-glyph-svg {
  color: var(--awd-text);
}

.skill-dropdown {
  position: absolute;
  /* 选择器位于工具条最右，向右展开会溢出 AI 面板，故右对齐 */
  right: 0;
  background: var(--awd-surface);
  border: 1px solid var(--awd-border);
  border-radius: 8px;
  box-shadow: 0 4px 16px rgba(0, 0, 0, 0.12);
  z-index: 1001;
  min-width: 230px;
  max-height: 280px;
  overflow-y: auto;
  padding: 4px 0;
}
.skill-dropdown.down {
  top: calc(100% + 4px);
}
.skill-dropdown.up {
  bottom: calc(100% + 4px);
}

.skill-dropdown-head {
  padding: 7px 12px 5px;
  display: flex;
  flex-direction: column;
  row-gap: 2px;
}
.skill-dropdown-title {
  font-size: 12px;
  color: var(--awd-text);
  font-weight: 500;
}
.skill-dropdown-hint {
  font-size: 11px;
  color: var(--awd-text-3);
  white-space: normal;
}

.skill-option {
  padding: 7px 12px;
  cursor: pointer;
  transition: background 0.15s ease;
  display: flex;
  align-items: flex-start;
  column-gap: 6px;
}
.skill-option:hover {
  background: var(--awd-surface-2);
}
.skill-option.active {
  background: var(--awd-accent-soft);
}
.skill-option.muted {
  opacity: 0.5;
  cursor: default;
}

/* 定宽勾选位：勾与不勾的行文字必须左对齐，否则勾一下整列会跳 */
.skill-check {
  width: 12px;
  flex-shrink: 0;
  font-size: 12px;
  line-height: 18px;
  color: var(--awd-accent-text);
}

.skill-empty {
  padding: 7px 12px;
  font-size: 12px;
  color: var(--awd-text-3);
}

.skill-option-text {
  display: flex;
  flex-direction: column;
  row-gap: 2px;
  min-width: 0;
}

.skill-option-name {
  font-size: 13px;
  color: var(--awd-text);
}
.skill-option.active .skill-option-name {
  color: var(--awd-accent-text);
  font-weight: 500;
}

.skill-option-desc {
  font-size: 11px;
  color: var(--awd-text-3);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  max-width: 200px;
}

.skill-divider {
  height: 1px;
  background: var(--awd-bg);
  margin: 4px 0;
}

.skill-manage {
  padding: 7px 12px;
  font-size: 12px;
  color: var(--awd-text-2);
  cursor: pointer;
  transition: background 0.15s ease;
}
.skill-manage:hover {
  background: var(--awd-surface-2);
  color: var(--awd-accent-text);
}

/* Model dropdown mask overlay */
.dropdown-mask.model-mask {
  position: fixed;
  top: 0;
  left: 0;
  right: 0;
  bottom: 0;
  z-index: 199;
  background: transparent;
}

/* =============================================
   AI WorkDeck Style - Upload Dialog Styles
   ============================================= */
.awd-dialog-mask {
  position: fixed;
  top: 0;
  left: 0;
  right: 0;
  bottom: 0;
  background-color: var(--awd-overlay);
  backdrop-filter: blur(2px);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 2000;
}

.awd-dialog {
  width: 618px; /* Golden Ratio-ish Width */
  max-width: 90vw;
  background-color: var(--awd-surface);
  border-radius: 12px;
  box-shadow: 0 20px 25px -5px rgba(0, 0, 0, 0.1), 0 10px 10px -5px rgba(0, 0, 0, 0.04);
  overflow: hidden;
  animation: awd-dialog-in 0.2s cubic-bezier(0.16, 1, 0.3, 1);
  display: flex;
  flex-direction: column;
  box-sizing: border-box;
}

.awd-dialog * {
  box-sizing: border-box;
}

.awd-dialog-large {
  width: 750px; /* Wider for Upload */
}

@keyframes awd-dialog-in {
  from {
    opacity: 0;
    transform: scale(0.96) translateY(10px);
  }
  to {
    opacity: 1;
    transform: scale(1) translateY(0);
  }
}

.awd-dialog-header {
  padding: 24px 24px 16px;
  flex-shrink: 0;
  border-bottom: 1px solid var(--awd-border-subtle);
}

.awd-dialog-header .header-row {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

/* Header with New Folder Button */
.folder-selector-header {
  flex-direction: row !important;
  justify-content: space-between;
  align-items: center;
}

.new-folder-btn {
  display: flex;
  align-items: center;
  gap: 4px;
  font-size: 13px;
  color: var(--awd-accent-text);
  cursor: pointer;
  padding: 4px 8px;
  border-radius: 4px;
  transition: background 0.15s ease;
}

.new-folder-btn:hover {
  background: var(--awd-accent-soft);
}

.new-folder-btn .btn-plus {
  font-size: 16px;
  font-weight: bold;
  line-height: 1;
}

.awd-dialog-title {
  font-size: 20px;
  font-weight: 600;
  color: var(--awd-accent-text); /* Forest Green */
  line-height: 1.4;
  display: block;
}

.awd-dialog-subtitle {
  margin-top: 6px;
  font-size: 13px;
  color: var(--awd-text-2);
  line-height: 1.5;
  display: block;
}

.awd-dialog-body {
  padding: 0 24px 24px;
  flex: 1;
  min-height: 0;
  /* Add top padding for content separation */
  padding-top: 20px;
}

.awd-dialog-body.scrollable-body {
  max-height: 400px;
  overflow-y: auto;
  padding-top: 0; /* Remove top padding for list */
}

.awd-dialog-footer {
  display: flex;
  align-items: center;
  justify-content: center; /* Centered as requested */
  gap: 16px;
  padding: 24px;
  background-color: transparent;
  flex-shrink: 0;
  border-top: 1px solid var(--awd-border-subtle);
}

.awd-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  height: 44px;
  padding: 0 32px;
  font-size: 15px;
  font-weight: 500;
  border-radius: 8px;
  cursor: pointer;
  transition: all 0.2s;
  min-width: 100px;
  border: none;
}

.awd-btn:active {
  transform: translateY(1px);
}

.awd-btn-primary {
  background-color: var(--awd-accent); /* Forest Green */
  color: var(--awd-text-on-accent);
}
.awd-btn-primary:hover {
  background-color: var(--awd-accent-hover);
}

.awd-btn-primary.disabled {
  opacity: 0.5;
  pointer-events: none;
  background-color: var(--awd-accent); /* Maintain color but transparent */
}

.awd-btn-secondary {
  background-color: var(--awd-surface);
  color: var(--awd-text);
  border: 1px solid var(--awd-border);
}
.awd-btn-secondary:hover {
  background-color: var(--awd-bg);
  border-color: var(--awd-border);
}

.form-group {
  margin-bottom: 20px;
}
.form-group:last-child {
  margin-bottom: 0;
}

/* 「+」对话框的两个来源页签与「从项目选择」列表（dev-board#794 K15 ③） */
.pick-tabs {
  display: flex;
  gap: 4px;
  margin-top: 12px;
}
.pick-tab {
  padding: 5px 12px;
  border-radius: 6px;
  font-size: 13px;
  color: var(--awd-text-2);
  cursor: pointer;
}
.pick-tab:hover { background: var(--awd-surface-2); }
.pick-tab.active {
  background: var(--awd-accent-soft);
  color: var(--awd-accent-text);
  font-weight: 500;
}
.pick-search {
  width: 100%;
  height: 34px;
  padding: 0 10px;
  box-sizing: border-box;
  font-size: 13px;
  border: 1px solid var(--awd-border);
  border-radius: 6px;
  color: var(--awd-text);
}
.pick-list {
  max-height: 44vh;
  margin-top: 10px;
}
.pick-row {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 7px 8px;
  border-radius: 6px;
  cursor: pointer;
}
.pick-row:hover { background: var(--awd-surface-2); }
.pick-row.picked { cursor: default; opacity: 0.65; }
.pick-icon { width: 14px; height: 14px; flex-shrink: 0; }
.pick-name {
  font-size: 13px;
  color: var(--awd-text);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.pick-path {
  font-size: 11px;
  color: var(--awd-text-3);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  flex-shrink: 1;
}
.pick-done {
  margin-left: auto;
  font-size: 11px;
  color: var(--awd-accent-text);
  white-space: nowrap;
}
.pick-empty {
  padding: 22px 8px;
  text-align: center;
  font-size: 13px;
  color: var(--awd-text-3);
}

.form-label {
  display: block;
  font-size: 14px;
  font-weight: 500;
  color: var(--awd-text);
  margin-bottom: 8px;
}

.awd-field {
  background-color: var(--awd-bg);
  border: 1px solid var(--awd-border);
  border-radius: 8px;
  padding: 12px 16px;
  display: flex;
  align-items: center;
  min-height: 48px;
  transition: all 0.15s ease;
  gap: 12px;
}

.awd-field.clickable {
  cursor: pointer;
}

.awd-field.clickable:hover {
  background-color: var(--awd-accent-soft);
  border-color: var(--awd-mint);
}

.awd-field .field-icon-img {
  width: 20px;
  height: 20px;
  flex-shrink: 0;
}

.awd-field .field-value {
  font-size: 14px;
  color: var(--awd-text);
}

.awd-field .field-placeholder {
  font-size: 14px;
  color: var(--awd-text-3);
}

.selected-files-list {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.selected-file-tag {
  font-size: 12px;
  background: var(--awd-surface);
  padding: 4px 8px;
  border-radius: 4px;
  border: 1px solid var(--awd-border);
  color: var(--awd-text);
  max-width: 150px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* Folder Tree in Dialog */
.folder-tree-item {
  display: flex;
  align-items: center;
  padding: 10px 12px;
  margin-bottom: 2px;
  border-radius: 6px;
  cursor: pointer;
  transition: background 0.15s;
}

.folder-tree-item:hover {
  background-color: var(--awd-surface-2);
}

.folder-tree-item.active {
  background-color: var(--awd-accent-soft);
  color: var(--awd-accent-text);
}

.folder-tree-item .indent {
  flex-shrink: 0;
}

.tree-expand-icon-wrapper {
  width: 24px;
  height: 24px;
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
}

.tree-expand-icon-img {
  width: 10px;
  height: 10px;
}

.folder-icon-img {
  width: 18px;
  height: 18px;
  transition: transform 0.2s;
  flex-shrink: 0;
}
.folder-icon-img.is-opened {
  transform: scale(1.2);
}

.folder-name {
  margin-left: 8px;
  font-size: 14px;
  color: var(--awd-text);
}

.empty-tip {
  text-align: center;
  color: var(--awd-text-3);
  font-size: 13px;
  padding: 20px 0;
}
/* Token Usage Bar */
.token-usage-bar {
  display: flex;
  align-items: center;
  justify-content: space-between; /* Spread content if needed, or keeping it left aligned but full width */
  gap: 8px;
  padding: 4px 12px;
  margin-bottom: 8px; /* Maintain margin */
  background: linear-gradient(135deg, var(--awd-accent-wash) 0%, var(--awd-accent-wash) 100%);
  border-radius: 6px;
  border: 1px solid var(--awd-accent-soft);
  width: 100%; /* Fix: Full width */
  box-sizing: border-box; /* Fix: Include padding in width */
  height: 28px; /* Fix: Fixed low height */
}

.token-usage-bar .token-label {
  font-size: 11px;
  font-weight: 600;
  color: var(--awd-accent-text);
  text-transform: uppercase;
  letter-spacing: 0.5px;
}

.token-usage-bar .token-value {
  font-size: 12px;
  font-weight: 600;
  color: var(--awd-mint);
  flex: 1; /* Allow value to take space if needed */
  margin-left: 4px;
}

.token-usage-bar .token-detail {
  font-size: 10px;
  color: var(--awd-text-2);
}


/* 步数超限一键继续条 */
.continue-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin: 0 12px 6px;
  padding: 6px 12px;
  background: var(--awd-bg);
  border: 1px solid var(--awd-warning);
  border-radius: 8px;
}

.continue-hint {
  font-size: 11px;
  color: var(--awd-warning-text);
}

.continue-btn {
  flex-shrink: 0;
  font-size: 12px;
  font-weight: 600;
  color: var(--awd-text-on-accent);
  background: var(--awd-accent);
  border-radius: 6px;
  padding: 4px 14px;
  cursor: pointer;
  transition: background 0.2s;
}

.continue-btn:hover {
  background: var(--awd-accent-hover);
}

/* SSE 断连提示条：外形对齐 continue-bar，只有一行文字、没有按钮（重连是自动的） */
.link-bar {
  display: flex;
  align-items: center;
  margin: 0 12px 6px;
  padding: 6px 12px;
  background: var(--awd-bg);
  border: 1px solid var(--awd-warning);
  border-radius: 8px;
}

.link-hint {
  font-size: 11px;
  color: var(--awd-warning-text);
}

/* 音频附件未转写（dev-board#814）：外形与 link-bar 一组，用警示色——
   它说的是「你以为 AI 听了，其实没有」，这一条错过了，后面整段回答都是凭空的 */
.audio-transcribe-bar {
  display: flex;
  align-items: center;
  gap: 8px;
  margin: 0 12px 6px;
  padding: 6px 12px;
  background: var(--awd-bg);
  border: 1px solid var(--awd-warning);
  border-radius: 8px;
}

.audio-transcribe-hint {
  flex: 1;
  font-size: 11px;
  color: var(--awd-warning-text);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.audio-transcribe-btn {
  flex-shrink: 0;
  font-size: 11px;
  color: var(--awd-text);
  background: var(--awd-surface);
  border: 1px solid var(--awd-border);
  border-radius: 6px;
  padding: 3px 12px;
  cursor: pointer;
  transition: all 0.15s;
}

.audio-transcribe-btn:hover {
  border-color: var(--awd-mint);
  color: var(--awd-accent-text);
  background: var(--awd-accent-soft);
}

/* 后台任务控制条（停止）：外形对齐 continue-bar，但用中性底色——
   这不是「需要你处理」的黄色警示，只是一个随时可用的控制 */
.task-control-bar {
  display: flex;
  flex-direction: column;
  gap: 4px;
  margin: 0 12px 6px;
}

.task-control-row {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 5px 12px;
  background: var(--awd-bg);
  border: 1px solid var(--awd-border);
  border-radius: 8px;
}

.task-control-name {
  font-size: 11px;
  font-weight: 600;
  color: var(--awd-text);
  flex-shrink: 0;
}

.task-control-msg {
  flex: 1;
  font-size: 11px;
  color: var(--awd-text-2);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.task-control-btn {
  flex-shrink: 0;
  font-size: 11px;
  color: var(--awd-text);
  background: var(--awd-surface);
  border: 1px solid var(--awd-border);
  border-radius: 6px;
  padding: 3px 12px;
  cursor: pointer;
  transition: all 0.15s;
}

.task-control-btn:hover {
  border-color: var(--awd-mint);
  color: var(--awd-accent-text);
  background: var(--awd-accent-soft);
}

/* 已发出停止请求：按钮变成状态显示，不再可点（重复点只会多发无用请求） */
.task-control-btn.pending {
  cursor: default;
  color: var(--awd-text-2);
  background: var(--awd-surface-2);
  border-color: var(--awd-border);
}

/* Status Bar Row (File Changes + Tokens) */
.status-bar-row {
  display: flex;
  flex-direction: row;
  justify-content: space-between;
  align-items: center;
  /* 与下方输入卡对齐（卡自带描边），行距走 8 栅格 */
  padding: 0 2px 8px;
  background-color: transparent;
  font-size: 11px;
  z-index: 10;
  width: 100%;
  box-sizing: border-box;
}

.status-bar-left {
  display: flex;
  flex-direction: row;
  gap: 8px;
  align-items: center;
}

.status-bar-right {
  display: flex;
  flex-direction: row;
  align-items: center;
  gap: 4px;
  opacity: 0.6;
  font-size: 11px;
}

/* 本轮用量是一句说明，不是要盯着看的数字面板：去掉等宽加粗，与左侧改动/新增同重 */
.status-bar-right .token-value {
  font-family: inherit;
  font-weight: 400;
  color: var(--awd-text-2);
}

/* Status Buttons */
.status-btn-wrapper {
  position: relative;
}

.status-btn {
  display: flex;
  align-items: center;
  padding: 4px 12px;
  border-radius: 6px;
  background-color: var(--awd-surface);
  cursor: pointer;
  font-size: 11px;
  font-weight: 600;
  color: var(--awd-text-2); /* Gray-Medium */
  border: 1px solid var(--awd-border); /* Gray-Light */
  transition: all 0.2s ease;
}

.status-icon {
  margin-right: 6px;
  flex-shrink: 0;
}

.status-icon {
  margin-right: 6px;
  flex-shrink: 0;
}

.status-btn.modified {
  border-color: var(--awd-accent-soft);
  color: var(--awd-accent-text); /* Forest Green */
  background-color: var(--awd-accent-soft); /* Mint Lightest */
}

.status-btn.modified:hover {
  /* background-color: var(--awd-mint); Mint Green */
  background-color: var(--awd-mint);
  /* color: #ffffff; */
  /* border-color: var(--awd-accent-text); */
}

.status-btn.created {
  border-color: var(--awd-accent-soft);
  color: var(--awd-accent-text);
  background-color: var(--awd-accent-soft);
}

.status-btn.created:hover {
  background-color: var(--awd-mint);
  /* color: #ffffff; */
  border-color: var(--awd-accent);
}

/* Status Popup */
.status-popup {
  position: absolute;
  bottom: 100%;
  left: 0;
  margin-bottom: 8px; /* Gap */
  width: 200px;
  background: var(--awd-surface);
  border-radius: 8px;
  box-shadow: 0 4px 12px rgba(0,0,0,0.15);
  padding: 4px 0;
  z-index: 100;
  border: 1px solid var(--awd-border);
  display: flex;
  flex-direction: column;
}

.status-popup-item {
  display: flex;
  align-items: center;
  padding: 8px 12px;
  cursor: pointer;
  transition: background 0.2s;
}

.status-popup-item:hover {
  background-color: var(--awd-surface-2);
}

.file-icon-mini {
  width: 14px;
  height: 14px;
  margin-right: 8px;
  opacity: 0.7;
}

.file-name-text {
  font-size: 13px;
  color: var(--awd-text);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.popup-mask-transparent {
  position: fixed;
  top: 0;
  left: 0;
  right: 0;
  bottom: 0;
  z-index: 99; /* Below popup but above others */
  background: transparent;
}

/* Reuse existing token styles */
.token-label {
  font-weight: 500;
  color: var(--awd-text-2);
}
.token-value {
  font-family: monospace;
  font-weight: 600;
}
.token-detail {
  font-size: 11px;
  color: var(--awd-text-3);
}

/* Empty state for file change buttons：0 项时收敛成幽灵标签——去底去框、灰字、
   更窄的内边距。刻意不隐藏：用户要能发现「改动/新增」这个功能的存在 */
.status-btn.empty {
  opacity: 1;
  cursor: default;
  background-color: transparent;
  border-color: transparent;
  color: var(--awd-text-3);
  font-weight: 500;
  padding: 4px 6px;
}
.status-btn.empty .status-icon {
  opacity: 0.65;
}
.status-btn.empty:hover {
  transform: none;
  background-color: transparent;
  border-color: transparent;
}

/* Rollback UI */
.bubble-footer {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  margin-top: 4px;
  position: absolute;
  bottom: -24px;
  right: 0px;
  min-width: 134px;
}

.rollback-btn,
.branch-btn {
  display: flex;
  align-items: center;
  margin-left: 8px;
  opacity: 0;
  transition: all 0.2s ease;
  cursor: pointer;
  padding: 4px 10px;
  border-radius: 99px;
  /* background-color: var(--awd-accent-soft); Mint Lightest */
  /* border: 1px solid rgba(46, 90, 80, 0.1); */
}

.user-bubble:hover .rollback-btn,
.user-bubble:hover .branch-btn {
  opacity: 1;
}

/* 定位不到这条消息时置灰：仍然显示（要让用户看到有这么个动作），但点了什么都不会发生，
   title 里写清原因——一个点了注定失败的按钮比没有按钮更糟 */
.rollback-btn.is-disabled,
.branch-btn.is-disabled {
  cursor: not-allowed;
}

.user-bubble:hover .rollback-btn.is-disabled,
.user-bubble:hover .branch-btn.is-disabled {
  opacity: 0.4;
}

.rollback-btn:hover {
  /* background-color: var(--awd-mint); Mint Green */
  /* border-color: var(--awd-accent-text); */
}

.rollback-icon-svg {
  display: flex;
  align-items: center;
  justify-content: center;
  margin-right: 4px;
  color: var(--awd-accent-text); /* Forest Green */
}

/* .rollback-btn:hover .rollback-icon-svg,
.rollback-btn:hover .rollback-text {
  color: white;
} */

.rollback-text {
  font-size: 11px;
  color: var(--awd-accent-text);
  font-weight: 600;
}

/* Warning Dialog */
.warning-header {
  border-bottom: 2px solid var(--awd-warning);
}

.warning-title {
  color: var(--awd-warning-text);
}

.rollback-warning-content {
  padding: 10px;
}

.warning-text {
  font-size: 14px;
  color: var(--awd-text);
  margin-bottom: 12px;
  display: block;
}

.doc-tip-box {
  background-color: var(--awd-surface);
  border: 1px solid var(--awd-info);
  border-radius: 6px;
  padding: 10px;
  display: flex;
  flex-direction: row;
  margin-bottom: 16px;
}

.doc-tip-icon {
  width: 18px;
  height: 18px;
  margin-right: 10px;
  flex-shrink: 0;
  color: var(--awd-warning-text);
}

.doc-tip-text {
  font-size: 13px;
  color: var(--awd-info-text);
  display: flex;
  flex-direction: column;
}

.doc-link-text {
  font-weight: 500;
  margin-top: 2px;
}

.rollback-preview {
  background-color: var(--awd-surface-2);
  padding: 8px;
  border-radius: 4px;
  border-left: 3px solid var(--awd-border-strong);
}

.preview-label {
  font-size: 12px;
  color: var(--awd-text-2);
  margin-right: 4px;
}

.preview-content {
  font-size: 12px;
  color: var(--awd-text);
  font-style: italic;
}

.awd-btn-danger {
  background-color: var(--awd-danger);
  color: var(--awd-text-on-accent);
  border: none;
}

.awd-btn-danger:hover {
  background-color: var(--awd-danger);
}
/* PPT Config Styles */
.ppt-config-section {
  padding: 10px 0;
}

.section-title {
  font-size: 14px;
  color: var(--awd-text-2);
  margin-bottom: 12px;
  display: block;
}

.ppt-option-card {
  border: 1px solid var(--awd-border);
  border-radius: 8px;
  padding: 16px;
  margin-bottom: 12px;
  cursor: pointer;
  transition: all 0.2s;
  background-color: var(--awd-surface);
}

.ppt-option-card:hover {
  border-color: var(--awd-info);
  background-color: var(--awd-surface);
}

.ppt-option-card.active {
  border-color: var(--awd-info);
  background-color: var(--awd-info-soft);
  box-shadow: 0 2px 8px rgba(33, 150, 243, 0.15);
}

.option-header {
  display: flex;
  align-items: center;
  margin-bottom: 8px;
}

.option-icon {
  width: 20px;
  height: 20px;
  margin-right: 12px;
  flex-shrink: 0;
  color: var(--awd-accent-text);
}

.option-name {
  font-size: 16px;
  font-weight: 600;
  color: var(--awd-text);
  flex: 1;
}

.check-mark {
  color: var(--awd-info-text);
  font-weight: bold;
  font-size: 16px;
}

.option-desc {
  font-size: 13px;
  color: var(--awd-text-2);
  line-height: 1.5;
  padding-left: 32px; /* align with text start */
}

.warning-text {
  color: var(--awd-warning-text);
  font-weight: 500;
  display: block;
  margin-top: 4px;
}

/* 存档说明是次要信息：与上一句同色系但不抢，避免两行一样重 */
.rollback-archive-note {
  color: var(--awd-text-2);
  font-weight: 400;
  font-size: 13px;
}

.highlight-text {
  color: var(--awd-accent-text);
  font-weight: 500;
  display: block;
  margin-top: 4px;
}

.awd-btn-secondary {
    background-color: var(--awd-surface-2);
    color: var(--awd-text);
    border: 1px solid var(--awd-border);
}
.awd-btn-secondary:hover {
    background-color: var(--awd-surface-3);
}

/* 可选组件缺失弹窗（设计 §4.2） */
.chat-component-gate {
    position: absolute;
    inset: 0;
    background: var(--awd-overlay);
    display: flex;
    align-items: center;
    justify-content: center;
    z-index: 2000;
}

.cg-panel {
    width: 420px;
    max-width: 88%;
    background: var(--awd-surface);
    border: 1px solid var(--awd-border);
    border-radius: 12px;
    box-shadow: var(--awd-shadow-lg);
    padding: 18px;
}

.cg-title {
    display: block;
    font-size: 15px;
    font-weight: 600;
    color: var(--awd-text);
    margin-bottom: 10px;
}

.cg-installing-text {
    font-size: 12px;
    color: var(--awd-text-2);
}

.cg-actions {
    display: flex;
    gap: 10px;
    margin-top: 12px;
}

.cg-btn {
    padding: 7px 14px;
    border: 1px solid var(--awd-border-strong);
    border-radius: 8px;
    font-size: 13px;
    color: var(--awd-text);
    cursor: pointer;
}

.cg-btn:hover {
    background: var(--awd-surface-2);
}

.cg-btn.primary {
    background: var(--awd-accent);
    color: var(--awd-text-on-accent);
    border-color: var(--awd-accent);
}

.cg-btn.primary:hover {
    background: var(--awd-accent-hover);
}

</style>
