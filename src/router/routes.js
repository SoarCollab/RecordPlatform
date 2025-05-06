import _ from 'lodash-es'
export default [
  {
    path: '/login',
    name: 'login',
    component: () => import('@/views/common/login.vue'),
    meta: {
      title: '登录'
    }
  },
  {
    path: '/register',
    name: 'register',
    component: () => import('@/views/common/register.vue'),
    meta: {
      title: '注册'
    }
  },
  {
    path: '/forget',
    name: 'forget',
    component: () => import('@/views/common/forget.vue'),
    meta: {
      title: '忘记密码'
    }
  },
  {
    path: '/404',
    name: '404',
    component: () => import('@/views/common/404.vue'),
    meta: {
      title: '404'
    }
  },
  {
    path: '/',
    name: 'root',
    component: () => import('@/layout/index.vue'),
    redirect: '/home',
    children: [
      {
        path: '/home',
        name: 'home',
        component: () => import('@/views/home/index.vue'),
        meta: {
          title: '欢迎',
          icon: 'House',
          hide: false,
        }
      },
      {
        path: '/personal',
        name: 'personal',
        component: () => import('@/views/common/personal.vue'),
        meta: {
          title: '个人中心',
          icon: 'User',
          hide: true,
        }
      }
    ]
  }
]

// 静态布局路由
const layoutRoute = [
  {
    path: '/home',
    name: 'home',
    component: () => import('@/views/home/index.vue'),
    meta: {
      title: '欢迎',
      icon: 'House',
      hide: false,
    }
  },
  {
    path: '/personal',
    name: 'personal',
    component: () => import('@/views/common/personal.vue'),
    meta: {
      title: '个人中心',
      icon: 'User',
      hide: true,
    }
  }
]

// 动态路由
const dynamicRoute = [
  // {
  //   path: '/system',
  //   name: 'system',
  //   meta: {
  //     title: '系统管理',
  //     icon: 'Setting',
  //     hide: false,
  //   },
  //   redirect: '/system/user',
  //   children: [
  //     {
  //       path: '/system/user',
  //       name: 'user',
  //       component: () => import('@/views/system/user/index.vue'),
  //       meta: {
  //         title: '用户管理',
  //         icon: 'User',
  //         hide: false,
  //       }
  //     }
  //   ]
  // },
  {
    path: '/file',
    name: 'file',
    meta: {
      title: '文件管理',
      icon: 'Folder',
      hide: false,
    },
    redirect: '/file/list',
    children: [
      {
        path: '/file/list',
        name: 'fileList',
        component: () => import('@/views/file/list.vue'),
        meta: {
          title: '文件列表',
          icon: 'Document',
          hide: false,
        }
      },
      {
        path: '/file/upload',
        name: 'fileUpload',
        component: () => import('@/views/file/upload.vue'),
        meta: {
          title: '文件上传',
          icon: 'Upload',
          hide: false,
        }
      },
      {
        path: '/file/get',
        name: 'fileGet',
        component: () => import('@/views/file/get.vue'),
        meta: {
          title: '获取文件',
          icon: 'Promotion',
          hide: false,
        }
      }
    ]
  },
  {
    path: '/monitor',
    name: 'monitor',
    meta: {
      title: '监控管理',
      icon: 'Monitor',
      hide: false,
    },
    redirect: '/monitor/host',
    children: [
      {
        path: '/monitor/login',
        name: 'monitorLogin',
        component: () => import('@/views/monitor/login.vue'),
        meta: {
          title: '监控登录',
          icon: 'Login',
          hide: true,
        }
      },
      {
        path: '/monitor/host',
        name: 'host',
        component: () => import('@/views/monitor/host.vue'),
        meta: {
          title: '主机管理',
          icon: 'Cpu',
          hide: false,
        }
      },
      {
        path: '/monitor/host/detail/:id',
        name: 'hostDetail',
        component: () => import('@/views/monitor/detail.vue'),
        meta: {
          title: '主机详情',
          icon: 'Server',
          hide: true,
        }
      },
      {
        path: '/monitor/host/ssh/:id',
        name: 'hostSSH',
        component: () => import('@/views/monitor/ssh.vue'),
        meta: {
          title: 'SSH登录',
          icon: 'Terminal',
          hide: true,
        }
      }
    ]
  }
]

/**
 * 根据角色加载路由
 * @param role admin user monitor
 * @returns object
 * */
export const loadRouteByRole = (role) => {
  console.log('loadRouteByRole', role)
  let dynamicRoutes = [];
  if(role === 'admin'){
    dynamicRoutes = _.cloneDeep(dynamicRoute);
    return {dynamicRoutes,layoutRoute}
  }else if(role === 'user') {
    // 添加用户路由
    dynamicRoutes = _.cloneDeep(dynamicRoute).filter(item => item.path === '/file');
    return {dynamicRoutes, layoutRoute}
  }else if(role === 'monitor'){
    // 添加监控路由
    dynamicRoutes = _.cloneDeep(dynamicRoute).filter(item => item.path === '/monitor');
    return {dynamicRoutes, layoutRoute}
  }
  return {dynamicRoutes,layoutRoute}
}

/**
 * 过滤隐藏的路由节点
 * @param {Array} routes 原始路由树
 * @returns {Array} 过滤后的路由树
 */
export function filterHiddenRoutes(routes) {
  // 深拷贝避免污染原始路由配置
  const clonedRoutes = _.cloneDeep(routes)

  return clonedRoutes.filter(route => {
    // 过滤条件：存在meta.hide且值为true时过滤
    if (route.meta?.hide) return false

    // 递归处理子路由
    if (route.children?.length) {
      route.children = filterHiddenRoutes(route.children)

      // 处理过滤后子路由为空的情况
      if (route.children.length === 0) {
        delete route.children
      }
    }

    return true
  })
}
// --

const temp = [
  {
    path: '/',
    name: 'welcome',
    component: () => import('@/views/WelcomeView.vue'),
    children: [
      {
        path: '',
        name: 'welcome-login',
        component: () => import('@/views/welcome/LoginPage.vue')
      }, {
        path: 'register',
        name: 'welcome-register',
        component: () => import('@/views/welcome/RegisterPage.vue')
      }, {
        path: 'forget',
        name: 'welcome-forget',
        component: () => import('@/views/welcome/ForgetPage.vue')
      }
    ]
  },
  {
    path: '/index',
    name: 'index',
    component: () => import('@/views/IndexView.vue'),
    children:[
      {
        path: '',
        name: 'topics',
        component: () => import('@/views/forum/Forum.vue'),
        children:[
          {
            path:'',
            name: 'topic-list',
            component: () => import('@/views/forum/TopicList.vue')
          },{
            path:'topic-detail/:tid',
            name: 'topic-detail',
            component: () => import('@/views/forum/TopicDetail.vue')
          }
        ]
      },{
        path:'user-setting',
        name: 'user-setting',
        component: () => import('@/views/settings/userSetting.vue')
      },{
        path:'privacy-setting',
        name: 'privacy-setting',
        component: () => import('@/views/settings/privacySetting.vue')
      }
    ]
  }
]