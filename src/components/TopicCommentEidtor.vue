<script setup>
import "@vueup/vue-quill/dist/vue-quill.snow.css"
import {Delta, Quill, QuillEditor} from "@vueup/vue-quill";
import {ref} from "vue";
import {post} from "@/net";
import {ElMessage} from "element-plus";
const props=defineProps({
  show:Boolean,
  tid:String,
  quote:Object
})
const emit=defineEmits(['close','comment'])
const content=ref()
const init = () => content.value=new Delta()
function submitComment() {
  if (deltaToText(content.value).length > 2000) {
    ElMessage.warning("评论字数超过最大限制，请修改评论内容！")
    return
  }
  post('/api/forum/add-comment',{
    tid: props.tid,
    quote: props.quote ? props.quote.id : -1,
    content: JSON.stringify(content.value)
  },()=>{
    ElMessage.success('发表评论成功！')
    emit('comment')
  })
}
function deltaToSimpleText(delta) {
  let str = deltaToText(JSON.parse(delta))
  if (str.length>35) str = str.substring(0,35) +'...'
  return str
}

function deltaToText(delta) {
  if(!delta?.ops) return ""
  let str = ""
  for(let op of delta.ops)
    str+=op.insert
  return str.replace(/\s/g,"");
}
</script>

<template>
  <div>
    <el-drawer :model-value="show"
               :title="quote ? `发表对评论：${deltaToSimpleText(quote.content)} 的回复` : '发表评论'"
               @open="init"
               direction="btt" :size="270"
               :close-on-click-modal="false"
               @close="emit('close')">
      <div>
        <div>
          <quill-editor style="height: 120px"
                        placeholder="请文明发表评论，谢谢！"
                        v-model:content="content"/>
        </div>
        <div style="margin-top: 10px;display: flex">
          <div style="flex: 1;color:grey;font-size: 13px">
            当前字数 {{deltaToText(content).length}} （最大支持2000字）
          </div>
          <el-button type="success" @click="submitComment" plain>发表评论</el-button>
        </div>
      </div>
    </el-drawer>
  </div>
</template>

<style lang="less" scoped>
:deep(.el-drawer){
  width: 800px;
  margin: 20px auto;
  border-radius: 10px;
}
:deep(.el-drawer__header){
  margin: 0;
}
:deep(.el-drawer__body){
  padding: 10px;
}
</style>