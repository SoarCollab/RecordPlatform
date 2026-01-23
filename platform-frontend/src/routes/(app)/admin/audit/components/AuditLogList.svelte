<script lang="ts">
  import { formatDateTime } from "$utils/format";
  import { getAuditLogs, getAuditLog } from "$api/endpoints/system";
  import type { AuditLogVO, AuditLogQueryParams, SysOperationLog } from "$api/types";
  import { Button } from "$lib/components/ui/button";
  import { Input } from "$lib/components/ui/input";
  import { Badge } from "$lib/components/ui/badge";
  import * as Table from "$lib/components/ui/table";
  import * as Card from "$lib/components/ui/card";
  import DateTimePicker from "$lib/components/ui/date-picker/date-time-picker.svelte";
  import LogDetailDialog from "./dialogs/LogDetailDialog.svelte";

  let logs = $state<AuditLogVO[]>([]);
  let loading = $state(false);
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

  async function loadLogs() {
    loading = true;
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

  function getStatusVariant(status: number): "default" | "destructive" {
    return status === 0 ? "default" : "destructive";
  }

  function getStatusLabel(status: number): string {
    return status === 0 ? "成功" : "失败";
  }

  // Load on mount
  $effect(() => {
    loadLogs();
  });
</script>

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
    {#if loading}
      <div class="flex items-center justify-center p-10">
        <div class="h-8 w-8 animate-spin rounded-full border-4 border-primary border-t-transparent"></div>
      </div>
    {:else}
      <div class="overflow-auto rounded-lg border">
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
                <Table.Cell class="whitespace-nowrap">{log.createTime ? formatDateTime(log.createTime) : "-"}</Table.Cell>
                <Table.Cell>{log.username || "-"}</Table.Cell>
                <Table.Cell>{log.module || "-"}</Table.Cell>
                <Table.Cell>{log.action || "-"}</Table.Cell>
                <Table.Cell class="font-mono text-xs">{log.ip || "-"}</Table.Cell>
                <Table.Cell class="font-mono text-xs">{log.duration ?? 0}ms</Table.Cell>
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
      </div>

      {#if logs.length === 0}
        <div class="p-6 text-center text-sm text-muted-foreground">暂无日志</div>
      {/if}

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

<LogDetailDialog
  open={logDetailDialogOpen}
  log={selectedAuditLog}
  detail={selectedLogDetail}
  loading={loadingLogDetail}
  onOpenChange={(open) => (logDetailDialogOpen = open)}
/>
