<script lang="ts">
  import type { PlatformHealth } from "$api/types";
  let { health }: { health: PlatformHealth } = $props();
  const labels: Record<string, string> = {
    database: "数据库",
    redis: "缓存",
    storage: "存储服务",
    blockchain: "区块链服务",
  };
  const statusLabels = {
    UP: "正常",
    DOWN: "不可用",
    DEGRADED: "降级",
    UNKNOWN: "未知",
  };
</script>

<section aria-label="共享服务状态" class="bg-card rounded-xl border p-5">
  <div class="mb-4 flex items-center justify-between">
    <h2 class="font-semibold">共享服务状态</h2>
    <span class="text-muted-foreground text-sm"
      >{statusLabels[health.status]}</span
    >
  </div>
  <dl class="grid grid-cols-2 gap-4 sm:grid-cols-4">
    {#each Object.entries(health.components) as [name, status] (name)}<div>
        <dt class="text-muted-foreground mb-1 text-sm">{labels[name]}</dt>
        <dd
          class="font-medium {status === 'UP'
            ? 'text-green-700 dark:text-green-400'
            : status === 'DOWN'
              ? 'text-destructive'
              : 'text-amber-700 dark:text-amber-400'}"
        >
          {statusLabels[status]}
        </dd>
      </div>{/each}
  </dl>
</section>
