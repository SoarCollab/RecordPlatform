import {defineStore} from "pinia";
import {computed, shallowRef} from "vue";
import {useAuthorization} from "@/composables/authorization";
import {getUserApi, loginApi, logoutApi} from "@/api/user.js";
import {filterHiddenRoutes, loadRouteByRole} from "@/router/routes.js";

export const useUserStore = defineStore('user-store', ()=>{

  // {
  //   id: -1,
  //     username: '',
  //   email: '',
  //   role: '',
  //   avatar: null,
  //   registerTime: null
  // }

  const userInfo = shallowRef(null);

  const routeTree = shallowRef([])
  const menuTree = shallowRef([])

  const {token,monitorAuth} = useAuthorization()

  const forum = shallowRef({
    types:[]
  })

  const avatar = computed(()=>{
    return userInfo.value?.avatar ? userInfo.value.avatar : 'https://cube.elemecdn.com/0/88/03b0d39583f48206768a7534e55bcpng.png'
  })

  /**
   * 登录
   * @param data
   * @returns {Promise<any>}
   */
  const loginAction = async (data) => {
    const res = await loginApi(data)
    // console.log('登陆响应', res)
    token.value = res.data.token
    return res.data
  }

  /**
   * 获取用户信息
   * @returns {Promise<[]>}
   */
  const getUserinfoAction = async () => {
    const res = await getUserApi()
    // console.log('获取用户信息', res)
    userInfo.value = res.data
    const {dynamicRoutes,layoutRoute} = loadRouteByRole(res.data.role)
    routeTree.value = dynamicRoutes
    menuTree.value = filterHiddenRoutes([...layoutRoute, ...dynamicRoutes])
    return dynamicRoutes
  }

  /**
   * 登出
   * @returns {Promise<void>}
   */
  const logoutAction = async () => {
    token.value = null
    monitorAuth.value = null
    userInfo.value = null
    await logoutApi()
  }

  return {
    userInfo,
    routeTree,
    menuTree,
    token,
    forum,
    avatar,
    loginAction,
    logoutAction,
    getUserinfoAction,
  }
});