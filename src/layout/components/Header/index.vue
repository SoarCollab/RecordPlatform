<script setup>
import LogoSvgIcon from "@/components/Icon/svg/LogoSvgIcon.vue";

import {useAppStore} from "@/store/appStore.js";
import {storeToRefs} from "pinia";
import {useUserStore} from "@/store/userStore.js";
import Breadcrumbs from "@/components/Breadcrumbs.vue";
import {useRouter} from "vue-router";
import {Moon, Sunny} from '@element-plus/icons-vue'

defineOptions({
  name: 'HeaderBar'
})

const router = useRouter()
const appStore = useAppStore()
const {layout, isDark} = storeToRefs(appStore)
const userStore = useUserStore()
const {logoutAction} = userStore
const {avatar, userInfo} = storeToRefs(userStore)

const appTitle = import.meta.env.VITE_APP_TITLE

const siderWidth = computed(() => {
  return layout.value.siderWidth + 'px'
})

const userRole = computed(() => {
  switch (userInfo.value.role) {
    case 'admin':
      return '管理员'
    case 'monitor':
      return '监控员'
    case 'guest':
      return '游客'
    default:
      return '未知角色'
  }
})


/**
 * 点击菜单
 * @param command
 */
const handleCommand = (command) => {
  switch (command) {
    case 'logout':
      console.log('logout')
      router.push('/login')
      break;
    case 'personal':
      console.log('personal')
      router.push('/personal')
      break;

  }
}




</script>

<template>
  <div class="w-full h-full flex">
    <div class="shrink-0 left flex-center cursor-pointer">
      <LogoSvgIcon/>
      <span class="font-bold ">
      {{ appTitle }}
    </span>
    </div>
    <div class="grow-1 px-3 flex items-center">
      <Breadcrumbs/>
    </div>
    <div class="shrink-0 flex-center pr-3">
      <!-- 明暗主题 -->
      <el-switch
        v-model="isDark"
        :active-action-icon="Moon"
        :inactive-action-icon="Sunny"
        class="mr-2"
      />
      <!-- 角色 -->
      <div class="flex-center mr-2 font-bold text-[20px]" >
        {{ userRole }}
      </div>
      <!-- 头像 -->
      <el-dropdown class="cursor-pointer" :hide-on-click="false" @command="handleCommand">
        <el-avatar :size="32" :src="avatar"/>
        <template #dropdown>
          <el-dropdown-menu>
            <el-dropdown-item>{{ userInfo?.username }}</el-dropdown-item>
            <el-dropdown-item command="personal" divided>个人中心</el-dropdown-item>
            <el-dropdown-item command="logout">退出登陆</el-dropdown-item>
          </el-dropdown-menu>
        </template>
      </el-dropdown>

    </div>
  </div>
</template>

<style scoped>
.left {
  width: v-bind(siderWidth);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
</style>