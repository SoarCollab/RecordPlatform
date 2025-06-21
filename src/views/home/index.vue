// src/views/home/index.vue
<script setup>

import {useMessage} from "@/utils/message.js";
import ContainerPage from "@/components/ContainerPage.vue";


const message = useMessage()

</script>

<template>
  <div>
    <h1>欢迎登录</h1>
  </div>
  <!-- <ContainerPage>
    <el-button  type="primary" @click="message.success('测试')">测试</el-button>
  </ContainerPage> -->
</template>

<style scoped>

</style>