// src/utils/message.js

import { ElMessage, ElMessageBox } from 'element-plus'

// ===================== Message 配置 =====================
const messageDefaults = {
  duration: 3000,
  showClose: true
}

const message = {
  success: (msg, options = {}) => {
    console.log('message-success', msg, options)
    return ElMessage({ ...messageDefaults, ...options, message: msg, type: 'success' })
  },
  error: (msg, options = {}) => {
    console.log('message-error', msg, options)
    return ElMessage({ ...messageDefaults, ...options, message: msg, type: 'error' })
  },
  warning: (msg, options = {}) => {
    console.log('message-warning', msg, options)
    return ElMessage({ ...messageDefaults, ...options, message: msg, type: 'warning' })
  },
  info: (msg, options = {}) => {
    console.log('message-info', msg, options)
    return ElMessage({ ...messageDefaults, ...options, message: msg, type: 'info' })
  }
}

// ===================== MessageBox 配置 =====================
const messageBoxDefaults = {
  confirmButtonText: '确定',
  cancelButtonText: '取消',
  type: 'warning'
}

const messageBox = {
  prompt: (content, title = '提示', options = {}) => {
    console.log('messageBox-prompt', content, title, options)
    return ElMessageBox.prompt(
      content,
      title,
      { ...messageBoxDefaults, ...options }
    )
  },
  confirm: (content, title = '提示', options = {}) => {
    console.log('messageBox-confirm', content, title, options)
    return ElMessageBox.confirm(
      content,
      title,
      { ...messageBoxDefaults, ...options }
    )
  },
  alert: (content, title = '提示', options = {}) => {
    console.log('messageBox-alert', content, title, options)
    return ElMessageBox.alert(
      content,
      title,
      { confirmButtonText: '确定', ...options }
    )
  },
  vnode: (message, title = '提示', options = {}) => {
    console.log('messageBox-vnode', message, title, options)
    return ElMessageBox({
      message,
      title,
      ...options
    })
  }
}

// ===================== 可选全局导出对象 =====================
export const useMessage = () => message
export const useMessageBox = () => messageBox
export default {
  message,
  messageBox,
}