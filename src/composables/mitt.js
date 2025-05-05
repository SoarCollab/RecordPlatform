import mitt from "mitt";
import {useRoute, useRouter} from "vue-router";

const router = useRouter()
const route = useRoute()


const eventBus = mitt()
export default eventBus



export const useEventBus = () => {
    return {

    }
}

const handleRouterPush = async (params) => {
  await router.push(params)
}

// 注册实践
onMounted(() => {
  eventBus.on('router.push', handleRouterPush)
})
// 卸载事件
onUnmounted(()=>{
  eventBus.off('router.push', handleRouterPush)
})