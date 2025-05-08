import mitt from 'mitt'

export const TaskStatus = {
  PENDING: 'pending',
  PROCESSING: 'processing',
  SUCCESS: 'success',
  ERROR: 'error'
}

// 创建 mitt 事件总线
const emitter = mitt()

export const TaskManager = {
  addTask(task) {
    const id = task.id || Date.now() + Math.random();
    emitter.emit('add-task', { ...task, id });
    return id;
  },

  updateTask(taskId, updates) {
    emitter.emit('update-task', { taskId, updates })
  },

  removeTask(taskId) {
    emitter.emit('remove-task', taskId)
  },

  onAddTask(callback) {
    emitter.on('add-task', callback)
  },

  onUpdateTask(callback) {
    emitter.on('update-task', callback)
  },

  onRemoveTask(callback) {
    emitter.on('remove-task', callback)
  },

  // 清理监听器
  offAddTask(callback) {
    emitter.off('add-task', callback)
  },

  offUpdateTask(callback) {
    emitter.off('update-task', callback)
  },

  offRemoveTask(callback) {
    emitter.off('remove-task', callback)
  }
}

export default TaskManager 