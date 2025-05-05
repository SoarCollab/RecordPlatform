<script setup>
import {loginApi} from "@/api/monitor.js";
import {useAuthorization} from "@/composables/authorization.js";
import {useRouter} from 'vue-router'
const router = useRouter();
const formRef = ref(null);
const formData = reactive({
  username: '',
  password: '',
});

const rules = {
  username: [
    { required: true, message: '请输入用户名', trigger: 'blur' },
  ],
  password: [
    { required: true, message: '请输入密码', trigger: 'blur' },
  ],
};

const {monitorAuth} = useAuthorization();

const onLogin = async () => {
  await formRef.value.validate().catch(() => false);
  const {data} = await loginApi({
    username: formData.username,
    password: formData.password,
  })
  monitorAuth.value = data.token;
  router.push('/monitor/host');

}

</script>

<template>
  <div class="w-full h-full flex-center" >
    <div class="max-w-[420px] min-w-[375px]" >
      <el-card>
        <template #header>
          <div class="text-center">
            <p>登录后，你将可以看到你的各个服务器运行状况</p>
          </div>
        </template>
        <el-form class="w-full" :model="formData" :rules="rules" ref="formRef" size="large" >
          <el-form-item prop="username">
            <el-input v-model="formData.username" placeholder="用户名" ></el-input>
          </el-form-item>
          <el-form-item prop="password">
            <el-input v-model="formData.password" placeholder="密码" show-password></el-input>
          </el-form-item>
          <el-form-item>
            <el-button class="w-full" type="primary" @click="onLogin" >登录</el-button>
          </el-form-item>
        </el-form>
      </el-card>
    </div>
  </div>
</template>

<style scoped>

</style>