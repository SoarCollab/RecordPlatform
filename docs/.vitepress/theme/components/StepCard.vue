<script setup lang="ts">
defineProps<{
  step: string | number
  title: string
}>()
</script>

<template>
  <div class="step-card">
    <div class="step-number">{{ step }}</div>
    <div class="step-content">
      <h4 class="step-title">{{ title }}</h4>
      <div class="step-body">
        <slot />
      </div>
    </div>
  </div>
</template>

<style scoped>
.step-card {
  display: flex;
  gap: 20px;
  padding: 20px 0;
  position: relative;
}

.step-card:not(:last-child)::before {
  content: '';
  position: absolute;
  left: 20px;
  top: 60px;
  bottom: -4px;
  width: 2px;
  background: var(--rp-step-line, rgba(99, 102, 241, 0.3));
}

.step-number {
  flex-shrink: 0;
  width: 40px;
  height: 40px;
  background: var(--rp-step-number-bg, linear-gradient(135deg, #3b82f6, #8b5cf6));
  color: var(--rp-step-number-text, #ffffff);
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  font-weight: 700;
  font-size: 1rem;
  box-shadow: 0 4px 12px rgba(99, 102, 241, 0.3);
}

.step-content {
  flex: 1;
  min-width: 0;
  padding-top: 6px;
}

.step-title {
  font-size: 1.125rem;
  font-weight: 600;
  color: var(--vp-c-text-1);
  margin: 0 0 12px 0;
  line-height: 1.4;
}

.step-body {
  color: var(--vp-c-text-2);
  line-height: 1.7;
}

.step-body :deep(p:first-child) {
  margin-top: 0;
}

.step-body :deep(p:last-child) {
  margin-bottom: 0;
}

.step-body :deep(div[class*='language-']) {
  margin: 12px 0;
}

@media (max-width: 640px) {
  .step-card {
    gap: 16px;
    padding: 16px 0;
  }

  .step-number {
    width: 32px;
    height: 32px;
    font-size: 0.875rem;
  }

  .step-card:not(:last-child)::before {
    left: 16px;
    top: 52px;
  }

  .step-title {
    font-size: 1rem;
  }
}
</style>
