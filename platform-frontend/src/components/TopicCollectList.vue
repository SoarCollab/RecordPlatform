<script setup>
import {get} from "@/net";
import {ref} from "vue";
import LightCard from "@/components/LightCard.vue";
import router from "@/router";
import TopicTag from "@/components/TopicTag.vue";
import {ElMessage} from "element-plus";

defineProps({
  show:Boolean
})
const emit=defineEmits(['close'])

function init() {
  get('/api/forum/collects',data=>list.value=data)
}
const list=ref([])
function deleteCollect(index,tid) {
  get(`api/forum/interact?tid=${tid}&type=collect&state=false`,()=>{
    ElMessage.success('已取消收藏！')
    list.value.splice(index,1)
  })
}
</script>

<template>
  <el-drawer :model-value="show"
             @close="emit('close')"
             @open="init"
             title="帖子收藏列表">
    <div class="collect-list">
      <light-card
          class="topic-card"
          @click="router.push(`/index/topic-detail/${item.id}`)"
          v-for="(item,index) in list">
        <topic-tag :type="item.type"/>
        <div class="topic-title">
          <b>{{item.title}}</b>
        </div>
        <el-link type="danger" @click.stop="deleteCollect(index,item.id)">取消收藏</el-link>
      </light-card>
    </div>
  </el-drawer>
</template>

<style scoped>
.collect-list{
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.topic-card{
  background-color: rgba(128, 128, 128, 0.2);
  transition: .3s;
  display: flex;
  justify-content: space-between;

  .topic-title{
    margin-left: 5px;
    font-size: 14px;
    flex: 1;
    white-space: nowrap;
    display: block;
    overflow: hidden;
    text-overflow: ellipsis;
  }

  &:hover {
    scale: 1.02;
    cursor: pointer;
  }
}
</style>