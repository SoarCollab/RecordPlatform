import { defineStore } from 'pinia'
import { ref, watch } from 'vue'

export const useThemeStore = defineStore('theme', () => {
  const isDarkMode = ref<boolean>(
    localStorage.getItem('theme') === 'dark' || 
    (!localStorage.getItem('theme') && window.matchMedia('(prefers-color-scheme: dark)').matches)
  )

  const toggleTheme = (): void => {
    isDarkMode.value = !isDarkMode.value
  }

  const setTheme = (dark: boolean): void => {
    isDarkMode.value = dark
  }

  // Watch for theme changes and update localStorage and document class
  watch(isDarkMode, (newValue) => {
    localStorage.setItem('theme', newValue ? 'dark' : 'light')
    document.documentElement.classList.toggle('dark', newValue)
  }, { immediate: true })

  return {
    isDarkMode,
    toggleTheme,
    setTheme
  }
})