<script setup>
import { getSharingFilesApi, saveShareFileApi } from "@/api/file";
import ContainerPage from "@/components/ContainerPage.vue";
import { formatSize } from "@/utils/file.js";
import { ElMessage } from "element-plus";

const formRef = ref(null);
const form = reactive({
  code: '',
});
const rules = reactive({
  code: [
    { required: true, message: '请输入分享码', trigger: 'blur' },
  ],
});
const shareList = ref([]);
const selectedRows = ref([]);

const handleGetFile = async () => {
  if (!formRef.value) return;
  
  try {
    await formRef.value.validate();
    getSharingFilesApi(form.code).then(res=>{
      console.log('getSharingFilesApi',res)
      if(res.data && res.data.length > 0) {
        shareList.value = res.data.map(it => {
          let fileParam = {}
          if(it.fileParam) {
            fileParam = JSON.parse(it.fileParam)
          }
          return {
            ...it,
            fileSize: formatSize(fileParam?.fileSize||0),
            fileType: fileParam?.contentType||'-',
          }
        })
      }
    })
  } catch (error) {
    console.error('表单验证失败:', error);
  }
}

// 格式化时间
const formatTime = (time) => {
  if (!time) return '-'
  return new Date(time).toLocaleString()
}

// 获取文件状态
const renderState = (st) => {
  switch (st) {
    case -2:
      return ['NOOP', 'warning']
    case -1:
      return ['上链失败', 'danger']
    case 0:
      return ['待上传', 'info']
    case 1:
      return ['上传成功', 'success']
    case 2:
      return ['已删除', 'info']
    default:
      return ['-', 'info']
  }
}

// 保存文件
const handleSave = (ids) => {
  if (!ids || ids.length === 0) {
    ElMessage.warning('请选择要保存的文件');
    return;
  }
  console.log('handleSave', ids);
  saveShareFileApi(ids).then(res=>{
    console.log('saveShareFileApi',res);
    ElMessage.success('保存成功');
  })
}

// 处理多选变化
const handleSelectionChange = (selection) => {
  selectedRows.value = selection;
}

// 批量保存
const handleBatchSave = () => {
  const ids = selectedRows.value.map(row => row.id);
  handleSave(ids);
}

</script>

<template>
<ContainerPage>
  <div class="flex flex-col items-center justify-center h-full" >
    <div class="w-[800px]" >
      <div class="flex-center text-[26px] font-bold mb-5" >
        通过分享码，获取分享的文件。
      </div>
      <el-form ref="formRef" :model="form" :rules="rules" size="large"  >
        <el-row :gutter="10" >
          <el-col :span="20">
            <el-form-item prop="code">
              <el-input v-model="form.code" placeholder="请输入分享码"></el-input>
            </el-form-item>
          </el-col>
          <el-col :span="4">
            <el-form-item>
              <el-button round type="primary" @click="handleGetFile" >提取文件</el-button>
            </el-form-item>
          </el-col>
        </el-row>
      </el-form>
    </div>
     <!-- 文件列表 -->
     <div v-if="shareList.length > 0" class="mt-8">
        <div class="mb-4">
          <el-button type="primary" @click="handleBatchSave" :disabled="selectedRows.length === 0">
            批量保存
          </el-button>
        </div>
        <el-table 
          height="250"
          :data="shareList" 
          style="width: 100%" 
          border 
          stripe
          @selection-change="handleSelectionChange"
        >
          <el-table-column type="selection" width="55" />
          <el-table-column align="center" prop="fileName" label="文件名" />
          <el-table-column align="center" prop="fileSize" label="文件分类" />
          <el-table-column align="center" prop="fileType" label="文件类型" />
          <el-table-column align="center" prop="fileHash" label="哈希值" />
          <el-table-column align="center" prop="status" label="状态">
            <template #default="{row}">
              <el-tag :type="renderState(row.status)[1]">{{renderState(row.status)[0]}}</el-tag>
            </template>
          </el-table-column>
          <el-table-column align="center" prop="createTime" label="创建时间">
            <template #default="{row}">
              {{ formatTime(row.createTime) }}
            </template>
          </el-table-column>
          <el-table-column align="center" width="120" label="操作">
            <template #default="{row}">
              <el-button type="primary" link @click="handleSave([row.id])">
                保存
              </el-button>
            </template>
          </el-table-column>
        </el-table>
      </div>
      <!-- 无数据提示 -->
      <el-empty v-else description="暂无分享文件" />
  </div>
</ContainerPage>
</template>

<style scoped>
.el-table {
  --el-table-border-color: var(--el-border-color-lighter);
  --el-table-header-bg-color: var(--el-fill-color-light);
}
</style>