<script setup>
defineOptions({
  name: 'DescItem'
})
defineProps({
  label: {
    type: String,
    default: ''
  },
  value: {
    type: String,
    default: ''
  },
})
</script>

<template>
<div class="w-full flex items-center text-[14px] overflow-hidden" >
  <div class="shrink-0 text-[14px] text-gray font-bold" >
    {{label}}
  </div>
  <div class="grow-1 overflow-hidden flex items-center  text-[14px]" >
    <slot name="preValue"/>
    <span class="truncate" >{{value}}</span>
    <slot name="value" :value="value" />
  </div>
</div>
</template>

<style scoped>

</style>