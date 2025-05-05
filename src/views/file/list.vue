<script setup>

import ContainerPage from "@/components/ContainerPage.vue";
import {getFileListApi} from "@/api/file.js";

const page = reactive({
  dataList: [],
})

const getList = async () => {
  const {data} = await getFileListApi()
  console.log('getList',data)
  page.dataList = data
}

onMounted(() => {
  getList()
})

</script>

<template>
<ContainerPage>
  <el-table
    :data="page.dataList" border stripe>
    <el-table-column prop="name" label="文件名" />
    <el-table-column prop="size" label="文件分类" />
    <el-table-column prop="type" label="文件类型" />
    <el-table-column prop="type" label="哈希值" />
    <el-table-column prop="type" label="状态" />
    <el-table-column prop="createTime" label="创建时间" />
  </el-table>
</ContainerPage>
</template>

<style scoped>

</style>