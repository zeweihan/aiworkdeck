<template>
  <view class="chat-interface" :class="{ 'is-empty': bubbles.length === 0 && !isStreaming }">

    <!-- Upload File Modal (reused from FileTree pattern) -->
    <view v-if="showUploadDialog" class="awd-dialog-mask" @tap="cancelUpload">
      <view class="awd-dialog awd-dialog-large" @tap.stop>
        <view class="awd-dialog-header">
          <view class="header-row">
            <text class="awd-dialog-title">{{ $t('chat.uploadFileTitle') }}</text>
            <text class="awd-dialog-subtitle">{{ $t('chat.uploadFileSubtitle') }}</text>
          </view>
        </view>
        <view class="awd-dialog-body">
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
          <view class="awd-btn awd-btn-secondary" @tap="cancelUpload">{{ $t('chat.cancel') }}</view>
          <view
            class="awd-btn awd-btn-primary"
            :class="{ disabled: !uploadSelectedFiles.length }"
            @tap="uploadSelectedFiles.length ? confirmUploadAndAddContext() : null"
          >
            {{ $t('chat.confirmUpload') }}
          </view>
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
          <text class="awd-dialog-title warning-title">{{ $t('chat.rollbackConfirmTitle') }}</text>
        </view>
        <view class="awd-dialog-body">
          <view class="rollback-warning-content">
            <text class="warning-text">{{ $t('chat.rollbackWarning') }}</text>
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
              <text class="preview-label">{{ $t('chat.rollbackPreviewLabel') }}</text>
              <text class="preview-content">"{{ truncateName(rollbackTargetContent, 50) }}"</text>
            </view>
          </view>
        </view>
        <view class="awd-dialog-footer">
          <view class="awd-btn awd-btn-secondary" @tap="cancelRollback">{{ $t('chat.cancel') }}</view>
          <view class="awd-btn awd-btn-danger" @tap="confirmRollback">{{ $t('chat.rollbackConfirmTitle') }}</view>
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
          <text class="project-name-display">{{ projectName }}</text>
       </view>
       <view class="header-actions">
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
            <!-- displayContent（契约 D）：点按钮产生的消息里，模型要的细节在 content，
                 用户气泡只显示那句人话；为空则回退 content，与今天行为完全一致。
                 contentHtml 仍优先——它只在用户手打输入那条路上存在（带内联文件标签）。 -->
            <div
              class="user-bubble-content"
              v-html="msg.contentHtml || escapeHtml(msg.displayContent || msg.content)"
            ></div>
            <div class="bubble-footer">
              <!-- Rollback Button -->
              <view v-if="!isStreaming" class="rollback-btn" @tap.stop="openRollbackDialog(msg, index)" :title="$t('chat.rollbackBtnTitle')">
                 <div class="rollback-icon-svg">
                    <svg xmlns="http://www.w3.org/2000/svg" width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round">
                        <path d="M9 14 4 9l5-5"></path>
                        <path d="M4 9h12a5 5 0 0 1 5 5v3"></path>
                    </svg>
                 </div>
                 <text class="rollback-text">{{ $t('chat.rollbackBtn') }}</text>
              </view>
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
          <text class="welcome-text">{{ $t('chat.welcome') }}</text>
          <text class="welcome-subtitle">{{ $t('chat.welcomeSubtitle') }}</text>
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
                     <image v-if="img.path" :src="img.path" mode="aspectFill" class="preview-thumb" />
                     <text class="preview-remove" @tap="removePastedImage(index)">×</text>
                  </view>
                  <!-- 能力未知时不出这行：只有明确 vision===false 才说会降级 -->
                  <text v-if="currentModelVision === false" class="input-images-note">{{ $t('chat.imageOcrFallbackNote') }}</text>
               </view>
              <div
                ref="richInput"
                class="chat-input-rich"
                contenteditable="true"
                @input="handleRichInput"
                @paste="handlePaste"
                @keydown.enter="handleEnterKey"
                @click="handleInputClick"
                :data-placeholder="$t('chat.inputPlaceholderEmpty')"
              ></div>
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
                          <view v-if="localModeNotice" class="mode-note">{{ localModeNotice }}</view>
                       </view>
                    </view>
                    <!-- Model Selector：清单来自 GET /api/ai/models，按厂商分组、国际档在后 -->
                    <view class="model-selector" @tap="toggleModelDropdown">
                       <text class="model-name">{{ currentModelName }}</text>
                       <text class="dropdown-arrow">▼</text>
                       <view v-if="showModelDropdown" class="model-dropdown down">
                          <view v-for="g in modelGroups" :key="g.key" class="model-group">
                             <view class="model-group-head">
                                <text class="model-group-vendor">{{ g.vendor }}</text>
                                <text v-if="g.region === 'INTERNATIONAL'" class="model-region-tag">{{ $t('chat.intlNetworkRequired') }}</text>
                             </view>
                             <view v-for="m in g.models" :key="m.id"
                                   class="model-option"
                                   :class="{ active: currentModelId === m.id }"
                                   @tap.stop="selectModel(m)">
                                <view class="model-option-head">
                                   <text class="model-option-name">{{ m.name }}</text>
                                   <text v-if="m.tiered" class="model-tier-tag">{{ $t('chat.tieredPricing') }}</text>
                                   <!-- 严格判 false：vision 缺字段是「未知」，标出来等于造谣 -->
                                   <text v-if="m.vision === false" class="model-novision-tag">{{ $t('chat.noVisionTag') }}</text>
                                </view>
                                <text class="model-option-price">{{ priceLabel(m) }}</text>
                             </view>
                          </view>
                          <view v-if="!modelGroups.length" class="model-empty">{{ $t('chat.noModels') }}</view>
                          <view v-if="networkRegionBasis" class="model-region-basis">{{ $t('chat.networkBasis', { basis: networkRegionBasis }) }}</view>
                       </view>
                    </view>
                    <!-- Skill Selector：触发词自动匹配始终生效，这里是「额外主动加载」的多选入口 -->
                    <view class="skill-selector" :class="{ pinned: selectedSkillIds.length > 0, muted: skillDisabledByMode }" :title="skillDisabledByMode ? $t('chat.skillAskDisabled') : $t('chat.skillDefaultTitle')" @tap="toggleSkillDropdown">
                       <svg class="skill-glyph-svg" viewBox="0 0 24 24" fill="none">
                          <path v-for="(d, gi) in ICONS.skill" :key="gi" :d="d" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round" />
                       </svg>
                       <text v-if="selectedSkillIds.length && !skillDisabledByMode" class="skill-count">{{ selectedSkillIds.length }}</text>
                       <view v-if="showSkillDropdown" class="skill-dropdown down">
                          <view class="skill-dropdown-head">
                             <text class="skill-dropdown-title">{{ $t('chat.skillPickerTitle') }}</text>
                             <text class="skill-dropdown-hint">{{ skillDisabledByMode ? $t('chat.skillAskDisabled') : $t('chat.skillPickerHint') }}</text>
                          </view>
                          <view v-if="availableSkills.length" class="skill-divider"></view>
                          <view v-for="s in availableSkills" :key="s.id"
                                class="skill-option"
                                :class="{ active: selectedSkillIds.includes(s.id), muted: skillDisabledByMode }"
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
                 <view
                    class="send-btn"
                    :class="{ disabled: !inputPrompt.trim() && !isStreaming, stopping: isStreaming }"
                    @tap="isStreaming ? handleAbort() : handleSubmit()"
                 >
                    <text class="send-icon">{{ isStreaming ? '■' : '↑' }}</text>
                 </view>
              </view>
          </view>
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
                           <text class="file-name-text">{{ f.fileName }}</text>
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
                 <image v-if="img.path" :src="img.path" mode="aspectFill" class="preview-thumb" />
                 <text class="preview-remove" @tap="removePastedImage(index)">×</text>
              </view>
              <!-- 能力未知时不出这行：只有明确 vision===false 才说会降级 -->
              <text v-if="currentModelVision === false" class="input-images-note">{{ $t('chat.imageOcrFallbackNote') }}</text>
           </view>
          <div
            ref="richInput"
            class="chat-input-rich"
            contenteditable="true"
            @input="handleRichInput"
            @paste="handlePaste"
            @keydown.enter="handleEnterKey"
            @click="handleInputClick"
            :data-placeholder="$t('chat.inputPlaceholder')"
          ></div>
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
                      <view v-if="localModeNotice" class="mode-note">{{ localModeNotice }}</view>
                   </view>
                </view>
                <!-- Model Selector：清单来自 GET /api/ai/models，按厂商分组、国际档在后 -->
                <view class="model-selector" @tap="toggleModelDropdown">
                   <text class="model-name">{{ currentModelName }}</text>
                   <text class="dropdown-arrow">▲</text>
                   <view v-if="showModelDropdown" class="model-dropdown up">
                      <view v-for="g in modelGroups" :key="g.key" class="model-group">
                         <view class="model-group-head">
                            <text class="model-group-vendor">{{ g.vendor }}</text>
                            <text v-if="g.region === 'INTERNATIONAL'" class="model-region-tag">{{ $t('chat.intlNetworkRequired') }}</text>
                         </view>
                         <view v-for="m in g.models" :key="m.id"
                               class="model-option"
                               :class="{ active: currentModelId === m.id }"
                               @tap.stop="selectModel(m)">
                            <view class="model-option-head">
                               <text class="model-option-name">{{ m.name }}</text>
                               <text v-if="m.tiered" class="model-tier-tag">{{ $t('chat.tieredPricing') }}</text>
                               <!-- 严格判 false：vision 缺字段是「未知」，标出来等于造谣 -->
                               <text v-if="m.vision === false" class="model-novision-tag">{{ $t('chat.noVisionTag') }}</text>
                            </view>
                            <text class="model-option-price">{{ priceLabel(m) }}</text>
                         </view>
                      </view>
                      <view v-if="!modelGroups.length" class="model-empty">{{ $t('chat.noModels') }}</view>
                      <view v-if="networkRegionBasis" class="model-region-basis">{{ $t('chat.networkBasis', { basis: networkRegionBasis }) }}</view>
                   </view>
                </view>
                <!-- Skill Selector：触发词自动匹配始终生效，这里是「额外主动加载」的多选入口 -->
                <view class="skill-selector" :class="{ pinned: selectedSkillIds.length > 0, muted: skillDisabledByMode }" :title="skillDisabledByMode ? $t('chat.skillAskDisabled') : $t('chat.skillDefaultTitle')" @tap="toggleSkillDropdown">
                   <svg class="skill-glyph-svg" viewBox="0 0 24 24" fill="none">
                      <path v-for="(d, gi) in ICONS.skill" :key="gi" :d="d" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round" />
                   </svg>
                   <text v-if="selectedSkillIds.length && !skillDisabledByMode" class="skill-count">{{ selectedSkillIds.length }}</text>
                   <view v-if="showSkillDropdown" class="skill-dropdown up">
                      <view class="skill-dropdown-head">
                         <text class="skill-dropdown-title">{{ $t('chat.skillPickerTitle') }}</text>
                         <text class="skill-dropdown-hint">{{ skillDisabledByMode ? $t('chat.skillAskDisabled') : $t('chat.skillPickerHint') }}</text>
                      </view>
                      <view v-if="availableSkills.length" class="skill-divider"></view>
                      <view v-for="s in availableSkills" :key="s.id"
                            class="skill-option"
                            :class="{ active: selectedSkillIds.includes(s.id), muted: skillDisabledByMode }"
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
             <view
                class="send-btn"
                :class="{ disabled: !inputPrompt.trim() && !isStreaming, stopping: isStreaming }"
                @tap="isStreaming ? handleAbort() : handleSubmit()"
             >
                <text class="send-icon">{{ isStreaming ? '■' : '↑' }}</text>
             </view>
          </view>
          <view v-if="showModelDropdown || showModeDropdown || showSkillDropdown" class="dropdown-mask" @tap="showModelDropdown = false; showModeDropdown = false; showSkillDropdown = false"></view>
       </view>
       </template>
    </view>

    <!-- Background Task Progress Indicator -->
    <BackgroundTaskIndicator
      :backgroundTasks="backgroundTasks"
      :lastHeartbeat="lastHeartbeat"
      @dismiss="dismissBackgroundTask"
    />

  </view>
</template>

<script>
import RootBubble from './AgentMessage/RootBubble.vue'
import BackgroundTaskIndicator from './BackgroundTaskIndicator.vue'
import { useAgentStream } from '@/composables/useAgentStream.js'
import { parseToolBlock } from '@/composables/agentTagProtocol.mjs'
import { ref, watch, onMounted, nextTick, getCurrentInstance, computed } from 'vue'
import { createFile, getProjectFiles, getApiBaseUrl, rollbackConversation, performPptGeneration, getSkills, fetchAiModels, getAiConfig, cancelBackgroundTask, listPluginJobs, cancelPluginJob } from '@/services/api.js'
import { getAuthHeaders } from '@/utils/auth.js'
import { getAppLanguage } from '@/utils/appLanguage.js'
import { t } from '@/i18n'
import { ICONS } from '@/config/icons.js'

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
    // 发送时把粘贴图片上传成项目文件的那一小段窗口（此时 isStreaming 还是 false）
    const isUploadingPasted = ref(false)

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

    // 单价跨度从 0.02 到 15 美元/百万 tokens，固定两位小数会把便宜模型显示成 0.00
    const formatPrice = (v) => {
      const n = Number(v)
      if (!isFinite(n) || n < 0) return '-'
      if (n === 0) return '0'
      return (n < 1 ? n.toFixed(3) : n.toFixed(2)).replace(/0+$/, '').replace(/\.$/, '')
    }

    // 下拉里的价格标签：让用户在切模型之前就知道自己在花什么钱
    const priceLabel = (m) => t('chat.priceLabel', { input: formatPrice(m.inputPricePerM), output: formatPrice(m.outputPricePerM) })

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
      } catch (e) {
        // 取不到供应商时按云端处理（不缩减模式），避免误把云端用户锁成只能 Ask
        console.warn('[ChatInterface] 加载 AI 供应商配置失败:', e)
        activeProvider.value = ''
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

    // Scroll to bottom when bubbles change
    watch(() => bubbles.value.length, () => {
       scrollToBottom()
    })
    // 曾经有一个 `watch(bubbles, () => {}, { deep: true })`：回调体是空的（注释也
    // 写着"for now simple trigger"），从来没做任何事。deep watch 每次触发都要把
    // bubbles 整棵响应式对象图（含全部消息/工具输出/artifact）重新遍历一遍来
    // 重建依赖追踪，而流式回答的每一个 token 都会命中它（currentAssistantBubble
    // 的 content 在 SSE 每个 chunk 都会变）——对话越长这个空转的代价越大，长
    // 工具调用/长回答期间会明显卡顿。上面那条浅层 watch（只看 length）已经覆盖
    // "新气泡出现要滚到底"，这条 deep watch 删掉不改变任何行为，纯粹省掉这份
    // 空转开销。

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
    const openRollbackDialog = (msg, index) => {
      if (isStreaming.value) {
        uni.showToast({ title: t('chat.waitCurrentChat'), icon: 'none' })
        return
      }
      rollbackTargetIndex.value = index
      // 预览与「回填到输入框重发」都用用户看到的那份（契约 D）：把回喂给模型的
      // 长文案塞回输入框，用户没法在上面继续编辑，只会一头雾水
      rollbackTargetContent.value = msg.displayContent || msg.content || ''
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

        uni.showToast({ title: t('chat.rollbackDone'), icon: 'success' })
      } catch (err) {
        console.error('[ChatInterface] Rollback failed:', err)
        uni.showToast({ title: t('chat.rollbackFailed', { error: err.message || t('chat.unknownError') }), icon: 'none' })
      }

      // 重置状态
      rollbackTargetIndex.value = -1
      rollbackTargetContent.value = ''
      rollbackTargetId.value = null
    }

    const startNewChat = () => {
      // 流式进行中点"新建对话"：以前只清前端状态（setConversationId(null) 触发的
      // resetSSE 只是断本地连接），从不通知后端——编排器在服务端继续跑这一轮
      // （模型调用、可能有副作用的工具调用），用户却完全看不到任何迹象，白烧
      // 资源/额度。复用 handleAbort 同一条 abort()（内部先 POST /api/agent/cancel
      // 再断连接）；不 await 它——新建对话本身不该被这次网络请求拖住，abort()
      // 对已经清空的气泡/会话状态做的收尾判断都是空值安全的。
      if (isStreaming.value) abort()
      setConversationId(null)  // This now triggers resetSSE internally
      clearBubbles()           // Use composable method
      selectedSkillIds.value = [] // 手动选的技能属于这一段对话，新会话从干净状态开始
      emit('new-chat')
    }

    const handleSubmit = async () => {
      // 插件镜像会话只读（dev-board#298）：输入区已换成说明条，这里再拦一道
      // 兜住空态输入框等旁路（后端对镜像会话追加也会拒，这是省一次报错）
      if (props.externalReadOnly) return
      // 流式进行中禁止再发送（回车路径不走发送按钮的 abort 分支）：
      // 必须在清空输入框之前拦截，否则用户输入会被静默丢弃
      if (isStreaming.value || isUploadingPasted.value) return
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

      // 先定住本次要带走的那几张，再去上传：上传要走网络，其间用户还可能继续粘贴，
      // 拿 pastedImages 的实时值会一边漏掉新贴的、一边把它顺手清掉。
      const pastedBatch = pastedImages.value.slice()
      let pastedFileList = []
      if (pastedBatch.length) {
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
      }

      if (richInput.value) richInput.value.innerHTML = ''
      inputPrompt.value = ''

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
      const contextFilesToShow = contextFiles.value.map(f => ({
        id: f.id,
        name: f.name,
        isDir: f.isDir
      }))

      // Clear context files and images after sending
      contextFiles.value = []
      // 只清掉本次带走的那几张，上传期间新粘的留给下一条消息
      pastedImages.value = pastedImages.value.filter(img => !pastedBatch.includes(img))

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
        activeContext, // NEW: Auto-detected active tab context
        // ASK 模式下 skill 不生效，一律不带——省得后端与面板的状态各说各话
        skillIds: currentSkillIds(),
        // Pass for user bubble display
        _userImages: imagesToShow,
        _userContextFiles: contextFilesToShow
      })

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
    const handleAbort = () => {
      uni.showToast({ title: t('chat.abortToast'), icon: 'none' })
      abort()
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
    const loadMessages = (conversationId, loadedMsgs) => {
       console.log('[ChatInterface] Loading history...', loadedMsgs.length)
       setConversationId(conversationId)  // This triggers resetSSE internally
       clearBubbles()  // Clear existing using composable method
       selectedSkillIds.value = [] // 切会话即重置手动选择：技能是按轮携带的，不该跨会话粘住

       loadedMsgs.forEach(msg => {
          const role = msg.role?.toUpperCase() || 'USER'

          if (role === 'USER') {
              bubbles.value.push({
                  id: msg.id,
                  role: 'USER',
                  content: msg.content,
                  // 契约 D：后端 GET /api/ai/history 带 displayContent（可空）。
                  // 渲染一律 displayContent || content——不带这个字段时与今天完全一致。
                  // 助手消息刻意不走这条回退：那边的 content 是协议 XML，要解析而不是直显，
                  // 而 displayContent 只会写在用户消息上。
                  displayContent: msg.displayContent || '',
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
                  // 反问（<question>）解析结果，形状与 useAgentStream.createAssistantBubble 一致：
                  // { text, options, answered } | null
                  question: null,
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
                  // 解转义与标签清单都在 agentTagProtocol.mjs：落库正文里的工具载荷是中和过的
                  // （否则输出里的 </tool_output>/</process> 会把这段解析整个带偏），此处还原成原文
                  const toolBlock = parseToolBlock(processContent)

                  if (toolBlock) {
                      const code = toolBlock.code
                      const outputAttrs = toolBlock.attrs
                      const output = toolBlock.output

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

              // 反问（<question>）回灌。此前全仓不解析这个标签：落库正文里带着原样标签，
              // 重开会话时整段 <question>…</question> 作为「未标记文本」掉进 bubble.content
              // ——用户看到的是一堆 XML，选项更是无从点起。
              // 刻意放在 <process> 剥离之后：工具输出里出现过 <question> 字样（模型复述协议）
              // 也不会被当成真的反问。
              // **这里不写 answered 的最终值**：artifact 那边把历史里的计划卡一律硬写成
              // status:'draft'（见上方 Extract Artifacts），同样的写法换到问题卡上就是
              // 「重开会话后已回答过的问题又长出一排能点的按钮」；answered 在整轮回灌结束后
              // 按「这条之后还有没有用户消息」统一判定。
              const questionMatch = remaining.match(/<question(?:\s[^>]*)?>([\s\S]*?)<\/question>/i)
              // 兜底：模型漏了 </question>（截断/笔误）时后端仍按「有问题」停机
              // （AgentOrchestrator.containsQuestion 只认起始标签），前端也得认，
              // 否则这条最需要提示的消息反而只剩裸标签。
              const openQuestionMatch = questionMatch
                  ? null
                  : remaining.match(/<question(?:\s[^>]*)?>([\s\S]*)$/i)
              const questionRaw = questionMatch ? questionMatch[1] : (openQuestionMatch ? openQuestionMatch[1] : null)
              if (questionRaw !== null) {
                  const options = []
                  const optionRegex = /<option>([\s\S]*?)<\/option>/gi
                  let optMatch
                  while ((optMatch = optionRegex.exec(questionRaw)) !== null) {
                      const opt = optMatch[1].trim()
                      if (opt) options.push(opt)
                  }
                  bubble.question = {
                      text: questionRaw.replace(/<option>[\s\S]*?<\/option>/gi, '').trim(),
                      options,
                      answered: false
                  }
                  remaining = remaining.replace(questionMatch ? questionMatch[0] : openQuestionMatch[0], '')
              }

              // Any remaining untagged text goes to content (fallback for legacy)
              remaining = remaining.trim()
              if (remaining && !bubble.content) {
                  bubble.content = remaining
              }

              bubbles.value.push(bubble)
          }
       })

       // 问题卡的已回答判定：这条助手消息后面还有用户消息，说明那一问已经答过了。
       // 只写 answered（历史态徽标），不靠它控制可操作性——那条链仍是 RootBubble 的
       // isLatest（仅最新一条助手消息可操作）。两者一致：真正未答的那一问必然是末条。
       let seenLaterUser = false
       for (let i = bubbles.value.length - 1; i >= 0; i--) {
          const b = bubbles.value[i]
          if (b.role === 'USER') { seenLaterUser = true; continue }
          if (b.question && seenLaterUser) b.question.answered = true
       }

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

      if (!props.projectId) {
        uni.showToast({ title: t('chat.projectIdMissing'), icon: 'none' })
        return
      }

      isUploading.value = true
      const projectId = typeof props.projectId === 'string' ? Number(props.projectId) : props.projectId
      const parentId = selectedUploadParent.value
      const filesToUpload = [...uploadSelectedFiles.value]

      // Close dialog
      showUploadDialog.value = false
      uploadSelectedFiles.value = []

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
    /** 停止：取消所有在跑的后台任务。没有在跑的就什么都不做（菜单那条已置灰）。 */
    const menuStop = async () => {
      const list = runningTasks.value.slice()
      for (const t of list) await handleCancelTask(t)
      return list.length
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
    })

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
       handleAbort,
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
       linkStatus,
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
       priceLabel,
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

.header-left .project-name-display {
  font-weight: 600;
  color: var(--awd-text);
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

.message-list {
  flex: 1;
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
/* 输入区获得焦点时整卡亮起：品牌绿描边 + mint 光晕（浅色，不做深色 chrome） */
.input-card:focus-within {
  border-color: var(--awd-accent);
  box-shadow: 0 0 0 3px rgba(91, 209, 151, 0.16), 0 1px 2px rgba(18, 52, 77, 0.04), 0 4px 16px rgba(18, 52, 77, 0.06);
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
  margin-top: 12px;
  padding-top: 12px;
  border-top: 1px solid var(--awd-border-subtle);
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

.model-dropdown {
  position: absolute;
  /* 锚点是 .input-card（position:relative，见上方定义）而不是 .model-selector
     自己——固定 268px 的 min-width 摆在只有内容宽的选择器上，AI 面板收到最窄
     240px 时无论往哪边对齐都放不下，会被 .chat-interface 的 overflow:hidden
     裁掉一截。改成跟随输入卡自身宽度（left/right 都钉到 0），永不溢出。 */
  left: 0;
  right: 0;
  background: var(--awd-surface);
  border: 1px solid var(--awd-border);
  border-radius: 8px;
  box-shadow: 0 4px 16px rgba(0, 0, 0, 0.12);
  z-index: 1001;
  min-width: 0;
  max-height: 320px;
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

.model-option {
  padding: 8px 14px;
  cursor: pointer;
  font-size: 13px;
  color: var(--awd-text);
  transition: background 0.15s ease;
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.model-option:hover {
  background: var(--awd-accent-soft);
}
.model-option.active {
  color: var(--awd-accent-text);
  font-weight: 500;
  background: var(--awd-accent-wash);
}

/* ===== 模型下拉：按厂商分组，国际档在后并标注需国际网络 ===== */
.model-group + .model-group {
  border-top: 1px solid var(--awd-border-subtle);
}
.model-group-head {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 6px 14px 2px;
}
.model-group-vendor {
  font-size: 11px;
  color: var(--awd-text-3);
  letter-spacing: 0.5px;
}
.model-region-tag {
  font-size: 10px;
  color: var(--awd-warning-text);
  background: var(--awd-warning-soft);
  border-radius: 3px;
  padding: 1px 4px;
}
.model-option-head {
  display: flex;
  align-items: center;
  gap: 6px;
  /* 下拉现在跟随输入卡宽度，窄面板下模型名 + 单价 tag 放不下一行——允许换行，
     不许把 tag 裁掉。 */
  flex-wrap: wrap;
}
.model-option-name {
  font-size: 13px;
}
.model-tier-tag {
  font-size: 10px;
  color: var(--awd-text-2);
  background: var(--awd-surface-3);
  border-radius: 3px;
  padding: 1px 4px;
}
/* 与 tier tag 同一档中性灰，刻意不用告警色：读不了图会自动降级 OCR，是能力差异不是错误 */
.model-novision-tag {
  font-size: 10px;
  color: var(--awd-text-2);
  background: var(--awd-surface-3);
  border-radius: 3px;
  padding: 1px 4px;
}
.model-option-price {
  font-size: 11px;
  color: var(--awd-text-3);
  font-weight: 400;
}
.model-empty {
  padding: 10px 14px;
  font-size: 12px;
  color: var(--awd-text-3);
}
.model-region-basis {
  border-top: 1px solid var(--awd-border-subtle);
  margin-top: 4px;
  padding: 6px 14px 2px;
  font-size: 10px;
  color: var(--awd-text-3);
  line-height: 1.5;
}

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
  background: linear-gradient(135deg, var(--awd-accent) 0%, var(--awd-accent-hover) 100%);
  color: var(--awd-text-on-accent);
  border-radius: 50%;
  font-size: 12px;
  text-align: center;
  line-height: 18px;
  cursor: pointer;
  box-shadow: 0 1px 3px rgba(26, 83, 54, 0.3);
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
    box-shadow: 0 0 0 2px rgba(91, 209, 151, 0.18);
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
  /* background-color: #5BD197; Mint Green */
  background-color: var(--awd-mint);
  /* color: #ffffff; */
  /* border-color: #1A5336; */
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

</style>
