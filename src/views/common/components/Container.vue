<script setup>

defineOptions({
  name: 'Container'
})

const props = defineProps({
  title: {
    type: String,
    default: '登录'
  },
  subTitle: {
    type: String,
    default: '在进入系统之前请先输入用户名和密码进行登录!'
  },
  bgUrl: {
    type: String,
    default: 'https://pic.vjshi.com/2020-12-11/12eafa35d49ca6429d5ceccdae803003/online/bc1a3a3eb5ef994605b751b1c6839303.jpg?x-oss-process=style/resize_w_720'
  }
})


</script>

<template>
  <div class="bg-box" >
    <img class="block w-full h-full" :src="props.bgUrl" alt="">
  </div>
  <div class="w-full h-full flex-center" >
    <div class="p-5 bg-white rounded-md">
      <!-- 标题 -->
      <div class="flex flex-col gap-5 mb-5">
        <div class="text-align-center text-[26px] font-bold" >{{props.title}}</div>
        <div class="text-[17px] text-gray text-align-center">{{ props.subTitle }}</div>
      </div>
      <!-- 表单 -->
      <div class="pb-2 min-w-[375px] max-w-[412px]">
        <slot name="form"></slot>
      </div>
      <!-- 操作 -->
      <div class="flex flex-col gap-3" >
        <slot name="action"></slot>
      </div>
    </div>
  </div>
</template>

<style scoped>
.bg-box{
  position: absolute;
  top: 0;
  left: 0;
  width: 100%;
  height: 100%;
  z-index: -1;
}
</style>