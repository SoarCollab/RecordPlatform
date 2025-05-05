<script setup>

import ContainerPage from "@/components/ContainerPage.vue";
import {useUserStore} from "@/store/userStore.js";
import {storeToRefs} from "pinia";
import MenuIcon from "@/components/Icon/MenuIcon.vue";
import {computed, reactive, shallowRef} from "vue";
import {dayjs} from "element-plus";
import {useCountdown} from "@vueuse/core";
import {changeEmail, changePassword, getEmailCodeApi} from "@/api/user.js";
import {useMessage} from "@/utils/message.js";
import {useRouter} from "vue-router";

defineOptions({
  name: 'Personal'
})

const userStore = useUserStore()
const {getUserinfoAction} = userStore
const {avatar, userInfo} = storeToRefs(userStore)

const registerTime = computed(()=>{
  return dayjs(userInfo.registerTime).format('YYYY-MM-DD')
})

const isEmailValid = shallowRef(false)
const coldTime = shallowRef(0)
const { remaining, start, stop, pause, resume } = useCountdown(coldTime)

const formRef = ref(null)
const page = reactive({
  loading: false,
  dialog: {
    visible: false
  },
  form: {
    model: {
      password: '',
      new_password: '',
      email: '',
      code: ''
    },
    rules: {
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
      new_password: [
        { required: true, message: '请输入新密码', trigger: 'blur' },
        { min: 6, max: 16, message: '密码的长度必须在6-16个字符之间', trigger: ['blur'] }
      ]
    },

  },
  tabs: {
    active: '1'
  }
})


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

const doReset = async () => {
  try {
    const isValid = await formRef.value.validate().catch(() => false);
    if (!isValid) return;

    page.loading = true;

    if (page.tabs.active === '1') {
      await changeEmail({
        email: page.form.model.email,
        code: page.form.model.code
      });
      await getUserinfoAction();
    }

    if (page.tabs.active === '2') {
      await changePassword({
        password: page.form.model.password,
        new_password: page.form.model.new_password
      });
      useRouter().push('/login');
    }

    useMessage().success('修改成功');
  } catch (e) {

  } finally {
    page.loading = false;
  }
};


const dialogClose = () => {
  page.dialog.visible = false
}

const diaogOpen = () => {
  page.dialog.visible = true
}

const handleTabChange = (tab) => {
  if(tab === '2') {
    page.form.model.password = ''
    page.form.model.new_password = ''
  }
  if(tab === '1') {
    page.form.model.code = ''
    page.form.model.email = ''
  }
}

</script>

<template>
  <ContainerPage>
    <el-row :gutter="20">
      <el-col :span="7">
        <el-card class="box-card">
          <div class="flex flex-col items-center gap-3" >
            <el-avatar :size="120" :src="avatar"/>
            <span class="font-bold" >{{userInfo.username}}</span>
            <el-divider />
            <div class="w-full flex flex-col gap-5" >
              <div class="flex gap-3 items-center justify-between" >
                <div class="flex items-center gap-2" >
                  <MenuIcon icon="user" />
                  <span class="font-bold text-[14px]" >角色：</span>
                </div>
                <el-tag type="success">{{userInfo.role}}</el-tag>
              </div>
              <div class="flex gap-3 items-center justify-between" >
                <div class="flex items-center gap-2" >
                  <MenuIcon icon="Message" />
                  <span class="font-bold text-[14px]" >电子邮件：</span>
                </div>
                <span class="font-bold text-[14px]" >{{userInfo.email}}</span>
              </div>
              <div class="flex gap-3 items-center justify-between" >
                <div class="flex items-center gap-2" >
                  <MenuIcon icon="Calendar" />
                  <span class="font-bold text-[14px]" >注册日期：</span>
                </div>
                <span class="font-bold text-[14px]" >{{registerTime}}</span>
              </div>
            </div>
            <el-divider style="margin-bottom: 0" />
            <div class="w-full flex-center">
              <el-button plain type="primary" @click="diaogOpen">
                修改信息
              </el-button>
            </div>
          </div>
        </el-card>
      </el-col>
      <el-col :span="17">
        <el-card class="box-card">
          <template #header>
            <div class="card-title">
              操作日志
            </div>
          </template>
        </el-card>
      </el-col>
    </el-row>
  </ContainerPage>
  <el-dialog v-model="page.dialog.visible" title="修改信息" width="30%" :show-close="false" >
    <el-form :model="page.form.model" :rules="page.form.rules" @validate="onValidate">
      <el-tabs v-model="page.tabs.active" @tab-change="handleTabChange" >
        <el-tab-pane label="修改电子邮件" name="1">
          <el-row :gutter="10">
            <el-col :span="24">
              <el-form-item prop="email">
                <template #prefix>
                  <MenuIcon icon="Message" />
                </template>
                <el-input v-model="page.form.model.email" placeholder="请输入新电子邮件" />
              </el-form-item>
            </el-col>
            <el-col :span="17">
              <el-form-item prop="code">
                <template #prefix>
                  <MenuIcon icon="Key" />
                </template>
                <el-input v-model="page.form.model.code" placeholder="请输入验证码" />
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
          </el-row>
        </el-tab-pane>
        <el-tab-pane label="修改登陆密码" name="2">
          <el-form-item prop="password">
            <template #prefix>
              <MenuIcon icon="Lock" />
            </template>
            <el-input v-model="page.form.model.password" show-password placeholder="请输入旧密码" />
          </el-form-item>
          <el-form-item prop="new_password">
            <template #prefix>
              <MenuIcon icon="Lock" />
            </template>
            <el-input v-model="page.form.model.new_password" show-password placeholder="请输入新密码" />
          </el-form-item>
        </el-tab-pane>
      </el-tabs>
    </el-form>
    <template #footer>
      <span class="flex-center">
        <el-button @click="dialogClose">取 消</el-button>
        <el-button type="primary" @click="doReset">确 定</el-button>
      </span>
    </template>
  </el-dialog>
</template>

<style scoped>

</style>