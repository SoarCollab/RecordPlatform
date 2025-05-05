<script setup>
import {User, Lock} from '@element-plus/icons-vue'
import {reactive, ref} from "vue";
import {useRouter} from "vue-router";
import Container from "@/views/common/components/Container.vue";
import {useUserStore} from "@/store/userStore.js";
import {useMessage} from "@/utils/message.js";

const router = useRouter()
const formRef = ref()
const {loginAction} = useUserStore()

const page = reactive({
  loading: false
})

const formData = reactive({
  username: '',
  password: '',
})
const rules = {
  username: [
    { required: true, message: '请输入用户名' }
  ],
  password: [
    { required: true, message: '请输入密码'}
  ]
}

// --------------------------- action ---------------------------
const doLogin = () => {
  formRef.value.validate((isValid) => {
    if(isValid) {
      page.loading = true
      loginAction(formData).then((res) => {
        router.push('/')
        useMessage().success(`登录成功，欢迎 ${res.username} 来到我们的系统！`)
      }).finally(() => {
        page.loading = false
      })
    }
  });
}

</script>

<template>
  <Container >

    <template #form>
      <el-form :model="formData" :rules="rules" ref="formRef" size="large" >
        <el-form-item prop="username">
          <el-input v-model="formData.username" maxlength="100" type="text" placeholder="用户名/邮箱">
            <template #prefix>
              <el-icon>
                <User/>
              </el-icon>
            </template>
          </el-input>
        </el-form-item>
        <el-form-item prop="password">
          <el-input v-model="formData.password" type="password" maxlength="100" placeholder="密码">
            <template #prefix>
              <el-icon>
                <Lock/>
              </el-icon>
            </template>
          </el-input>
        </el-form-item>
        <el-form-item>
          <el-button
            class="w-full"
            block
            type="primary"
            :loading="page.loading"
            @click="doLogin">
            立即登录
          </el-button>
        </el-form-item>
      </el-form>
    </template>

    <template #action>
      <div class="flex-center gap-5 mt-5 " >
        <el-link type="primary" @click="router.push('/register')">注册账号</el-link>
        <el-divider direction="vertical"></el-divider>
        <el-link type="primary" @click="router.push('/forget')">忘记密码</el-link>
      </div>
    </template>

  </Container>
</template>

<style scoped>

</style>