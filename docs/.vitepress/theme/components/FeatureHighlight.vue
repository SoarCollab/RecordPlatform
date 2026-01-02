<script setup lang="ts">
defineProps<{
  icon?: string
  title: string
  description?: string
}>()
</script>

<template>
  <div class="feature-highlight">
    <div v-if="icon" class="highlight-icon">{{ icon }}</div>
    <div class="highlight-content">
      <h4>{{ title }}</h4>
      <p v-if="description">{{ description }}</p>
      <div v-else class="highlight-slot">
        <slot />
      </div>
    </div>
  </div>
</template>

<style scoped>
.feature-highlight {
  display: flex;
  gap: 16px;
  padding: 20px;
  background: var(--rp-glass-bg, rgba(255, 255, 255, 0.7));
  backdrop-filter: blur(10px);
  -webkit-backdrop-filter: blur(10px);
  border: 1px solid var(--rp-glass-border, rgba(255, 255, 255, 0.2));
  border-radius: 12px;
  margin: 16px 0;
  transition: all 0.3s ease;
}

.feature-highlight:hover {
  transform: translateY(-2px);
  box-shadow: var(--rp-glass-shadow, 0 8px 32px rgba(0, 0, 0, 0.1));
  border-color: rgba(99, 102, 241, 0.3);
}

.highlight-icon {
  flex-shrink: 0;
  width: 48px;
  height: 48px;
  background: var(--rp-feature-icon-bg, linear-gradient(135deg, rgba(99, 102, 241, 0.1), rgba(139, 92, 246, 0.1)));
  border-radius: 10px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 1.5rem;
}

.highlight-content {
  flex: 1;
  min-width: 0;
}

.highlight-content h4 {
  font-size: 1rem;
  font-weight: 600;
  color: var(--vp-c-text-1);
  margin: 0 0 6px 0;
}

.highlight-content p {
  font-size: 0.9375rem;
  color: var(--vp-c-text-2);
  margin: 0;
  line-height: 1.5;
}

.highlight-slot {
  font-size: 0.9375rem;
  color: var(--vp-c-text-2);
  line-height: 1.5;
}

.highlight-slot :deep(p) {
  margin: 0;
}

@media (max-width: 640px) {
  .feature-highlight {
    flex-direction: column;
    gap: 12px;
    padding: 16px;
  }

  .highlight-icon {
    width: 40px;
    height: 40px;
    font-size: 1.25rem;
  }
}
</style>
