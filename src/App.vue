<script setup>
import TaskNotification from '@/components/TaskNotification.vue'
</script>

<template>
  <el-config-provider>
    <div class="w-full h-full font-chinese antialiased" >
      <RouterView/>
      <TaskNotification />
    </div>
  </el-config-provider>
</template>

