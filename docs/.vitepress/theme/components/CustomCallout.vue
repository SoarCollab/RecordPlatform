<script setup lang="ts">
defineProps<{
  type?: 'info' | 'warning' | 'danger' | 'tip'
  title?: string
}>()

const icons = {
  info: 'ℹ️',
  warning: '⚠️',
  danger: '🚨',
  tip: '💡'
}
</script>

<template>
  <div :class="['custom-callout', type || 'info']">
    <div class="callout-icon">{{ icons[type || 'info'] }}</div>
    <div class="callout-content">
      <div v-if="title" class="callout-title">{{ title }}</div>
      <div class="callout-body">
        <slot />
      </div>
    </div>
  </div>
</template>

<style scoped>
.custom-callout {
  display: flex;
  gap: 16px;
  padding: 16px 20px;
  border-radius: 12px;
  margin: 16px 0;
  border-left: 4px solid;
}

.custom-callout.info {
  background: var(--rp-callout-info-bg, rgba(99, 102, 241, 0.1));
  border-color: var(--rp-callout-info-border, rgba(99, 102, 241, 0.4));
}

.custom-callout.warning {
  background: var(--rp-callout-warning-bg, rgba(245, 158, 11, 0.1));
  border-color: var(--rp-callout-warning-border, rgba(245, 158, 11, 0.4));
}

.custom-callout.danger {
  background: var(--rp-callout-danger-bg, rgba(239, 68, 68, 0.1));
  border-color: var(--rp-callout-danger-border, rgba(239, 68, 68, 0.4));
}

.custom-callout.tip {
  background: var(--rp-callout-tip-bg, rgba(16, 185, 129, 0.1));
  border-color: var(--rp-callout-tip-border, rgba(16, 185, 129, 0.4));
}

.callout-icon {
  flex-shrink: 0;
  width: 24px;
  height: 24px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 1.125rem;
}

.callout-content {
  flex: 1;
  min-width: 0;
}

.callout-title {
  font-weight: 600;
  font-size: 0.9375rem;
  margin-bottom: 6px;
  color: var(--vp-c-text-1);
}

.callout-body {
  color: var(--vp-c-text-2);
  line-height: 1.6;
}

.callout-body :deep(p) {
  margin: 0;
}

.callout-body :deep(p + p) {
  margin-top: 8px;
}

@media (max-width: 640px) {
  .custom-callout {
    padding: 12px 16px;
    gap: 12px;
  }
}
</style>
