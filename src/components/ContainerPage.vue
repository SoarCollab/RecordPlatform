<script setup>
import {useRoute, useRouter} from "vue-router";
import MenuIcon from "@/components/Icon/MenuIcon.vue";
const router = useRouter()
defineOptions({
  name: 'ContainerPage'
})

defineProps({
  title: {
    type: [String,Boolean],
    default: true
  },
  back: {
    type: String,
    default: ''
  }
})

</script>

<template>
<div class="w-full h-full box-border overflow-hidden flex flex-col gap-3" >
  <div class="shrink-0 flex items-center gap-2" >
    <MenuIcon v-if="back" icon="Back" @click="router.push(back)" />
    <span v-if="title" class="font-bold text-[18px]" >{{useRoute()?.meta?.title || '-'}}</span>
    <div class="grow-1" >
      <slot name="title" />
    </div>
  </div>
  <div class="grow-1 of-x-hidden of-y-auto" >
    <slot/>
  </div>
</div>
</template>

<style scoped>

</style>