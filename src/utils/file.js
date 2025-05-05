/**
 * @description 创建文件切片
 * @param file
 * @param chunkSize
 * @returns {*[]}
 */
export function createChunks (file, chunkSize) {
  const chunks = []
  for (let i = 0; i < file.size; i += chunkSize) {
    chunks.push(file.slice(i, i + chunkSize))
  }
  return chunks
}

/**
 * 生成一个符合 UUID v4 规范的字符串。
 * @returns {string} UUID字符串
 */
export function generateUUID() {
  // 标准的 UUID v4 生成算法
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, function(c) {
    const r = Math.random() * 16 | 0;
    const v = c === 'x' ? r : (r & 0x3 | 0x8);
    return v.toString(16);
  });
}

// 格式化工具
export function formatSize(bytes) {
  const units = ['B', 'KB', 'MB', 'GB']
  let size = bytes
  let unitIndex = 0
  while (size >= 1024 && unitIndex < units.length - 1) {
    size /= 1024
    unitIndex++
  }
  return `${size.toFixed(2)} ${units[unitIndex]}`
}
