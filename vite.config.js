import {fileURLToPath, URL} from 'node:url'

import {defineConfig} from 'vite'
import vue from '@vitejs/plugin-vue'
import AutoImport from 'unplugin-auto-import/vite'
import Components from 'unplugin-vue-components/vite'
import {ElementPlusResolver} from 'unplugin-vue-components/resolvers'
import UnoCSS from 'unocss/vite'

const baseUrl = fileURLToPath(new URL('./src', import.meta.url))

// https://vitejs.dev/config/
export default defineConfig({
  plugins: [
    vue(),
    UnoCSS(),
    AutoImport({
      imports: ['vue', 'pinia', 'vue-router'],
      resolvers: [ElementPlusResolver()],
    }),
    Components({
      resolvers: [ElementPlusResolver({
        importStyle: 'css',
      })],
    }),
  ],
  resolve: {
    alias: [
      {
        find: '@',
        replacement: baseUrl
      }
    ]
  },
  server: {
    // 设置服务器的 host，允许外部设备通过 IP 访问开发服务器
    host: '0.0.0.0',
    // 设置开发服务器的端口号
    port: 13999,
    // 配置代理，解决开发环境下的跨域问题
    proxy: {
      '/api': {
        target: 'http://110.42.57.131:8080',
        changeOrigin: true,
        // rewrite: (path) => path.replace(/^\/api/, ''),
        secure: false,
        // 可添加更多配置
      }
    }
    // proxy: {
    //   '/api': {
    //     // 将以 /api 开头的请求代理到目标服务器
    //     target: 'http://47.92.80.128:8080',
    //     // 允许跨域，并将请求的 host 更改为目标服务器的 host
    //     changeOrigin: true,
    //     // 重写路径，去掉 /api 前缀
    //     // rewrite: (path) => path.replace(/^\/api/, '')
    //   }
    // }
  }
})
