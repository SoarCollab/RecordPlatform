<template>
  <!-- 网格容器，使用动态计算的列数和卡片宽度 -->
  <div
    ref="containerRef"
    class="grid-container"
    :style="{
      gridTemplateColumns: `repeat(${columns}, ${cardWidth}px)`
    }"
  >
    <!-- 使用作用域插槽暴露卡片尺寸给父组件 -->
    <slot :width="cardWidth" :height="cardHeight" />
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { debounce } from 'lodash-es'
import { useResizeObserver } from '@vueuse/core'

// 组件属性定义
const props = defineProps({
  minWidth: { // 卡片最小宽度
    type: Number,
    default: 300
  },
  maxWidth: { // 卡片最大宽度
    type: Number,
    default: 320
  },
  gap: { // 卡片间距
    type: Number,
    default: 16
  },
  debounceTime: { // 防抖时间（毫秒）
    type: Number,
    default: 100
  },
  aspectRatio: { // 宽高比（宽度/高度）
    type: Number,
    default: 3/2 // 默认 3:2 比例
  }
})

// 容器元素引用
const containerRef = ref(null)
// 当前列数
const columns = ref(1)
// 当前卡片宽度
const cardWidth = ref(props.minWidth)

/**
 * 计算卡片高度（基于宽高比）
 * 通过计算属性自动更新
 */
const cardHeight = computed(() => {
  return cardWidth.value / props.aspectRatio
})

/**
 * 带防抖的布局计算函数
 * 使用 Lodash 的 debounce 进行性能优化
 */
const calculateLayout = debounce(() => {
  if (!containerRef.value) return

  // 获取容器可用宽度
  const containerWidth = containerRef.value.offsetWidth
  const gap = props.gap

  // 计算最大可能的列数（基于最大宽度）
  const maxPossibleColumns = Math.floor(
    (containerWidth + gap) / (props.maxWidth + gap))

  // 初始计算值
  let calculatedColumns = maxPossibleColumns
  let calculatedWidth = (containerWidth - (maxPossibleColumns - 1) * gap) / maxPossibleColumns

  // 处理宽度超过最大值的情况
  if (calculatedWidth > props.maxWidth) {
    calculatedWidth = props.maxWidth
    calculatedColumns = Math.floor(
      (containerWidth + gap) / (props.maxWidth + gap)
    )
  }
  // 处理宽度小于最小值的情况
  else if (calculatedWidth < props.minWidth) {
    calculatedColumns = Math.floor(
      (containerWidth + gap) / (props.minWidth + gap)
    )
    calculatedColumns = Math.max(1, calculatedColumns) // 保证至少1列
    calculatedWidth = Math.min(
      (containerWidth - (calculatedColumns - 1) * gap) / calculatedColumns,
      props.maxWidth
    )
  }

  // 更新响应式数据
  columns.value = calculatedColumns
  cardWidth.value = calculatedWidth
}, props.debounceTime)

/**
 * 使用 VueUse 的 ResizeObserver 监听容器尺寸变化
 * 比原生 resize 事件更高效准确
 */
useResizeObserver(containerRef, () => {
  calculateLayout()
})

// 组件挂载时立即计算初始布局
onMounted(() => {
  // SSR 兼容处理：仅在客户端执行
  if (typeof window !== 'undefined') {
    calculateLayout()
  }
})

/**
 * 监听相关属性变化，触发重新计算
 * 包括：minWidth, maxWidth, gap
 */
watch(
  () => [props.minWidth, props.maxWidth, props.gap],
  () => {
    calculateLayout()
  },
  { deep: true }
)
</script>

<style scoped>
.grid-container {
  width: 100%;
  display: grid;
  gap: v-bind('props.gap + "px"'); /* 动态绑定间距值 */
  justify-content: start;
  transition: grid-template-columns 0.3s ease-in-out; /* 平滑过渡效果 */
}
</style>