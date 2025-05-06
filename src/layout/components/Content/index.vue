<script setup>

import {useAppStore} from "@/store/appStore.js";
import {storeToRefs} from "pinia";

defineOptions({
  name: 'ContentContainer'
})

const appStore = useAppStore()
const {layout, isDark} = storeToRefs(appStore)

const cls = computed(() => {
  return {
    'content-container': !isDark.value,
     'bg-[#222429ff]': isDark.value
  }
})

const contentCls = computed(() => {
  return {
    'bg-white': !isDark.value,
    'bg-[#141414FF]': isDark.value
  }
})


</script>

<template>
  <div class="grow-1 h-full overflow-hidden box-border p-2" :class="cls" >
    <div class="w-full h-full box-border of-x-hidden of-y-auto scrollbar p-2 rounded-md" :class="contentCls" >
      <RouterView/>
    </div>
  </div>
</template>

<style scoped>
.content-container{
  background-color: rgb(240, 242, 245);
}
</style>