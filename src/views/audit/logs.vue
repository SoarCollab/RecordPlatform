<script setup>
import { getUserLogsApi } from "@/api/audit.js";
import { onMounted, reactive } from "vue";
import ContainerPage from "@/components/ContainerPage.vue";
import { useRouter } from "vue-router";
import { ElMessage } from "element-plus";

const router = useRouter();
const page = reactive({
  loading: false,
  dataList: [],
  searchParams: {
    username: '',
    operationType: '',
    startTime: '',
    endTime: ''
  },
  pagination: {
    pageNum: 1,
    pageSize: 10,
    total: 0
  }
});

const operationTypeOptions = [
  { value: 'LOGIN', label: '登录' },
  { value: 'LOGOUT', label: '登出' },
  { value: 'UPLOAD', label: '上传文件' },
  { value: 'DOWNLOAD', label: '下载文件' },
  { value: 'DELETE', label: '删除文件' },
  { value: 'SHARE', label: '分享文件' },
  { value: 'ERROR', label: '操作错误' }
];

const getList = () => {
  page.loading = true;
  console.log('请求参数:', {
    ...page.searchParams,
    pageNum: page.pagination.pageNum,
    pageSize: page.pagination.pageSize
  });

  getUserLogsApi({
    ...page.searchParams,
    pageNum: page.pagination.pageNum,
    pageSize: page.pagination.pageSize
  })
  .then(response => {
    console.log('API调用成功解析，接口完整返回 (then block):', response);
    if (response && response.code === 1 && response.data) {
      const data = response.data;
      console.log('接口数据部分 (then block - code 1):', data);
      page.dataList = data.records || [];
      page.pagination.total = parseInt(data.total || 0);
    } else if (response && response.records !== undefined && response.total !== undefined) {
      console.log('接口直接返回数据 (then block - no code 1, has records):', response);
      page.dataList = response.records || [];
      page.pagination.total = parseInt(response.total || 0);
    } else {
      console.error('API调用成功解析但响应格式无法识别 (then block):', response);
      page.dataList = [];
      page.pagination.total = 0;
      ElMessage.error('获取用户日志失败: 响应格式不正确');
    }
  })
  .catch(apiErrorOrData => {
    if (apiErrorOrData && apiErrorOrData.records !== undefined && apiErrorOrData.total !== undefined) {
      console.log('捕获的对象被识别为数据，将使用它 (catch block):', apiErrorOrData);
      page.dataList = apiErrorOrData.records || [];
      page.pagination.total = parseInt(apiErrorOrData.total || 0);
    } else {
      console.warn('API调用中捕获到非预期的对象或错误 (catch block):', apiErrorOrData);
      console.error('获取用户日志列表失败 (catch block - 未能识别为数据):', apiErrorOrData);
      page.dataList = [];
      page.pagination.total = 0;
      const errorMessage = typeof apiErrorOrData === 'object' && apiErrorOrData !== null && apiErrorOrData.message ? apiErrorOrData.message : '未知错误';
      ElMessage.error(`获取用户日志列表失败: ${errorMessage}`);
    }
  })
  .finally(() => {
    page.loading = false;
  });
};

const handleSearch = () => {
  page.pagination.pageNum = 1;
  getList();
};

const resetSearch = () => {
  page.searchParams = {
    username: '',
    operationType: '',
    startTime: '',
    endTime: ''
  };
  page.pagination.pageNum = 1;
  getList();
};

const handlePageChange = (newPage) => {
  page.pagination.pageNum = newPage;
  getList();
};

const viewDetail = (row) => {
  router.push({
    name: 'auditDetail',
    params: { id: row.id }
  });
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
  getList();
  console.info(page)
});
</script>

<template>
  <ContainerPage>
    <template #title>操作日志</template>
    
<!--    <el-form :model="page.searchParams" inline class="mb-4">-->
<!--      <el-form-item label="用户名">-->
<!--        <el-input v-model="page.searchParams.username" placeholder="请输入用户名" clearable />-->
<!--      </el-form-item>-->
<!--      <el-form-item label="操作类型">-->
<!--        <el-select v-model="page.searchParams.operationType" placeholder="请选择操作类型" clearable>-->
<!--          <el-option v-for="item in operationTypeOptions" :key="item.value" :label="item.label" :value="item.value" />-->
<!--        </el-select>-->
<!--      </el-form-item>-->
<!--      <el-form-item label="操作时间">-->
<!--        <el-date-picker-->
<!--          v-model="page.searchParams.startTime"-->
<!--          type="datetime"-->
<!--          placeholder="开始时间"-->
<!--          value-format="YYYY-MM-DD"-->
<!--        />-->
<!--        <span class="mx-2">至</span>-->
<!--        <el-date-picker-->
<!--          v-model="page.searchParams.endTime"-->
<!--          type="datetime"-->
<!--          placeholder="结束时间"-->
<!--          value-format="YYYY-MM-DD"-->
<!--        />-->
<!--      </el-form-item>-->
<!--      <el-form-item>-->
<!--        <el-button type="primary" @click="handleSearch">查询</el-button>-->
<!--        <el-button @click="resetSearch">重置</el-button>-->
<!--      </el-form-item>-->
<!--    </el-form>-->
    
    <el-table :data="page.dataList" v-loading="page.loading" border stripe>
      <el-table-column align="center" prop="id" label="日志ID" width="80" />
      <el-table-column align="center" prop="username" label="用户名" />
      <el-table-column align="center" prop="operationType" label="操作类型">
        <template #default="{ row }">
          {{ operationTypeOptions.find(item => item.value === row.operationType)?.label || row.operationType }}
        </template>
      </el-table-column>
      <el-table-column align="center" prop="description" label="操作内容" :show-overflow-tooltip="true" />
      <el-table-column align="center" prop="operationTime" label="操作时间" />
      <el-table-column align="center" prop="status" label="状态">
        <template #default="{ row }">
          <el-tag :type="formatStatus(row.status)[1]">{{ formatStatus(row.status)[0] === 0 ? '成功' : '失败' }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column align="center" label="操作" width="120">
        <template #default="{ row }">
          <el-button type="primary" link @click="viewDetail(row)">查看详情</el-button>
        </template>
      </el-table-column>
    </el-table>
    
    <div class="flex justify-end mt-4">
      <el-pagination
        v-model:current-page="page.pagination.pageNum"
        v-model:page-size="page.pagination.pageSize"
        :total="page.pagination.total"
        :page-sizes="[10, 20, 50, 100]"
        layout="total, sizes, prev, pager, next, jumper"
        @size-change="getList"
        @current-change="handlePageChange"
      />
    </div>
  </ContainerPage>
</template>

<style scoped>
/* 自定义样式 */
</style> 