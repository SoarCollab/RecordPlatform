<template>
  <div v-if="tasks.length > 0" class="task-notification"
    :class="{ 'is-minimized': isMinimized, 'task-notification-minimized': isMinimized, 'is-dark': isDark }">
    <div class="task-notification-header" @click="toggleMinimize"
      :class="{ 'has-error': hasFailedTasks }">
      <el-badge :value="tasks.length" :type="hasFailedTasks ? 'danger' : 'primary'">
        <el-icon class="task-icon" :size="20" :class="{ 'has-error': hasFailedTasks }">
          <Clock />
        </el-icon>
      </el-badge>
      <el-icon v-if="!isMinimized" class="task-icon" :size="18">
        <ArrowDown />
      </el-icon>
    </div>

    <div class="task-notification-content" v-show="!isMinimized">
      <div v-for="task in tasks" :key="task.id" class="task-item" 
        :class="{
          'task-failed': task.status === TaskStatus.ERROR,
          'task-success': task.status === TaskStatus.SUCCESS,
          'task-processing': task.status === TaskStatus.PROCESSING,
          'task-pending': task.status === TaskStatus.PENDING
        }">
        <div class="task-info">
          <span class="task-name">{{ task.title }}</span>
          <span class="task-status" :class="task.status">{{ getStatusText(task.status) }}</span>
        </div>
        <div v-if="task.error" class="task-error">
          {{ task.error }}
        </div>
        <div class="task-progress" v-if="task.status === TaskStatus.PROCESSING">
          <div class="progress-bar">
            <div class="progress" :style="{ width: `${task.progress}%` }"></div>
          </div>
          <span class="progress-text">{{ task.progress }}%</span>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onBeforeUnmount } from 'vue'
import TaskManager, { TaskStatus } from '@/utils/taskNotification'
import { useAppStore } from '@/store/appStore'
import { Clock, ArrowUp, ArrowDown } from '@element-plus/icons-vue'

const appStore = useAppStore()
const isDark = computed(() => appStore.isDark)

const isMinimized = ref(true)
const tasks = ref([])

const hasFailedTasks = computed(() => {
  return tasks.value.some(task => task.status === TaskStatus.ERROR)
})

const toggleMinimize = () => {
  isMinimized.value = !isMinimized.value
}

const getStatusText = (status) => {
  const statusMap = {
    [TaskStatus.PROCESSING]: '处理中',
    [TaskStatus.SUCCESS]: '已完成',
    [TaskStatus.ERROR]: '失败',
    [TaskStatus.PENDING]: '等待中'
  }
  return statusMap[status] || status
}

const addTask = (task) => {
  tasks.value.push({
    id: Date.now(),
    ...task,
    status: TaskStatus.PENDING,
    progress: 0
  })
  isMinimized.value = false
}

const updateTask = ({ taskId, updates }) => {
  const task = tasks.value.find(t => t.id === taskId)
  if (task) {
    Object.assign(task, updates)
    // 如果任务失败，自动展开通知
    if (updates.status === TaskStatus.ERROR) {
      isMinimized.value = false
    }
  }
}

const removeTask = (taskId) => {
  const index = tasks.value.findIndex(t => t.id === taskId)
  if (index !== -1) {
    tasks.value.splice(index, 1)
  }
}

onMounted(() => {
  TaskManager.onAddTask(addTask)
  TaskManager.onUpdateTask(updateTask)
  TaskManager.onRemoveTask(removeTask)
})

onBeforeUnmount(() => {
  TaskManager.offAddTask(addTask)
  TaskManager.offUpdateTask(updateTask)
  TaskManager.offRemoveTask(removeTask)
})
</script>

<style scoped>
.task-notification {
  position: fixed;
  bottom: 20px;
  right: 20px;
  width: 300px;
  background: var(--el-bg-color);
  border-radius: 8px;
  box-shadow: 0 2px 12px rgba(0, 0, 0, 0.15);
  z-index: 1000;
  transition: all 0.3s ease;
  font-size: 14px;
}

.task-notification-header {
  padding: 12px;
  background-color: var(--el-color-primary-light-9);
  border-radius: 8px;
  cursor: pointer;
  display: flex;
  justify-content: space-between;
  align-items: center;
  transition: all 0.3s ease;
}

.task-notification-header.has-error {
  background-color: var(--el-color-danger-light-9);
}

.task-icon {
  color: var(--el-color-primary);
  transition: color 0.3s ease;
}

.task-icon.has-error {
  color: var(--el-color-danger);
}

.task-notification-content {
  padding: 4px 8px;
  max-height: 300px;
  overflow-y: auto;
}

.task-item {
  margin-bottom: 8px;
  padding: 8px;
  border-radius: 4px;
  background: var(--el-bg-color-overlay);
  display: flex;
  flex-direction: column;
  gap: 4px;
  border: 1px solid transparent;
  transition: all 0.3s ease;
}

.task-item.task-failed {
  background: var(--el-color-danger-light-9);
  border-color: var(--el-color-danger-light-5);
}

.task-item.task-success {
  background: var(--el-color-success-light-9);
  border-color: var(--el-color-success-light-5);
}

.task-item.task-processing {
  background: var(--el-color-primary-light-9);
  border-color: var(--el-color-primary-light-5);
}

.task-item.task-pending {
  background: var(--el-bg-color-overlay);
  border-color: var(--el-border-color);
}

.task-info {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.task-name {
  font-size: 14px;
  color: var(--el-text-color-primary);
  flex: 1;
}

.task-status {
  flex-shrink: 0;
  font-size: 12px;
  padding: 2px 6px;
  border-radius: 10px;
}

.task-status.processing {
  background: var(--el-color-primary-light-8);
  color: var(--el-color-primary);
}

.task-status.success {
  background: var(--el-color-success-light-8);
  color: var(--el-color-success);
}

.task-status.error {
  background: var(--el-color-danger-light-8);
  color: var(--el-color-danger);
}

.task-status.pending {
  background: var(--el-bg-color-overlay);
  color: var(--el-text-color-secondary);
}

.task-error {
  font-size: 12px;
  color: var(--el-color-danger);
  margin-top: 4px;
  word-break: break-all;
}

.progress-bar {
  height: 4px;
  background: var(--el-border-color-lighter);
  border-radius: 2px;
  overflow: hidden;
  margin-top: 4px;
}

.progress {
  height: 100%;
  background: var(--el-color-primary);
  transition: width 0.3s ease;
}

.progress-text {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  margin-top: 4px;
  display: block;
}

.is-minimized .task-notification-content {
  display: none;
}

.task-notification-minimized {
  width: auto;
  background-color: transparent !important;
  padding: 0;
  box-shadow: unset !important;
}

/* 暗色主题适配 */
.task-notification.is-dark {
  background: var(--el-bg-color-overlay);
}

.task-notification.is-dark .task-notification-header {
  background: var(--el-bg-color);
}

.task-notification.is-dark .task-notification-header.has-error {
  background: var(--el-color-danger-dark-2);
}

.task-notification.is-dark .task-item {
  background: var(--el-bg-color);
}

.task-notification.is-dark .task-item.task-failed {
  background: var(--el-color-danger-dark-2);
  border-color: var(--el-color-danger);
}

.task-notification.is-dark .task-item.task-success {
  background: var(--el-color-success-dark-2);
  border-color: var(--el-color-success);
}

.task-notification.is-dark .task-item.task-processing {
  background: var(--el-color-primary-dark-2);
  border-color: var(--el-color-primary);
}

.task-notification.is-dark .task-item.task-pending {
  background: var(--el-bg-color);
  border-color: var(--el-border-color-darker);
}

.task-notification.is-dark .task-status.processing {
  background: var(--el-color-primary-dark-2);
  color: var(--el-color-primary-light-3);
}

.task-notification.is-dark .task-status.success {
  background: var(--el-color-success-dark-2);
  color: var(--el-color-success-light-3);
}

.task-notification.is-dark .task-status.error {
  background: var(--el-color-danger-dark-2);
  color: var(--el-color-danger-light-3);
}

.task-notification.is-dark .task-status.pending {
  background: var(--el-bg-color);
  color: var(--el-text-color-secondary);
}

.task-notification.is-dark .task-error {
  color: var(--el-color-danger-light-3);
}
</style>