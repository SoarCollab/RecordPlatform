<script setup>

import FlagIcon from "@/components/Icon/FlagIcon.vue";
import {Delete,Connection} from '@element-plus/icons-vue'
import DescItem from "@/components/DescItem.vue";
import {osNameToIcon, percentageToStatus, fitByUnit} from '@/utils/other.js'
import {useClipboard} from "@vueuse/core";
import {useMessage, useMessageBox} from '@/utils/message.js'
import MenuIcon from "@/components/Icon/MenuIcon.vue";
import {deleteMonitorApi} from "@/api/monitor.js";
const message = useMessage()

defineOptions({
  name: 'HostCard'
})

const emits = defineEmits(['delete', 'connection'])

defineProps({
  width: {
    type: String,
    default: '100%'
  },
  height: {
    type: String,
    default: '100%'
  },
  data: {
    type: Object,
    default: () => ({})
  }
})

const {copy} = useClipboard()

const handleCopy = (value) => {
  console.log('复制', value)
  copy(value).then(() => {
    console.log('复制成功')
    message.success('复制成功')
  }).catch(() => {
    console.log('复制失败')
    message.error('复制失败')
  })
}

const deleteClient = () => {

  useMessageBox().confirm('删除此主机后所有统计数据都将丢失，您确定要这样做吗？', '删除主机', {
    confirmButtonText: '确定',
    cancelButtonText: '取消',
    type: 'warning',
  }).then(() => {
    deleteMonitorApi(route.params.id).then(() => {
      useMessage().success('主机已成功移除')
    }).finally(() => {
      emits('delete')
    })
  }).catch(() => {

  })
}

</script>

<template>
  <el-card :style="{width: `${width}px`,}" class="" >
    <div class="flex flex-col gap-3" >
      <div class="flex items-center justify-between" >
        <div class="flex items-center gap-1" >
          <FlagIcon :size="'16px'" :name="data.location"/>
          <span class="font-bold text-[16px]" >{{ data.name }}</span>
        </div>
        <div v-if="data.online" class="flex items-center text-[16px]" >
          <i class="fa-regular fa-circle-play text-green"></i>
          &nbsp;<span>运行中</span>
        </div>
        <div v-else class="flex items-center text-[16px]" >
          <i class="fa-regular fa-circle-stop text-gray"></i>
          &nbsp;<span>离线</span>
        </div>
      </div>
      <el-divider style="margin: 0" />
      <div class="flex flex-col gap-1" >
        <DescItem label="操作系统：" :value="data.osName">
          <template #preValue>
            <i :style="{color: osNameToIcon(data.osName).color}"
               :class="`fa-brands ${osNameToIcon(data.osName).icon}`"
               class="pr-1"/>
          </template>
        </DescItem>
        <DescItem label="公网IP：" :value="data.ip">
          <template #value="{value}">
            <i class="fa-solid fa-copy pl-1 text-[14px] text-blue" @click.stop="handleCopy(value)"/>
          </template>
        </DescItem>
        <DescItem label="处理器：" :value="data.cpuName" />
        <div class="flex items-center text-[14px] " >
          <MenuIcon icon="Cpu" :size="'14px'" />
          <span class="pl-1 mr-5 text-[13px]">{{` ${data.cpuCore} CPU`}}</span>
          <MenuIcon icon="ScaleToOriginal" :size="'14px'" />
          <span class="pl-1 text-[13px]" >{{` ${data.memory.toFixed(1)} GB`}}</span>
        </div>
      </div>
      <div class="text-[14px] flex flex-col gap-1">
        <span >{{ `CPU Usage: ${(data.cpuUsage * 100).toFixed(1)} %` }}</span>
        <el-progress :status="percentageToStatus(data.cpuUsage * 100)" :percentage="data.cpuUsage * 100" :stroke-width="5" :show-text="false"/>
      </div>
      <div class="text-[14px] flex flex-col gap-1">
        <span>Memory Usage: <b>{{ data.memoryUsage.toFixed(1) }}</b>GB</span>
        <el-progress :status="percentageToStatus(data.memoryUsage/data.memory *100)" :percentage="data.memoryUsage/data.memory *100" :stroke-width="5" :show-text="false"/>
      </div>
      <div class="text-[14px] flex flex-col gap-1">
        <div class="font-bold text-gray" >网络流量</div>
        <div class="flex gap-1 ">
          <MenuIcon icon="Top" :color="'orange'" />
          <span>{{ ` ${fitByUnit(data.networkUpload,'KB')} /s `}}</span>
          <el-divider direction="vertical"/>
          <MenuIcon icon="Bottom" :color="'green'" />
          <span>{{ ` ${fitByUnit(data.networkDownload,'KB')} /s `}}</span>
        </div>
      </div>
      <el-divider style="margin: 0" />
      <div class="flex gap-1">
        <el-button @click.stop="deleteClient" :icon="Delete" class="w-full" size="small" type="danger" plain>删除当前主机</el-button>
        <el-button @click.stop="emits('connection')" :icon="Connection"  class="w-full" size="small" type="primary" plain>SSH远程连接</el-button>
      </div>
    </div>
  </el-card>
</template>

<style scoped>
.host-card {
  background: #fff;
  transition: all 0.3s ease; /* 卡片内容过渡效果 */
  padding: 12px;
  border-radius: 8px;
  display: flex;
  flex-direction: column;
  gap: 8px;
}
</style>