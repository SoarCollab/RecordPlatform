<script setup lang="ts">
import DefaultTheme from 'vitepress/theme'
import { useData } from 'vitepress'
import HomeTechStack from './components/HomeTechStack.vue'
import HomeCallToAction from './components/HomeCallToAction.vue'

const { Layout } = DefaultTheme
const { frontmatter } = useData()
</script>

<template>
  <Layout>
    <!-- Home page hero decoration -->
    <template #home-hero-before>
      <div class="hero-bg-decoration" aria-hidden="true">
        <div class="blob blob-1"></div>
        <div class="blob blob-2"></div>
        <div class="blob blob-3"></div>
      </div>
    </template>

    <!-- Home page additional sections -->
    <template #home-features-after>
      <HomeTechStack v-if="frontmatter.layout === 'home'" />
      <HomeCallToAction v-if="frontmatter.layout === 'home'" />
    </template>

    <!-- Sidebar brand indicator -->
    <template #sidebar-nav-before>
      <div class="sidebar-brand-indicator"></div>
    </template>
  </Layout>
</template>

<style scoped>
.hero-bg-decoration {
  position: absolute;
  top: 0;
  left: 0;
  right: 0;
  height: 100vh;
  overflow: hidden;
  pointer-events: none;
  z-index: -1;
}

.blob {
  position: absolute;
  border-radius: 50%;
  filter: blur(80px);
  opacity: 0.6;
}

.blob-1 {
  top: -10%;
  left: -5%;
  width: 40vw;
  height: 40vw;
  max-width: 600px;
  max-height: 600px;
  background: radial-gradient(circle, rgba(99, 102, 241, 0.25) 0%, transparent 70%);
  animation: float1 12s ease-in-out infinite;
}

.blob-2 {
  top: 20%;
  right: -10%;
  width: 35vw;
  height: 35vw;
  max-width: 500px;
  max-height: 500px;
  background: radial-gradient(circle, rgba(139, 92, 246, 0.2) 0%, transparent 70%);
  animation: float2 15s ease-in-out infinite;
}

.blob-3 {
  bottom: 10%;
  left: 30%;
  width: 25vw;
  height: 25vw;
  max-width: 350px;
  max-height: 350px;
  background: radial-gradient(circle, rgba(59, 130, 246, 0.15) 0%, transparent 70%);
  animation: float3 10s ease-in-out infinite;
}

@keyframes float1 {
  0%, 100% {
    transform: translate(0, 0) scale(1);
  }
  33% {
    transform: translate(30px, -20px) scale(1.05);
  }
  66% {
    transform: translate(-20px, 10px) scale(0.95);
  }
}

@keyframes float2 {
  0%, 100% {
    transform: translate(0, 0) scale(1);
  }
  50% {
    transform: translate(-40px, 30px) scale(1.1);
  }
}

@keyframes float3 {
  0%, 100% {
    transform: translate(0, 0) scale(1);
  }
  50% {
    transform: translate(20px, -30px) scale(1.05);
  }
}

/* Dark mode adjustments */
:global(.dark) .blob-1 {
  background: radial-gradient(circle, rgba(99, 102, 241, 0.15) 0%, transparent 70%);
}

:global(.dark) .blob-2 {
  background: radial-gradient(circle, rgba(139, 92, 246, 0.12) 0%, transparent 70%);
}

:global(.dark) .blob-3 {
  background: radial-gradient(circle, rgba(59, 130, 246, 0.1) 0%, transparent 70%);
}
</style>
