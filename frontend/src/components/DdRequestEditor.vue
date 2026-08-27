<template>
  <view class="dd-request-editor">
    <!-- Header -->
    <view class="editor-header">
      <view class="header-left">
        <!-- Title Input -->
        <input
          class="title-edit"
          v-model="requestName"
          @blur="updateRequestName"
          @confirm="updateRequestName"
          :disabled="deleted"
          :placeholder="request ? request.name : $t('panels.ddLoadingPlaceholder')"
        />
        <view class="status-badge" v-if="request" :class="request.status">
           {{ getStatusText(request.status) }}
        </view>
        <text class="progress-info" v-if="items.length > 0">{{ $t('panels.ddProgress', { completed: completedCount, total: items.length }) }}</text>
      </view>

      <view style="display: flex; gap: 10px; align-items: center;" v-if="!deleted">
        <button class="delete-list-btn" @tap="handleDeleteRequest">{{ $t('panels.ddDeleteList') }}</button>
        <button class="new-btn" @tap="handleAddItem">
            <text>{{ $t('panels.ddNewItem') }}</text>
        </button>
      </view>
    </view>

    <!-- Table -->
    <view class="table-container">
      <view class="table-header">
        <view class="col-name">{{ $t('panels.ddColName') }}</view>
        <view class="col-desc">{{ $t('panels.ddColDesc') }}</view>
        <view class="col-example">{{ $t('panels.ddColExample') }}</view>
        <view class="col-upload">{{ $t('panels.ddColUpload') }}</view>
        <view class="col-qa">{{ $t('panels.ddColQa') }}</view>
        <view class="col-action"></view>
      </view>

      <scroll-view scroll-y class="items-list">
        <view
          v-for="item in flattenedItems"
          :key="item.id"
          class="table-row"
          :class="{ selected: item.id === selectedItemId }"
          @tap="selectItem(item)"
          @mouseenter="hoveredItemId = item.id"
          @mouseleave="hoveredItemId = null"
        >
          <!-- File Name (Tree Column) -->
          <!--
             Padding logic:
             - Base indent: 20px
             - Per level: 24px
             - Arrow space: 24px (positioned relatively)
          -->
          <view class="col-name" :style="{ paddingLeft: (item.level * 24 + 10) + 'px' }">
             <view class="tree-controls-wrapper">
                <!-- Arrows for Indent/Outdent (Hover Only) -->
                <!-- Positioned specifically to not overlap the triangle -->
                <view class="indent-controls" v-if="hoveredItemId === item.id">
                  <view class="arrow-btn" @tap.stop="handleOutdent(item)" :title="$t('panels.ddOutdentTitle')">‹</view>
                  <view class="arrow-btn" @tap.stop="handleIndent(item)" :title="$t('panels.ddIndentTitle')">›</view>
                </view>
                <view class="indent-placeholder" v-else></view>

                <!-- Expand Toggle -->
                <view
                  class="expand-icon"
                  @tap.stop="toggleExpand(item)"
                  v-if="hasChildren(item)"
                >
                  <text>{{ isExpanded(item) ? '▼' : '▶' }}</text>
                </view>
                <view class="expand-placeholder" v-else></view>
             </view>

            <input
              class="silent-input title-input"
              v-model="item.title"
              @blur="updateInfo(item)"
              :placeholder="$t('panels.ddNamePlaceholder')"
            />
          </view>

          <!-- Description -->
          <view class="col-desc">
            <input
              class="silent-input"
              v-model="item.description"
              @blur="updateInfo(item)"
              :placeholder="$t('panels.ddDescPlaceholder')"
            />
          </view>

          <!-- Example -->
          <view class="col-example">
            <text class="link-text" v-if="item.exampleFileId">{{ $t('panels.ddView') }}</text>
          </view>

          <!-- Upload -->
          <view class="col-upload">
            <view v-if="item.uploadedFileId" class="uploaded-info" @tap.stop="viewFile(item.uploadedFileId)">
               <svg class="file-icon" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
                 <path v-for="(d, gi) in ICONS.doc" :key="gi" :d="d" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round" />
               </svg>
               <text class="file-name">{{ $t('panels.ddUploaded') }}</text>
            </view>
             <button
              class="mini-btn upload"
              v-else-if="!isApproved(item.status)"
              @tap.stop="chooseFile(item)"
            >
              {{ $t('panels.ddUpload') }}
            </button>
             <view class="status-tag" :class="item.status.toLowerCase()" v-if="item.status !== 'PENDING' && !item.uploadedFileId">
               {{ getItemStatusText(item.status) }}
             </view>
          </view>

          <!-- QA/Comments -->
          <view class="col-qa">
             <view class="comment-trigger" @tap.stop="toggleComments(item)">
                <text>{{ $t('panels.ddComment') }}</text>
                <view class="dot" v-if="item.comments && item.comments.length > 0"></view>
             </view>
          </view>

          <!-- Delete Action -->
          <view class="col-action">
              <view class="delete-btn" @tap.stop="handleDeleteItem(item)" :title="$t('panels.ddDeleteTitle')">
                   <text>×</text>
              </view>
          </view>
        </view>

        <view v-if="items.length === 0" class="empty-state">
          <text>{{ $t('panels.ddEmptyState') }}</text>
        </view>
        <!-- Bottom padding for scrolling -->
        <view style="height: 100px;"></view>
      </scroll-view>
    </view>

    <!-- Comment Drawer (Simplified) -->
    <view v-if="showCommentsDrawer" class="drawer-mask" @tap="showCommentsDrawer = false">
        <view class="drawer" @tap.stop>
            <view class="drawer-header">{{ $t('panels.ddCommentBoardTitle') }}</view>
            <view class="drawer-body">
                <view v-for="c in activeItemComments" :key="c.id" class="comment-row">
                    <text class="user">{{c.userId}}:</text> <text>{{c.content}}</text>
                </view>
                <view v-if="activeItemComments.length === 0" class="no-data">{{ $t('panels.ddNoComments') }}</view>
            </view>
            <view class="drawer-footer">
                <input v-model="newCommentText" :placeholder="$t('panels.ddCommentPlaceholder')" @confirm="sendComment" />
                <button @tap="sendComment">{{ $t('panels.ddSend') }}</button>
            </view>
        </view>
    </view>
  </view>
</template>

<script>
import api, { getApiBaseUrl } from '@/services/api'
import { getSessionId } from '@/utils/auth'
import { ICONS } from '@/config/icons.js'

export default {
  name: 'DdRequestEditor',
  props: {
    requestId: {
      type: [Number, String],
      required: true
    }
  },
  data() {
    return {
      request: null,
      requestName: '',
      items: [],
      isLawyer: true,
      selectedItemId: null,
      expandedItems: new Set(),
      hoveredItemId: null,
      // 本组件在工作台里没有 :key，切换不同尽调清单标签时是同一个实例被复用，
      // 只靠 requestId watcher 再发一次请求。响应可能乱序回来，用递增序号
      // 只认最后一次发出的那次结果；删除清单时也把序号推进一格作废在途请求。
      fetchSeq: 0,
      deleted: false,

      // Comments
      showCommentsDrawer: false,
      activeItem: null,
      activeItemComments: [],
      newCommentText: ''
    }
  },
  computed: {
    ICONS() { return ICONS },
    completedCount() {
      return this.items.filter(i => i.status === 'APPROVED' || i.status === 'UPLOADED').length
    },
    flattenedItems() {
      if (!this.items.length) return []

      const rootItems = []
      const itemMap = new Map()
      const rawItems = JSON.parse(JSON.stringify(this.items))

      rawItems.forEach(item => {
        item.children = []
        itemMap.set(item.id, item)
      })

      rawItems.sort((a, b) => a.sortOrder - b.sortOrder)

      rawItems.forEach(item => {
        if (item.parentId && itemMap.has(item.parentId)) {
          itemMap.get(item.parentId).children.push(item)
        } else {
          rootItems.push(item)
        }
      })

      const result = []
      const traverse = (nodes) => {
        nodes.forEach(node => {
          result.push(node)
          if (this.expandedItems.has(node.id) && node.children.length > 0) {
            traverse(node.children)
          }
        })
      }

      traverse(rootItems)
      return result
    }
  },
  mounted() {
    this.fetchData()
  },
  watch: {
    requestId: {
      handler() {
        this.fetchData()
      },
      immediate: false
    }
  },
  methods: {
    async fetchData() {
      const seq = ++this.fetchSeq
      try {
        const res = await api.getDdRequestDetails(this.requestId)
        if (seq !== this.fetchSeq) return
        this.request = res.request
        this.requestName = this.request.name
        this.items = res.items
      } catch (e) {
        if (seq !== this.fetchSeq) return
        console.error('Fetch DD details failed', e)
      }
    },

    async updateRequestName() {
        if (!this.request) return
        if (!this.requestName || this.requestName === this.request.name) return
        try {
            await api.updateDdRequest(this.requestId, this.requestName)
            this.request.name = this.requestName
            uni.showToast({ title: this.$t('panels.ddRenamed'), icon: 'success' })
            // Would be nice to emit event to refresh sidebar
            // this.$emit('refresh')
        } catch (e) {
            console.error(e)
            this.requestName = this.request.name // Revert
        }
    },

    selectItem(item) {
      if (this.selectedItemId === item.id) {
        this.selectedItemId = null
      } else {
        this.selectedItemId = item.id
      }
    },

    isExpanded(item) {
      return this.expandedItems.has(item.id)
    },

    hasChildren(item) {
       return this.items.some(i => i.parentId === item.id)
    },

    toggleExpand(item) {
      if (this.expandedItems.has(item.id)) {
        this.expandedItems.delete(item.id)
      } else {
        this.expandedItems.add(item.id)
      }
      this.$forceUpdate()
    },

    async handleAddItem() {
      let parentId = this.selectedItemId || null
      if (this.selectedItemId) {
          this.expandedItems.add(this.selectedItemId)
      }
      try {
        await api.addDdItem(this.requestId, parentId)
        await this.fetchData()
        uni.showToast({ title: this.$t('panels.ddCreated'), icon: 'none' })
      } catch (e) {
        console.error(e)
        uni.showToast({ title: this.$t('panels.ddCreateFailed'), icon: 'none' })
      }
    },

    async handleIndent(item) {
      const flat = this.flattenedItems
      const idx = flat.findIndex(i => i.id === item.id)
      if (idx <= 0) return

      const prev = flat[idx - 1]
      if (prev.id === item.parentId) return

      await this.moveItem(item.id, prev.id)
    },

    async handleOutdent(item) {
       if (!item.parentId) return
       const currentParent = this.items.find(i => i.id === item.parentId)
       const newParentId = currentParent ? currentParent.parentId : null
       await this.moveItem(item.id, newParentId)
    },

    async moveItem(itemId, newParentId) {
      try {
        await api.moveDdItem(itemId, newParentId)
        await this.fetchData()
        if (newParentId) this.expandedItems.add(newParentId)
      } catch (e) {
        console.error(e)
        uni.showToast({ title: this.$t('panels.ddOperationFailed'), icon: 'none' })
      }
    },

    async updateInfo(item) {
      try {
        await api.updateDdItemInfo(item.id, item.title, item.description)
      } catch (e) { console.error(e) }
    },

    getStatusText(s) {
      return s === 'PUBLISHED' ? this.$t('panels.ddStatusPublished') : (s === 'DRAFT' ? this.$t('panels.ddStatusDraft') : s)
    },
    getItemStatusText(s) {
      const map = {
        'PENDING': this.$t('panels.ddItemPending'),
        'UPLOADED': this.$t('panels.ddItemUploaded'),
        'APPROVED': this.$t('panels.ddItemApproved'),
        'REJECTED': this.$t('panels.ddItemRejected')
      }
      return map[s] || s
    },
    isApproved(s) { return s === 'APPROVED' },

    async chooseFile(item) {
      uni.chooseFile({
        count: 1,
        success: (res) => {
          this.uploadFile(item, res.tempFiles[0])
        }
      })
    },
    async uploadFile(item, file) {
      const uploadUrl = `${getApiBaseUrl()}/api/dd/items/${item.id}/upload`
      uni.showLoading({ title: this.$t('panels.ddUploading') })
      uni.uploadFile({
        url: uploadUrl,
        filePath: file.path,
        file: file,
        name: 'file',
        header: { 'X-Session-Id': getSessionId() },
        success: (res) => {
          uni.hideLoading()
          if (res.statusCode === 200) {
            uni.showToast({ title: this.$t('panels.ddUploadSuccess') })
            this.fetchData()
          } else {
            uni.showToast({ title: this.$t('panels.ddUploadFail'), icon: 'none' })
          }
        },
        fail: () => { uni.hideLoading(); uni.showToast({ title: this.$t('panels.ddNetworkError'), icon: 'none' }) }
      })
    },
    viewFile(fileId) {
       const url = `${getApiBaseUrl()}/api/files/${fileId}/download?token=${encodeURIComponent(getSessionId())}`
       window.open(url, '_blank')
    },

    async toggleComments(item) {
        this.activeItem = item
        this.showCommentsDrawer = true
        this.activeItemComments = []
        const res = await api.getDdItemComments(item.id)
        this.activeItemComments = res
    },
    async sendComment() {
        if (!this.newCommentText || !this.activeItem) return
        try {
            await api.addDdItemComment(this.activeItem.id, this.newCommentText)
            this.newCommentText = ''
            const res = await api.getDdItemComments(this.activeItem.id)
            this.activeItemComments = res
            this.activeItem.comments = res
        } catch(e) { console.error(e) }
    },

    handleDeleteItem(item) {
        uni.showModal({
            title: this.$t('panels.ddConfirmDeleteTitle'),
            content: this.$t('panels.ddDeleteItemConfirmBody'),
            success: async (res) => {
                if (res.confirm) {
                    try {
                        await api.deleteDdItem(item.id)
                        this.fetchData()
                        uni.showToast({title: this.$t('panels.ddDeleted'), icon: 'none'})
                    } catch (e) {
                         uni.showToast({title: this.$t('panels.ddDeleteFailed'), icon: 'none'})
                         console.error(e)
                    }
                }
            }
        })
    },

    handleDeleteRequest() {
        uni.showModal({
            title: this.$t('panels.ddConfirmDeleteTitle'),
            content: this.$t('panels.ddDeleteRequestConfirmBody'),
            confirmColor: '#DC3545',
            success: async (res) => {
                if (res.confirm) {
                    try {
                        await api.deleteDdRequest(this.requestId)
                        uni.showToast({title: this.$t('panels.ddDeleted'), icon: 'success'})
                        // 父组件（工作台）没有接 @deleted，标签不会自动关；
                        // 这里先把本地状态清空，免得面板继续渲染已删清单的行、
                        // 用户接着编辑又拿已不存在的 id 去打接口。
                        this.deleted = true
                        this.fetchSeq++
                        this.request = null
                        this.requestName = ''
                        this.items = []
                        this.selectedItemId = null
                        this.showCommentsDrawer = false
                        // Emit event to close editor or refresh list
                        this.$emit('deleted')
                    } catch(e) {
                         uni.showToast({title: this.$t('panels.ddDeleteFailed'), icon: 'none'})
                         console.error(e)
                    }
                }
            }
        })
    }
  }
}
</script>

<style lang="scss" scoped>

.dd-request-editor {
  display: flex;
  flex-direction: column;
  height: 100%;
  background-color: var(--awd-surface);
  font-family: Inter, -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;

  .editor-header {
    display: flex;
    justify-content: space-between;
    align-items: center;
    padding: 16px 24px;
    border-bottom: 1px solid var(--awd-border);

    .header-left {
      display: flex;
      align-items: center;
      gap: 12px;

      .title-edit {
        font-size: 18px;
        font-weight: 600;
        color: var(--awd-accent-text);
        border: 1px solid transparent;
        border-radius: 4px;
        padding: 4px 8px;
        width: 200px;

        &:hover { border-color: var(--awd-border); }
        &:focus { border-color: var(--awd-mint); outline: none; background: var(--awd-surface); }
      }

      .status-badge {
        font-size: 12px;
        padding: 2px 8px;
        background: var(--awd-surface-3);
        color: var(--awd-text);
        border-radius: 4px;
      }

      .progress-info {
        font-size: 12px;
        color: var(--awd-text-3);
        margin-left: 10px;
      }
    }

    .new-btn {
      background-color: var(--awd-accent);
      color: var(--awd-text-on-accent);
      font-size: 14px;
      padding: 6px 16px;
      border-radius: 4px;
      border: none;
      cursor: pointer;
      line-height: 1.5;
      transition: background-color 0.2s;

      &:hover { background-color: var(--awd-accent-hover); }
    }

    .delete-list-btn {
        margin-left: 10px;
        background: transparent;
        color: var(--awd-text-3);
        border: 1px solid var(--awd-border);
        padding: 6px 12px;
        // height: 20px;
        // box-sizing: border-box;
        line-height: 1.5;
        border-radius: 4px;
        cursor: pointer;
        font-size: 13px;
        &:hover { color: var(--awd-danger-text); border-color: var(--awd-danger); background: var(--awd-surface); }
    }
  }

  .table-container {
    flex: 1;
    display: flex;
    flex-direction: column;
    overflow: hidden;

    .table-header {
      display: flex;
      padding: 10px 0;
      background: var(--awd-bg);
      border-bottom: 1px solid var(--awd-border);
      font-size: 12px;
      font-weight: 600;
      color: var(--awd-text-2);

      .col-name { width: 35%; padding-left: 20px; }
      .col-desc { flex: 1; }
      .col-example { width: 60px; text-align: center; }
      .col-upload { width: 100px; text-align: center; }
      .col-qa { width: 60px; text-align: center; }
      .col-action { width: 40px; text-align: center; }
    }

    .items-list {
      flex: 1;
    }

    .table-row {
      display: flex;
      align-items: center;
      padding: 8px 0;
      border-bottom: 1px solid var(--awd-border);
      font-size: 13px;
      cursor: pointer;
      transition: background-color 0.1s;

      &:hover {
          background-color: var(--awd-bg);
          .col-action .delete-btn { opacity: 1; }
      }
      &.selected { background-color: var(--awd-accent-soft); }

      .col-name {
        width: 35%;
        display: flex;
        align-items: center;
        padding-right: 10px;
      }
      .col-desc { flex: 1; padding-right: 10px; }
      .col-example { width: 60px; text-align: center; }
      .col-upload { width: 100px; display: flex; justify-content: center; }
      .col-qa { width: 60px; display: flex; justify-content: center; }
      .col-action {
          width: 40px;
          display: flex;
          justify-content: center;

          .delete-btn {
              opacity: 0;
              color: var(--awd-text-3);
              cursor: pointer;
              font-size: 14px;
              transition: opacity 0.2s;
              &:hover { color: var(--awd-danger-text); }
          }
      }

      /* Tree Indentation & Controls */
      .tree-controls-wrapper {
          display: flex;
          align-items: center;
          /* Fixed width container for controls to prevent shifting */
          width: 50px;
          flex-shrink: 0;
          margin-right: 4px;
      }

      .indent-controls {
        display: flex;
        gap: 2px;
        margin-right: 4px;

        .arrow-btn {
          width: 14px;
          height: 18px;
          background: var(--awd-surface);
          border: 1px solid var(--awd-border);
          display: flex;
          align-items: center;
          justify-content: center;
          font-size: 12px;
          color: var(--awd-text-2);
          cursor: pointer;
          border-radius: 2px;
          &:hover { color: var(--awd-mint); border-color: var(--awd-mint); }
        }
      }

      .indent-placeholder { width: 32px; margin-right: 4px; /* Matches 2 arrows of 14px + gap */ }

      .expand-icon {
        width: 16px;
        font-size: 10px;
        color: var(--awd-text-3);
        cursor: pointer;
        text-align: center;
      }
      .expand-placeholder { width: 16px; }

      .silent-input {
        flex: 1;
        border: 1px solid transparent;
        background: transparent;
        padding: 4px;
        border-radius: 4px;
        font-size: 13px;
        color: var(--awd-text);
        min-width: 0; /* Allow shrinking */

        &:focus { background: var(--awd-surface); border-color: var(--awd-mint); outline: none; }
      }
      .title-input { font-weight: 500; }

      .link-text { color: var(--awd-info-text); cursor: pointer; &:hover { text-decoration: underline; } }

      .mini-btn {
        padding: 3px 10px;
        font-size: 12px;
        border-radius: 4px;
        border: 1px solid var(--awd-border);
        background: var(--awd-surface);
        cursor: pointer;
        color: var(--awd-text);

        &:hover { border-color: var(--awd-mint); color: var(--awd-mint); }
      }

      .uploaded-info {
         display: flex;
         align-items: center;
         gap: 4px;
         background: var(--awd-accent-soft);
         border: 1px solid var(--awd-border);
         padding: 2px 6px;
         border-radius: 4px;
         cursor: pointer;
         max-width: 90px;

         .file-icon {
  width: 13px;
  height: 13px;
  flex-shrink: 0; }
         .file-name { font-size: 11px; color: var(--awd-accent-text); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
      }

      .comment-trigger {
        font-size: 12px;
        color: var(--awd-text-2);
        cursor: pointer;
        position: relative;
        &:hover { color: var(--awd-mint); }

        .dot {
          position: absolute;
          top: -2px;
          right: -4px;
          width: 6px;
          height: 6px;
          background: red;
          border-radius: 50%;
        }
      }
    }
  }
}

.drawer-mask {
    position: fixed; inset: 0; background: var(--awd-overlay); z-index: 1000;
    display: flex; justify-content: flex-end;

    .drawer {
        width: 300px;
        background: var(--awd-surface);
        height: 100%;
        display: flex;
        flex-direction: column;
        box-shadow: -2px 0 8px rgba(0,0,0,0.1);

        .drawer-header { padding: 15px; font-weight: bold; border-bottom: 1px solid var(--awd-border); }
        .drawer-body { flex: 1; padding: 15px; overflow-y: auto;
            .comment-row { margin-bottom: 10px; font-size: 13px; .user{font-weight:bold; margin-right:5px;} }
            .no-data { text-align: center; color: var(--awd-text-3); margin-top: 20px; }
        }
        .drawer-footer {
            padding: 10px; border-top: 1px solid var(--awd-border); display: flex; gap: 5px;
            input { flex: 1; border: 1px solid var(--awd-border); padding: 6px; border-radius: 4px; }
            button { background: var(--awd-mint); border: none; color: white; padding: 0 12px; border-radius: 4px; font-size: 12px; }
        }
    }
}
</style>
