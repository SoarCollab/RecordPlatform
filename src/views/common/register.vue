<script setup>
import {User, Lock, Message, EditPen} from '@element-plus/icons-vue'
import {reactive, ref, shallowRef} from "vue";
import {useRouter} from "vue-router";
import Container from "@/views/common/components/Container.vue";
import {useUserStore} from "@/store/userStore.js";
import {useMessage} from "@/utils/message.js";
import {getEmailCodeApi, registerApi} from "@/api/user.js";
import {useCountdown} from "@vueuse/core";

const router = useRouter()
const formRef = ref()
const coldTime = shallowRef(0)
const isEmailValid = shallowRef(false)
const { remaining, start, stop, pause, resume } = useCountdown(coldTime)

const page = reactive({
  loading: false
})

const formData = reactive({
  username: '',
  password: '',
  password_repeat: '',
  email: '',
  code: ''
})

const onValidate = (prop, isValid) => {
  if(prop === 'email') {
    isEmailValid.value = isValid
  }

}


const validateUsername = (rule, value, callback) => {
  if (value === '') {
    callback(new Error('请输入用户名'))
  } else if(!/^[a-zA-Z0-9\u4e00-\u9fa5]+$/.test(value)){
    callback(new Error('用户名不能包含特殊字符，只能是中文/英文'))
  } else {
    callback()
  }
}

const validatePassword = (rule, value, callback) => {
  if (value === '') {
    callback(new Error('请再次输入密码'))
  } else if (value !== formData.password) {
    callback(new Error("两次输入的密码不一致"))
  } else {
    callback()
  }
}

const rules = {
  username: [
    { validator: validateUsername, trigger: ['blur', 'change'] },
    { min: 2, max: 8, message: '用户名的长度必须在2-8个字符之间', trigger: ['blur', 'change'] },
  ],
  password: [
    { required: true, message: '请输入密码', trigger: 'blur' },
    { min: 6, max: 16, message: '密码的长度必须在6-16个字符之间', trigger: ['blur', 'change'] }
  ],
  password_repeat: [
    { validator: validatePassword, trigger: ['blur', 'change'] },
  ],
  email: [
    { required: true, message: '请输入邮件地址', trigger: 'blur' },
    {type: 'email', message: '请输入合法的电子邮件地址', trigger: ['blur', 'change']}
  ],
  code: [
    { required: true, message: '请输入获取的验证码', trigger: 'blur' },
  ]
}

// ------------------------------- action -------------------------------
const handleGetEmailCode = async () => {
  start(60)
  console.log('发送验证码')
  await getEmailCodeApi(formData.email, 'register')
  console.log('发送验证码成功')
  useMessage().info(`验证码已发送到邮箱: ${formData.email}，请注意查收邮件！`)
}
const doRegister = () => {
  formRef.value.validate((isValid) => {
    if(isValid) {
      page.loading = true
      registerApi({
        username: formData.username,
        password: formData.password,
        email: formData.email,
        code: formData.code
      }).then((res) => {
        router.push('/login')
        useMessage().success(`注册成功，请登录！`)
      })
    }
  })
}

</script>

<template>
  <Container title="注册账号" sub-title="欢迎注册本系统，请您在下方填写相关信息！" >
    <template #form>
      <el-form :model="formData" :rules="rules" @validate="onValidate" ref="formRef">
        <el-form-item prop="username">
          <el-input v-model="formData.username" :maxlength="8" type="text" placeholder="用户名">
            <template #prefix>
              <el-icon><User /></el-icon>
            </template>
          </el-input>
        </el-form-item>
        <el-form-item prop="password">
          <el-input v-model="formData.password" :maxlength="16" type="password" placeholder="密码">
            <template #prefix>
              <el-icon><Lock /></el-icon>
            </template>
          </el-input>
        </el-form-item>
        <el-form-item prop="password_repeat">
          <el-input v-model="formData.password_repeat" :maxlength="16" type="password" placeholder="确认密码">
            <template #prefix>
              <el-icon><Lock /></el-icon>
            </template>
          </el-input>
        </el-form-item>
        <el-form-item prop="email">
          <el-input v-model="formData.email" placeholder="电子邮件地址">
            <template #prefix>
              <el-icon><Message /></el-icon>
            </template>
          </el-input>
        </el-form-item>
        <el-form-item prop="code">
          <el-row :gutter="10" style="width: 100%">
            <el-col :span="17">
              <el-input v-model="formData.code" :maxlength="6" type="text" placeholder="请输入验证码">
                <template #prefix>
                  <el-icon><EditPen /></el-icon>
                </template>
              </el-input>
            </el-col>
            <el-col :span="7">
              <el-button
                plain
                type="success"
                @click="handleGetEmailCode"
                :disabled="!isEmailValid || remaining > 0">
                {{ remaining > 0 ? '请稍后 ' + remaining + ' 秒' : '获取验证码' }}
              </el-button>
            </el-col>
          </el-row>
        </el-form-item>
        <el-form-item>
          <el-button class="w-full" type="warning" @click="doRegister" block>立即注册</el-button>
        </el-form-item>
      </el-form>
    </template>
    <template #action>
      <div class="flex-center gap-5 mt-5 " >
        <el-link type="primary" @click="router.push('/login')">已有账号？立即登陆</el-link>
      </div>
    </template>
  </Container>
</template>

<style scoped>

</style>