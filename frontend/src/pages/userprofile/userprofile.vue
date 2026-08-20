<template>
  <!-- 薄壳页：navigationStyle custom。

       2026-08-20 起个人中心不再是一个独立面板——它是统一「设置」页
       （components/admin/AdminPane.vue）里的「个人」组。两个设置类入口互相跳、
       各开一整块面板的形态是用户明确抱怨过的，现在只剩一个。

       这一页保留下来只为了不断既有链接：应用菜单的「账户」、项目列表页的
       「个人中心」按钮、以及仓里其它 navigateTo '/pages/userprofile/userprofile'。
       它落在个人组第一栏（工作记录），与老页面进来时看到的东西一致。 -->
  <AdminPane initial-nav="work_log" />
</template>

<script>
import AdminPane from '@/components/admin/AdminPane.vue'

export default {
  name: 'UserProfilePage',
  components: { AdminPane },
}
</script>
