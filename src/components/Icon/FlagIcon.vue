<template>
  <span
    class="fi flag-icon"
    :class="`fi-${name.toLowerCase()}`"
    :style="{
      width: size,
      height: size,
      'font-size': size
    }"
    v-bind="$attrs"
  />
</template>

<script setup>
import { defineProps, computed } from 'vue'

const props = defineProps({
  // 国家代码（例如：cn, us, jp）
  name: {
    type: String,
    required: true,
    validator: (value) => /^[a-z]{2}$/i.test(value)
  },
  // 图标尺寸（支持 CSS 单位）
  size: {
    type: String,
    default: '2em'
  }
})
</script>

<style scoped>
@import 'flag-icons/css/flag-icons.min.css';

.flag-icon {
  background-size: contain;
  background-position: 50%;
  background-repeat: no-repeat;
  display: inline-block;
}
</style>