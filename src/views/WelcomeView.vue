<template>
    <div class="welcome-container">
        <!-- 背景图片 -->
        <div class="background">
            <el-image style="width: 100%;height: 100%" fit="cover"
                      src="https://pic.vjshi.com/2020-12-11/12eafa35d49ca6429d5ceccdae803003/online/bc1a3a3eb5ef994605b751b1c6839303.jpg?x-oss-process=style/resize_w_720"/>
        </div>
        
        <!-- 登录卡片 -->
        <div class="login-card">
            <router-view v-slot="{ Component }">
                <transition name="el-fade-in-linear" mode="out-in">
                    <component :is="Component"/>
                </transition>
            </router-view>
        </div>
    </div>
</template>

<script setup>
</script>

<style scoped>
.welcome-container {
    width: 100vw;
    height: 100vh;
    position: relative;
    display: flex;
    align-items: center;
    justify-content: center;
    overflow: hidden;
}

.background {
    position: absolute;
    top: 0;
    left: 0;
    width: 100%;
    height: 100%;
    z-index: 0;
}

.login-card {
    position: relative;
    z-index: 1;
    width: 400px;
    padding: 20px;
    background: rgba(255, 255, 255, 0.9);
    border-radius: 8px;
    box-shadow: 0 4px 20px rgba(0, 0, 0, 0.15);
    backdrop-filter: blur(10px);
}

/* 深色模式适配 */
.dark .login-card {
    background: rgba(30, 30, 30, 0.9);
}

/* 响应式设计 */
@media screen and (max-width: 768px) {
    .login-card {
        width: 90%;
        margin: 0 20px;
    }
}
</style>
