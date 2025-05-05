import {defineStore} from "pinia";
import {reactive} from "vue";
import {useDark, useToggle} from "@vueuse/core";

export const useAppStore = defineStore('app-store', () => {
  const layout = reactive({
    headerHeight: 60,

    collapsed: false,
    siderWidth: 200,
    collapsedWidth: 63,
  })
// 配置 useDark，保存主题到 localStorage
  const isDark = useDark({
    storageKey: 'theme', // 存储键名
    valueDark: 'dark',   // 暗黑模式对应的值
    valueLight: 'light'  // 明亮模式对应的值
  })

// 生成切换函数
  const toggleDark = useToggle(isDark)
  // --------------------- action ---------------------
  const toggleCollapsed = () => {
    layout.collapsed = !layout.collapsed
  }

  return {
    layout,
    toggleCollapsed,
    isDark,
    toggleDark,
  }
})