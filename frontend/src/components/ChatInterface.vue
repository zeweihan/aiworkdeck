<template>
  <view class="chat-interface" :class="{ 'is-empty': bubbles.length === 0 && !isStreaming }">

    <!-- Upload File Modal (reused from FileTree pattern) -->
    <view v-if="showUploadDialog" class="awd-dialog-mask" @tap="cancelUpload">
      <view class="awd-dialog awd-dialog-large" @tap.stop>
        <view class="awd-dialog-header">
          <view class="header-row">
            <text class="awd-dialog-title">上传文件</text>
            <text class="awd-dialog-subtitle">选择目标位置并选择要上传的文档</text>
          </view>
        </view>
        <view class="awd-dialog-body">
          <view class="form-group">
            <text class="form-label">上传位置</text>
            <view class="awd-field clickable" @tap="openFolderSelector">
              <image src="/static/folder-closed.png" class="field-icon-img" mode="aspectFit" />
              <text class="field-value">
                {{ selectedUploadParent ? getFolderPath(selectedUploadParent) : '根目录' }}
              </text>
            </view>
          </view>

          <!-- H5 Folder Upload -->
          <!-- #ifdef H5 -->
          <view class="form-group">
            <text class="form-label">上传文件夹</text>
            <view class="awd-field clickable" @tap="triggerFolderUploadInput">
               <view v-if="isFolderUpload && uploadSelectedFiles.length > 0" class="field-content-row">
                  <text class="field-value">已选择 {{ uploadSelectedFiles.length }} 个文件</text>
               </view>
               <view v-else>
                  <text class="field-placeholder">点击选择文件夹...</text>
               </view>
            </view>
          </view>
          <!-- #endif -->

          <view class="form-group">
            <text class="form-label">上传文件</text>
            <view class="awd-field clickable" @tap="selectFilesForUpload">
              <view v-if="uploadSelectedFiles.length === 0 || isFolderUpload">
                <text class="field-placeholder">选择文件（支持多选）</text>
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
          <view class="awd-btn awd-btn-secondary" @tap="cancelUpload">取消</view>
          <view
            class="awd-btn awd-btn-primary"
            :class="{ disabled: !uploadSelectedFiles.length }"
            @tap="uploadSelectedFiles.length ? confirmUploadAndAddContext() : null"
          >
            确定上传
          </view>
        </view>
      </view>
    </view>

    <!-- Folder Selector Popup (Nested) - Matching FileTree design -->
    <view v-if="showFolderSelector" class="awd-dialog-mask" style="z-index: 3000;" @tap="showFolderSelector = false">
      <view class="awd-dialog" @tap.stop>
        <view class="awd-dialog-header">
          <view class="header-row folder-selector-header">
            <text class="awd-dialog-title">选择文件夹</text>
            <view class="new-folder-btn" @tap="handleSelectorCreateFolder">
              <text class="btn-plus">+</text>
              <text>新建文件夹</text>
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
            <text class="folder-name">根目录</text>
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
          <view v-if="folderTree.length === 0" class="empty-tip">暂无其他文件夹</view>
        </view>
        <view class="awd-dialog-footer">
          <view class="awd-btn awd-btn-secondary" @tap="showFolderSelector = false">取消</view>
          <view class="awd-btn awd-btn-primary" @tap="confirmFolderSelection">确定</view>
        </view>
      </view>
    </view>

    <!-- Rollback Confirmation Dialog -->
    <view v-if="showRollbackDialog" class="awd-dialog-mask" style="z-index: 3100;" @tap="cancelRollback">
      <view class="awd-dialog" @tap.stop>
        <view class="awd-dialog-header warning-header">
          <text class="awd-dialog-title warning-title">确认回退</text>
        </view>
        <view class="awd-dialog-body">
          <view class="rollback-warning-content">
            <text class="warning-text">此操作将删除该消息以及之后的所有对话记录，且无法恢复。</text>
            <view class="doc-tip-box">
              <svg class="doc-tip-icon" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
                <path d="M12 3a6 6 0 0 0-3.5 10.9V17h7v-3.1A6 6 0 0 0 12 3Z" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round" />
                <path d="M10 20h4" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" />
              </svg>
              <view class="doc-tip-text">
                <text>如果助手在后续对话中修改了文档（Word/PPT），文件内容不会自动回退。</text>
                <text class="doc-link-text">AI 的文档修改以修订痕迹记录，可在编辑器中「拒绝修订」恢复原文。</text>
              </view>
            </view>
            <view class="rollback-preview">
              <text class="preview-label">将回退到并编辑：</text>
              <text class="preview-content">"{{ truncateName(rollbackTargetContent, 50) }}"</text>
            </view>
          </view>
        </view>
        <view class="awd-dialog-footer">
          <view class="awd-btn awd-btn-secondary" @tap="cancelRollback">取消</view>
          <view class="awd-btn awd-btn-danger" @tap="confirmRollback">确认回退</view>
        </view>
      </view>
    </view>

    <!-- PPT Config Dialog -->
    <view v-if="showPptConfigDialog" class="awd-dialog-mask" style="z-index: 3200;" @tap="cancelPptConfig">
      <view class="awd-dialog" @tap.stop>
        <view class="awd-dialog-header">
           <text class="awd-dialog-title">PPT 生成选项</text>
        </view>
        <view class="awd-dialog-body">
           <view class="ppt-config-section">
              <text class="section-title">请选择导出格式</text>

              <!-- Option 1: Editable (Beta) -->
              <view class="ppt-option-card"
                   :class="{ active: pptExportEditable === true }"
                   @tap="pptExportEditable = true">
                 <view class="option-header">
                    <svg class="option-icon" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
                       <path d="M4 20h4L19 9a2.8 2.8 0 0 0-4-4L4 16v4Z" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round" />
                       <path d="M14.5 5.5 18.5 9.5" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" />
                    </svg>
                    <text class="option-name">可编辑版 (Beta)</text>
                    <text v-if="pptExportEditable === true" class="check-mark">✔</text>
                 </view>
                 <view class="option-desc">
                    生成原生 PPTX 文本和表格。
                    <text class="warning-text">实验性功能，复杂排版可能不稳定。</text>
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
                    <text class="option-name">高清图片版 (推荐)</text>
                    <text v-if="pptExportEditable === false" class="check-mark">✔</text>
                 </view>
                 <view class="option-desc">
                    将每页渲染为高清图片。
                    <text class="highlight-text">排版完美，渲染稳定，但文字不可编辑。</text>
                 </view>
              </view>
           </view>
        </view>
        <view class="awd-dialog-footer">
           <view class="awd-btn awd-btn-secondary" @tap="cancelPptConfig">取消</view>
           <view class="awd-btn awd-btn-primary" @tap="confirmPptGeneration">开始生成</view>
        </view>
      </view>
    </view>

    <!-- 1. Header Actions -->
    <view class="chat-header">
       <view class="header-left">
          <view class="assistant-avatar">AI</view>
          <text class="project-name-display">{{ projectName }}</text>
       </view>
       <view class="header-actions">
          <view class="icon-btn" @tap="toggleAssistantMenu" :class="{ active: showAssistantMenu }" title="Assistants">
             <image class="btn-icon default" src="/static/assistant.png" />
             <image class="btn-icon hover" src="/static/assistant_hover.png" />
          </view>
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

    <!-- Assistant Dropdown Panel - positioned relative to chat-interface like history drawer -->
    <view v-if="showAssistantMenu" class="assistant-dropdown-panel" @tap.stop>
       <view class="assistant-menu-header">智慧助手</view>
       <view
         v-for="ast in assistants"
         :key="ast.id"
         class="assistant-menu-item"
         :class="{ active: currentAssistantId === ast.id }"
         @tap="selectAssistant(ast)"
       >
          <text class="assistant-item-name">{{ ast.name }}</text>
          <view class="setting-icon-wrapper" @tap.stop="$emit('config-assistant', ast)">
             <image class="setting-icon default" src="/static/setting.png" mode="aspectFit" />
             <image class="setting-icon hover" src="/static/setting_hoving.png" mode="aspectFit" />
          </view>
       </view>
    </view>
    <view v-if="showAssistantMenu" class="dropdown-mask" @tap="showAssistantMenu = false"></view>

    <!-- 2. Message List (Single Source of Truth: bubbles) -->
    <scroll-view
      v-if="bubbles.length > 0 || isStreaming"
      class="message-list"
      scroll-y
      :scroll-top="scrollTop"
      :scroll-with-animation="true"
    >
      <view class="message-list-content">
        <view
          v-for="(msg, index) in bubbles"
          :key="msg.id || index"
          class="message-row"
          :class="msg.role.toLowerCase()"
        >
          <!-- User Message -->
          <div v-if="msg.role === 'USER'" class="user-bubble">
            <!-- Image Thumbnails (above message) -->
            <view v-if="msg.images && msg.images.length > 0" class="user-bubble-images">
               <image v-for="(img, idx) in msg.images" :key="idx" :src="img.path" mode="aspectFill" class="bubble-image-thumb" />
            </view>
            <!-- Content with inline file tags preserved at their original positions -->
            <div
              class="user-bubble-content"
              v-html="msg.contentHtml || escapeHtml(msg.content)"
            ></div>
            <div class="bubble-footer">
              <!-- Rollback Button -->
              <view v-if="!isStreaming" class="rollback-btn" @tap.stop="openRollbackDialog(msg, index)" title="回退到此消息（修改重发）">
                 <div class="rollback-icon-svg">
                    <svg xmlns="http://www.w3.org/2000/svg" width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round">
                        <path d="M9 14 4 9l5-5"></path>
                        <path d="M4 9h12a5 5 0 0 1 5 5v3"></path>
                    </svg>
                 </div>
                 <text class="rollback-text">回退</text>
              </view>
              <span v-if="msg.timestamp" class="bubble-timestamp user">{{ msg.timestamp }}</span>
            </div>
          </div>

          <!-- Assistant Message (Root Bubble) -->
          <div v-else-if="msg.role === 'ASSISTANT'" class="assistant-root-wrapper">
             <RootBubble
               :bubble="msg"
               @open-artifact-tab="handleArtifactOpenTab"
               @approve="handleArtifactApprove"
               @message-action="$emit('message-action', $event)"
             />
             <!-- <span v-if="msg.timestamp" class="bubble-timestamp assistant">{{ msg.timestamp }}</span> -->
          </div>
        </view>
      </view>
    </scroll-view>

    <!-- 3. Integrated Empty & Input Layout -->
    <view v-if="bubbles.length === 0 && !isStreaming" class="empty-flow-container">
       <!-- Top: Welcome Text (between header and input) -->
       <view class="empty-top-section">
          <text class="welcome-text">有什么可以帮您？</text>
          <text class="welcome-subtitle">随时为您解答疑问、起草文档或分析数据。</text>
       </view>

       <!-- Center: Input -->
       <view class="empty-middle-section">
          <view class="input-card centered-style">
              <view v-if="isDragging" class="drop-overlay">
                 <text>Drop files here</text>
              </view>
               <!-- Image Thumbnails Preview (top-left) -->
               <view v-if="pastedImages.length > 0" class="input-images-preview">
                  <view v-for="(img, index) in pastedImages" :key="index" class="preview-image-item">
                     <image :src="img.path" mode="aspectFill" class="preview-thumb" />
                     <text class="preview-remove" @tap="removePastedImage(index)">×</text>
                  </view>
               </view>
              <div
                ref="richInput"
                class="chat-input-rich"
                contenteditable="true"
                @input="handleRichInput"
                @paste="handlePaste"
                @keydown.enter="handleEnterKey"
                @click="handleInputClick"
                data-placeholder="请输入问题、拖拽文件/文件夹至此、粘贴合同文本或描述案情..."
              ></div>
              <!-- Note: Context files are now shown as inline tags inside the rich input -->
              <view class="input-footer">
                 <view class="action-bar-left">
                    <view class="icon-btn mini file-add-btn" @tap="triggerFileSelect" title="Add File">
                   <image class="btn-icon default" src="/static/plus.png" />
                   <image class="btn-icon hover" src="/static/plus_hover.png" />
                 </view>
                    <!-- Agent Mode Selector -->
                    <view class="mode-selector" @tap="toggleModeDropdown">
                       <text class="mode-icon" v-if="currentModeIcon">{{ currentModeIcon }}</text>
                       <text class="mode-name">{{ currentModeName }}</text>
                       <text class="dropdown-arrow">▼</text>
                       <view v-if="showModeDropdown" class="mode-dropdown down">
                          <view v-for="mode in availableModes" :key="mode.id"
                                class="mode-option"
                                :class="{ active: currentModeId === mode.id }"
                                @tap.stop="selectMode(mode)">
                            <text class="mode-option-icon" v-if="mode.icon">{{ mode.icon }}</text>
                             <view class="mode-option-text">
                                <text class="mode-option-name">{{ mode.name }}</text>
                                <text class="mode-option-desc">{{ mode.desc }}</text>
                             </view>
                          </view>
                       </view>
                    </view>
                    <!-- Model Selector -->
                    <view class="model-selector" @tap="toggleModelDropdown">
                       <text class="model-name">{{ currentModelName }}</text>
                       <text class="dropdown-arrow">▼</text>
                       <view v-if="showModelDropdown" class="model-dropdown down">
                          <view v-for="m in availableModels" :key="m.id"
                                class="model-option"
                                :class="{ active: currentModelId === m.id }"
                                @tap.stop="selectModel(m)">
                             {{ m.name }}
                          </view>
                       </view>
                    </view>
                    <!-- Skill Selector：默认自动匹配触发词，可钉选固定使用某个 Skill -->
                    <view class="skill-selector" :class="{ pinned: !!pinnedSkillId }" :title="pinnedSkillId ? ('已固定使用 Skill：' + skillChipLabel) : 'Skill（默认自动匹配触发词）'" @tap="toggleSkillDropdown">
                       <text class="skill-glyph">◲</text>
                       <view v-if="showSkillDropdown" class="skill-dropdown down">
                          <view class="skill-option" :class="{ active: !pinnedSkillId }" @tap.stop="selectSkill('')">
                             <view class="skill-option-text">
                                <text class="skill-option-name">自动匹配</text>
                                <text class="skill-option-desc">命中触发词时自动生效</text>
                             </view>
                          </view>
                          <view v-if="availableSkills.length" class="skill-divider"></view>
                          <view v-for="s in availableSkills" :key="s.id"
                                class="skill-option"
                                :class="{ active: pinnedSkillId === s.id }"
                                @tap.stop="selectSkill(s.id)">
                             <view class="skill-option-text">
                                <text class="skill-option-name">{{ s.name || s.id }}</text>
                                <text class="skill-option-desc">{{ s.activationMode === 'manual' ? '仅手动' : (s.triggers || []).join(' / ') || '无触发词' }}</text>
                             </view>
                          </view>
                          <view class="skill-divider"></view>
                          <view class="skill-manage" @tap.stop="goToSkillManagement">管理已安装 Skill</view>
                       </view>
                    </view>
                 </view>
                 <view
                    class="send-btn"
                    :class="{ disabled: !inputPrompt.trim() && !isStreaming, stopping: isStreaming }"
                    @tap="isStreaming ? abort() : handleSubmit()"
                 >
                    <text class="send-icon">{{ isStreaming ? '■' : '↑' }}</text>
                 </view>
              </view>
          </view>
          <view v-if="showModelDropdown || showModeDropdown || showSkillDropdown" class="dropdown-mask model-mask" @tap="showModelDropdown = false; showModeDropdown = false; showSkillDropdown = false"></view>
       </view>

       <!-- Bottom: History (pushed to bottom with flexbox) -->
       <view class="empty-bottom-section">
          <view class="recent-history-header">近期对话</view>
          <view class="recent-history" v-if="recentHistory && recentHistory.length > 0">
             <view v-for="h in recentHistory" :key="h.id" class="history-item" @tap="$emit('load-history', h)">
                <view v-if="recentDotClass(h)" class="conv-dot" :class="recentDotClass(h)"></view>
                <text class="history-title">{{ cleanTitle(h.title) }}</text>
                <text class="history-time">{{ formatRelativeTime(h.updatedAt) }}</text>
             </view>
          </view>
          <view v-else class="history-empty-placeholder">
             <text>Your recent chats will appear here</text>
          </view>
          <view class="history-disclaimer">AI生成内容仅供参考，不构成正式意见，请自负责任使用。</view>
       </view>
    </view>

    <!-- 4. Regular Bottom Input -->
    <view v-else class="input-area-wrapper">
       <!-- 任务清单进度卡已随消息流内联展示（RootBubble），不再常驻输入框上方，
            避免与气泡内的步骤分组重复（用户反馈：线性时序结构） -->
       <!-- 步数超限暂停：一键继续，免得用户手动输入「继续」 -->
       <view v-if="agentPaused && !isStreaming" class="continue-bar">
          <text class="continue-hint">已达单轮执行步数上限，任务已暂停</text>
          <view class="continue-btn" @tap="handleContinue">继续执行</view>
       </view>
       <!-- NEW: File Changes & Token Usage Bar (Always visible) -->
       <view class="status-bar-row">
           <!-- Left: File Changes -->
           <view class="status-bar-left">
               <!-- Modified Files -->
                <view class="status-btn-wrapper">
                    <view class="status-btn modified" :class="{ empty: modifiedFiles.length === 0 }" @tap.stop="modifiedFiles.length > 0 ? toggleModifiedPopup() : null">
                        <svg class="status-icon" xmlns="http://www.w3.org/2000/svg" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M12 20h9"/><path d="M16.5 3.5a2.121 2.121 0 0 1 3 3L7 19l-4 1 1-4L16.5 3.5z"/></svg>
                        <text>改动 ({{ modifiedFiles.length }})</text>
                    </view>
                   <view v-if="showModifiedPopup && modifiedFiles.length > 0" class="status-popup up">
                       <view v-for="(f, i) in modifiedFiles" :key="i" class="status-popup-item" @tap.stop="handleOpenFile(f)">
                           <image src="/static/file.png" class="file-icon-mini"/>
                           <text class="file-name-text">{{ f.fileName }}</text>
                       </view>
                   </view>
                   <view v-if="showModifiedPopup && modifiedFiles.length > 0" class="popup-mask-transparent" @tap.stop="showModifiedPopup = false"></view>
               </view>

               <!-- New Files -->
               <view class="status-btn-wrapper">
                   <view class="status-btn created" :class="{ empty: createdFiles.length === 0 }" @tap.stop="createdFiles.length > 0 ? toggleNewPopup() : null">
                       <svg class="status-icon" xmlns="http://www.w3.org/2000/svg" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><line x1="12" y1="5" x2="12" y2="19"></line><line x1="5" y1="12" x2="19" y2="12"></line></svg>
                       <text>新增 ({{ createdFiles.length }})</text>
                   </view>
                   <view v-if="showNewPopup && createdFiles.length > 0" class="status-popup up">
                       <view v-for="(f, i) in createdFiles" :key="i" class="status-popup-item" @tap.stop="handleOpenFile(f)">
                           <image src="/static/file.png" class="file-icon-mini"/>
                           <text class="file-name-text">{{ f.fileName }}</text>
                       </view>
                   </view>
                   <view v-if="showNewPopup && createdFiles.length > 0" class="popup-mask-transparent" @tap.stop="showNewPopup = false"></view>
               </view>
           </view>

           <!-- Right: Token Usage -->
           <!-- <view v-if="tokenUsage && tokenUsage.totalTokens > 0" class="status-bar-right">
               <text class="token-label">Tokens</text>
               <text class="token-value">{{ tokenUsage.totalTokens.toLocaleString() }}</text>
               <text class="token-detail">({{ tokenUsage.promptTokens.toLocaleString() }} / {{ tokenUsage.completionTokens.toLocaleString() }})</text>
           </view> -->
       </view>
       <view class="input-card">
          <view v-if="isDragging" class="drop-overlay">
             <text>Drop files here</text>
          </view>
           <!-- Image Thumbnails Preview (top-left) -->
           <view v-if="pastedImages.length > 0" class="input-images-preview">
              <view v-for="(img, index) in pastedImages" :key="index" class="preview-image-item">
                 <image :src="img.path" mode="aspectFill" class="preview-thumb" />
                 <text class="preview-remove" @tap="removePastedImage(index)">×</text>
              </view>
           </view>
          <div
            ref="richInput"
            class="chat-input-rich"
            contenteditable="true"
            @input="handleRichInput"
            @paste="handlePaste"
            @keydown.enter="handleEnterKey"
            @click="handleInputClick"
            data-placeholder="Ask anything..."
          ></div>
          <!-- Note: Context files are now shown as inline tags inside the rich input -->
          <view class="input-footer">
             <view class="action-bar-left">
                <view class="icon-btn mini" @tap="triggerFileSelect" title="Add File">
                   <image class="btn-icon default" src="/static/plus.png" />
                   <image class="btn-icon hover" src="/static/plus_hover.png" />
                </view>
                <!-- Agent Mode Selector -->
                <view class="mode-selector" @tap="toggleModeDropdown">
                   <text class="mode-icon" v-if="currentModeIcon">{{ currentModeIcon }}</text>
                   <text class="mode-name">{{ currentModeName }}</text>
                   <text class="dropdown-arrow">▲</text>
                   <view v-if="showModeDropdown" class="mode-dropdown up">
                      <view v-for="mode in availableModes" :key="mode.id"
                            class="mode-option"
                            :class="{ active: currentModeId === mode.id }"
                            @tap.stop="selectMode(mode)">
                         <text class="mode-option-icon" v-if="mode.icon">{{ mode.icon }}</text>
                         <view class="mode-option-text">
                            <text class="mode-option-name">{{ mode.name }}</text>
                            <text class="mode-option-desc">{{ mode.desc }}</text>
                         </view>
                      </view>
                   </view>
                </view>
                <!-- Model Selector -->
                <view class="model-selector" @tap="toggleModelDropdown">
                   <text class="model-name">{{ currentModelName }}</text>
                   <text class="dropdown-arrow">▲</text>
                   <view v-if="showModelDropdown" class="model-dropdown up">
                      <view v-for="m in availableModels" :key="m.id"
                            class="model-option"
                            :class="{ active: currentModelId === m.id }"
                            @tap.stop="selectModel(m)">
                         {{ m.name }}
                      </view>
                   </view>
                </view>
                <!-- Skill Selector：默认自动匹配触发词，可钉选固定使用某个 Skill -->
                <view class="skill-selector" :class="{ pinned: !!pinnedSkillId }" :title="pinnedSkillId ? ('已固定使用 Skill：' + skillChipLabel) : 'Skill（默认自动匹配触发词）'" @tap="toggleSkillDropdown">
                   <text class="skill-glyph">◲</text>
                   <view v-if="showSkillDropdown" class="skill-dropdown up">
                      <view class="skill-option" :class="{ active: !pinnedSkillId }" @tap.stop="selectSkill('')">
                         <view class="skill-option-text">
                            <text class="skill-option-name">自动匹配</text>
                            <text class="skill-option-desc">命中触发词时自动生效</text>
                         </view>
                      </view>
                      <view v-if="availableSkills.length" class="skill-divider"></view>
                      <view v-for="s in availableSkills" :key="s.id"
                            class="skill-option"
                            :class="{ active: pinnedSkillId === s.id }"
                            @tap.stop="selectSkill(s.id)">
                         <view class="skill-option-text">
                            <text class="skill-option-name">{{ s.name || s.id }}</text>
                            <text class="skill-option-desc">{{ s.activationMode === 'manual' ? '仅手动' : (s.triggers || []).join(' / ') || '无触发词' }}</text>
                         </view>
                      </view>
                      <view class="skill-divider"></view>
                      <view class="skill-manage" @tap.stop="goToSkillManagement">管理已安装 Skill</view>
                   </view>
                </view>
             </view>
             <view
                class="send-btn"
                :class="{ disabled: !inputPrompt.trim() && !isStreaming, stopping: isStreaming }"
                @tap="isStreaming ? abort() : handleSubmit()"
             >
                <text class="send-icon">{{ isStreaming ? '■' : '↑' }}</text>
             </view>
          </view>
          <view v-if="showModelDropdown || showModeDropdown || showSkillDropdown" class="dropdown-mask" @tap="showModelDropdown = false; showModeDropdown = false; showSkillDropdown = false"></view>
       </view>
    </view>

    <!-- Background Task Progress Indicator -->
    <BackgroundTaskIndicator
      :backgroundTasks="backgroundTasks"
      :lastHeartbeat="lastHeartbeat"
    />

  </view>
</template>

<script>
import RootBubble from './AgentMessage/RootBubble.vue'
import BackgroundTaskIndicator from './BackgroundTaskIndicator.vue'
import { useAgentStream } from '@/composables/useAgentStream.js'
import { ref, watch, onMounted, nextTick, getCurrentInstance, computed } from 'vue'
import { createFile, getProjectFiles, getApiBaseUrl, rollbackConversation, performPptGeneration, getSkills } from '@/services/api.js'
import { getAuthHeaders } from '@/utils/auth.js'

export default {
  name: 'ChatInterface',
  components: { RootBubble, BackgroundTaskIndicator },
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
    assistants: {
        type: Array,
        default: () => []
    },
    currentAssistantId: String,
    // NEW: Current active tab for auto-context injection
    activeTab: {
      type: Object,
      default: null
    },
    activeTabPane: {
      type: String,
      default: null // 'left' | 'right' | null
    }
  },
  setup(props, { emit, expose }) {
    const {
      bubbles,
      isStreaming,
      sendMessage,
      abort,
      setConversationId,
      clearBubbles,
      onClientAction,
      onTitleUpdate,
      backgroundTasks,
      lastHeartbeat,
      tokenUsage,
      fileChanges,
      agentPaused,
      agentRunStatus,
      reattachSSE,
      rollbackToMessage,
      currentConversationId,
      loadConversationMetadata
    } = useAgentStream()

    // Bridge Stream Events to Component Events
    onClientAction((action) => {
        if (action.action === 'ppt_config_required') {
           // Show PPT config dialog
           pptConfigData.value = action
           pptExportEditable.value = false // Default to safe option
           showPptConfigDialog.value = true
        } else {
           emit('client-action', action)
        }
    })

    // Bridge Title Update Event to Parent
    onTitleUpdate((title) => {
        emit('title-update', title)
        emit('refresh-history') // Trigger history refresh to show new title
    })
    const inputPrompt = ref('')
    const richInput = ref(null)
    const scrollTop = ref(0)
    const isDragging = ref(false)

    // Context Files (for drag-drop file context)
    const contextFiles = ref([])

    // Pasted Images (for paste/drop images)
    const pastedImages = ref([])

    // Model Selection
    const showModelDropdown = ref(false)
    // 模型 ID 必须与后端 AllowedModels 白名单一致，且为 OpenRouter 当前在线模型。
    // 排序：区域无关模型在前（Google/Anthropic/OpenAI 系在国内网络会被
    // OpenRouter 返回 403 region 错误，仅国际网络可用）
    const availableModels = [
      { id: 'deepseek/deepseek-v4-flash', name: 'DeepSeek V4 Flash' },
      { id: 'deepseek/deepseek-v4-pro', name: 'DeepSeek V4 Pro' },
      { id: 'qwen/qwen3-235b-a22b-2507', name: 'Qwen3 235B' },
      { id: 'moonshotai/kimi-k2.6', name: 'Kimi K2.6' },
      { id: 'z-ai/glm-5', name: 'GLM-5' },
      { id: 'anthropic/claude-sonnet-5', name: 'Claude Sonnet 5 (国际网络)' },
      { id: 'google/gemini-3.1-pro-preview', name: 'Gemini 3.1 Pro (国际网络)' },
      { id: 'openai/gpt-5.2', name: 'GPT-5.2 (国际网络)' }
    ]
    const currentModelId = ref(availableModels[0].id)
    const currentModelName = ref(availableModels[0].name)

    // Fix: Define selectModel explicitly
    const selectModel = (m) => {
      console.log('Switching model to:', m.name)
      currentModelId.value = m.id
      currentModelName.value = m.name
      showModelDropdown.value = false
    }

    // Agent Mode Selection (Ask, Plan, Agent)
    const showModeDropdown = ref(false)
    const availableModes = [
      { id: 'AGENT', name: 'Agent', icon: '', desc: '自动执行' },
      { id: 'ASK', name: 'Ask', icon: '', desc: '纯对话' },
      { id: 'PLAN', name: 'Plan', icon: '', desc: '规划确认' }
    ]
    const currentModeId = ref(availableModes[0].id)
    const currentModeName = ref(availableModes[0].name)
    const currentModeIcon = ref(availableModes[0].icon)

    const selectMode = (mode) => {
      console.log('Switching agent mode to:', mode.name)
      currentModeId.value = mode.id
      currentModeName.value = mode.name
      currentModeIcon.value = mode.icon
      showModeDropdown.value = false
    }

    const toggleModeDropdown = () => {
      showModeDropdown.value = !showModeDropdown.value
      // 关闭其他下拉菜单
      if (showModeDropdown.value) {
        showModelDropdown.value = false
        showSkillDropdown.value = false
      }
    }

    // Skill 选择（默认自动匹配触发词；钉选后本会话固定使用该 Skill）
    const showSkillDropdown = ref(false)
    const availableSkills = ref([])
    const pinnedSkillId = ref('')

    const pinnedSkill = computed(() =>
      availableSkills.value.find(s => s.id === pinnedSkillId.value) || null
    )
    // 未钉选时只显示 "Skill"：AI 面板窄，默认态不该占掉模型选择器的位置
    const skillChipLabel = computed(() => pinnedSkill.value ? pinnedSkill.value.name : 'Skill')

    // 已安装 Skill 为 0 时不显示选择器，避免输入区堆无用控件
    const loadAvailableSkills = async () => {
      try {
        const res = await getSkills()
        const list = Array.isArray(res) ? res : (res?.data || [])
        availableSkills.value = list.filter(s => s.activationMode !== 'disabled' && s.enabled !== false)
      } catch (e) {
        // Skill 列表拉取失败不该影响对话，静默降级为"无可选 Skill"
        console.warn('[ChatInterface] 加载 Skill 列表失败:', e)
        availableSkills.value = []
      }
    }

    const selectSkill = (skillId) => {
      // 钉选状态跟随会话，切换 Skill 后下一条消息即生效
      pinnedSkillId.value = pinnedSkillId.value === skillId ? '' : skillId
      showSkillDropdown.value = false
    }

    const toggleSkillDropdown = () => {
      showSkillDropdown.value = !showSkillDropdown.value
      if (showSkillDropdown.value) {
        showModelDropdown.value = false
        showModeDropdown.value = false
        loadAvailableSkills()
      }
    }

    const goToSkillManagement = () => {
      showSkillDropdown.value = false
      uni.navigateTo({ url: '/pages/plugin-market/plugin-market' })
    }

    // Rollback Dialog State
    const showRollbackDialog = ref(false)
    const rollbackTargetIndex = ref(-1)
    const rollbackTargetContent = ref('')
    const rollbackTargetId = ref(null)

    const showAssistantMenu = ref(false)

    // Upload Dialog State
    const showUploadDialog = ref(false)
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
      if (selectedUploadParent.value === null) return '根目录'
      const folder = allProjectFiles.value.find(f => f.id === selectedUploadParent.value)
      return folder ? folder.name : '根目录'
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

    const handleOpenFile = (f) => {
        // Emit open-file event to parent
        // f.fileName is the name. Backend might need full path if it's nested.
        // But for now we just emit what we have.
        // Assuming parent can handle opening by name or request details if needed.
        // Or send { name: f.fileName, path: f.fileName }
        console.log('Opening file:', f.fileName)
        emit('open-file', { name: f.fileName })
        showModifiedPopup.value = false
        showNewPopup.value = false
    }

    // Scroll to bottom when bubbles change
    watch(() => bubbles.value.length, () => {
       scrollToBottom()
    })
    // Deep watch active bubble changes (e.g. streaming content)
    watch(bubbles, () => {
       // Optional: throttle scroll?
       // For now simple trigger
    }, { deep: true })

    const scrollToBottom = () => {
       nextTick(() => {
         scrollTop.value += 10000
       })
    }


    // --- PPT Config Logic ---
    const showPptConfigDialog = ref(false)
    const pptConfigData = ref(null)
    const pptExportEditable = ref(false)

    const cancelPptConfig = () => {
       showPptConfigDialog.value = false
       pptConfigData.value = null
       // Optionally notify backend of cancellation? Not strictly needed as AI task handles timeout or just hangs.
       // Ideally we should tell user "Cancelled".
       bubbles.value.push({
          role: 'ASSISTANT',
          content: 'PPT 生成已取消。',
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
          bubbles.value.push({
             role: 'ASSISTANT',
             content: `开始生成 PPT (${pptExportEditable.value ? '可编辑版' : '高清图片版'})...\n请留意上方进度条。`,
             timestamp: new Date().toLocaleTimeString()
          })

       } catch (err) {
          console.error("Failed to start PPT generation:", err)
          uni.showToast({ title: '启动生成失败', icon: 'none' })
       }
    }

    // --- Rollback Functions ---
    const openRollbackDialog = (msg, index) => {
      if (isStreaming.value) {
        uni.showToast({ title: '请等待当前对话完成', icon: 'none' })
        return
      }
      rollbackTargetIndex.value = index
      rollbackTargetContent.value = msg.content || ''
      rollbackTargetId.value = msg.id
      showRollbackDialog.value = true
    }

    const cancelRollback = () => {
      showRollbackDialog.value = false
      rollbackTargetIndex.value = -1
      rollbackTargetContent.value = ''
      rollbackTargetId.value = null
    }

    const confirmRollback = async () => {
      const targetIndex = rollbackTargetIndex.value
      const targetId = rollbackTargetId.value
      const content = rollbackTargetContent.value

      // 关闭对话框
      showRollbackDialog.value = false

      try {
        // 1. 调用后端API删除数据库中的消息
        if (targetId && currentConversationId.value) {
          await rollbackConversation(currentConversationId.value, targetId)
        }

        // 2. 在前端删除bubbles
        const rolledBackContent = rollbackToMessage(targetIndex)

        // 3. 将回退的消息内容放入输入框
        if (richInput.value && content) {
          richInput.value.innerHTML = escapeHtml(content)
          inputPrompt.value = content
        }

        // 4. 通知父组件刷新历史
        emit('refresh-history')

        uni.showToast({ title: '已回退', icon: 'success' })
      } catch (err) {
        console.error('[ChatInterface] Rollback failed:', err)
        uni.showToast({ title: '回退失败: ' + (err.message || '未知错误'), icon: 'none' })
      }

      // 重置状态
      rollbackTargetIndex.value = -1
      rollbackTargetContent.value = ''
      rollbackTargetId.value = null
    }

    const startNewChat = () => {
      setConversationId(null)  // This now triggers resetSSE internally
      clearBubbles()           // Use composable method
      emit('new-chat')
    }

    const handleSubmit = async () => {
      // 流式进行中禁止再发送（回车路径不走发送按钮的 abort 分支）：
      // 必须在清空输入框之前拦截，否则用户输入会被静默丢弃
      if (isStreaming.value) return
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

      // 禁止发送纯空消息：必须有文本、图片或文件上下文至少其一
      if (!text && !hasImages && !hasFiles) {
        if (isStreaming.value) {
          // 如果正在流式传输，允许中断操作
          return
        }
        // 显示提示
        if (typeof uni !== 'undefined') {
          uni.showToast({ title: '请输入消息内容', icon: 'none' })
        }
        return
      }

      const prompt = text

      if (richInput.value) richInput.value.innerHTML = ''
      inputPrompt.value = ''

      // Use context files as fileList
      const fileListToSend = contextFiles.value.map(f => ({
        id: f.id,  // useAgentStream.js uses f.id to extract fileIds
        fileName: f.name,
        fileType: f.fileType,
        wpsFileId: f.wpsFileId,
        isDir: f.isDir
      }))

      // Save images and context files for user bubble display
      const imagesToShow = pastedImages.value.map(img => ({ path: img.path }))
      const contextFilesToShow = contextFiles.value.map(f => ({
        id: f.id,
        name: f.name,
        isDir: f.isDir
      }))

      // Clear context files and images after sending
      contextFiles.value = []
      pastedImages.value = []

      // Build activeContext from props.activeTab (only if no manual context provided)
      // Priority: manual contextFiles > activeContext
      const activeContext = (fileListToSend.length === 0 && props.activeTab) ? {
        id: String(props.activeTab.id || props.activeTab.wpsFileId),
        name: props.activeTab.name,
        fileType: props.activeTab.fileType,
        wpsFileId: props.activeTab.wpsFileId,
        pane: props.activeTabPane
      } : null

      if (activeContext) {
        console.log('[ChatInterface] Auto-attaching active context:', activeContext.name)
      }

      await sendMessage({
        prompt,
        contentHtml, // Pass HTML with inline tags for bubble display
        fileList: fileListToSend,
        projectId: props.projectId,
        modelId: currentModelId.value,
        mode: currentModeId.value, // Agent 模式: ASK, PLAN, AGENT
        assistantId: props.currentAssistantId,
        activeContext, // NEW: Auto-detected active tab context
        pinnedSkillId: pinnedSkillId.value,
        // Pass for user bubble display
        _userImages: imagesToShow,
        _userContextFiles: contextFilesToShow
      })

      scrollToBottom()
    }

    // 步数超限后的一键续跑：等价于用户输入「继续」（后端 depth 归零重新起循环），
    // 复用 sendMessage 的会话/模型/助手上下文。
    const handleContinue = async () => {
      if (isStreaming.value) return
      await sendMessage({
        prompt: '继续',
        projectId: props.projectId,
        modelId: currentModelId.value,
        mode: currentModeId.value,
        assistantId: props.currentAssistantId
      })
      scrollToBottom()
    }

    // --- History Loading Logic ---
    const loadMessages = (conversationId, loadedMsgs) => {
       console.log('[ChatInterface] Loading history...', loadedMsgs.length)
       setConversationId(conversationId)  // This triggers resetSSE internally
       clearBubbles()  // Clear existing using composable method

       loadedMsgs.forEach(msg => {
          const role = msg.role?.toUpperCase() || 'USER'

          if (role === 'USER') {
              bubbles.value.push({
                  id: msg.id,
                  role: 'USER',
                  content: msg.content,
                  timestamp: formatTime(msg.createdAt)
              })
          } else {
              // Convert Assistant Message to Root Bubble Structure
              // 1. Check for XML tags
              const content = msg.content || ''

              // Simple Heuristic: If content has <thinking> or <title>, try to parse?
              // Or just dump content into Walkthrough for legacy safety.
              // IF we want to support old artifacts in history, we parse them.

              // Create default bubble
              const bubble = {
                  id: msg.id,
                  role: 'ASSISTANT',
                  thinking: { status: 'done', content: '', duration: 0 },
                  title: '',
                  processes: [],
                  artifacts: [],
                  walkthrough: '',
                  content: '', // Main Answer (from <final> tag)
                  timestamp: formatTime(msg.createdAt)
              }

              // Extract Artifacts
              const artifactRegex = /<artifact\s+type="([^"]+)"(?:[^>]*)>([\s\S]*?)<\/artifact>/g
              let remaining = content
              let match
              while ((match = artifactRegex.exec(content)) !== null) {
                 const type = match[1]
                 const artContent = match[2]
                 bubble.artifacts.push({
                     id: `hist-art-${Math.random()}`,
                     type,
                     status: 'draft',
                     data: { content: artContent },
                     fileName: type === 'task_list' ? 'Task List' : 'Plan'
                 })
                 remaining = remaining.replace(match[0], '')
              }

              // Extract thinking
              const thinkingMatch = remaining.match(/<thinking>([\s\S]*?)<\/thinking>/)
              if (thinkingMatch) {
                  bubble.thinking.content = thinkingMatch[1]
                  remaining = remaining.replace(thinkingMatch[0], '')
              }

              // Extract title
              const titleMatch = remaining.match(/<title>([\s\S]*?)<\/title>/)
              if (titleMatch) {
                  bubble.title = titleMatch[1]
                  remaining = remaining.replace(titleMatch[0], '')
              }

              // Extract <final> tag content -> bubble.content
              const finalMatch = remaining.match(/<final>([\s\S]*?)<\/final>/)
              if (finalMatch) {
                  bubble.content = finalMatch[1].trim()
                  remaining = remaining.replace(finalMatch[0], '')
              }

              // Extract <walkthrough> tag content
              const walkthroughMatch = remaining.match(/<walkthrough>([\s\S]*?)<\/walkthrough>/)
              if (walkthroughMatch) {
                  bubble.walkthrough = walkthroughMatch[1].trim()
                  remaining = remaining.replace(walkthroughMatch[0], '')
              }

              // Extract <process> tags and their content (steps, tool_code, tool_output)
              const processRegex = /<process(?:\s+name="([^"]*)")?[^>]*>([\s\S]*?)<\/process>/g
              let processMatch
              while ((processMatch = processRegex.exec(remaining)) !== null) {
                  const processName = processMatch[1] || 'Processing'
                  const processContent = processMatch[2]

                  const proc = {
                      id: `hist-proc-${Date.now()}-${Math.random()}`,
                      title: processName,
                      isExpanded: false, // Collapse by default in history
                      items: [],  // CHANGED: Use items array instead of steps for consistency
                      steps: [],  // Keep for backward compatibility
                      content: ''
                  }

                  // Extract <step> tags
                  const stepRegex = /<step>([\s\S]*?)<\/step>/g
                  let stepMatch
                  while ((stepMatch = stepRegex.exec(processContent)) !== null) {
                      proc.items.push({
                          type: 'step',
                          status: 'done',
                          text: stepMatch[1].trim()
                      })
                  }

                  // Extract <tool_code> and <tool_output> - create tool items
                  const toolCodeMatch = processContent.match(/<tool_code>([\s\S]*?)<\/tool_code>/)
                  const toolOutputMatch = processContent.match(/<tool_output([^>]*)>([\s\S]*?)<\/tool_output>/)

                  if (toolCodeMatch) {
                      const code = toolCodeMatch[1].trim()
                      // toolOutputMatch[1] = attributes string, toolOutputMatch[2] = content
                      const outputAttrs = toolOutputMatch ? toolOutputMatch[1] : ''
                      const output = toolOutputMatch ? toolOutputMatch[2].trim() : ''

                      // First: Try to parse status from attribute (new format)
                      let status = 'success'
                      const statusAttrMatch = outputAttrs.match(/status="([^"]*)"/)
                      if (statusAttrMatch) {
                          const statusAttr = statusAttrMatch[1]
                          if (statusAttr === 'SUCCESS') {
                              status = 'success'
                          } else if (statusAttr === 'FAILURE') {
                              status = 'error'
                          }
                      } else {
                          // Fallback: Determine status from output content (legacy format)
                          if (output.includes('Error') || output.includes('Exception') || output.includes('FAILURE')) {
                              status = 'error'
                          }
                      }

                      proc.items.push({
                          type: 'tool',
                          code: code,
                          output: output,
                          status: status
                      })
                  }

                  bubble.processes.push(proc)
              }

              // Clean up process tags from remaining
              remaining = remaining.replace(/<process[^>]*>[\s\S]*?<\/process>/g, '')

              // Any remaining untagged text goes to content (fallback for legacy)
              remaining = remaining.trim()
              if (remaining && !bubble.content) {
                  bubble.content = remaining
              }

              bubbles.value.push(bubble)
          }
       })

       // 后台续跑关键一步：切回会话时重连 SSE。后端 connect 会推 run_state
       // （运行中还会推 state_recovery 全量续流 + plan_update 恢复任务清单），
       // 已结束的会话则只是挂一条静默连接，不影响静态历史展示。
       reattachSSE(conversationId)

       scrollToBottom()
    }

    // 近期对话列表的状态点（数据字段由宿主 fetchChatHistory 映射）
    const recentDotClass = (h) => {
       if (!h) return ''
       if (h.runStatus === 'RUNNING') return 'dot-running'
       if (h.runStatus === 'PAUSED' || h.runStatus === 'AWAITING_APPROVAL') return 'dot-attention'
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

       if (diffMins < 1) return '刚刚'
       if (diffMins < 60) return `${diffMins}分钟前`
       if (diffHours < 24) return `${diffHours}小时前`
       if (diffDays < 7) return `${diffDays}天前`
       return `${d.getMonth()+1}/${d.getDate()}`
    }

    // Clean title - strip XML tags like <thinking>, <process>, etc.
    const cleanTitle = (title) => {
       if (!title) return '新对话'
       // Remove common XML tags
       let cleaned = title
         .replace(/<thinking>[\s\S]*?<\/thinking>/gi, '')
         .replace(/<process[^>]*>[\s\S]*?<\/process>/gi, '')
         .replace(/<step>[\s\S]*?<\/step>/gi, '')
         .replace(/<tool_code>[\s\S]*?<\/tool_code>/gi, '')
         .replace(/<tool_output>[\s\S]*?<\/tool_output>/gi, '')
         .replace(/<artifact[^>]*>[\s\S]*?<\/artifact>/gi, '')
         .replace(/<final>[\s\S]*?<\/final>/gi, '')
         .replace(/<[^>]+>/g, '') // Remove any remaining tags
         .trim()
       return cleaned || '新对话'
    }

    const handleRichInput = (e) => {
        inputPrompt.value = e.target.innerText

        // Sync inline tags with contextFiles ref
        // When user deletes a tag from the input, also remove it from contextFiles
        syncContextFilesWithInlineTags()
    }

    const handleInputClick = (e) => {
      // Check if clicked the close button of a tag
      if (e.target.classList.contains('tag-close')) {
        const tag = e.target.closest('.context-tag-inline')
        if (tag) {
          tag.remove()
          syncContextFilesWithInlineTags()
          // Update text model
          if (richInput.value) {
            inputPrompt.value = richInput.value.innerText
          }
        }
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
              const reader = new FileReader()
              reader.onload = (evt) => {
                pastedImages.value.push({
                  file: file,
                  path: evt.target.result
                })
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
      if (!e.shiftKey) {
        // Plain Enter -> Send
        e.preventDefault()
        handleSubmit()
      } else {
        // Shift+Enter -> New line (default behavior, do not prevent)
      }
    }

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
      // Check if file already exists by ID
      if (!contextFiles.value.find(f => f.id === file.id)) {
        const fileData = {
          id: file.id,
          name: file.name,
          fileType: file.fileType,
          wpsFileId: file.wpsFileId,
          isDir: file.isDir || file.fileType === 'folder'
        }
        contextFiles.value.push(fileData)

        // Insert inline tag into rich input
        insertContextTagToInput(fileData)

        console.log('[ChatInterface] File added as context:', file.name)
      }
    }

    // --- Insert inline tag into contenteditable ---
    const insertContextTagToInput = (file) => {
      if (!richInput.value) return

      const icon = file.isDir ? '/static/folder-closed.png' : '/static/document.png'
      const displayName = truncateName(file.name)
      // 文件名由项目成员自由命名（后端只挡路径分隔符），这段字符串会直接进 DOM，必须转义
      const safeName = escapeHtml(file.name)
      const safeDisplayName = escapeHtml(displayName)

      const tagHtml = `
        <span class="context-tag-inline" contenteditable="false" data-file-id="${file.id}" data-is-dir="${file.isDir ? 'true' : 'false'}" title="${safeName}">
          <img src="${icon}" class="tag-icon"/>
          <span class="tag-at">@</span>
          <span class="tag-name">${safeDisplayName}</span>
          <span class="tag-close">×</span>
        </span>&nbsp;`.replace(/\s+/g, ' ').trim()

      // Insert at cursor or append to end
      const sel = window.getSelection()
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

    const removeContextFile = (index) => {
      contextFiles.value.splice(index, 1)
    }

    const removePastedImage = (index) => {
      pastedImages.value.splice(index, 1)
    }

    // --- Upload Dialog Methods ---
    const triggerFileSelect = async () => {
      // Load project folders for folder selector
      await loadProjectFolders()

      // Reset state
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
        console.log('[ChatInterface] Loaded project files for folder selector:', files?.length)
      } catch (e) {
        console.error('[ChatInterface] Failed to load project folders:', e)
        allProjectFiles.value = []
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
          uni.showToast({ title: '选择文件失败', icon: 'none' })
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
        return '未知文件夹'
      }
      if (folderId && folderId.name) {
        return buildFolderPath(folderId)
      }
      return '根目录'
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
          title: '新建文件夹',
          editable: true,
          placeholderText: '请输入文件夹名称',
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
          uni.showToast({ title: '文件夹创建成功', icon: 'success' })
        }
      } catch (error) {
        console.error('[ChatInterface] Create folder failed:', error)
        uni.showToast({ title: error.message || '创建文件夹失败', icon: 'none' })
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
        jpg: 'image', jpeg: 'image', png: 'image', gif: 'image', webp: 'image',
        md: 'markdown'
      }
      return typeMap[ext] || 'other'
    }

    // Confirm upload and add to context (like drag-drop)
    const confirmUploadAndAddContext = async () => {
      if (uploadSelectedFiles.value.length === 0) {
        uni.showToast({ title: '请选择要上传的文件', icon: 'none' })
        return
      }

      if (!props.projectId) {
        uni.showToast({ title: '项目ID未设置', icon: 'none' })
        return
      }

      isUploading.value = true
      const projectId = typeof props.projectId === 'string' ? Number(props.projectId) : props.projectId
      const parentId = selectedUploadParent.value
      const filesToUpload = [...uploadSelectedFiles.value]

      // Close dialog
      showUploadDialog.value = false
      uploadSelectedFiles.value = []

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
                console.warn('[ChatInterface] File content upload failed, file record created:', uploadErr)
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
          }
        }

        uni.showToast({ title: `已添加 ${filesToUpload.length} 个文件`, icon: 'success' })
      } catch (error) {
        console.error('[ChatInterface] Upload failed:', error)
        uni.showToast({ title: error.message || '上传失败', icon: 'none' })
      } finally {
        isUploading.value = false
      }
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

    // 外部面板（如股东大会核查）注入预设 prompt：强制 AGENT 模式发送
    // （skill 注入依赖 prompt 文本内的触发词；ASK 模式会跳过注入）。
    // 返回本次会话 ID，供调用方把业务对象绑定到该会话。
    const sendExternalPrompt = async (prompt) => {
      if (!prompt) return null
      if (isStreaming.value) {
        uni.showToast({ title: 'AI 正在执行其他任务，请稍后再试', icon: 'none' })
        return null
      }
      await sendMessage({
        prompt,
        projectId: props.projectId,
        modelId: currentModelId.value,
        mode: 'AGENT',
        assistantId: props.currentAssistantId
      })
      scrollToBottom()
      return currentConversationId.value
    }

    // Expose methods for parent ref access
    expose({ addFile, loadMessages, loadConversationMetadata, sendExternalPrompt })

    return {
       bubbles,
       isStreaming,
       inputPrompt,
       richInput,
       tokenUsage,
       scrollTop,
       isDragging,
       contextFiles,
       pastedImages,
       handleSubmit,
       abort,
       handleRichInput,
       handleInputClick,
       handlePaste,
       handleEnterKey,
       startNewChat,
       loadMessages,
       formatTime,
       formatRelativeTime,
       cleanTitle,
       recentDotClass,
       agentRunStatus,
       addFile,
       removeContextFile,
       removePastedImage,
       truncateName,
       escapeHtml,
       // Rollback
       showRollbackDialog,
       rollbackTargetContent,
       openRollbackDialog,
       cancelRollback,
       confirmRollback,
       // Menu
       showAssistantMenu,
       toggleAssistantMenu: () => showAssistantMenu.value = !showAssistantMenu.value,
       selectAssistant: (a) => emit('update:currentAssistantId', a.id),
       // Model
       currentModelId,
       currentModelName,
       toggleModelDropdown: () => {
         showModelDropdown.value = !showModelDropdown.value
         if (showModelDropdown.value) showModeDropdown.value = false
       },
       selectModel,
       showModelDropdown,
       availableModels,
       // Agent Mode
       currentModeId,
       currentModeName,
       currentModeIcon,
       toggleModeDropdown,
       selectMode,
       showModeDropdown,
       availableModes,
       // Skill 选择
       showSkillDropdown,
       availableSkills,
       pinnedSkillId,
       skillChipLabel,
       toggleSkillDropdown,
       selectSkill,
       goToSkillManagement,
       // Artifact
       handleArtifactOpenTab: (art) => emit('artifact-open-tab', art),
       handleArtifactApprove: async (art) => {
          console.log('[ChatInterface] Artifact Approved:', art.id)
          // 审批后使用 AGENT 模式执行计划
          await sendMessage({
             prompt: `已批准实施计划: ${art.fileName}`,
             fileList: [],
             projectId: props.projectId,
             modelId: currentModelId.value,
             mode: 'AGENT', // 审批后使用 Agent 模式执行
             assistantId: props.currentAssistantId
          })
          scrollToBottom()
       },
       // Upload Dialog
       showUploadDialog,
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
       lastHeartbeat,
       // PPT Config
       showPptConfigDialog,
       pptExportEditable,
       pptConfigData,
       cancelPptConfig,
       confirmPptGeneration,
       // File Changes Status
       fileChanges,
       // 步数超限一键继续
       agentPaused,
       handleContinue,
       modifiedFiles,
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

<style lang="scss" scoped>
.chat-interface {
  display: flex;
  flex-direction: column;
  height: 100%;
  width: 100%;
  max-width: 100%;
  background: $awd-chrome-panel;
  position: relative;
  overflow: hidden; /* Prevent children from overflowing */
  box-sizing: border-box;
}

.chat-header {
  height: 36px;
  border-bottom: 1px solid $awd-chrome-line;
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 0 16px;
  background: $awd-chrome-panel;
  flex-shrink: 0;
}

.header-left {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}

/* 助手身份：渐变头像小方块 + 衬线名（对齐原型 bu-head） */
.assistant-avatar {
  width: 22px;
  height: 22px;
  border-radius: 8px;
  background: linear-gradient(135deg, #2D7A52, #5BD197);
  color: $awd-text-on-dark;
  font-family: $awd-font-serif;
  font-weight: 600;
  font-size: 11px;
  line-height: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}

.header-left .project-name-display {
  font-weight: 600;
  font-family: $awd-font-serif;
  font-size: 13px;
  color: $awd-text-on-dark;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.header-actions {
  display: flex;
  gap: 12px;
  position: relative;
}

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
  color: $awd-text-on-dark-2;
  display: flex;
  align-items: center;
  justify-content: center;
  position: relative;
  transition: background 0.15s ease;
}
/* 深底下 PNG 图标（深灰描线）不可见：统一翻白；
   hover/active 不再切绿色 hover 变体（同样看不清），改为提亮 + 深底 hover 面 */
.icon-btn .btn-icon {
  width: 15px;
  height: 15px;
  display: block;
  object-fit: contain;
  filter: grayscale(1) brightness(0) invert(0.72);
}
.icon-btn .btn-icon.hover {
  display: none;
}
.icon-btn:hover {
  background: $awd-chrome-hover;
}
.icon-btn:hover .btn-icon.default {
  display: block;
  filter: grayscale(1) brightness(0) invert(0.95);
}
.icon-btn:hover .btn-icon.hover {
  display: none;
}
/* Prevent layout shift when active */
.icon-btn.active {
  background: rgba(91, 209, 151, 0.15);
  border-radius: 6px;
}
.icon-btn.active .btn-icon.default {
  display: block;
  filter: grayscale(1) brightness(0) invert(0.95);
}
.icon-btn.active .btn-icon.hover {
  display: none;
}
.icon-btn.mini {
  padding: 4px;
}
.icon-btn.mini .btn-icon {
  width: 14px;
  height: 14px;
}
/* File add button with border */
.icon-btn.file-add-btn {
  border: 1px solid $awd-chrome-active;
  border-radius: 4px;
  padding: 3px;
}
.icon-btn.file-add-btn:hover {
  border-color: rgba(91, 209, 151, 0.5);
}

.message-list {
  flex: 1;
  overflow-y: auto;
  overflow-x: hidden; /* Prevent horizontal overflow */
  padding: 12px;
  min-width: 0; /* Allow flex shrinking */
  width: 100%;
  box-sizing: border-box;
}

.message-list::-webkit-scrollbar {
  width: 6px;
}
.message-list::-webkit-scrollbar-thumb {
  background: $awd-chrome-line;
  border-radius: 3px;
}

.message-list-content {
  padding-bottom: 20px;
  max-width: 100%; /* Use full available width */
  width: 100%;
  box-sizing: border-box;
  min-width: 0; /* Allow flex shrinking */
  overflow: hidden; /* Prevent children from overflowing */
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
  background: rgba(62, 142, 99, 0.15); /* 深底用户消息：绿 rgba 底（对齐原型 msg.me） */
  padding: 8px 12px;
  border-radius: 6px 6px 0 6px;
  max-width: 80%;
  color: $awd-text-on-dark;
  font-size: 13px;
  line-height: 1.5;
  box-shadow: none;
  border: 1px solid rgba(62, 142, 99, 0.3);
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
  font-size: 10px;
  color: $awd-text-on-dark-3;
  font-family: $awd-font-mono;
  /* margin-top: 4px; */
}
.user-bubble .bubble-timestamp { text-align: right; }

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
  font-family: $awd-font-serif;
  color: $awd-text-on-dark;
  margin-bottom: 8px;
  display: block;
}

.welcome-subtitle {
  font-size: 15px;
  font-weight: 400;
  color: $awd-text-on-dark-2;
  display: block;
}

.input-card {
  background: $awd-chrome-hover;
  border: 1px solid $awd-chrome-active;
  border-radius: 8px;
  padding: 16px;
  width: 100%;
  box-sizing: border-box;
  box-shadow: 0 4px 20px rgba(0, 0, 0, 0.25);
  position: relative;
}

/* Recent History Section - 紧凑专业样式 */
.recent-history-header {
  font-size: 12px;
  font-weight: 500;
  color: $awd-text-on-dark-3;
  margin-bottom: 8px;
  text-transform: uppercase;
  letter-spacing: 0.5px;
}

.recent-history {
  display: flex;
  flex-direction: column;
  gap: 0; /* 无间距 */
  border: 1px solid $awd-chrome-line;
  border-radius: 4px; /* 减小圆角 */
  overflow: hidden;
  background: transparent;
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
.conv-dot.dot-running { background: #5BD197; animation: conv-dot-pulse 1.2s ease-in-out infinite; }
.conv-dot.dot-attention { background: #F5B60D; }
.conv-dot.dot-unread { background: #3B82F6; }
.conv-dot.dot-error { background: #E74C3C; }
.conv-dot.header-dot {
  position: absolute;
  top: 2px;
  right: 2px;
  width: 7px;
  height: 7px;
  margin: 0;
  border: 1px solid $awd-chrome-panel;
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
  background: transparent;
  border-bottom: 1px solid $awd-chrome-line;
  border-radius: 0; /* 无圆角 */
  cursor: pointer;
  transition: background 0.15s ease;
  box-shadow: none;
}

.history-item:last-child {
  border-bottom: none;
}

.history-item:hover {
  background: $awd-chrome-hover;
}

.history-title {
  font-size: 13px;
  color: $awd-text-on-dark;
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-weight: 500;
  text-align: left;
}

.history-time {
  font-size: 11px;
  color: $awd-text-on-dark-3;
  font-family: $awd-font-mono;
  margin-left: 12px;
  flex-shrink: 0;
  text-align: right;
  min-width: 60px;
}

.history-empty-placeholder {
  font-size: 13px;
  color: $awd-text-on-dark-3;
  text-align: center;
  padding: 24px 0;
}

.history-disclaimer {
  font-size: 12px;
  color: $awd-text-on-dark-3;
  text-align: center;
  padding: 16px 0 0;
  margin-top: 12px;
  border-top: 1px solid $awd-chrome-line;
}

.chat-input-rich {
  min-height: 60px;
  max-height: 200px;
  overflow-y: auto;
  outline: none;
  font-size: 15px;
  line-height: 1.5;
  color: $awd-text-on-dark;
}

.chat-input-rich:empty:before {
  content: attr(data-placeholder);
  color: $awd-text-on-dark-3;
}

.input-footer {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-top: 12px;
  padding-top: 12px;
  border-top: 1px solid $awd-chrome-line;
}

.action-bar-left {
  display: flex;
  gap: 12px;
  align-items: center;
  /* 允许整条工具栏收缩，避免钉选长名 Skill 时把发送按钮挤出面板 */
  min-width: 0;
}

.model-selector {
  font-size: 13px;
  color: $awd-text-on-dark-2;
  cursor: pointer;
  position: relative;
  display: flex;
  align-items: center;
  gap: 4px;
  padding: 2px 6px;
  border-radius: 2px;
  transition: background 0.15s ease;
  white-space: nowrap;
}
.model-selector:hover {
  background: $awd-chrome-line;
}

.dropdown-arrow {
  font-size: 8px;
  color: $awd-text-on-dark-3;
  transition: color 0.15s ease;
}
.model-selector:hover .dropdown-arrow {
  color: $awd-text-on-dark-2;
}

.model-dropdown {
  position: absolute;
  left: 0;
  background: #fff;
  border: 1px solid #e0e0e0;
  border-radius: 8px;
  box-shadow: 0 4px 16px rgba(0, 0, 0, 0.12);
  z-index: 1001;
  min-width: 180px;
  max-height: 200px;
  overflow-y: auto;
  padding: 4px 0;
}

/* 向下展开 (新对话页面) */
.model-dropdown.down {
  top: calc(100% + 4px);
}

/* 向上展开 (对话中) */
.model-dropdown.up {
  bottom: calc(100% + 4px);
}

/* ============= Mode Selector (Agent/Ask/Plan) ============= */
/* 深底下弃用蓝色系，统一 mint 点缀（对齐原型 skill 胶囊配色） */
.mode-selector {
  font-size: 13px;
  color: $awd-text-on-dark-2;
  cursor: pointer;
  position: relative;
  display: flex;
  align-items: center;
  gap: 4px;
  padding: 2px 8px;
  border-radius: 2px;
  background: rgba(91, 209, 151, 0.10);
  border: 1px solid rgba(91, 209, 151, 0.25);
  transition: all 0.15s ease;
}
.mode-selector:hover {
  background: rgba(91, 209, 151, 0.18);
  border-color: rgba(91, 209, 151, 0.35);
}

.mode-icon {
  font-size: 14px;
}

.mode-name {
  font-weight: 500;
  color: $awd-mint;
}

.mode-dropdown {
  position: absolute;
  left: 0;
  background: #fff;
  border: 1px solid #e0e0e0;
  border-radius: 10px;
  box-shadow: 0 4px 20px rgba(0, 0, 0, 0.15);
  z-index: 1001;
  min-width: 160px;
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
  background: #f5f5f5;
}
.mode-option.active {
  background: rgba(59, 130, 246, 0.1);
}
.mode-option.active .mode-option-name {
  color: #3b82f6;
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
  color: #333;
}

.mode-option-desc {
  font-size: 11px;
  color: #888;
}

.dropdown-menu {
  position: absolute;
  top: 100%;
  right: 0;
  background: #fff;
  border: 1px solid #e2e8f0;
  border-radius: 10px;
  box-shadow: 0 8px 24px rgba(0, 0, 0, 0.12), 0 2px 8px rgba(0, 0, 0, 0.08);
  z-index: 1000;
  min-width: 240px;
  padding: 8px 0;
  margin-top: 4px;
}

/* Assistant Dropdown Panel - matches history drawer positioning */
.assistant-dropdown-panel {
  position: absolute;
  top: 36px; /* Exactly below header */
  left: 0;
  right: 0;
  background: #fff;
  border-bottom: 1px solid #e2e8f0;
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.08);
  z-index: 1001;
  display: flex;
  flex-direction: column;
  max-height: 400px;
  overflow-y: auto;
  animation: slideDown 0.15s ease-out;
  border-radius: 0 0 8px 8px;
}

@keyframes slideDown {
  from { opacity: 0; transform: translateY(-5px); }
  to { opacity: 1; transform: translateY(0); }
}

.assistant-menu-header {
  padding: 6px 12px;
  font-size: 11px;
  font-weight: 600;
  color: #64748b;
  background: #f8f9fa;
  border-bottom: 1px solid #f1f5f9;
}

.assistant-menu-item {
  display: flex;
  align-items: center;
  padding: 10px 12px;
  font-size: 13px;
  color: #334155;
  cursor: pointer;
  border-bottom: 1px solid #f8f9fa;
  transition: all 0.15s ease;
}

.assistant-menu-item:hover {
  background: #f1f5f9;
}

.assistant-menu-item.active {
  background: rgba(26, 83, 54, 0.08);
  color: #1A5336;
  font-weight: 500;
}

.assistant-item-name {
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
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
  color: #64748b;
  background: #f8f9fa;
  border-bottom: 1px solid #f1f5f9;
}

.menu-item {
  padding: 10px 12px;
  cursor: pointer;
  display: flex;
  justify-content: space-between;
  align-items: center;
  font-size: 13px;
  color: #334155;
  transition: all 0.15s ease;
  border-bottom: 1px solid #f8f9fa;
}
.menu-item:hover {
  background: #f1f5f9;
  color: #1A5336;
}
.menu-item.active {
  background: rgba(26, 83, 54, 0.08);
  color: #1A5336;
  font-weight: 500;
}

/* Menu item name (takes up flex space) */
.menu-item-name {
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* Setting icon wrapper with hover effect */
.setting-icon-wrapper {
  width: 24px;
  height: 24px;
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  border-radius: 4px;
  transition: background 0.15s ease;
  flex-shrink: 0;
  margin-left: 8px;
}

.setting-icon-wrapper:hover {
  background: rgba(26, 83, 54, 0.08);
}

.setting-icon {
  width: 16px;
  height: 16px;
}

.setting-icon.hover {
  display: none;
}

.setting-icon-wrapper:hover .setting-icon.default {
  display: none;
}

.setting-icon-wrapper:hover .setting-icon.hover {
  display: block;
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

.model-option {
  padding: 10px 14px;
  cursor: pointer;
  font-size: 13px;
  color: #333;
  transition: background 0.15s ease;
}
.model-option:hover {
  background: rgba(26, 83, 54, 0.08);
}
.model-option.active {
  color: #1A5336;
  font-weight: 500;
  background: rgba(26, 83, 54, 0.04);
}

/* 发送钮：mint 实心 + 深色箭头（对齐原型 chat-input .send） */
.send-btn {
  background: $awd-mint;
  color: $awd-chrome-panel;
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
.send-btn:hover {
  background: lighten($awd-mint, 8%);
}
.send-btn.disabled {
  background: $awd-chrome-active;
  color: $awd-text-on-dark-3;
  cursor: not-allowed;
}
.send-btn.disabled:hover {
  background: $awd-chrome-active;
}
.send-btn.stopping {
  background: $awd-brick;
  color: $awd-text-on-dark;
}
.send-btn.stopping:hover {
  background: darken($awd-brick, 8%);
}
.send-icon {
  font-size: 16px;
  font-weight: bold;
  display: inline-block;
}

.input-area-wrapper {
  padding: 16px 24px;
  background: $awd-chrome-panel;
  border-top: 1px solid $awd-chrome-line;
  display: flex;
  flex-direction: column;  /* Fix: Stack children vertically */
  align-items: stretch;    /* Fix: Make children full width */
  flex-shrink: 0;
  min-width: 0;
  box-sizing: border-box;
}

/* Context Files Styles */
.context-files-list {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin: 8px 0;
  padding: 8px 0;
  border-bottom: 1px solid #f0f0f0;
}

.context-file-tag {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  background: #e8f4fd;
  border: 1px solid #bce0fd;
  border-radius: 14px;
  padding: 4px 8px 4px 6px;
  font-size: 12px;
  color: #1a73e8;
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
  color: #999;
  cursor: pointer;
  font-size: 14px;
  line-height: 1;
}

.context-file-remove:hover {
  color: #e53935;
}

/* =============================================
   AI Workdeck Style - Input Image Preview
   ============================================= */
.input-images-preview {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: 12px;
  padding-bottom: 8px;
}

.preview-image-item {
  position: relative;
  width: 48px;
  height: 48px;
  border-radius: 8px;
  overflow: hidden;
  box-shadow: 0 2px 6px rgba(26, 83, 54, 0.15);
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
  background: linear-gradient(135deg, #1A5336 0%, #2D7A52 100%);
  color: #fff;
  border-radius: 50%;
  font-size: 12px;
  text-align: center;
  line-height: 18px;
  cursor: pointer;
  box-shadow: 0 1px 3px rgba(26, 83, 54, 0.3);
  transition: all 0.15s ease;
}

.preview-remove:hover {
  background: linear-gradient(135deg, #2D7A52 0%, #1A5336 100%);
  transform: scale(1.1);
}

 /* =============================================
    AI Workdeck Style - Inline Context Tags (Input Box)
    Transparent background + border style
    ============================================= */
 :deep(.context-tag-inline) {
   display: inline-flex;
   align-items: center;
   gap: 3px;
   background: transparent;
   color: $awd-mint;
   padding: 3px 8px;
   border-radius: 4px;
   margin: 0 4px 2px 0;
   font-size: 12px;
   font-weight: 500;
   vertical-align: middle;
   user-select: none;
   max-width: 160px;
   border: 1px solid rgba(91, 209, 151, 0.4);
   transition: all 0.15s ease;
   position: relative;
 }

 :deep(.context-tag-inline:hover) {
   background: rgba(91, 209, 151, 0.10);
   border-color: rgba(91, 209, 151, 0.6);
   padding-right: 22px; /* Make room for close button */
 }

 :deep(.tag-icon) {
   width: 14px;
   height: 14px;
   flex-shrink: 0;
   border-radius: 2px;
   filter: grayscale(1) brightness(0) invert(0.85);
 }

 :deep(.tag-at) {
   color: $awd-mint;
   font-weight: 600;
 }

 :deep(.tag-name) {
   white-space: nowrap;
   overflow: hidden;
   text-overflow: ellipsis;
   max-width: 100px;
   color: $awd-mint;
 }

 :deep(.tag-close) {
   display: none;
   position: absolute;
   right: 6px;
   top: 50%;
   transform: translateY(-50%);
   width: 14px;
   height: 14px;
   background: rgba(91, 209, 151, 0.2);
   color: $awd-mint;
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
   background: rgba(91, 209, 151, 0.5);
   color: $awd-chrome-panel;
 }

 /* =============================================
    AI Workdeck Style - Inline Context Tags (User Bubble)
    Lighter/transparent background for visibility
    ============================================= */
.user-bubble .context-tag-inline {
  display: inline-flex;
  align-items: center;
  gap: 3px;
  background: transparent;
  color: $awd-mint;
  padding: 3px 8px;
  border-radius: 4px;
  margin: 0 4px 2px 0;
  font-size: 12px;
  font-weight: 500;
  vertical-align: middle;
  user-select: none;
  max-width: 160px;
  border: 1px solid rgba(91, 209, 151, 0.4);
  transition: all 0.15s ease;
}

.user-bubble .tag-icon {
  width: 14px;
  height: 14px;
  flex-shrink: 0;
  border-radius: 2px;
  /* Ensure icon is visible on dark background */
  filter: grayscale(1) brightness(0) invert(0.85);
}

.user-bubble .tag-at {
  color: $awd-mint;
  font-weight: 600;
}

.user-bubble .tag-name {
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  max-width: 100px;
  color: $awd-mint;
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
   Skill Selector（对话内钉选，默认自动匹配触发词）
   ============================================= */
/* AI 面板窄，工具条已有模式/模型两个文字选择器，故 Skill 用定宽图标按钮，
   当前钉选的 Skill 名靠高亮 + title + 下拉勾选表达，不占横向空间 */
.skill-selector {
  cursor: pointer;
  position: relative;
  display: flex;
  align-items: center;
  justify-content: center;
  width: 24px;
  height: 24px;
  border-radius: 4px;
  transition: background 0.15s ease;
  flex-shrink: 0;
}
.skill-selector:hover {
  background: $awd-chrome-line;
}

/* 钉选态：mint 微底 + mint 字形，让"本轮固定用了某个 Skill"一眼可见 */
.skill-selector.pinned {
  background: rgba(91, 209, 151, 0.18);
}
.skill-selector.pinned .skill-glyph {
  color: $awd-mint;
}

.skill-glyph {
  font-size: 14px;
  color: $awd-text-on-dark-3;
  line-height: 1;
}
.skill-selector:hover .skill-glyph {
  color: $awd-text-on-dark-2;
}

.skill-dropdown {
  position: absolute;
  /* 选择器位于工具条最右，向右展开会溢出 AI 面板，故右对齐 */
  right: 0;
  background: #fff;
  border: 1px solid #e0e0e0;
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

.skill-option {
  padding: 7px 12px;
  cursor: pointer;
  transition: background 0.15s ease;
}
.skill-option:hover {
  background: #f5f5f5;
}
.skill-option.active {
  background: rgba(91, 209, 151, 0.12);
}

.skill-option-text {
  display: flex;
  flex-direction: column;
  row-gap: 2px;
}

.skill-option-name {
  font-size: 13px;
  color: #2C3338;
}
.skill-option.active .skill-option-name {
  color: #1A5336;
  font-weight: 500;
}

.skill-option-desc {
  font-size: 11px;
  color: #999;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  max-width: 200px;
}

.skill-divider {
  height: 1px;
  background: #f0f0f0;
  margin: 4px 0;
}

.skill-manage {
  padding: 7px 12px;
  font-size: 12px;
  color: #666;
  cursor: pointer;
  transition: background 0.15s ease;
}
.skill-manage:hover {
  background: #f5f5f5;
  color: #1A5336;
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
   AI Workdeck Style - Upload Dialog Styles
   ============================================= */
.awd-dialog-mask {
  position: fixed;
  top: 0;
  left: 0;
  right: 0;
  bottom: 0;
  background-color: rgba(0, 0, 0, 0.4);
  backdrop-filter: blur(2px);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 2000;
}

.awd-dialog {
  width: 618px; /* Golden Ratio-ish Width */
  max-width: 90vw;
  background-color: #ffffff;
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
  border-bottom: 1px solid #f0f0f0;
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
  color: #1A5336;
  cursor: pointer;
  padding: 4px 8px;
  border-radius: 4px;
  transition: background 0.15s ease;
}

.new-folder-btn:hover {
  background: rgba(26, 83, 54, 0.08);
}

.new-folder-btn .btn-plus {
  font-size: 16px;
  font-weight: bold;
  line-height: 1;
}

.awd-dialog-title {
  font-size: 20px;
  font-weight: 600;
  color: #1A5336; /* Forest Green */
  line-height: 1.4;
  display: block;
}

.awd-dialog-subtitle {
  margin-top: 6px;
  font-size: 13px;
  color: #6C757D;
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
  border-top: 1px solid #f0f0f0;
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
  background-color: #1A5336; /* Forest Green */
  color: #ffffff;
}
.awd-btn-primary:hover {
  background-color: #16452d;
}

.awd-btn-primary.disabled {
  opacity: 0.5;
  pointer-events: none;
  background-color: #1A5336; /* Maintain color but transparent */
}

.awd-btn-secondary {
  background-color: #ffffff;
  color: #2C3338;
  border: 1px solid #E9ECEF;
}
.awd-btn-secondary:hover {
  background-color: #F8F9FA;
  border-color: #DDE2E5;
}

.form-group {
  margin-bottom: 20px;
}
.form-group:last-child {
  margin-bottom: 0;
}

.form-label {
  display: block;
  font-size: 14px;
  font-weight: 500;
  color: #2C3338;
  margin-bottom: 8px;
}

.awd-field {
  background-color: #F8F9FA;
  border: 1px solid #E9ECEF;
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
  background-color: #E6F9F0;
  border-color: #5BD197;
}

.awd-field .field-icon-img {
  width: 20px;
  height: 20px;
  flex-shrink: 0;
}

.awd-field .field-value {
  font-size: 14px;
  color: #111827;
}

.awd-field .field-placeholder {
  font-size: 14px;
  color: #9CA3AF;
}

.selected-files-list {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.selected-file-tag {
  font-size: 12px;
  background: white;
  padding: 4px 8px;
  border-radius: 4px;
  border: 1px solid #E5E7EB;
  color: #374151;
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
  background-color: #F3F4F6;
}

.folder-tree-item.active {
  background-color: #E6F9F0;
  color: #1A5336;
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
  color: #333;
}

.empty-tip {
  text-align: center;
  color: #999;
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
  background: linear-gradient(135deg, rgba(26, 83, 54, 0.05) 0%, rgba(91, 209, 151, 0.08) 100%);
  border-radius: 6px;
  border: 1px solid rgba(91, 209, 151, 0.2);
  width: 100%; /* Fix: Full width */
  box-sizing: border-box; /* Fix: Include padding in width */
  height: 28px; /* Fix: Fixed low height */
}

.token-usage-bar .token-label {
  font-size: 11px;
  font-weight: 600;
  color: #1A5336;
  text-transform: uppercase;
  letter-spacing: 0.5px;
}

.token-usage-bar .token-value {
  font-size: 12px;
  font-weight: 600;
  color: #5BD197;
  flex: 1; /* Allow value to take space if needed */
  margin-left: 4px;
}

.token-usage-bar .token-detail {
  font-size: 10px;
  color: #6C757D;
}


/* 步数超限一键继续条（深底 amber 变体） */
.continue-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin: 0 12px 6px;
  padding: 6px 12px;
  background: rgba(197, 138, 46, 0.12);
  border: 1px solid rgba(197, 138, 46, 0.35);
  border-radius: 8px;
}

.continue-hint {
  font-size: 11px;
  color: lighten($awd-amber, 22%);
}

.continue-btn {
  flex-shrink: 0;
  font-size: 12px;
  font-weight: 600;
  color: $awd-chrome-panel;
  background: $awd-mint;
  border-radius: 6px;
  padding: 4px 14px;
  cursor: pointer;
  transition: background 0.2s;
}

.continue-btn:hover {
  background: lighten($awd-mint, 8%);
}

/* Status Bar Row (File Changes + Tokens) */
.status-bar-row {
  display: flex;
  flex-direction: row;
  justify-content: space-between;
  align-items: center;
  padding: 4px 12px;
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

/* Status Buttons */
.status-btn-wrapper {
  position: relative;
}

.status-btn {
  display: flex;
  align-items: center;
  padding: 4px 12px;
  border-radius: 6px;
  background-color: transparent;
  cursor: pointer;
  font-size: 11px;
  font-weight: 600;
  color: $awd-text-on-dark-2;
  border: 1px solid $awd-chrome-active;
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
  border-color: rgba(91, 209, 151, 0.25);
  color: $awd-mint;
  background-color: rgba(91, 209, 151, 0.08);
}

.status-btn.modified:hover {
  background-color: rgba(91, 209, 151, 0.2);
}

.status-btn.created {
  border-color: rgba(91, 209, 151, 0.25);
  color: $awd-mint;
  background-color: rgba(91, 209, 151, 0.08);
}

.status-btn.created:hover {
  background-color: rgba(91, 209, 151, 0.2);
  border-color: rgba(91, 209, 151, 0.45);
}

/* Status Popup */
.status-popup {
  position: absolute;
  bottom: 100%;
  left: 0;
  margin-bottom: 8px; /* Gap */
  width: 200px;
  background: white;
  border-radius: 8px;
  box-shadow: 0 4px 12px rgba(0,0,0,0.15);
  padding: 4px 0;
  z-index: 100;
  border: 1px solid #eee;
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
  background-color: #f5f5f5;
}

.file-icon-mini {
  width: 14px;
  height: 14px;
  margin-right: 8px;
  opacity: 0.7;
}

.file-name-text {
  font-size: 13px;
  color: #333;
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
  color: #666;
}
.token-value {
  font-family: monospace;
  font-weight: 600;
}
.token-detail {
  font-size: 11px;
  color: #999;
}

/* Empty state for file change buttons */
.status-btn.empty {
  opacity: 0.7;
  cursor: default;
}
.status-btn.empty:hover {
  transform: none;
  background-color: inherit;
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

.rollback-btn {
  display: flex;
  align-items: center;
  margin-left: 8px;
  opacity: 0;
  transition: all 0.2s ease;
  cursor: pointer;
  padding: 4px 10px;
  border-radius: 99px;
  /* background-color: #E6F9F0; Mint Lightest */
  /* border: 1px solid rgba(26, 83, 54, 0.1); */
}

.user-bubble:hover .rollback-btn {
  opacity: 1;
}

.rollback-btn:hover {
  /* background-color: #5BD197; Mint Green */
  /* border-color: #1A5336; */
}

.rollback-icon-svg {
  display: flex;
  align-items: center;
  justify-content: center;
  margin-right: 4px;
  color: $awd-text-on-dark-2;
}

/* .rollback-btn:hover .rollback-icon-svg,
.rollback-btn:hover .rollback-text {
  color: white;
} */

.rollback-text {
  font-size: 11px;
  color: $awd-text-on-dark-2;
  font-weight: 600;
}

.rollback-btn:hover .rollback-icon-svg,
.rollback-btn:hover .rollback-text {
  color: $awd-mint;
}

/* Warning Dialog */
.warning-header {
  border-bottom: 2px solid #FFED4D;
}

.warning-title {
  color: #B45309;
}

.rollback-warning-content {
  padding: 10px;
}

.warning-text {
  font-size: 14px;
  color: #333;
  margin-bottom: 12px;
  display: block;
}

.doc-tip-box {
  background-color: #f0f9ff;
  border: 1px solid #bae6fd;
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
  color: #B8860B;
}

.doc-tip-text {
  font-size: 13px;
  color: #0369a1;
  display: flex;
  flex-direction: column;
}

.doc-link-text {
  font-weight: 500;
  margin-top: 2px;
}

.rollback-preview {
  background-color: #f5f5f5;
  padding: 8px;
  border-radius: 4px;
  border-left: 3px solid #ccc;
}

.preview-label {
  font-size: 12px;
  color: #666;
  margin-right: 4px;
}

.preview-content {
  font-size: 12px;
  color: #333;
  font-style: italic;
}

.awd-btn-danger {
  background-color: #dc2626;
  color: white;
  border: none;
}

.awd-btn-danger:hover {
  background-color: #b91c1c;
}
/* PPT Config Styles */
.ppt-config-section {
  padding: 10px 0;
}

.section-title {
  font-size: 14px;
  color: #666;
  margin-bottom: 12px;
  display: block;
}

.ppt-option-card {
  border: 1px solid #e0e0e0;
  border-radius: 8px;
  padding: 16px;
  margin-bottom: 12px;
  cursor: pointer;
  transition: all 0.2s;
  background-color: #fff;
}

.ppt-option-card:hover {
  border-color: #2196f3;
  background-color: #f5f9ff;
}

.ppt-option-card.active {
  border-color: #2196f3;
  background-color: #e3f2fd;
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
  color: #1A5336;
}

.option-name {
  font-size: 16px;
  font-weight: 600;
  color: #333;
  flex: 1;
}

.check-mark {
  color: #2196f3;
  font-weight: bold;
  font-size: 16px;
}

.option-desc {
  font-size: 13px;
  color: #666;
  line-height: 1.5;
  padding-left: 32px; /* align with text start */
}

.warning-text {
  color: #ff9800;
  font-weight: 500;
  display: block;
  margin-top: 4px;
}

.highlight-text {
  color: #4caf50;
  font-weight: 500;
  display: block;
  margin-top: 4px;
}

.awd-btn-secondary {
    background-color: #f5f5f5;
    color: #333;
    border: 1px solid #ddd;
}
.awd-btn-secondary:hover {
    background-color: #e0e0e0;
}

</style>
