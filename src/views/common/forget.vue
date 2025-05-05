<script setup>
import {reactive, ref, shallowRef} from "vue";
import {EditPen, Lock, Message, Back} from "@element-plus/icons-vue";
import Container from "@/views/common/components/Container.vue";
import {useCountdown} from "@vueuse/core";
import {getEmailCodeApi, resetConfirmApi, resetPasswordApi} from "@/api/user.js";
import {useMessage} from "@/utils/message.js";
import {useRouter} from "vue-router";
const router = useRouter()
const active = shallowRef(0)
const formRef = ref()
const isEmailValid = shallowRef(false)
const coldTime = shallowRef(0)
const { remaining, start, stop, pause, resume } = useCountdown(coldTime)

const form = reactive({
  email: '',
  code: '',
  password: '',
  password_repeat: '',
})

const validatePassword = (rule, value, callback) => {
  if (value === '') {
    callback(new Error('请再次输入密码'))
  } else if (value !== form.password) {
    callback(new Error("两次输入的密码不一致"))
  } else {
    callback()
  }
}

const rules = {
  email: [
    { required: true, message: '请输入邮件地址', trigger: 'blur' },
    {type: 'email', message: '请输入合法的电子邮件地址', trigger: ['blur', 'change']}
  ],
  code: [
    { required: true, message: '请输入获取的验证码', trigger: 'blur' },
  ],
  password: [
    { required: true, message: '请输入密码', trigger: 'blur' },
    { min: 6, max: 16, message: '密码的长度必须在6-16个字符之间', trigger: ['blur'] }
  ],
  password_repeat: [
    { validator: validatePassword, trigger: ['blur', 'change'] },
  ],
}



const onValidate = (prop, isValid) => {
  if(prop === 'email')
    isEmailValid.value = isValid
}

const validateEmail = async () => {
  start(60)
  console.log('发送验证码')
  await getEmailCodeApi(form.email, 'reset')
  console.log('发送验证码成功')
  useMessage().info(`验证码已发送到邮箱: ${form.email}，请注意查收邮件！`)
}

const confirmReset = () => {
  formRef.value.validate((isValid) => {
    if(isValid) {
      resetConfirmApi({
        email: form.email,
        code: form.code
      }).then(() => {
        active.value++
      })
    }
  })
}

const doReset = () => {
  formRef.value.validate((isValid) => {
    if(isValid) {
      resetPasswordApi({
        email: form.email,
        code: form.code,
        password: form.password
      }).then(() => {
        useMessage().success(`密码重置成功，请重新登录`)
        router.push('/login')
      })
    }
  })
}

</script>

<template>
  <Container title="忘记密码" sub-title="请完成以下步骤重置密码！">
    <template #form>
      <div class="max-w-[375px] flex flex-col gap-3" >
        <el-steps :active="active" finish-status="success" align-center>
          <el-step title="验证电子邮件" />
          <el-step title="重新设定密码" />
        </el-steps>
        <transition name="el-fade-in-linear" mode="out-in">
          <el-form v-if="active === 0" :model="form" :rules="rules" @validate="onValidate" ref="formRef">
            <el-row :gutter="10">
              <el-col :span="24" >
                <el-form-item prop="email">
                  <el-input v-model="form.email" placeholder="重置密码电子邮件地址">
                    <template #prefix>
                      <el-icon><Message /></el-icon>
                    </template>
                  </el-input>
                </el-form-item>
              </el-col>
              <el-col :span="17">
                <el-form-item prop="code">
                  <el-input v-model="form.code" :maxlength="6" type="text" placeholder="请输入验证码">
                    <template #prefix>
                      <el-icon><EditPen /></el-icon>
                    </template>
                  </el-input>
                </el-form-item>
              </el-col>
              <el-col :span="7">
                <el-form-item>
                  <el-button
                    class="w-full"
                    type="success"
                    @click="validateEmail"
                    :disabled="!isEmailValid || remaining > 0">
                    {{remaining > 0 ? '请稍后 ' + remaining + ' 秒' : '获取验证码'}}
                  </el-button>
                </el-form-item>
              </el-col>
              <el-col :span="24">
                <el-button class="w-full" @click="confirmReset()" type="danger" block>验证邮箱</el-button>
              </el-col>
            </el-row>
          </el-form>
        </transition>
        <transition name="el-fade-in-linear" mode="out-in">
          <el-form v-if="active === 1" :model="form" :rules="rules" @validate="onValidate" ref="formRef">
            <el-form-item prop="password">
              <el-input v-model="form.password" :maxlength="16" type="password" placeholder="新密码">
                <template #prefix>
                  <el-icon><Lock /></el-icon>
                </template>
              </el-input>
            </el-form-item>
            <el-form-item prop="password_repeat">
              <el-input v-model="form.password_repeat" :maxlength="16" type="password" placeholder="重复新密码">
                <template #prefix>
                  <el-icon><Lock /></el-icon>
                </template>
              </el-input>
            </el-form-item>
            <el-form-item>
              <el-button
                class="w-full"
                @click="doReset()"
                type="danger" block>
                重置密码
              </el-button>
            </el-form-item>
          </el-form>
        </transition>
      </div>
    </template>
    <template #action>
      <div class="flex-center gap-5 mt-5 ">
        <el-link type="primary" @click="router.push('/login')">返回登录</el-link>
      </div>
    </template>
  </Container>
</template>

<style scoped>

</style>