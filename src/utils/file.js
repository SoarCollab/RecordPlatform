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

/**
 * 将 File 对象转换为 Base64
 * @param {File} file - 文件对象
 * @returns {Promise<string>} Base64 字符串
 */
export function fileToBase64(file) {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.readAsDataURL(file);
    reader.onload = () => resolve(reader.result.split(',')[1]); // 去掉 data URL 前缀
    reader.onerror = error => reject(error);
  });
}

/**
 * 将 Base64 字符串转换为 File 对象
 * @param {string} base64 - Base64 字符串
 * @param {string} filename - 文件名
 * @param {string} mimeType - MIME 类型
 * @returns {Promise<File>} File 对象
 */
export async function base64ToFile(base64, filename, mimeType) {
  const response = await fetch(`data:${mimeType};base64,${base64}`);
  const blob = await response.blob();
  return new File([blob], filename, { type: mimeType });
}

export async function buildFile (file) {

  return {
    // file: fileToBase64(file),
    fileName: file.name,
    fineType: file.type,
    fileSize: formatSize(file.size),
  }
}