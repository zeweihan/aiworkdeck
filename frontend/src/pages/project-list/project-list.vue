<!-- SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors -->
<!-- SPDX-License-Identifier: AGPL-3.0-or-later -->
<template>
  <!-- 直链薄壳（dev-board#1047）：项目列表的内容本体已经是工作台左栏的「项目」面板
       （components/project-list/ProjectListPane.vue）。路由保留给直链、e2e、全局返回键
       与仓里既有的落点（登录成功、应用菜单「关闭项目」、日程页 / 概览薄壳页 / 新建项目页的返回），
       进来即转到工作台外壳并打开该面板。 -->
  <view class="page-project-list"></view>
</template>

<script>
import { isDesktopHost } from '@/services/host.js'
import { getCurrentUser, getSessionId } from '@/utils/auth.js'

const SHELL_URL = '/pages/project-overview/project-overview?pane=projects'

export default {
  name: 'ProjectList',
  onLoad() {
    // 浏览器端未登录回登录页；桌面 local-mode 免登，跳过该检查
    if (!isDesktopHost() && !(getSessionId() && getCurrentUser())) {
      uni.reLaunch({ url: '/pages/login/login' })
      return
    }
    // 同级替换：本页是 reLaunch 进来的单页栈（登录成功、菜单「关闭项目」）时 redirectTo，
    // 栈深度保持 1。栈里还压着别的页（有人 navigateTo 进来）时改 reLaunch——底下若恰好
    // 是一个活着的工作台，redirectTo 会让两个工作台实例并存（页面栈多实例地雷）。
    const pages = typeof getCurrentPages === 'function' ? getCurrentPages() : []
    if (pages.length > 1) {
      uni.reLaunch({ url: SHELL_URL })
    } else {
      uni.redirectTo({ url: SHELL_URL })
    }
  },
}
</script>

<style lang="scss" scoped>
.page-project-list {
  min-height: 100vh;
  background: var(--awd-bg);
}
</style>
