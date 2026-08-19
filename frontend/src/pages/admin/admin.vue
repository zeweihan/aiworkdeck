<template>
  <!-- 薄壳页：navigationStyle custom，仅把 query 转成 props；本体在
       components/admin/AdminPane.vue（照 plugin-market.vue + MarketPane 的先例）。

       2026-08-19 起工作台里的「系统设置」是中栏的一个标签，不再整页跳转。
       这一页保留给：直链 / 浏览器端 / 仓里十来处既有的
       navigateTo '/pages/admin/admin?nav=account' 之类的入口。 -->
  <AdminPane :initial-nav="initialNav" :initial-service="initialService" />
</template>

<script>
import AdminPane from '@/components/admin/AdminPane.vue'

export default {
  name: 'AdminPage',
  components: { AdminPane },
  data() {
    return {
      initialNav: '',
      initialService: '',
    }
  },
  onLoad(query) {
    // AdminPane 的 mounted 会读这两个 prop。onLoad 早于子组件 mounted，
    // 所以这里同步赋值即可，不需要 v-if 等一拍。
    this.initialNav = (query && query.nav) || ''
    this.initialService = (query && query.service) || ''
  },
}
</script>
