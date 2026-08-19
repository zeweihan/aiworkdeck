<template>
  <!-- 薄壳页：navigationStyle custom，本体在 components/userprofile/UserProfilePane.vue
       （照 pages/admin/admin.vue + AdminPane 的先例）。

       2026-08-19 起工作台里的「个人中心」是中栏的一个标签，不再整页跳转
       （overview.vue 里 goToUserProfile 仍是 navigateTo 进来，它依赖页面栈保留
       工作台实例以便 onShow 回流刷新——本页只是它落地的地方）。
       这一页保留给：直链 / 浏览器端 / 仓里既有的
       navigateTo '/pages/userprofile/userprofile'。 -->
  <UserProfilePane />
</template>

<script>
import UserProfilePane from '@/components/userprofile/UserProfilePane.vue'

export default {
  name: 'UserProfilePage',
  components: { UserProfilePane },
}
</script>
