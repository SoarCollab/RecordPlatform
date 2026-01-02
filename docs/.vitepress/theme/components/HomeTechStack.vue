<script setup lang="ts">
import { computed } from 'vue'
import { useData } from 'vitepress'

const { lang } = useData()

const techStack = [
  {
    name: 'FISCO BCOS',
    icon: '🔗',
    description: { en: 'Blockchain', zh: '区块链' }
  },
  {
    name: 'S3',
    icon: '💾',
    description: { en: 'Object Storage', zh: '对象存储' }
  },
  {
    name: 'Apache Dubbo',
    icon: '🔌',
    description: { en: 'RPC Framework', zh: 'RPC 框架' }
  },
  {
    name: 'Spring Boot',
    icon: '🍃',
    description: { en: 'Backend', zh: '后端框架' }
  },
  {
    name: 'SvelteKit',
    icon: '⚡',
    description: { en: 'Frontend', zh: '前端框架' }
  },
  {
    name: 'Java 21',
    icon: '☕',
    description: { en: 'Runtime', zh: '运行时' }
  }
]

const isZh = computed(() => lang.value === 'zh-CN')
</script>

<template>
  <section class="tech-stack-section">
    <div class="tech-stack-container">
      <h2 class="tech-stack-title">
        {{ isZh ? '技术栈' : 'Tech Stack' }}
      </h2>
      <p class="tech-stack-subtitle">
        {{ isZh ? '基于成熟可靠的开源技术构建' : 'Built on proven open-source technologies' }}
      </p>
      <div class="tech-stack-grid">
        <div
          v-for="tech in techStack"
          :key="tech.name"
          class="tech-stack-item"
        >
          <span class="tech-icon">{{ tech.icon }}</span>
          <span class="tech-name">{{ tech.name }}</span>
          <span class="tech-desc">{{ isZh ? tech.description.zh : tech.description.en }}</span>
        </div>
      </div>
    </div>
  </section>
</template>

<style scoped>
.tech-stack-section {
  padding: 80px 24px;
  text-align: center;
  position: relative;
}

.tech-stack-section::before {
  content: '';
  position: absolute;
  top: 0;
  left: 50%;
  transform: translateX(-50%);
  width: 80%;
  max-width: 400px;
  height: 1px;
  background: linear-gradient(90deg, transparent, var(--vp-c-divider), transparent);
}

.tech-stack-container {
  max-width: 1152px;
  margin: 0 auto;
}

.tech-stack-title {
  font-size: 1.75rem;
  font-weight: 700;
  color: var(--vp-c-text-1);
  margin: 0 0 12px 0;
}

.tech-stack-subtitle {
  font-size: 1.125rem;
  color: var(--vp-c-text-2);
  margin: 0 0 48px 0;
}

.tech-stack-grid {
  display: grid;
  grid-template-columns: repeat(6, 1fr);
  gap: 20px;
}

.tech-stack-item {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
  padding: 24px 16px;
  background: var(--rp-glass-bg);
  backdrop-filter: var(--rp-glass-blur);
  -webkit-backdrop-filter: var(--rp-glass-blur);
  border: 1px solid var(--rp-glass-border);
  border-radius: 16px;
  transition: all 0.3s ease;
}

.tech-stack-item:hover {
  transform: translateY(-4px);
  box-shadow: var(--rp-glass-shadow);
  border-color: rgba(99, 102, 241, 0.3);
}

.tech-icon {
  font-size: 2rem;
  line-height: 1;
  margin-bottom: 4px;
}

.tech-name {
  font-size: 0.9375rem;
  font-weight: 600;
  color: var(--vp-c-text-1);
}

.tech-desc {
  font-size: 0.8125rem;
  color: var(--vp-c-text-3);
}

@media (max-width: 960px) {
  .tech-stack-grid {
    grid-template-columns: repeat(3, 1fr);
  }
}

@media (max-width: 640px) {
  .tech-stack-section {
    padding: 60px 16px;
  }

  .tech-stack-title {
    font-size: 1.5rem;
  }

  .tech-stack-subtitle {
    font-size: 1rem;
    margin-bottom: 32px;
  }

  .tech-stack-grid {
    grid-template-columns: repeat(2, 1fr);
    gap: 12px;
  }

  .tech-stack-item {
    padding: 16px 12px;
  }

  .tech-icon {
    font-size: 1.5rem;
  }

  .tech-name {
    font-size: 0.8125rem;
  }

  .tech-desc {
    font-size: 0.75rem;
  }
}
</style>
