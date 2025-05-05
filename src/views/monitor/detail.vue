<script setup>
import {useRoute} from "vue-router";
import ContainerPage from "@/components/ContainerPage.vue";
import {fromPairs} from "lodash";
import {useMessageBox, useMessage} from  '@/utils/message.js'
import {
  deleteMonitorApi,
  getMonitorDetailsApi,
  getMonitorRuntimeHistoryApi,
  getMonitorRuntimeNowApi,
  renameMonitorApi, resetMonitorNodeApi
} from "@/api/monitor.js";
import DescItem from "@/components/DescItem.vue";
import FlagIcon from "@/components/Icon/FlagIcon.vue";
import {useClipboard} from "@vueuse/core";
import {fitByUnit} from '@/utils/other.js'
import MenuIcon from "@/components/Icon/MenuIcon.vue";
import RuntimeHistory from "@/components/RuntimeHistory.vue";


const route = useRoute()
const formRef = ref(null)
const page = reactive({
  loading: false,
  locations: [
    {name: 'cn', desc: '中国大陆'},
    {name: 'hk', desc: '香港'},
    {name: 'jp', desc: '日本'},
    {name: 'us', desc: '美国'},
    {name: 'sg', desc: '新加坡'},
    {name: 'kr', desc: '韩国'},
    {name: 'de', desc: '德国'}
  ],
  details: {
    base: {},
    runtime: {
      list:[]
    }
  },
  dialog: {
    visible: false,
    title: '标题',
    type: 1,
    form: {
      id: '',
      name: '',
      node: '',
      location: '',
    },
    rules: {
      name: [
        {required: true, message: '请输入名称', trigger: 'blur'},
        // /^[a-zA-Z0-9_\u4e00-\u9fa5]{1,20}$/
        {pattern: /^[a-zA-Z0-9_\u4e00-\u9fa5]{1,20}$/, message: '请输入1-20位字符，支持中文、英文、数字、下划线'}
      ],
      node: [
        {required: true, message: '请输入节点', trigger: 'blur'}
      ],
      location: [
        {required: true, message: '请选择位置', trigger: 'change'}
      ]
    }
  }
})

const now = computed(() => {
  if (page.details.runtime.list.length){
    return page.details.runtime.list[page.details.runtime.list.length-1]
  }
  return null
})

const usageCpu = computed(() => {
  if (page.details.runtime.list.length){
    return parseFloat(page.details.runtime.list[page.details.runtime.list.length-1]?.cpuUsage * 100)
  }
  return 0
})
const usageMemory = computed(() => {
  //memoryUsage
  if (page.details.runtime.list.length && page.details.runtime?.memory){
    return parseFloat(page.details.runtime.list[page.details.runtime.list.length-1]?.memoryUsage / page.details.runtime.memory * 100)
  }
  return 0
})

const usageDisk = computed(() => {
  if (page.details.runtime.list.length && page.details.runtime?.disk){
    return parseFloat(page.details.runtime.list[page.details.runtime.list.length-1]?.diskUsage / page.details.runtime.disk * 100)
  }
  return 0
})

const {copy} = useClipboard()

const handleCopy = (value) => {
  console.log('复制', value)
  copy(value).then(() => {
    console.log('复制成功')
    useMessage().success('复制成功')
  }).catch(() => {
    console.log('复制失败')
    useMessage().error('复制失败')
  })
}

const cpuNameToImage = (name) => {
  if (name.indexOf('Apple')>=0)
    return 'Apple.png'
  else if (name.indexOf('AMD')>=0)
    return 'AMD.png'
  else
    return 'Intel.png'
}

const percentageToStatus = (percentage) => {
  if (percentage<50)
    return 'success'
  else if (percentage<80)
    return 'warning'
  else
    return 'exception'
}

const colorToPercentage = (percentage) => {
  if (percentage<50)
    return 'green'
  else if (percentage<80)
    return 'orange'
  else
    return 'red'
}

const getDetail = async (id) => {
  const res = await getMonitorDetailsApi(id)
  console.log('getDetail', res)
  page.details.base  = res.data
}

const getHistory = async (id) => {
  const res = await getMonitorRuntimeHistoryApi(id)
  console.log('getHistory', res)
  page.details.runtime = res.data
}

const getNow = async (id) => {
  const res = await getMonitorRuntimeNowApi(id)
  console.log('getNow', res)
  if(page.details.runtime.list.length >= 360){
    page.details.runtime.list.shift()
    // page.details.runtime.list.splice(0, 1)
  }
  page.details.runtime.list.push(res.data)

}


const id = setInterval(()=>{
  getNow(route.params.id)
}, 2000)
onMounted(() => {
  getDetail(route.params.id)
  getHistory(route.params.id)
  getNow(route.params.id)
})
onBeforeUnmount(() => {
  id && clearInterval(id)
})

const handleRename = () => {
  page.dialog.title = '重命名服务器'
  page.dialog.form = {
    id: page.details.base.id,
    name: page.details.base.name,
  }
  page.dialog.visible = true
}

const handleResetNode = () => {
  page.dialog.title = '重置节点'
  page.dialog.form = {
    id: page.details.base.id,
    node: page.details.base.node,
    location: page.details.base.location
  }
  page.dialog.visible = true
}

const handleSubmit = () => {
  formRef.value.validate((valid) => {
    if (valid) {
      if(page.dialog.title==='重命名服务器'){
        renameMonitorApi({
          id: page.dialog.form.id,
          name: page.dialog.form.name
        }).then(() => {
          useMessage().success('重命名成功')
        }).finally(() => {
          getDetail(route.params.id)
          handleCancel()
        })
      }
      if(page.dialog.title==='重置节点'){
        resetMonitorNodeApi({
          id: page.dialog.form.id,
          node: page.dialog.form.node,
          location: page.dialog.form.location
        }).then(() => {
          useMessage().success('重置节点成功')
        }).finally(() => {
          getDetail(route.params.id)
          handleCancel()
        })
      }
    }
  })
}

const handleCancel = () => {
  page.dialog.visible = false
  page.dialog.form = {}
}

const connection = (id) => {
  console.log('connection', id)
  router.push(`/monitor/host/ssh/${id}`)
}

</script>

<template>
  <el-dialog  v-model="page.dialog.visible" :title="page.dialog.title" width="30%" :show-close="false" >
    <el-form ref="formRef" :model="page.dialog.form" :rules="page.dialog.rules" label-position="top">
      <template v-if="page.dialog.title==='重命名服务器'" >
        <el-form-item label="服务器名称" prop="name">
          <el-input v-model="page.dialog.form.name" maxlength="10" />
        </el-form-item>
      </template>
      <template v-else>
        <el-form-item label="位置" prop="location">
          <el-select v-model="page.dialog.form.location" placeholder="请选择位置" >
            <el-option v-for="item in page.locations" :key="item.name" :label="item.desc" :value="item.name">
              <FlagIcon  :name="item.name" :size="'16px'"/>
              <span class="text-[16px] pl-2" >{{item.desc}}</span>
            </el-option>
          </el-select>
        </el-form-item>
        <el-form-item label="节点" prop="node">
          <el-input v-model="page.dialog.form.node" placeholder="请输入节点" />
        </el-form-item>
      </template>
      <el-form-item>
        <el-button type="primary" @click="handleSubmit" >提交</el-button>
        <el-button @click="handleCancel" >取消</el-button>
      </el-form-item>
    </el-form>
  </el-dialog>
  <ContainerPage :back="'/monitor/host'">
    <template #title>
      <div class="flex flex-row-reverse items-center gap-3">
        <el-button type="primary" size="small" @click="connection(page.details.base.id)" >SSH远程连接</el-button>
      </div>
    </template>
    <div class="flex h-full gap-3 box-border" >
      <div class="w-2/5 grow-1 h-full " >
        <div class="text-[18px] font-bold pb-5 text-blue" >
          服务器信息
        </div>
        <el-row>
          <el-col :span="8">
            <span class="text-[16px] font-bold" >服务器id</span>
          </el-col>
          <el-col :span="16">
            <span class="text-[16px]">{{ page.details.base.id }}</span>
          </el-col>
          <el-col :span="24"><div class="m-3" ></div></el-col>

          <el-col :span="8">
            <span class="text-[16px] font-bold">服务器名称</span>
          </el-col>
          <el-col :span="16">
            <div class="flex items-center gap-1">
              <span class="text-[16px] ">{{ page.details.base.name }}</span>
              <i class="text-[16px] fa-solid fa-pen-to-square interact-item" @click.stop="handleRename" ></i>
            </div>
          </el-col>
          <el-col :span="24"><div class="m-3" ></div></el-col>

          <el-col :span="8">
            <span class="text-[16px] font-bold">服务器节点</span>
          </el-col>
          <el-col :span="16">
            <div class="flex items-center gap-1" >
              <FlagIcon v-if="page.details.base?.location" :size="'16px'" :name="page.details.base.location"/>
              <span class="font-bold text-[16px]" >{{ page.details.base.node }}</span>
              <i class="text-[16px] fa-solid fa-pen-to-square interact-item" @click.stop="handleResetNode" ></i>
            </div>
          </el-col>
          <el-col :span="24"><div class="m-3" ></div></el-col>

          <el-col :span="8">
            <span class="text-[16px] font-bold">运行状态</span>
          </el-col>
          <el-col :span="16">
            <div v-if="page.details.base.online" class="flex items-center text-[16px]" >
              <i class="fa-regular fa-circle-play text-green"></i>
              &nbsp;<span>运行中</span>
            </div>
            <div v-else class="flex items-center text-[16px]" >
              <i class="fa-regular fa-circle-stop text-gray"></i>
              &nbsp;<span>离线</span>
            </div>
          </el-col>
          <el-col :span="24"><div class="m-3" ></div></el-col>

          <el-col :span="8">
            <span class="text-[16px] font-bold">公网ip</span>
          </el-col>
          <el-col :span="16">
            <span class="text-[16px] ">{{ page.details.base.ip }}</span>
            <i class="fa-solid fa-copy pl-1 text-[14px] text-blue" @click.stop="handleCopy(value)"/>
          </el-col>
          <el-col :span="24"><div class="m-3" ></div></el-col>

          <el-col :span="8">
            <span class="text-[16px] font-bold">处理器</span>
          </el-col>
          <el-col :span="16">
            <div class="flex items-center text-[16px] gap-2">
              <span class="text-[16px] ">{{ page.details.base.cpuName }}</span>
              <el-image v-if="page.details.base?.cpuName" class="h-4" :src="`/cpu-icons/${cpuNameToImage(page.details.base.cpuName)}`"/>
            </div>
          </el-col>
          <el-col :span="24"><div class="m-3" ></div></el-col>

          <el-col :span="8">
            <span class="text-[16px] font-bold">硬件信息</span>
          </el-col>
          <el-col :span="16">
            <span class="text-[16px] ">{{ `${page.details.base.cpuCore} CPU核心数 / ${page.details.base.memory} GB内存容量` }}</span>
          </el-col>
          <el-col :span="24"><div class="m-3" ></div></el-col>

          <el-col :span="8">
            <span class="text-[16px] font-bold">操作系统</span>
          </el-col>
          <el-col :span="16">
            <span class="text-[16px] ">{{ `${page.details.base.osName} ${page.details.base.osVersion}` }}</span>
          </el-col>
        </el-row>
      </div>

      <div class="h-full w-[1px] bg-gray-400" ></div>

      <div class="w-3/5 grow-1 h-full flex flex-col box-border" >
        <div class="shrink-0 text-[18px] font-bold pb-5 text-blue" >
          实时监控
        </div>
        <div v-if="page.details.base.online" class="h-full flex flex-col gap-5 box-border">

          <div class="shrink-0 flex justify-between gap-10" v-if="page.details.runtime.list.length">

            <div class="shrink-0 flex items-center gap-4" >
              <el-progress
                type="dashboard"
                :width="100"
                :percentage="usageCpu"
                :status="percentageToStatus(now.cpuUsage *100)">
                <div style="font-size: 17px;font-weight: bold;color: initial">CPU</div>
                <div style="font-size: 13px;color: grey;margin-top: 5px">{{ (now.cpuUsage * 100).toFixed(1) }}%</div>
              </el-progress>

              <el-progress
                type="dashboard"
                :width="100"
                :percentage="usageMemory"
                :status="percentageToStatus(now.memoryUsage / page.details.runtime.memory * 100)">
                <div style="font-size: 16px;font-weight: bold;color: initial">内存</div>
                <div style="font-size: 13px;color: grey;margin-top: 5px">{{ (now.memoryUsage).toFixed(1) }} GB</div>
              </el-progress>
            </div>

            <div class=" flex flex-col gap-5" >
              <div class="text-[17px] " >
                实时网络速度
              </div>
              <div class="flex gap-1 text-[16px]" >
                <MenuIcon icon="Top" :color="'orange'" />
                <span>{{` ${fitByUnit(now.networkUpload, 'KB')}/s`}}</span>
                <el-divider direction="vertical"/>
                <MenuIcon icon="Bottom" :color="'green'" />
                <span>{{` ${fitByUnit(now.networkDownload, 'KB')}/s`}}</span>
              </div>
            </div>

            <div class="grow-1 flex flex-col gap-5" >
              <div class="text-[17px]" >
                磁盘总容量
              </div>
              <div class="flex items-center gap-3 text-[16px]" >
                <el-progress
                  class="grow-1"
                  type="line"
                  :show-text="false"
                  :status="percentageToStatus(now.diskUsage / page.details.runtime.disk * 100)"
                  :percentage="usageDisk" />
                <div class="shrink-0" v-if="page.details.runtime?.disk" >
                  <span :style="{color: colorToPercentage(now.diskUsage / page.details.runtime.disk * 100)}" >
                    {{now.diskUsage.toFixed(1)}} GB
                  </span>
                  <span>{{` / `}}</span>
                  <span class="text-blue" >{{page.details.runtime.disk.toFixed(1)}} GB</span>
                </div>
              </div>
            </div>

          </div>

          <div class="grow-1 h-full box-border p-2" >
            <RuntimeHistory v-if="page.details.runtime.list.length" :data="page.details.runtime.list"/>
          </div>
        </div>
        <el-empty description="服务器处于离线状态，请检查服务器是否正常运行" v-else/>
      </div>
    </div>
  </ContainerPage>
</template>

<style scoped>

</style>