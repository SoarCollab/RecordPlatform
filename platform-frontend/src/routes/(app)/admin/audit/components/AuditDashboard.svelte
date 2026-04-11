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
  import { Button } from "$components/ui/button";
  import * as Table from "$components/ui/table";
  import KpiCard from "./cards/KpiCard.svelte";
  import TrendChart from "./charts/TrendChart.svelte";
  import ErrorPieChart from "./charts/ErrorPieChart.svelte";
  import HeatmapChart from "./charts/HeatmapChart.svelte";

  /** 钻取筛选参数 */
  export type DrillDownFilter = {
    username?: string;
    module?: string;
    operationType?: string;
    status?: string;
    startTime?: string;
    endTime?: string;
    onlySensitive?: boolean;
  };

  interface Props {
    onDrillDown?: (filters: DrillDownFilter) => void;
  }

  let { onDrillDown }: Props = $props();

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
          (typeof row["operation_date"] === "string" &&
            (row["operation_date"] as string)) ||
          (typeof row["operationDate"] === "string" &&
            (row["operationDate"] as string)) ||
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

  /** 获取今日日期范围 */
  function getTodayRange(): { startTime: string; endTime: string } {
    const now = new Date();
    const y = now.getFullYear();
    const m = String(now.getMonth() + 1).padStart(2, "0");
    const d = String(now.getDate()).padStart(2, "0");
    return {
      startTime: `${y}-${m}-${d} 00:00:00`,
      endTime: `${y}-${m}-${d} 23:59:59`,
    };
  }

  /** 获取某日的日期范围 */
  function getDateRange(dateStr: string): {
    startTime: string;
    endTime: string;
  } {
    return {
      startTime: `${dateStr} 00:00:00`,
      endTime: `${dateStr} 23:59:59`,
    };
  }

  // --- KPI 卡片钻取 ---

  function drillTodayOperations() {
    onDrillDown?.({ ...getTodayRange() });
  }

  function drillTodayErrors() {
    onDrillDown?.({ ...getTodayRange(), status: "1" });
  }

  function drillHighFrequencyAlerts() {
    onDrillDown?.({ ...getTodayRange() });
  }

  function drillActiveUsers() {
    onDrillDown?.({ ...getTodayRange() });
  }

  // --- 图表钻取 ---

  function handleTrendClick(date: string) {
    onDrillDown?.({ ...getDateRange(date) });
  }

  function handleErrorPieClick(item: ErrorOperationStatsVO) {
    onDrillDown?.({
      module: item.module,
      operationType: item.operationType,
      status: "1",
    });
  }

  // --- 表格钻取 ---

  function handleHighFreqRowClick(item: HighFrequencyOperationVO) {
    onDrillDown?.({ username: item.username, ...getTodayRange() });
  }

  function handleErrorRowClick(row: ErrorOperationStatsVO) {
    onDrillDown?.({
      module: row.module,
      operationType: row.operationType,
      status: "1",
    });
  }

  async function loadAllData() {
    loading = true;
    try {
      const [overviewRes, highFreqRes, errorStatsRes, timeDistRes] =
        await Promise.allSettled([
          getAuditOverview(),
          getHighFrequencyOperations(),
          getErrorOperationStats(),
          getUserTimeDistribution(),
        ]);

      if (overviewRes.status === "fulfilled") overview = overviewRes.value;
      if (highFreqRes.status === "fulfilled") highFreq = highFreqRes.value;
      if (errorStatsRes.status === "fulfilled")
        errorStats = errorStatsRes.value;
      if (timeDistRes.status === "fulfilled")
        timeDistribution = timeDistRes.value;

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

  const todayOperations = $derived(
    asNumber(overview?.["todayOperations"]) ?? 0,
  );
  const todayErrorOperations = $derived(
    asNumber(overview?.["todayErrorOperations"]) ?? 0,
  );
  const highFrequencyAlerts = $derived(
    asNumber(overview?.["highFrequencyAlerts"]) ?? 0,
  );
  const todayActiveUsers = $derived(
    asNumber(overview?.["todayActiveUsers"]) ?? 0,
  );
  const dailyStats = $derived(overview ? normalizeDailyStats(overview) : []);
</script>

<div class="space-y-6">
  <div class="flex items-center justify-between">
    <div>
      <h2 class="text-lg font-semibold">审计仪表盘</h2>
      <p class="text-muted-foreground text-sm">
        实时监控系统操作和异常行为，点击卡片或图表可查看详细日志
      </p>
    </div>
    <div class="flex items-center gap-3">
      {#if lastRefresh}
        <span class="text-muted-foreground text-xs">
          上次刷新: {lastRefresh.toLocaleTimeString("zh-CN", {
            hour: "2-digit",
            minute: "2-digit",
          })}
        </span>
      {/if}
      <Button
        variant="outline"
        size="sm"
        onclick={loadAllData}
        disabled={loading}
      >
        {#if loading}
          <div
            class="mr-2 h-3 w-3 animate-spin rounded-full border-2 border-current border-t-transparent"
          ></div>
        {/if}
        刷新
      </Button>
    </div>
  </div>

  <!-- KPI 卡片 -->
  <div class="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
    <KpiCard
      title="今日操作"
      value={todayOperations}
      icon="activity"
      onclick={drillTodayOperations}
      subtitle="点击查看详情"
    />
    <KpiCard
      title="今日错误"
      value={todayErrorOperations}
      icon="error"
      onclick={drillTodayErrors}
      subtitle="点击查看失败日志"
    />
    <KpiCard
      title="高频告警"
      value={highFrequencyAlerts}
      icon="alert"
      badge={highFrequencyAlerts > 0 ? String(highFrequencyAlerts) : undefined}
      badgeVariant={highFrequencyAlerts > 0 ? "destructive" : "outline"}
      onclick={drillHighFrequencyAlerts}
      subtitle="点击查看高频操作"
    />
    <KpiCard
      title="活跃用户"
      value={todayActiveUsers}
      icon="users"
      onclick={drillActiveUsers}
      subtitle="点击查看今日日志"
    />
  </div>

  <!-- 图表行 -->
  <div class="grid gap-4 lg:grid-cols-2">
    <TrendChart data={dailyStats} {loading} onDateClick={handleTrendClick} />
    <ErrorPieChart
      data={errorStats.slice(0, 6)}
      {loading}
      onItemClick={handleErrorPieClick}
    />
  </div>

  <!-- 热力图 -->
  <HeatmapChart data={timeDistribution} {loading} />

  <!-- 表格区域 -->
  <div class="grid gap-4 lg:grid-cols-2">
    <!-- 高频告警 -->
    <div class="bg-card/50 rounded-xl border p-4">
      <div class="mb-3 flex items-center justify-between">
        <p class="text-sm font-medium">高频操作告警</p>
        {#if highFreq.length > 0}
          <span class="text-muted-foreground text-xs">点击行查看详情</span>
        {/if}
      </div>
      {#if loading}
        <div class="flex h-[160px] items-center justify-center">
          <div
            class="border-primary h-5 w-5 animate-spin rounded-full border-2 border-t-transparent"
          ></div>
        </div>
      {:else if highFreq.length === 0}
        <div
          class="text-muted-foreground flex h-[160px] items-center justify-center text-sm"
        >
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
              {#each highFreq.slice(0, 5) as item (item.userId + item.requestIp)}
                <Table.Row
                  class="hover:bg-muted/40 cursor-pointer"
                  onclick={() => handleHighFreqRowClick(item)}
                >
                  <Table.Cell class="font-medium"
                    >{item.username || item.userId}</Table.Cell
                  >
                  <Table.Cell class="font-mono text-xs"
                    >{item.requestIp}</Table.Cell
                  >
                  <Table.Cell class="text-right font-mono text-sm"
                    >{formatNumber(item.operationCount)}</Table.Cell
                  >
                </Table.Row>
              {/each}
            </Table.Body>
          </Table.Root>
        </div>
      {/if}
    </div>

    <!-- 错误统计 -->
    <div class="bg-card/50 rounded-xl border p-4">
      <div class="mb-3 flex items-center justify-between">
        <p class="text-sm font-medium">错误统计 Top 5</p>
        {#if errorStats.length > 0}
          <span class="text-muted-foreground text-xs">点击行查看详情</span>
        {/if}
      </div>
      {#if loading}
        <div class="flex h-[160px] items-center justify-center">
          <div
            class="border-primary h-5 w-5 animate-spin rounded-full border-2 border-t-transparent"
          ></div>
        </div>
      {:else if errorStats.length === 0}
        <div
          class="text-muted-foreground flex h-[160px] items-center justify-center text-sm"
        >
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
              {#each errorStats.slice(0, 5) as row, i (i)}
                <Table.Row
                  class="hover:bg-muted/40 cursor-pointer"
                  onclick={() => handleErrorRowClick(row)}
                >
                  <Table.Cell class="font-medium">{row.module}</Table.Cell>
                  <Table.Cell class="text-xs">{row.operationType}</Table.Cell>
                  <Table.Cell class="text-right font-mono text-sm"
                    >{formatNumber(row.errorCount)}</Table.Cell
                  >
                </Table.Row>
              {/each}
            </Table.Body>
          </Table.Root>
        </div>
      {/if}
    </div>
  </div>
</div>
