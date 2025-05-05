import 'virtual:uno.css'
import 'nprogress/nprogress.css';
import '@/assets/quill.css'
import 'element-plus/dist/index.css'
import '@/assets/main.css'
import 'element-plus/theme-chalk/dark/css-vars.css'
import '@fortawesome/fontawesome-free/css/all.min.css'  // 路径可能需要调整

import { createApp } from 'vue'
import {createPinia} from "pinia";

import App from './App.vue'
import router from './router'
import '@/router/gurad.js'


const pinia = createPinia()
const app = createApp(App)
app.use(pinia)
app.use(router)
app.mount('#app')