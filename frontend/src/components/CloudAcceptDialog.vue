<!-- SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors -->
<!-- SPDX-License-Identifier: AGPL-3.0-or-later -->
<template>
  <view v-if="visible" class="awd-mask" @tap.self="close">
    <view class="awd-dialog">
      <view class="awd-header"><text class="awd-title">{{ $t('version.pullFromLibraryTitle') }}</text></view>
      <view class="awd-body">
        <view v-if="loading" class="cloud-accept-hint">{{ $t('version.loadingGeneric') }}</view>
        <!-- 走到这里 = 本站没有官方案件库、本机也没有连接（国际站）。没有「去连一个」
             那条路了：手填地址的入口已撤，自建部署由 cloud.collab.base-url 指过来。 -->
        <view v-else-if="noConnection" class="cloud-accept-empty">
          <text class="cloud-accept-hint">{{ $t('version.noLibraryAvailableShort') }}</text>
        </view>
        <template v-else>
          <!-- 存放位置（dev-board#1040，仅桌面壳）：默认沿用软件托管目录（不传 localRoot，
               行为与原来一样）；「更改…」选一个文件夹后，案卷落在其下以案卷名命名的新文件夹里，
               按本机文件夹项目打开。浏览器端没有系统文件夹对话框，这一行不出现。 -->
          <view v-if="canChooseFolder && projects.length" class="cloud-accept-location">
            <view class="cloud-accept-location-main">
              <text class="cloud-accept-location-label">{{ $t('version.pullLocationLabel') }}</text>
              <text class="cloud-accept-location-path">{{ parentDir || $t('version.pullLocationManaged') }}</text>
            </view>
            <view class="cloud-accept-location-actions">
              <text class="cloud-accept-link" @tap="chooseParentDir">{{ $t('version.pullLocationChange') }}</text>
              <text v-if="parentDir" class="cloud-accept-link" @tap="parentDir = ''">{{ $t('version.pullLocationReset') }}</text>
            </view>
            <text v-if="parentDir" class="cloud-accept-location-hint">{{ $t('version.pullLocationHint') }}</text>
          </view>
          <view v-if="!projects.length" class="cloud-accept-hint">{{ $t('version.noSharedProjects') }}</view>
          <view v-else class="cloud-project-list">
            <view v-for="p in projects" :key="p.id" class="cloud-project-row">
              <view class="cloud-project-info">
                <text class="cloud-project-name">{{ p.name }}</text>
                <!-- 被邀请进来的人在取之前就该看得见自己在这份案卷里是什么身份 -->
                <text v-if="p.myRole" class="cloud-project-role">{{ roleLabel(p.myRole) }}</text>
              </view>
              <view
                class="awd-btn awd-btn-secondary"
                :class="{ 'awd-btn-disabled': busy }"
                @tap="onAccept(p)"
              >{{ $t('version.pullToDevice') }}</view>
            </view>
          </view>
        </template>
      </view>
      <view class="awd-footer">
        <view class="awd-btn awd-btn-secondary" @tap="close">{{ $t('common.close') }}</view>
      </view>
    </view>
  </view>
</template>

<script>
import {
  listCloudConnections, listRemoteProjects, acceptCloudProject,
  getOfficialCloud, connectOfficialCloud,
} from '@/services/api.js'
import { roleLabel } from '@/config/memberRoles.js'
import { host, isDesktopHost } from '@/services/host.js'

// 案卷名当文件夹名：去掉各平台文件名里不许出现的字符与首尾的点/空格
function folderNameFor(name) {
  const cleaned = String(name || '').replace(/[\\/:*?"<>|\u0000-\u001f]/g, '_').replace(/^[\s.]+|[\s.]+$/g, '')
  return cleaned || 'case'
}

// 分隔符跟着所选父目录走（Windows 的对话框回的是反斜杠路径）
function joinPath(parent, child) {
  const sep = parent.includes('\\') && !parent.includes('/') ? '\\' : '/'
  return parent.endsWith(sep) ? parent + child : parent + sep + child
}

export default {
  name: 'CloudAcceptDialog',
  props: {
    visible: { type: Boolean, default: false },
  },
  emits: ['accepted', 'update:visible'],
  data() {
    return {
      loading: false,
      noConnection: false,
      // 本机只认一个案件库（官方，或 cloud.collab.base-url 指过来的自建库），
      // 界面上不再有「从哪个案件库取」的选择器
      connectionId: null,
      projects: [],
      busy: false,
      // 自选存放位置的父目录；空 = 软件托管目录（默认）
      parentDir: '',
    }
  },
  computed: {
    // 判据同项目列表页的 isDesktop：有系统文件夹对话框才给这一行
    canChooseFolder() {
      return isDesktopHost() && !!(host.fs && host.fs.showOpenDialog)
    },
  },
  watch: {
    visible(v) {
      if (v) this.load()
    },
  },
  methods: {
    async load() {
      this.loading = true
      this.noConnection = false
      this.projects = []
      try {
        let conns = await this.fetchConnections()
        // 一条连接都没有、但本站有官方案件库：用本机的 AI WorkDeck 账户当场连上再列。
        // 这个弹窗的全部用途就是「取一份案卷」，先弹一个「去连一个」再让人回来点第二次
        // 是白走一趟——连的还是他自己的账号，不是什么新授权。
        if (!conns.length && await this.officialAvailable()) {
          try {
            await connectOfficialCloud()
            conns = await this.fetchConnections()
          } catch (e) {
            uni.showToast({ title: (e && e.message) || this.$t('version.connectFailed'), icon: 'none' })
          }
        }
        if (!conns.length) { this.noConnection = true; return }
        this.connectionId = conns[0].id
        await this.loadProjects()
      } catch (e) {
        uni.showToast({ title: (e && e.message) || this.$t('version.loadRemoteProjectsFailed'), icon: 'none' })
      } finally {
        this.loading = false
      }
    },
    // Options API 模板拿不到裸导入函数，包一层 method 才能在模板里当 roleLabel(...) 调用
    roleLabel,
    async fetchConnections() {
      const res = await listCloudConnections()
      return (res && res.data && res.data.connections) || []
    },
    async officialAvailable() {
      try {
        const res = await getOfficialCloud()
        return !!(res && res.data && res.data.available)
      } catch (e) {
        return false
      }
    },
    async loadProjects() {
      const pres = await listRemoteProjects(this.connectionId)
      this.projects = (pres && pres.data && pres.data.projects) || []
    },
    async onAccept(project) {
      if (this.busy) return
      this.busy = true
      try {
        const localRoot = this.canChooseFolder && this.parentDir
          ? joinPath(this.parentDir, folderNameFor(project.name))
          : undefined
        const res = await acceptCloudProject(this.connectionId, project.id, localRoot)
        const localProjectId = res && res.data && res.data.localProjectId
        this.close()
        this.$emit('accepted', localProjectId)
      } catch (e) {
        uni.showToast({ title: (e && e.message) || this.$t('version.pullToDeviceFailed'), icon: 'none' })
      } finally {
        this.busy = false
      }
    },
    async chooseParentDir() {
      const res = await host.fs.showOpenDialog({
        title: this.$t('account.selectLocationTitle'),
        buttonLabel: this.$t('account.selectHereBtn'),
        properties: ['openDirectory', 'createDirectory'],
      })
      if (!res || res.canceled || !res.filePaths || !res.filePaths.length) return
      this.parentDir = res.filePaths[0]
    },
    close() {
      this.$emit('update:visible', false)
    },
  },
}
</script>

<style lang="scss" scoped>
.awd-mask {
  position: fixed; inset: 0; background: var(--awd-overlay);
  display: flex; align-items: center; justify-content: center; z-index: 999;
}
.awd-dialog { width: 600rpx; max-height: 74vh; display: flex; flex-direction: column; background: var(--awd-surface); border-radius: 12rpx; overflow: hidden; }
.awd-header { padding: 24rpx; border-bottom: 1px solid var(--awd-border); }
.awd-title { font-size: 30rpx; font-weight: 600; }
.awd-body { padding: 24rpx; overflow-y: auto; flex: 1; }
.awd-footer {
  display: flex; justify-content: flex-end; gap: 16rpx;
  padding: 20rpx 24rpx; border-top: 1px solid var(--awd-border);
}
.awd-btn { padding: 12rpx 24rpx; border-radius: 6rpx; font-size: 25rpx; }
.awd-btn-primary { background: var(--awd-info); color: var(--awd-text-on-accent); }
.awd-btn-secondary { background: var(--awd-bg); color: var(--awd-text); }
.awd-btn-disabled { opacity: .4; pointer-events: none; }

.cloud-accept-hint { font-size: 26rpx; color: var(--awd-text-2); line-height: 1.6; }
.cloud-accept-empty { display: flex; flex-direction: column; gap: 12rpx; align-items: flex-start; }
.cloud-project-list {}
.cloud-project-row {
  display: flex; align-items: center; justify-content: space-between;
  padding: 16rpx 0; border-bottom: 1px solid var(--awd-border-subtle);
}
.cloud-project-info { display: flex; flex-direction: column; gap: 2px; min-width: 0; }
.cloud-project-role { font-size: 12px; color: var(--awd-text-3); }
.cloud-project-name { font-size: 26rpx; color: var(--awd-text); word-break: break-all; }
.cloud-accept-location {
  display: flex; flex-wrap: wrap; align-items: center; justify-content: space-between; gap: 6px 12px;
  padding-bottom: 16rpx; margin-bottom: 8rpx; border-bottom: 1px solid var(--awd-border);
}
.cloud-accept-location-main { display: flex; flex-direction: column; gap: 2px; min-width: 0; flex: 1; }
.cloud-accept-location-label { font-size: 12px; color: var(--awd-text-3); }
.cloud-accept-location-path { font-size: 24rpx; color: var(--awd-text); word-break: break-all; }
.cloud-accept-location-actions { display: flex; gap: 12px; flex-shrink: 0; }
.cloud-accept-location-hint { width: 100%; font-size: 12px; color: var(--awd-text-3); }
.cloud-accept-link { font-size: 24rpx; color: var(--awd-accent-text); cursor: pointer; }
</style>
