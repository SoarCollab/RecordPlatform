<script setup>

import {storeToRefs} from "pinia";
import {useAppStore} from "@/store/appStore.js";
import {Fold,Expand} from "@element-plus/icons-vue";
import SiderMenu from "@/layout/components/Sider/SiderMenu.vue";

defineOptions({
  name: 'SiderBar'
})
const appStore = useAppStore()
const {toggleCollapsed} = appStore
const {layout} = storeToRefs(appStore)

const siderStyle = computed(() => {
  return {
    width: layout.value.collapsed ? `${layout.value.collapsedWidth}px` : `${layout.value.siderWidth}px`
  }
})

</script>

<template>
<div :style="siderStyle" class=" shrink-0 sider flex flex-col h-full overflow-hidden" >
  <div class="grow-1 scrollbar" >
    <SiderMenu/>
  </div>
  <el-divider style="margin: 0" />
  <div class="shrink-0 h-[40px] flex-center" >
    <el-icon @click="toggleCollapsed" >
      <Expand v-if="layout.collapsed" />
      <Fold v-else />
    </el-icon>
  </div>
</div>
</template>

<style scoped>
.sider {
  transition: width 0.3s;
}

</style>