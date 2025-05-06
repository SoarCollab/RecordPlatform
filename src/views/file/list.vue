<script setup>

import ContainerPage from "@/components/ContainerPage.vue";
import {getFileListApi} from "@/api/file.js";
import {useStorage} from "@vueuse/core";
import {Promotion, DeleteFilled, Download} from '@element-plus/icons-vue'

const page = reactive({
  loading: false,
  dataList: [],
})

const getList = async () => {
  try {
    page.loading = true
    const res = await getFileListApi()
    console.log('getList',res)
    // const {data} = res
    const data = JSON.parse(localStorage.getItem('fileList') || '[]')
    console.log('getList',data)
    page.dataList = data
  }finally {
    page.loading = false
  }
}

onMounted(() => {
  getList()
})

const handleDownload = (row, index) => {
  console.log('handleDownload',row, index)
}

const handleShare = (row, index) => {
  console.log('handleShare',row, index)
}

const handleDel = (row, index) => {
  console.log('handleDel',row, index)
  page.dataList.splice(index, 1)
  localStorage.setItem('fileList', JSON.stringify(page.dataList))
}

</script>

<template>
<ContainerPage>
  <el-table
    :loading="page.loading"
    :data="page.dataList"
    border stripe>
    <el-table-column align="center" prop="fileName" label="文件名" />
    <el-table-column align="center" prop="fileSize" label="文件分类" />
    <el-table-column align="center" prop="fileType" label="文件类型" />
<!--    <el-table-column prop="type" label="哈希值" />-->
<!--    <el-table-column prop="type" label="状态" />-->
    <el-table-column prop="createTime" label="创建时间" />
    <el-table-column align="center" width="300" label="操作" >
      <template #default="{row, $index}">
        <el-button text :icon="Download" type="primary" @click="handleDownload(row, $index)" >下载</el-button>
        <el-button text :icon="Promotion" type="success" @click="handleShare(row, $index)" >分享</el-button>

        <el-popconfirm
          title="你确认要删除此文件吗？删除后将无法恢复！"
          confirm-button-text="确认"
          confirm-button-type="danger"
          cancel-button-text="取消"
          cancel-button-type="primary"
          hide-icon
          @confirm="handleDel(row, $index)" >
          <template #reference>
            <el-button text :icon="DeleteFilled" type="danger">删除</el-button>
          </template>
        </el-popconfirm>
      </template>
    </el-table-column>
  </el-table>
</ContainerPage>
</template>

<style scoped>

</style>