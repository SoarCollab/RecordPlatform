<script setup lang="ts">
defineProps<{
  columns: string[]
}>()
</script>

<template>
  <div class="comparison-table-wrapper">
    <table class="comparison-table">
      <thead>
        <tr>
          <th>Feature</th>
          <th v-for="col in columns" :key="col">{{ col }}</th>
        </tr>
      </thead>
      <tbody>
        <slot />
      </tbody>
    </table>
  </div>
</template>

<style scoped>
.comparison-table-wrapper {
  overflow-x: auto;
  margin: 20px 0;
  border-radius: 12px;
  border: 1px solid var(--vp-c-divider);
}

.comparison-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 0.9375rem;
}

.comparison-table th,
.comparison-table :deep(td) {
  padding: 14px 18px;
  text-align: left;
  border-bottom: 1px solid var(--vp-c-divider);
}

.comparison-table th {
  background: var(--vp-c-bg-soft);
  font-weight: 600;
  color: var(--vp-c-text-1);
  white-space: nowrap;
}

.comparison-table th:first-child {
  border-top-left-radius: 11px;
}

.comparison-table th:last-child {
  border-top-right-radius: 11px;
}

.comparison-table :deep(tbody tr:last-child td) {
  border-bottom: none;
}

.comparison-table :deep(tbody tr:last-child td:first-child) {
  border-bottom-left-radius: 11px;
}

.comparison-table :deep(tbody tr:last-child td:last-child) {
  border-bottom-right-radius: 11px;
}

.comparison-table :deep(tbody tr:hover) {
  background: var(--vp-c-bg-soft);
}

.comparison-table :deep(td) {
  color: var(--vp-c-text-2);
}

/* Check/Cross/Partial icons */
.comparison-table :deep(.check) {
  color: #10b981;
  font-weight: bold;
}

.comparison-table :deep(.cross) {
  color: #ef4444;
  font-weight: bold;
}

.comparison-table :deep(.partial) {
  color: #f59e0b;
  font-weight: bold;
}

@media (max-width: 640px) {
  .comparison-table th,
  .comparison-table :deep(td) {
    padding: 10px 12px;
    font-size: 0.875rem;
  }
}
</style>
