import axios from "axios";
import {useMessage} from "./message";
import {useAuthorization} from "@/composables/authorization.js";
import {useRouter} from "vue-router";

const request = axios.create({
  baseURL: import.meta.env.VITE_BASE_URL,
  timeout: 5000,
  skipToken: false
})

// 请求拦截器
request.interceptors.request.use(config => {

  if(config?.cusToken){
    config.baseURL = import.meta.env.VITE_BASE_MONITOR_URL
    console.log('设置监控url')
  }else {
    config.baseURL = import.meta.env.VITE_BASE_URL
    console.log('默认url')
  }
  if (!config.skipToken) {
    // 获取token
    const {token,monitorAuth} = useAuthorization()
    // 判断token是否存在
    if (!config?.cusToken && token.value) {
      // 设置token到请求头中
      config.headers.Authorization = 'Bearer ' + token.value
    }
    if (config?.cusToken && monitorAuth.value) {
      // 设置token到请求头中
      config.headers.Authorization = 'Bearer ' + monitorAuth.value
    }
  }

  return config
}, err => {
  return Promise.reject(err)
})

// 响应拦截器
request.interceptors.response.use(res => {
  // console.log('响应拦截器 onFulfilled', res)
  const {data} = res
  if(data.code === 20001){// 未登录
    // 提示错误信息
    useMessage().error(data.message)
    // 跳转到登录页
    useRouter().push('/login')
  }else if (data.code !== 1 && data.code !== 200) {// 判断响应数据是否成功
    // 提示错误信息
    useMessage().error(data.message)
    return Promise.reject(data)
  }
  // 返回响应数据
  return data
}, err => {
  // console.log('响应拦截器 onRejected', err)
  // 提示错误信息
  useMessage().error('服务器错误，请联系管理员！')
  return Promise.reject(err)
})

export default request