<template>
  <view class="search-panel">
    <!-- Header Area -->
    <view class="search-header">
      <!-- <text class="panel-title">搜索</text> -->

      <!-- Search Input -->
      <view class="input-wrapper">
        <view class="input-box" :class="{ focused: isSearchFocused }">
          <view class="search-icon">
            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <circle cx="11" cy="11" r="8"></circle>
              <line x1="21" y1="21" x2="16.65" y2="16.65"></line>
            </svg>
          </view>
          <input
            class="search-input"
            v-model="searchQuery"
            placeholder="搜索文件或内容..."
            @focus="isSearchFocused = true"
            @blur="isSearchFocused = false"
            @confirm="performSearch"
            @input="onSearchInput"
          />
          <view v-if="searchQuery" class="clear-icon" @tap="searchQuery = ''; performSearch()">
             <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                <line x1="18" y1="6" x2="6" y2="18"></line>
                <line x1="6" y1="6" x2="18" y2="18"></line>
             </svg>
          </view>
        </view>
      </view>

      <!-- Tag Filters -->
      <view class="section-label" v-if="visibleTags && visibleTags.length > 0">按标签筛选</view>
      <view class="tags-container" v-if="visibleTags && visibleTags.length > 0">
        <view
          v-for="tag in visibleTags"
          :key="tag.id"
          class="tag-chip"
          :class="{ selected: selectedTagIds.includes(tag.id) }"
          :style="getTagStyle(tag)"
          @tap="toggleTag(tag.id)"
        >
          <text class="tag-name">{{ tag.name }}</text>
        </view>
      </view>

      <!-- Search Stats -->
      <view class="search-stats" v-if="hasSearched">
         <text v-if="loading">正在搜索...</text>
         <text v-else-if="results.totalMatches === 0 && (!results.results || results.results.length === 0)">未找到结果</text>
         <template v-else>
            <text class="highlight">{{ results.totalMatches }}</text> 个匹配 ·
            <text class="highlight">{{ results.totalFiles }}</text> 个文件
         </template>
      </view>
    </view>

    <!-- Results List -->
    <scroll-view scroll-y class="results-list" v-if="!loading && results.results && results.results.length > 0">
      <view class="file-group" v-for="file in results.results" :key="file.fileId">
        <!-- File Header -->
        <view class="file-header" @tap.stop="toggleFile(file.fileId)">
          <view class="arrow-icon" :class="{ expanded: !collapsedFiles[file.fileId], hidden: file.matchCount === 0 }">
             <svg width="10" height="10" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
               <polyline points="9 18 15 12 9 6"></polyline>
             </svg>
          </view>
          <view class="file-icon-wrapper">
             <FileTypeIcon :type="file.fileType" />
          </view>
          <view class="file-info">
             <text class="file-name" @tap.stop="openFile(file)">{{ file.fileName }}</text>
             <text class="file-path">{{ getRelativePath(file.filePath) }}</text>
          </view>
          <view class="badge" v-if="file.matchCount > 0">{{ file.matchCount }}</view>
        </view>

        <!-- Matches -->
        <view class="matches-container" v-if="!collapsedFiles[file.fileId] && file.matchCount > 0">
          <view
            class="match-item"
            v-for="(match, idx) in file.matches"
            :key="idx"
            @tap="openMatch(file, match)"
          >
             <view class="indent-line"></view>
             <text class="line-number" v-if="match.lineNumber">{{ match.lineNumber }}</text>
             <text class="match-content">
                <text class="pre-match">{{ getPreMatch(match) }}</text>
                <text class="match-highlight">{{ getMatchText(match) }}</text>
                <text class="post-match">{{ getPostMatch(match) }}</text>
             </text>
          </view>
        </view>
      </view>
    </scroll-view>

    <!-- Empty State -->
    <view class="empty-state" v-if="!hasSearched && !loading">
       <view class="empty-icon">
          <svg width="48" height="48" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5">
             <circle cx="11" cy="11" r="8"></circle>
             <line x1="21" y1="21" x2="16.65" y2="16.65"></line>
          </svg>
       </view>
       <text class="empty-text">在所有文件中搜索</text>
    </view>
  </view>
</template>

<script>
import { searchProjectContent, getProjectTags } from '@/services/api'
import FileTypeIcon from '@/components/FileTypeIcon.vue'

export default {
  name: 'SearchPanel',
  components: {
    FileTypeIcon
  },
  props: {
    projectId: {
      type: Number,
      required: true
    }
  },
  emits: ['open-file'],
  data() {
    return {
      searchQuery: '',
      isSearchFocused: false,
      loading: false,
      hasSearched: false,
      searchOptions: {
        caseSensitive: false,
        wholeWord: false,
        useRegex: false
      },
      results: {
        totalMatches: 0,
        totalFiles: 0,
        results: []
      },
      collapsedFiles: {},
      debounceTimer: null,
      allProjectTags: [], // Store all tags
      visibleTags: [],    // Tags to display
      selectedTagIds: []
    }
  },
  mounted() {
    this.fetchTags()
  },
  methods: {
    async fetchTags() {
      try {
        const res = await getProjectTags(this.projectId)
        this.allProjectTags = res || []
        this.visibleTags = this.allProjectTags // Initially show all
      } catch (e) {
        console.error('Failed to load tags', e)
      }
    },
    toggleTag(tagId) {
      const index = this.selectedTagIds.indexOf(tagId)
      if (index === -1) {
        this.selectedTagIds.push(tagId)
      } else {
        this.selectedTagIds.splice(index, 1)
      }
      this.performSearch()
    },
    getTagStyle(tag) {
        const isSelected = this.selectedTagIds.includes(tag.id);
        const color = tag.color || '#6C757D';

        if (isSelected) {
            return {
                backgroundColor: color,
                borderColor: color,
                color: '#FFFFFF'
            };
        } else {
            return {
                backgroundColor: 'transparent',
                borderColor: '#2D5240', // 深底强分隔（$awd-chrome-active）
                color: '#A8BDB2' // 深底次级文字（$awd-text-on-dark-2）
            };
        }
    },
    updateVisibleTags(fileResults) {
        if (!fileResults || fileResults.length === 0) {
            if (this.selectedTagIds.length > 0) {
                 this.visibleTags = this.allProjectTags.filter(t => this.selectedTagIds.includes(t.id))
            } else {
                 this.visibleTags = this.allProjectTags
            }
            return
        }

        // Collect all tag IDs present in the result files
        const relevantTagIds = new Set()

        // Also always include currently selected tags, so they don't disappear
        this.selectedTagIds.forEach(id => relevantTagIds.add(id))

        fileResults.forEach(file => {
            if (file.tags) {
                file.tags.forEach(tag => relevantTagIds.add(tag.id))
            }
        })

        // Filter allProjectTags
        this.visibleTags = this.allProjectTags.filter(t => relevantTagIds.has(t.id))
    },
    onSearchInput() {
      if (this.debounceTimer) clearTimeout(this.debounceTimer)
      this.debounceTimer = setTimeout(() => {
        this.performSearch()
      }, 500)
    },
    toggleOption(option) {
      this.searchOptions[option] = !this.searchOptions[option]
      if (this.searchQuery) {
        this.performSearch()
      }
    },
    async performSearch() {
      // Allow search if query is non-empty OR if tags are selected
      this.loading = true
      this.hasSearched = true
      // 竞态防护：快速连点标签/选项会并发多次搜索，只让最新一次的结果落地
      const seq = (this._searchSeq = (this._searchSeq || 0) + 1)

      try {
        const response = await searchProjectContent(this.projectId, {
          query: this.searchQuery,
          ...this.searchOptions,
          tagIds: this.selectedTagIds,
          fileTypes: ['docx', 'pdf', 'pptx', 'xlsx', 'txt', 'md'] // Explicitly support these types
        })

        if (seq !== this._searchSeq) return // 已有更新的搜索发起，丢弃本次陈旧结果

        this.results = response

        // Update visible tags based on results
        this.updateVisibleTags(response.results)

        // Expand all by default
        this.collapsedFiles = {}
      } catch (e) {
        console.error('Search failed:', e)
        uni.showToast({ title: 'Search failed', icon: 'none' })
      } finally {
        if (seq === this._searchSeq) this.loading = false
      }
    },
    refreshSearch() {
      this.performSearch()
    },
    collapseAll() {
       const newCollapsed = {}
       if (this.results.results) {
         this.results.results.forEach(f => {
           newCollapsed[f.fileId] = true
         })
       }
       this.collapsedFiles = newCollapsed
    },
    toggleFile(fileId) {
      this.collapsedFiles[fileId] = !this.collapsedFiles[fileId]
    },
    openFile(file) {
      this.$emit('open-file', {
        id: file.fileId,
        wpsFileId: file.wpsFileId,
        name: file.fileName,
        fileType: file.fileType,
        filePath: file.filePath
      })
    },
    openMatch(file, match) {
      this.$emit('open-file', {
        id: file.fileId,
        wpsFileId: file.wpsFileId,
        name: file.fileName,
        fileType: file.fileType,
        filePath: file.filePath,
        position: {
           lineNumber: match.lineNumber,
           startIndex: match.startIndex,
           endIndex: match.endIndex
        }
      })
    },
    getRelativePath(path) {
        if (!path) return ''
        const parts = path.split('/')
        if (parts.length > 2) {
            return '.../' + parts[parts.length - 2]
        }
        return ''
    },
    getPreMatch(match) {
        if (!match.content) return ''
        return match.content.substring(0, match.startIndex)
    },
    getMatchText(match) {
        if (!match.content) return ''
        return match.content.substring(match.startIndex, match.endIndex)
    },
    getPostMatch(match) {
        if (!match.content) return ''
        return match.content.substring(match.endIndex)
    }
  }
}
</script>

<style lang="scss" scoped>
.search-panel {
  display: flex;
  flex-direction: column;
  height: 100%;
  background-color: transparent; /* 继承左栏 $awd-chrome-panel */
  font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Helvetica, Arial, sans-serif;
}

.search-header {
    padding: 16px 12px 12px;
    background-color: transparent;
    border-bottom: 1px solid transparent; /* Prepare for sticky behavior if needed */
}

.panel-title {
    font-size: 11px;
    font-weight: 600;
    color: $awd-text-on-dark-3;
    text-transform: uppercase;
    letter-spacing: 0.5px;
    margin-bottom: 12px;
    display: block;
}

.input-wrapper {
  position: relative;
  margin-bottom: 16px;

  .input-box {
    display: flex;
    align-items: center;
    background: $awd-chrome-hover;
    border: 1px solid $awd-chrome-active;
    border-radius: 6px;
    padding: 6px 10px;
    transition: all 0.2s ease;

    &.focused {
      border-color: $awd-mint;
      box-shadow: 0 0 0 3px rgba($awd-mint, 0.15);
    }

    .search-icon {
        margin-right: 8px;
        display: flex;
        align-items: center;
        color: $awd-text-on-dark-3;
    }

    .search-input {
      flex: 1;
      font-size: 13px;
      color: $awd-text-on-dark;
      border: none;
      outline: none;
      background: transparent;
      height: 20px;
      min-width: 0;

      &::placeholder {
          color: $awd-text-on-dark-3;
      }
    }

    .clear-icon {
        padding: 4px;
        cursor: pointer;
        display: flex;
        align-items: center;
        color: $awd-text-on-dark-3;
        opacity: 0.7;
        &:hover { opacity: 1; }
    }
  }
}

.section-label {
    font-size: 11px;
    color: $awd-text-on-dark-3;
    margin-bottom: 8px;
    font-weight: 500;
}

.tags-container {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: 16px;

  .tag-chip {
    padding: 4px 10px;
    border-radius: 100px; /* Pill shape */
    border: 1px solid; /* Color coming from inline style */
    cursor: pointer;
    transition: all 0.15s ease;
    font-size: 11px;
    font-weight: 500;

    &:hover {
      transform: translateY(-1px);
      box-shadow: 0 2px 4px rgba(0,0,0,0.05);
    }

    &:active {
        transform: translateY(0);
    }

    .tag-name {
        line-height: 1.2;
    }
  }
}

.search-stats {
  font-size: 11px;
  color: $awd-text-on-dark-2;
  display: flex;
  align-items: center;
  gap: 4px;

  .highlight {
      color: $awd-mint;
      font-weight: 600;
  }
}

.results-list {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding-bottom: 20px;
}

.file-group {
    background: transparent;
    margin-bottom: 8px;
    border-top: 1px solid transparent;
    border-bottom: 1px solid transparent;

    &:first-child {
        border-top: 1px solid $awd-chrome-line;
    }
    &:last-child {
        border-bottom: 1px solid $awd-chrome-line;
    }
}

.file-header {
  display: flex;
  align-items: center;
  padding: 8px 12px;
  cursor: pointer;
  transition: background-color 0.1s;

  &:hover {
    background-color: $awd-chrome-hover;
  }

  .arrow-icon {
    display: flex;
    align-items: center;
    justify-content: center;
    color: $awd-text-on-dark-3;
    margin-right: 8px;
    width: 16px;
    height: 16px;
    transition: transform 0.2s;

    &.expanded {
      transform: rotate(-90deg);
    }

    &.hidden {
        visibility: hidden;
    }
  }

  .file-icon-wrapper {
      margin-right: 10px;
      display: flex;
      align-items: center;
  }

  .file-info {
      flex: 1;
      min-width: 0;
      display: flex;
      flex-direction: column;

      .file-name {
          font-size: 13px;
          font-weight: 500;
          color: $awd-text-on-dark;
          margin-bottom: 2px;
          white-space: nowrap;
          overflow: hidden;
          text-overflow: ellipsis;

          &:hover {
              color: $awd-mint;
              text-decoration: underline;
          }
      }

      .file-path {
          font-size: 10px;
          color: $awd-text-on-dark-3;
          white-space: nowrap;
          overflow: hidden;
          text-overflow: ellipsis;
      }
  }

  .badge {
    background-color: $awd-chrome-active;
    color: $awd-text-on-dark-2;
    font-size: 10px;
    font-weight: 600;
    padding: 2px 6px;
    border-radius: 99px;
    min-width: 16px;
    text-align: center;
    margin-left: 8px;
  }
}

.matches-container {
    padding-bottom: 8px;
}

.match-item {
  display: flex;
  padding: 4px 12px 4px 30px; /* Indented alignment */
  cursor: pointer;
  position: relative;
  font-family: "JetBrains Mono", Menlo, Monaco, Consolas, monospace;

  &:hover {
    background-color: rgba($awd-mint, 0.08);
    .match-highlight {
        background-color: rgba($awd-mint, 0.3);
    }
  }

  .indent-line {
      position: absolute;
      left: 18px; /* Align with file icon center roughly */
      top: 0;
      bottom: 0;
      width: 1px;
      background-color: $awd-chrome-line;
  }

  .line-number {
      font-size: 10px;
      color: $awd-text-on-dark-3;
      width: 10px;
      text-align: right;
      margin-right: 12px;
      flex-shrink: 0;
  }

  .match-content {
     font-size: 11px;
     line-height: 1.5;
     color: $awd-text-on-dark-2;
     white-space: pre;
     overflow: hidden;
     text-overflow: ellipsis;

     .match-highlight {
         background-color: rgba($awd-mint, 0.18);
         color: $awd-mint;
         border-radius: 2px;
         padding: 0 1px;
         font-weight: 500;
     }
  }
}

.empty-state {
   display: flex;
   flex-direction: column;
   align-items: center;
   justify-content: center;
   padding: 60px 20px;

   .empty-icon {
       margin-bottom: 16px;
       color: $awd-chrome-active;
   }

   .empty-text {
      color: $awd-text-on-dark-3;
      font-size: 13px;
   }
}
</style>
