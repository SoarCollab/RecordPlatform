<script setup>

import {useRoute} from "vue-router";
import ContainerPage from "@/components/ContainerPage.vue";
import {saveSSHMonitorApi, sshMonitorApi} from "@/api/monitor.js";
import Terminal from "@/components/Terminal.vue";

const route = useRoute()
const formRef = ref(null)
const page = reactive({
  state: 1,
  connectId: null,
  form: {
    ip: '',
    port: 22,
    username: '',
    password: ''
  },
  rules: {
    ip: [
      { required: true, message: '请输入IP地址', trigger: ['blur', 'change'] },
    ],
    port: [
      { required: true, message: '请输入端口', trigger: ['blur', 'change'] },
    ],
    username: [
      { required: true, message: '请输入用户名', trigger: ['blur', 'change'] },
    ],
    password: [
      { required: true, message: '请输入密码', trigger: ['blur', 'change'] },
    ]
  }
})

const getConnectInfo = async () => {
  try {
    if(!route.params?.id) return
    const res = await sshMonitorApi(route.params.id)
    console.log('获取连接信息', res)
    page.form = res.data
  }catch (e) {

  }
}

const getConnect = () => {
  console.log('连接')
  formRef.value.validate( valid => {
    if(valid){
      console.log('连接', toRaw(page.form), route.params.id)
      saveSSHMonitorApi({
        ...page.form,
        id: route.params.id
      }).then(res => {
        page.state = 2
        page.connectId  = res.id
      })
    }
  })
}
const handleClose = () => {
  page.state = 1
  page.connectId = null
}

onMounted(() => {
  getConnectInfo()
})

</script>

<template>
<ContainerPage :back="'/monitor/host'">
  <div v-if="page.state === 1" class="box-border w-full h-full flex-center" >
    <el-card  >
      <div class="w-[420px]" >
        <el-form  :model="page.form" :rules="page.rules" ref="formRef" label-position="top" >
          <el-row :gutter="10" >
            <el-col :span="18">
              <el-form-item label="服务器IP地址" prop="ip" >
                <el-input v-model="page.form.ip" placeholder="请输入IP" ></el-input>
              </el-form-item>
            </el-col>
            <el-col :span="6">
              <el-form-item prop="port" label="端口" >
                <el-input v-model="page.form.port" type="number" placeholder="请输入端口" ></el-input>
              </el-form-item>
            </el-col>
            <el-col :span="24">
              <el-form-item label="用户名" prop="username" >
                <el-input v-model="page.form.username" placeholder="请输入用户名" ></el-input>
              </el-form-item>
            </el-col>
            <el-col :span="24">
              <el-form-item label="密码" prop="password" >
                <el-input v-model="page.form.password" placeholder="请输入密码" show-password ></el-input>
              </el-form-item>
            </el-col>
            <el-col :span="24">
              <el-form-item >
                <el-button class="w-full" type="primary" @click="getConnect" >连接</el-button>
              </el-form-item>
            </el-col>
          </el-row>
        </el-form>
      </div>
    </el-card>
  </div>
  <div v-if="page.state === 2" class="w-full h-full box-border overflow-hidden" >
    <Terminal v-if="page.connectId" :id="parseInt(route.params.id)" @dispose="handleClose" />
  </div>
</ContainerPage>
</template>

<style scoped>

</style>