<script lang="ts">
  import { formatDateTime } from "$utils/format";
  import { getSensitiveOperations, getAuditLog } from "$api/endpoints/system";
  import type { SysOperationLog, AuditLogQueryVO } from "$api/types";
  import { Button } from "$lib/components/ui/button";
  import { Input } from "$lib/components/ui/input";
  import { Badge } from "$lib/components/ui/badge";
  import * as Table from "$lib/components/ui/table";
  import * as Card from "$lib/components/ui/card";
  import DateTimePicker from "$lib/components/ui/date-picker/date-time-picker.svelte";
  import LogDetailDialog from "./dialogs/LogDetailDialog.svelte";

  let sensitive = $state<SysOperationLog[]>([]);
  let loading = $state(false);
  let page = $state(1);
  let pageSize = $state(20);
  let total = $state(0);

  let filterUsername = $state("");
  let filterModule = $state("");
  let filterOperationType = $state("");
  let filterStatus = $state<string>("");
  let filterStartTime = $state("");
  let filterEndTime = $state("");

  let detailDialogOpen = $state(false);
  let selectedRow = $state<SysOperationLog | null>(null);
  let detail = $state<SysOperationLog | null>(null);
  let loadingDetail = $state(false);

  async function loadSensitive() {
    loading = true;
    try {
      const query: AuditLogQueryVO = {
        pageNum: page,
        pageSize,
      };
      if (filterUsername) query.username = filterUsername;
      if (filterModule) query.module = filterModule;
      if (filterOperationType) query.operationType = filterOperationType;
      if (filterStartTime) query.startTime = filterStartTime;
      if (filterEndTime) query.endTime = filterEndTime;
      if (filterStatus !== "") query.status = Number(filterStatus);

      const result = await getSensitiveOperations(query);
      sensitive = result.records;
      total = result.total;
    } finally {
      loading = false;
    }
  }

  function handleSearch() {
    page = 1;
    loadSensitive();
  }

  function resetFilters() {
    filterUsername = "";
    filterModule = "";
    filterOperationType = "";
    filterStartTime = "";
    filterEndTime = "";
    filterStatus = "";
    page = 1;
    loadSensitive();
  }

  async function openDetail(row: SysOperationLog) {
    selectedRow = row;
    detail = row;
    detailDialogOpen = true;

    const id = row.id;
    if (id === null || id === undefined || id === "") return;

    loadingDetail = true;
    try {
      detail = await getAuditLog(String(id));
    } catch {
      detail = row;
    } finally {
      loadingDetail = false;
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
    loadSensitive();
  });
</script>

<Card.Root>
  <Card.Header class="pb-4">
    <div class="flex items-center justify-between">
      <Card.Title>敏感操作</Card.Title>
      <Button variant="outline" onclick={loadSensitive} disabled={loading}>刷新</Button>
    </div>
    <div class="mt-4 grid gap-4 md:grid-cols-2 lg:grid-cols-3 xl:grid-cols-6">
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
        bind:value={filterOperationType}
        onkeydown={(e) => e.key === "Enter" && handleSearch()}
      />
      <select
        bind:value={filterStatus}
        class="h-9 w-full rounded-md border bg-background px-3 text-sm focus:border-primary focus:outline-none"
      >
        <option value="">全部状态</option>
        <option value="0">成功</option>
        <option value="1">失败</option>
      </select>
      <DateTimePicker bind:value={filterStartTime} placeholder="开始时间" />
      <DateTimePicker bind:value={filterEndTime} placeholder="结束时间" />
      <div class="flex gap-2 xl:col-span-6">
        <Button onclick={handleSearch}>搜索</Button>
        <Button variant="secondary" onclick={resetFilters}>重置</Button>
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
                onclick={() => openDetail(row)}
                role="button"
                tabindex={0}
                onkeypress={(e) => e.key === "Enter" && openDetail(row)}
              >
                <Table.Cell class="whitespace-nowrap">{row.operationTime ? formatDateTime(String(row.operationTime)) : "-"}</Table.Cell>
                <Table.Cell>{row.username || row.userId || "-"}</Table.Cell>
                <Table.Cell>{row.module || "-"}</Table.Cell>
                <Table.Cell>{row.operationType || "-"}</Table.Cell>
                <Table.Cell class="max-w-[260px] truncate font-mono text-xs">{row.requestUrl || "-"}</Table.Cell>
                <Table.Cell class="font-mono text-xs">{row.requestIp || "-"}</Table.Cell>
                <Table.Cell>
                  <Badge variant={getStatusVariant(row.status)}>
                    {getStatusLabel(row.status)}
                  </Badge>
                </Table.Cell>
              </Table.Row>
            {/each}
          </Table.Body>
        </Table.Root>
      </div>

      {#if sensitive.length === 0}
        <div class="p-6 text-center text-sm text-muted-foreground">暂无数据</div>
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
                loadSensitive();
              }}
            >
              上一页
            </Button>
            <Button
              variant="outline"
              disabled={page >= Math.ceil(total / pageSize)}
              onclick={() => {
                page = page + 1;
                loadSensitive();
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
  open={detailDialogOpen}
  log={null}
  detail={detail}
  loading={loadingDetail}
  onOpenChange={(open) => (detailDialogOpen = open)}
/>
