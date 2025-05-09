<script setup>
import { getLogDetailApi } from "@/api/audit.js";
import { onMounted, reactive } from "vue";
import { useRoute, useRouter } from "vue-router";
import ContainerPage from "@/components/ContainerPage.vue";
import { ElMessage } from "element-plus";

const route = useRoute();
const router = useRouter();
const logId = route.params.id;

const logDetail = reactive({
  loading: false,
  data: null
});

const fetchLogDetail = () => {
  logDetail.loading = true;
  getLogDetailApi(logId)
    .then(response => {
      // console.log('日志详情API调用成功解析 (then block):', response); // Debugging
      if (response && response.code === 1 && response.data) {
        logDetail.data = response.data;
      } else if (response && typeof response === 'object' && !response.code && response.id !== undefined) {
        logDetail.data = response;
      } else {
        console.error('日志详情API调用成功解析但响应格式无法识别 (then block):', response);
        logDetail.data = null;
        ElMessage.error('获取日志详情失败: 响应格式不正确');
      }
    })
    .catch(apiErrorOrData => {
      if (apiErrorOrData && typeof apiErrorOrData === 'object' && !apiErrorOrData.code && apiErrorOrData.id !== undefined) {
        // 预期的"成功"数据通过catch块传递
        console.log('日志详情捕获的对象被识别为对象数据 (catch block):', apiErrorOrData);
        logDetail.data = apiErrorOrData;
      } else {
        // 真正的错误
        console.warn('日志详情API调用中捕获到非预期的对象或错误 (catch block):', apiErrorOrData);
        console.error('获取日志详情失败 (catch block - 未能识别为对象数据):', apiErrorOrData);
        logDetail.data = null;
        const errorMessage = typeof apiErrorOrData === 'object' && apiErrorOrData !== null && apiErrorOrData.message ? apiErrorOrData.message : '未知错误';
        ElMessage.error(`获取日志详情失败: ${errorMessage}`);
      }
    })
    .finally(() => {
      logDetail.loading = false;
      // console.log('fetchLogDetail 完成. 日志详情数据:', logDetail.data); // Debugging
    });
};

const goBack = () => {
  router.push({ name: 'auditLogs' });
};

const formatStatus = (status) => {
  switch(status) {
    case 'SUCCESS':
      return ['成功', 'success'];
    case 'FAILED':
      return ['失败', 'danger'];
    default:
      return [status, 'info'];
  }
};

onMounted(() => {
  if (logId) {
    fetchLogDetail();
  }
});
</script>

<template>
  <ContainerPage>
    <template #title>
      <div class="flex items-center">
        <el-button style="margin-left: 20px" type="success" plain @click="goBack">返回</el-button>
      </div>
    </template>
    
    <el-card v-loading="logDetail.loading">
      <div v-if="logDetail.data">
        <el-descriptions :column="2" border>
          <el-descriptions-item label="日志ID">{{ logDetail.data.id }}</el-descriptions-item>
          <el-descriptions-item label="用户名">{{ logDetail.data.username }}</el-descriptions-item>
          <el-descriptions-item label="IP地址">{{ logDetail.data.requestIp }}</el-descriptions-item>
          <el-descriptions-item label="操作时间">{{ logDetail.data.operationTime }}</el-descriptions-item>
          <el-descriptions-item label="操作类型">{{ logDetail.data.operationType }}</el-descriptions-item>
          <el-descriptions-item label="状态">
            <el-tag :type="formatStatus(logDetail.data.status)[1]">
              {{ formatStatus(logDetail.data.status)[0]===0 ? '成功' : '失败'}}
            </el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="操作内容" :span="2">
            {{ logDetail.data.description }}
          </el-descriptions-item>
          <el-descriptions-item label="操作模块" :span="2">
            <pre class="whitespace-pre-wrap break-all p-3 bg-gray-100 rounded">{{ logDetail.data.module }}</pre>
          </el-descriptions-item>
          <el-descriptions-item v-if="logDetail.data.errorMessage" label="错误信息" :span="2">
            <pre class="whitespace-pre-wrap break-all p-3 bg-red-50 text-red-500 rounded">{{ logDetail.data.errorMessage }}</pre>
          </el-descriptions-item>
        </el-descriptions>
      </div>
      <el-empty v-else description="未找到日志详情" />
    </el-card>
  </ContainerPage>
</template>

<style scoped>
/* 自定义样式 */
</style> 