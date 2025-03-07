<script setup>
import { logout,get} from '@/net'
import router from "@/router";
import {useStore} from "@/store";
import {reactive, ref} from "vue";
import {
  Back, Bell,
  ChatDotSquare, Check,
  HelpFilled,
  Location, Lock, Message, Notification,
  Operation,
  Pouring, Search,
  User
} from "@element-plus/icons-vue";
import LightCard from "@/components/LightCard.vue";

const store = useStore();
const loading= ref(true);
const notification =ref([])
const searchInput = reactive({
  type:'1',
  text:""
})
get('api/user/info',(data)=>{
  store.user=data
  loading.value=false
})
const loadNotification=()=> get("/api/notification/list",data => notification.value = data)
loadNotification()
function userLogout() {
  logout(() => router.push("/"))
}
function confirmNotification(id,url) {
  get(`/api/notification/delete?id=${id}`,()=>{
    loadNotification()
    window.location.href=url
  })
}
function deleteAllNotification() {
  get("api/notification/delete-all",loadNotification)
}
</script>

<template>
  <div class="main-content" v-loading="loading" element-loading-text="加载中，请稍等...">
    <el-container style="height: 100%" v-if="!loading">
      <el-header class="main-content-header">
          <el-image class="logo" src="/comments.svg"/>
            <div style="flex: 1;padding: 0 30px;text-align: center">
              <el-input v-model="searchInput.text" style="width: 100%;max-width: 550px" placeholder="搜你想搜">
                <template #prefix>
                  <el-icon><Search/></el-icon>
                </template>
                <template #prepend>
                  <el-select style="width: 120px" v-model="searchInput.type">
                    <el-option value="1" label="帖子广场"></el-option>
                    <el-option value="2" label="校园活动"></el-option>
                    <el-option value="3" label="万能墙"></el-option>
                    <el-option value="4" label="学习交流"></el-option>
                  </el-select>
                </template>
              </el-input>
            </div>
          <div  class="user-info">
            <el-popover placement="bottom" :width="350" trigger="click">
              <template #reference>
                <el-badge style="margin-right: 15px" is-dot :hidden="!notification.length">
                  <div class="notification">
                    <el-icon><Bell/></el-icon>
                    <div style="font-size: 10px">消息</div>
                  </div>
                </el-badge>
              </template>
              <el-empty :image-size="20" description="暂时没有消息哦~" v-if="!notification.length"/>
              <el-scrollbar :max-height="500" v-else>
                <light-card v-for="item in notification" class="notification-item" @click="confirmNotification(item.id,item.url)">
                  <div>
                    <el-tag :type="item.type">消息</el-tag>
                    <span style="font-weight: bold">{{item.title}}</span>
                  </div>
                  <el-divider style="margin: 7px 0 3px 0"/>
                  <div style="font-size: 13px;color: grey">
                    {{item.content}}
                  </div>
                </light-card>
              </el-scrollbar>
              <div style="margin-top: 10px;" v-if="notification.length">
                <el-button size="small" type="info" :icon="Check" @click="deleteAllNotification" style="width: 100%" plain>
                  清除所有未读消息
                </el-button>
              </div>
            </el-popover>
            <div class="profile">
              <div>{{ store.user.username }}</div>
              <div>{{ store.user.email }}</div>
            </div>
            <el-dropdown>
              <el-avatar :src="store.avatarUrl"/>
              <template #dropdown>
                <el-dropdown-item @click="router.push('/index/user-setting')">
                  <el-icon><operation/></el-icon>
                  个人设置
                </el-dropdown-item>
                <el-dropdown-item>
                  <el-icon><Message/></el-icon>
                  消息列表
                </el-dropdown-item>
                <el-dropdown-item @click="userLogout" divided>
                  <el-icon><Back/></el-icon>
                  退出登录
                </el-dropdown-item>
              </template>
            </el-dropdown>
          </div>
      </el-header>
      <el-container>
        <el-aside width="230px">
          <el-scrollbar style="height: calc(100vh - 55px)">
            <el-menu
                router
                :default-active="$route.path"
                style="min-height: calc(100vh - 55px)"
                :default-openeds="['1','2','3']"
            >
              <el-sub-menu index="1">
                <template #title>
                  <el-icon><location/></el-icon>
                  <span><b>校园论坛</b></span>
                </template>
                <el-menu-item index="/index">
                  <template #title>
                    <el-icon><chat-dot-square/></el-icon>
                    帖子广场
                  </template>
                </el-menu-item>
                <el-menu-item index="1-2">
                  <template #title>
                    <el-icon><Notification/></el-icon>
                    校园活动
                  </template>
                </el-menu-item>
                <el-menu-item index="1-3">
                  <template #title>
                    <el-icon><help-filled/></el-icon>
                    万能墙
                  </template>
                </el-menu-item>
                <el-menu-item>
                  <template #title>
                    <el-icon><Pouring/></el-icon>
                    雨云服务器
                    <el-tag style="margin-left: 10px" size="small">合作机构</el-tag>
                  </template>
                </el-menu-item>
              </el-sub-menu>
              <el-sub-menu index="2">
                <template #title>
                  <el-icon><location/></el-icon>
                  <span><b>Dokihub</b></span>
                </template>
                <el-menu-item index="2-1">
                  <template #title>
                    <el-icon><chat-dot-square/></el-icon>
                    学习交流
                  </template>
                </el-menu-item>
                <el-menu-item index="2-2">
                  <template #title>
                    <el-icon><chat-dot-square/></el-icon>
                    项目分享
                  </template>
                </el-menu-item>
              </el-sub-menu>
              <el-sub-menu index="3">
                <template #title>
                  <el-icon><Operation/></el-icon>
                  <span><b>用户中心</b></span>
                </template>
                <el-menu-item index="/index/user-setting">
                  <template #title>
                    <el-icon><User/></el-icon>
                    个人信息设置
                  </template>
                </el-menu-item>
                <el-menu-item index="/index/privacy-setting">
                  <template #title>
                    <el-icon><Lock/></el-icon>
                    帐号安全设置
                  </template>
                </el-menu-item>
              </el-sub-menu>
            </el-menu>
          </el-scrollbar>
        </el-aside>
        <el-main class="main-content-page">
          <el-scrollbar style="height: calc(100vh - 55px)">
            <router-view v-slot="{ Component}">
              <transition name="el-fade-in-linear" mode="out-in">
                <component :is="Component" style="height: 100%"></component>
              </transition>
            </router-view>
          </el-scrollbar>
        </el-main>
      </el-container>
    </el-container>
  </div>
</template>

<style lang="less" scoped>
.notification-item{
  transition: .3s;
  background-color: #dadada;
  &:hover{
    cursor: pointer;
    opacity: 0.7;
  }
}
.dark .notification-item{
  background-color: #282828;
}
.notification{
  font-size: 22px;
  text-align: center;
  line-height: 14px;
  align-content: center;
  transition: color .3s;
  &:hover{
    color: grey;
    cursor: pointer;
  }
}
.main-content-page{
  padding: 0;
  background-color: #f7f8fa;
}
.dark .main-content-page{
  background-color: #1f2023;
}

.main-content{
  height: 100vh;
  width: 100vw;
}
.main-content-header{
  border-bottom: 1px solid var(--el-border-color);
  height: 55px;
  display: flex;
  align-items: center;
  box-sizing: border-box;

  .logo{
    height: 38px;
  }

  .user-info{
    display: flex;
    align-items: center;
    justify-content: flex-end;

    .el-avatar:hover{
      cursor: pointer;
    }
    .profile {
      text-align: right;
      margin-right: 20px;

      :first-child {
        font-size: 18px;
        font-weight: bold;
        line-height: 20px;
      }

      :last-child {
        font-size: 10px;
        color: gray;
      }
    }
  }
}
</style>
