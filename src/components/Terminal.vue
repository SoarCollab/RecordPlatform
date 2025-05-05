
<script setup>
import {markRaw, onBeforeUnmount, onMounted, ref} from "vue";
import {useMessage} from "@/utils/message.js";
import {AttachAddon} from "@xterm/addon-attach";
import {Terminal} from "@xterm/xterm";
import "@xterm/xterm/css/xterm.css"
const props=defineProps({
  id:Number
})
const emits=defineEmits(['dispose'])
const terminalRef=ref(null)
console.log('我看看怎么个事', props.id)

const socket = ref(null)
const attachAddon = ref(null)
const term = ref(null)
const loading = shallowRef(false)

const initWebSocket = (id) => {

  try {
    console.log('我开始连接了', id, `${import.meta.env.VITE_BASE_WS_URL}/${id}`)

    loading.value = true

    socket.value = markRaw(new WebSocket(`ws://47.92.80.128:8081/terminal/${id}`))

    socket.value.onclose = evt => {
      if(evt.code !== 1000) {
        useMessage().warning(`连接失败: ${evt.reason}`)
      } else {
        useMessage().success('远程SSH连接已断开')
      }
      emits('dispose')
    }

    attachAddon.value = markRaw(new AttachAddon(socket.value))
    term.value = markRaw(new Terminal({
      lineHeight: 1.2,
      rows: 20,
      fontSize: 13,
      fontFamily: "Monaco, Menlo, Consolas, 'Courier New', monospace",
      fontWeight: "bold",
      theme: {
        background: '#000000'
      },
      // 光标闪烁
      cursorBlink: true,
      cursorStyle: 'underline',
      scrollback: 100,
      tabStopWidth: 4,
    }))
    term.value.loadAddon(attachAddon.value);
    term.value.open(terminalRef.value)
    term.value.focus()
  }catch (e) {
    console.log(e)
  }finally {
    loading.value = false
  }
}

const close = () => {
  socket.value && socket.value.close()
  term.value && term.value.dispose()
}


onMounted( ()=>{
  initWebSocket(props.id)
})

onBeforeUnmount(() => {
  close()
})

</script>

<template>
  <div v-loading="loading" ref="terminalRef" class="xterm box-border h-full"/>
</template>

<style scoped>

</style>