<script setup>
import { computed } from 'vue'
import * as ElementPlusIconsVue from '@element-plus/icons-vue'
import { QuestionFilled } from '@element-plus/icons-vue'
defineOptions({
  name: 'MenuIcon'
})



const props = defineProps({
  icon: {
    type: String,
    default: ''
  },
  color: {
    type: String,
    default: ''
  },
  size: {
    type: String,
    default: ''
  }
})

const iconComponent = computed(() => {
  if (!props.icon) return null
  return ElementPlusIconsVue[props.icon] ||
    ElementPlusIconsVue[props.icon.charAt(0).toUpperCase() + props.icon.slice(1)]
})

// 只有传入时才设置对应样式属性
const iconStyle = computed(() => {
  const style = {}
  if (props.color) {
    style.color = props.color
  }
  if (props.size) {
    style.fontSize = props.size
  }
  return style
})
</script>

<template>
  <el-icon :style="iconStyle">
    <component :is="iconComponent" v-if="iconComponent" />
    <el-icon v-else><QuestionFilled /></el-icon>
  </el-icon>
</template>

<style scoped>

</style>