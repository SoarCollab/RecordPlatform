<script setup>

import ContainerPage from "@/components/ContainerPage.vue";
import FlagIcon from "@/components/Icon/FlagIcon.vue";
import {getMonitorListApi, registerMonitorApi} from "@/api/monitor.js";
import HostCard from "@/components/HostCard.vue";
import GridContainer from "@/components/GridContainer.vue";
import {useRouter} from "vue-router";
import RegisterCard from "@/components/RegisterCard.vue";
const router = useRouter()
const page = reactive({
  dataList: [],
  locations: [
    {name: 'cn', desc: '中国大陆'},
    {name: 'hk', desc: '香港'},
    {name: 'jp', desc: '日本'},
    {name: 'us', desc: '美国'},
    {name: 'sg', desc: '新加坡'},
    {name: 'kr', desc: '韩国'},
    {name: 'de', desc: '德国'}
  ],
  checkedNodes: [],
  dialog: {
    visible: false,
  }
})

const dataList = computed(() => {
  if (page.checkedNodes.length === 0) return  page.dataList
  return page.dataList.filter(item => page.checkedNodes.includes(item.location))
})

const getList = async () => {
  const {data} = await getMonitorListApi()
  page.dataList = data
}

const id = setInterval(()=>{
  getList()
},10000)

onMounted(() => {
  getList()
})
onBeforeUnmount(() => {
  id && clearInterval(id)
})

const handleOpenDetail = (id) => {
  router.push(`/monitor/host/detail/${id}`)
}

const connection = (id) => {
  console.log('connection', id)
  router.push(`/monitor/host/ssh/${id}`)
}

const handleAdd = async () => {
  const res = await registerMonitorApi()
  page.dialog.token = res.data
  page.dialog.visible = true
}

const handleClose = () => {
  page.dialog.token = null
}

</script>

<template>
  <el-dialog v-model="page.dialog.visible" width="30%" :show-close="false" @close="handleClose" >
    <RegisterCard :token="page.dialog.token" />
  </el-dialog>
  <ContainerPage>
    <template #title>
      <div class="box-border pl-3 flex items-center justify-between gap-10">
        <el-checkbox-group v-model="page.checkedNodes">
          <el-checkbox
            v-for="item in page.locations"
            :key="item.name"
            :value="item.name">
            <!-- 自定义复选框内容 -->
           <el-tag type="primary" size="small" >
             <FlagIcon :name="item.name" :size="'13px'"/>
             <span class="pl-2" >{{ item.desc }}</span>
           </el-tag>
          </el-checkbox>
        </el-checkbox-group>
        <el-button type="primary" size="small" @click="handleAdd" >
          添加主机
        </el-button>
      </div>
    </template>

    <GridContainer v-if="dataList.length" >
      <template #default="{ width, height }">
        <HostCard
          @delete="getList"
          @connection="connection(item.id)"
          @click="handleOpenDetail(item.id)"
          v-for="item in dataList"
          :key="item.id"
          :width="`${width}px`"
          :height="`${height}px`"
          :data="item"/>
      </template>
    </GridContainer>
    <el-empty description="当前无主机连接，请点击添加主机按钮" v-else/>

  </ContainerPage>
</template>

<style scoped>

</style>