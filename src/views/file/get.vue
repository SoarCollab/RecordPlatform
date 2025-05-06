<script setup>

import ContainerPage from "@/components/ContainerPage.vue";

const formRef = ref(null);
const form = reactive({
  code: '',
});

</script>

<template>
<ContainerPage>
  <div class="flex-center h-full" >
    <div class="w-[520px]" >
      <div class="flex-center text-[26px] font-bold mb-5" >
        通过分享码，获取分享的文件。
      </div>
      <el-form size="large"  >
        <el-row :gutter="10" >
          <el-col :span="20">
            <el-form-item prop="code">
              <el-input v-model="form.code" placeholder="请输入分享码"></el-input>
            </el-form-item>
          </el-col>
          <el-col :span="4">
            <el-form-item>
              <el-button round type="primary" >提取文件</el-button>
            </el-form-item>
          </el-col>
        </el-row>
      </el-form>
    </div>
  </div>
</ContainerPage>
</template>

<style scoped>

</style>