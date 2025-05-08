<script setup>

import ContainerPage from "@/components/ContainerPage.vue";
import { getFileListApi, getFileAddressApi, deleteFileApi, adminDeleteFileApi, generateShareCodeApi } from "@/api/file.js";
import { useStorage } from "@vueuse/core";
import { Promotion, DeleteFilled, Download } from '@element-plus/icons-vue'
import { formatSize } from "@/utils/file.js";
import { decryptAndAssembleFile, base64ToUint8Array } from "@/utils/decrypt.js";
import TaskManager from "@/utils/taskNotification.js";
import { ElMessage } from "element-plus";
import { useMessage, useMessageBox } from "@/utils/message";
import { useClipboard } from "@vueuse/core";
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

const getList = async () => {
  try {
    page.loading = true
    const res = await getFileListApi({
      pageNum: page.pagination.pageNum,
      pageSize: page.pagination.pageSize
    })
    console.log('getList', res)
    const { data } = res
    console.log('getList', data)
    page.pagination.total = parseInt(data.total)
    if (data.records && data.records.length > 0) {
      page.dataList = data.records.map(it => {
        let fileParam = {}
        if (it.fileParam) {
          fileParam = JSON.parse(it.fileParam)
        }

        return {
          ...it,
          fileSize: formatSize(fileParam?.fileSize || 0),
          fileType: fileParam?.contentType || '-',
        }
      })
    }
  } finally {
    page.loading = false
  }
}

onMounted(() => {
  getList()
})

const handleDownload = async (row) => {
  const taskId = TaskManager.addTask({
    title: `下载文件: ${row.fileName}`,
    progress: 0
  });

  try {
    console.log('开始下载文件:', {
      fileName: row.fileName,
      fileHash: row.fileHash
    });

    // 获取文件分片地址
    const res = await getFileAddressApi(row.fileHash);
    console.log('获取文件分片地址响应:', res);

    if (!res || !res.data) {
      throw new Error('获取文件分片地址失败：服务器响应无效');
    }

    const fileAddresses = res.data;
    if (!Array.isArray(fileAddresses) || fileAddresses.length === 0) {
      throw new Error('获取文件分片地址失败：地址列表为空或格式不正确');
    }

    // 更新任务状态为处理中
    TaskManager.updateTask(taskId, {
      status: 'processing',
      progress: 0
    });

    // 下载最后一个分片以获取解密密钥
    const lastChunkResponse = await fetch(fileAddresses[fileAddresses.length - 1]);
    if (!lastChunkResponse.ok) {
      throw new Error(`下载最后一个分片失败: ${lastChunkResponse.status} ${lastChunkResponse.statusText}`);
    }
    const lastChunkData = await lastChunkResponse.arrayBuffer();
    console.log('最后一个分片下载完成:', {
      size: lastChunkData.byteLength,
      preview: new Uint8Array(lastChunkData.slice(0, 50)) // 打印前50个字节用于调试
    });

    // 从最后一个分片中提取解密密钥
    const keySeparator = new TextEncoder().encode('\n--NEXT_KEY--\n');
    const keyIndex = findSequenceReverse(lastChunkData, keySeparator);
    console.log('密钥分隔符搜索:', {
      keyIndex,
      keySeparatorLength: keySeparator.length,
      lastChunkSize: lastChunkData.byteLength
    });

    if (keyIndex === -1) {
      // 尝试打印分片末尾的内容以进行调试
      const lastBytes = new Uint8Array(lastChunkData.slice(-100));
      const textDecoder = new TextDecoder();
      console.error('未找到密钥分隔符，分片末尾内容:', {
        hex: Array.from(lastBytes).map(b => b.toString(16).padStart(2, '0')).join(' '),
        text: textDecoder.decode(lastBytes)
      });
      throw new Error('在最后一个分片中未找到密钥分隔符');
    }

    const keyBase64 = new TextDecoder().decode(lastChunkData.slice(keyIndex + keySeparator.length)).trim();
    console.log('提取的密钥Base64:', {
      length: keyBase64.length,
      preview: keyBase64.substring(0, 20) + '...',
      fullKey: keyBase64 // 临时打印完整密钥用于调试
    });

    // 验证Base64格式
    if (!isValidBase64(keyBase64)) {
      throw new Error('提取的密钥不是有效的Base64格式');
    }

    // 下载所有分片
    const chunks = [];
    for (let i = 0; i < fileAddresses.length; i++) {
      try {
        console.log(`开始下载分片 ${i + 1}/${fileAddresses.length}`);
        const response = await fetch(fileAddresses[i]);
        if (!response.ok) {
          throw new Error(`下载分片 ${i + 1} 失败: ${response.status} ${response.statusText}`);
        }
        const chunkData = await response.arrayBuffer();
        chunks.push(chunkData);

        // 更新进度
        const progress = Math.round(((i + 1) / fileAddresses.length) * 100);
        TaskManager.updateTask(taskId, {
          status: 'processing',
          progress: progress
        });
        console.log(`分片 ${i + 1} 下载完成:`, {
          size: chunkData.byteLength,
          progress: progress
        });
      } catch (error) {
        console.error(`下载分片 ${i + 1} 时出错:`, error);
        throw error;
      }
    }

    // 解密并组装文件
    console.log('开始解密和组装文件...', chunks, keyBase64);
    const decryptedData = await decryptAndAssembleFile(chunks, keyBase64);
    if (!decryptedData) {
      throw new Error('文件解密失败：解密过程返回空数据');
    }
    console.log('文件解密完成:', {
      size: decryptedData.byteLength
    });

    // 创建下载链接
    const blob = new Blob([decryptedData], { type: 'application/octet-stream' });
    const url = URL.createObjectURL(blob);
    const link = document.createElement('a');
    link.href = url;
    link.download = row.fileName;
    document.body.appendChild(link);
    link.click();
    document.body.removeChild(link);
    URL.revokeObjectURL(url);

    // 更新任务状态为成功
    TaskManager.updateTask(taskId, {
      status: 'success',
      progress: 100
    });
  } catch (error) {
    console.error('下载文件失败:', error);
    TaskManager.updateTask(taskId, {
      status: 'error',
      progress: 0,
      error: error.message
    });
    // 使用 ElMessage 显示错误信息
    ElMessage.error(`文件下载失败: ${error.message}`);
  }
};

// 验证 Base64 字符串是否有效
function isValidBase64(str) {
  try {
    // 移除可能的空白字符
    const cleanStr = str.replace(/\s/g, '');
    console.log('清理后的Base64字符串:', cleanStr);

    // 检查长度是否为 4 的倍数
    if (cleanStr.length % 4 !== 0) {
      console.error('Base64长度不是4的倍数:', cleanStr.length);
      return false;
    }

    // 检查是否只包含有效的 Base64 字符
    if (!/^[A-Za-z0-9+/]*={0,2}$/.test(cleanStr)) {
      console.error('Base64包含无效字符');
      return false;
    }

    // 尝试解码
    window.atob(cleanStr);
    return true;
  } catch (e) {
    console.error('Base64验证失败:', e);
    return false;
  }
}

// 辅助函数：在 ArrayBuffer 中查找字节序列的位置 (从后向前)
function findSequenceReverse(buffer, sequence) {
  const bufferView = new Uint8Array(buffer);
  const seqLen = sequence.length;
  const bufLen = bufferView.length;

  if (seqLen === 0 || seqLen > bufLen) {
    return -1;
  }

  for (let i = bufLen - seqLen; i >= 0; i--) {
    let found = true;
    for (let j = 0; j < seqLen; j++) {
      if (bufferView[i + j] !== sequence[j]) {
        found = false;
        break;
      }
    }
    if (found) {
      return i;
    }
  }
  return -1;
}

const handleShare = (row, index) => {
  console.log('handleShare', row, index)
  useMessageBox().prompt('请输入最大分享次数', '分享文件', {
    confirmButtonText: '确定',
    cancelButtonText: '取消',
    type: 'warning',
    inputType: 'number',
    inputPlaceholder: '请输入最大分享次数',
    inputValidator: (value) => {
      if (!value) {
        return '请输入最大分享次数'
      }
      if (!/^[1-9][0-9]*$/.test(value)) {
        return '请输入大于0的整数'
      }
      return true
    },
    inputErrorMessage: '输入格式不正确',
  }).then(({ value }) => {
    generateShareCodeApi(row.fileHash, value).then(res => {
      console.log('generateShareCodeApi', res)
      useMessageBox().alert('分享码：' + res.data, '分享文件', {
        confirmButtonText: '复制',
        cancelButtonText: '取消',
        type: 'success',
      }).then(() => {
        const { copy } = useClipboard()
        copy(res.data).then(() => {
          useMessage().success('复制成功')
        }).catch(() => {
          useMessage().error('复制失败')
        })
      })
    })
  })
}

const handleDel = (row, index) => {
  console.log('handleDel', row, index)
  useMessageBox().confirm('你确认要删除此文件吗？', '删除文件', {
    confirmButtonText: '确定',
    cancelButtonText: '取消',
    type: 'warning',
  }).then(() => {
    adminDeleteFileApi(row.id).finally(() => {
      getList()
    })
  })
}

const handleBatchShare = () => {
  if (page.selectedRows.length === 0) {
    useMessage().warning('请选择要分享的文件')
    return
  }

  const fileHashes = page.selectedRows.map(row => row.fileHash).join(',')

  useMessageBox().prompt('请输入最大分享次数', '批量分享文件', {
    confirmButtonText: '确定',
    cancelButtonText: '取消',
    type: 'warning',
    inputType: 'number',
    inputPlaceholder: '请输入最大分享次数',
    inputValidator: (value) => {
      if (!value) {
        return '请输入最大分享次数'
      }
      if (!/^[1-9][0-9]*$/.test(value)) {
        return '请输入大于0的整数'
      }
      return true
    },
    inputErrorMessage: '输入格式不正确',
  }).then(({ value }) => {
    generateShareCodeApi(fileHashes, value).then(res => {
      useMessageBox().alert('分享码：' + res.data, '批量分享文件', {
        confirmButtonText: '复制',
        cancelButtonText: '取消',
        type: 'success',
      }).then(() => {
        const { copy } = useClipboard()
        copy(res.data).then(() => {
          useMessage().success('复制成功')
        }).catch(() => {
          useMessage().error('复制失败')
        })
      })
    })
  })
}

const handleBatchDelete = () => {
  if (page.selectedRows.length === 0) {
    useMessage().warning('请选择要删除的文件')
    return
  }

  const idList = page.selectedRows.map(row => row.id).join(',')

  useMessageBox().confirm('你确认要删除选中的文件吗？', '批量删除文件', {
    confirmButtonText: '确定',
    cancelButtonText: '取消',
    type: 'warning',
  }).then(() => {
    adminDeleteFileApi(idList).finally(() => {
      getList()
      page.selectedRows = []
    })
  })
}

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

</script>

<template>
  <ContainerPage>
    <template #title>
      <div class="flex flex-row-reverse gap-2">
        <el-button size="small" type="success" :disabled="page.selectedRows.length === 0" @click="handleBatchShare">
          <el-icon>
            <Promotion />
          </el-icon>
          批量分享
        </el-button>
        <el-button size="small" type="danger" :disabled="page.selectedRows.length === 0" @click="handleBatchDelete">
          <el-icon>
            <DeleteFilled />
          </el-icon>
          批量删除
        </el-button>
      </div>
    </template>
    <el-table :loading="page.loading" :data="page.dataList" border stripe
      @selection-change="(rows) => page.selectedRows = rows">
      <el-table-column type="selection" width="55" />
      <el-table-column align="center" prop="fileName" label="文件名" />
      <el-table-column align="center" prop="fileSize" label="文件分类" />
      <el-table-column align="center" prop="fileType" label="文件类型" />
      <el-table-column align="center" prop="fileHash" label="哈希值" />
      <el-table-column align="center" prop="status" label="状态">
        <template #default="{ row }">
          <el-tag :type="renderState(row.status)[1]">{{ renderState(row.status)[0] }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column align="center" prop="createTime" label="创建时间" />
      <el-table-column align="center" width="300" label="操作">
        <template #default="{ row, $index }">
          <el-button text :icon="Download" type="primary" @click="handleDownload(row)">下载</el-button>
          <el-button text :icon="Promotion" type="success" @click="handleShare(row, $index)">分享</el-button>
          <el-button text :icon="DeleteFilled" @click="handleDel(row, $index)" type="danger">删除</el-button>
          <!-- <el-popconfirm
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
    </el-popconfirm> -->
        </template>
      </el-table-column>
    </el-table>
    <div class="flex flex-row-reverse">
      <el-pagination layout="prev, pager, next, total" v-model:current-page="page.pagination.pageNum"
        :total="page.pagination.total" :page-size="page.pagination.pageSize" @current-change="getList" />
    </div>
  </ContainerPage>
</template>

<style scoped></style>