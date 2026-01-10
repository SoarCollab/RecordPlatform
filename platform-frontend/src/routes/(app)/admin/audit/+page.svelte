<script lang="ts">
  import { onMount } from "svelte";
  import { goto } from "$app/navigation";
  import { useNotifications } from "$stores/notifications.svelte";
  import { useAuth } from "$stores/auth.svelte";
  import { formatDateTime, formatNumber } from "$utils/format";
  import {
    getAuditLogs,
    exportAuditLogs,
    getAuditLog,
    getAuditOverview,
    getHighFrequencyOperations,
    getSensitiveOperations,
    getErrorOperationStats,
    getUserTimeDistribution,
    getAuditConfigs,
    updateAuditConfig,
    checkAuditAnomalies,
    backupAuditLogs,
  } from "$api/endpoints/system";
  import type {
    AuditLogVO,
    AuditLogQueryParams,
    AuditConfigVO,
    HighFrequencyOperationVO,
    ErrorOperationStatsVO,
    UserTimeDistributionVO,
    SysOperationLog,
    AuditLogQueryVO,
  } from "$api/types";
  import { Button } from "$lib/components/ui/button";
  import { Input } from "$lib/components/ui/input";
  import { Badge } from "$lib/components/ui/badge";
  import * as Table from "$lib/components/ui/table";
  import * as Dialog from "$lib/components/ui/dialog";
  import * as Card from "$lib/components/ui/card";
  import * as Tabs from "$lib/components/ui/tabs";
  import DateTimePicker from "$lib/components/ui/date-picker/date-time-picker.svelte";

  const notifications = useNotifications();
  const auth = useAuth();

  let activeTab = $state("logs");

  let logs = $state<AuditLogVO[]>([]);
  let loadingLogs = $state(true);
  let page = $state(1);
  let pageSize = $state(20);
  let total = $state(0);

  let filterUsername = $state("");
  let filterModule = $state("");
  let filterAction = $state("");
  let filterStartTime = $state("");
  let filterEndTime = $state("");

  let logDetailDialogOpen = $state(false);
  let selectedAuditLog = $state<AuditLogVO | null>(null);
  let selectedLogDetail = $state<SysOperationLog | null>(null);
  let loadingLogDetail = $state(false);

  let isExporting = $state(false);

  let overview = $state<Record<string, unknown> | null>(null);
  let loadingOverview = $state(false);

  let highFreq = $state<HighFrequencyOperationVO[]>([]);
  let loadingHighFreq = $state(false);

  let sensitive = $state<SysOperationLog[]>([]);
  let loadingSensitive = $state(false);
  let sensitivePage = $state(1);
  let sensitivePageSize = $state(20);
  let sensitiveTotal = $state(0);
  let sensitiveUsername = $state("");
  let sensitiveModule = $state("");
  let sensitiveOperationType = $state("");
  let sensitiveStatus = $state<string>("");
  let sensitiveStartTime = $state("");
  let sensitiveEndTime = $state("");

  let errorStats = $state<ErrorOperationStatsVO[]>([]);
  let loadingErrorStats = $state(false);

  let timeDistribution = $state<UserTimeDistributionVO[]>([]);
  let loadingTimeDistribution = $state(false);

  let auditConfigs = $state<AuditConfigVO[]>([]);
  let loadingConfigs = $state(false);
  let configDialogOpen = $state(false);
  let editingConfig = $state<AuditConfigVO | null>(null);
  let editingConfigValue = $state("");
  let savingConfig = $state(false);

  let anomalies = $state<Record<string, unknown> | null>(null);
  let checkingAnomalies = $state(false);

  let backupDays = $state(180);
  let backupDeleteAfter = $state(false);
  let backupRunning = $state(false);
  let backupResult = $state<string | null>(null);

  let sensitiveDetailDialogOpen = $state(false);
  let selectedSensitiveRow = $state<SysOperationLog | null>(null);
  let sensitiveDetail = $state<SysOperationLog | null>(null);
  let loadingSensitiveDetail = $state(false);

  const canAccess = $derived(auth.isAdminOrMonitor);

  onMount(() => {
    if (!canAccess) {
      notifications.error("权限不足", "仅管理员或监控员可访问此页面");
      goto("/dashboard");
      return;
    }
    loadLogs();
  });

  async function loadLogs() {
    loadingLogs = true;
    try {
      const params: AuditLogQueryParams & { pageNum: number; pageSize: number } = {
        pageNum: page,
        pageSize,
      };
      if (filterUsername) params.username = filterUsername;
      if (filterModule) params.module = filterModule;
      if (filterAction) params.operationType = filterAction;
      if (filterStartTime) params.startTime = filterStartTime;
      if (filterEndTime) params.endTime = filterEndTime;

      const result = await getAuditLogs(params);
      logs = result.records;
      total = result.total;
    } catch (err) {
      notifications.error(
        "加载失败",
        err instanceof Error ? err.message : "请稍后重试",
      );
    } finally {
      loadingLogs = false;
    }
  }

  function handleSearch() {
    page = 1;
    loadLogs();
  }

  function clearFilters() {
    filterUsername = "";
    filterModule = "";
    filterAction = "";
    filterStartTime = "";
    filterEndTime = "";
    page = 1;
    loadLogs();
  }

  async function openLogDetail(log: AuditLogVO) {
    selectedAuditLog = log;
    selectedLogDetail = null;
    logDetailDialogOpen = true;
    loadingLogDetail = true;
    try {
      selectedLogDetail = await getAuditLog(String(log.id));
    } catch {
      selectedLogDetail = null;
    } finally {
      loadingLogDetail = false;
    }
  }

  async function handleExport() {
    isExporting = true;
    try {
      const params: AuditLogQueryParams = {};
      if (filterUsername) params.username = filterUsername;
      if (filterModule) params.module = filterModule;
      if (filterAction) params.operationType = filterAction;
      if (filterStartTime) params.startTime = filterStartTime;
      if (filterEndTime) params.endTime = filterEndTime;

      const blob = await exportAuditLogs(params);
      const url = URL.createObjectURL(blob);
      const a = document.createElement("a");
      a.href = url;
      a.download = `audit_logs_${new Date().toISOString().split("T")[0]}.xlsx`;
      document.body.appendChild(a);
      a.click();
      document.body.removeChild(a);
      URL.revokeObjectURL(url);
      notifications.success("导出成功");
    } catch (err) {
      notifications.error(
        "导出失败",
        err instanceof Error ? err.message : "请稍后重试",
      );
    } finally {
      isExporting = false;
    }
  }

  function getStatusVariant(status: number): "default" | "destructive" {
    return status === 0 ? "default" : "destructive";
  }

  function getStatusLabel(status: number): string {
    return status === 0 ? "成功" : "失败";
  }

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

  function asBoolean(value: unknown): boolean | null {
    if (typeof value === "boolean") return value;
    if (typeof value === "string") {
      const v = value.trim().toLowerCase();
      if (v === "true" || v === "1" || v === "yes") return true;
      if (v === "false" || v === "0" || v === "no") return false;
    }
    return null;
  }

  function getOverviewNumber(source: Record<string, unknown>, key: string): number | null {
    return asNumber(source[key]);
  }

  function getOverviewString(source: Record<string, unknown>, key: string): string {
    const v = source[key];
    if (v === null || v === undefined) return "-";
    if (typeof v === "string") return v;
    if (typeof v === "number" || typeof v === "boolean") return String(v);
    return JSON.stringify(v);
  }

  function normalizeDailyStats(source: Record<string, unknown>): Array<{ date: string; count: number }> {
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

  async function ensureOverviewLoaded() {
    if (overview || loadingOverview) return;
    loadingOverview = true;
    try {
      overview = await getAuditOverview();
    } catch (err) {
      notifications.error(
        "加载失败",
        err instanceof Error ? err.message : "请稍后重试",
      );
    } finally {
      loadingOverview = false;
    }
  }

  async function ensureHighFreqLoaded() {
    if (highFreq.length || loadingHighFreq) return;
    loadingHighFreq = true;
    try {
      highFreq = await getHighFrequencyOperations();
    } catch (err) {
      notifications.error(
        "加载失败",
        err instanceof Error ? err.message : "请稍后重试",
      );
    } finally {
      loadingHighFreq = false;
    }
  }

  async function loadSensitive() {
    loadingSensitive = true;
    try {
      const query: AuditLogQueryVO = {
        pageNum: sensitivePage,
        pageSize: sensitivePageSize,
      };
      if (sensitiveUsername) query.username = sensitiveUsername;
      if (sensitiveModule) query.module = sensitiveModule;
      if (sensitiveOperationType) query.operationType = sensitiveOperationType;
      if (sensitiveStartTime) query.startTime = sensitiveStartTime;
      if (sensitiveEndTime) query.endTime = sensitiveEndTime;
      if (sensitiveStatus !== "") query.status = Number(sensitiveStatus);

      const result = await getSensitiveOperations(query);
      sensitive = result.records;
      sensitiveTotal = result.total;
    } catch (err) {
      notifications.error(
        "加载失败",
        err instanceof Error ? err.message : "请稍后重试",
      );
    } finally {
      loadingSensitive = false;
    }
  }

  function resetSensitiveFilters() {
    sensitiveUsername = "";
    sensitiveModule = "";
    sensitiveOperationType = "";
    sensitiveStartTime = "";
    sensitiveEndTime = "";
    sensitiveStatus = "";
    sensitivePage = 1;
    loadSensitive();
  }

  async function openSensitiveDetail(row: SysOperationLog) {
    selectedSensitiveRow = row;
    sensitiveDetail = row;
    sensitiveDetailDialogOpen = true;

    const id = row.id;
    if (id === null || id === undefined || id === "") return;

    loadingSensitiveDetail = true;
    try {
      sensitiveDetail = await getAuditLog(String(id));
    } catch {
      sensitiveDetail = row;
    } finally {
      loadingSensitiveDetail = false;
    }
  }

  async function ensureErrorStatsLoaded() {
    if (errorStats.length || loadingErrorStats) return;
    loadingErrorStats = true;
    try {
      errorStats = await getErrorOperationStats();
    } catch (err) {
      notifications.error(
        "加载失败",
        err instanceof Error ? err.message : "请稍后重试",
      );
    } finally {
      loadingErrorStats = false;
    }
  }

  async function ensureTimeDistributionLoaded() {
    if (timeDistribution.length || loadingTimeDistribution) return;
    loadingTimeDistribution = true;
    try {
      timeDistribution = await getUserTimeDistribution();
    } catch (err) {
      notifications.error(
        "加载失败",
        err instanceof Error ? err.message : "请稍后重试",
      );
    } finally {
      loadingTimeDistribution = false;
    }
  }

  function getDistributionCount(day: number, hour: number): number {
    const item = timeDistribution.find(
      (d) => d.dayOfWeek === day && d.hourOfDay === hour,
    );
    return item?.operationCount ?? 0;
  }

  const maxDistributionCount = $derived(
    timeDistribution.reduce((max, item) => Math.max(max, item.operationCount), 0),
  );

  function getHeatOpacity(count: number): number {
    if (maxDistributionCount <= 0) return 0.08;
    const ratio = count / maxDistributionCount;
    return Math.max(0.08, Math.min(1, ratio));
  }

  async function ensureConfigsLoaded() {
    if (auditConfigs.length || loadingConfigs) return;
    loadingConfigs = true;
    try {
      auditConfigs = await getAuditConfigs();
    } catch (err) {
      notifications.error(
        "加载失败",
        err instanceof Error ? err.message : "请稍后重试",
      );
    } finally {
      loadingConfigs = false;
    }
  }

  function openEditConfig(cfg: AuditConfigVO) {
    editingConfig = cfg;
    editingConfigValue = cfg.configValue;
    configDialogOpen = true;
  }

  async function saveConfig() {
    if (!editingConfig) return;
    savingConfig = true;
    try {
      const updated: AuditConfigVO = {
        ...editingConfig,
        configValue: editingConfigValue,
      };
      const ok = await updateAuditConfig(updated);
      if (!ok) {
        throw new Error("更新失败");
      }
      auditConfigs = auditConfigs.map((c) =>
        c.id === editingConfig?.id ? updated : c,
      );
      notifications.success("更新成功");
      configDialogOpen = false;
      editingConfig = null;
    } catch (err) {
      notifications.error(
        "更新失败",
        err instanceof Error ? err.message : "请稍后重试",
      );
    } finally {
      savingConfig = false;
    }
  }

  async function runAnomalyCheck() {
    checkingAnomalies = true;
    anomalies = null;
    try {
      anomalies = await checkAuditAnomalies();
      notifications.success("检查完成");
    } catch (err) {
      notifications.error(
        "检查失败",
        err instanceof Error ? err.message : "请稍后重试",
      );
    } finally {
      checkingAnomalies = false;
    }
  }

  async function runBackup() {
    backupRunning = true;
    backupResult = null;
    try {
      backupResult = await backupAuditLogs({
        days: backupDays,
        deleteAfterBackup: backupDeleteAfter,
      });
      notifications.success("备份完成");
    } catch (err) {
      notifications.error(
        "备份失败",
        err instanceof Error ? err.message : "请稍后重试",
      );
    } finally {
      backupRunning = false;
    }
  }

  function handleTabChange(value: string) {
    activeTab = value;
    if (value === "overview") ensureOverviewLoaded();
    if (value === "highfreq") ensureHighFreqLoaded();
    if (value === "sensitive") loadSensitive();
    if (value === "errors") ensureErrorStatsLoaded();
    if (value === "distribution") ensureTimeDistributionLoaded();
    if (value === "configs") ensureConfigsLoaded();
  }
</script>

<svelte:head>
  <title>系统审计 - 存证平台</title>
</svelte:head>

<div class="space-y-6">
  <div class="flex items-center justify-between">
    <div>
      <h1 class="text-2xl font-bold">系统审计</h1>
      <p class="text-muted-foreground">操作日志、敏感操作与审计配置</p>
    </div>
    <div class="flex items-center gap-2">
      <Button onclick={handleExport} disabled={isExporting} variant="outline">
        {#if isExporting}
          <div class="mr-2 h-4 w-4 animate-spin rounded-full border-2 border-current border-t-transparent"></div>
        {/if}
        导出日志
      </Button>
      <Button variant="outline" onclick={loadLogs} disabled={loadingLogs}>
        刷新
      </Button>
    </div>
  </div>

  <Tabs.Root value={activeTab} onValueChange={handleTabChange}>
    <Tabs.List>
      <Tabs.Trigger value="logs">审计日志</Tabs.Trigger>
      <Tabs.Trigger value="overview">概览</Tabs.Trigger>
      <Tabs.Trigger value="highfreq">高频操作</Tabs.Trigger>
      <Tabs.Trigger value="sensitive">敏感操作</Tabs.Trigger>
      <Tabs.Trigger value="errors">错误统计</Tabs.Trigger>
      <Tabs.Trigger value="distribution">时间分布</Tabs.Trigger>
      <Tabs.Trigger value="configs">审计配置</Tabs.Trigger>
      <Tabs.Trigger value="tools">工具</Tabs.Trigger>
    </Tabs.List>

    <Tabs.Content value="logs" class="mt-4">
      <Card.Root>
        <Card.Header class="pb-4">
          <div class="grid gap-4 md:grid-cols-2 lg:grid-cols-3 xl:grid-cols-6">
            <Input
              placeholder="用户名"
              bind:value={filterUsername}
              onkeydown={(e) => e.key === "Enter" && handleSearch()}
            />
            <Input
              placeholder="模块"
              bind:value={filterModule}
              onkeydown={(e) => e.key === "Enter" && handleSearch()}
            />
            <Input
              placeholder="操作类型"
              bind:value={filterAction}
              onkeydown={(e) => e.key === "Enter" && handleSearch()}
            />
            <DateTimePicker bind:value={filterStartTime} placeholder="开始时间" />
            <DateTimePicker bind:value={filterEndTime} placeholder="结束时间" />
            <div class="flex gap-2">
              <Button onclick={handleSearch} class="flex-1">搜索</Button>
              <Button variant="secondary" onclick={clearFilters}>重置</Button>
            </div>
          </div>
        </Card.Header>
        <Card.Content>
          {#if loadingLogs}
            <div class="flex items-center justify-center p-10">
              <div class="h-8 w-8 animate-spin rounded-full border-4 border-primary border-t-transparent"></div>
            </div>
          {:else}
            <Table.Root>
              <Table.Header>
                <Table.Row>
                  <Table.Head>时间</Table.Head>
                  <Table.Head>用户</Table.Head>
                  <Table.Head>模块</Table.Head>
                  <Table.Head>动作</Table.Head>
                  <Table.Head>IP</Table.Head>
                  <Table.Head>耗时</Table.Head>
                  <Table.Head>状态</Table.Head>
                  <Table.Head class="text-right">操作</Table.Head>
                </Table.Row>
              </Table.Header>
              <Table.Body>
                {#each logs as log (log.id)}
                  <Table.Row>
                    <Table.Cell>{log.createTime ? formatDateTime(log.createTime) : "-"}</Table.Cell>
                    <Table.Cell>{log.username || "-"}</Table.Cell>
                    <Table.Cell>{log.module || "-"}</Table.Cell>
                    <Table.Cell>{log.action || "-"}</Table.Cell>
                    <Table.Cell>{log.ip || "-"}</Table.Cell>
                    <Table.Cell>{log.duration ?? 0}ms</Table.Cell>
                    <Table.Cell>
                      <Badge variant={getStatusVariant(log.status)}>
                        {getStatusLabel(log.status)}
                      </Badge>
                    </Table.Cell>
                    <Table.Cell class="text-right">
                      <Button size="sm" variant="outline" onclick={() => openLogDetail(log)}>
                        详情
                      </Button>
                    </Table.Cell>
                  </Table.Row>
                {/each}
              </Table.Body>
            </Table.Root>

            {#if total > pageSize}
              <div class="mt-4 flex items-center justify-between">
                <p class="text-sm text-muted-foreground">
                  共 {total} 条，第 {page} / {Math.max(1, Math.ceil(total / pageSize))} 页
                </p>
                <div class="flex gap-2">
                  <Button
                    variant="outline"
                    disabled={page <= 1}
                    onclick={() => {
                      page = Math.max(1, page - 1);
                      loadLogs();
                    }}
                  >
                    上一页
                  </Button>
                  <Button
                    variant="outline"
                    disabled={page >= Math.ceil(total / pageSize)}
                    onclick={() => {
                      page = page + 1;
                      loadLogs();
                    }}
                  >
                    下一页
                  </Button>
                </div>
              </div>
            {/if}
          {/if}
        </Card.Content>
      </Card.Root>
    </Tabs.Content>

    <Tabs.Content value="overview" class="mt-4">
      <Card.Root>
        <Card.Header>
          <div class="flex items-center justify-between">
            <Card.Title>审计概览</Card.Title>
            <Button
              variant="outline"
              onclick={async () => {
                overview = null;
                await ensureOverviewLoaded();
              }}
              disabled={loadingOverview}
            >
              刷新
            </Button>
          </div>
        </Card.Header>
        <Card.Content>
          {#if loadingOverview}
            <div class="flex items-center justify-center p-10">
              <div class="h-8 w-8 animate-spin rounded-full border-4 border-primary border-t-transparent"></div>
            </div>
          {:else if overview}
            {@const o = overview}
            {@const totalOperations = getOverviewNumber(o, "totalOperations") ?? 0}
            {@const todayOperations = getOverviewNumber(o, "todayOperations") ?? 0}
            {@const totalErrorOperations = getOverviewNumber(o, "totalErrorOperations") ?? 0}
            {@const todayErrorOperations = getOverviewNumber(o, "todayErrorOperations") ?? 0}
            {@const todaySensitiveOperations = getOverviewNumber(o, "todaySensitiveOperations") ?? 0}
            {@const todayActiveUsers = getOverviewNumber(o, "todayActiveUsers") ?? 0}
            {@const highFrequencyAlerts = getOverviewNumber(o, "highFrequencyAlerts") ?? 0}
            {@const auditEnabled = asBoolean(o["auditEnabled"]) }
            {@const logRetentionDays = getOverviewString(o, "logRetentionDays") }
            {@const daily = normalizeDailyStats(o)}
            {@const maxDaily = daily.reduce((m, s) => Math.max(m, s.count), 0)}

            <div class="grid gap-4 md:grid-cols-2 lg:grid-cols-4">
              <div class="rounded-xl border bg-card/50 p-4">
                <p class="text-sm text-muted-foreground">总操作数</p>
                <p class="mt-1 text-2xl font-semibold">{formatNumber(totalOperations)}</p>
              </div>
              <div class="rounded-xl border bg-card/50 p-4">
                <p class="text-sm text-muted-foreground">今日操作数</p>
                <p class="mt-1 text-2xl font-semibold">{formatNumber(todayOperations)}</p>
              </div>
              <div class="rounded-xl border bg-card/50 p-4">
                <p class="text-sm text-muted-foreground">总错误操作</p>
                <p class="mt-1 text-2xl font-semibold">{formatNumber(totalErrorOperations)}</p>
              </div>
              <div class="rounded-xl border bg-card/50 p-4">
                <p class="text-sm text-muted-foreground">今日错误操作</p>
                <p class="mt-1 text-2xl font-semibold">{formatNumber(todayErrorOperations)}</p>
              </div>
              <div class="rounded-xl border bg-card/50 p-4">
                <p class="text-sm text-muted-foreground">今日敏感操作</p>
                <p class="mt-1 text-2xl font-semibold">{formatNumber(todaySensitiveOperations)}</p>
              </div>
              <div class="rounded-xl border bg-card/50 p-4">
                <p class="text-sm text-muted-foreground">今日活跃用户</p>
                <p class="mt-1 text-2xl font-semibold">{formatNumber(todayActiveUsers)}</p>
              </div>
              <div class="rounded-xl border bg-card/50 p-4">
                <p class="text-sm text-muted-foreground">高频告警</p>
                <p class="mt-1 text-2xl font-semibold">{formatNumber(highFrequencyAlerts)}</p>
              </div>
              <div class="rounded-xl border bg-card/50 p-4">
                <p class="text-sm text-muted-foreground">审计开关</p>
                <div class="mt-2">
                  {#if auditEnabled === true}
                    <Badge>启用</Badge>
                  {:else if auditEnabled === false}
                    <Badge variant="destructive">关闭</Badge>
                  {:else}
                    <Badge variant="outline">未知</Badge>
                  {/if}
                </div>
                <p class="mt-3 text-xs text-muted-foreground">日志保留 {logRetentionDays} 天</p>
              </div>
            </div>

            <div class="mt-6 grid gap-4 lg:grid-cols-2">
              <div class="rounded-xl border bg-card/50 p-4">
                <p class="text-sm font-medium">最近 7 天操作趋势</p>
                {#if daily.length === 0}
                  <p class="mt-3 text-sm text-muted-foreground">暂无趋势数据</p>
                {:else}
                  <div class="mt-4 space-y-2">
                    {#each daily as d (d.date)}
                      {@const ratio = maxDaily > 0 ? d.count / maxDaily : 0}
                      <div class="grid grid-cols-[110px_1fr_70px] items-center gap-3">
                        <span class="text-xs text-muted-foreground">{d.date}</span>
                        <div class="h-2 overflow-hidden rounded-full bg-muted">
                          <div
                            class="h-full rounded-full bg-primary"
                            style={`width: ${Math.round(ratio * 100)}%`}
                          ></div>
                        </div>
                        <span class="text-right text-xs font-mono">{formatNumber(d.count)}</span>
                      </div>
                    {/each}
                  </div>
                {/if}
              </div>

              <div class="rounded-xl border bg-card/50 p-4">
                <p class="text-sm font-medium">概览原始字段</p>
                <div class="mt-4 grid gap-2">
                  <div class="flex items-center justify-between rounded-lg border bg-muted/20 px-3 py-2">
                    <span class="text-sm text-muted-foreground">auditEnabled</span>
                    <span class="font-mono text-xs">{getOverviewString(o, "auditEnabled")}</span>
                  </div>
                  <div class="flex items-center justify-between rounded-lg border bg-muted/20 px-3 py-2">
                    <span class="text-sm text-muted-foreground">logRetentionDays</span>
                    <span class="font-mono text-xs">{logRetentionDays}</span>
                  </div>
                  <div class="flex items-center justify-between rounded-lg border bg-muted/20 px-3 py-2">
                    <span class="text-sm text-muted-foreground">error</span>
                    <span class="font-mono text-xs">{getOverviewString(o, "error")}</span>
                  </div>
                </div>

                <details class="mt-4">
                  <summary class="cursor-pointer text-sm text-muted-foreground hover:text-foreground">查看完整 JSON</summary>
                  <pre class="mt-3 max-h-[360px] overflow-auto rounded-lg border bg-muted/20 p-4 text-xs">{JSON.stringify(o, null, 2)}</pre>
                </details>
              </div>
            </div>
          {:else}
            <div class="p-6 text-sm text-muted-foreground">暂无数据</div>
          {/if}
        </Card.Content>
      </Card.Root>
    </Tabs.Content>

    <Tabs.Content value="highfreq" class="mt-4">
      <Card.Root>
        <Card.Header>
          <div class="flex items-center justify-between">
            <Card.Title>高频操作</Card.Title>
            <Button
              variant="outline"
              onclick={async () => {
                highFreq = [];
                await ensureHighFreqLoaded();
              }}
              disabled={loadingHighFreq}
            >
              刷新
            </Button>
          </div>
        </Card.Header>
        <Card.Content>
          {#if loadingHighFreq}
            <div class="flex items-center justify-center p-10">
              <div class="h-8 w-8 animate-spin rounded-full border-4 border-primary border-t-transparent"></div>
            </div>
          {:else}
            <Table.Root>
              <Table.Header>
                <Table.Row>
                  <Table.Head>用户</Table.Head>
                  <Table.Head>IP</Table.Head>
                  <Table.Head>次数</Table.Head>
                  <Table.Head>开始</Table.Head>
                  <Table.Head>结束</Table.Head>
                  <Table.Head>跨度(秒)</Table.Head>
                </Table.Row>
              </Table.Header>
              <Table.Body>
                {#each highFreq as item (item.userId + item.requestIp)}
                  <Table.Row>
                    <Table.Cell>{item.username || item.userId}</Table.Cell>
                    <Table.Cell>{item.requestIp}</Table.Cell>
                    <Table.Cell>{formatNumber(item.operationCount)}</Table.Cell>
                    <Table.Cell>{item.startTime ? formatDateTime(item.startTime) : "-"}</Table.Cell>
                    <Table.Cell>{item.endTime ? formatDateTime(item.endTime) : "-"}</Table.Cell>
                    <Table.Cell>{item.timeSpanSeconds}</Table.Cell>
                  </Table.Row>
                {/each}
              </Table.Body>
            </Table.Root>
            {#if highFreq.length === 0}
              <div class="p-6 text-sm text-muted-foreground">暂无数据</div>
            {/if}
          {/if}
        </Card.Content>
      </Card.Root>
    </Tabs.Content>

    <Tabs.Content value="sensitive" class="mt-4">
      <Card.Root>
        <Card.Header class="pb-4">
          <div class="flex items-center justify-between">
            <Card.Title>敏感操作</Card.Title>
            <Button variant="outline" onclick={loadSensitive} disabled={loadingSensitive}>
              刷新
            </Button>
          </div>
          <div class="mt-4 grid gap-4 md:grid-cols-2 lg:grid-cols-3 xl:grid-cols-6">
            <Input
              placeholder="用户名"
              bind:value={sensitiveUsername}
              onkeydown={(e) => e.key === "Enter" && loadSensitive()}
            />
            <Input
              placeholder="模块"
              bind:value={sensitiveModule}
              onkeydown={(e) => e.key === "Enter" && loadSensitive()}
            />
            <Input
              placeholder="操作类型"
              bind:value={sensitiveOperationType}
              onkeydown={(e) => e.key === "Enter" && loadSensitive()}
            />
            <select
              bind:value={sensitiveStatus}
              class="h-9 w-full rounded-md border bg-background px-3 text-sm focus:border-primary focus:outline-none"
            >
              <option value="">全部状态</option>
              <option value="0">成功</option>
              <option value="1">失败</option>
            </select>
            <DateTimePicker bind:value={sensitiveStartTime} placeholder="开始时间" />
            <DateTimePicker bind:value={sensitiveEndTime} placeholder="结束时间" />
            <div class="flex gap-2 xl:col-span-6">
              <Button onclick={() => {
                sensitivePage = 1;
                loadSensitive();
              }}>搜索</Button>
              <Button variant="secondary" onclick={resetSensitiveFilters}>重置</Button>
            </div>
          </div>
        </Card.Header>
        <Card.Content>
          {#if loadingSensitive}
            <div class="flex items-center justify-center p-10">
              <div class="h-8 w-8 animate-spin rounded-full border-4 border-primary border-t-transparent"></div>
            </div>
          {:else}
            <Table.Root>
              <Table.Header>
                <Table.Row>
                  <Table.Head>时间</Table.Head>
                  <Table.Head>用户</Table.Head>
                  <Table.Head>模块</Table.Head>
                  <Table.Head>类型</Table.Head>
                  <Table.Head>URL</Table.Head>
                  <Table.Head>IP</Table.Head>
                  <Table.Head>状态</Table.Head>
                </Table.Row>
              </Table.Header>
              <Table.Body>
                {#each sensitive as row (String(row.id))}
                  <Table.Row
                    class="cursor-pointer hover:bg-muted/40"
                    onclick={() => openSensitiveDetail(row)}
                    role="button"
                    tabindex={0}
                    onkeypress={(e) => e.key === "Enter" && openSensitiveDetail(row)}
                  >
                    <Table.Cell>{row.operationTime ? formatDateTime(String(row.operationTime)) : "-"}</Table.Cell>
                    <Table.Cell>{row.username || row.userId || "-"}</Table.Cell>
                    <Table.Cell>{row.module || "-"}</Table.Cell>
                    <Table.Cell>{row.operationType || "-"}</Table.Cell>
                    <Table.Cell class="max-w-[260px] truncate">{row.requestUrl || "-"}</Table.Cell>
                    <Table.Cell>{row.requestIp || "-"}</Table.Cell>
                    <Table.Cell>
                      <Badge variant={getStatusVariant(row.status)}>
                        {getStatusLabel(row.status)}
                      </Badge>
                    </Table.Cell>
                  </Table.Row>
                {/each}
              </Table.Body>
            </Table.Root>

            {#if sensitiveTotal > sensitivePageSize}
              <div class="mt-4 flex items-center justify-between">
                <p class="text-sm text-muted-foreground">
                  共 {sensitiveTotal} 条，第 {sensitivePage} / {Math.max(1, Math.ceil(sensitiveTotal / sensitivePageSize))} 页
                </p>
                <div class="flex gap-2">
                  <Button
                    variant="outline"
                    disabled={sensitivePage <= 1}
                    onclick={() => {
                      sensitivePage = Math.max(1, sensitivePage - 1);
                      loadSensitive();
                    }}
                  >
                    上一页
                  </Button>
                  <Button
                    variant="outline"
                    disabled={sensitivePage >= Math.ceil(sensitiveTotal / sensitivePageSize)}
                    onclick={() => {
                      sensitivePage = sensitivePage + 1;
                      loadSensitive();
                    }}
                  >
                    下一页
                  </Button>
                </div>
              </div>
            {/if}

            {#if sensitive.length === 0}
              <div class="p-6 text-sm text-muted-foreground">暂无数据</div>
            {/if}
          {/if}
        </Card.Content>
      </Card.Root>
    </Tabs.Content>

    <Tabs.Content value="errors" class="mt-4">
      <Card.Root>
        <Card.Header>
          <div class="flex items-center justify-between">
            <Card.Title>错误操作统计</Card.Title>
            <Button
              variant="outline"
              onclick={async () => {
                errorStats = [];
                await ensureErrorStatsLoaded();
              }}
              disabled={loadingErrorStats}
            >
              刷新
            </Button>
          </div>
        </Card.Header>
        <Card.Content>
          {#if loadingErrorStats}
            <div class="flex items-center justify-center p-10">
              <div class="h-8 w-8 animate-spin rounded-full border-4 border-primary border-t-transparent"></div>
            </div>
          {:else}
            <Table.Root>
              <Table.Header>
                <Table.Row>
                  <Table.Head>模块</Table.Head>
                  <Table.Head>类型</Table.Head>
                  <Table.Head>错误</Table.Head>
                  <Table.Head>次数</Table.Head>
                  <Table.Head>首次出现</Table.Head>
                  <Table.Head>最近出现</Table.Head>
                </Table.Row>
              </Table.Header>
              <Table.Body>
                {#each errorStats as row (row.module + row.operationType + row.errorMsg)}
                  <Table.Row>
                    <Table.Cell>{row.module}</Table.Cell>
                    <Table.Cell>{row.operationType}</Table.Cell>
                    <Table.Cell class="max-w-[360px] truncate">{row.errorMsg}</Table.Cell>
                    <Table.Cell>{formatNumber(row.errorCount)}</Table.Cell>
                    <Table.Cell>{row.firstOccurrence ? formatDateTime(row.firstOccurrence) : "-"}</Table.Cell>
                    <Table.Cell>{row.lastOccurrence ? formatDateTime(row.lastOccurrence) : "-"}</Table.Cell>
                  </Table.Row>
                {/each}
              </Table.Body>
            </Table.Root>
            {#if errorStats.length === 0}
              <div class="p-6 text-sm text-muted-foreground">暂无数据</div>
            {/if}
          {/if}
        </Card.Content>
      </Card.Root>
    </Tabs.Content>

    <Tabs.Content value="distribution" class="mt-4">
      <Card.Root>
        <Card.Header>
          <div class="flex items-center justify-between">
            <Card.Title>用户操作时间分布</Card.Title>
            <Button
              variant="outline"
              onclick={async () => {
                timeDistribution = [];
                await ensureTimeDistributionLoaded();
              }}
              disabled={loadingTimeDistribution}
            >
              刷新
            </Button>
          </div>
        </Card.Header>
        <Card.Content>
          {#if loadingTimeDistribution}
            <div class="flex items-center justify-center p-10">
              <div class="h-8 w-8 animate-spin rounded-full border-4 border-primary border-t-transparent"></div>
            </div>
          {:else}
            <div class="overflow-auto rounded-lg border">
              <div class="min-w-[900px]">
                <div class="grid" style="grid-template-columns: 80px repeat(24, minmax(24px, 1fr));">
                  <div class="border-b bg-muted/30 p-2 text-xs text-muted-foreground">星期/小时</div>
                  {#each Array(24) as _, hour}
                    <div class="border-b bg-muted/30 p-2 text-center text-xs text-muted-foreground">{hour}</div>
                  {/each}

                  {#each [0, 1, 2, 3, 4, 5, 6] as day}
                    <div class="border-b p-2 text-xs font-medium">
                      {[
                        "周一",
                        "周二",
                        "周三",
                        "周四",
                        "周五",
                        "周六",
                        "周日",
                      ][day]}
                    </div>
                    {#each Array(24) as _, hour}
                      {@const count = getDistributionCount(day, hour)}
                      <div
                        class="border-b border-l p-2"
                        title={`${count} 次`}
                        style={`background-color: rgba(59, 130, 246, ${getHeatOpacity(count)})`}
                      ></div>
                    {/each}
                  {/each}
                </div>
              </div>
            </div>

            {#if timeDistribution.length === 0}
              <div class="p-6 text-sm text-muted-foreground">暂无数据</div>
            {/if}
          {/if}
        </Card.Content>
      </Card.Root>
    </Tabs.Content>

    <Tabs.Content value="configs" class="mt-4">
      <Card.Root>
        <Card.Header>
          <div class="flex items-center justify-between">
            <Card.Title>审计配置</Card.Title>
            <Button
              variant="outline"
              onclick={async () => {
                auditConfigs = [];
                await ensureConfigsLoaded();
              }}
              disabled={loadingConfigs}
            >
              刷新
            </Button>
          </div>
        </Card.Header>
        <Card.Content>
          {#if loadingConfigs}
            <div class="flex items-center justify-center p-10">
              <div class="h-8 w-8 animate-spin rounded-full border-4 border-primary border-t-transparent"></div>
            </div>
          {:else}
            <Table.Root>
              <Table.Header>
                <Table.Row>
                  <Table.Head>Key</Table.Head>
                  <Table.Head>Value</Table.Head>
                  <Table.Head>描述</Table.Head>
                  <Table.Head class="text-right">操作</Table.Head>
                </Table.Row>
              </Table.Header>
              <Table.Body>
                {#each auditConfigs as cfg (cfg.id)}
                  <Table.Row>
                    <Table.Cell class="font-mono text-xs">{cfg.configKey}</Table.Cell>
                    <Table.Cell class="font-mono text-xs">{cfg.configValue}</Table.Cell>
                    <Table.Cell class="max-w-[420px] truncate">{cfg.description || "-"}</Table.Cell>
                    <Table.Cell class="text-right">
                      <Button size="sm" variant="outline" onclick={() => openEditConfig(cfg)}>
                        编辑
                      </Button>
                    </Table.Cell>
                  </Table.Row>
                {/each}
              </Table.Body>
            </Table.Root>

            {#if auditConfigs.length === 0}
              <div class="p-6 text-sm text-muted-foreground">暂无数据</div>
            {/if}
          {/if}
        </Card.Content>
      </Card.Root>

      <Dialog.Root bind:open={configDialogOpen}>
        <Dialog.Content class="max-w-lg">
          <Dialog.Header>
            <Dialog.Title>编辑配置</Dialog.Title>
            {#if editingConfig}
              <Dialog.Description class="font-mono text-xs">{editingConfig.configKey}</Dialog.Description>
            {/if}
          </Dialog.Header>
          <div class="space-y-4">
            <Input bind:value={editingConfigValue} placeholder="配置值" />
            <div class="flex justify-end gap-2">
              <Button variant="secondary" onclick={() => (configDialogOpen = false)}>取消</Button>
              <Button onclick={saveConfig} disabled={savingConfig}>
                {savingConfig ? "保存中..." : "保存"}
              </Button>
            </div>
          </div>
        </Dialog.Content>
      </Dialog.Root>
    </Tabs.Content>

    <Tabs.Content value="tools" class="mt-4">
      <div class="grid gap-4 lg:grid-cols-2">
        <Card.Root>
          <Card.Header>
            <Card.Title>异常检查</Card.Title>
          </Card.Header>
          <Card.Content>
            <div class="flex gap-2">
              <Button onclick={runAnomalyCheck} disabled={checkingAnomalies}>
                {checkingAnomalies ? "检查中..." : "执行检查"}
              </Button>
              <Button variant="outline" onclick={() => (anomalies = null)}>清空</Button>
            </div>
            {#if anomalies}
              <pre class="mt-4 max-h-[360px] overflow-auto rounded-lg border bg-muted/20 p-4 text-xs">{JSON.stringify(anomalies, null, 2)}</pre>
            {/if}
          </Card.Content>
        </Card.Root>

        <Card.Root>
          <Card.Header>
            <Card.Title>日志备份</Card.Title>
          </Card.Header>
          <Card.Content>
            <div class="grid gap-4">
              <div class="grid gap-2">
                <label for="backupDays" class="text-sm text-muted-foreground">备份天数</label>
                <Input id="backupDays" type="number" bind:value={backupDays} min={1} max={3650} />
              </div>
              <label class="flex items-center gap-2 text-sm cursor-pointer">
                <input type="checkbox" bind:checked={backupDeleteAfter} class="h-4 w-4 rounded border accent-primary" />
                <span>备份后删除日志</span>
              </label>
              <div class="flex gap-2">
                <Button onclick={runBackup} disabled={backupRunning}>
                  {backupRunning ? "执行中..." : "开始备份"}
                </Button>
                <Button variant="outline" onclick={() => (backupResult = null)}>清空</Button>
              </div>
              {#if backupResult}
                <pre class="max-h-[240px] overflow-auto rounded-lg border bg-muted/20 p-4 text-xs">{backupResult}</pre>
              {/if}
            </div>
          </Card.Content>
        </Card.Root>
      </div>
    </Tabs.Content>
  </Tabs.Root>
</div>

<Dialog.Root bind:open={logDetailDialogOpen}>
  <Dialog.Content class="max-w-3xl">
    <Dialog.Header>
      <Dialog.Title>日志详情</Dialog.Title>
      {#if selectedAuditLog}
        <Dialog.Description>
          {selectedAuditLog.module} · {selectedAuditLog.action} · {selectedAuditLog.username}
        </Dialog.Description>
      {/if}
    </Dialog.Header>

    {#if loadingLogDetail}
      <div class="flex items-center justify-center p-10">
        <div class="h-8 w-8 animate-spin rounded-full border-4 border-primary border-t-transparent"></div>
      </div>
    {:else}
      <div class="grid gap-4">
        {#if selectedLogDetail}
          <div class="grid gap-2 rounded-lg border p-4">
            <div class="grid gap-2 md:grid-cols-2">
              <div>
                <p class="text-xs text-muted-foreground">请求URL</p>
                <p class="text-sm font-mono break-all">{selectedLogDetail.requestUrl || "-"}</p>
              </div>
              <div>
                <p class="text-xs text-muted-foreground">方法</p>
                <p class="text-sm font-mono">{selectedLogDetail.method || "-"}</p>
              </div>
              <div>
                <p class="text-xs text-muted-foreground">IP</p>
                <p class="text-sm font-mono">{selectedLogDetail.requestIp || "-"}</p>
              </div>
              <div>
                <p class="text-xs text-muted-foreground">耗时</p>
                <p class="text-sm font-mono">{selectedLogDetail.executionTime ?? 0}ms</p>
              </div>
            </div>
            <div>
              <p class="text-xs text-muted-foreground">请求参数</p>
              <pre class="mt-1 max-h-[220px] overflow-auto rounded-md bg-muted/20 p-3 text-xs">{selectedLogDetail.requestParam || "-"}</pre>
            </div>
            <div>
              <p class="text-xs text-muted-foreground">响应结果</p>
              <pre class="mt-1 max-h-[220px] overflow-auto rounded-md bg-muted/20 p-3 text-xs">{selectedLogDetail.responseResult || "-"}</pre>
            </div>
            {#if selectedLogDetail.errorMsg}
              <div>
                <p class="text-xs text-muted-foreground">错误信息</p>
                <pre class="mt-1 max-h-[140px] overflow-auto rounded-md bg-destructive/10 p-3 text-xs text-destructive">{selectedLogDetail.errorMsg}</pre>
              </div>
            {/if}
          </div>
        {:else}
          <div class="p-6 text-sm text-muted-foreground">无法获取详情</div>
        {/if}
      </div>
    {/if}
  </Dialog.Content>
</Dialog.Root>

<Dialog.Root bind:open={sensitiveDetailDialogOpen}>
  <Dialog.Content class="max-w-3xl">
    <Dialog.Header>
      <Dialog.Title>敏感操作详情</Dialog.Title>
      {#if selectedSensitiveRow}
        <Dialog.Description>
          {selectedSensitiveRow.module || "-"} · {selectedSensitiveRow.operationType || "-"} · {selectedSensitiveRow.username || selectedSensitiveRow.userId || "-"}
        </Dialog.Description>
      {/if}
    </Dialog.Header>

    {#if loadingSensitiveDetail}
      <div class="flex items-center justify-center p-10">
        <div class="h-8 w-8 animate-spin rounded-full border-4 border-primary border-t-transparent"></div>
      </div>
    {:else}
      {@const detail = sensitiveDetail || selectedSensitiveRow}
      {#if detail}
        <div class="grid gap-2 rounded-lg border p-4">
          <div class="grid gap-2 md:grid-cols-2">
            <div>
              <p class="text-xs text-muted-foreground">请求URL</p>
              <p class="text-sm font-mono break-all">{detail.requestUrl || "-"}</p>
            </div>
            <div>
              <p class="text-xs text-muted-foreground">方法</p>
              <p class="text-sm font-mono">{detail.method || "-"}</p>
            </div>
            <div>
              <p class="text-xs text-muted-foreground">IP</p>
              <p class="text-sm font-mono">{detail.requestIp || "-"}</p>
            </div>
            <div>
              <p class="text-xs text-muted-foreground">耗时</p>
              <p class="text-sm font-mono">{detail.executionTime ?? 0}ms</p>
            </div>
          </div>

          <div>
            <p class="text-xs text-muted-foreground">请求参数</p>
            <pre class="mt-1 max-h-[220px] overflow-auto rounded-md bg-muted/20 p-3 text-xs">{detail.requestParam || "-"}</pre>
          </div>
          <div>
            <p class="text-xs text-muted-foreground">响应结果</p>
            <pre class="mt-1 max-h-[220px] overflow-auto rounded-md bg-muted/20 p-3 text-xs">{detail.responseResult || "-"}</pre>
          </div>
          {#if detail.errorMsg}
            <div>
              <p class="text-xs text-muted-foreground">错误信息</p>
              <pre class="mt-1 max-h-[140px] overflow-auto rounded-md bg-destructive/10 p-3 text-xs text-destructive">{detail.errorMsg}</pre>
            </div>
          {/if}
        </div>
      {:else}
        <div class="p-6 text-sm text-muted-foreground">暂无详情</div>
      {/if}
    {/if}
  </Dialog.Content>
</Dialog.Root>
