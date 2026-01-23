<script lang="ts">
  import { onMount, onDestroy } from "svelte";
  import { formatNumber } from "$utils/format";
  import {
    getAuditOverview,
    getHighFrequencyOperations,
    getErrorOperationStats,
    getUserTimeDistribution,
  } from "$api/endpoints/system";
  import type {
    HighFrequencyOperationVO,
    ErrorOperationStatsVO,
    UserTimeDistributionVO,
  } from "$api/types";
  import { Button } from "$lib/components/ui/button";
  import * as Table from "$lib/components/ui/table";
  import KpiCard from "./cards/KpiCard.svelte";
  import TrendChart from "./charts/TrendChart.svelte";
  import ErrorPieChart from "./charts/ErrorPieChart.svelte";
  import HeatmapChart from "./charts/HeatmapChart.svelte";

  interface Props {
    onViewHighFreq?: () => void;
  }

  let { onViewHighFreq }: Props = $props();

  let loading = $state(false);
  let overview = $state<Record<string, unknown> | null>(null);
  let highFreq = $state<HighFrequencyOperationVO[]>([]);
  let errorStats = $state<ErrorOperationStatsVO[]>([]);
  let timeDistribution = $state<UserTimeDistributionVO[]>([]);
  let refreshInterval: ReturnType<typeof setInterval> | null = null;
  let lastRefresh = $state<Date | null>(null);

  function asNumber(value: unknown): number | null {
    if (typeof value === "number" && Number.isFinite(value)) return value;
    if (typeof value === "string") {
      const trimmed = value.trim();
      if (!trimmed) return null;
      const num = Number(trimmed);
      return Number.isFinite(num) ? num : null;
    }
    return null;
  }

  function normalizeDailyStats(
    source: Record<string, unknown>,
  ): Array<{ date: string; count: number }> {
    const raw = source["dailyStats"];
    if (!Array.isArray(raw)) return [];

    return raw
      .map((item) => {
        if (!item || typeof item !== "object") return null;
        const row = item as Record<string, unknown>;

        const date =
          (typeof row["date"] === "string" && row["date"]) ||
          (typeof row["operation_date"] === "string" && (row["operation_date"] as string)) ||
          (typeof row["operationDate"] === "string" && (row["operationDate"] as string)) ||
          "";

        const count =
          asNumber(row["count"]) ??
          asNumber(row["operation_count"]) ??
          asNumber(row["operationCount"]) ??
          0;

        if (!date) return null;
        return { date, count };
      })
      .filter((x) => x !== null) as Array<{ date: string; count: number }>;
  }

  async function loadAllData() {
    loading = true;
    try {
      const [overviewRes, highFreqRes, errorStatsRes, timeDistRes] = await Promise.allSettled([
        getAuditOverview(),
        getHighFrequencyOperations(),
        getErrorOperationStats(),
        getUserTimeDistribution(),
      ]);

      if (overviewRes.status === "fulfilled") overview = overviewRes.value;
      if (highFreqRes.status === "fulfilled") highFreq = highFreqRes.value;
      if (errorStatsRes.status === "fulfilled") errorStats = errorStatsRes.value;
      if (timeDistRes.status === "fulfilled") timeDistribution = timeDistRes.value;

      lastRefresh = new Date();
    } finally {
      loading = false;
    }
  }

  onMount(() => {
    loadAllData();
    refreshInterval = setInterval(loadAllData, 30000);
  });

  onDestroy(() => {
    if (refreshInterval) clearInterval(refreshInterval);
  });

  const todayOperations = $derived(asNumber(overview?.["todayOperations"]) ?? 0);
  const todayErrorOperations = $derived(asNumber(overview?.["todayErrorOperations"]) ?? 0);
  const highFrequencyAlerts = $derived(asNumber(overview?.["highFrequencyAlerts"]) ?? 0);
  const todayActiveUsers = $derived(asNumber(overview?.["todayActiveUsers"]) ?? 0);
  const dailyStats = $derived(overview ? normalizeDailyStats(overview) : []);
</script>

<div class="space-y-6">
  <div class="flex items-center justify-between">
    <div>
      <h2 class="text-lg font-semibold">审计仪表盘</h2>
      <p class="text-sm text-muted-foreground">实时监控系统操作和异常行为</p>
    </div>
    <div class="flex items-center gap-3">
      {#if lastRefresh}
        <span class="text-xs text-muted-foreground">
          上次刷新: {lastRefresh.toLocaleTimeString("zh-CN", { hour: "2-digit", minute: "2-digit" })}
        </span>
      {/if}
      <Button variant="outline" size="sm" onclick={loadAllData} disabled={loading}>
        {#if loading}
          <div class="mr-2 h-3 w-3 animate-spin rounded-full border-2 border-current border-t-transparent"></div>
        {/if}
        刷新
      </Button>
    </div>
  </div>

  <!-- KPI Cards -->
  <div class="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
    <KpiCard title="今日操作" value={todayOperations} icon="activity" />
    <KpiCard title="今日错误" value={todayErrorOperations} icon="error" />
    <KpiCard
      title="高频告警"
      value={highFrequencyAlerts}
      icon="alert"
      badge={highFrequencyAlerts > 0 ? "查看" : undefined}
      badgeVariant={highFrequencyAlerts > 0 ? "destructive" : "outline"}
      onclick={highFrequencyAlerts > 0 ? onViewHighFreq : undefined}
    />
    <KpiCard title="活跃用户" value={todayActiveUsers} icon="users" />
  </div>

  <!-- Charts Row 1 -->
  <div class="grid gap-4 lg:grid-cols-2">
    <TrendChart data={dailyStats} {loading} />
    <ErrorPieChart data={errorStats.slice(0, 6)} {loading} />
  </div>

  <!-- Heatmap -->
  <HeatmapChart data={timeDistribution} {loading} />

  <!-- Tables Row -->
  <div class="grid gap-4 lg:grid-cols-2">
    <!-- High Frequency Alerts -->
    <div class="rounded-xl border bg-card/50 p-4">
      <div class="mb-3 flex items-center justify-between">
        <p class="text-sm font-medium">高频操作告警</p>
        {#if highFreq.length > 0}
          <Button variant="ghost" size="sm" onclick={onViewHighFreq}>查看全部</Button>
        {/if}
      </div>
      {#if loading}
        <div class="flex h-[160px] items-center justify-center">
          <div class="h-5 w-5 animate-spin rounded-full border-2 border-primary border-t-transparent"></div>
        </div>
      {:else if highFreq.length === 0}
        <div class="flex h-[160px] items-center justify-center text-sm text-muted-foreground">
          暂无高频告警
        </div>
      {:else}
        <div class="overflow-auto rounded-lg border">
          <Table.Root>
            <Table.Header>
              <Table.Row>
                <Table.Head>用户</Table.Head>
                <Table.Head>IP</Table.Head>
                <Table.Head class="text-right">次数</Table.Head>
              </Table.Row>
            </Table.Header>
            <Table.Body>
              {#each highFreq.slice(0, 3) as item (item.userId + item.requestIp)}
                <Table.Row>
                  <Table.Cell class="font-medium">{item.username || item.userId}</Table.Cell>
                  <Table.Cell class="font-mono text-xs">{item.requestIp}</Table.Cell>
                  <Table.Cell class="text-right font-mono text-sm">{formatNumber(item.operationCount)}</Table.Cell>
                </Table.Row>
              {/each}
            </Table.Body>
          </Table.Root>
        </div>
      {/if}
    </div>

    <!-- Error Stats Top 5 -->
    <div class="rounded-xl border bg-card/50 p-4">
      <p class="mb-3 text-sm font-medium">错误统计 Top 5</p>
      {#if loading}
        <div class="flex h-[160px] items-center justify-center">
          <div class="h-5 w-5 animate-spin rounded-full border-2 border-primary border-t-transparent"></div>
        </div>
      {:else if errorStats.length === 0}
        <div class="flex h-[160px] items-center justify-center text-sm text-muted-foreground">
          暂无错误数据
        </div>
      {:else}
        <div class="overflow-auto rounded-lg border">
          <Table.Root>
            <Table.Header>
              <Table.Row>
                <Table.Head>模块</Table.Head>
                <Table.Head>类型</Table.Head>
                <Table.Head class="text-right">次数</Table.Head>
              </Table.Row>
            </Table.Header>
            <Table.Body>
              {#each errorStats.slice(0, 5) as row (row.module + row.operationType)}
                <Table.Row>
                  <Table.Cell class="font-medium">{row.module}</Table.Cell>
                  <Table.Cell class="text-xs">{row.operationType}</Table.Cell>
                  <Table.Cell class="text-right font-mono text-sm">{formatNumber(row.errorCount)}</Table.Cell>
                </Table.Row>
              {/each}
            </Table.Body>
          </Table.Root>
        </div>
      {/if}
    </div>
  </div>
</div>
