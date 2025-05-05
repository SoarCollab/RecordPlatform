<script setup>

import {useUserStore} from "@/store/userStore.js";
import {storeToRefs} from "pinia";
import {computed} from "vue";
import {useRoute} from "vue-router";
import {useAppStore} from "@/store/appStore.js";
import SubMenu from "@/layout/components/Sider/SubMenu.vue";

defineOptions({
  name: 'SiderMenu'
})
const route = useRoute()
const {layout} = storeToRefs(useAppStore())
const userStore = useUserStore()
const {menuTree} = storeToRefs(userStore)

const getUserRouteList = computed(() => {
  return menuTree.value
})


</script>

<template>
  <el-menu
    :default-active="route.path"
    class="el-menu-vertical"
    :collapse="layout.collapsed"
    router
    unique-opened
  >
    <template v-for="routeItem in getUserRouteList" :key="routeItem.path">
      <SubMenu :item="routeItem" />
    </template>
  </el-menu>
</template>

<style scoped>
.el-menu {
  /* 移除主容器边框 */
  border: none !important;
}
.el-menu-vertical:not(.el-menu--collapse) {
  width: 200px;
  min-height: 100%;
  border-right: none !important;
}
</style>