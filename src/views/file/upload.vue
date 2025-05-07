<script setup>

import ContainerPage from "@/components/ContainerPage.vue";
import MenuIcon from "@/components/Icon/MenuIcon.vue";
import {useMessage} from "@/utils/message.js";
import {ChunkedUploader} from './ChunkedUploader.js'
import {useAuthorization} from "@/composables/authorization.js";
import {buildFile, formatSize, generateUUID} from '@/utils/file.js'
import {markRaw} from "vue";
import {useStorage} from "@vueuse/core";
import {dayjs} from "element-plus";

const baseUrl = import.meta.env.VITE_BASE_URL
const {token,monitorAuth} = useAuthorization()
const page = reactive({
  loading: false,
  // 文件上传限制
  allow: {
    // 允许上传文件格式
    suffix: ['jpg', 'jpeg', 'png', 'gif', 'pdf', 'doc', 'docx', 'xls', 'xlsx', 'ppt', 'pptx', 'txt', 'zip', 'rar', '7z'],
    // 允许上传文件大小
    size: 1024 * 1024 * 1024 * 4, // 4G
  },
  // 分片上传分片大小
  chunkSize: 1024 * 1024 * 2,// 2M
  // 上传状态
  uploadStatus: 'idle', // idle, uploading, paused, completed，cancel
  // 上传历史
  uploadHistory: [],
  // 上传进度
  percentage: 0,
})
// 分片上传实例
const chunkedUploader = ref(null)
// 状态计算
const canStart = computed(() => page.uploadStatus === 'idle' && chunkedUploader.value)
const canPause = computed(() => page.uploadStatus === 'uploading')
const canResume = computed(() => page.uploadStatus === 'paused')
const canCancel = computed(() => ['uploading', 'paused'].includes(page.uploadStatus))
const renderStatus = computed(()=>{
  switch (page.uploadStatus) {
    case 'idle':
      return ['待上传', 'warning']
    case 'uploading':
      return ['上传中', 'primary']
    case 'paused':
      return ['暂停中', 'danger']
    case 'completed':
      return ['上传完成', 'success']
    default:
      return ['未知状态', 'info']
  }
})
/**
 * 上传文件前校验
 * @param file
 * @returns {boolean}
 */
const beforeUpload = (file) => {
  // 文件大小校验
  if (file.size > page.allow.size) {
    useMessage().error('上传文件大小不能超过4G')
    console.log('文件大小超出限制')
    return false
  }
  // 文件格式校验
  if (!page.allow.suffix?.includes(file.name.split('.').pop())) {
    useMessage().error('上传文件格式不支持')
    console.log('文件格式不支持', file.name)
    return false
  }
  // 初始化分片上传实例
  initUploader(file)
  return false
}

const initUploader = (file, uploadId) => {
  chunkedUploader.value = markRaw(new ChunkedUploader({
    file,
    chunkSize: page.chunkSize,
    concurrency: 3,
    onProgress: handleUploadProgress,
    uploadId: uploadId || null,
    api: {
      // start pause resume cancel uploadChunk complete
      start: handleStartApi,
      pause: handlePauseApi,
      resume: handleResumeApi,
      cancel: handleCancelApi,
      uploadChunk: handleUploadChunkApi,
      complete: handleCompleteApi
    }
  }))
  page.percentage = 0
  console.log('分片上传实例', toRaw(chunkedUploader.value))
}

/**
 * 开始上传Api
 * @param pms
 * @returns {Promise<{uploadId: *}>}
 */
const handleStartApi = async (pms) => {
  const response = await fetch(`${baseUrl}/file/uploader/upload/start`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/x-www-form-urlencoded',
      'Authorization': `Bearer ${token.value}`
    },
    body: new URLSearchParams({
      fileName: pms.name,
      fileSize: pms.size,
      contentType: pms.type,
      totalChunks: pms.totalChunks,
      chunkSize: pms.chunkSize,
    })
  })

  console.log('开始上传', response)
  // 检查响应
  if (!response.ok) {
    const errorText = await response.text();
    throw new Error(`[${response.status}] ${errorText}`);
  }
  const res = await response.json();
  console.log('开始上传', res)
  if (res.code !== 1){
    throw new Error(res.message)
  }
  return {uploadId: res.data.clientId}
}
/**
 * 暂停上传Api
 * @param pms
 * @returns {Promise<void>}
 */
const handlePauseApi = async (pms) => {
  const response = await fetch(`${baseUrl}/file/uploader/upload/pause`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/x-www-form-urlencoded',
      'Authorization': `Bearer ${token.value}`
    },
    body: new URLSearchParams({
      clientId: pms.uploadId,
    })
  })
  console.log('暂停上传', response)
  // 检查响应
  if (!response.ok) {
    const errorText = await response.text();
    throw new Error(`[${response.status}] ${errorText}`);
  }
  const res = await response.json();
  console.log('暂停上传', res)
  if (res.code !== 1){
    throw new Error(res.message)
  }
}
/**
 * 恢复上传Api
 * @param pms
 * @returns {Promise<{uploadedChunks: *, totalChunks: *}>}
 */
const handleResumeApi = async (pms) => {
  const response = await fetch(`${baseUrl}/file/uploader/upload/resume`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/x-www-form-urlencoded',
      'Authorization': `Bearer ${token.value}`
    },
    body: new URLSearchParams({
      clientId: pms.uploadId,
    })
  })
  console.log('恢复上传', response)
  // 检查响应
  if (!response.ok) {
    const errorText = await response.text();
    throw new Error(`[${response.status}] ${errorText}`);
  }
  const res = await response.json();
  console.log('恢复上传', res)
  if (res.code !== 1){
    throw new Error(res.message)
  }
  return {uploadedChunks : res.data.processedChunks, totalChunks: res.data.totalChunks}
}
/**
 * 完成上传Api
 * @param pms
 * @returns {Promise<void>}
 */
const handleCompleteApi = async (pms) => {
  const response = await fetch(`${baseUrl}/file/uploader/upload/complete`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/x-www-form-urlencoded',
      'Authorization': `Bearer ${token.value}`
    },
    body: new URLSearchParams({
      clientId: pms.uploadId,
    })
  })
  const fileTemp = chunkedUploader.value.file
  const itemTemp = {
    file: fileTemp,
    clientId: pms.uploadId,
    status: 'success',
    createTime: dayjs().format('YYYY-MM-DD HH:mm:ss'),
  }
  console.log('完成上传', response)
  // 检查响应
  if (!response.ok) {
    itemTemp.status  = 'danger'
    page.uploadHistory.push(itemTemp)
    const errorText = await response.text();
    throw new Error(`[${response.status}] ${errorText}`);
  }
  const res = await response.json();
  console.log('完成上传', res)
  if (res.code !== 1){
    itemTemp.status  = 'danger'
    page.uploadHistory.push(itemTemp)
    throw new Error(res.message)
  }
  page.uploadStatus = 'completed'
  page.uploadHistory.push(itemTemp)
  // // localStorage.setItem('uploadHistory', JSON.stringify(page.uploadHistory))
  // const list = JSON.parse(localStorage.getItem('fileList') || '[]')
  // list.push({
  //   clientId: itemTemp.clientId,
  //   createTime: itemTemp.createTime,
  //   fileName: itemTemp.file.name,
  //   fileSize: formatSize(itemTemp.file.size),
  //   fileType: itemTemp.file.type,
  //   status: itemTemp.status,
  // })
  // localStorage.setItem('fileList', JSON.stringify(list))
}
/**
 * 取消上传Api
 * @param pms
 * @returns {Promise<void>}
 */
const handleCancelApi = async (pms) => {
  const response = await fetch(`${baseUrl}/file/uploader/upload/cancel`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/x-www-form-urlencoded',
      'Authorization': `Bearer ${token.value}`
    },
    body: new URLSearchParams({
      clientId: pms.uploadId,
    })
  })
  console.log('取消上传', response)
  // 检查响应
  if (!response.ok) {
    const errorText = await response.text();
    throw new Error(`[${response.status}] ${errorText}`);
  }
  const res = await response.json();
  console.log('取消上传', res)
  if (res.code !== 1){
    throw new Error(res.message)
  }
}
/**
 * 分片上传Api
 * @param pms
 * @returns {Promise<void>}
 */
const handleUploadChunkApi = async (pms) => {
  const formData = new FormData()
  formData.append('file', pms.chunkData)
  formData.append('clientId', pms.uploadId)
  formData.append('chunkNumber', pms.chunkIndex)


  const response = await fetch(`${baseUrl}/file/uploader/upload/chunk`, {
    method: 'POST',
    headers: {
      // 'Content-Type': 'multipart/form-data',
      'Authorization': `Bearer ${token.value}`
    },
    body: formData
  })
  console.log('分片上传', response)
  // 检查响应
  if (!response.ok) {
    const errorText = await response.text();
    throw new Error(`[${response.status}] ${errorText}`);
  }
  const res = await response.json();
  console.log('分片上传', res)
  if (res.code !== 1){
    throw new Error(res.message)
  }
}

/**
 * 上传进度回调
 * @param total
 * @param uploaded
 * @param percentage
 */
const handleUploadProgress = ({total, uploaded, percentage}) => {
  console.log('上传进度', total, uploaded, percentage)
  page.percentage = parseFloat(percentage)
}

/**
 * 开始上传按钮点击事件
 * @returns {Promise<void>}
 */
const start = async () => {
  try {
    page.loading  = true
    await chunkedUploader.value?.start()
    useMessage().success('开始上传')
    console.log('开始上传')
    page.uploadStatus = 'uploading'
  }finally {
    page.loading  = false
  }
}
/**
 * 暂停上传按钮点击事件
 * @returns {Promise<void>}
 */
const pause = async () => {
  try {
    page.loading  = true
    await chunkedUploader.value?.pause()
    useMessage().success('暂停上传成功')
    console.log('暂停上传成功')
    page.uploadStatus = 'paused'
  }finally {
    page.loading  = false
  }
}
/**
 * 恢复上传按钮点击事件
 * @returns {Promise<void>}
 */
const resume = async () => {
  try {
    page.loading  = true
    await chunkedUploader.value?.resume()
    useMessage().success('暂停上传成功')
    console.log('暂停上传成功')
    page.uploadStatus = 'paused'
  }finally {
    page.loading  = false
  }
}
/**
 * 取消上传按钮点击事件
 * @returns {Promise<void>}
 */
const cancel = async () => {
  try {
    page.loading  = true
    await chunkedUploader.value?.cancel()
    useMessage().success('取消上传成功')
    console.log('取消上传成功')
    page.uploadStatus = 'idle'
    chunkedUploader.value = null
  }finally {
    page.loading  = false
  }
}
/**
 * 重试上传按钮点击事件
 * @param row
 * @param $index
 * @returns {Promise<void>}
 */
const retry = async ({row, $index}) => {
  if(!row || !row.file) return
  chunkedUploader.value = null
  page.uploadHistory.splice($index, 1)
  initUploader(row.file, row.clientId)
  await resume()
}

</script>

<template>
  <ContainerPage>
    <div class="flex flex-col" >
     <el-card>
       <el-upload
         class="upload-demo"
         drag
         :before-upload="beforeUpload"
       >
         <el-icon class="el-icon--upload">
           <MenuIcon icon="upload" />
         </el-icon>
         <div class="">
           <span class="font-bold" >将文件拖放到此处或 <em class="font-bold text-blue">点击选择文件</em></span>
           <p class="text-gray-500">
             支持扩展名：jpg, jpeg, png, gif, pdf, doc, docx, xls, xlsx, ppt, pptx, txt, zip, rar, 7z
           </p>
         </div>
       </el-upload>

       <div class="w-full box-border p-3 flex gap-5" v-if="chunkedUploader" >
         <div class="grow-1" >
           <el-descriptions :column="3">
             <el-descriptions-item label="文件名">
               {{ chunkedUploader.file.name || '-' }}
             </el-descriptions-item>
             <el-descriptions-item label="类型">
               {{  chunkedUploader.file.type ||'-' }}
             </el-descriptions-item>
             <el-descriptions-item label="状态">
               <el-tag :type="renderStatus[1]">{{renderStatus[0] || '-' }}</el-tag>
             </el-descriptions-item>
             <el-descriptions-item label="文件大小">
               {{ formatSize(chunkedUploader.file.size || 0) }}
             </el-descriptions-item>
             <el-descriptions-item label="分片大小">
               {{ chunkedUploader.chunkSize || 0 }}
             </el-descriptions-item>
             <el-descriptions-item label="分片数量">
               {{ chunkedUploader.totalChunks || 0 }}
             </el-descriptions-item>
           </el-descriptions>
         </div>
         <div class="shrink-0 w-[400px] flex flex-col gap-3" >
           <!-- 进度条 -->
           <el-progress
             :percentage="page.percentage"
             :status="page.uploadStatus === 'completed' ? 'success' : undefined"
             striped />

           <!-- 控制按钮 -->
           <div class="flex items-center justify-between pr-5">
             <el-button :loading="page.loading" type="primary" @click="start" :disabled="!canStart">开始</el-button>
             <el-button :loading="page.loading" type="danger" @click="pause" :disabled="!canPause">暂停</el-button>
             <el-button :loading="page.loading" type="warning" @click="resume" :disabled="!canResume">继续</el-button>
             <el-button :loading="page.loading" type="info" @click="cancel" :disabled="!canCancel">取消</el-button>
           </div>
         </div>
       </div>
     </el-card>

      <div class="flex-center gap-3" >
        <el-divider/>
        <span class="shrink-0 text-[16px] " >上传历史</span>
        <el-divider/>
      </div>

      <el-table :data="page.uploadHistory" border>
        <el-table-column align="center" label="clientId" prop="clientId"/>
        <el-table-column align="center" label="文件名">
          <template #default="{row}">
            {{row?.file?.name}}
          </template>
        </el-table-column>
        <el-table-column align="center" label="文件大小" >
          <template #default="{row}">
            {{formatSize(row?.file?.size || 0)}}
          </template>
        </el-table-column>
        <el-table-column align="center" label="文件类型">
          <template #default="{row}">
            {{row?.file?.type}}
          </template>
        </el-table-column>
        <el-table-column align="center" label="状态" prop="status">
          <template #default="scope">
            <el-tag :type="scope.row.status">{{ scope.row.status === 'success' ? '成功' : '失败' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column align="center" label="操作" prop="action">
          <template #default="scope">
            <el-button v-if="scope.row.status !== 'success'" type="warning" @click="retry(scope)" >重试</el-button>
          </template>
        </el-table-column>
      </el-table>

    </div>
  </ContainerPage>
</template>

<style scoped>

</style>


