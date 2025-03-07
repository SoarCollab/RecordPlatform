<script setup>

import LightCard from "@/components/LightCard.vue";
import {
  Calendar,
  Clock,
  CollectionTag,
  Compass,
  Document,
  Edit,
  EditPen,
  Picture,
  Link,
  Microphone, Star, CircleCheck, FolderOpened, ArrowLeft, ArrowLeftBold, ArrowRightBold
} from "@element-plus/icons-vue";
import Weather from "@/components/Weather.vue";
import {computed, reactive, ref, watch} from "vue";
import {get} from "@/net";
import TopicEditor from "@/components/TopicEditor.vue";
import {useStore} from "@/store";
import axios from "axios";
import ColorDot from "@/components/ColorDot.vue";
import router from "@/router";
import TopicTag from "@/components/TopicTag.vue";
import TopicCollectList from "@/components/TopicCollectList.vue";

const store=useStore()
const today=computed(()=>{
  const data= new Date()
  return `${data.getFullYear()} 年 ${data.getMonth()+1} 月 ${data.getDate()} 日`
})
const IP=ref("127.0.0.1")
const weather=reactive({
  location:{},
  now:{},
  hourly:[],
  success:false
});
const editor=ref(false)
const topics=reactive({
  list:[],
  type:0,
  page:0,
  end:false,
  top:[]
})
const collects=ref(false)
watch(()=>topics.type,()=>restList(),{immediate : true})

get('api/forum/top-topic',data=>topics.top=data)
function updateList(){
  if (topics.end) return
  get(`/api/forum/list-topic?page=${topics.page}&type=${topics.type}`, data => {
    if(data){
      data.forEach(d=> topics.list.push(d))
      topics.page++
    }
    if(!data||data.length<10)
      topics.end=true
  })
}

function onTopicCreate() {
  editor.value=false
  restList()
}
function restList() {
  topics.page=0
  topics.end=false
  topics.list=[]
  updateList()
}
get('api/forum/getIP',(data)=>{
  IP.value=data
})
navigator.geolocation.getCurrentPosition(position => {
  const longitude = position.coords.longitude
  const latitude = position.coords.latitude
  position.coords
  get(`api/forum/weather?longitude=${longitude}&latitude=${latitude}`,data=>{
    Object.assign(weather,data)
    weather.success =true
  })
},error=>{
  console.info(error)
  console.log("地理位置信息获取失败，请检查浏览器设置（注意：chrome浏览器存在获取失败的问题，请更换其他浏览器再次尝试！）")
  get(`api/forum/weather?longitude=116.40529&latitude=39.90499`,data=>{
  Object.assign(weather,data)
  weather.success =true
})
},{
  timeout:10000,
  enableHighAccuracy:true
})
</script>

<template>
<div style="display: flex;margin: 20px auto;gap: 20px;max-width: 900px">
  <div style="flex: 1">
    <LightCard>
      <div class="create-topic"  @click="editor=true">
        <el-icon class="icon"><EditPen/></el-icon>
        点击发表主题...
      </div>
      <div style="margin-top: 10px;display: flex;gap: 13px;font-size: 18px;color: grey">
        <el-icon><Edit /></el-icon>
        <el-icon><Document /></el-icon>
        <el-icon><Compass /></el-icon>
        <el-icon><Picture /></el-icon>
        <el-icon><Microphone /></el-icon>
      </div>
    </LightCard>
    <light-card style="margin-top: 10px;display: flex;flex-direction: column;gap: 10px">
      <div v-for="item in topics.top" class="top-topic" @click="router.push(`/index/topic-detail/${item.id}`)">
        <el-tag type="info" size="small">置顶</el-tag>
        <div>{{item.title}}</div>
        <div>{{new Date(item.time).toLocaleDateString()}}</div>
      </div>
    </light-card>
    <light-card style="margin-top: 10px;display: flex;gap: 7px">
      <div :class="`type-select-card ${topics.type===item.id? 'active' : ''}`"
           @click="topics.type = item.id"
           v-for="item in store.forum.types">
        <color-dot :color="item.color"/>
        <span style="margin-left: 5px">{{item.name}}</span>
      </div>
    </light-card>
    <transition name="el-fade-in" mode="out-in">
      <div v-if="topics.list.length">
        <div style="margin-top: 10px;display: flex;flex-direction: column;gap: 10px"
        v-infinite-scroll="updateList">
          <light-card  v-for="item in topics.list" class="topic-card"
          @click="router.push('/index/topic-detail/'+item.id)">
            <div style="display: flex">
              <div>
                <el-avatar :size="30"
                           :src="store.avatarUserUrl(item.avatar)"/>
              </div>
              <div style="margin-left: 7px;transform: translateY(-2px)">
                <div style="font-size: 13px;font-weight: bold">{{item.username}}</div>
                <div style="font-size: 13px;color: grey">
                  <el-icon style="margin-right: 3px;translate: 0 2px;"><Clock/></el-icon>
                  <span>{{new Date(item.time).toLocaleString()}}</span>
                </div>
              </div>
            </div>
            <div style="margin-top: 5px">
              <TopicTag :type="item.type"/>
              <span style="font-weight: bold">{{item.title}}</span>
            </div>
            <div class="topic-content">{{item.text}}</div>
            <div style="display: grid;grid-template-columns: repeat(3,1fr);grid-gap: 10px">
              <el-image class="topic-image" v-for="img in item.images" :src="img" fit="cover"></el-image>
            </div>
            <div style="display: flex;gap: 20px;font-size: 13px;margin-top: 10px;opacity: 0.8">
              <div>
                <el-icon style="vertical-align: middle;translate: 0 -1px;"><CircleCheck/></el-icon> {{item.like}}点赞
              </div>
              <div>
                <el-icon style="vertical-align: middle;translate: 0 -1px;"><Star/></el-icon> {{item.collect}}收藏
              </div>
            </div>
          </light-card>
        </div>
      </div>
    </transition>
  </div>
  <div style="width: 300px">
    <div style="position: sticky;top: 20px">
      <light-card>
        <div class="collect-list-button" @click="collects=true">
          <span><el-icon style="margin-right: 3px;transform: translateY(2px)"><FolderOpened/></el-icon>查看我的收藏</span>
          <el-icon style="transform: translateY(3px)"><ArrowRightBold/></el-icon>
        </div>
      </light-card>
      <LightCard style="margin-top: 10px">
        <div style="font-weight: bold;">
          <el-icon class="icon"><CollectionTag/></el-icon>
          论坛公告栏
        </div>
        <el-divider style="margin: 10px 0"/>
        <div style="font-size: 14px;margin: 10px;color: grey">
          你可以在这里与他人畅聊技术，分享经验，结识更多优秀的小伙伴。
        </div>
      </LightCard>
      <LightCard style="margin-top: 10px">
        <div style="font-weight: bold;">
          <el-icon class="icon">
            <Calendar/>
          </el-icon>
          今日天气
        </div>
        <el-divider style="margin: 10px 0"/>
        <Weather :data="weather"/>
      </LightCard>
      <light-card>
        <div class="info-text">
          <div>当前日期</div>
          <div>{{today}}</div>
        </div>
        <div class="info-text">
          <div>当前IP</div>
          <div>{{IP}}</div>
        </div>
      </light-card>
      <div style="font-size: 14px;margin-top: 10px;color: grey">
        <el-icon class="icon"><Link/></el-icon>
        友情链接 暂无
        <el-divider style="margin: 10px 0"/>
      </div>
    </div>
  </div>
  <TopicEditor :show="editor" @success="onTopicCreate" @close="editor=false"></TopicEditor>
  <topic-collect-list :show="collects" @close="collects=false"></topic-collect-list>
</div>
</template>

<style lang="less" scoped>
.collect-list-button{
  font-size: 14px;
  font-weight: bold;
  display: flex;
  justify-content: space-between;
  transition: .3s;

  &:hover{
    cursor: pointer;
    opacity: 0.6;
  }
}
.top-topic{
  display: flex;

  div:first-of-type{
    font-size: 14px;
    margin-left: 10px;
    font-weight: bold;
    opacity: 0.8;
    transition: color .3s;

    &:hover{
      color: grey;
    }
  }

  div:nth-of-type(2){
    flex: 1;
    color: grey;
    font-size: 13px;
    text-align: right;
  }
  &:hover{
    cursor: pointer;
  }
}
.type-select-card{
  background-color: #f5f5f5f5;
  padding: 2px 7px;
  font-size: 14px;
  border-radius: 3px;
  box-sizing: border-box;
  transition: background-color .3s;

  &.active {
    border: solid 1px #ead4c4;
  }
  &:hover {
    cursor: pointer;
    background-color: #dadada;
  }
}
.topic-card{
  padding: 15px;

  &:hover{
    scale: 1.02;
    cursor: pointer;
  }
  .topic-content{
    font-size: 13px;
    color: grey;
    margin: 10px 0;
    display: -webkit-box;
    -webkit-box-orient: vertical;
    -webkit-line-clamp: 3;
    overflow: hidden;
    text-overflow: ellipsis;
  }
  .topic-image{
    width: 100%;
    height: 100%;
    max-height: 110px;
    border-radius: 5px;
  }
}

.info-text{
  display: flex;
  justify-content: space-between;
  font-size: 14px;
  color: grey;
}
.create-topic{
  background-color: #e4e4e4;
  border-radius: 5px;
  height: 40px;
  font-size: 14px;
  line-height: 40px;
  padding: 0 10px;
  color: grey;

  &:hover{
    cursor: pointer;
  }
}
.dark {
  .create-topic {
    background-color: #454545;
    color: #939393;
  }
  .type-select-card {
    background-color: #282828;

    &.active {
      border: solid 1px #64594b;
    }

    &:hover {
      background-color: #5e5e5e;
    }
  }
}
.icon{
  margin-right: 3px;
  translate: 0 2px
}

</style>