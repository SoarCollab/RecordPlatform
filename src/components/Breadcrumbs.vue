<script setup>
import { computed } from 'vue'
import { useRoute } from 'vue-router'

defineOptions({
  name: 'Breadcrumbs'
})

const route = useRoute()



const breadcrumbs = computed(() => {
  return route?.matched
    .filter(item => item?.meta?.title) // 过滤有 title 的路由
    .map(item => ({
      path: item.path,
      title: item?.meta?.title
    }))
})
</script>

<template>
  <el-breadcrumb separator="/">
    <el-breadcrumb-item
      v-for="(item, index) in breadcrumbs"
      :key="index"
      :to="item.path">
      <span :class="{'text-blue font-bold': route.path === item.path }" >{{ item.title }}</span>
    </el-breadcrumb-item>
  </el-breadcrumb>
</template>

<style scoped>

</style>