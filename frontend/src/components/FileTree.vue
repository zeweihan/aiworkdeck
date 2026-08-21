<template>
  <view class="file-tree" tabindex="0" @keydown="handleKeyDown" @mousedown="focusTree">

    <!-- AI WorkDeck Style Modals System -->

    <!-- 1. Delete Confirmation Modal -->
    <view v-if="showDeleteDialog" class="awd-dialog-mask" @tap="showDeleteDialog = false">
      <view class="awd-dialog" @tap.stop>
        <view class="awd-dialog-header">
          <text class="awd-dialog-title">{{ deleteMode === 'hard' ? $t('fileTree.hardDeleteTitle') : $t('fileTree.softDeleteTitle') }}</text>
        </view>
        <view class="awd-dialog-body">
          <text class="awd-dialog-text">
            <template v-if="!deleteIsBatch && deleteTargetItem">
              {{ $t(deleteMode === 'hard' ? 'fileTree.deleteConfirmItemHard' : 'fileTree.deleteConfirmItemSoft', { kind: deleteTargetItem.isFolder ? $t('fileTree.folder') : $t('fileTree.file'), name: deleteTargetItem.name }) }}
              {{ deleteTargetItem.isFolder && deleteMode !== 'hard' ? $t('fileTree.folderSoftDeleteNote') : '' }}
              {{ deleteMode === 'hard' ? $t('fileTree.irreversibleNote') : '' }}
            </template>
            <template v-else-if="deleteIsBatch">
              {{ $t(deleteMode === 'hard' ? 'fileTree.deleteConfirmBatchHard' : 'fileTree.deleteConfirmBatchSoft', { count: deleteBatchIds.length }) }}
              {{ deleteMode === 'hard' ? $t('fileTree.irreversibleNote') : '' }}
            </template>
          </text>
        </view>
        <view class="awd-dialog-footer">
           <view class="awd-btn awd-btn-secondary" @tap="showDeleteDialog = false">{{ $t('fileTree.cancel') }}</view>
           <!-- Use Danger (Red) for Delete Actions -->
           <view class="awd-btn awd-btn-danger" @tap="confirmDelete">{{ $t('fileTree.confirmDeleteBtn') }}</view>
        </view>
      </view>
    </view>

    <!-- 2. New Folder Modal -->
    <view v-if="showCreateDialog" class="awd-dialog-mask" @tap="showCreateDialog = false">
      <view class="awd-dialog" @tap.stop>
        <view class="awd-dialog-header">
          <text class="awd-dialog-title">{{ $t('fileTree.newFolder') }}</text>
        </view>
        <view class="awd-dialog-body">
          <input
            v-model="newFolderName"
            class="awd-input"
            :placeholder="$t('fileTree.folderNamePlaceholder')"
            @confirm="handleCreateFolder"
            :focus="true"
          />
        </view>
        <view class="awd-dialog-footer">
          <view class="awd-btn awd-btn-secondary" @tap="showCreateDialog = false">{{ $t('fileTree.cancel') }}</view>
          <view class="awd-btn awd-btn-primary" @tap="handleCreateFolder">{{ $t('fileTree.confirm') }}</view>
        </view>
      </view>
    </view>

    <!-- 3. Upload File Modal -->
    <view v-if="showUploadDialog" class="awd-dialog-mask" @tap="showUploadDialog = false">
      <view class="awd-dialog awd-dialog-large" @tap.stop>
        <view class="awd-dialog-header">
          <view class="header-row">
            <text class="awd-dialog-title">{{ $t('fileTree.uploadFile') }}</text>
            <text class="awd-dialog-subtitle">{{ $t('fileTree.uploadDialogSubtitle') }}</text>
          </view>
        </view>
        <view class="awd-dialog-body">
          <view class="form-group">
            <text class="form-label">{{ $t('fileTree.uploadLocation') }}</text>
            <view class="awd-field clickable" @tap="showFolderSelector = true">
              <image src="/static/folder-closed.png" class="field-icon-img" mode="aspectFit" />
              <text class="field-value">
                {{ selectedUploadParent ? getFolderPath(selectedUploadParent) : $t('fileTree.rootDirectory') }}
              </text>
            </view>
          </view>

          <!-- H5 Folder Upload -->
          <!-- #ifdef H5 -->
          <view class="form-group">
            <text class="form-label">{{ $t('fileTree.uploadFolder') }}</text>
            <view class="awd-field clickable" @tap="triggerFolderUpload">
               <view v-if="isFolderUpload && selectedFiles.length > 0" class="field-content-row">
                  <text class="field-value">{{ $t('fileTree.selectedFilesCount', { count: selectedFiles.length }) }}</text>
                  <text class="field-desc">({{ selectedFiles[0].relativePath ? selectedFiles[0].relativePath.split('/')[0] : $t('fileTree.folder') }})</text>
               </view>
               <view v-else>
                  <text class="field-placeholder">{{ $t('fileTree.clickToSelectFolder') }}</text>
               </view>
            </view>
          </view>
          <!-- #endif -->

          <view class="form-group">
            <text class="form-label">{{ $t('fileTree.uploadFile') }}</text>
            <view class="awd-field clickable" @tap="selectFiles">
              <view v-if="selectedFiles.length === 0 || isFolderUpload">
                <text class="field-placeholder">{{ $t('fileTree.selectFilesPlaceholder') }}</text>
              </view>
              <view v-else class="selected-files-list">
                <text v-for="(file, index) in selectedFiles" :key="index" class="selected-file-tag">
                  {{ file.name }}
                </text>
              </view>
            </view>
          </view>
        </view>
        <view class="awd-dialog-footer">
          <view class="awd-btn awd-btn-secondary" @tap="cancelUpload">{{ $t('fileTree.cancel') }}</view>
          <view
            class="awd-btn awd-btn-primary"
            :class="{ disabled: !selectedFiles.length }"
            @tap="selectedFiles.length ? confirmUpload() : null"
          >
            {{ $t('fileTree.confirmUploadBtn') }}
          </view>
        </view>
      </view>
    </view>

    <!-- 4. Manage Tags Modal -->
    <view v-if="showTagEditDialog" class="awd-dialog-mask" @tap="showTagEditDialog = false">
      <view class="awd-dialog" @tap.stop>
        <view class="awd-dialog-header">
          <view class="header-row">
            <text class="awd-dialog-title">{{ $t('fileTree.manageTags') }}</text>
            <text class="awd-dialog-subtitle">{{ targetFileForTags ? targetFileForTags.name : '' }}</text>
          </view>
        </view>
        <view class="awd-dialog-body" style="min-height: 200px;">
           <view class="form-group">
              <text class="form-label">{{ $t('fileTree.currentTags') }}</text>
              <view class="tags-container" style="display: flex; flex-wrap: wrap; gap: 8px; margin-bottom: 12px; min-height: 32px;">
                  <view v-if="!targetFileForTags || !targetFileForTags.tags || targetFileForTags.tags.length === 0" class="empty-tags">
                     <text style="color: #6C757D; font-size: 13px;">{{ $t('fileTree.noTags') }}</text>
                  </view>
                  <TagChip
                    v-for="tag in (targetFileForTags ? targetFileForTags.tags : [])"
                    :key="tag.id"
                    :tag="tag"
                    :closable="true"
                    @close="handleRemoveTag(tag)"
                  />
              </view>
           </view>

           <view class="form-group">
              <text class="form-label">{{ $t('fileTree.addTag') }}</text>
              <TagSelector
                :available-tags="projectTags"
                :existing-tag-ids="(targetFileForTags && targetFileForTags.tags) ? targetFileForTags.tags.map(t => t.id) : []"
                :project-id="projectId"
                @select="handleAddTag"
                @create="handleCreateNewTag"
                @manage="showTagManager = true"
              />
           </view>
        </view>
        <view class="awd-dialog-footer">
          <view class="awd-btn awd-btn-primary" @tap="showTagEditDialog = false">{{ $t('fileTree.done') }}</view>
        </view>
      </view>
    </view>

    <!-- 4b. Set Deadline Modal（project_task，文件/文件夹右键「设置截止日」） -->
    <view v-if="showDeadlineDialog" class="awd-dialog-mask" @tap="showDeadlineDialog = false">
      <view class="awd-dialog" @tap.stop>
        <view class="awd-dialog-header">
          <text class="awd-dialog-title">{{ $t('calendar.deadlineDialogTitle') }}</text>
        </view>
        <view class="awd-dialog-body">
          <text class="awd-dialog-text" style="display: block; margin-bottom: 12px;">
            {{ $t('calendar.deadlineForFile', { name: deadlineTargetItem ? deadlineTargetItem.name : '' }) }}
          </text>
          <view class="form-group">
            <text class="form-label">{{ $t('calendar.taskTitleLabel') }}</text>
            <input v-model="deadlineTitle" class="awd-input" :placeholder="$t('calendar.taskTitlePlaceholder')" />
          </view>
          <view class="form-group">
            <text class="form-label">{{ $t('calendar.dateLabel') }}</text>
            <AwdDatePicker v-model="deadlineDate" type="date" />
          </view>
          <view class="form-group">
            <text class="form-label">{{ $t('calendar.timeLabel') }}</text>
            <AwdDatePicker v-model="deadlineTime" type="time" />
          </view>
        </view>
        <view class="awd-dialog-footer">
          <view class="awd-btn awd-btn-secondary" @tap="showDeadlineDialog = false">{{ $t('calendar.cancel') }}</view>
          <view class="awd-btn awd-btn-primary" @tap="confirmSetDeadline">{{ $t('calendar.save') }}</view>
        </view>
      </view>
    </view>

    <!-- 5. Tag Manager (Global) -->
    <view v-if="showTagManager" class="awd-dialog-mask" style="z-index: 3100;" @tap="showTagManager = false">
       <view @tap.stop>
          <TagManager :project-id="projectId" @close="showTagManager = false" />
       </view>
    </view>

    <!-- 6. Folder Selector Popup (Nested) -->
    <view v-if="showFolderSelector" class="awd-dialog-mask" style="z-index: 3000;" @tap="showFolderSelector = false">
      <view class="awd-dialog" @tap.stop>
        <view class="awd-dialog-header">
          <view class="header-row">
            <text class="awd-dialog-title">{{ $t('fileTree.selectFolder') }}</text>
            <view class="new-folder-btn" @tap="handleSelectorCreateFolder">
              <text class="btn-plus">+</text>
              <text>{{ $t('fileTree.newFolder') }}</text>
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
            <text class="folder-name">{{ $t('fileTree.rootDirectory') }}</text>
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
            <view v-if="renamingId === folder.id" class="rename-input-wrapper dialog-rename" @tap.stop>
              <input
                class="rename-input"
                v-model="tempRenameValue"
                :focus="true"
                @confirm="commitRename"
                @blur="commitRename"
                @keydown.esc="renamingId = null"
              />
            </view>
            <text v-else class="folder-name">{{ folder.name }}</text>
          </view>
          <view v-if="folderTree.length === 0" class="empty-tip">{{ $t('fileTree.noOtherFolders') }}</view>
        </view>
        <view class="awd-dialog-footer">
          <view class="awd-btn awd-btn-secondary" @tap="showFolderSelector = false">{{ $t('fileTree.cancel') }}</view>
          <view class="awd-btn awd-btn-primary" @tap="confirmFolderSelection">{{ $t('fileTree.confirm') }}</view>
        </view>
      </view>
    </view>

    <!-- 右键上下文菜单 -->
    <view
      v-if="contextMenu.visible"
      class="context-menu-mask"
      @tap="closeContextMenu"
      @contextmenu.prevent="closeContextMenu"
    >
      <view
        class="context-menu"
        :style="{ left: contextMenu.x + 'px', top: contextMenu.y + 'px' }"
        @tap.stop
      >
        <view v-if="canCompareDocuments()" class="context-menu-item" @tap="startDocumentCompare">
          <view class="context-menu-icon" style="display: flex; align-items: center; justify-content: center;">
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <path d="M9 19V5M15 19V5" stroke-linecap="round" stroke-linejoin="round"/>
              <rect x="3" y="3" width="18" height="18" rx="2" stroke-linecap="round" stroke-linejoin="round"/>
            </svg>
          </view>
          <text class="context-menu-text">{{ $t('fileTree.compareDocuments') }}</text>
        </view>
        <view v-if="contextMenu.targetItem && !contextMenu.targetItem.isFolder" class="context-menu-item" @tap="handleDownload(contextMenu.targetItem); closeContextMenu()">
          <view class="context-menu-icon" style="display: flex; align-items: center; justify-content: center;">
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4" stroke-linecap="round" stroke-linejoin="round"/>
              <polyline points="7 10 12 15 17 10" stroke-linecap="round" stroke-linejoin="round"/>
              <line x1="12" y1="15" x2="12" y2="3" stroke-linecap="round" stroke-linejoin="round"/>
            </svg>
          </view>
          <text class="context-menu-text">{{ $t('fileTree.download') }}</text>
        </view>
        <view v-if="contextMenu.targetItem" class="context-menu-item" @tap="handleRename(contextMenu.targetItem); closeContextMenu()">
          <view class="context-menu-icon" style="display: flex; align-items: center; justify-content: center;">
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <path d="M11 4H4a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-7" stroke-linecap="round" stroke-linejoin="round"/>
              <path d="M18.5 2.5a2.121 2.121 0 0 1 3 3L12 15l-4 1 1-4 9.5-9.5z" stroke-linecap="round" stroke-linejoin="round"/>
            </svg>
          </view>
          <text class="context-menu-text">{{ $t('fileTree.rename') }}</text>
        </view>
        <view v-if="contextMenu.targetItem && !contextMenu.targetItem.isFolder" class="context-menu-item" @tap="openTagEditDialog(contextMenu.targetItem); closeContextMenu()">
          <view class="context-menu-icon" style="display: flex; align-items: center; justify-content: center;">
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <path d="M20.59 13.41l-7.17 7.17a2 2 0 0 1-2.83 0L2 12V2h10l8.59 8.59a2 2 0 0 1 0 2.82z" stroke-linecap="round" stroke-linejoin="round"/>
              <line x1="7" y1="7" x2="7.01" y2="7" stroke-linecap="round" stroke-linejoin="round"/>
            </svg>
          </view>
          <text class="context-menu-text">{{ $t('fileTree.manageTags') }}</text>
        </view>
        <view v-if="contextMenu.targetItem" class="context-menu-item" @tap="openDeadlineDialog(contextMenu.targetItem); closeContextMenu()">
          <view class="context-menu-icon" style="display: flex; align-items: center; justify-content: center;">
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <path d="M5 4h14a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2Z" stroke-linecap="round" stroke-linejoin="round"/>
              <path d="M16 2v4" stroke-linecap="round"/>
              <path d="M8 2v4" stroke-linecap="round"/>
              <path d="M3 10h18" stroke-linecap="round"/>
            </svg>
          </view>
          <text class="context-menu-text">{{ $t('calendar.setDeadline') }}</text>
        </view>
        <view v-if="contextMenu.targetItem && !contextMenu.targetItem.isFolder" class="context-menu-item"
          @tap="$emit('file-history', contextMenu.targetItem); closeContextMenu()">
          <view class="context-menu-icon" style="display: flex; align-items: center; justify-content: center;">
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <circle cx="12" cy="12" r="9"/><path d="M12 7v5l3 2" stroke-linecap="round"/>
            </svg>
          </view>
          <text class="context-menu-text">{{ $t('fileTree.fileHistory') }}</text>
        </view>
        <view v-if="contextMenu.targetItem && isDesktopShell" class="context-menu-item"
          @tap="$emit('reveal-file', contextMenu.targetItem); closeContextMenu()">
          <view class="context-menu-icon" style="display: flex; align-items: center; justify-content: center;">
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <path d="M3 7a2 2 0 0 1 2-2h4l2 2h8a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z" stroke-linecap="round" stroke-linejoin="round"/>
            </svg>
          </view>
          <text class="context-menu-text">{{ $t('fileTree.revealInFinder') }}</text>
        </view>
        <view v-if="contextMenu.targetItem" class="context-menu-item context-menu-item-danger" @tap="handleDelete(contextMenu.targetItem); closeContextMenu()">
          <view class="context-menu-icon" style="display: flex; align-items: center; justify-content: center;">
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <polyline points="3 6 5 6 21 6" stroke-linecap="round" stroke-linejoin="round"/>
              <path d="M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6m3 0V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2" stroke-linecap="round" stroke-linejoin="round"/>
            </svg>
          </view>
          <text class="context-menu-text">{{ $t('fileTree.delete') }}</text>
        </view>
      </view>
    </view>

    <view class="tree-content" @mousedown="onMarqueeStart" @mousemove="onMarqueeMove" @mouseup="onMarqueeEnd" @tap="closeContextMenu">
      <!-- Recycle Bin Header -->
      <view v-if="viewMode === 'recycle'" class="tree-toolbar" style="background: #E8F3ED; border-bottom: 1px solid #E9ECEF; justify-content: space-between;">
         <svg class="recycle-glyph" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
           <path v-for="(d, gi) in ICONS.trash" :key="gi" :d="d" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round" />
         </svg>
         <text style="font-size: 12px; color: #1A5336; display: flex; align-items: center; font-weight: 500;">{{ $t('fileTree.recycleBinCount', { count: recycleBin.length }) }}</text>
         <text class="action-btn recycle-back-btn" @tap="exitRecycleBin">{{ $t('fileTree.back') }}</text>
      </view>

      <!-- Sort Menu (Dropdown) -->
      <view v-if="showSortMenu" class="sort-menu-mask" @tap="showSortMenu = false">
         <view class="sort-menu" @tap.stop>
            <view class="sort-item" :class="{ active: sortMode === 'name' }" @tap="setSortMode('name')">
               <text>{{ $t('fileTree.sortName') }}</text>
               <text v-if="sortMode === 'name'">✓</text>
            </view>
            <view class="sort-item" :class="{ active: sortMode === 'date' }" @tap="setSortMode('date')">
               <text>{{ $t('fileTree.sortDate') }}</text>
               <text v-if="sortMode === 'date'">✓</text>
            </view>
            <view class="sort-item" :class="{ active: sortMode === 'type' }" @tap="setSortMode('type')">
               <text>{{ $t('fileTree.sortType') }}</text>
               <text v-if="sortMode === 'type'">✓</text>
            </view>
         </view>
      </view>

      <view v-if="loading" class="tree-loading">
        <text>{{ $t('fileTree.loading') }}</text>
      </view>
      <view v-else-if="displayFiles.length === 0" class="tree-empty">
        <view class="empty-content">
          <text>{{ $t('fileTree.noFiles') }}</text>
        </view>
        <!-- Root Drop Zone for empty folders -->
        <!-- #ifdef H5 -->
        <view
          v-if="isAnyDragging"
          class="root-drop-zone-empty"
          :class="{ 'drop-active': rootDropActive }"
          @dragover.prevent="onRootDragOver"
          @dragleave="onRootDragLeave"
          @drop.prevent="onRootDrop"
        >
          <text>{{ $t('fileTree.dropToRoot') }}</text>
        </view>
        <!-- #endif -->
      </view>
      <view v-else class="tree-list">
          <!-- 框选区域（H5） -->
          <view v-if="marquee.active" class="marquee" :style="marqueeStyle"></view>

          <!-- H5端使用HTML5拖拽API -->
          <!-- #ifdef H5 -->
          <template v-for="(item, index) in windowedDisplayFiles" :key="item.id">
          <view
            v-if="!item.__loadMore"
            class="tree-item"
            :class="{
              'tree-item-selected': selectedFileId === item.id,
              'tree-item-multi-selected': multiSelectedIds.includes(item.id),
              'tree-item-drop-target': dragOverIndex === index
            }"
          :data-file-id="item.id"
          :draggable="viewMode !== 'recycle'"
          @tap="handleItemClick(item, $event)"
          @contextmenu.prevent="handleContextMenu(item, $event)"
          @dragstart="handleDragStart($event, item, index)"
          @dragover.prevent="handleDragOver($event, index)"
          @drop="handleDrop($event, index)"
          @dragend="handleDragEnd"
        >
          <!-- Tag Strip (Vertical color bar on left) -->
          <view
            v-if="!item.isFolder && item.tags && item.tags.length"
            class="tag-strip"
            :style="getTagStripStyle(item.tags)"
          ></view>
          <view class="tree-item-content" :style="{ paddingLeft: getItemPadding(item) }">
            <!-- Progress Background Bar -->
            <view
              v-if="uploadStatusMap[item.id]"
              class="item-upload-progress-bg"
              :style="{ width: (uploadStatusMap[item.id].progress || 0) + '%' }"
            ></view>

            <view v-if="selectionMode" class="tree-checkbox" @tap.stop="toggleChecked(item)">
              <view
                class="checkbox-box"
                :class="{
                  checked: getCheckState(item) === 'checked',
                  indeterminate: getCheckState(item) === 'indeterminate'
                }"
              ></view>
            </view>
            <view v-if="item.isFolder && showTree" class="tree-expand-icon-wrapper" @tap.stop="toggleFolder(item.id)">
              <image
                :src="expandedFolders.has(item.id) ? '/static/down.png' : '/static/right.png'"
                class="tree-expand-icon-img"
                mode="aspectFit"
              />
            </view>
            <view v-else class="tree-expand-placeholder"></view>

            <!-- Icon Logic: Folder uses CSS, Files use SVG Component -->
            <image
              v-if="item.isFolder"
              class="tree-item-icon-img"
              :class="{ 'is-opened': expandedFolders.has(item.id) }"
              :src="expandedFolders.has(item.id) ? '/static/folder-opened.png' : '/static/folder-closed.png'"
              mode="aspectFit"
            />
            <view v-else class="tree-item-icon-wrapper">
               <FileTypeIcon :type="item.fileType" :active="selectedFileId === item.id" />
            </view>
            <view v-if="renamingId === item.id" class="rename-input-wrapper" @tap.stop @mousedown.stop>
              <input
                class="rename-input"
                v-model="tempRenameValue"
                @confirm="commitRename"
                @blur="commitRename"
                :focus="true"
                @keydown.stop="handleRenameKeydown"
              />
            </view>
            <text v-else class="tree-item-name" :class="{ 'text-muted': uploadStatusMap[item.id] && uploadStatusMap[item.id].progress < 100 }">
               {{ item.name }}
               <text v-if="uploadStatusMap[item.id] && uploadStatusMap[item.id].progress < 100" style="font-size: 10px; color: #999; margin-left: 6px;">
                 {{ Math.floor(uploadStatusMap[item.id].progress) }}%
               </text>
               <text v-if="!item.isFolder && refCounts[item.id] > 0" class="tree-item-ref-count">
                 {{ $t('fileTree.referencedCount', { count: refCounts[item.id] }) }}
               </text>
            </text>
            <view class="tree-item-actions" @tap.stop>
              <template v-if="viewMode === 'files' && renamingId !== item.id">
                <view class="action-btn icon-btn" :title="$t('fileTree.download')" @tap="handleDownload(item)">
                   <image src="/static/download.png" class="action-icon" mode="aspectFit" />
                </view>
                <view class="action-btn icon-btn" :title="$t('fileTree.copy')" @tap="handleCopy(item)">
                   <image src="/static/copy.png" class="action-icon" mode="aspectFit" />
                </view>
                <view class="action-btn icon-btn" :title="$t('fileTree.rename')" @tap="handleRename(item)">
                   <image src="/static/rename.png" class="action-icon" mode="aspectFit" />
                </view>
                <view class="action-btn icon-btn" :title="$t('fileTree.delete')" @tap="handleDelete(item)">
                   <image src="/static/delete.png" class="action-icon" mode="aspectFit" />
                </view>
              </template>
              <template v-else-if="viewMode === 'recycle'">
                <view
                  class="action-btn icon-btn"
                  :title="$t('fileTree.restore')"
                  @tap="restoreFile(item)"
                  @mouseenter="hoverRestore = { ...hoverRestore, [item.id]: true }"
                  @mouseleave="hoverRestore = { ...hoverRestore, [item.id]: false }"
                >
                  <image
                    :src="hoverRestore[item.id] ? '/static/restore.png' : '/static/restore_unselected.png'"
                    class="action-icon"
                    mode="aspectFit"
                  />
                </view>
                <view
                  class="action-btn icon-btn"
                  :title="$t('fileTree.hardDeleteTitle')"
                  @tap="permDeleteFile(item)"
                  @mouseenter="hoverPermDelete = { ...hoverPermDelete, [item.id]: true }"
                  @mouseleave="hoverPermDelete = { ...hoverPermDelete, [item.id]: false }"
                >
                  <image
                    :src="hoverPermDelete[item.id] ? '/static/permnently_delete.png' : '/static/permnently_delete_unselected.png'"
                    class="action-icon"
                    mode="aspectFit"
                  />
                </view>
              </template>

            </view>
          </view>
          <!-- 行内上传进度条 (Removed old one to avoid duplicate) -->
          <view v-if="uploadStatusMap[item.id]" class="upload-progress-inline">
            <view
              class="upload-progress-inline-bar"
              :style="{ width: (uploadStatusMap[item.id].progress || 0) + '%' }"
            ></view>
          </view>
          <view v-if="uploadStatusMap[item.id]" class="upload-progress-inline-text">
            {{ (uploadStatusMap[item.id].progress || 0) }}%
            <text v-if="uploadStatusMap[item.id].speed"> · {{ formatSpeed(uploadStatusMap[item.id].speed) }}</text>
            <text v-if="uploadStatusMap[item.id].eta !== null"> · {{ $t('fileTree.etaAbout', { time: formatEta(uploadStatusMap[item.id].eta) }) }}</text>
          </view>
        </view>
        <!-- 窗口化「展开更多」占位行（dev-board#107 单元 F3）：文件夹子项超过 100 时，
             其余项先不挂进 DOM，点这一行一次性多渲染 200 项。 -->
        <view v-else class="tree-item tree-item-load-more" @tap="revealMore(item.parentId)">
          <view class="tree-item-content" :style="{ paddingLeft: getItemPadding(item) }">
            <view class="tree-expand-placeholder"></view>
            <text class="tree-item-load-more-text">{{ $t('fileTree.loadMoreItems', { count: item.remaining }) }}</text>
          </view>
        </view>
        </template>
        <!-- #endif -->
        <!-- 非H5端使用触摸事件 -->
        <!-- #ifndef H5 -->
        <template v-for="(item, index) in windowedDisplayFiles" :key="item.id">
        <view
          v-if="!item.__loadMore"
          class="tree-item"
          :class="{
            'tree-item-selected': selectedFileId === item.id,
            'tree-item-multi-selected': multiSelectedIds.includes(item.id),
            'tree-item-dragging': draggingIndex === index
          }"
          :data-file-id="item.id"
          @tap="handleItemClick(item, $event)"
          @contextmenu.prevent="handleContextMenu(item, $event)"
          @touchstart="handleTouchStart($event, index)"
          @touchmove="handleTouchMove($event, index)"
          @touchend="handleTouchEnd($event, index)"
        >
          <!-- Tag Strip (Vertical color bar on left) -->
          <view
            v-if="!item.isFolder && item.tags && item.tags.length"
            class="tag-strip"
            :style="getTagStripStyle(item.tags)"
          ></view>
          <view class="tree-item-content" :style="{ paddingLeft: getItemPadding(item) }">
            <!-- Progress Background Bar -->
            <view
              v-if="uploadStatusMap[item.id]"
              class="item-upload-progress-bg"
              :style="{ width: (uploadStatusMap[item.id].progress || 0) + '%' }"
            ></view>

            <view v-if="selectionMode" class="tree-checkbox" @tap.stop="toggleChecked(item)">
              <view
                class="checkbox-box"
                :class="{
                  checked: getCheckState(item) === 'checked',
                  indeterminate: getCheckState(item) === 'indeterminate'
                }"
              ></view>
            </view>
            <view v-if="item.isFolder && showTree" class="tree-expand-icon-wrapper" @tap.stop="toggleFolder(item.id)">
              <image
                :src="expandedFolders.has(item.id) ? '/static/down.png' : '/static/right.png'"
                class="tree-expand-icon-img"
                mode="aspectFit"
              />
            </view>
            <view v-else class="tree-expand-placeholder"></view>

             <!-- Icon Logic: Folder uses CSS, Files use SVG Component -->
            <image
              v-if="item.isFolder"
              class="tree-item-icon-img"
              :class="{ 'is-opened': expandedFolders.has(item.id) }"
              :src="expandedFolders.has(item.id) ? '/static/folder-opened.png' : '/static/folder-closed.png'"
              mode="aspectFit"
            />
            <view v-else class="tree-item-icon-wrapper">
               <FileTypeIcon :type="item.fileType" :active="selectedFileId === item.id" />
            </view>
            <view v-if="renamingId === item.id" class="rename-input-wrapper" @tap.stop @mousedown.stop>
              <input
                class="rename-input"
                v-model="tempRenameValue"
                @confirm="commitRename"
                @blur="commitRename"
                :focus="true"
                @keydown.stop="handleRenameKeydown"
              />
            </view>
            <text v-else class="tree-item-name" :class="{ 'text-muted': uploadStatusMap[item.id] && uploadStatusMap[item.id].progress < 100 }">
               {{ item.name }}
               <text v-if="uploadStatusMap[item.id] && uploadStatusMap[item.id].progress < 100" style="font-size: 10px; color: #999; margin-left: 6px;">
                 {{ Math.floor(uploadStatusMap[item.id].progress) }}%
               </text>
               <text v-if="!item.isFolder && refCounts[item.id] > 0" class="tree-item-ref-count">
                 {{ $t('fileTree.referencedCount', { count: refCounts[item.id] }) }}
               </text>
            </text>
            <view class="tree-item-actions" @tap.stop>
              <template v-if="viewMode === 'files' && renamingId !== item.id">
                <view class="action-btn icon-btn" :title="$t('fileTree.download')" @tap="handleDownload(item)">
                   <image src="/static/download.png" class="action-icon" mode="aspectFit" />
                </view>
                <view class="action-btn icon-btn" :title="$t('fileTree.copy')" @tap="handleCopy(item)">
                   <image src="/static/copy.png" class="action-icon" mode="aspectFit" />
                </view>
                <view class="action-btn icon-btn" :title="$t('fileTree.rename')" @tap="handleRename(item)">
                   <image src="/static/rename.png" class="action-icon" mode="aspectFit" />
                </view>
                <view class="action-btn icon-btn" :title="$t('fileTree.delete')" @tap="handleDelete(item)">
                   <image src="/static/delete.png" class="action-icon" mode="aspectFit" />
                </view>
              </template>
              <template v-else-if="viewMode === 'recycle'">
                <view
                  class="action-btn icon-btn"
                  :title="$t('fileTree.restore')"
                  @tap="restoreFile(item)"
                  @mouseenter="hoverRestore = { ...hoverRestore, [item.id]: true }"
                  @mouseleave="hoverRestore = { ...hoverRestore, [item.id]: false }"
                >
                  <image
                    :src="hoverRestore[item.id] ? '/static/restore.png' : '/static/restore_unselected.png'"
                    class="action-icon"
                    mode="aspectFit"
                  />
                </view>
                <view
                  class="action-btn icon-btn"
                  :title="$t('fileTree.hardDeleteTitle')"
                  @tap="permDeleteFile(item)"
                  @mouseenter="hoverPermDelete = { ...hoverPermDelete, [item.id]: true }"
                  @mouseleave="hoverPermDelete = { ...hoverPermDelete, [item.id]: false }"
                >
                  <image
                    :src="hoverPermDelete[item.id] ? '/static/permnently_delete.png' : '/static/permnently_delete_unselected.png'"
                    class="action-icon"
                    mode="aspectFit"
                  />
                </view>
              </template>

            </view>
          </view>
        </view>
        <!-- 窗口化「展开更多」占位行，非 H5 端同理（见上方 H5 分支的说明） -->
        <view v-else class="tree-item tree-item-load-more" @tap="revealMore(item.parentId)">
          <view class="tree-item-content" :style="{ paddingLeft: getItemPadding(item) }">
            <view class="tree-expand-placeholder"></view>
            <text class="tree-item-load-more-text">{{ $t('fileTree.loadMoreItems', { count: item.remaining }) }}</text>
          </view>
        </view>
        </template>
        <!-- #endif -->

        <!-- Root Drop Zone: 拖拽到此区域可移动到根目录 -->
        <!-- #ifdef H5 -->
        <view
          v-if="isAnyDragging"
          class="root-drop-zone"
          :class="{ 'drop-active': rootDropActive }"
          @dragover.prevent="onRootDragOver"
          @dragleave="onRootDragLeave"
          @drop.prevent="onRootDrop"
        >
          <text>{{ $t('fileTree.dropToRoot') }}</text>
        </view>
        <!-- #endif -->
      </view> <!-- Close tree-list -->

    </view> <!-- Close tree-content -->

    <!-- 独立于 Footer 的上传进度显示 -->
    <view
        v-if="isBatchUploading || Object.keys(uploadStatusMap).length > 0"
        class="upload-status-footer-fixed"
        style="padding: 10px; border-top: 1px solid #eee; background: white; position: relative; flex-shrink: 0;"
        @mouseenter="showUploadDetails = true"
        @mouseleave="showUploadDetails = false"
    >
        <!-- 悬浮详情列表 -->
        <view v-if="showUploadDetails" class="upload-details-popover" style="position: absolute; bottom: 100%; left: 0; right: 0; background: white; border: 1px solid #eee; border-radius: 4px; box-shadow: 0 -2px 10px rgba(0,0,0,0.1); max-height: 200px; overflow-y: auto; z-index: 100;">
            <view class="popover-header" style="padding: 8px 12px; border-bottom: 1px solid #f0f0f0; display: flex; justify-content: space-between; align-items: center; background: #f9fafb;">
                <text style="font-size: 12px; font-weight: bold; color: #333;">{{ $t('fileTree.uploadListCount', { count: Object.keys(uploadStatusMap).length }) }}</text>
                <view style="display: flex; align-items: center; gap: 12px;">
                    <text
                        style="font-size: 11px; color: #ef4444; cursor: pointer; padding: 2px 8px; border: 1px solid #fecaca; border-radius: 4px; background: #fef2f2;"
                        @tap.stop="cancelAllUploads"
                    >{{ $t('fileTree.cancelAll') }}</text>
                    <text style="font-size: 12px; color: #666; cursor: pointer;" @tap.stop="showUploadDetails = false">▼</text>
                </view>
            </view>
            <view class="popover-list">
                <view v-for="status in uploadStatusMap" :key="status.fileId" class="popover-item" style="padding: 8px 12px; border-bottom: 1px solid #f5f5f5; display: flex; align-items: center; justify-content: space-between;">
                    <view style="display: flex; flex-direction: column; width: 85%;">
                        <text style="font-size: 12px; color: #333; overflow: hidden; white-space: nowrap; text-overflow: ellipsis;">{{ status.name }}</text>
                        <!-- Error/Interrupted State -->
                        <view v-if="status.error || status.status === 'interrupted'" style="display: flex; align-items: center; margin-top: 4px;">
                             <text style="font-size: 10px; color: #ef4444;">{{ status.errorMessage || $t('fileTree.interrupted') }}</text>
                        </view>
                        <!-- Normal Progress -->
                        <view v-else style="display: flex; align-items: center; margin-top: 4px;">
                             <view style="width: 100%; height: 2px; background: #eee; border-radius: 2px; overflow: hidden;">
                                 <view :style="{ width: status.progress + '%', background: '#2563eb', height: '100%' }"></view>
                             </view>
                             <text style="font-size: 10px; color: #999; margin-left: 6px;">{{ Math.floor(status.progress) }}%</text>
                        </view>
                    </view>
                    <view style="display: flex; align-items: center;">
                        <!-- Resume Button for Interrupted items -->
                        <text
                            v-if="status.error || status.status === 'interrupted'"
                            class="btn-retry"
                            @tap.stop="resumeUpload(status.fileId)"
                            style="font-size: 14px; color: #2563eb; cursor: pointer; padding: 0 4px; margin-right: 4px;"
                            :title="$t('fileTree.resumeUploadTitle')"
                        >↻</text>
                        <text class="btn-cancel" @tap.stop="cancelSingleUpload(status.fileId)" style="font-size: 16px; color: #999; cursor: pointer; padding: 0 4px;" :title="$t('fileTree.cancelUploadTitle')">×</text>
                    </view>
                </view>
            </view>
        </view>

        <!-- Hidden input removed (using uni.chooseFile) -->

        <view class="upload-status-content" style="display: flex; align-items: center; gap: 12px;">
          <!-- 线性进度条 -->
          <!-- <view style="flex: 1; max-width: 200px;">
            <view style="display: flex; justify-content: space-between; margin-bottom: 4px;">
              <text style="font-size: 13px; font-weight: 600; color: #1e40af;">正在上传文件...</text>
              <text style="font-size: 13px; font-weight: 600; color: #2563eb;">{{ Math.floor(globalUploadProgress || 0) }}%</text>
            </view>
            <view style="width: 100%; height: 8px; background: #e5e7eb; border-radius: 4px; overflow: hidden; position: relative;">
              <view
                :style="{ width: (globalUploadProgress || 0) + '%', background: 'linear-gradient(90deg, #3b82f6, #2563eb)', height: '100%', transition: 'width 0.3s ease' }"
              ></view>
            </view>
            <view style="display: flex; justify-content: space-between; margin-top: 4px;">
              <text style="font-size: 11px; color: #6b7280;">{{ uploadedCount }} / {{ totalUploadCount }} 文件</text>
              <text v-if="uploadSpeed" style="font-size: 11px; color: #6b7280;">{{ formatSpeed(uploadSpeed) }}</text>
            </view>
          </view> -->
          <!-- 圆形进度条 -->
          <CircularProgress
            :percentage="globalUploadProgress || 0"
            :size="48"
            :strokeWidth="4"
            color="#2563eb"
          >
            <text style="font-size: 12px; font-weight: bold;">{{ Math.floor(globalUploadProgress || 0) }}%</text>
          </CircularProgress>
          <view class="upload-status-text" style="display: flex; flex-direction: column; flex: 1;">
             <text class="status-title" style="font-size: 12px; color: #333;">{{ $t('fileTree.uploadingProgress', { done: uploadedCount, total: totalUploadCount }) }}</text>
             <text class="status-detail" v-if="globalUploadProgress !== null" style="font-size: 10px; color: #666;">{{ Math.floor(globalUploadProgress) }}%</text>
          </view>
          <text
            style="font-size: 11px; color: #ef4444; cursor: pointer; padding: 4px 10px; border: 1px solid #fecaca; border-radius: 4px; background: #fef2f2; flex-shrink: 0;"
            @tap.stop="cancelAllUploads"
          >{{ $t('fileTree.cancelAll') }}</text>
        </view>
    </view>

    <!-- 文档对比按钮（选中 2 个文档时显示） -->
    <view v-if="canCompareDocuments()" class="compare-bar">
      <view class="compare-bar-content">
        <text class="compare-bar-text">{{ $t('fileTree.twoDocsSelected') }}</text>
        <button class="btn-compare" @tap="startDocumentCompare">
          <svg class="compare-icon" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
            <path v-for="(d, gi) in ICONS.compare" :key="gi" :d="d" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round" />
          </svg>
          <text>{{ $t('fileTree.compareDocsBtn') }}</text>
        </button>
      </view>
    </view>

    <!-- 底部工具栏 -->
    <view v-if="showFooterActions" class="tree-footer">
       <!-- Upload status moved outside -->
       <!-- 第一行：新建文件夹和新建Word -->
       <view class="footer-row">
        <button class="btn-new-folder" @tap="showCreateFolderDialog">
          {{ $t('fileTree.newFolder') }}
        </button>
        <button class="btn-new-word" @tap="handleCreateWord">
          {{ $t('fileTree.newWord') }}
        </button>
      </view>
      <!-- 第二行：上传文件 -->
      <view class="footer-row">
        <button class="btn-upload" @tap="handleUploadFile">
          {{ $t('fileTree.uploadFile') }}
        </button>
      </view>
    </view>

    <!-- Recycle Bin Dialog -->
    <view v-if="showRecycleBin" class="upload-mask" @tap="showRecycleBin = false">
      <view class="upload-modal" @tap.stop>
         <view class="upload-header">
           <text class="upload-title">{{ $t('fileTree.recycleBin') }}</text>
         </view>
         <view class="upload-body" style="max-height: 300px; overflow-y: auto;">
            <view v-if="recycleBin.length === 0" class="tree-empty">{{ $t('fileTree.recycleBinEmpty') }}</view>
            <view v-else v-for="f in recycleBin" :key="f.id" style="display: flex; justify-content: space-between; padding: 10px; border-bottom: 1px solid #eee;">
               <text>{{ f.name }}</text>
               <view style="display: flex; gap: 10px;">
                  <text @tap="restoreFile(f)" style="color: blue; cursor: pointer;">{{ $t('fileTree.restore') }}</text>
                  <text @tap="permDeleteFile(f)" style="color: red; cursor: pointer;">{{ $t('fileTree.hardDeleteTitle') }}</text>
               </view>
            </view>
         </view>
      </view>
    </view>



    <!-- 重命名对话框 -->
    <view v-if="showRenameDialog" class="dialog-overlay" @tap="showRenameDialog = false">
      <view class="dialog-content" @tap.stop>
        <view class="dialog-header">
          <text class="dialog-title">{{ $t('fileTree.rename') }}</text>
        </view>
        <view class="dialog-body">
          <input
            v-model="renameValue"
            class="dialog-input"
            :placeholder="$t('fileTree.newNamePlaceholder')"
            @confirm="handleConfirmRename"
          />
        </view>
        <view class="dialog-footer">
          <button class="dialog-btn dialog-btn-default" @tap="showRenameDialog = false">{{ $t('fileTree.cancel') }}</button>
          <button class="dialog-btn dialog-btn-primary" @tap="handleConfirmRename">{{ $t('fileTree.confirm') }}</button>
        </view>
      </view>
    </view>
  </view>
</template>

<script>
import { getProjectFiles, createFolder, createFile, renameFile, deleteFile, deleteFilePerm, restoreFile as restoreFileApi, getRecycleBinFiles, moveFile, batchDeleteFiles, batchMoveFiles, batchCopyFiles, getApiBaseUrl } from '@/services/api.js'
import { getAuthHeaders, getSessionId } from '@/utils/auth.js'
import { host } from '@/services/host.js'
import { findTopmostDeletedAncestor, summarizeDeleteResults } from '@/utils/fileTreeRecycle.js'
import { groupByParent, buildTreeFromGroups } from '@/utils/fileTreeBuild.js'
import { evidenceRefCounts } from '@/services/api.js'
import { createRefCountsFetcher } from '@/utils/fileTreeRefCounts.js'
import CircularProgress from '@/components/CircularProgress.vue'
import FileTypeIcon from '@/components/FileTypeIcon.vue'
import TagChip from '@/components/TagChip.vue'
import TagSelector from '@/components/TagSelector.vue'
import TagManager from '@/components/TagManager.vue'
import AwdDatePicker from '@/components/AwdDatePicker.vue'
import { ICONS } from '@/config/icons.js'
import {
  getProjectTags,
  addTagToFile,
  removeTagFromFile,
  createTag,
  createTask
} from '@/services/api.js'

export default {
  name: 'FileTree',
  components: {
    CircularProgress,
    FileTypeIcon,
    TagChip,
    TagSelector,
    TagManager,
    AwdDatePicker
  },
  props: {
    projectId: {
      type: [Number, String],
      required: true
    },
    parentId: {
      type: Number,
      default: null
    },
    // 批量选择模式：由父组件控制（默认关闭）
    selectionMode: {
      type: Boolean,
      default: false
    },
    // 是否展示底部“新建/上传”操作区（默认展示；在 IDE 风格页面由父组件放到头部工具栏）
    showFooterActions: {
      type: Boolean,
      default: true
    },
    hiddenFileIds: {
      type: Array,
      default: () => []
    }
  },
  data() {
    return {
      files: [],
      loading: false,
      selectedFileId: null,
      showCreateDialog: false,
      showRenameDialog: false,
      newFolderName: '',
      renameValue: '',
      renamingFile: null,
      // 拖拽相关
      draggingIndex: -1,
      dragStartY: 0,
      dragCurrentY: 0,
      isDragging: false,
      dragOverIndex: -1, // H5拖拽悬停索引
      draggedIndex: -1, // H5拖拽源索引（仅用于"是不是原地放下"的快速判断，实际
                         // 移动谁必须靠下面的 draggedFileId 重新按 id 查，见 handleDrop）
      draggedFileId: null, // H5拖拽源文件 id：dragstart 时记下，drop 时按 id（而不是
                            // 下标）在（可能已经因为后台重载而重排过的）displayFiles
                            // 里重新定位，防止把并发上传触发的 loadFiles() 期间
                            // 挪了位置的另一个文件当成拖拽源移走
      // 树形结构相关
      allFiles: [], // 完整文件树
      expandedFolders: new Set(), // 展开的文件夹ID集合
      showTree: true, // 是否显示完整树形结构（默认false，只显示当前文件夹）
      // 窗口化渲染（dev-board#107 单元 F3）：文件夹 id -> 当前渲染的子项上限，
      // 缺省 WINDOW_PAGE_SIZE；点「展开更多」一次加 WINDOW_PAGE_SIZE。
      // 不引入虚拟列表，只是把渲染节点数从「整个子树」封顶到「已展开量」。
      revealCounts: {},
      // 「被引用 N 次」角标（dev-board#107 单元 F3）：文件 id -> 引用计数，
      // 树刷新后对当前渲染的文件 id 批量拉取，接口不存在/出错时静默不显示角标。
      refCounts: {},
      // 批量选择（勾选/框选）
      checkedMap: {}, // { [id]: true }
      pendingBatchAction: null, // 'move'|'cut'|'copy'
      batchTargetParentId: null,
      folderSelectorMode: 'upload', // 'upload' | 'batch'
      // 框选状态（H5）
      marquee: {
        active: false,
        startX: 0,
        startY: 0,
        x: 0,
        y: 0,
        w: 0,
        h: 0
      },
      // 上传文件相关
      showUploadDialog: false,
      isFolderUpload: false, // 是否是文件夹上传模式
      showFolderSelector: false,
      selectedUploadParent: null,
      selectedFiles: [], // 选中的文件列表
      tempSelectedParent: null, // 临时选中的父文件夹（用于文件夹选择器）
      // 上传状态：fileId -> { name, size, uploaded, progress, speed, eta, startTime }
      uploadStatusMap: {},
      // 文件夹选择器展开状态（id -> bool），默认 true
      folderSelectorExpanded: {},

      // New Features State
      rootDropActive: false,
      activeFolderId: null,
      lastClickTime: 0,
      lastClickItemId: null,
      recycleBin: [],
      showRecycleBin: false,
      viewMode: 'files', // 'files' | 'recycle'
      renamingId: null,
      tempRenameValue: '',

      // Sort
      sortMode: 'name', // 'name' | 'date' | 'type'
      sortOrder: 'asc', // 'asc' | 'desc'
      showSortMenu: false,

      // Batch Upload Global Progress
      batchUploadTotalSize: 0,
      batchUploadFinishedSize: 0,
      isBatchUploading: false,
      showUploadDetails: false,
      resumingFileId: null,

      // Delete Confirmation
      showDeleteDialog: false,
      deleteTargetItem: null, // The item being deleted
      deleteMode: 'soft', // 'soft' | 'hard'
      deleteIsBatch: false,
      deleteBatchIds: [],
      // Hover states for Recycle Bin icons
      hoverRestore: {}, // { [fileId]: boolean }
      hoverPermDelete: {},

      // Cmd/Ctrl 多选支持
      multiSelectedIds: [], // 多选的文件 ID 数组

      // 右键菜单状态
      contextMenu: {
        visible: false,
        x: 0,
        y: 0,
        targetItem: null
      },
      // Global Drag Support
      isAnyDragging: false,

      // Tag Management
      showTagManager: false,
      showTagEditDialog: false,
      projectTags: [],
      targetFileForTags: null,
      editingFileId: null, // ID of file currently editing tags for

      // 设置截止日（project_task，文件/文件夹右键）
      showDeadlineDialog: false,
      deadlineTargetItem: null,
      deadlineTitle: '',
      deadlineDate: '',
      deadlineTime: '',
      deadlineSaving: false
    }

  },
  computed: {
    ICONS() { return ICONS },
    isDesktopShell() {
      return !!(host.fs && host.fs.showItemInFolder)
    },
    sortLabel() {
      const map = { name: this.$t('fileTree.sortName'), date: this.$t('fileTree.sortModifiedTime'), type: this.$t('fileTree.sortType') }
      return map[this.sortMode] || this.$t('fileTree.sortLabelDefault')
    },
    displayFiles() {
       let result = []
       if (this.viewMode === 'recycle') {
         result = this.recycleBin
       } else {
         // Filter out soft-deleted items
         const binIds = new Set(this.recycleBin.map(f => f.id))
         result = this.files.filter(f => !binIds.has(f.id))
       }

       // Filter out staged files AND the staging folder itself
       const hiddenNames = new Set(['.stagezone', '__staging_area__'])

       if (this.hiddenFileIds && this.hiddenFileIds.length > 0) {
         const hiddenIds = new Set(this.hiddenFileIds.map(id => Number(id)))
         result = result.filter(f => !hiddenIds.has(Number(f.id)) && !hiddenNames.has(f.name))
       } else {
         result = result.filter(f => !hiddenNames.has(f.name))
       }
       return result
    },
    // 窗口化渲染（dev-board#107 单元 F3）：只用于模板 v-for 的渲染层，displayFiles 本身
    // 保持不变——选择/拖拽/批量操作等既有逻辑继续对完整列表生效，只是超过 100 项的
    // 文件夹在 DOM 里只挂前 N 项 + 一行「展开更多」占位（不引入虚拟列表）。
    windowedDisplayFiles() {
      const WINDOW_INITIAL = 100
      const list = this.displayFiles
      const totalByParent = new Map()
      for (const item of list) {
        const key = this.windowParentKey(item.parentId)
        totalByParent.set(key, (totalByParent.get(key) || 0) + 1)
      }
      const emittedByParent = new Map()
      const result = []
      for (const item of list) {
        const key = this.windowParentKey(item.parentId)
        const emitted = emittedByParent.get(key) || 0
        const limit = this.revealCounts[key] || WINDOW_INITIAL
        if (emitted >= limit) {
          if (emitted === limit) {
            result.push({
              __loadMore: true,
              id: `__loadmore_${key}`,
              parentId: item.parentId,
              remaining: totalByParent.get(key) - limit
            })
          }
          emittedByParent.set(key, emitted + 1)
          continue
        }
        result.push(item)
        emittedByParent.set(key, emitted + 1)
      }
      return result
    },
    checkedIds() {
      return Object.keys(this.checkedMap)
        .filter(k => this.checkedMap[k])
        .map(k => Number(k))
        .filter(v => !isNaN(v))
    },
    checkedCount() {
      return this.checkedIds.length
    },
    uploadedCount() {
        return Object.values(this.uploadStatusMap).filter(s => s.progress === 100).length
    },
    totalUploadCount() {
        return Object.keys(this.uploadStatusMap).length
    },
    marqueeStyle() {
      const m = this.marquee
      return {
        left: `${m.x}px`,
        top: `${m.y}px`,
        width: `${m.w}px`,
        height: `${m.h}px`
      }
    },
    folders() {
      return this.allFiles.filter(f => f.isFolder)
    },
    // 用于“选择上传位置”弹窗的文件夹树（扁平化列表）
    // 始终返回数组，避免 undefined.length 报错
    folderTree() {
      if (!Array.isArray(this.allFiles) || this.allFiles.length === 0) {
        return []
      }

      // 只取文件夹
      const folders = this.allFiles.filter(f => f && f.isFolder)
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
       const isRootExpanded = this.folderSelectorExpanded['root'] !== false

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
               // 一级及以下文件夹默认收起，必须显式在 folderSelectorExpanded 标记为 true 才展示下级
               const expanded = this.folderSelectorExpanded[String(node.id)] === true
               if (hasChildren && expanded) {
                 traverse(node.children, level + 1)
               }
             })
         }
         traverse(roots, 1)
       }
      return result
    },
    // 全局上传进度（0-100），如果没有上传任务则返回 null
    globalUploadProgress() {
      // 批量上传模式：使用总进度
      if (this.isBatchUploading && this.batchUploadTotalSize > 0) {
        let currentUploaded = 0
        // 加上正在上传的文件的已上传量 (exclude interrupted)
        Object.values(this.uploadStatusMap).forEach(info => {
            if (info.status !== 'interrupted' && !info.error) {
                currentUploaded += (info.uploaded || 0)
            }
        })
        return Math.min(100, (this.batchUploadFinishedSize + currentUploaded) * 100 / this.batchUploadTotalSize)
      }

      const keys = Object.keys(this.uploadStatusMap).filter(k => {
          const item = this.uploadStatusMap[k]
          // Filter out interrupted items from "active" progress calculation?
          // If we show them in the list, we might want to count them or not.
          // User says "indicator shows 0/23 but not starting".
          // Better to filter them out of the "Active" count.
          return !item.error && item.status !== 'interrupted'
      })
      if (keys.length === 0) return null

      let total = 0
      let uploaded = 0
      keys.forEach(key => {
        const info = this.uploadStatusMap[key]
        total += info.size
        uploaded += info.uploaded
      })
      return total > 0 ? (uploaded / total) * 100 : 0
    },
    // 全局上传速度（所有正在上传文件的速度总和）
    uploadSpeed() {
      let totalSpeed = 0
      Object.values(this.uploadStatusMap).forEach(info => {
        if (!info.error && info.status !== 'interrupted' && info.speed) {
          totalSpeed += info.speed
        }
      })
      return totalSpeed
    }
  },
  watch: {
    projectId: {
      immediate: true,
      handler() {
        // 切换项目时，清空当前上传状态，并恢复新项目的状态
        this.uploadStatusMap = {}
        this.isBatchUploading = false
        this.batchUploadTotalSize = 0
        this.batchUploadFinishedSize = 0
        // 「展开更多」的渲染上限与引用角标都是按项目的，换项目必须归零
        this.revealCounts = {}
        this.refCounts = {}
        this.restoreUploadState()
        this.loadFiles()
      }
    },
    parentId: {
      immediate: true,
      handler() {
        this.loadFiles()
      }
    },
    selectionMode(val) {
      if (!val) {
        this.clearChecked()
      }
    },
    // 打开文件夹选择器时，初始化展开状态并同步当前选择
    showFolderSelector(val) {
      if (val) {
        // 重置为默认状态：根目录展开(undefined !== false)，其他收起(undefined !== true)
        this.folderSelectorExpanded = {}
        this.tempSelectedParent = this.folderSelectorMode === 'batch' ? this.batchTargetParentId : this.selectedUploadParent
      }
    }
  },
  mounted() {
    // selectionMode 关闭时，确保不残留选中态
    if (!this.selectionMode) {
      this.clearChecked()
    }
    this.restoreUploadState()
    this.refreshProjectTags() // Tag Management

    // Listen for global drag events to show/hide root drop zone
    // 具名 handler + Vue3 的 beforeUnmount（beforeDestroy 在 Vue3 不触发，导致监听泄漏）；
    // $off 必须带 handler，否则会连带移除其它 FileTree 实例（FileStagingArea 内嵌）的同名监听。
    this._onDragStart = () => { this.isAnyDragging = true }
    this._onDragEnd = () => { this.isAnyDragging = false }
    uni.$on('file-drag-start', this._onDragStart)
    uni.$on('file-drag-end', this._onDragEnd)
  },
  beforeUnmount() {
    uni.$off('file-drag-start', this._onDragStart)
    uni.$off('file-drag-end', this._onDragEnd)
  },
  methods: {
    // 让文件树容器可聚焦，接收键盘事件（H5）
    focusTree() {
      try {
        const el = typeof document !== 'undefined' ? document.querySelector('.file-tree') : null
        if (el && el.focus) el.focus()
      } catch (e) {
        // ignore
      }
    },
    toggleSortMenu() {
      this.showSortMenu = !this.showSortMenu
    },
    // 公开方法：打开回收站
    openRecycleBin() {
      this.viewMode = 'recycle'
      this.loadFiles()
    },
    exitRecycleBin() {
      this.viewMode = 'files'
      this.loadFiles()
    },
    async loadFiles() {
      if (!this.projectId) {
        console.warn('FileTree: projectId 未设置，无法加载文件列表')
        return
      }

      this.loading = true
      // 整树重载：窗口化上限回到初始值，引用角标整批重拉（旧值会被覆盖而不是叠加）
      this.revealCounts = {}
      this.refCounts = {}
      try {
        // 确保 projectId 是数字类型
        const projectId = typeof this.projectId === 'string' ? Number(this.projectId) : this.projectId
        if (isNaN(projectId)) {
          throw new Error(this.$t('fileTree.projectIdInvalid'))
        }
        if (this.viewMode === 'recycle') {
           this.files = [] // Clear existing
           this.recycleBin = await getRecycleBinFiles(projectId)
           // Recycle mode usually displays `recycleBin` data, not `files`.
           // Ensure the template uses `recycleBin`.
        } else if (this.showTree) {
          // 加载完整文件树
          this.allFiles = await getProjectFiles(projectId, null, true)

          // 过滤掉系统文件夹（在 allFiles 层面过滤，确保所有地方都不会显示）
          const hiddenNames = new Set(['.stagezone', '__staging_area__'])
          this.allFiles = this.allFiles.filter(f => !hiddenNames.has(f.name))

          this.files = this.buildTreeView(this.allFiles, this.parentId)
        } else {
          // 只加载当前文件夹下的文件
          let files = await getProjectFiles(projectId, this.parentId)

          // 过滤掉系统文件夹
          const hiddenNames = new Set(['.stagezone', '__staging_area__'])
          this.files = files.filter(f => !hiddenNames.has(f.name))
        }
        this.scheduleRefCountsFetch({ reload: true })
        console.log('加载文件列表成功:', this.files)
      } catch (error) {
        // 完整打印错误信息，包括堆栈、响应数据等
        console.error('加载文件列表失败:', error)
        console.error('错误详情:', {
          message: error.message,
          stack: error.stack,
          name: error.name,
          response: error.response,
          data: error.data,
          statusCode: error.statusCode,
          errMsg: error.errMsg,
          toString: error.toString()
        })
        uni.showToast({
          title: error.message || this.$t('fileTree.loadFailed'),
          icon: 'none',
          duration: 3000
        })
      } finally {
        this.loading = false
      }
    },
    // Old handleItemClick removed
    /*
    handleItemClick(item) {
       ... moved to below ...
    }
    */
    showCreateFolderDialog() {
      this.newFolderName = ''
      this.showCreateDialog = true
    },
    async handleCreateFolder() {
      if (!this.newFolderName.trim()) {
        uni.showToast({
          title: this.$t('fileTree.folderNamePlaceholder'),
          icon: 'none'
        })
        return
      }

      // 检查是否使用了系统保留名称
      const reservedNames = ['.stagezone', '__staging_area__']
      if (reservedNames.includes(this.newFolderName.trim())) {
        uni.showToast({
          title: this.$t('fileTree.reservedNameNotAllowed'),
          icon: 'none'
        })
        return
      }

      if (!this.projectId) {
        uni.showToast({
          title: this.$t('fileTree.projectIdMissingCreateFolder'),
          icon: 'none'
        })
        return
      }

      try {
        // 确保 projectId 是数字类型
        const projectId = typeof this.projectId === 'string' ? Number(this.projectId) : this.projectId
        if (isNaN(projectId)) {
          throw new Error(this.$t('fileTree.projectIdInvalid'))
        }
        // Use activeFolderId or parentId
        const parentId = this.activeFolderId || this.parentId
        await createFolder(projectId, parentId, this.newFolderName.trim())
        this.showCreateDialog = false
        this.newFolderName = ''
        await this.loadFiles()
        uni.showToast({
          title: this.$t('fileTree.createSuccess'),
          icon: 'success'
        })
      } catch (error) {
        console.error('创建文件夹失败:', error)
        uni.showToast({
          title: error.message || this.$t('fileTree.createFailed'),
          icon: 'none'
        })
      }
    },
    async handleCreateWord() {
      if (!this.projectId) {
        uni.showToast({
          title: this.$t('fileTree.projectIdMissingCreateFile'),
          icon: 'none'
        })
        return
      }

      try {
        // 确保 projectId 是数字类型
        const projectId = typeof this.projectId === 'string' ? Number(this.projectId) : this.projectId
        if (isNaN(projectId)) {
          throw new Error(this.$t('fileTree.projectIdInvalid'))
        }

        // Auto Rename Logic: Check displayFiles for collisions
        let baseName = 'newdocument'
        const ext = '.docx'
        let name = baseName + ext
        let counter = 1

        const existingNames = new Set(this.displayFiles.map(f => f.name))
        while (existingNames.has(name)) {
           name = `${baseName} (${counter})${ext}`
           counter++
        }

        // 生成唯一的 wpsFileId
        const wpsFileId = `project_${projectId}_doc_${Date.now()}_${Math.random().toString(36).substring(2, 9)}`

        // 创建Word文件
        await createFile(
          projectId,
          this.parentId,
          name,
          'docx',
          null, // fileSize
          null, // filePath
          wpsFileId
        )

        await this.loadFiles()
        uni.showToast({
          title: this.$t('fileTree.createSuccess'),
          icon: 'success'
        })
      } catch (error) {
        console.error('创建Word文件失败:', error)
        // 使用模态对话框显示错误
        this.showErrorModal(error.message || this.$t('fileTree.createFileFailedRetry'), this.$t('fileTree.createFailed'))
      }
    },
    handleRenameKeydown(e) {
      if (e.key === 'Enter' || e.key === 'Escape') {
        this.commitRename()
      }
    },
    async handleCopy(item) {
       if (!this.projectId) return
       try {
         const projectId = typeof this.projectId === 'string' ? Number(this.projectId) : this.projectId
         // Duplicate file in same directory
         await batchCopyFiles(projectId, [item.id], item.parentId)

         // Set clipboard
         uni.setClipboardData({
             data: item.name,
             success: () => {
                 uni.showToast({ title: this.$t('fileTree.copiedAndDuplicated'), icon: 'none' })
             }
         })

         await this.loadFiles()
       } catch (error) {
         console.error('复制失败:', error)
         this.showErrorModal(error.message || this.$t('fileTree.copyFileFailedRetry'), this.$t('fileTree.copyFailed'))
       }
    },
    handleRename(item) {
      this.renamingId = item.id
      this.tempRenameValue = item.name
      // this.showRenameDialog = true
    },
    async commitRename() {
      if (!this.renamingId) return

      const fileId = this.renamingId
      const newName = (this.tempRenameValue || '').trim()

      // Reset state first to exit edit mode
      this.renamingId = null
      this.tempRenameValue = ''

      if (!newName) {
        uni.showToast({ title: this.$t('fileTree.nameEmpty'), icon: 'none' })
        return
      }

      // 检查是否使用了系统保留名称
      const reservedNames = ['.stagezone', '__staging_area__']
      if (reservedNames.includes(newName)) {
        uni.showToast({
          title: this.$t('fileTree.reservedNameNotAllowed'),
          icon: 'none'
        })
        return
      }

      // Find file object
      const file = this.allFiles.find(f => f.id === fileId)
      if (!file) return

      if (file.name === newName) return // No change

      if (!this.projectId) return

      try {
        const projectId = typeof this.projectId === 'string' ? Number(this.projectId) : this.projectId
        await renameFile(projectId, fileId, newName)
        await this.loadFiles()
        uni.showToast({ title: this.$t('fileTree.renameSuccess'), icon: 'success' })
      } catch (error) {
        console.error('重命名失败:', error)
        this.showErrorModal(error.message || this.$t('fileTree.renameFailedRetry'), this.$t('fileTree.renameFailed'))
      }
    },
    // Deprecated dialog method
    async handleConfirmRename() {
      if (!this.renameValue.trim()) {
        uni.showToast({
          title: this.$t('fileTree.newNamePlaceholder'),
          icon: 'none'
        })
        return
      }

      if (!this.projectId) {
        uni.showToast({
          title: this.$t('fileTree.projectIdMissing'),
          icon: 'none'
        })
        return
      }

      try {
        const projectId = typeof this.projectId === 'string' ? Number(this.projectId) : this.projectId
        if (isNaN(projectId)) {
          throw new Error(this.$t('fileTree.projectIdInvalid'))
        }
        await renameFile(projectId, this.renamingFile.id, this.renameValue.trim())
        this.showRenameDialog = false
        this.renamingFile = null
        this.renameValue = ''
        await this.loadFiles()
        uni.showToast({
          title: this.$t('fileTree.renameSuccess'),
          icon: 'success'
        })
      } catch (error) {
        console.error('重命名失败:', error)
        uni.showToast({
          title: error.message || this.$t('fileTree.renameFailed'),
          icon: 'none'
        })
      }
    },
    async handleDelete(item) {
      if (!this.projectId) {
        uni.showToast({ title: this.$t('fileTree.projectIdMissing'), icon: 'none' })
        return
      }
      this.deleteTargetItem = item
      this.deleteMode = 'soft'
      this.deleteIsBatch = false
      this.showDeleteDialog = true
    },

    async confirmDelete() {
      this.showDeleteDialog = false

      try {
        if (this.deleteIsBatch) {
           await this.executeBatchDelete()
        } else {
           if (this.deleteMode === 'hard') {
             await this.executePermDelete(this.deleteTargetItem)
           } else {
             await this.executeSoftDelete(this.deleteTargetItem)
           }
        }
      } catch (error) {
        console.error('操作失败:', error)
        this.showErrorModal(error.message || this.$t('fileTree.deleteOpFailedRetry'), this.$t('fileTree.opFailed'))
      } finally {
        this.deleteTargetItem = null
        this.deleteBatchIds = []
      }
    },

    async executeSoftDelete(item) {
      const projectId = typeof this.projectId === 'string' ? Number(this.projectId) : this.projectId
      if (isNaN(projectId)) throw new Error(this.$t('fileTree.projectIdInvalid'))

      // 2. Call API (Soft Delete)
      try {
        await deleteFile(projectId, item.id)
      } catch (e) {
        // 如果文件不存在，视为删除成功
        if (e.statusCode === 404 || e.status === 404) {
          console.warn('文件已不存在，跳过删除报错:', item.id)
        } else {
          throw e
        }
      }
      
      await this.loadFiles()
      uni.showToast({ title: this.$t('fileTree.movedToRecycleBin'), icon: 'success' })
      
      // Emit events
      this.$emit('file-deleted', { ids: [item.id] })

      if (this.selectedFileId === item.id) {
        this.selectedFileId = null
        this.$emit('file-select', null)
      }
    },

    // Wrapper for perm delete with dialog
    permDeleteFile(item) {
       this.deleteTargetItem = item
       this.deleteMode = 'hard'
       this.deleteIsBatch = false
       this.showDeleteDialog = true
    },

    async executePermDelete(item) {
        const projectId = typeof this.projectId === 'string' ? Number(this.projectId) : this.projectId
        try {
            await deleteFilePerm(projectId, item.id)
            // Remove from local bin UI
            const idx = this.recycleBin.findIndex(f => f.id === item.id)
            if (idx > -1) {
              this.recycleBin.splice(idx, 1)
            }
            uni.showToast({ title: this.$t('fileTree.permDeleteSuccess'), icon: 'success' })
            this.$emit('file-deleted', { ids: [item.id] })
        } catch (e) {
             if (e.statusCode === 404 || e.status === 404) {
               console.warn('文件已不存在(彻底删除)，视为成功:', item.id)
               const idx = this.recycleBin.findIndex(f => f.id === item.id)
               if (idx > -1) {
                 this.recycleBin.splice(idx, 1)
               }
               uni.showToast({ title: this.$t('fileTree.permDeleteSuccess'), icon: 'success' })
               this.$emit('file-deleted', { ids: [item.id] })
             } else {
               uni.showToast({ title: this.$t('fileTree.deleteFailed'), icon: 'none' })
             }
        }
    },

    async executeBatchDelete() {
        const ids = this.deleteBatchIds
        const projectId = typeof this.projectId === 'string' ? Number(this.projectId) : this.projectId

        if (this.deleteMode === 'hard') {
            // Batch Perm Delete：逐条调用结果先收集，不能循环完就无条件当全体成功——
            // 否则某一条服务端真的失败时，界面显示全部删除成功且行全部消失，
            // 但服务端其实还留着那份文档。404（服务端已经没有这条）按成功处理。
            const results = []
            for (const id of ids) {
                 try {
                   await deleteFilePerm(projectId, id)
                   results.push({ id, ok: true })
                 } catch (e) {
                   const isMissing = e.statusCode === 404 || e.status === 404
                   if (!isMissing) {
                     console.error('Batch perm delete fail:', id, e)
                   }
                   results.push({ id, ok: isMissing })
                 }
            }
            const { succeededIds, failedIds } = summarizeDeleteResults(results)
            // Update local state：只把真正删掉的从本地列表摘掉，失败的原样留着
            const deletedSet = new Set(succeededIds.map(Number));
            this.recycleBin = this.recycleBin.filter(f => !deletedSet.has(f.id));
            if (failedIds.length > 0) {
              uni.showToast({ title: this.$t('fileTree.permDeletePartialFail', { failed: failedIds.length, total: ids.length }), icon: 'none' })
            } else {
              uni.showToast({ title: this.$t('fileTree.permDeleted'), icon: 'success' })
            }
            this.$emit('file-deleted', { ids: succeededIds })
        } else {
            // Soft Batch Delete
             try {
                await batchDeleteFiles(projectId, ids)
             } catch (e) {
                // Batch delete API might not return granular 404s, but if it fails completely, we throw
                console.error('Batch soft delete fail:', e)
                throw e
             }
             await this.loadFiles()
             uni.showToast({ title: this.$t('fileTree.movedToRecycleBin'), icon: 'success' })
             this.$emit('file-deleted', { ids: ids })
        }
        this.clearChecked()
    },

    async restoreFile(item) {
        if (!item) return
        // 后端还原只向下递归子节点、从不向上恢复祖先：如果 item 的父文件夹（或更上层）
        // 仍在回收站里，直接还原 item 会让它的 parentId 指向一个还是软删除状态的文件夹——
        // 文件树建树时根层只收 parentId 为空的节点、子层只收「已展开文件夹」的子节点，
        // 那个父文件夹压根不在列表里、永不展开，还原出来的文件因此永久不可见。
        const blocker = findTopmostDeletedAncestor(item, this.recycleBin)
        if (blocker) {
            uni.showModal({
                title: this.$t('fileTree.restoreBlockedTitle'),
                content: this.$t('fileTree.restoreBlockedContent', { folderName: blocker.name }),
                confirmText: this.$t('fileTree.restoreBlockedConfirm'),
                cancelText: this.$t('fileTree.cancel'),
                success: (res) => {
                    if (!res.confirm) return
                    // 还原最上层的祖先文件夹：后端会向下级联，把 item（以及同批其它
                    // 子孙）一并带出来。级联影响的不止 blocker 一条本地记录，splice
                    // 单条不够，还原后整体重拉回收站清单，避免残留幽灵行。
                    this.restoreFile(blocker).then(() => {
                        if (this.viewMode === 'recycle') this.loadFiles()
                    })
                }
            })
            return
        }
        const projectId = typeof this.projectId === 'string' ? Number(this.projectId) : this.projectId
        try {
            await restoreFileApi(projectId, item.id)
            // Remove from local bin UI
            const idx = this.recycleBin.findIndex(f => f.id === item.id)
            if (idx > -1) {
              this.recycleBin.splice(idx, 1)
            }
            uni.showToast({ title: this.$t('fileTree.restored'), icon: 'success' })
            // If we are viewing files, we might want to refresh, but usually restore is done in recycle view.
            if (this.viewMode !== 'recycle') {
                this.loadFiles()
            }
        } catch (e) {
            console.error('还原失败:', e)
            uni.showToast({ title: this.$t('fileTree.restoreFailed'), icon: 'none' })
        }
    },

    // 构建树形视图。分组/递归的纯逻辑抽在 utils/fileTreeBuild.js（node:test 单测，
    // 见 tests/evidence/treeBuild.test.mjs）：此前每递归一层都对全量 allFiles 做一次
    // filter+sort，千节点树、多层展开时是 O(N × 展开文件夹数)；现在先一次 O(N) 按
    // parentId 分组，递归只在分组表里取子集。
    buildTreeView(allFiles, parentId) {
      const byParent = groupByParent(allFiles)
      const compareFn = (a, b) => {
         // 文件夹始终置顶
         if (a.isFolder && !b.isFolder) return -1
         if (!a.isFolder && b.isFolder) return 1

         let result = 0
         if (this.sortMode === 'date') {
            // Newest first by default in 'date' mode?
            // Existing was timeB - timeA. We now make it controllable.
            const timeA = new Date(a.updatedAt || a.createdAt || 0).getTime()
            const timeB = new Date(b.updatedAt || b.createdAt || 0).getTime()
            result = timeA - timeB
         } else if (this.sortMode === 'type') {
            // 按类型 A-Z
            const typeA = (a.fileType || '').toLowerCase()
            const typeB = (b.fileType || '').toLowerCase()
            if (typeA !== typeB) {
              result = typeA.localeCompare(typeB)
            } else {
              result = (a.name || '').localeCompare(b.name || '', 'zh-CN', { numeric: true })
            }
         } else {
            // 默认：按名称 A-Z (中文拼音)
            result = (a.name || '').localeCompare(b.name || '', 'zh-CN', { numeric: true })
         }

         // Date mode default desc (newest first)
         if (this.sortMode === 'date') {
            return this.sortOrder === 'asc' ? result : -result
         }

         return this.sortOrder === 'desc' ? -result : result
      }
      return buildTreeFromGroups(byParent, parentId, compareFn, id => this.expandedFolders.has(id))
    },
    setSortMode(mode) {
      this.sortMode = mode
      this.showSortMenu = false
      // 触发重绘
      this.refreshTreeView()
    },
    toggleSortOrder() {
      this.sortOrder = this.sortOrder === 'asc' ? 'desc' : 'asc'
      uni.showToast({
        title: this.sortOrder === 'asc' ? this.$t('fileTree.ascOrder') : this.$t('fileTree.descOrder'),
        icon: 'none'
      })
      this.refreshTreeView()
    },
    refreshTreeView() {
      if (this.showTree && Array.isArray(this.allFiles) && this.allFiles.length > 0) {
        this.files = this.buildTreeView(this.allFiles, this.parentId)
        this.scheduleRefCountsFetch()
      } else {
        this.loadFiles()
      }
    },
    // 切换文件夹展开/收起
    toggleFolder(folderId) {
      if (this.expandedFolders.has(folderId)) {
        this.expandedFolders.delete(folderId)
      } else {
        this.expandedFolders.add(folderId)
      }
      // 重新构建树形视图
      if (this.showTree && this.allFiles.length > 0) {
        this.files = this.buildTreeView(this.allFiles, this.parentId)
        this.scheduleRefCountsFetch()
      }
    },
    // 窗口化渲染的分组 key：根（null/undefined/0）统一归一成 '__root__'，
    // 与 utils/fileTreeBuild.js 的 normalizeParentId 语义一致。
    windowParentKey(parentId) {
      return (parentId === null || parentId === undefined || parentId === 0) ? '__root__' : String(parentId)
    },
    // 「展开更多」：每点一次给该文件夹的渲染上限加 200，直到全部展开。
    revealMore(folderId) {
      const key = this.windowParentKey(folderId)
      const current = this.revealCounts[key] || 100
      this.revealCounts = { ...this.revealCounts, [key]: current + 200 }
      this.scheduleRefCountsFetch()
    },
    // 「被引用 N 次」角标：树刷新/展开更多后，对当前渲染的文件 id 分批拉取引用计数。
    // 防抖/去重/过期丢弃/缺失置 0 的规则在 utils/fileTreeRefCounts.js（有 node 测试）。
    // 接口不存在或出错时静默不显示角标，不 mock 后端。
    scheduleRefCountsFetch({ reload = false } = {}) {
      if (!this.projectId) return
      if (!this._refCountsFetcher) {
        this._refCountsFetcher = createRefCountsFetcher({
          fetch: (projectId, ids) => evidenceRefCounts(projectId, ids),
          apply: (counts) => { this.refCounts = { ...this.refCounts, ...counts } }
        })
      }
      const ids = this.windowedDisplayFiles
        .filter(f => !f.__loadMore && !f.isFolder)
        .map(f => f.id)
      this._refCountsFetcher.schedule(this.projectId, ids, { reload })
    },
    // 计算项目的缩进（用于树形结构）
    getItemPadding(item) {
      if (!this.showTree) return '0'
      // 计算层级深度
      let depth = 0
      let current = item
      while (current && current.parentId !== null) {
        depth++
        current = this.allFiles.find(f => f.id === current.parentId)
        if (!current) break
      }
      return `${depth * 24}rpx`
    },
    // 为选择器设计的新建文件夹逻辑
    async handleSelectorCreateFolder() {
      if (!this.projectId) return

      try {
        const pId = this.tempSelectedParent // 这个是当前的高亮选中项
        const folderName = this.$t('fileTree.newFolder')
        // 1. 创建文件夹
        const res = await createFolder(this.projectId, pId, folderName)
        const newFolderId = res.id || res.data?.id

        // 2. 刷新列表
        await this.loadFiles()

        // 3. 展开父节点
        if (pId) {
          this.folderSelectorExpanded = {
            ...this.folderSelectorExpanded,
            [String(pId)]: true
          }
        } else {
          // 如果是根目录创建，确保根也是展开的
          this.folderSelectorExpanded = {
            ...this.folderSelectorExpanded,
            ['root']: true
          }
        }

        // 4. 进入重命名模式
        this.$nextTick(() => {
          this.renamingId = newFolderId
          this.tempRenameValue = folderName
        })

      } catch (error) {
        console.error('新建文件夹失败:', error)
        uni.showToast({ title: this.$t('fileTree.newFolderFailed'), icon: 'none' })
      }
    },
    // 切换选择器中某个文件夹的展开/收起
    toggleFolderSelectorExpand(folderId) {
      const sId = String(folderId)
      const isRoot = sId === 'root'
      const current = this.folderSelectorExpanded[sId]

      // 根目录默认是展开的 (undefined 或 true)
      // 其他目录默认是收起的 (undefined 或 false)
      let nextState
      if (isRoot) {
        nextState = current === false ? true : false
      } else {
        nextState = current === true ? false : true
      }

      this.folderSelectorExpanded = {
        ...this.folderSelectorExpanded,
        [sId]: nextState
      }
    },
    isChecked(id) {
      return !!this.checkedMap[String(id)]
    },
    getCheckState(item) {
      if (!this.selectionMode || !item) return 'unchecked'
      if (!item.isFolder) {
        return this.isChecked(item.id) ? 'checked' : 'unchecked'
      }

      const ids = this.getDescendantIds(item.id, true) // 包含自身
      let checked = 0
      ids.forEach(id => {
        if (this.isChecked(id)) checked++
      })
      if (checked === 0) return 'unchecked'
      if (checked === ids.length) return 'checked'
      return 'indeterminate'
    },
    getDescendantIds(folderId, includeSelf = false) {
      const all = Array.isArray(this.allFiles) && this.allFiles.length ? this.allFiles : (Array.isArray(this.files) ? this.files : [])
      const childrenMap = new Map()
      all.forEach(f => {
        const pid = f.parentId == null ? null : f.parentId
        if (!childrenMap.has(pid)) childrenMap.set(pid, [])
        childrenMap.get(pid).push(f)
      })
      const result = []
      if (includeSelf) result.push(folderId)
      const stack = [folderId]
      while (stack.length) {
        const cur = stack.pop()
        const kids = childrenMap.get(cur) || []
        kids.forEach(k => {
          result.push(k.id)
          if (k.isFolder) stack.push(k.id)
        })
      }
      return result
    },
    toggleChecked(item) {
      if (!this.selectionMode) return
      if (!item || item.id == null) return

      // 文件夹：联动勾选/取消其全部子孙
      if (item.isFolder) {
        const state = this.getCheckState(item)
        const ids = this.getDescendantIds(item.id, true)
        const next = { ...this.checkedMap }
        if (state === 'checked') {
          ids.forEach(id => delete next[String(id)])
        } else {
          ids.forEach(id => { next[String(id)] = true })
        }
        this.checkedMap = next
        this.$emit('checked-change', this.checkedIds)
        return
      }

      const key = String(item.id)
      const next = { ...this.checkedMap }
      if (next[key]) {
        delete next[key]
      } else {
        next[key] = true
      }
      this.checkedMap = next
      this.$emit('checked-change', this.checkedIds)
    },
    handleItemClick(item, event) {
      if (!item) return

      const now = Date.now()
      // Double Click Detection: Toggle Folder
      if (this.lastClickItemId === item.id && (now - this.lastClickTime < 350)) {
        if (item.isFolder) {
          this.toggleFolder(item.id)
        }
        // Clear logic
        this.lastClickTime = 0
        this.lastClickItemId = null
        return
      }

      this.lastClickItemId = item.id
      this.lastClickTime = now

      if (this.selectionMode) {
        this.toggleChecked(item)
        return
      }

      // Cmd/Ctrl 多选逻辑
      // 使用 try-catch 包裹事件属性访问，避免 WPS iframe 的跨域错误
      let isMultiSelect = false
      try {
        isMultiSelect = event && (event.metaKey || event.ctrlKey)
      } catch (e) {
        // 忽略跨域访问错误（WPS iframe 可能会拦截事件）
        console.warn('检测多选键时出错:', e)
      }

      if (isMultiSelect) {
        // 阻止事件继续传播，避免触发 WPS iframe 的处理逻辑
        if (event && typeof event.stopPropagation === 'function') {
          event.stopPropagation()
        }
        if (event && typeof event.preventDefault === 'function') {
          event.preventDefault()
        }

        // 多选模式：切换当前项的选中状态
        const idx = this.multiSelectedIds.indexOf(item.id)
        if (idx >= 0) {
          this.multiSelectedIds.splice(idx, 1)
        } else {
          this.multiSelectedIds.push(item.id)
        }
        // 多选时也更新 selectedFileId 为最后选中的
        if (this.multiSelectedIds.length > 0) {
          this.selectedFileId = this.multiSelectedIds[this.multiSelectedIds.length - 1]
        }
        this.$emit('multi-select-change', this.multiSelectedIds)
        return
      }

      // 单选模式：清空多选，只选中当前
      this.multiSelectedIds = [item.id]
      this.selectedFileId = item.id
      this.$emit('file-select', item)

      // Update Active Folder for Creation Context
      if (item.isFolder) {
        this.activeFolderId = item.id
      } else {
        this.activeFolderId = item.parentId
      }
    },
    selectAll() {
      if (!this.selectionMode) return
      const next = {}
      // Select all currently displayed files (respecting viewMode and filter)
      const list = this.displayFiles || []
      list.forEach(f => {
        next[String(f.id)] = true
        // If it's a folder, select all its descendants too (hierarchical check)
        if (f.isFolder) {
            const descendantIds = this.getDescendantIds(f.id, true)
            descendantIds.forEach(did => next[String(did)] = true)
        }
      })
      this.checkedMap = next
      this.$emit('checked-change', this.checkedIds)
    },
    clearChecked() {
      this.checkedMap = {}
      this.pendingBatchAction = null
      this.batchTargetParentId = null
      this.folderSelectorMode = 'upload'
      this.$emit('checked-change', [])
    },
    openBatchAction(action) {
      const ids = this.checkedIds
      if (!ids.length) return

      if (action === 'delete') {
        this.deleteBatchIds = ids
        this.deleteMode = this.viewMode === 'recycle' ? 'hard' : 'soft'
        this.deleteIsBatch = true
        this.showDeleteDialog = true
        return
      }

      if (action === 'download') {
         this.executeBatchDownload(ids)
         return
      }

      this.pendingBatchAction = action
      this.folderSelectorMode = 'batch'
      this.batchTargetParentId = null
      this.showFolderSelector = true
    },
    async executeBatchDownload(ids) {
       if (!ids || !ids.length) return

       // Ensure IDs are comparable (string vs number)
       const idSet = new Set(ids.map(String))
       const selectedItems = (this.allFiles || this.files || []).filter(f => idSet.has(String(f.id)))

       if (selectedItems.length > 1) {
           uni.showToast({ title: this.$t('fileTree.batchDownloadUnsupported'), icon: 'error' })
           return
       }

       const item = selectedItems[0]
       if (!item) return

       if (item.isFolder) {
           uni.showToast({ title: this.$t('fileTree.batchDownloadUnsupported'), icon: 'error' })
           return
       }

       uni.showToast({ title: this.$t('fileTree.downloadStarting'), icon: 'none' })

       const baseUrl = getApiBaseUrl()
       const token = getSessionId() || ''

       try {
          const url = `${baseUrl}/api/files/${item.id}/download?token=${encodeURIComponent(token)}`

          const link = document.createElement('a')
          link.href = url
          link.download = item.name || 'download'
          document.body.appendChild(link)
          link.click()
          document.body.removeChild(link)
       } catch (e) {
          console.error('Download failed for', item.id, e)
       }
       this.clearChecked()
    },
    async executeBatchAction() {
      const action = this.pendingBatchAction
      const ids = this.checkedIds
      if (!action || !ids.length) return
      try {
        const projectId = typeof this.projectId === 'string' ? Number(this.projectId) : this.projectId
        const targetParentId = this.batchTargetParentId
        if (action === 'move' || action === 'cut') {
          await batchMoveFiles(projectId, ids, targetParentId)
          uni.showToast({ title: this.$t('fileTree.moveSuccess'), icon: 'success' })
        } else if (action === 'copy') {
          await batchCopyFiles(projectId, ids, targetParentId)
          uni.showToast({ title: this.$t('fileTree.copySuccess'), icon: 'success' })
        }
        this.clearChecked()
        await this.loadFiles()
      } catch (e) {
        console.error('批量操作失败:', e)
        uni.showToast({ title: e.message || this.$t('fileTree.batchOpFailed'), icon: 'none' })
      }
    },
    handleDownload(item) {
        if (!item || item.isFolder) return
        const baseUrl = getApiBaseUrl()
        const token = getSessionId() || ''
        const url = `${baseUrl}/api/files/${item.id}/download?token=${encodeURIComponent(token)}`

        // Trigger browser download; handled by Main process to show Save As dialog
        const link = document.createElement('a')
        link.href = url
        link.download = item.name || 'download'
        document.body.appendChild(link)
        link.click()
        document.body.removeChild(link)
    },
    handleContextMenu(item, event) {
      // 右键：展开文件夹（保持原有功能）
      if (item && item.isFolder && this.showTree && !this.expandedFolders.has(item.id)) {
        this.toggleFolder(item.id)
      }

      // 显示右键菜单
      if (event && item) {
        event.preventDefault()
        event.stopPropagation()

        // 如果右键点击的项不在多选列表中，则将其加入
        if (!this.multiSelectedIds.includes(item.id)) {
          this.multiSelectedIds = [item.id]
          this.selectedFileId = item.id
        }

        this.contextMenu = {
          visible: true,
          x: event.clientX || event.pageX || 0,
          y: event.clientY || event.pageY || 0,
          targetItem: item
        }
      }
    },

    /**
     * 关闭右键菜单
     */
    closeContextMenu() {
      this.contextMenu.visible = false
      this.contextMenu.targetItem = null
    },

    /**
     * 检查是否可以进行文档对比（选中恰好 2 个文档文件）
     */
    canCompareDocuments() {
      if (this.multiSelectedIds.length !== 2) return false
      const docTypes = ['doc', 'docx']
      const selectedFiles = this.multiSelectedIds.map(id =>
        this.allFiles.find(f => f.id === id)
      ).filter(Boolean)
      return selectedFiles.every(f => !f.isFolder && docTypes.includes((f.fileType || '').toLowerCase()))
    },

    /**
     * 获取选中的两个文档文件
     */
    getSelectedDocumentFiles() {
      return this.multiSelectedIds.map(id =>
        this.allFiles.find(f => f.id === id)
      ).filter(Boolean)
    },

    /**
     * 发起文档对比
     */
    startDocumentCompare() {
      if (!this.canCompareDocuments()) return
      const docs = this.getSelectedDocumentFiles()
      this.$emit('compare-documents', docs)
      this.closeContextMenu()
    },

    /**
     * 定位并展开到指定文件
     * @param {number|string} fileId - 要定位的文件 ID
     * @returns {boolean} 是否成功定位
     */
    revealFile(fileId) {
      if (!fileId) return false

      // 确保文件数据已加载
      if (!this.allFiles || this.allFiles.length === 0) {
        console.warn('[FileTree] revealFile: allFiles 为空，无法定位')
        return false
      }

      // 查找目标文件
      const targetFile = this.allFiles.find(f => f.id === fileId || String(f.id) === String(fileId))
      if (!targetFile) {
        console.warn('[FileTree] revealFile: 未找到文件', fileId)
        return false
      }

      // 获取所有父目录 ID 链
      const parentIds = []
      let current = targetFile
      while (current && current.parentId != null) {
        parentIds.push(current.parentId)
        current = this.allFiles.find(f => f.id === current.parentId)
      }

      // 展开所有父目录
      parentIds.forEach(pid => {
        if (!this.expandedFolders.has(pid)) {
          this.expandedFolders.add(pid)
        }
      })

      // 重新构建树形视图
      if (this.showTree && this.allFiles.length > 0) {
        this.files = this.buildTreeView(this.allFiles, this.parentId)
        this.scheduleRefCountsFetch()
      }

      // 设置选中状态（单选模式）
      this.selectedFileId = targetFile.id
      // 清空多选状态，避免旧文件保留多选样式
      this.multiSelectedIds = [targetFile.id]

      // 延迟滚动到目标元素
      this.$nextTick(() => {
        try {
          const el = document.querySelector(`.tree-item[data-file-id="${targetFile.id}"]`)
          if (el && el.scrollIntoView) {
            el.scrollIntoView({ behavior: 'smooth', block: 'center' })
          }
        } catch (e) {
          // ignore scroll errors
        }
      })

      return true
    },

    handleKeyDown(e) {
      if (!e) return
      const key = e.key
      const idx = this.files.findIndex(f => f.id === this.selectedFileId)
      const curIndex = idx >= 0 ? idx : 0

      if (key === 'ArrowDown') {
        e.preventDefault()
        const next = this.files[Math.min(this.files.length - 1, curIndex + 1)]
        if (next) this.handleItemClick(next)
      } else if (key === 'ArrowUp') {
        e.preventDefault()
        const prev = this.files[Math.max(0, curIndex - 1)]
        if (prev) this.handleItemClick(prev)
      } else if (key === 'ArrowLeft') {
        e.preventDefault()
        const cur = this.files[curIndex]
        if (cur && cur.isFolder && this.expandedFolders.has(cur.id)) {
          this.toggleFolder(cur.id)
        }
      } else if (key === 'ArrowRight') {
        e.preventDefault()
        const cur = this.files[curIndex]
        if (cur && cur.isFolder && !this.expandedFolders.has(cur.id)) {
          this.toggleFolder(cur.id)
        }
      }
    },
    // 框选（H5）：在空白区域按下拖动
    onMarqueeStart(e) {
      if (!this.selectionMode) return
      if (!e || e.button !== 0) return
      const target = e.target
      if (target && (target.closest?.('.tree-item') || target.closest?.('.tree-footer'))) return

      this.marquee.active = true
      this.marquee.startX = e.clientX
      this.marquee.startY = e.clientY
      this.marquee.x = e.clientX
      this.marquee.y = e.clientY
      this.marquee.w = 0
      this.marquee.h = 0
      this.clearChecked()
    },
    onMarqueeMove(e) {
      if (!this.selectionMode) return
      if (!this.marquee.active || !e) return
      const x1 = this.marquee.startX
      const y1 = this.marquee.startY
      const x2 = e.clientX
      const y2 = e.clientY
      const left = Math.min(x1, x2)
      const top = Math.min(y1, y2)
      const w = Math.abs(x2 - x1)
      const h = Math.abs(y2 - y1)
      this.marquee.x = left
      this.marquee.y = top
      this.marquee.w = w
      this.marquee.h = h

      try {
        const items = typeof document !== 'undefined' ? document.querySelectorAll('.file-tree .tree-item') : []
        const next = {}
        items.forEach(el => {
          const rect = el.getBoundingClientRect()
          const hit = !(rect.right < left || rect.left > left + w || rect.bottom < top || rect.top > top + h)
          if (hit) {
            const id = el.getAttribute('data-file-id')
            if (id) next[String(id)] = true
          }
        })
        this.checkedMap = next
        this.$emit('checked-change', this.checkedIds)
      } catch (err) {
        // ignore
      }
    },
    onMarqueeEnd() {
      if (!this.marquee.active) return
      this.marquee.active = false
    },

    // 供父组件快速同步文件名（例如 WPS 内重命名）
    updateFileName(fileId, newName) {
      if (!fileId || !newName) return
      if (Array.isArray(this.files)) {
        this.files.forEach(f => {
          if (f.id === fileId) f.name = newName
        })
      }
      if (Array.isArray(this.allFiles)) {
        this.allFiles.forEach(f => {
          if (f.id === fileId) f.name = newName
        })
      }
      this.$forceUpdate()
    },
    // 根据文件类型返回图标样式类
    getFileIconClass(item) {
      if (item.isFolder) return 'icon-folder'
      const t = (item.fileType || '').toLowerCase()
      if (t === 'doc' || t === 'docx') return 'icon-word'
      if (t === 'xls' || t === 'xlsx') return 'icon-excel'
      if (t === 'ppt' || t === 'pptx') return 'icon-ppt'
      if (t === 'pdf') return 'icon-pdf'
      return 'icon-file'
    },
    // 根据文件类型返回图标文字（经典 Office 首字母）
    getFileIconLabel(item) {
      if (item.isFolder) return ''
      const t = (item.fileType || '').toLowerCase()
      if (t === 'doc' || t === 'docx') return 'W'
      if (t === 'xls' || t === 'xlsx') return 'X'
      if (t === 'ppt' || t === 'pptx') return 'P'
      if (t === 'pdf') return 'PDF'
      return 'F'
    },
    // 进度条显示速度（格式化为 KB/s 或 MB/s）
    formatSpeed(speedBytesPerSec) {
      if (!speedBytesPerSec || speedBytesPerSec <= 0) return ''
      const kb = speedBytesPerSec / 1024
      if (kb < 1024) {
        return kb.toFixed(1) + ' KB/s'
      }
      const mb = kb / 1024
      return mb.toFixed(1) + ' MB/s'
    },
    // 格式化剩余时间（秒 => Xm Ys）
    formatEta(etaSeconds) {
      if (etaSeconds == null || etaSeconds <= 0) return this.$t('fileTree.etaDone')
      const s = Math.round(etaSeconds)
      const m = Math.floor(s / 60)
      const rest = s % 60
      if (m === 0) return `${rest}s`
      if (rest === 0) return `${m}min`
      return `${m}min ${rest}s`
    },
    // #ifdef H5
    handleDragStart(e, item, index) {
      console.log('拖拽开始:', index)
      this.draggedIndex = index
      this.draggedFileId = (item && item.id != null) ? item.id : null
      uni.$emit('file-drag-start')
      // 向外部暴露“拖拽文件开始”（用于 WPS 文档建立关联）
      try {
        if (item && item.id && !item.isFolder) {
          this.$emit('file-drag-start', { id: item.id, name: item.name, fileType: item.fileType, wpsFileId: item.wpsFileId })
        }
      } catch (e) {
        // ignore
      }
      // 检查 dataTransfer 是否存在（在某些环境中可能不存在）
      if (e.dataTransfer) {
        // 允许 copy/link/move
        e.dataTransfer.effectAllowed = 'all'

        // 设置自定义拖拽图片
        const img = new Image()
        img.src = '/static/Drag.png'
        // 设置图片热点为中心 (假设图片大概 30x30, 这里的 offset 可以根据实际图调整)
        e.dataTransfer.setDragImage(img, 15, 15)

        // 设置拖拽数据
        try {
          // 1. 基础索引，用于列表内排序
          e.dataTransfer.setData('text/plain', index.toString())

          // 2. 完整文件信息，用于跨组件拖拽（如拖到 WPS）
          if (item && item.id) {
            const fileData = JSON.stringify({
              fileId: item.id,
              name: item.name,
              fileType: item.isFolder ? 'folder' : item.fileType,
              wpsFileId: item.wpsFileId
            })
            // 标准自定义类型
            e.dataTransfer.setData('application/x-checkba-file', fileData)
            // 兜底
            e.dataTransfer.setData('text/checkba-file-json', fileData)
          }
        } catch (err) {
          // 某些环境可能不支持 setData，静默失败
          console.warn('设置拖拽数据失败:', err)
        }
      }

      // Fallback: Global variable for environments where dataTransfer is cleared
      if (typeof document !== 'undefined') {
          try {
             if (item && item.id) {
                document.__checkbaDraggedFile = {
                   fileId: item.id,
                   name: item.name,
                   fileType: item.isFolder ? 'folder' : item.fileType,
                   wpsFileId: item.wpsFileId
                }
             }
          } catch(e) {}
      }
    },
    // #endif
    handleDragOver(e, index) {
      e.preventDefault()
      // 检查 dataTransfer 是否存在（在某些环境中可能不存在）
      if (e.dataTransfer) {
        e.dataTransfer.dropEffect = 'move'
      }
      if (this.dragOverIndex !== index) {
        this.dragOverIndex = index
      }
    },
    async handleDrop(e, index) {
      e.preventDefault()
      // 回收站视图守卫：:draggable 只挡住了"从回收站里拖出"，挡不住"从外部（如暂存区）
      // 拖一个文件扔到回收站的某一行"——那条分支不看 draggedIndex，直接调 moveFile，
      // 会把一个正常文件的 parentId 改写成指向一个软删除的文件夹。回收站视图整个禁止落点。
      if (this.viewMode === 'recycle') return
      console.log('拖拽放下:', { draggedIndex: this.draggedIndex, targetIndex: index })

      const projectId = typeof this.projectId === 'string' ? Number(this.projectId) : this.projectId
      // BUGFIX: 用 windowedDisplayFiles 而不是 displayFiles——模板 v-for 挂的是
      // windowedDisplayFiles（窗口化渲染，dev-board#107 单元 F3），index 是那个数组里的下标。
      const targetItem = this.windowedDisplayFiles[index]
      if (!targetItem || targetItem.__loadMore) return

      // Determine Target Parent ID
      let targetParentId
      let newSortOrder = targetItem.sortOrder

      // If dropping onto a folder, move INTO it
      // If dropping onto a file, move to same parent (sibling)
      if (targetItem.isFolder) {
         targetParentId = targetItem.id
         newSortOrder = 0 // Or keep default
      } else {
         targetParentId = this.showTree ? targetItem.parentId : this.parentId
      }

      // Case 1: Internal FileTree Drag (Reordering)
      if (this.draggedIndex !== -1) {
          if (this.draggedIndex === index) {
            this.dragOverIndex = -1
            this.draggedIndex = -1
            this.draggedFileId = null
            return
          }

          try {
            // BUGFIX（原用 displayFiles[this.draggedIndex]，仍会撞上同一类竞态）：
            // dragstart 与 drop 之间可能有后台重载（比如并发批量上传完成触发的
            // loadFiles()）把 displayFiles 重新排序/过滤过，draggedIndex 这个下标
            // 到 drop 时可能已经指向另一个文件——按 dragstart 时记下的 id 重新在
            // 当前 displayFiles 里查，查不到说明拖拽源已经不在列表里（被移走/
            // 删除/过滤掉了），放弃这次移动而不是把错的文件挪过去。
            const draggedItem = this.displayFiles.find(f => f.id === this.draggedFileId)
            if (!draggedItem) {
              console.warn('拖拽源文件已不在列表中，取消移动')
              uni.showToast({ title: this.$t('fileTree.moveFailed'), icon: 'none' })
              this.dragOverIndex = -1
              this.draggedIndex = -1
              this.draggedFileId = null
              return
            }

            // Prevent self-parenting or no-op moves if needed check
            if (draggedItem.id === targetParentId) return

            await moveFile(projectId, draggedItem.id, targetParentId, newSortOrder)
            await this.loadFiles()
            uni.showToast({ title: this.$t('fileTree.moveSuccess'), icon: 'success' })
          } catch (error) {
            console.error('移动文件失败:', error)
            uni.showToast({ title: error.message || this.$t('fileTree.moveFailed'), icon: 'none' })
          }
      }
      // Case 2: External Drag (e.g. from Staging Area)
      else {
          // Parse dropped data
          let droppedFileId = null
          let droppedFileName = ''

          if (e.dataTransfer) {
              try {
                  let rawData = e.dataTransfer.getData('application/x-checkba-file')
                  if (!rawData) rawData = e.dataTransfer.getData('text/checkba-file-json')

                  if (rawData) {
                      const data = JSON.parse(rawData)
                      if (data.fileId) {
                          droppedFileId = data.fileId
                          droppedFileName = data.name
                      }
                  }
              } catch (err) {}
          }

          // Fallback global check
          if (!droppedFileId && typeof document !== 'undefined' && document.__checkbaDraggedFile) {
              droppedFileId = document.__checkbaDraggedFile.fileId
              droppedFileName = document.__checkbaDraggedFile.name
              document.__checkbaDraggedFile = null // Consume
          }

          if (droppedFileId) {
             try {
                 await moveFile(projectId, droppedFileId, targetParentId, newSortOrder)
                 await this.loadFiles()
                 uni.showToast({ title: this.$t('fileTree.moveSuccess'), icon: 'success' })

                 // If Staging Area listens to file changes (it does via project-overview reloading),
                 // it will update automatically.
                 // However, project-overview needs to know to reload staging?
                 // Actually moveFile changes the parent, so it disappears from .stagezone
                 // Staging Area should refresh? project-overview usually listens to changes?
                 // We might need to emit an event to notify parent to refresh staging area
                 this.$emit('files-changed') // Standardize this event?

                 // Verify if project-overview handles this.
             } catch (error) {
                console.error('从暂存区移动失败:', error)
                uni.showToast({ title: error.message || this.$t('fileTree.moveFailed'), icon: 'none' })
             }
          }
      }

      this.dragOverIndex = -1
      this.draggedIndex = -1
      this.draggedFileId = null
    },
    handleDragEnd() {
      this.draggedIndex = -1
      this.draggedFileId = null
      this.dragOverIndex = -1
      this.$emit('file-drag-end')
      uni.$emit('file-drag-end')
    },
    onRootDragOver(e) {
      e.preventDefault()
      this.rootDropActive = true
    },
    onRootDragLeave() {
      this.rootDropActive = false
    },
    async onRootDrop(e) {
      e.preventDefault()
      this.rootDropActive = false
      console.log('拖拽到根目录')

      const projectId = typeof this.projectId === 'string' ? Number(this.projectId) : this.projectId
      const targetParentId = null // Move to root
      const newSortOrder = 0

      // Case 1: Internal FileTree Drag
      if (this.draggedIndex !== -1) {
          try {
            // 同 handleDrop 的 BUGFIX：按 dragstart 时记下的 id 重新定位，不用可能
            // 已经因后台重载而过期的下标。
            const draggedItem = this.displayFiles.find(f => f.id === this.draggedFileId)
            if (!draggedItem) {
              console.warn('拖拽源文件已不在列表中，取消移动')
              uni.showToast({ title: this.$t('fileTree.moveFailed'), icon: 'none' })
              this.draggedIndex = -1
              this.draggedFileId = null
              return
            }

            // If already in root (parentId is null or matches), we might still want to allow movement if subfolder -> root
            await moveFile(projectId, draggedItem.id, targetParentId, newSortOrder)
            await this.loadFiles()
            uni.showToast({ title: this.$t('fileTree.moveToRootSuccess'), icon: 'success' })
            this.draggedIndex = -1
            this.draggedFileId = null
          } catch (error) {
            console.error('移动到根目录失败:', error)
            uni.showToast({ title: error.message || this.$t('fileTree.moveFailed'), icon: 'none' })
          }
      }
      // Case 2: External Drag (e.g. from Staging Area)
      else {
          let droppedFileId = null
          if (e.dataTransfer) {
              try {
                  let rawData = e.dataTransfer.getData('application/x-checkba-file')
                  if (!rawData) rawData = e.dataTransfer.getData('text/checkba-file-json')
                  if (rawData) {
                      const data = JSON.parse(rawData)
                      if (data.fileId) droppedFileId = data.fileId
                  }
              } catch (err) {}
          }
          if (!droppedFileId && typeof document !== 'undefined' && document.__checkbaDraggedFile) {
              droppedFileId = document.__checkbaDraggedFile.fileId
              document.__checkbaDraggedFile = null
          }

          if (droppedFileId) {
             try {
                 await moveFile(projectId, droppedFileId, targetParentId, newSortOrder)
                 await this.loadFiles()
                 uni.showToast({ title: this.$t('fileTree.moveToRootSuccess'), icon: 'success' })
                 this.$emit('files-changed')
             } catch (error) {
                console.error('从外部移动到根目录失败:', error)
                uni.showToast({ title: error.message || this.$t('fileTree.moveFailed'), icon: 'none' })
             }
          }
      }
    },
    // 非H5端触摸拖拽方法
    handleTouchStart(e, index) {
      this.draggingIndex = index
      this.dragStartY = e.touches[0].clientY
      this.isDragging = false
    },
    handleTouchMove(e, index) {
      if (this.draggingIndex === -1) return
      this.dragCurrentY = e.touches[0].clientY
      const deltaY = this.dragCurrentY - this.dragStartY
      if (Math.abs(deltaY) > 10) {
        this.isDragging = true
      }
    },
    async handleTouchEnd(e, index) {
      if (!this.isDragging || this.draggingIndex === -1) {
        this.draggingIndex = -1
        return
      }

      const endY = e.changedTouches[0].clientY
      const deltaY = endY - this.dragStartY
      const itemHeight = 60 // 估算每个项目高度（rpx转px约30px）
      const targetIndex = Math.round(deltaY / itemHeight) + index

      // BUGFIX: 用 windowedDisplayFiles 而不是 displayFiles——模板 v-for 挂的是
      // windowedDisplayFiles（窗口化渲染，dev-board#107 单元 F3），index 是那个数组里的下标。
      if (targetIndex !== index && targetIndex >= 0 && targetIndex < this.windowedDisplayFiles.length) {
        try {
          const projectId = typeof this.projectId === 'string' ? Number(this.projectId) : this.projectId
          const draggedItem = this.windowedDisplayFiles[index]
          const targetItem = this.windowedDisplayFiles[targetIndex]
          if (!draggedItem || draggedItem.__loadMore || !targetItem || targetItem.__loadMore) return

          let targetParentId
          let newSortOrder = targetItem.sortOrder

          if (targetItem.isFolder && draggedItem.parentId !== targetItem.id) {
             targetParentId = targetItem.id
             newSortOrder = 0
          } else {
             targetParentId = this.showTree ? targetItem.parentId : this.parentId
          }

          await moveFile(projectId, draggedItem.id, targetParentId, newSortOrder)
          await this.loadFiles()
          uni.showToast({
            title: this.$t('fileTree.moveSuccess'),
            icon: 'success'
          })
        } catch (error) {
          console.error('移动文件失败:', error)
          uni.showToast({
            title: error.message || this.$t('fileTree.moveFailed'),
            icon: 'none'
          })
        }
      }

      this.draggingIndex = -1
      this.isDragging = false
    },
    // 上传文件相关方法
    handleUploadFile() {
      this.selectedUploadParent = this.parentId
      this.selectedFiles = []
      this.isFolderUpload = false
      this.showUploadDialog = true
    },
    // #ifdef H5
    triggerFolderUpload() {
      // 动态创建 input 元素
      const input = document.createElement('input')
      input.type = 'file'
      input.webkitdirectory = true
      input.directory = true // 非标准，兼容性
      input.multiple = true

      input.onchange = (e) => {
        const files = Array.from(e.target.files || [])
        if (files.length === 0) return

        this.isFolderUpload = true
        this.selectedFiles = files.map(f => ({
          name: f.name,
          size: f.size,
          path: URL.createObjectURL(f), // 生成临时预览地址
          fileObject: f, // 保留原始 File 对象用于上传
          relativePath: f.webkitRelativePath || f.name // 保留相对路径结构
        }))
      }

      input.click()
    },
    // #endif
    selectFiles() {
      // uni-app 文件选择
      uni.chooseFile({
        count: 9, // 最多选择9个文件
        count: 9, // 最多选择9个文件
        success: (res) => {
          this.isFolderUpload = false
          this.selectedFiles = res.tempFiles.map(file => ({
            name: file.name,
            path: file.path,
            size: file.size,
            relativePath: file.name, // 普通文件上传只有文件名
            fileObject: file
          }))
        },
        fail: (err) => {
          console.error('选择文件失败:', err)
          uni.showToast({
            title: this.$t('fileTree.chooseFileFailed'),
            icon: 'none'
          })
        }
      })
    },
    removeFile(index) {
      this.selectedFiles.splice(index, 1)
    },
    selectUploadParent(parentId) {
      this.tempSelectedParent = parentId
    },
    confirmFolderSelection() {
      if (this.folderSelectorMode === 'batch') {
        this.batchTargetParentId = this.tempSelectedParent
        this.showFolderSelector = false
        this.executeBatchAction()
        return
      }
      this.selectedUploadParent = this.tempSelectedParent
      this.showFolderSelector = false
    },

    // 设置截止日（project_task）
    openDeadlineDialog(item) {
      this.deadlineTargetItem = item
      this.deadlineTitle = item ? item.name : ''
      this.deadlineDate = ''
      this.deadlineTime = ''
      this.showDeadlineDialog = true
    },
    async confirmSetDeadline() {
      if (this.deadlineSaving) return
      const title = (this.deadlineTitle || '').trim()
      if (!title) {
        uni.showToast({ title: this.$t('calendar.requiredTitle'), icon: 'none' })
        return
      }
      if (!this.deadlineDate) {
        uni.showToast({ title: this.$t('calendar.requiredDate'), icon: 'none' })
        return
      }
      this.deadlineSaving = true
      try {
        await createTask({
          projectId: this.projectId,
          fileId: this.deadlineTargetItem ? this.deadlineTargetItem.id : null,
          title,
          dueDate: this.deadlineDate,
          dueTime: this.deadlineTime || null
        })
        this.showDeadlineDialog = false
        uni.showToast({ title: this.$t('calendar.deadlineSet'), icon: 'none' })
      } catch (e) {
        uni.showToast({ title: (e && e.message) || this.$t('calendar.saveFailed'), icon: 'none' })
      } finally {
        this.deadlineSaving = false
      }
    },

    // Tag Methods
    async refreshProjectTags() {
      if (!this.projectId) return
      try {
        const res = await getProjectTags(this.projectId)
        this.projectTags = res.data || res || []
      } catch (e) {
        console.error('Failed to load project tags', e)
      }
    },
    openTagEditDialog(file) {
       this.targetFileForTags = file
       this.showTagEditDialog = true
       this.refreshProjectTags()
    },
    async handleAddTag(tag) {
       if (!this.targetFileForTags) return
       try {
         await addTagToFile(this.projectId, this.targetFileForTags.id, tag.id)
         // Optimistic update
         if (!this.targetFileForTags.tags) this.$set(this.targetFileForTags, 'tags', [])
         this.targetFileForTags.tags.push(tag)
       } catch (e) {
         uni.showToast({ title: 'Failed to add tag', icon: 'none' })
       }
    },
    async handleCreateNewTag(payload) {
       // payload can be string (legacy) or { name, color, type } object
       const tagName = typeof payload === 'string' ? payload : payload?.name
       const tagColor = typeof payload === 'object' ? payload?.color : '#5BD197'
       const tagType = typeof payload === 'object' ? payload?.type : undefined

       if (!this.targetFileForTags || !tagName) return
       try {
         // 1. Create Tag
         const res = await createTag(this.projectId, { name: tagName, color: tagColor, type: tagType })
         const newTag = res.data || res

         // 2. Refresh available tags
         await this.refreshProjectTags()

         // 3. Add to file
         await this.handleAddTag(newTag)

       } catch (e) {
         console.error(e)
         uni.showToast({ title: 'Failed to create tag', icon: 'none' })
       }
    },
    async handleRemoveTag(tag) {
       if (!this.targetFileForTags) return
       try {
         await removeTagFromFile(this.projectId, this.targetFileForTags.id, tag.id)
         // Optimistic update
         const idx = this.targetFileForTags.tags.findIndex(t => t.id === tag.id)
         if (idx > -1) this.targetFileForTags.tags.splice(idx, 1)

       } catch (e) {
         uni.showToast({ title: 'Failed to remove tag', icon: 'none' })
       }
    },

    // ====== Tag Strip Styling ======
    /**
     * Generates inline style for the vertical tag strip.
     * Sorts tags by color spectrum (hue) and creates a gradient if multiple tags.
     */
    getTagStripStyle(tags) {
      if (!tags || tags.length === 0) {
        return { display: 'none' }
      }

      // Sort tags by spectral position (hue)
      const sortedTags = this.sortTagsBySpectrum(tags)
      const colors = sortedTags.map(t => t.color || '#5BD197')

      if (colors.length === 1) {
        return { background: colors[0] }
      }

      // Generate equal segments for gradient
      const segments = colors.map((color, i) => {
        const start = (i / colors.length) * 100
        const end = ((i + 1) / colors.length) * 100
        return `${color} ${start}%, ${color} ${end}%`
      }).join(', ')

      return { background: `linear-gradient(to bottom, ${segments})` }
    },

    /**
     * Sorts tags by their color's hue value (spectral order).
     * Red -> Orange -> Yellow -> Green -> Cyan -> Blue -> Purple
     */
    sortTagsBySpectrum(tags) {
      if (!tags || tags.length <= 1) return tags

      const getHue = (hexColor) => {
        if (!hexColor) return 180 // Default to cyan
        const hex = hexColor.replace('#', '')
        const r = parseInt(hex.substring(0, 2), 16) / 255
        const g = parseInt(hex.substring(2, 4), 16) / 255
        const b = parseInt(hex.substring(4, 6), 16) / 255

        const max = Math.max(r, g, b)
        const min = Math.min(r, g, b)
        let h = 0

        if (max === min) {
          h = 0
        } else if (max === r) {
          h = 60 * (((g - b) / (max - min)) % 6)
        } else if (max === g) {
          h = 60 * (((b - r) / (max - min)) + 2)
        } else {
          h = 60 * (((r - g) / (max - min)) + 4)
        }

        if (h < 0) h += 360
        return h
      }

      return [...tags].sort((a, b) => getHue(a.color) - getHue(b.color))
    },

    toggleFolderExpand(folderId) {
      if (this.expandedFolderIds.has(folderId)) {
        this.expandedFolderIds.delete(folderId)
      } else {
        this.expandedFolderIds.add(folderId)
      }
      // 触发响应式更新
      this.$forceUpdate()
    },
    getFolderPath(folderId) {
      // 如果传入的是ID，查找对应的文件夹
      if (typeof folderId === 'number' || typeof folderId === 'string') {
        const folder = this.allFiles.find(f => f.id === folderId)
        if (folder) {
          return this.buildFolderPath(folder)
        }
        return this.$t('fileTree.unknownFolder')
      }
      // 如果传入的是文件夹对象
      if (folderId && folderId.name) {
        return this.buildFolderPath(folderId)
      }
      return this.$t('fileTree.rootDirectory')
    },
    // 构建文件夹完整路径
    buildFolderPath(folder) {
      if (!folder) return ''
      const path = [folder.name]
      let current = folder
      // 向上查找父文件夹，构建完整路径
      while (current && current.parentId !== null) {
        const parent = this.allFiles.find(f => f.id === current.parentId)
        if (parent) {
          path.unshift(parent.name)
          current = parent
        } else {
          break
        }
      }
      return path.join(' / ')
    },
    cancelUpload() {
      this.showUploadDialog = false
      this.selectedFiles = []
      this.selectedUploadParent = null
    },
    async confirmUpload() {
      if (this.selectedFiles.length === 0) {
        uni.showToast({
          title: this.$t('fileTree.selectFilesFirst'),
          icon: 'none'
        })
        return
      }

      if (!this.projectId) {
        uni.showToast({
          title: this.$t('fileTree.projectIdMissing'),
          icon: 'none'
        })
        return
      }

      try {
        const projectId = typeof this.projectId === 'string' ? Number(this.projectId) : this.projectId
        if (isNaN(projectId)) {
          throw new Error(this.$t('fileTree.projectIdInvalid'))
        }
        const files = [...this.selectedFiles]
        const rootParentId = this.selectedUploadParent

        // 关闭弹窗，清空选择
        this.showUploadDialog = false
        this.selectedFiles = []
        this.selectedUploadParent = null
        this.isFolderUpload = false // 重置模式

        // 文件夹路径缓存 (path -> folderId)
        // key: relative path (e.g., "A", "A/B"), value: folderId
        const dirCache = { "": rootParentId }

        // Helper to get directory path
        const getDirName = (path) => {
           if (!path) return ''
           // Windows path separator handling? Usually webkitRelativePath uses '/'
           const parts = path.split('/')
           if (parts.length <= 1) return ''
           parts.pop() // remove filename
           return parts.join('/')
        }

        // 1. 收集所有需要创建的目录
        const dirsToCreate = new Set()
        // Calculate total size for batch progress
        let totalSize = 0
        files.forEach(f => {
           totalSize += (f.size || 0)
           if (f.relativePath) {
              const dir = getDirName(f.relativePath)
              if (dir) {
                 // 添加所有父级目录
                 const parts = dir.split('/')
                 let current = ''
                 parts.forEach(p => {
                    current = current ? `${current}/${p}` : p
                    dirsToCreate.add(current)
                 })
              }
           }
        })

        // Initialize or update batch progress
        this.isBatchUploading = true
        if (!this.batchUploadTotalSize) this.batchUploadTotalSize = 0
        this.batchUploadTotalSize += totalSize
        // this.batchUploadFinishedSize already exists, don't reset it
        if (!this.batchUploadFinishedSize) this.batchUploadFinishedSize = 0

        // 统计成功和失败的数量
        let successCount = 0
        let failCount = 0

        // 2. 排序：短路径在前（确保先创建父目录）
        const sortedDirs = Array.from(dirsToCreate).sort((a, b) => a.split('/').length - b.split('/').length)

        // 3. 逐个创建目录
        for (const dirPath of sortedDirs) {
           if (dirCache[dirPath] !== undefined) continue // 已存在

           const parts = dirPath.split('/')
           const folderName = parts.pop()
           const parentPath = parts.join('/')
           const parentId = dirCache[parentPath]

           try {
              const newFolder = await createFolder(projectId, parentId, folderName)
              if (newFolder && newFolder.id) {
                dirCache[dirPath] = newFolder.id

                // 乐观更新：如果新建的文件夹在当前视图下，立即加入显示列表
                // 注意：parentId可能是null或数字，this.parentId可能是null或数字
                const isCurrentDir = (parentId === this.parentId) ||
                                     (parentId == null && this.parentId == null) ||
                                     (String(parentId) === String(this.parentId))
                if (isCurrentDir) {
                    this.files.unshift(newFolder)
                    // 同时加入 activeFolderId 对应的缓存（如果需要）但 loadFiles 稍后会刷新
                }
                this.allFiles.unshift(newFolder)
              } else {
                throw new Error('创建文件夹未返回ID')
              }
           } catch (e) {
              console.warn(`尝试创建文件夹 ${folderName} 失败，尝试查找已存在的...`, e)
              try {
                  // 如果创建失败（可能是同名），尝试查找已存在的文件夹
                  const siblings = await getProjectFiles(projectId, parentId)
                  const existing = siblings.find(f => f.isFolder && f.name === folderName)
                  if (existing) {
                      dirCache[dirPath] = existing.id
                  } else {
                      // 确实无法创建也找不到，回退到父目录
                      console.error(`无法创建也无法找到文件夹 ${folderName}`)
                      dirCache[dirPath] = parentId
                  }
              } catch (innerE) {
                  console.error('查找已存在文件夹失败', innerE)
                  dirCache[dirPath] = parentId // Fallback
              }
           }
        }

      // 4. 准备文件队列并展示“等待中”状态
        const uploadQueue = []
        // Ensure file objects cache is initialized
        if (!this.uploadFileObjects) {
             this.uploadFileObjects = {}
        }

        files.forEach((file, index) => {
            const tempId = `pending_${Date.now()}_${index}`
            this.uploadStatusMap[tempId] = {
                fileId: tempId,
                name: file.name,
                size: file.size || 0,
                uploaded: 0,
                progress: 0,
                status: 'pending', // Special status
                xhr: null
            }
            // CACHE THE FILE OBJECT for resume support without re-selection
            this.uploadFileObjects[tempId] = file
            uploadQueue.push({ file, tempId })
        })
        this.saveUploadState()

        // 5. 并发上传控制
        const CONCURRENCY = 3
        let activeCount = 0
        let currentIndex = 0

        const processNext = async () => {
             // Check if we are done with the queue
             if (currentIndex >= uploadQueue.length) {
                 if (activeCount === 0) {
                     // All done
                     this.isBatchUploading = false
                     this.batchUploadFinishedSize = 0 // Reset only when all done
                     this.uploadFileObjects = {} // Clean up cache when batch done (or keep? better keep for failed items)
                     // Actually, we should only clean up successful ones.
                     // But for now, let's just trigger loadFiles
                     this.loadFiles()

                     // 显示批量上传完成提示，包含成功和失败的统计
                     const totalCount = uploadQueue.length
                     if (failCount === 0) {
                       // 全部成功
                       uni.showToast({
                         title: this.$t('fileTree.uploadSuccessCount', { count: successCount }),
                         icon: 'success',
                         duration: 2000
                       })
                     } else if (successCount === 0) {
                       // 全部失败
                       uni.showToast({
                         title: this.$t('fileTree.uploadFailCount', { count: failCount }),
                         icon: 'error',
                         duration: 2000
                       })
                     } else {
                       // 部分成功
                       uni.showToast({
                         title: this.$t('fileTree.uploadPartialResult', { success: successCount, fail: failCount }),
                         icon: successCount > failCount ? 'success' : 'none',
                         duration: 2500
                       })
                     }
                 }
                 return
             }

             activeCount++
             const { file, tempId } = uploadQueue[currentIndex++]

             const dirPath = getDirName(file.relativePath || '')
             // Resolve Parent ID: if we created a folder, use it; otherwise use root
             const targetParentId = dirCache[dirPath] !== undefined ? dirCache[dirPath] : rootParentId

             try {
                 // Update cache with real ID later?
                 // uploadSingleFile returns the new fileId, but we might need to map tempId -> realId in cache?
                 // Actually uploadSingleFile handles the transition.
                 await this.uploadSingleFile(projectId, file, targetParentId, tempId)
                 successCount++ // 成功计数
             } catch (error) {
                 console.error('上传文件失败:', error)
                 failCount++ // 失败计数
                 // 显示错误提示（使用模态对话框）
                 this.showErrorModal(error.message || this.$t('fileTree.uploadFileFailedRetry'), this.$t('fileTree.uploadFailed'))
                 // Keep the error in status map so user can see it
                 if (this.uploadStatusMap[tempId]) {
                      this.uploadStatusMap[tempId].status = 'interrupted'
                      this.uploadStatusMap[tempId].error = true
                      this.uploadStatusMap[tempId].errorMessage = error.message || this.$t('fileTree.uploadFailed')
                      this.saveUploadState()
                 }
                 // Do not delete from map, allow retry
             } finally {
                 activeCount--
                 processNext()
             }
        }

        // Start initial batch
        for (let i = 0; i < Math.min(CONCURRENCY, uploadQueue.length); i++) {
             processNext()
        }

      } catch (error) {
        console.error('上传文件失败:', error)
        this.showErrorModal(error.message || this.$t('fileTree.uploadFileFailedRetry'), this.$t('fileTree.uploadFailed'))
        this.isBatchUploading = false
      }
    },
    // 支持分片断点续传的上传逻辑
    async uploadSingleFile(projectId, file, parentId, pendingTempId = null) {
      // 0. 检查是否存在同名且处于失败状态的文件记录，如果存在则先删除
      try {
        const failedFile = this.allFiles.find(f =>
          f.name === file.name &&
          f.parentId === parentId &&
          f._isUploading === true &&
          this.uploadStatusMap[f.id] &&
          (this.uploadStatusMap[f.id].error || this.uploadStatusMap[f.id].status === 'interrupted')
        )
        if (failedFile) {
          console.log('发现同名失败文件记录，先删除:', failedFile.name)
          await deleteFile(projectId, failedFile.id)
          // 从前端列表和状态中移除
          this.files = this.files.filter(f => f.id !== failedFile.id)
          this.allFiles = this.allFiles.filter(f => f.id !== failedFile.id)
          delete this.uploadStatusMap[failedFile.id]
          this.saveUploadState()
        }
      } catch (e) {
        console.warn('检查失败文件记录时出错，继续上传:', e)
      }

      // 1. 创建文件记录
      const fileType = this.getFileTypeFromName(file.name)
      const wpsFileId = `project_${projectId}_doc_${Date.now()}_${Math.random().toString(36).substring(2, 9)}`

      // 乐观更新：仅当文件属于当前视图（parentId匹配）时，才添加到前端列表
      const isCurrentDir = (parentId === this.parentId) ||
                           (parentId == null && this.parentId == null) ||
                           (String(parentId) === String(this.parentId))

      // 并发批量上传（CONCURRENCY=3）时，processNext() 在同一 tick 里背靠背调用
      // 本方法：每次调用在真正 await createFile(...) 之前都是纯同步代码，三次
      // Date.now() 落在同一毫秒里完全可能拿到一模一样的值。撞车的两行会共享同一
      // 个 id——Vue :key 靠它做身份识别，进度/文件名会在两行之间串掉，且
      // findIndex(f => f.id === tempId) 只命中第一条，另一条永远等不到真实记录
      // 替换，卡死在"正在上传"。叠加一个自增序号，保证同一毫秒内也互不相同。
      const tempId = Date.now() * 1000 + (this._uploadTempIdSeq = ((this._uploadTempIdSeq || 0) + 1) % 1000)
      // Transfer file object from pending cache to new ID cache
      if (pendingTempId && this.uploadFileObjects[pendingTempId]) {
           this.uploadFileObjects[tempId] = this.uploadFileObjects[pendingTempId]
           delete this.uploadFileObjects[pendingTempId]
      } else {
           // Direct upload case
           if (!this.uploadFileObjects) this.uploadFileObjects = {}
           this.uploadFileObjects[tempId] = file
      }

      const tempFileObj = {
          id: tempId,
          projectId,
          parentId,
          name: file.name,
          isFolder: false, // Uploaded items via this method are files. Folders are created separately.
          fileType: fileType,
          size: file.size,
          createTime: new Date().toISOString(),
          updateTime: new Date().toISOString(),
          sortOrder: 0, // 暂时置顶
          wpsFileId: wpsFileId,
          _isUploading: true // 标记为正在上传
      }

      // 添加到本地列表，实现“立即刷新”效果
      if (isCurrentDir) {
           this.files.unshift(tempFileObj)
      }
      // allFiles 始终添加（如果维护完整树）
      this.allFiles.unshift(tempFileObj)

      // 真实创建请求
      let createdFile
      try {
          createdFile = await createFile(
            projectId,
            parentId,
            file.name,
            fileType,
            file.size,
            null,
            wpsFileId
          )
          // 替换临时生成的 ID
          if (isCurrentDir) {
              const idx = this.files.findIndex(f => f.id === tempId)
              if (idx !== -1) {
                  const updated = { ...createdFile, _isUploading: true }
                  this.files[idx] = updated
              }
          }
          const allIdx = this.allFiles.findIndex(f => f.id === tempId)
          if (allIdx !== -1) {
              const updated = { ...createdFile, _isUploading: true }
              this.allFiles[allIdx] = updated
          }
      } catch (e) {
          // 创建失败，移除临时文件
          if (isCurrentDir) {
              this.files = this.files.filter(f => f.id !== tempId)
          }
          this.allFiles = this.allFiles.filter(f => f.id !== tempId)
          throw e
      }

      // 如果有挂起的临时任务状态，先移除，再添加真实的
      if (pendingTempId && this.uploadStatusMap[pendingTempId]) {
          delete this.uploadStatusMap[pendingTempId]
      }

      // 初始化进度状态
      this.uploadStatusMap[createdFile.id] = {
        fileId: createdFile.id,
        wpsFileId: wpsFileId,
        name: createdFile.name,
        size: file.size || 0,
        uploaded: 0,
        progress: 0,
        speed: 0,
        eta: null,
        startTime: Date.now(),
        // 保存原始 File 对象用于续传 (H5 only since we can hold ref)
        // 注意：页面刷新后 File 对象会丢失，需要用户重新选择，或暂时只支持单次会话续传
        // 如需跨刷新续传，H5 无法直接获取 File，但 requestFileSystem API 支持有限。
        // 这里主要支持“网络中断/暂停”后的续传，以及页面不刷新情况下的重试。
        fileObject: file.fileObject || file // Prefer raw File object
      }

      // 保存到 localStorage 以便刷新后知道有哪些断点任务（虽然没 File 对象传不了，但可以提示）
      this.saveUploadState()

      return this.processChunkedUpload(createdFile.id, file.fileObject || file, createdFile)
    },

    async processChunkedUpload(fileId, fileObject, fileRecord) {
       const status = this.uploadStatusMap[fileId]
       if (!status) return

       const CHUNK_SIZE = 5 * 1024 * 1024 // 5MB chunks
       const TIMEOUT = 60000 // 60s timeout
       const MAX_RETRIES = 3 // Retry 3 times

       try {
           // 1. 获取服务器已上传大小 (Check server status)
           let offset = 0
           try {
               const res = await uni.request({
                   url: `${this.getApiBaseUrl()}/api/files/${status.wpsFileId}/upload-status`,
                   header: this.getAuthHeaders(),
                   method: 'GET'
               })
               if (res.data && res.data.code === 0 && res.data.data) {
                   offset = res.data.data.uploadedSize || 0
               }
           } catch (e) {
               console.warn('获取断点状态失败，将从头上传', e)
           }

           if (offset >= status.size) {
               this.completeUpload(fileId)
               return
           }

           status.uploaded = offset
           this.updateProgress(fileId, offset, status.size)

           // 2. 开始分片上传
           while (offset < status.size) {
               const end = Math.min(offset + CHUNK_SIZE, status.size)
               const chunk = fileObject.slice(offset, end)

               // Retry loop
               let retryCount = 0
               let success = false

               while (!success && retryCount < MAX_RETRIES) {
                   try {
                       await new Promise((resolve, reject) => {
                   let isTimeout = false
                   const timer = setTimeout(() => {
                       isTimeout = true
                       reject(new Error('Upload timeout'))
                   }, TIMEOUT)

                   // 使用 uni.request 发送 ArrayBuffer (H5)
                   // 注意：uni.uploadFile 也可以，但 headers控制较弱，且通常用于 multipart
                   // 这里改用 request 发送二进制流

                   // #ifdef H5
                   const xhr = new XMLHttpRequest()
                   // 保存 XHR 以便取消
                   if (this.uploadStatusMap[fileId]) {
                      this.uploadStatusMap[fileId].xhr = xhr
                   }

                   xhr.open('POST', `${this.getApiBaseUrl()}/api/files/${status.wpsFileId}/upload`)

                   const headers = this.getAuthHeaders()
                   for (const key in headers) {
                       xhr.setRequestHeader(key, headers[key])
                   }
                   xhr.setRequestHeader('Content-Type', 'application/octet-stream')
                   xhr.setRequestHeader('X-File-Offset', offset.toString())
                   xhr.setRequestHeader('X-File-Total-Size', status.size.toString()) // Notify backend of total size for RAG trigger

                   xhr.upload.onprogress = (e) => {
                       if (e.lengthComputable) {
                           // 块内进度
                           const chunkProgress = e.loaded
                           this.updateProgress(fileId, offset + chunkProgress, status.size)
                       }
                   }

                   xhr.onload = () => {
                       clearTimeout(timer)
                       if (xhr.status >= 200 && xhr.status < 300) {
                           resolve()
                       } else {
                           reject(new Error(`HTTP ${xhr.status}`))
                       }
                   }

                   xhr.onabort = () => {
                       clearTimeout(timer)
                       reject(new Error('Aborted'))
                   }

                   xhr.onerror = () => {
                       clearTimeout(timer)
                       reject(new Error('Network Error'))
                   }

                   xhr.send(chunk)
                   // #endif

                   // #ifndef H5
                   // 非H5端暂不支持流式分片，回退到普通上传或使用 uni.uploadFile 模拟
                   // 由于非H5端 FileSlice 支持有限，这里简单处理：
                   // 如果是大文件，建议使用特定平台的原生上传插件
                   // 这里暂且回退到整文件上传或报错
                   reject(new Error(this.$t('fileTree.chunkUnsupportedNonH5')))
                   // #endif
               })

               success = true // Mark as success if promise resolves

               } catch (e) {
                   console.warn(`Chunk upload failed, attempt ${retryCount + 1}/${MAX_RETRIES}`, e)
                   retryCount++
                   if (retryCount >= MAX_RETRIES) {
                       throw e // Rethrow if max retries reached
                   }
                   // Optional: add delay before retry
                   await new Promise(r => setTimeout(r, 1000 * retryCount))
               }
           } // End retry loop

           offset = end
           this.saveUploadState()
       }
       this.completeUpload(fileId)

       } catch (error) {
           console.error('分片上传失败:', error)
           // 显示具体的错误信息（使用模态对话框）
           const errorMessage = error.message || this.$t('fileTree.uploadInterruptedRetry')
           this.showErrorModal(errorMessage, this.$t('fileTree.uploadFailed'))
           // 不要删除 status，保留进度条以允许重试
           status.error = true
           status.errorMessage = errorMessage
           // 保存失败状态
           this.saveUploadState()
       }
    },

    updateProgress(fileId, uploaded, total) {
        const status = this.uploadStatusMap[fileId]
        if (!status) return

        status.uploaded = uploaded
        status.progress = (uploaded / total) * 100

        // Calculate speed
        const now = Date.now()
        const diffTime = (now - status.startTime) / 1000
        if (diffTime > 0) {
            status.speed = uploaded / diffTime
        }

        this.$forceUpdate()
    },

    completeUpload(fileId) {
        const status = this.uploadStatusMap[fileId]
        if (status) {
            status.progress = 100
            status.uploaded = status.size
            this.batchUploadFinishedSize += status.size
            // 不再显示单个文件的成功提示，等待批量完成后统一提示
        }

        // Remove from map after delay
        setTimeout(() => {
            if (this.uploadStatusMap[fileId]) {
                delete this.uploadStatusMap[fileId]
                this.saveUploadState()

                // 去掉 _isUploading 标记
                const idx = this.files.findIndex(f => f.id === fileId)
                if (idx !== -1) {
                    this.files[idx]._isUploading = undefined
                }
            }
        }, 1000)
    },

    cancelSingleUpload(fileId) {
        const status = this.uploadStatusMap[fileId]
        if (status) {
            // Abort XHR
            if (status.xhr) {
                status.xhr.abort()
            }

            // Remove from map
            delete this.uploadStatusMap[fileId]
            this.saveUploadState()

            // Optimistic UI cleanup
            // Remove from this.files if present
            const idx = this.files.findIndex(f => f.id === fileId)
            if (idx !== -1) {
                this.files.splice(idx, 1) // Remove it completely like a delete
            }
            // Remove from allFiles
            const allIdx = this.allFiles.findIndex(f => f.id === fileId)
            if (allIdx !== -1) {
                this.allFiles.splice(allIdx, 1)
            }

            uni.showToast({
                title: this.$t('fileTree.uploadCanceled'),
                icon: 'none'
            })
        }
    },

    cancelAllUploads() {
        const fileIds = Object.keys(this.uploadStatusMap)
        if (fileIds.length === 0) return

        fileIds.forEach(fileId => {
            const status = this.uploadStatusMap[fileId]
            if (status) {
                // Abort XHR if still running
                if (status.xhr) {
                    status.xhr.abort()
                }
                // Remove from files list
                const idx = this.files.findIndex(f => f.id === fileId)
                if (idx !== -1) {
                    this.files.splice(idx, 1)
                }
                const allIdx = this.allFiles.findIndex(f => f.id === fileId)
                if (allIdx !== -1) {
                    this.allFiles.splice(allIdx, 1)
                }
            }
        })

        // Clear all upload status
        this.uploadStatusMap = {}
        this.isBatchUploading = false
        this.batchUploadTotalSize = 0
        this.batchUploadFinishedSize = 0
        this.saveUploadState()

        uni.showToast({
            title: this.$t('fileTree.allUploadsCanceled'),
            icon: 'none'
        })
    },

    resumeUpload(fileId) {
        this.resumingFileId = fileId

        // Strategy 1: Check In-Memory Cache (No user interaction needed)
        if (this.uploadFileObjects && this.uploadFileObjects[fileId]) {
            console.log('Resuming from cache flow', fileId)
            const cachedFile = this.uploadFileObjects[fileId]
            this.handleResumeFile(cachedFile)
            return
        }

        // Strategy 2: Fallback to File Selection (If refreshed)
        // #ifdef H5
        uni.showToast({ title: this.$t('fileTree.reselectAfterRefresh'), icon: 'none' })
        uni.chooseFile({
            count: 1,
            success: (res) => {
                const files = res.tempFiles
                if (files && files.length > 0) {
                     this.handleResumeFile(files[0])
                }
            },
            fail: (err) => {
                console.warn('Choose file failed', err)
            }
        })
        // #endif

        // #ifndef H5
        uni.showToast({ title: this.$t('fileTree.resumeUnsupportedApp'), icon: 'none' })
        // #endif
    },

    handleResumeFile(file) {
        if (!file || !this.resumingFileId) return

        const status = this.uploadStatusMap[this.resumingFileId]
        if (!status) return

        // Validation: Verify if same file
        if (file.name !== status.name || Math.abs(file.size - status.size) > 1024) {
             if (file.name !== status.name) {
                 uni.showToast({ title: this.$t('fileTree.selectNamedFile', { name: status.name }), icon: 'none' })
                 return
             }
             if (file.size !== status.size) {
                 uni.showToast({ title: this.$t('fileTree.fileSizeMismatch'), icon: 'none' })
                 return
             }
        }

        // Reset status
        this.$set(this.uploadStatusMap, this.resumingFileId, {
            ...status,
            error: false,
            status: 'pending',
            errorMessage: null,
            xhr: null
        })

        // Restart upload
        uni.showToast({ title: this.$t('fileTree.resumingUpload'), icon: 'none' })
        this.processChunkedUpload(this.resumingFileId, file)

        this.resumingFileId = null
    },

    saveUploadState() {
        try {
            // 只保存 metadata，File对象无法保存
            const state = {}
            for (const key in this.uploadStatusMap) {
                const item = this.uploadStatusMap[key]
                state[key] = {
                    fileId: item.fileId,
                    wpsFileId: item.wpsFileId,
                    name: item.name,
                    size: item.size,
                    uploaded: item.uploaded || 0,
                    progress: item.progress || 0,
                    startTime: item.startTime
                }
            }
            const storageKey = `upload_state_v2_project_${this.projectId}`
            uni.setStorageSync(storageKey, JSON.stringify(state))
        } catch (e) { console.error('Save state failed', e) }
    },

    restoreUploadState() {
        try {
            const storageKey = `upload_state_v2_project_${this.projectId}`
            const data = uni.getStorageSync(storageKey)
            if (!data) {
                this.uploadStatusMap = {}
                return
            }
            const state = JSON.parse(data)
            // 恢复时，检查是否有正在进行的任务。由于刷新导致 File 对象丢失，无法自动继续。
            // 必须将这些任务标记为中断/失败，避免 UI 假死。
            for (const fileId in state) {
                const item = state[fileId]
                // 如果任务未完成 (progress < 100)，标记为 error
                if (item.progress < 100) {
                    item.error = true
                    item.status = 'interrupted' // Add explicitly status
                    item.errorMessage = this.$t('fileTree.refreshInterrupted')
                    // 确保 xhr 是空的
                    item.xhr = null
                }
            }
            this.uploadStatusMap = state
        } catch (e) {}
    },

    // Placeholder for old method if called elsewhere
    async uploadSingleFileLegacy(projectId, file, parentId) {
       // ... kept for fallback if needed, or removed
    },
    // 统一的错误提示函数
    showErrorModal(message, title = this.$t('fileTree.opFailed')) {
      // 使用 Toast 替代 Modal，无需手动确认
      uni.showToast({
        title: message,
        icon: 'none',
        duration: 3000
      })
    },
    getFileTypeFromName(fileName) {
      const ext = fileName.split('.').pop()?.toLowerCase()
      const typeMap = {
        'doc': 'doc',
        'docx': 'docx',
        'pdf': 'pdf',
        'xls': 'xls',
        'xlsx': 'xlsx',
        'ppt': 'ppt',
        'pptx': 'pptx'
      }
      return typeMap[ext] || ext || 'file'
    },
    getApiBaseUrl() {
      return getApiBaseUrl()
    },
    getAuthHeaders() {
      return getAuthHeaders()
    }
  }
}
</script>

<style lang="scss" scoped>
$brand-primary: $brand-color-primary;
$brand-border: $brand-border-light;
$bg: $uni-bg-color;
$bg-grey: $uni-bg-color-grey;

.file-tree {
  height: 100%;
  display: flex;
  flex-direction: column;
}

.file-tree,
.tree-content,
.tree-list,
.tree-item,
.tree-item-name {
  font-family: -apple-system, BlinkMacSystemFont, "PingFang SC", "Microsoft YaHei", system-ui, sans-serif;
}

.tree-toolbar {
  padding: 12rpx 16rpx;
  border-bottom: 1rpx solid #e5e7eb;
  display: flex;
  gap: 8rpx;
  background-color: #ffffff;
}

.btn-new-folder,
.btn-new-word {
  flex: 1 1 0;
  width: auto;
  max-width: none;
  min-width: 0;
  height: 56rpx;
  font-size: 24rpx;
  padding: 0;
  line-height: 56rpx;
  border-radius: 10rpx;
  border: 1px solid transparent;
  transition: all 0.2s;
  box-sizing: border-box;
}

/* uni-app button 默认会带 ::after 边框，这里统一去掉，避免窄屏/不同端显示“发虚/变丑” */
.btn-new-folder::after,
.btn-new-word::after,
.btn-upload::after,
.batch-btn::after,
.dialog-btn::after {
  border: none;
}

.btn-new-folder {
  background-color: $bg;
  border-color: $brand-primary;
  color: $brand-primary;
}

.btn-new-folder:active {
  background-color: rgba($brand-primary, 0.06);
}

.btn-new-word {
  background-color: $brand-primary;
  color: $uni-text-color-inverse;
}

.btn-new-word:active {
  background-color: rgba($brand-primary, 0.92);
}

.tree-content {
  flex: 1;
  overflow-y: auto;

  /* Custom Scrollbar */
  &::-webkit-scrollbar {
    width: 6px;
    background-color: transparent;
  }

  &::-webkit-scrollbar-thumb {
    background-color: transparent;
    border-radius: 3px;
    transition: background-color 0.2s;
  }

  &:hover::-webkit-scrollbar-thumb {
    background-color: rgba(148, 163, 184, 0.3);
  }

  &::-webkit-scrollbar-thumb:hover {
    background-color: rgba(148, 163, 184, 0.5);
  }
}

.file-tree {
  outline: none;
}


.tree-list {
  flex: 1;
  position: relative;
  min-height: 100rpx;
  display: flex;
  flex-direction: column;
  height: 100%;
}

.root-drop-zone,
.root-drop-zone-empty {
  margin: 12rpx 16rpx;
  padding: 24rpx;
  border: 1rpx dashed #e2e8f0;
  border-radius: 8rpx;
  display: flex;
  align-items: center;
  justify-content: center;
  color: #94a3b8;
  font-size: 22rpx;
  transition: all 0.3s cubic-bezier(0.4, 0, 0.2, 1);
  background-color: #f8fafc;
  opacity: 0.8;
  animation: fadeIn 0.3s ease-out;

  text {
    pointer-events: none;
  }

  &.drop-active {
    background-color: #eff6ff;
    border-color: #3b82f6;
    color: #3b82f6;
    border-style: solid;
    transform: scale(1.005);
    opacity: 1;
    box-shadow: 0 2rpx 8rpx rgba(59, 130, 246, 0.08);
  }
}

@keyframes fadeIn {
  from { opacity: 0; transform: translateY(4rpx); }
  to { opacity: 0.8; transform: translateY(0); }
}

.root-drop-zone {
  min-height: 60rpx;
}

.root-drop-zone-empty {
  min-height: 160rpx;
  margin-top: 40rpx;
}

.tree-empty {
  display: flex;
  flex-direction: column;
  padding: 40rpx 0;
  flex: 1;

  .empty-content {
     display: flex;
     justify-content: center;
     padding: 40rpx 0;
     color: #94a3b8;
     font-size: 28rpx;
  }
}

.marquee {
  position: fixed;
  z-index: 999;
  border: 1px solid rgba(37, 99, 235, 0.55);
  background: rgba(37, 99, 235, 0.10);
  pointer-events: none;
  border-radius: 6px;
}

/* 右键上下文菜单样式 */
.context-menu-mask {
  position: fixed;
  top: 0;
  left: 0;
  right: 0;
  bottom: 0;
  z-index: 9999;
  background: transparent;
}

.context-menu {
  position: fixed;
  min-width: 160px;
  background: #fff;
  border-radius: 8px;
  box-shadow: 0 4px 16px rgba(0, 0, 0, 0.15);
  padding: 4px 0;
  z-index: 10000;
  border: 1px solid #e5e7eb;
}

.context-menu-item {
  display: flex;
  align-items: center;
  padding: 10px 16px;
  cursor: pointer;
  transition: background 0.15s;
}

.context-menu-item:hover {
  background: #f3f4f6;
}

.context-menu-item-danger:hover {
  background: #fef2f2;
}

.context-menu-item-danger .context-menu-text {
  color: #dc2626;
}

.context-menu-icon {
  font-size: 14px;
  margin-right: 10px;
  width: 20px;
  text-align: center;
}

.context-menu-text {
  font-size: 13px;
  color: #374151;
}

/* 文档对比按钮栏 */
.compare-bar {
  padding: 8px 12px;
  background: linear-gradient(135deg, #e0f2fe 0%, #dbeafe 100%);
  border-top: 1px solid #bae6fd;
  border-bottom: 1px solid #bae6fd;
}

.compare-bar-content {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.compare-bar-text {
  font-size: 12px;
  color: #0369a1;
  font-weight: 500;
}

.btn-compare {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 6px 14px;
  background: linear-gradient(135deg, #0ea5e9 0%, #2563eb 100%);
  color: #fff;
  border: none;
  border-radius: 6px;
  font-size: 12px;
  font-weight: 500;
  cursor: pointer;
  transition: all 0.2s;
  box-shadow: 0 2px 4px rgba(37, 99, 235, 0.2);
}

.btn-compare:hover {
  background: linear-gradient(135deg, #0284c7 0%, #1d4ed8 100%);
  transform: translateY(-1px);
  box-shadow: 0 3px 8px rgba(37, 99, 235, 0.3);
}

.btn-compare:active {
  transform: translateY(0);
}

.compare-icon {

  width: 14px;
  height: 14px;
  flex-shrink: 0;
}

.tree-checkbox {
  width: 30rpx;
  display: flex;
  align-items: center;
  justify-content: center;
}

.checkbox-box {
  width: 14px;
  height: 14px;
  border-radius: 4px;
  border: 1px solid rgba(148, 163, 184, 0.9);
  background: #ffffff;
  box-sizing: border-box;
}

.checkbox-box.checked {
  border-color: $brand-primary;
  background: $brand-primary;
  position: relative;
}

.checkbox-box.checked::after {
  content: '';
  position: absolute;
  left: 4px;
  top: 1px;
  width: 4px;
  height: 8px;
  border: solid #fff;
  border-width: 0 2px 2px 0;
  transform: rotate(45deg);
}

.checkbox-box.indeterminate {
  border-color: $brand-primary;
  background: rgba($brand-primary, 0.10);
  position: relative;
}

.checkbox-box.indeterminate::after {
  content: '';
  position: absolute;
  left: 3px;
  top: 6px;
  width: 8px;
  height: 2px;
  background: $brand-primary;
  border-radius: 2px;
}

.batch-bar {
  padding: 10rpx 12rpx;
  border-top: 1rpx solid #e5e7eb;
  background-color: #ffffff;
  display: flex;
  flex-direction: column;
  gap: 8rpx;
}

.batch-info {
  font-size: 22rpx;
  color: #64748b;
}

.batch-actions {
  display: flex;
  gap: 8rpx;
  justify-content: center;
  flex-wrap: wrap;
}

.batch-btn {
  height: 52rpx;
  line-height: 52rpx;
  padding: 0 14rpx;
  font-size: 24rpx;
  border-radius: 10rpx;
  border: 1px solid #e5e7eb;
  background: #ffffff;
  color: #12344D;
}

.batch-btn:active {
  background: #f8f9fa;
}

.batch-btn-danger {
  border-color: rgba(220, 38, 38, 0.25);
  color: #dc2626;
}

.batch-btn-danger:active {
  background: rgba(220, 38, 38, 0.06);
}

.batch-btn-ghost {
  border-color: transparent;
  color: #64748b;
}

.tree-footer {
  padding: 10rpx 12rpx;
  border-top: 1rpx solid rgba($brand-border, 0.9);
  background-color: $bg;
  display: flex;
  flex-direction: column;
  gap: 8rpx;
}

.upload-status-footer {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 0 4px;
  margin-bottom: 4px;
}

.upload-status-text {
  display: flex;
  flex-direction: column;
}

.status-title {
  font-size: 10px;
  color: #666;
}

.status-detail {
  font-size: 12px;
  font-weight: bold;
  color: #333;
}

.footer-row {
  display: flex;
  gap: 8rpx;
  justify-content: center;
  flex-wrap: wrap;
}

.btn-upload {
  flex: 1;
  min-width: 0;
  height: 56rpx;
  font-size: 24rpx;
  padding: 0;
  line-height: 56rpx;
  border-radius: 10rpx;
  border: 1px solid transparent;
  transition: all 0.2s;
  background-color: $brand-primary;
  color: $uni-text-color-inverse;
}

.btn-upload:active {
  background-color: rgba($brand-primary, 0.92);
}

.btn-recycle-bin {
  flex: 0 0 56rpx;
  width: 56rpx;
  height: 56rpx;
  display: flex;
  align-items: center;
  justify-content: center;
  background-color: #f1f5f9;
  border-radius: 10rpx;
  cursor: pointer;
  font-size: 28rpx;
  transition: all 0.2s;
}

.btn-recycle-bin:hover {
  background-color: #e2e8f0;
}

@media (max-width: 420px) {
  .btn-new-folder,
  .btn-new-word {
    min-width: 46%;
  }
}

/* 桌面端窄屏：避免 rpx 随视口缩放导致按钮“忽大忽小/比例失衡” */
@media (max-width: 900px) {
  .btn-new-folder,
  .btn-new-word,
  .btn-upload {
    height: 28px;
    line-height: 28px;
    font-size: 12px;
    border-radius: 10px;
  }
  .tree-footer {
    padding: 8px 10px;
  }
  .tree-item-name {
    font-size: 12px;
  }

  .tree-expand-icon,
  .tree-expand-placeholder {
    width: 20px;
  }
  .tree-item-content {
    height: 32px;
    gap: 6px;
    padding-right: 68px;
  }
}

/* < 960px：直接压平缩进，避免层级 padding 把内容挤到“啥都看不见” */
@media (max-width: 960px) {
  .tree-item-content {
    padding-left: 10px !important;
  }
}

.upload-dialog {
  width: 600px; /* 桌面端固定宽度 */
  max-width: 90vw; /* 移动端响应式 */
  height: 70vh;
  max-height: 800px;
  background-color: #fff;
  border-radius: 12px;
  display: flex;
  flex-direction: column;
  box-shadow: 0 10px 30px rgba(0,0,0,0.2);
  /* 确保不被压缩 */
  flex-shrink: 0;
}

.folder-list {
  flex: 1;
  overflow-y: auto;
  padding: 16px;
  background-color: #f8f9fa;
}

.folder-body {
  padding: 24rpx 40rpx;
  max-height: 520rpx;
  overflow-y: auto;
  background-color: #ffffff;
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
  background-color: #e3f2fd;
  color: #0d6efd;
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

.folder-arrow {
  width: 32rpx;
  text-align: center;
  margin-right: 8rpx;
  color: #6b7280;
}

.folder-arrow-placeholder {
  width: 32rpx;
  margin-right: 8rpx;
}

.tree-line {
  color: #adb5bd;
  margin-right: 6px;
  font-family: monospace;
}

.empty-tip {
  text-align: center;
  color: #adb5bd;
  padding: 20px;
  font-size: 14px;
}

.folder-item:active {
  background-color: #e5e7eb;
}

.folder-item.active {
  background-color: #e0f2fe;
  border: 1px solid #12344D;
}

.folder-item.root-folder {
  font-weight: 600;
  background-color: #ffffff;
  border: 1px solid #12344D;
}

.folder-icon {
  font-size: 32rpx;
  margin-right: 12rpx;
  flex-shrink: 0;
}

.folder-name {
  flex: 1;
  font-size: 28rpx;
  color: #1f2430;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.folder-expand {
  font-size: 24rpx;
  color: #666;
  margin-left: 8rpx;
  width: 32rpx;
  text-align: center;
  flex-shrink: 0;
}

.folder-level-1 {
  background-color: #fafafa;
}

.folder-level-2 {
  background-color: #f5f5f5;
}

.folder-level-3 {
  background-color: #f0f0f0;
}

.tree-loading,
.tree-empty {
  padding: 40rpx;
  text-align: center;
  color: #9ca3af;
}

.tree-list {
  padding: 6rpx;
}

.tree-item {
  position: relative;
  padding: 4rpx 6rpx;
  border-radius: 8rpx;
  margin-bottom: 0;
  transition: background-color 0.18s ease, box-shadow 0.18s ease;
}

/* Tag Strip - Vertical color bar on left side of file items */
.tag-strip {
  position: absolute;
  left: 0;
  top: 4rpx;
  bottom: 4rpx;
  width: 4rpx;
  border-radius: 2rpx;
  z-index: 1;
}

/* Finder 风格：奇偶行浅色差（尽量克制） */
.tree-list .tree-item:nth-child(odd) {
  background-color: rgba(26, 83, 54, 0.02); /* Using Brand Color Tint */
}

.tree-list .tree-item:nth-child(even) {
  background-color: transparent;
}

.tree-item:hover {
  background-color: rgba(26, 83, 54, 0.05);
}

.tree-item:active {
  background-color: rgba(26, 83, 54, 0.08);
}

/* Increase specificity to override zebra striping (.tree-list .tree-item:nth-child) */
.tree-list .tree-item.tree-item-selected {
  background-color: #D1E7DD !important; /* Forest Green Lighter Tint - darkened for visibility */
}

/* Cmd/Ctrl 多选样式 */
.tree-list .tree-item.tree-item-multi-selected {
  background-color: #D1E7DD !important;
}

/* Removed vertical bar indicators to avoid conflict with Tag Strip */
.tree-item-multi-selected .tree-item-actions {
  opacity: 1;
  background: linear-gradient(to right, transparent 0%, #E8F3ED 30%, #E8F3ED 100%);
}

.tree-item-dragging {
  opacity: 0.5;
  transform: scale(0.95);
}

/* H5拖拽样式 */
/* .tree-item[draggable="true"] {
  cursor: move;
} */

/* 拖拽目标高亮 */
.tree-item-drop-target {
  outline: 2rpx dashed #2563eb;
  background-color: #eff6ff;
}

.tree-item[draggable="true"]:hover {
  background-color: #f0f0f0;
}

.tree-item-content {
  position: relative; /* 确保内容在进度条之上 */
  z-index: 1;
  display: flex;
  align-items: center;
  gap: 10rpx;
  height: 52rpx; /* 紧凑行高，接近 Finder */
  padding-right: 84rpx; /* 预留右侧操作按钮空间，避免把文件名挤没 */
}

/* 树引导线 - 竖线 */
.tree-guide-line {
  position: absolute;
  top: 0;
  bottom: 0;
  width: 1px;
  background-color: transparent;
  border-left: 1px dashed #e5e7eb;
  pointer-events: none;
}

/* 上传进度条样式 */
.upload-progress-bar {
  position: absolute;
  left: 0;
  top: 0;
  bottom: 0;
  background-color: rgba(64, 158, 255, 0.2);
  transition: width 0.3s ease;
  z-index: 0;
}

/* Upload Progress Styles */
.item-upload-progress-bg {
  position: absolute;
  left: 0;
  bottom: 0;
  height: 2px;
  background-color: #2563eb;
  opacity: 0.8;
  z-index: 1;
  transition: width 0.3s ease;
}
.text-muted {
  color: #999 !important;
  opacity: 0.8;
}

.tree-expand-icon-wrapper {
  width: 32rpx;
  height: 32rpx;
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
}

.tree-expand-icon-img {
  width: 16rpx;
  height: 16rpx;
}

.tree-expand-placeholder {
  width: 32rpx;
  height: 32rpx;
}

.tree-item-icon-img {
  width: 32rpx;
  height: 32rpx;
  margin-right: 8rpx;
  transition: transform 0.2s;
}

.tree-item-icon-img.is-opened {
  transform: scale(1.2);
}

.upload-status-footer-fixed{
  position: absolute;
  bottom: 0;
  left: 0;
  z-index: 10;
  width: 100%;
  box-sizing: border-box;
  padding: 12px 16px;
  border-top: 2px solid #2563eb;
  background: linear-gradient(to bottom, #f8fafc, #ffffff);
  box-shadow: 0 -2px 8px rgba(37, 99, 235, 0.1);
  background-color: #fff;
}




.tree-item-name {
  flex: 1;
  font-size: 26rpx;
  color: $uni-text-color;
  letter-spacing: 0.2px;
  display: block;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* 「被引用 N 次」角标（dev-board#107 单元 F3） */
.tree-item-ref-count {
  font-size: 20rpx;
  color: #999999;
  margin-left: 12rpx;
}

/* 窗口化「展开更多」占位行（dev-board#107 单元 F3） */
.tree-item-load-more {
  cursor: pointer;
}

.tree-item-load-more-text {
  flex: 1;
  font-size: 24rpx;
  color: #1a5336;
}

.rename-input-wrapper {
  flex: 1;
  margin-right: 8rpx;
}

.rename-input {
  width: 100%;
  height: 44rpx;
  font-size: 26rpx;
  padding: 0 8rpx;
  background: #ffffff;
  border: 1px solid #2563eb;
  border-radius: 4rpx;
}

.tree-item-actions {
  position: absolute;
  right: 0;
  top: 0;
  bottom: 0;
  display: flex;
  align-items: center;
  gap: 8rpx;
  padding-left: 48rpx; /* Width of gradient fade area */
  padding-right: 12rpx; /* Spacing from right edge */
  opacity: 0;
  transition: opacity 0.2s;

  /* Prevent pointer events on the gradient part so clicks go through if empty,
     but here we want it to block text interaction, so auto is fine.
     The mask ensures actions are clickable. */
}

.tree-item:hover .tree-item-actions {
  opacity: 1;
  /* Hover Background: matches rgba(18, 52, 77, 0.05) on white -> #F5F7FA */
  background: linear-gradient(to right, transparent 0%, #F5F7FA 30%, #F5F7FA 100%);
}

.tree-item-selected .tree-item-actions {
  opacity: 1;
  /* Selected Background: matches rgba(18, 52, 77, 0.08) on white -> #EFF4F8 */
  background: linear-gradient(to right, transparent 0%, #EFF4F8 30%, #EFF4F8 100%);
}

.action-btn {
  font-size: 22rpx;
  color: #64748b;
  padding: 0 4rpx;
}

.recycle-back-btn {
  color: #3498DB !important;
  font-size: 12px;
  cursor: pointer;
  padding: 0 12rpx;
  transition: all 0.2s;
  font-weight: 500;
}

.recycle-back-btn:hover {
  color: #2980B9 !important;
  text-decoration: underline;
}

.action-btn:active {
  opacity: 0.7;
}

.action-btn.icon-btn {
  width: 28rpx;
  height: 28rpx;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border-radius: 4rpx;
  background: transparent;
  border: none;
  transition: color 0.2s;
}

.action-btn.icon-btn:hover {
  background-color: transparent;
  color: #111827;
  opacity: 0.8;
}

.action-icon {
  width: 24rpx;
  height: 24rpx;
  display: block;
}

/* 行内上传进度条 */
.upload-progress-inline {
  display: none;
}

.upload-progress-inline-bar {
  display: none;
}

.upload-progress-inline-text {
  display: none;
}

/* 全局上传进度条 */
.global-upload {
  padding: 8rpx 16rpx;
  border-top: 1rpx solid #e5e7eb;
  background-color: #f9fafb;
}

.global-upload-bar-wrapper {
  position: relative;
  height: 8rpx;
  border-radius: 999px;
  background-color: #e5e7eb;
  overflow: hidden;
}

.global-upload-bar {
  position: absolute;
  left: 0;
  top: 0;
  bottom: 0;
  background: linear-gradient(90deg, #3b82f6, #2563eb);
  transition: width 0.2s ease-out;
}

.global-upload-text {
  margin-top: 4rpx;
  font-size: 20rpx;
  color: #6b7280;
}

/* 上传对话框重构样式 */
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

.upload-modal {
  width: 640rpx;
  max-width: 92vw;
  background-color: #ffffff;
  border-radius: 16rpx;
  box-shadow: 0 16rpx 40rpx rgba(0, 0, 0, 0.08);
  display: flex;
  flex-direction: column;
}

.folder-modal {
  width: 580rpx;
  max-width: 88vw;
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

.upload-body {
  padding: 24rpx 40rpx 8rpx;
  display: flex;
  flex-direction: column;
  gap: 24rpx;
}

.upload-row {
  display: flex;
  flex-direction: column;
  gap: 8rpx;
}

.upload-label {
  font-size: 26rpx;
  color: #1A1A1A;
}

.upload-field {
  background-color: #F7F5F0;
  border-radius: 12rpx;
  border: 1rpx solid #E0E0E0;
  padding: 20rpx 24rpx;
  display: flex;
  align-items: center;
}

.upload-field-clickable:hover {
  background-color: #EFE9DD;
  border-color: #C8A45D;
}

.upload-folder-icon {
  margin-right: 12rpx;
  font-size: 28rpx;
}

.upload-field-text {
  font-size: 26rpx;
  color: #1A1A1A;
}

.upload-placeholder {
  color: #999999;
}

.upload-selected-list {
  display: flex;
  flex-direction: column;
  gap: 6rpx;
  max-height: 300rpx;
  overflow-y: auto;
}

.upload-selected-item {
  font-size: 24rpx;
  color: #333333;
}

.upload-footer {
  padding: 16rpx 40rpx 24rpx;
  border-top: 1rpx solid #E0E0E0;
  background-color: #FAFAFA;
  display: flex;
  justify-content: flex-end;
  gap: 16rpx;
}

.upload-btn {
  min-width: 160rpx;
  padding: 16rpx 32rpx;
  text-align: center;
  border-radius: 999rpx;
  font-size: 26rpx;
}

.upload-btn-secondary {
  background-color: #ffffff;
  color: #12344D;
  border: 1rpx solid #12344D;
}

.upload-btn-primary {
  background-color: #12344D;
  color: #ffffff;
}

.upload-btn-primary.upload-btn-disabled {
  background-color: #CBD5E1;
  color: #ffffff;
}

.dialog-overlay {
  position: fixed;
  top: 0;
  left: 0;
  right: 0;
  bottom: 0;
  background-color: rgba(0, 0, 0, 0.5);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 1000;
}

.dialog-content {
  background-color: #ffffff;
  border-radius: 16rpx;
  overflow: hidden;
}

.dialog-header {
  padding: 32rpx;
  border-bottom: 1rpx solid #e5e7eb;
}

.dialog-title {
  font-size: 32rpx;
  font-weight: 500;
  color: #1f2430;
}

.dialog-body {
  padding: 32rpx;
}

.dialog-input {
  width: 100%;
  height: 80rpx;
  padding: 0 16rpx;
  border: 1rpx solid #e5e7eb;
  border-radius: 8rpx;
  font-size: 28rpx;
}

.dialog-footer {
  display: flex;
  border-top: 1rpx solid #e5e7eb;
}

.dialog-btn {
  flex: 1;
  height: 88rpx;
  border-radius: 0;
  font-size: 28rpx;
}

.dialog-btn-default {
  background-color: #ffffff;
  color: #1a1a1a;
}

.dialog-btn-primary {
  background-color: #12344D; /* 品牌深墨蓝 */
  color: #ffffff;
}

.dialog-btn-primary[disabled] {
  opacity: 0.5;
}

.dialog-btn:first-child {
  border-right: 1rpx solid #e5e7eb;
}

/* Sort Menu Styles */
.sort-menu-mask {
  position: absolute;
  top: 0;
  left: 0;
  right: 0;
  bottom: 0;
  z-index: 99;
}
.sort-menu {
  position: absolute;
  top: 8px;
  right: 8px;
  width: 120px;
  background: #fff;
  border: 1px solid #e5e7eb;
  border-radius: 6px;
  box-shadow: 0 4px 12px rgba(0,0,0,0.1);
  padding: 4px 0;
  z-index: 100;
}
.sort-item {
  padding: 8px 12px;
  font-size: 13px;
  color: #333;
  display: flex;
  justify-content: space-between;
  cursor: pointer;
}
.sort-item:hover {
  background-color: #f3f4f6;
}
.sort-item.active {
  color: #2563eb;
  font-weight: 500;
}

.header-row {
  display: flex;
  align-items: center;
  width: 100%;
}

.new-folder-btn {
  display: flex;
  align-items: center;
  padding: 4px 12px;
  background-color: #F3F4F6;
  border: 1px solid #E5E7EB;
  border-radius: 6px;
  cursor: pointer;
  font-size: 13px;
  color: #374151;
  transition: all 0.2s;
  margin-left: auto;
}
.new-folder-btn:hover {
  background-color: #E5E7EB;
  color: #111827;
}
.new-folder-btn .btn-plus {
  font-size: 18px;
  margin-right: 4px;
  font-weight: 300;
  line-height: 1;
}

/* AI WORKDECK Dialog Styles */
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
  color: #6C757D; /* Gray Medium */
  line-height: 1.5;
  display: block;
}

.awd-dialog-body {
  padding: 0 24px 24px;
  flex: 1;
  min-height: 0; /* Allow scrolling */
}

.scrollable-body {
  max-height: 400px; /* Taller */
  overflow-y: auto;
}

.awd-dialog-text {
  font-size: 15px;
  color: #2C3338;
  line-height: 1.6;
}

.awd-input {
  width: 100%;
  height: 48px;
  padding: 0 16px;
  border: 1px solid #E9ECEF;
  border-radius: 8px;
  font-size: 15px;
  color: #111827;
  transition: all 0.2s;
}

.awd-input:focus {
  border-color: #5BD197; /* Mint Green */
  box-shadow: 0 0 0 3px rgba(91, 209, 151, 0.15);
}

.awd-dialog-footer {
  display: flex;
  align-items: center;
  justify-content: center; /* Centered as requested */
  gap: 16px;
  padding: 24px;
  background-color: transparent;
  flex-shrink: 0;
}

/* AI WorkDeck Buttons */
.awd-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  height: 44px; /* Slightly taller */
  padding: 0 32px; /* Wider padding */
  font-size: 15px;
  font-weight: 500;
  border-radius: 8px;
  cursor: pointer;
  transition: all 0.2s;
  min-width: 100px;
}

.awd-btn:active {
  transform: translateY(1px);
}

/* Primary is now Forest Green to match AI WorkDeck */
.awd-btn-primary {
  background-color: #1A5336; /* Forest Green */
  color: #ffffff;
  border: 1px solid transparent;
}
.awd-btn-primary:active {
  background-color: #123A26;
}

/* Forest variant (redundant now but kept for compatibility) */
.awd-btn-forest {
  background-color: #1A5336;
  color: #ffffff;
}

/* New Danger Button for Delete */
.awd-btn-danger {
  background-color: #DC3545;
  color: #ffffff;
  border: 1px solid transparent;
}
.awd-btn-danger:active {
  background-color: #BB2D3B;
}

.awd-btn-secondary {
  background-color: #ffffff;
  color: #2C3338;
  border: 1px solid #E9ECEF;
}
.awd-btn-secondary:active, .awd-btn-secondary:hover {
  background-color: #F8F9FA;
  border-color: #DDE2E5;
}

.awd-btn.disabled {
  opacity: 0.5;
  pointer-events: none;
}

/* Form & Fields */
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
}
.awd-field.clickable {
  cursor: pointer;
}
.awd-field.clickable:hover {
  background-color: #E6F9F0; /* Mint tint */
  border-color: #5BD197;
}

.field-icon {
  margin-right: 12px;
  font-size: 18px;
}
.field-icon-img {
  width: 20px;
  height: 20px;
  margin-right: 12px;
}
.field-value {
  font-size: 14px;
  color: #111827;
}
.field-placeholder {
  font-size: 14px;
  color: #9CA3AF;
}
.field-content-row {
  display: flex;
  flex-direction: column;
}
.field-desc {
  font-size: 12px;
  color: #6B7280;
  margin-top: 2px;
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
.awd-dialog .tree-expand-icon-wrapper {
  width: 24px;
  height: 24px;
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
}
.awd-dialog .tree-expand-icon-img {
  width: 10px;
  height: 10px;
}
.awd-dialog .tree-expand-placeholder {
  width: 24px;
}
.folder-icon-img {
  width: 18px;
  height: 18px;
  transition: transform 0.2s;
}
.folder-icon-img.is-opened {
  transform: scale(1.2);
}
.folder-name {
  margin-left: 8px;
  font-size: 14px;
}

.rename-input-wrapper.dialog-rename {
  margin-left: 8px;
  flex: 1;
}

.rename-input-wrapper.dialog-rename .rename-input {
  width: 100%;
  height: 28px;
  padding: 0 8px;
  border: 1px solid #5BD197;
  border-radius: 4px;
  background-color: #ffffff;
  font-size: 14px;
}
.empty-tip {
  padding: 16px;
  text-align: center;
  color: #9CA3AF;
  font-size: 14px;
}



.recycle-glyph {
  width: 13px;
  height: 13px;
  flex-shrink: 0;
  color: #1A5336;
}
</style>

