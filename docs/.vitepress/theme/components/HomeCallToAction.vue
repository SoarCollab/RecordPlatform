<script setup lang="ts">
import { computed } from 'vue'
import { useData } from 'vitepress'

const { lang } = useData()

const isZh = computed(() => lang.value === 'zh-CN')

const content = computed(() => ({
  title: isZh.value ? '开始使用 RecordPlatform' : 'Get Started with RecordPlatform',
  subtitle: isZh.value
    ? '安全、可靠的企业级文件存证解决方案，基于区块链与分布式存储技术'
    : 'Secure, reliable enterprise file attestation powered by blockchain and distributed storage',
  docBtn: isZh.value ? '阅读文档' : 'Read the Docs',
  githubBtn: isZh.value ? '查看源码' : 'View on GitHub',
  docLink: isZh.value ? '/zh/getting-started/' : '/en/getting-started/',
  githubLink: 'https://github.com/SoarCollab/RecordPlatform'
}))
</script>

<template>
  <section class="cta-section">
    <div class="cta-pattern" aria-hidden="true"></div>
    <div class="cta-content">
      <h2 class="cta-title">{{ content.title }}</h2>
      <p class="cta-subtitle">{{ content.subtitle }}</p>
      <div class="cta-buttons">
        <a :href="content.docLink" class="cta-button primary">
          <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
            <path d="M4 19.5A2.5 2.5 0 0 1 6.5 17H20"></path>
            <path d="M6.5 2H20v20H6.5A2.5 2.5 0 0 1 4 19.5v-15A2.5 2.5 0 0 1 6.5 2z"></path>
          </svg>
          {{ content.docBtn }}
        </a>
        <a :href="content.githubLink" target="_blank" rel="noopener" class="cta-button secondary">
          <svg width="20" height="20" viewBox="0 0 24 24" fill="currentColor">
            <path d="M12 0c-6.626 0-12 5.373-12 12 0 5.302 3.438 9.8 8.207 11.387.599.111.793-.261.793-.577v-2.234c-3.338.726-4.033-1.416-4.033-1.416-.546-1.387-1.333-1.756-1.333-1.756-1.089-.745.083-.729.083-.729 1.205.084 1.839 1.237 1.839 1.237 1.07 1.834 2.807 1.304 3.492.997.107-.775.418-1.305.762-1.604-2.665-.305-5.467-1.334-5.467-5.931 0-1.311.469-2.381 1.236-3.221-.124-.303-.535-1.524.117-3.176 0 0 1.008-.322 3.301 1.23.957-.266 1.983-.399 3.003-.404 1.02.005 2.047.138 3.006.404 2.291-1.552 3.297-1.23 3.297-1.23.653 1.653.242 2.874.118 3.176.77.84 1.235 1.911 1.235 3.221 0 4.609-2.807 5.624-5.479 5.921.43.372.823 1.102.823 2.222v3.293c0 .319.192.694.801.576 4.765-1.589 8.199-6.086 8.199-11.386 0-6.627-5.373-12-12-12z"/>
          </svg>
          {{ content.githubBtn }}
        </a>
      </div>
    </div>
  </section>
</template>

<style scoped>
.cta-section {
  padding: 80px 24px;
  background: linear-gradient(135deg, var(--rp-gradient-start), var(--rp-gradient-end));
  margin-top: 40px;
  text-align: center;
  position: relative;
  overflow: hidden;
}

.cta-pattern {
  position: absolute;
  top: 0;
  left: 0;
  right: 0;
  bottom: 0;
  background-image:
    radial-gradient(circle at 20% 50%, rgba(255, 255, 255, 0.1) 0%, transparent 50%),
    radial-gradient(circle at 80% 50%, rgba(255, 255, 255, 0.08) 0%, transparent 40%);
  pointer-events: none;
}

.cta-content {
  position: relative;
  z-index: 1;
  max-width: 700px;
  margin: 0 auto;
}

.cta-title {
  font-size: 2rem;
  font-weight: 700;
  color: #ffffff;
  margin: 0 0 16px 0;
  line-height: 1.3;
}

.cta-subtitle {
  font-size: 1.125rem;
  color: rgba(255, 255, 255, 0.9);
  margin: 0 0 36px 0;
  line-height: 1.6;
}

.cta-buttons {
  display: flex;
  gap: 16px;
  justify-content: center;
  flex-wrap: wrap;
}

.cta-button {
  display: inline-flex;
  align-items: center;
  gap: 10px;
  padding: 14px 28px;
  border-radius: 10px;
  font-weight: 600;
  font-size: 1rem;
  transition: all 0.3s ease;
  text-decoration: none;
}

.cta-button.primary {
  background: #ffffff;
  color: var(--rp-gradient-mid);
  box-shadow: 0 4px 14px rgba(0, 0, 0, 0.15);
}

.cta-button.primary:hover {
  transform: translateY(-3px);
  box-shadow: 0 8px 20px rgba(0, 0, 0, 0.2);
}

.cta-button.secondary {
  background: rgba(255, 255, 255, 0.15);
  color: #ffffff;
  border: 1px solid rgba(255, 255, 255, 0.3);
  backdrop-filter: blur(4px);
}

.cta-button.secondary:hover {
  background: rgba(255, 255, 255, 0.25);
  transform: translateY(-3px);
  border-color: rgba(255, 255, 255, 0.5);
}

@media (max-width: 640px) {
  .cta-section {
    padding: 60px 20px;
  }

  .cta-title {
    font-size: 1.5rem;
  }

  .cta-subtitle {
    font-size: 1rem;
    margin-bottom: 28px;
  }

  .cta-buttons {
    flex-direction: column;
    align-items: center;
  }

  .cta-button {
    width: 100%;
    max-width: 280px;
    justify-content: center;
    padding: 12px 24px;
  }
}
</style>
