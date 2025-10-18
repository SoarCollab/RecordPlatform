<template>
  <div id="app" :class="{ 'dark-mode': isDarkMode }">
    <AppHeader v-if="isAuthenticated" />
    <AppSidebar v-if="isAuthenticated" />
    <main :class="{ 'main-content': isAuthenticated, 'main-content-full': !isAuthenticated }">
      <RouterView />
    </main>
    <AppNotifications />
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { RouterView } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { useThemeStore } from '@/stores/theme'
import AppHeader from '@/components/layout/AppHeader.vue'
import AppSidebar from '@/components/layout/AppSidebar.vue'
import AppNotifications from '@/components/common/AppNotifications.vue'

const authStore = useAuthStore()
const themeStore = useThemeStore()

const isAuthenticated = computed(() => authStore.isAuthenticated)
const isDarkMode = computed(() => themeStore.isDarkMode)
</script>

<style scoped>
#app {
  min-height: 100vh;
  display: flex;
  flex-direction: column;
}

.main-content {
  margin-left: 250px;
  margin-top: 60px;
  padding: 20px;
  flex: 1;
  background-color: var(--bg-secondary);
  transition: margin-left 0.3s ease;
}

.main-content-full {
  padding: 0;
  flex: 1;
}

@media (max-width: 768px) {
  .main-content {
    margin-left: 0;
    margin-top: 60px;
  }
}
</style>