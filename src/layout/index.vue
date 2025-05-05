<script setup>

import HeaderBar from "@/layout/components/Header/index.vue";
import {useAppStore} from "@/store/appStore.js";
import SiderBar from "@/layout/components/Sider/index.vue";
import ContentContainer from "@/layout/components/Content/index.vue";
import {storeToRefs} from "pinia";

defineOptions({
  name: 'Layout'
})

const {layout} = storeToRefs(useAppStore())

const headerStyle = computed(() => {
  return {
    height: layout.value.headerHeight + 'px'
  }
})

const containerStyle = computed(() => {
  return {
    height: `calc(100% - ${layout.value.headerHeight}px)`
  }
})

</script>

<template>
  <div class="w-full h-full flex flex-col" >
    <div class="shrink-0 bottom-shadow" :style="headerStyle" >
      <HeaderBar/>
    </div>
    <div class="grow-1 flex flex-row" :style="containerStyle" >
      <SiderBar/>
      <ContentContainer/>
    </div>
  </div>
</template>

<style scoped>
.bottom-shadow{
  box-shadow: 0 2px 0 0 rgba(0, 0, 0, 0.03);
}
</style>