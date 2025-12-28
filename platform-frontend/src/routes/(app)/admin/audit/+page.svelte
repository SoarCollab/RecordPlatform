<script lang="ts">
  import { onMount } from "svelte";
  import { useNotifications } from "$stores/notifications.svelte";
  import { useAuth } from "$stores/auth.svelte";
  import { goto } from "$app/navigation";
  import { formatDateTime } from "$utils/format";
  import { getAuditLogs, exportAuditLogs } from "$api/endpoints/system";
  import type { AuditLogVO, AuditLogQueryParams } from "$api/types";
  import { Button } from "$lib/components/ui/button";
  import { Input } from "$lib/components/ui/input";
  import { Badge } from "$lib/components/ui/badge";
  import * as Table from "$lib/components/ui/table";
  import * as Dialog from "$lib/components/ui/dialog";
  import DateTimePicker from "$lib/components/ui/date-picker/date-time-picker.svelte";

  const notifications = useNotifications();
  const auth = useAuth();

  let logs = $state<AuditLogVO[]>([]);
  let loading = $state(true);
  let page = $state(1);
  let pageSize = $state(20);
  let total = $state(0);

  // Filters
  let filterUsername = $state("");
  let filterModule = $state("");
  let filterAction = $state("");
  let filterStartTime = $state("");
  let filterEndTime = $state("");

  // Detail dialog
  let selectedLog = $state<AuditLogVO | null>(null);
  let detailDialogOpen = $state(false);

  // Export state
  let isExporting = $state(false);

  onMount(() => {
    if (!auth.isAdmin) {
      notifications.error("权限不足", "仅管理员可访问此页面");
      goto("/dashboard");
      return;
    }
    loadLogs();
  });

  async function loadLogs() {
    loading = true;
    try {
      const params: AuditLogQueryParams & { current: number; size: number } = {
        current: page,
        size: pageSize,
      };
      if (filterUsername) params.username = filterUsername;
      if (filterModule) params.module = filterModule;
      if (filterAction) params.action = filterAction;
      if (filterStartTime) params.startTime = filterStartTime;
      if (filterEndTime) params.endTime = filterEndTime;

      const result = await getAuditLogs(params);
      logs = result.records;
      total = result.total;
    } catch (err) {
      notifications.error(
        "加载失败",
        err instanceof Error ? err.message : "请稍后重试"
      );
    } finally {
      loading = false;
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

  function showDetail(log: AuditLogVO) {
    selectedLog = log;
    detailDialogOpen = true;
  }

  async function handleExport() {
    isExporting = true;
    try {
      const params: AuditLogQueryParams = {};
      if (filterUsername) params.username = filterUsername;
      if (filterModule) params.module = filterModule;
      if (filterAction) params.action = filterAction;
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
        err instanceof Error ? err.message : "请稍后重试"
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
</script>

<svelte:head>
  <title>审计日志 - 存证平台</title>
</svelte:head>

<div class="space-y-6">
  <div class="flex items-center justify-between">
    <div>
      <h1 class="text-2xl font-bold">审计日志</h1>
      <p class="text-muted-foreground">查看系统操作日志和安全审计记录</p>
    </div>
    <Button onclick={handleExport} disabled={isExporting} variant="outline">
      {#if isExporting}
        <div
          class="mr-2 h-4 w-4 animate-spin rounded-full border-2 border-current border-t-transparent"
        ></div>
      {:else}
        <svg
          class="mr-2 h-4 w-4"
          fill="none"
          stroke="currentColor"
          viewBox="0 0 24 24"
        >
          <path
            stroke-linecap="round"
            stroke-linejoin="round"
            stroke-width="2"
            d="M12 10v6m0 0l-3-3m3 3l3-3m2 8H7a2 2 0 01-2-2V5a2 2 0 012-2h5.586a1 1 0 01.707.293l5.414 5.414a1 1 0 01.293.707V19a2 2 0 01-2 2z"
          />
        </svg>
      {/if}
      导出日志
    </Button>
  </div>

  <!-- Filters -->
  <div
    class="rounded-xl border bg-card/50 px-4 py-4 backdrop-blur-sm transition-all hover:bg-card/80"
  >
    <div class="flex flex-col gap-4">
      <div class="flex items-center gap-2">
        <div
          class="flex h-8 w-8 items-center justify-center rounded-lg bg-primary/10 text-primary"
        >
          <svg
            class="h-4 w-4"
            fill="none"
            stroke="currentColor"
            viewBox="0 0 24 24"
          >
            <path
              stroke-linecap="round"
              stroke-linejoin="round"
              stroke-width="2"
              d="M3 4a1 1 0 011-1h16a1 1 0 011 1v2.586a1 1 0 01-.293.707l-6.414 6.414a1 1 0 00-.293.707V17l-4 4v-6.586a1 1 0 00-.293-.707L3.293 7.293A1 1 0 013 6.586V4z"
            />
          </svg>
        </div>
        <h3 class="font-medium">筛选条件</h3>
      </div>

      <div class="grid gap-4 md:grid-cols-2 lg:grid-cols-3 xl:grid-cols-6">
        <div class="relative">
          <div
            class="pointer-events-none absolute left-2.5 top-2.5 text-muted-foreground"
          >
            <svg
              class="h-4 w-4"
              fill="none"
              stroke="currentColor"
              viewBox="0 0 24 24"
            >
              <path
                stroke-linecap="round"
                stroke-linejoin="round"
                stroke-width="2"
                d="M16 7a4 4 0 11-8 0 4 4 0 018 0zM12 14a7 7 0 00-7 7h14a7 7 0 00-7-7z"
              />
            </svg>
          </div>
          <Input
            placeholder="用户名"
            bind:value={filterUsername}
            class="pl-9"
            onkeydown={(e) => e.key === "Enter" && handleSearch()}
          />
        </div>

        <div class="relative">
          <div
            class="pointer-events-none absolute left-2.5 top-2.5 text-muted-foreground"
          >
            <svg
              class="h-4 w-4"
              fill="none"
              stroke="currentColor"
              viewBox="0 0 24 24"
            >
              <path
                stroke-linecap="round"
                stroke-linejoin="round"
                stroke-width="2"
                d="M4 6a2 2 0 012-2h2a2 2 0 012 2v2a2 2 0 01-2 2H6a2 2 0 01-2-2V6zM14 6a2 2 0 012-2h2a2 2 0 012 2v2a2 2 0 01-2 2h-2a2 2 0 01-2-2V6zM4 16a2 2 0 012-2h2a2 2 0 012 2v2a2 2 0 01-2 2H6a2 2 0 01-2-2v-2zM14 16a2 2 0 012-2h2a2 2 0 012 2v2a2 2 0 01-2 2h-2a2 2 0 01-2-2v-2z"
              />
            </svg>
          </div>
          <Input
            placeholder="模块"
            bind:value={filterModule}
            class="pl-9"
            onkeydown={(e) => e.key === "Enter" && handleSearch()}
          />
        </div>

        <div class="relative">
          <div
            class="pointer-events-none absolute left-2.5 top-2.5 text-muted-foreground"
          >
            <svg
              class="h-4 w-4"
              fill="none"
              stroke="currentColor"
              viewBox="0 0 24 24"
            >
              <path
                stroke-linecap="round"
                stroke-linejoin="round"
                stroke-width="2"
                d="M13 10V3L4 14h7v7l9-11h-7z"
              />
            </svg>
          </div>
          <Input
            placeholder="操作"
            bind:value={filterAction}
            class="pl-9"
            onkeydown={(e) => e.key === "Enter" && handleSearch()}
          />
        </div>

        <DateTimePicker bind:value={filterStartTime} placeholder="开始时间" />

        <DateTimePicker bind:value={filterEndTime} placeholder="结束时间" />

        <div class="flex gap-2">
          <Button
            onclick={handleSearch}
            class="flex-1 bg-primary/90 hover:bg-primary"
          >
            <svg
              class="mr-2 h-4 w-4"
              fill="none"
              stroke="currentColor"
              viewBox="0 0 24 24"
            >
              <path
                stroke-linecap="round"
                stroke-linejoin="round"
                stroke-width="2"
                d="M21 21l-6-6m2-5a7 7 0 11-14 0 7 7 0 0114 0z"
              />
            </svg>
            搜索
          </Button>
          <Button
            variant="secondary"
            onclick={clearFilters}
            class="px-3"
            title="清除筛选"
          >
            <svg
              class="h-4 w-4"
              fill="none"
              stroke="currentColor"
              viewBox="0 0 24 24"
            >
              <path
                stroke-linecap="round"
                stroke-linejoin="round"
                stroke-width="2"
                d="M6 18L18 6M6 6l12 12"
              />
            </svg>
          </Button>
        </div>
      </div>
    </div>
  </div>

  <!-- Table -->
  <div
    class="overflow-hidden rounded-xl border bg-card text-card-foreground shadow-sm"
  >
    <div class="p-0">
      {#if loading}
        <div class="flex h-64 items-center justify-center">
          <div class="flex flex-col items-center gap-2">
            <div
              class="h-8 w-8 animate-spin rounded-full border-4 border-primary border-t-transparent"
            ></div>
            <p class="text-sm text-muted-foreground">加载中...</p>
          </div>
        </div>
      {:else if logs.length === 0}
        <div
          class="flex h-64 flex-col items-center justify-center p-8 text-center"
        >
          <div
            class="mb-4 flex h-16 w-16 items-center justify-center rounded-full bg-muted"
          >
            <svg
              class="h-8 w-8 text-muted-foreground"
              fill="none"
              stroke="currentColor"
              viewBox="0 0 24 24"
            >
              <path
                stroke-linecap="round"
                stroke-linejoin="round"
                stroke-width="2"
                d="M9 5H7a2 2 0 00-2 2v12a2 2 0 002 2h10a2 2 0 002-2V7a2 2 0 00-2-2h-2M9 5a2 2 0 002 2h2a2 2 0 002-2M9 5a2 2 0 012-2h2a2 2 0 012 2m-3 7h3m-3 4h3m-6-4h.01M9 16h.01"
              />
            </svg>
          </div>
          <h3 class="mb-1 text-lg font-medium">暂无审计日志</h3>
          <p class="text-sm text-muted-foreground">没有找到符合条件的记录</p>
        </div>
      {:else}
        <div class="relative w-full overflow-auto">
          <Table.Root>
            <Table.Header>
              <Table.Row class="bg-muted/50 hover:bg-muted/50">
                <Table.Head class="w-[180px]">时间</Table.Head>
                <Table.Head>用户</Table.Head>
                <Table.Head>模块</Table.Head>
                <Table.Head>操作</Table.Head>
                <Table.Head>IP</Table.Head>
                <Table.Head>耗时</Table.Head>
                <Table.Head>状态</Table.Head>
                <Table.Head class="w-[80px] text-right">操作</Table.Head>
              </Table.Row>
            </Table.Header>
            <Table.Body>
              {#each logs as log (log.id)}
                <Table.Row class="group transition-colors hover:bg-muted/50">
                  <Table.Cell class="font-mono text-sm text-muted-foreground"
                    >{formatDateTime(log.createTime)}</Table.Cell
                  >
                  <Table.Cell>
                    <div class="flex items-center gap-2">
                      <div
                        class="flex h-6 w-6 items-center justify-center rounded-full bg-primary/10 text-xs font-medium text-primary"
                      >
                        {log.username.slice(0, 1).toUpperCase()}
                      </div>
                      <span class="font-medium">{log.username}</span>
                    </div>
                  </Table.Cell>
                  <Table.Cell>
                    <span
                      class="inline-flex items-center rounded-md bg-secondary/50 px-2 py-1 text-xs font-medium text-secondary-foreground"
                    >
                      {log.module}
                    </span>
                  </Table.Cell>
                  <Table.Cell class="font-medium">{log.action}</Table.Cell>
                  <Table.Cell class="font-mono text-sm text-muted-foreground"
                    >{log.ip}</Table.Cell
                  >
                  <Table.Cell>
                    <span
                      class={log.duration > 1000
                        ? "text-orange-500 font-medium"
                        : "text-muted-foreground"}
                    >
                      {log.duration}ms
                    </span>
                  </Table.Cell>
                  <Table.Cell>
                    <Badge
                      variant={getStatusVariant(log.status)}
                      class="shadow-none"
                    >
                      {getStatusLabel(log.status)}
                    </Badge>
                  </Table.Cell>
                  <Table.Cell class="text-right">
                    <Button
                      variant="ghost"
                      size="icon"
                      class="h-8 w-8 text-muted-foreground opacity-0 transition-opacity group-hover:opacity-100"
                      onclick={() => showDetail(log)}
                      title="查看详情"
                    >
                      <svg
                        class="h-4 w-4"
                        fill="none"
                        stroke="currentColor"
                        viewBox="0 0 24 24"
                      >
                        <path
                          stroke-linecap="round"
                          stroke-linejoin="round"
                          stroke-width="2"
                          d="M15 12a3 3 0 11-6 0 3 3 0 016 0z"
                        />
                        <path
                          stroke-linecap="round"
                          stroke-linejoin="round"
                          stroke-width="2"
                          d="M2.458 12C3.732 7.943 7.523 5 12 5c4.478 0 8.268 2.943 9.542 7-1.274 4.057-5.064 7-9.542 7-4.477 0-8.268-2.943-9.542-7z"
                        />
                      </svg>
                    </Button>
                  </Table.Cell>
                </Table.Row>
              {/each}
            </Table.Body>
          </Table.Root>
        </div>
      {/if}
    </div>
  </div>

  <!-- Pagination -->
  {#if total > pageSize}
    <div class="flex items-center justify-between border-t pt-4">
      <p class="text-sm text-muted-foreground">
        显示 {(page - 1) * pageSize + 1} 到 {Math.min(page * pageSize, total)} 条，共
        {total} 条
      </p>
      <div class="flex items-center gap-2">
        <Button
          variant="outline"
          size="sm"
          disabled={page <= 1}
          onclick={() => {
            page--;
            loadLogs();
          }}
          class="w-24 justify-between"
        >
          <svg
            class="h-4 w-4"
            fill="none"
            stroke="currentColor"
            viewBox="0 0 24 24"
          >
            <path
              stroke-linecap="round"
              stroke-linejoin="round"
              stroke-width="2"
              d="M15 19l-7-7 7-7"
            />
          </svg>
          上一页
        </Button>
        <div
          class="flex items-center justify-center rounded-md border bg-background px-3 py-1.5 text-sm"
        >
          第 {page} 页 / {Math.ceil(total / pageSize)} 页
        </div>
        <Button
          variant="outline"
          size="sm"
          disabled={page >= Math.ceil(total / pageSize)}
          onclick={() => {
            page++;
            loadLogs();
          }}
          class="w-24 justify-between"
        >
          下一页
          <svg
            class="h-4 w-4"
            fill="none"
            stroke="currentColor"
            viewBox="0 0 24 24"
          >
            <path
              stroke-linecap="round"
              stroke-linejoin="round"
              stroke-width="2"
              d="M9 5l7 7-7 7"
            />
          </svg>
        </Button>
      </div>
    </div>
  {/if}
</div>

<!-- Detail Dialog -->
<Dialog.Root bind:open={detailDialogOpen}>
  <Dialog.Content class="sm:max-w-lg">
    <Dialog.Header>
      <Dialog.Title>日志详情</Dialog.Title>
    </Dialog.Header>
    {#if selectedLog}
      <div class="space-y-4">
        <div class="grid grid-cols-2 gap-4">
          <div>
            <p class="text-sm font-medium text-muted-foreground">用户</p>
            <p class="mt-1">{selectedLog.username}</p>
          </div>
          <div>
            <p class="text-sm font-medium text-muted-foreground">时间</p>
            <p class="mt-1">{formatDateTime(selectedLog.createTime)}</p>
          </div>
          <div>
            <p class="text-sm font-medium text-muted-foreground">模块</p>
            <p class="mt-1">{selectedLog.module}</p>
          </div>
          <div>
            <p class="text-sm font-medium text-muted-foreground">操作</p>
            <p class="mt-1">{selectedLog.action}</p>
          </div>
          <div>
            <p class="text-sm font-medium text-muted-foreground">IP 地址</p>
            <p class="mt-1 font-mono">{selectedLog.ip}</p>
          </div>
          <div>
            <p class="text-sm font-medium text-muted-foreground">耗时</p>
            <p class="mt-1">{selectedLog.duration}ms</p>
          </div>
          <div>
            <p class="text-sm font-medium text-muted-foreground">状态</p>
            <p class="mt-1">
              <Badge variant={getStatusVariant(selectedLog.status)}>
                {getStatusLabel(selectedLog.status)}
              </Badge>
            </p>
          </div>
          {#if selectedLog.targetType}
            <div>
              <p class="text-sm font-medium text-muted-foreground">目标类型</p>
              <p class="mt-1">{selectedLog.targetType}</p>
            </div>
          {/if}
        </div>
        {#if selectedLog.targetId}
          <div>
            <p class="text-sm font-medium text-muted-foreground">目标 ID</p>
            <p class="mt-1 break-all font-mono text-sm">
              {selectedLog.targetId}
            </p>
          </div>
        {/if}
        {#if selectedLog.detail}
          <div>
            <p class="text-sm font-medium text-muted-foreground">详情</p>
            <pre
              class="mt-1 max-h-40 overflow-auto rounded bg-muted p-2 text-sm">{selectedLog.detail}</pre>
          </div>
        {/if}
        {#if selectedLog.errorMessage}
          <div>
            <p class="text-sm font-medium text-muted-foreground">错误信息</p>
            <p class="mt-1 text-destructive">{selectedLog.errorMessage}</p>
          </div>
        {/if}
        {#if selectedLog.userAgent}
          <div>
            <p class="text-sm font-medium text-muted-foreground">User-Agent</p>
            <p class="mt-1 break-all text-sm text-muted-foreground">
              {selectedLog.userAgent}
            </p>
          </div>
        {/if}
      </div>
    {/if}
    <Dialog.Footer>
      <Button onclick={() => (detailDialogOpen = false)}>关闭</Button>
    </Dialog.Footer>
  </Dialog.Content>
</Dialog.Root>
