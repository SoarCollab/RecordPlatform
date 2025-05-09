<script setup>

import ContainerPage from "@/components/ContainerPage.vue";
import { getFileListApi, getFileAddressApi, adminDeleteFileApi, generateShareCodeApi, getFileTransactionApi } from "@/api/file.js";
import { Promotion, DeleteFilled, Download, InfoFilled } from '@element-plus/icons-vue'
import { formatSize } from "@/utils/file.js";
import { decryptAndAssembleFile } from "@/utils/decrypt.js";
import TaskManager from "@/utils/taskNotification.js";
import { ElMessage } from "element-plus";
import { useMessage, useMessageBox } from "@/utils/message";
import { useClipboard } from "@vueuse/core";
import {ref, reactive, onMounted} from "vue"

const page = reactive({
  loading: false,
  dataList: [],
  pagination: {
    pageNum: 1,
    pageSize: 10,
    total: 0
  },
  selectedRows: []
})

// --- BEGIN: New state for Transaction Detail Dialog ---
const transactionDialogVisible = ref(false);
const currentTransaction = reactive({
  transactionHash: '',
  chainId: '',
  groupId: '',
  from: '',
  to: '',
  signature: '',
  timestamp: '',
  contractABI: '', // Raw ABI
  formattedABI: '', // Formatted ABI for display
  input: '' // Transaction input
});
const isABISectionExpanded = ref(false);
// --- END: New state for Transaction Detail Dialog ---

const getList = () => {
  page.loading = true;
  getFileListApi({
    pageNum: page.pagination.pageNum,
    pageSize: page.pagination.pageSize
  })
  .then(response => {
    let dataToProcess = response;
    if (response && response.code === 1 && response.data !== undefined) {
        dataToProcess = response.data;
    } else if (response && response.records === undefined && response.total === undefined){
        console.warn('getList (file/list): API response format not recognized or empty, using direct response:', response);
        if (!response || (typeof response === 'object' && Object.keys(response).length === 0)) {
             ElMessage.error('获取文件列表失败: 响应为空或格式不正确');
        }
    }

    page.pagination.total = parseInt(dataToProcess.total || 0);
    if (dataToProcess.records && dataToProcess.records.length > 0) {
      page.dataList = dataToProcess.records.map(it => {
        let fileParam = {};
        try { if (it.fileParam) fileParam = JSON.parse(it.fileParam); }
        catch (e) { console.error('Failed to parse fileParam for file:', it.fileName, e); fileParam = {}; }
        return { ...it, fileSize: formatSize(fileParam?.fileSize || 0), fileType: fileParam?.contentType || '-' };
      });
    } else {
      page.dataList = [];
    }
  })
  .catch(error => {
    console.error('getList (file/list) failed:', error);
    page.dataList = [];
    page.pagination.total = 0;
    const errorMessage = typeof error === 'object' && error !== null && error.message ? error.message : '未知错误';
    ElMessage.error(`获取文件列表失败: ${errorMessage}`);
  })
  .finally(() => {
    page.loading = false;
  });
};

onMounted(() => {
  getList();
});

const handleDownload = (row) => {
  const taskId = TaskManager.addTask({
    title: `下载文件: ${row.fileName}`,
    progress: 0
  });
  console.log('开始下载文件:', { fileName: row.fileName, fileHash: row.fileHash });

  getFileAddressApi(row.fileHash)
    .then(res => {
      if (!res || !res.data || !Array.isArray(res.data)) {
        throw new Error('获取文件分片地址失败：服务器响应无效或数据格式不正确');
      }
      const fileAddresses = res.data;
      if (fileAddresses.length === 0) {
        throw new Error('获取文件分片地址失败：地址列表为空');
      }
      TaskManager.updateTask(taskId, { status: 'processing', progress: 0 });
      return fetch(fileAddresses[fileAddresses.length - 1])
        .then(lastChunkResponse => {
          if (!lastChunkResponse.ok) {
            throw new Error(`下载最后一个分片失败: ${lastChunkResponse.status} ${lastChunkResponse.statusText}`);
          }
          return lastChunkResponse.arrayBuffer();
        })
        .then(lastChunkData => {
          const keySeparator = new TextEncoder().encode('\\n--NEXT_KEY--\\n');
          const keyIndex = findSequenceReverse(lastChunkData, keySeparator);
          if (keyIndex === -1) throw new Error('在最后一个分片中未找到密钥分隔符');
          const keyBase64 = new TextDecoder().decode(lastChunkData.slice(keyIndex + keySeparator.length)).trim();
          if (!isValidBase64(keyBase64)) throw new Error('提取的密钥不是有效的Base64格式');
          
          const chunkPromises = fileAddresses.map((address, i) => 
            fetch(address).then(response => {
              if (!response.ok) throw new Error(`下载分片 ${i + 1} 失败: ${response.status} ${response.statusText}`);
              TaskManager.updateTask(taskId, { progress: Math.round(((i + 1) / fileAddresses.length) * 100) });
              return response.arrayBuffer();
            })
          );
          return Promise.all(chunkPromises).then(chunks => ({ chunks, keyBase64 }));
        });
    })
    .then(({ chunks, keyBase64 }) => {
      return decryptAndAssembleFile(chunks, keyBase64);
    })
    .then(decryptedData => {
      if (!decryptedData) throw new Error('文件解密失败');
      const blob = new Blob([decryptedData], { type: 'application/octet-stream' });
      const url = URL.createObjectURL(blob);
      const link = document.createElement('a');
      link.href = url;
      link.download = row.fileName;
      document.body.appendChild(link);
      link.click();
      document.body.removeChild(link);
      URL.revokeObjectURL(url);
      TaskManager.updateTask(taskId, { status: 'success', progress: 100 });
    })
    .catch(error => {
      console.error('下载文件失败:', error);
      TaskManager.updateTask(taskId, { status: 'error', error: error.message });
      ElMessage.error(`文件下载失败: ${error.message}`);
    });
};

function isValidBase64(str) {
  try {
    const cleanStr = str.replace(/\\s/g, '');
    if (cleanStr.length % 4 !== 0) return false;
    if (!/^[A-Za-z0-9+/]*={0,2}$/.test(cleanStr)) return false;
    window.atob(cleanStr);
    return true;
  } catch (e) { return false; }
}

function findSequenceReverse(buffer, sequence) {
  const bufferView = new Uint8Array(buffer);
  const seqLen = sequence.length;
  for (let i = bufferView.length - seqLen; i >= 0; i--) {
    let found = true;
    for (let j = 0; j < seqLen; j++) {
      if (bufferView[i + j] !== sequence[j]) { found = false; break; }
    }
    if (found) return i;
  }
  return -1;
}

const handleShare = (row, index) => {
  useMessageBox().prompt('请输入最大分享次数', '分享文件', {
    confirmButtonText: '确定', cancelButtonText: '取消', type: 'warning', inputType: 'number',
    inputPlaceholder: '请输入最大分享次数',
    inputValidator: (value) => { if (!value || !/^[1-9][0-9]*$/.test(value)) return '请输入大于0的整数'; return true; },
  }).then(({ value }) => {
    generateShareCodeApi(row.fileHash, value)
      .then(res => {
        useMessageBox().alert('分享码：' + res.data, '分享文件', {
          confirmButtonText: '复制', cancelButtonText: '取消', type: 'success',
        }).then(() => {
          const { copy } = useClipboard();
          copy(res.data).then(() => useMessage().success('复制成功')).catch(() => useMessage().error('复制失败'));
        }).catch(() => {});
      }).catch(err => ElMessage.error('生成分享码失败'));
  }).catch(() => {});
};

const handleDel = (row, index) => {
  useMessageBox().confirm('你确认要删除此文件吗？', '删除文件', {
    confirmButtonText: '确定', cancelButtonText: '取消', type: 'warning',
  }).then(() => {
    adminDeleteFileApi(row.id)
      .then(() => { ElMessage.success('删除成功'); getList(); })
      .catch(err => ElMessage.error('删除失败'));
  }).catch(() => {});
};

const handleBatchShare = () => {
  if (page.selectedRows.length === 0) { useMessage().warning('请选择要分享的文件'); return; }
  const fileHashes = page.selectedRows.map(row => row.fileHash).join(',');
  useMessageBox().prompt('请输入最大分享次数', '批量分享文件', {
    confirmButtonText: '确定', cancelButtonText: '取消', type: 'warning', inputType: 'number',
    inputPlaceholder: '请输入最大分享次数',
    inputValidator: (value) => { if (!value || !/^[1-9][0-9]*$/.test(value)) return '请输入大于0的整数'; return true; },
  }).then(({ value }) => {
    generateShareCodeApi(fileHashes, value)
      .then(res => {
         useMessageBox().alert('分享码：' + res.data, '批量分享文件', {
          confirmButtonText: '复制', cancelButtonText: '取消', type: 'success',
        }).then(() => {
          const { copy } = useClipboard();
          copy(res.data).then(() => useMessage().success('复制成功')).catch(() => useMessage().error('复制失败'));
        }).catch(() => {});
      }).catch(err => ElMessage.error('批量生成分享码失败'));
  }).catch(() => {});
};

const handleBatchDelete = () => {
  if (page.selectedRows.length === 0) { useMessage().warning('请选择要删除的文件'); return; }
  const idList = page.selectedRows.map(row => row.id).join(',');
   useMessageBox().confirm('你确认要删除选中的文件吗？', '批量删除文件', {
    confirmButtonText: '确定', cancelButtonText: '取消', type: 'warning',
  }).then(() => {
    adminDeleteFileApi(idList)
      .then(() => { ElMessage.success('批量删除成功'); getList(); page.selectedRows = []; })
      .catch(err => ElMessage.error('批量删除失败'));
  }).catch(() => {});
};

const renderState = (st) => {
  switch (st) {
    case -2: return ['NOOP', 'warning']; case -1: return ['上链失败', 'danger'];
    case 0: return ['待上传', 'info']; case 1: return ['上传成功', 'success'];
    case 2: return ['已删除', 'info']; default: return ['-', 'info'];
  }
};

const formatAbi = (abiString) => {
  try {
    const abiObject = JSON.parse(abiString);
    return JSON.stringify(abiObject, null, 2);
  } catch (e) {
    console.error('格式化ABI失败', e); return abiString;
  }
};

const handleVerify = (row) => {
  if (!row.transactionHash) {
    ElMessage.warning('此文件没有关联的交易哈希'); return;
  }
  getFileTransactionApi(row.transactionHash)
    .then(res => {
      if (res && res.code === 1 && res.data) {
        const txData = res.data;
        currentTransaction.transactionHash = txData.transactionHash;
        currentTransaction.chainId = txData.chainId;
        currentTransaction.groupId = txData.groupId;
        currentTransaction.from = txData.from;
        currentTransaction.to = txData.to;
        currentTransaction.signature = txData.signature;
        currentTransaction.timestamp = txData.timestamp;
        currentTransaction.contractABI = txData.contractABI;
        currentTransaction.input = txData.input;
        currentTransaction.formattedABI = formatAbi(txData.contractABI);
        isABISectionExpanded.value = false;
        transactionDialogVisible.value = true;
      } else {
        console.error('获取交易记录响应格式不正确或操作失败 (then block):', res);
        const message = res?.message || '获取交易记录数据格式不正确或操作失败';
        ElMessage.error(message);
      }
    })
    .catch(error => {
      console.error('获取交易记录请求失败 (catch block):', error);
      const errorMessage = typeof error === 'object' && error !== null && error.message ? error.message : '未知错误';
      ElMessage.error(`获取交易记录请求失败: ${errorMessage}`);
    });
};

</script>

<template>
  <ContainerPage>
    <template #title>
      <div class="flex flex-row-reverse gap-2">
        <el-button size="small" type="success" :disabled="page.selectedRows.length === 0" @click="handleBatchShare">
          <el-icon><Promotion /></el-icon>批量分享
        </el-button>
        <el-button size="small" type="danger" :disabled="page.selectedRows.length === 0" @click="handleBatchDelete">
          <el-icon><DeleteFilled /></el-icon>批量删除
        </el-button>
      </div>
    </template>
    <el-table :loading="page.loading" :data="page.dataList" border stripe
      @selection-change="(rows) => page.selectedRows = rows">
      <el-table-column type="selection" width="55" />
      <el-table-column align="center" prop="fileName" label="文件名" />
      <el-table-column align="center" prop="fileSize" label="文件大小" />
      <el-table-column align="center" prop="fileType" label="文件类型" />
      <el-table-column align="center" prop="fileHash" label="哈希值" :show-overflow-tooltip="true"/>
      <el-table-column align="center" prop="transactionHash" label="交易哈希" :show-overflow-tooltip="true" />
      <el-table-column align="center" prop="status" label="状态">
        <template #default="{ row }">
          <el-tag :type="renderState(row.status)[1]">{{ renderState(row.status)[0] }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column align="center" prop="createTime" label="创建时间" />
      <el-table-column align="center" width="380" label="操作">
        <template #default="{ row, $index }">
          <el-button text :icon="Download" type="primary" @click="handleDownload(row)">下载</el-button>
          <el-button text :icon="Promotion" type="success" @click="handleShare(row, $index)">分享</el-button>
          <el-button text :icon="InfoFilled" type="info" @click="handleVerify(row)">验证</el-button>
          <el-button text :icon="DeleteFilled" @click="handleDel(row, $index)" type="danger">删除</el-button>
        </template>
      </el-table-column>
    </el-table>
    <div class="flex flex-row-reverse mt-4">
      <el-pagination layout="prev, pager, next, total" v-model:current-page="page.pagination.pageNum"
        :total="page.pagination.total" :page-size="page.pagination.pageSize" @current-change="getList" />
    </div>
  </ContainerPage>

  <el-dialog
    v-model="transactionDialogVisible"
    title="文件交易记录"
    width="60%" 
    :close-on-click-modal="false"
    append-to-body
    class="transaction-verify-dialog-vue" 
  >
    <div v-if="currentTransaction.transactionHash" class="transaction-detail-content-vue">
      <el-descriptions :column="1" border label-class-name="tx-label" class-name="tx-content-cell">
         <el-descriptions-item label="交易哈希">
           <div class="dialog-breakable-text">{{ currentTransaction.transactionHash }}</div>
         </el-descriptions-item>
         <el-descriptions-item label="区块链ID">
           <div class="dialog-breakable-text">{{ currentTransaction.chainId }}</div>
         </el-descriptions-item>
         <el-descriptions-item label="群组ID">
           <div class="dialog-breakable-text">{{ currentTransaction.groupId }}</div>
         </el-descriptions-item>
         <el-descriptions-item label="交易发起方">
           <div class="dialog-breakable-text">{{ currentTransaction.from }}</div>
         </el-descriptions-item>
         <el-descriptions-item label="交易接收方">
           <div class="dialog-breakable-text">{{ currentTransaction.to }}</div>
         </el-descriptions-item>
          <el-descriptions-item label="交易签名">
            <div class="dialog-breakable-text">{{ currentTransaction.signature }}</div>
          </el-descriptions-item>
         <el-descriptions-item label="交易时间">
           {{ new Date(parseInt(currentTransaction.timestamp)).toLocaleString() }}
         </el-descriptions-item>
          <el-descriptions-item label="交易输入参数">
            <el-scrollbar max-height="100px">
             <div class="dialog-pre-wrap breakable-scroll">{{ currentTransaction.input }}</div>
            </el-scrollbar>
          </el-descriptions-item>
      </el-descriptions>

      <div class="abi-section-vue mt-4">
        <h4 class="font-bold mb-2">合约ABI文件:</h4>
        <el-button @click="isABISectionExpanded = !isABISectionExpanded" size="small" class="mb-2">
          {{ isABISectionExpanded ? '点击收起' : '点击展开' }}
        </el-button>
        <div class="abi-content-vue">
          <el-scrollbar :max-height="isABISectionExpanded ? '400px' : '100px'"> 
             <pre class="dialog-pre-wrap breakable-scroll">{{ currentTransaction.formattedABI }}</pre> 
          </el-scrollbar>
        </div>
      </div>
    </div>
    <div v-else>
        <el-empty description="暂无交易详情" />
    </div>
    <template #footer>
      <el-button @click="transactionDialogVisible = false">关闭</el-button>
    </template>
  </el-dialog>

</template>

<style scoped>
:deep(.transaction-verify-dialog-vue) {
  max-width: 800px; 
}

:deep(.transaction-verify-dialog-vue .el-dialog__body) {
  padding-top: 10px; 
  padding-bottom: 20px;
  overflow-y: auto; 
}

.transaction-detail-content-vue {
  text-align: left;
}

:deep(.transaction-verify-dialog-vue .el-descriptions__table) {
  width: 100% !important; 
  table-layout: fixed !important; 
}

:deep(.transaction-verify-dialog-vue .el-descriptions .tx-label) { 
  width: 120px !important; 
  font-weight: bold;
  background-color: #fafafa; 
  text-align: right;
  padding-right: 12px; 
  vertical-align: top; 
  word-break: normal; 
}

:deep(.transaction-verify-dialog-vue .el-descriptions .tx-content-cell) { 
  word-break: break-all; 
  overflow-wrap: break-word;
  vertical-align: top; 
}

:deep(.transaction-verify-dialog-vue .el-descriptions .tx-content-cell .el-descriptions__content),
:deep(.transaction-verify-dialog-vue .el-descriptions .tx-content-cell .dialog-breakable-text) {
  word-break: break-all; 
  overflow-wrap: break-word; 
  white-space: normal; 
  display: block; 
}

.dialog-breakable-text {
   word-break: break-all;
   overflow-wrap: break-word;
   white-space: pre-wrap; /* Changed from normal to pre-wrap for potentially better formatting */
 }
 
.dialog-pre-wrap {
   white-space: pre-wrap;
   font-family: monospace;
   word-break: break-word; /* Changed from break-all for better readability */
 }
 
.breakable-scroll { /* Applied to the div/pre inside scrollbar */
   /* max-width: 100%; This might be implicitly handled by scrollbar */
   overflow-wrap: break-word;
   word-break: break-all; /* Ensure very long strings without spaces break */
 }

.abi-section-vue {
  margin-top: 1rem; 
}
.abi-section-vue .font-bold {
  font-weight: bold;
}
.abi-section-vue .mb-2 {
  margin-bottom: 0.5rem; 
}

:deep(.transaction-verify-dialog-vue .el-scrollbar .dialog-pre-wrap) { /* Adjusted selector based on user's HTML changes */
  background-color: #f8f9fa;
  padding: 10px;
  border-radius: 4px;
  font-size: 0.9em;
  line-height: 1.5;
  white-space: pre-wrap;  
  word-break: break-all;  
  margin: 0; 
}

.mt-4 { 
    margin-top: 1rem; 
}
</style>