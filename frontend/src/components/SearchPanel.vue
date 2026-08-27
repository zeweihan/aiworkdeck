<template>
  <view class="search-panel">
    <!-- Header Area -->
    <view class="search-header">
      <!-- 面板标题由外壳的 sidebar-header 出（此前这里是一行注释掉的 panel-title，
           属于「靠注释躲开重复标题」那一档写法，已按统一口径清掉） -->

      <!-- Search Input -->
      <view class="input-wrapper">
        <view class="input-box" :class="{ focused: isSearchFocused }">
          <view class="search-icon">
            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="#6C757D" stroke-width="2">
              <circle cx="11" cy="11" r="8"></circle>
              <line x1="21" y1="21" x2="16.65" y2="16.65"></line>
            </svg>
          </view>
          <input
            class="search-input"
            v-model="searchQuery"
            :placeholder="$t('files.searchPlaceholder')"
            @focus="isSearchFocused = true"
            @blur="isSearchFocused = false"
            @confirm="performSearch"
            @input="onSearchInput"
          />
          <view v-if="searchQuery" class="clear-icon" @tap="searchQuery = ''; performSearch()">
             <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="#999" stroke-width="2">
                <line x1="18" y1="6" x2="6" y2="18"></line>
                <line x1="6" y1="6" x2="18" y2="18"></line>
             </svg>
          </view>
        </view>
      </view>

      <!-- Tag Filters
           默认折叠。自动打标签会给项目攒出上百个词（本机实测单个项目 338 个），
           全量平铺出来的那面标签墙会把搜索框和结果一起挤出屏幕——筛选器反而
           成了这个面板最难用的部分。折叠 + 计数 + 过滤框 + 已选常驻，是让
           「有几百个标签」这件事不再阻碍「找一份文件」。 -->
      <view class="tag-sec-head" v-if="visibleTags && visibleTags.length > 0" @tap="tagsOpen = !tagsOpen">
        <text class="tag-sec-chevron" :class="{ open: tagsOpen }">›</text>
        <text class="tag-sec-title">{{ $t('files.filterByTag') }}</text>
        <text class="tag-sec-count">{{ visibleTags.length }}</text>
        <view class="tag-sec-spacer"></view>
        <text class="tag-sec-clear" v-if="selectedTagIds.length" @tap.stop="clearTags">
          {{ $t('files.tagClear') }}
        </text>
      </view>

      <!-- 已选标签：折叠状态下也必须看得见，否则「搜不到东西」的原因被藏起来了 -->
      <view class="tags-container selected-row" v-if="!tagsOpen && selectedTags.length > 0">
        <view
          v-for="tag in selectedTags"
          :key="'sel-' + tag.id"
          class="tag-chip"
          :style="getTagStyle(tag)"
          @tap="toggleTag(tag.id)"
        >
          <text class="tag-name">{{ tag.name }}</text>
        </view>
      </view>

      <template v-if="tagsOpen && visibleTags && visibleTags.length > 0">
        <view class="tag-filter-box" v-if="visibleTags.length > TAG_FILTER_THRESHOLD">
          <input
            class="tag-filter-input"
            v-model="tagFilter"
            :placeholder="$t('files.tagFilterPlaceholder')"
          />
        </view>
        <!-- 分组只是展示形式，过滤/截断/排序全部作用于 shownTags 这一份全量列表，
             按组切片渲染，不另起一套逻辑（三个分组共用一份过滤与截断） -->
        <template v-for="group in shownTagGroups" :key="group.type">
          <view v-if="group.tags.length > 0" class="tag-subsec-head">
            <text class="tag-subsec-title">{{ $t(group.labelKey) }}</text>
          </view>
          <view v-if="group.tags.length > 0" class="tags-container">
            <view
              v-for="tag in group.tags"
              :key="tag.id"
              class="tag-chip"
              :class="{ selected: selectedTagIds.includes(tag.id) }"
              :style="getTagStyle(tag)"
              @tap="toggleTag(tag.id)"
            >
              <text class="tag-name">{{ tag.name }}</text>
            </view>
          </view>
        </template>
        <text
          class="tag-more"
          v-if="filteredTags.length > shownTags.length"
          @tap="tagsExpanded = true"
        >{{ $t('files.tagShowAll', { count: filteredTags.length }) }}</text>
        <text class="tag-empty" v-else-if="filteredTags.length === 0">{{ $t('files.tagNoMatch') }}</text>
      </template>

      <!-- Search Stats -->
      <view class="search-stats" v-if="hasSearched">
         <text v-if="loading">{{ $t('files.searching') }}</text>
         <text v-else-if="results.totalMatches === 0 && (!results.results || results.results.length === 0)">{{ $t('files.noResults') }}</text>
         <template v-else>
            <text class="highlight">{{ results.totalMatches }}</text> {{ $t('files.matchesSuffix') }} ·
            <text class="highlight">{{ results.totalFiles }}</text> {{ $t('files.filesSuffix') }}
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
          <svg width="48" height="48" viewBox="0 0 24 24" fill="none" stroke="#E9ECEF" stroke-width="1.5">
             <circle cx="11" cy="11" r="8"></circle>
             <line x1="21" y1="21" x2="16.65" y2="16.65"></line>
          </svg>
       </view>
       <text class="empty-text">{{ $t('files.searchAllFiles') }}</text>
    </view>
  </view>
</template>

<script>
import { searchProjectContent, getProjectTags } from '@/services/api'
import FileTypeIcon from '@/components/FileTypeIcon.vue'
import { TAG_TYPE_PARTY, TAG_TYPE_ISSUE, TAG_TYPE_NORMAL, normalizeTagType } from '@/utils/tagTypes.js'

// 标签超过这个数量才值得再给它一个过滤框
const TAG_FILTER_THRESHOLD = 12
// 展开后先只铺这么多，剩下的走「显示全部」——一个项目可能有几百个自动标签
const TAG_MAX_COLLAPSED = 24

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
      selectedTagIds: [],
      // 标签筛选区默认折叠（自动打标签能攒出上百个词，平铺会淹掉整个面板）
      tagsOpen: false,
      tagsExpanded: false,   // 「显示全部」点过之后不再截断
      tagFilter: '',
      tagCounts: {}          // tagId -> 本次搜索结果里命中的文件数，用来排序
    }
  },
  computed: {
    TAG_FILTER_THRESHOLD: () => TAG_FILTER_THRESHOLD,
    TAG_MAX_COLLAPSED: () => TAG_MAX_COLLAPSED,
    // 折叠时用来展示「当前正在按哪几个标签筛」
    selectedTags() {
      return this.allProjectTags.filter(t => this.selectedTagIds.includes(t.id))
    },
    /**
     * 排序口径：已选的永远在最前（否则勾过的标签会被冲到几百个之后找不回来），
     * 其次按本次搜索结果里的命中文件数降序——没搜过就没有计数，退回按名称排。
     */
    filteredTags() {
      const q = this.tagFilter.trim().toLowerCase()
      const list = q
        ? this.visibleTags.filter(t => (t.name || '').toLowerCase().includes(q))
        : this.visibleTags.slice()
      const selected = new Set(this.selectedTagIds)
      return list.sort((a, b) => {
        const sa = selected.has(a.id) ? 1 : 0
        const sb = selected.has(b.id) ? 1 : 0
        if (sa !== sb) return sb - sa
        const ca = this.tagCounts[a.id] || 0
        const cb = this.tagCounts[b.id] || 0
        if (ca !== cb) return cb - ca
        return String(a.name || '').localeCompare(String(b.name || ''), 'zh-Hans-CN')
      })
    },
    shownTags() {
      if (this.tagsExpanded || this.tagFilter.trim()) return this.filteredTags
      return this.filteredTags.slice(0, TAG_MAX_COLLAPSED)
    },
    // 展开态按「当事人 / 争议焦点 / 其他标签」三组渲染；shownTags 已经算好过滤+截断，
    // 这里只按类型切片，组内相对顺序原样保留（filteredTags 排好的序不受影响）
    shownTagGroups() {
      const list = this.shownTags
      return [
        { type: TAG_TYPE_PARTY, labelKey: 'files.tagGroupParty', tags: list.filter(t => normalizeTagType(t) === TAG_TYPE_PARTY) },
        { type: TAG_TYPE_ISSUE, labelKey: 'files.tagGroupIssue', tags: list.filter(t => normalizeTagType(t) === TAG_TYPE_ISSUE) },
        { type: TAG_TYPE_NORMAL, labelKey: 'files.tagGroupOther', tags: list.filter(t => normalizeTagType(t) === TAG_TYPE_NORMAL) }
      ]
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
    clearTags() {
      if (!this.selectedTagIds.length) return
      this.selectedTagIds = []
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
                backgroundColor: '#FFFFFF',
                borderColor: '#E9ECEF', // Neutral border
                color: '#6C757D' // Neutral text
            };
        }
    },
    updateVisibleTags(fileResults) {
        if (!fileResults || fileResults.length === 0) {
            this.tagCounts = {}
            if (this.selectedTagIds.length > 0) {
                 this.visibleTags = this.allProjectTags.filter(t => this.selectedTagIds.includes(t.id))
            } else {
                 this.visibleTags = this.allProjectTags
            }
            return
        }

        // Collect all tag IDs present in the result files
        const relevantTagIds = new Set()
        // 顺带数一遍每个标签命中了几个文件：标签区的排序靠它，没有计数就只能按名字排，
        // 而按名字排在几百个自动标签里等于没有排序
        const counts = {}

        // Also always include currently selected tags, so they don't disappear
        this.selectedTagIds.forEach(id => relevantTagIds.add(id))

        fileResults.forEach(file => {
            if (file.tags) {
                file.tags.forEach(tag => {
                    relevantTagIds.add(tag.id)
                    counts[tag.id] = (counts[tag.id] || 0) + 1
                })
            }
        })

        this.tagCounts = counts
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
        // 失败提示同样按 seq 收口：陈旧请求的迟到失败不该盖在新结果上弹「搜索失败」
        if (seq === this._searchSeq) uni.showToast({ title: 'Search failed', icon: 'none' })
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
/* Brands Colors from color.md */
$brand-mint: #5BD197;

.search-panel {
  display: flex;
  flex-direction: column;
  height: 100%;
  background-color: var(--awd-bg);
  font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Helvetica, Arial, sans-serif;
}

/* 密度令牌见 App.vue 的 --awd-panel-*（基准 = 插件广场） */
.search-header {
    padding: var(--awd-panel-gap) 0 var(--awd-panel-gap);
    background-color: var(--awd-bg);
    border-bottom: 1px solid transparent; /* Prepare for sticky behavior if needed */
}

.input-wrapper {
  position: relative;
  margin: 0 var(--awd-panel-pad-x) var(--awd-panel-gap);

  .input-box {
    display: flex;
    align-items: center;
    height: var(--awd-panel-row-h);
    background: var(--awd-surface);
    border: 1px solid var(--awd-panel-border);
    border-radius: var(--awd-panel-radius);
    padding: 0 8px;
    transition: all 0.2s ease;
    box-shadow: 0 1px 2px rgba(0,0,0,0.02);

    &.focused {
      border-color: var(--awd-mint);
      box-shadow: 0 0 0 3px rgba($brand-mint, 0.15);
    }

    .search-icon {
        margin-right: 8px;
        display: flex;
        align-items: center;
    }

    .search-input {
      flex: 1;
      font-size: var(--awd-panel-fs);
      color: var(--awd-text);
      border: none;
      outline: none;
      background: transparent;
      height: 20px;
      min-width: 0;

      &::placeholder {
          color: var(--awd-text-3);
      }
    }

    .clear-icon {
        padding: 4px;
        cursor: pointer;
        display: flex;
        align-items: center;
        opacity: 0.6;
        &:hover { opacity: 1; }
    }
  }
}

/* ---- 标签筛选（可折叠）---- */
.tag-sec-head {
  display: flex;
  align-items: center;
  gap: 4px;
  height: var(--awd-panel-sec-h);
  padding: 0 var(--awd-panel-pad-x);
  cursor: pointer;
  user-select: none;

  &:hover { background: var(--awd-panel-accent-wash); }
}

.tag-sec-chevron {
  width: 12px;
  font-size: 12px;
  color: var(--awd-panel-text-3);
  transition: transform 0.12s ease;
  transform-origin: center;

  &.open { transform: rotate(90deg); }
}

.tag-sec-title {
  font-size: var(--awd-panel-fs-sec);
  font-weight: 700;
  letter-spacing: 0.04em;
  color: var(--awd-panel-text-2);
}

.tag-sec-count {
  font-size: 10px;
  color: var(--awd-panel-text-3);
  background: var(--awd-panel-hover);
  border-radius: 999px;
  padding: 0 6px;
  line-height: 14px;
}

.tag-sec-spacer { flex: 1; }

.tag-sec-clear {
  font-size: 10px;
  color: var(--awd-panel-accent);
  cursor: pointer;

  &:hover { text-decoration: underline; }
}

/* 展开态内的三段分组头（当事人/争议焦点/其他标签）：与 .tag-sec-head 同一套令牌，
   不折叠、不带计数/清除按钮——判据类型已经写在标题里了 */
.tag-subsec-head {
  display: flex;
  align-items: center;
  height: var(--awd-panel-sec-h);
  padding: 0 var(--awd-panel-pad-x);
}

.tag-subsec-title {
  font-size: var(--awd-panel-fs-sec);
  font-weight: 700;
  letter-spacing: 0.04em;
  color: var(--awd-panel-text-2);
}

.tag-filter-box {
  margin: 2px var(--awd-panel-pad-x) 4px;
}

.tag-filter-input {
  width: 100%;
  height: 24px;
  box-sizing: border-box;
  padding: 0 8px;
  font-size: var(--awd-panel-fs-meta);
  color: var(--awd-panel-text);
  background: var(--awd-surface);
  border: 1px solid var(--awd-panel-border);
  border-radius: 4px;
  outline: none;

  &::placeholder { color: var(--awd-panel-text-4); }
}

.tags-container {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
  padding: 0 var(--awd-panel-pad-x);
  margin-bottom: var(--awd-panel-gap);

  &.selected-row { margin-bottom: 4px; }

  .tag-chip {
    padding: 1px 7px;
    border-radius: 4px;
    border: 1px solid; /* Color coming from inline style */
    cursor: pointer;
    font-size: 10px;
    line-height: 16px;
    font-weight: 500;
    max-width: 100%;

    &:hover { filter: brightness(0.97); }

    .tag-name {
        line-height: 1.2;
    }
  }
}

.tag-more,
.tag-empty {
  display: block;
  padding: 0 var(--awd-panel-pad-x) var(--awd-panel-gap);
  font-size: 10px;
  color: var(--awd-panel-text-3);
}

.tag-more {
  color: var(--awd-panel-accent);
  cursor: pointer;

  &:hover { text-decoration: underline; }
}

.search-stats {
  font-size: var(--awd-panel-fs-meta);
  color: var(--awd-text-2);
  padding: 0 var(--awd-panel-pad-x);
  display: flex;
  align-items: center;
  gap: 4px;

  .highlight {
      color: var(--awd-accent-text);
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
    background: var(--awd-surface);
    margin-bottom: 8px;
    border-top: 1px solid transparent;
    border-bottom: 1px solid transparent;

    &:first-child {
        border-top: 1px solid var(--awd-border);
    }
    &:last-child {
        border-bottom: 1px solid var(--awd-border);
    }
}

.file-header {
  display: flex;
  align-items: center;
  padding: 8px 12px;
  cursor: pointer;
  transition: background-color 0.1s;

  &:hover {
    background-color: var(--awd-bg);
  }

  .arrow-icon {
    display: flex;
    align-items: center;
    justify-content: center;
    color: var(--awd-text-2);
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
          color: var(--awd-text);
          margin-bottom: 2px;
          white-space: nowrap;
          overflow: hidden;
          text-overflow: ellipsis;

          &:hover {
              color: var(--awd-accent-text);
              text-decoration: underline;
          }
      }

      .file-path {
          font-size: 10px;
          color: var(--awd-text-3);
          white-space: nowrap;
          overflow: hidden;
          text-overflow: ellipsis;
      }
  }

  .badge {
    background-color: var(--awd-surface-3);
    color: var(--awd-text-2);
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
    background-color: rgba($brand-mint, 0.05);
    .match-highlight {
        background-color: rgba($brand-mint, 0.3);
    }
  }

  .indent-line {
      position: absolute;
      left: 18px; /* Align with file icon center roughly */
      top: 0;
      bottom: 0;
      width: 1px;
      background-color: var(--awd-surface-3);
  }

  .line-number {
      font-size: 10px;
      color: var(--awd-text-3);
      width: 10px;
      text-align: right;
      margin-right: 12px;
      flex-shrink: 0;
  }

  .match-content {
     font-size: 11px;
     line-height: 1.5;
     color: var(--awd-text-2);
     white-space: pre;
     overflow: hidden;
     text-overflow: ellipsis;

     .match-highlight {
         background-color: rgba($brand-mint, 0.15);
         color: var(--awd-accent-text);
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
       color: var(--awd-info-text);
   }

   .empty-text {
      color: var(--awd-text-3);
      font-size: 13px;
   }
}
</style>
