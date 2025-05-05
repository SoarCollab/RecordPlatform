
import router from "./index.js";
import {useAuthorization} from "@/composables/authorization.js";
import {useUserStore} from "@/store/userStore.js";
import NProgress from 'nprogress';
import {useTitle} from "@vueuse/core";
import {useLoadingCheck, useScrollToTop} from "@/composables/loading.js";
const allowList = ['/login', '/register', '/forget', '/404', '/403']
const loginPath = '/login'

router.beforeEach( async (to, _, next) => {
  // 开始进度条
  NProgress.start();

  // 获取授权状态
  const {token,monitorAuth} = useAuthorization();
  // 获取用户信息存储
  const {userInfo, logoutAction, getUserinfoAction} = useUserStore();

  // 如果没有授权
  if (!token.value) {
    // 检查是否在允许列表中
    if (allowList.includes(to.path)) {
      // 允许访问
      next();
    } else {
      // 重定向到登录页面
      next(loginPath);
    }
  } else {
    // 如果有授权但没有用户信息，并且不在允许列表中
    if (!userInfo && !allowList.includes(to.path)) {
      try {
        // 获取用户信息
        console.log('获取用户信息');
        const route = await getUserinfoAction()
        // 根据用户角色，加载对应路由 admin user monitor
        console.log('加载对应路由', route);
        // 获取根路由
        const root = router.getRoutes().find(route => route.name === 'root');
        if(root){
          await router.removeRoute('root')
          root.children = [...root.children, ...route]
          await router.addRoute(root)
        }
        // 用户信息获取成功后，重新检查路由
        next({ ...to, replace: true });
      }catch (e) {
        console.log('22222222222', e)
        // 处理获取用户信息失败的情况，重定向到登录页面
        next(loginPath);
      }
    } else {
      // 如果当前路径是登录页面且用户已登录，重定向到首页
      if (to.path === loginPath) {
       try {
         await logoutAction()
       }finally {
         next(loginPath);
       }
      }else if (to.path.startsWith('/monitor') && to.path !== '/monitor/login') {// 如果路径以monitor开头判断useMonitorAuth
        if(!monitorAuth.value){
          next('/monitor/login')
          return;
        }
        next()
      }else {
        next()
      }
    }
  }
});

router.afterEach((to) => {
  // 设置页面标题
  const { title } = to.meta;
  useTitle(title + '-' + import.meta.env.VITE_APP_TITLE);

  // 检查加载状态
  useLoadingCheck();

  // 滚动到页面顶部
  useScrollToTop();

  // 结束进度条
  NProgress.done();
});
