<script lang="ts">
  import { formatDateTime } from "$utils/format";
  import { getAuditLogs, getAuditLog } from "$api/endpoints/system";
  import type {
    AuditLogVO,
    AuditLogQueryParams,
    SysOperationLog,
  } from "$api/types";
  import { Button } from "$lib/components/ui/button";
  import { Input } from "$lib/components/ui/input";
  import { Badge } from "$lib/components/ui/badge";
  import * as Table from "$lib/components/ui/table";
  import * as Card from "$lib/components/ui/card";
  import DateTimePicker from "$lib/components/ui/date-picker/date-time-picker.svelte";
  import LogDetailDialog from "./dialogs/LogDetailDialog.svelte";
  import { useNotifications } from "$stores/notifications.svelte";
  import {
    AUDIT_MODULES,
    AUDIT_OPERATION_TYPES,
    isSensitiveOperation,
    getOperationTypesForModule,
  } from "../constants";

  const notifications = useNotifications();

  /** 外部传入的筛选条件（从仪表盘钻取） */
  interface Props {
    externalFilters?: {
      username?: string;
      module?: string;
      operationType?: string;
      status?: string;
      startTime?: string;
      endTime?: string;
      onlySensitive?: boolean;
      [key: string]: unknown;
    } | null;
  }

  let { externalFilters = null }: Props = $props();

  let logs = $state<AuditLogVO[]>([]);
  let loading = $state(false);
  let page = $state(1);
  let pageSize = $state(20);
  let total = $state(0);

  let filterUsername = $state("");
  let filterModule = $state("");
  let filterOperationType = $state("");
  let filterStatus = $state("");
  let filterStartTime = $state("");
  let filterEndTime = $state("");
  let onlySensitive = $state(false);

  let logDetailDialogOpen = $state(false);
  let selectedAuditLog = $state<AuditLogVO | null>(null);
  let selectedLogDetail = $state<SysOperationLog | null>(null);
  let loadingLogDetail = $state(false);

  /** 当前模块对应的可用操作类型 */
  const availableOperationTypes = $derived(
    filterModule
      ? getOperationTypesForModule(filterModule)
      : [...AUDIT_OPERATION_TYPES],
  );

  /** 应用外部筛选并加载 */
  function applyExternalFilters() {
    if (!externalFilters) return;
    filterUsername = externalFilters.username ?? "";
    filterModule = externalFilters.module ?? "";
    filterOperationType = externalFilters.operationType ?? "";
    filterStatus = externalFilters.status ?? "";
    filterStartTime = externalFilters.startTime ?? "";
    filterEndTime = externalFilters.endTime ?? "";
    onlySensitive = externalFilters.onlySensitive ?? false;
    page = 1;
    loadLogs();
  }

  /** 监听外部 filter 变化（通过 _counter 触发） */
  let lastCounter = -1;
  $effect(() => {
    const counter = externalFilters?._counter;
    if (typeof counter === "number" && counter !== lastCounter) {
      lastCounter = counter;
      applyExternalFilters();
    }
  });

  /** 初始化：无外部 filter 时加载全部 */
  let mounted = false;
  $effect(() => {
    if (!mounted) {
      mounted = true;
      if (!externalFilters?._counter) loadLogs();
    }
  });

  async function loadLogs() {
    loading = true;
    try {
      const params: AuditLogQueryParams & {
        pageNum: number;
        pageSize: number;
      } = {
        pageNum: page,
        pageSize,
      };
      if (filterUsername) params.username = filterUsername;
      if (filterModule) params.module = filterModule;
      if (filterOperationType) params.operationType = filterOperationType;
      if (filterStatus !== "") params.status = Number(filterStatus);
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
    filterOperationType = "";
    filterStatus = "";
    filterStartTime = "";
    filterEndTime = "";
    onlySensitive = false;
    page = 1;
    loadLogs();
  }

  function toggleSensitiveOnly() {
    onlySensitive = !onlySensitive;
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

  /** 切换模块时清空操作类型（避免选中无效值） */
  function handleModuleChange(e: Event) {
    const target = e.target as HTMLSelectElement;
    filterModule = target.value;
    // 如果当前操作类型不在新模块可用列表中，清空
    if (filterModule) {
      const available = getOperationTypesForModule(filterModule);
      if (!available.some((t) => t.value === filterOperationType)) {
        filterOperationType = "";
      }
    }
  }

  /** 显示的日志列表（考虑 onlySensitive 客户端过滤） */
  const displayLogs = $derived(
    onlySensitive
      ? logs.filter((log) => isSensitiveOperation(log.action))
      : logs,
  );

  const totalPages = $derived(Math.max(1, Math.ceil(total / pageSize)));
</script>

<Card.Root>
  <Card.Header class="pb-4">
    <div class="grid gap-3 md:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4">
      <!-- 用户名 -->
      <Input
        placeholder="用户名"
        bind:value={filterUsername}
        onkeydown={(e) => e.key === "Enter" && handleSearch()}
      />

      <!-- 模块 - 下拉框 -->
      <select
        value={filterModule}
        onchange={handleModuleChange}
        class="bg-background focus:border-primary h-9 w-full rounded-md border px-3 text-sm focus:outline-none"
      >
        <option value="">全部模块</option>
        {#each AUDIT_MODULES as mod}
          <option value={mod.value}>{mod.label}</option>
        {/each}
      </select>

      <!-- 操作类型 - 下拉框（随模块联动） -->
      <select
        bind:value={filterOperationType}
        class="bg-background focus:border-primary h-9 w-full rounded-md border px-3 text-sm focus:outline-none"
      >
        <option value="">全部操作类型</option>
        {#each availableOperationTypes as op}
          <option value={op.value}>{op.label}</option>
        {/each}
      </select>

      <!-- 状态 - 下拉框 -->
      <select
        bind:value={filterStatus}
        class="bg-background focus:border-primary h-9 w-full rounded-md border px-3 text-sm focus:outline-none"
      >
        <option value="">全部状态</option>
        <option value="0">成功</option>
        <option value="1">失败</option>
      </select>

      <!-- 时间 -->
      <DateTimePicker bind:value={filterStartTime} placeholder="开始时间" />
      <DateTimePicker bind:value={filterEndTime} placeholder="结束时间" />

      <!-- 操作按钮 -->
      <div class="flex items-center gap-2">
        <Button onclick={handleSearch} class="flex-1">搜索</Button>
        <Button variant="secondary" onclick={clearFilters}>重置</Button>
        <Button
          variant={onlySensitive ? "destructive" : "outline"}
          onclick={toggleSensitiveOnly}
          title="仅显示敏感操作（删除、授权、撤销、备份、上报）"
          class="shrink-0"
        >
          <svg
            class="mr-1 h-3.5 w-3.5"
            fill="none"
            stroke="currentColor"
            viewBox="0 0 24 24"
          >
            <path
              stroke-linecap="round"
              stroke-linejoin="round"
              stroke-width="2"
              d="M12 9v2m0 4h.01m-6.938 4h13.856c1.54 0 2.502-1.667 1.732-3L13.732 4c-.77-1.333-2.694-1.333-3.464 0L3.34 16c-.77 1.333.192 3 1.732 3z"
            />
          </svg>
          敏感
        </Button>
      </div>
    </div>
  </Card.Header>
  <Card.Content>
    {#if loading}
      <div class="flex items-center justify-center p-10">
        <div
          class="border-primary h-8 w-8 animate-spin rounded-full border-4 border-t-transparent"
        ></div>
      </div>
    {:else}
      <div class="overflow-auto rounded-lg border">
        <Table.Root>
          <Table.Header>
            <Table.Row>
              <Table.Head>时间</Table.Head>
              <Table.Head>用户</Table.Head>
              <Table.Head>模块</Table.Head>
              <Table.Head>操作类型</Table.Head>
              <Table.Head>IP</Table.Head>
              <Table.Head>耗时</Table.Head>
              <Table.Head>状态</Table.Head>
              <Table.Head class="text-right">操作</Table.Head>
            </Table.Row>
          </Table.Header>
          <Table.Body>
            {#each displayLogs as log (log.id)}
              <Table.Row
                class="hover:bg-muted/40 cursor-pointer"
                onclick={() => openLogDetail(log)}
              >
                <Table.Cell class="whitespace-nowrap"
                  >{log.createTime
                    ? formatDateTime(log.createTime)
                    : "-"}</Table.Cell
                >
                <Table.Cell>{log.username || "-"}</Table.Cell>
                <Table.Cell>{log.module || "-"}</Table.Cell>
                <Table.Cell>
                  <span class="inline-flex items-center gap-1.5">
                    {log.action || "-"}
                    {#if isSensitiveOperation(log.action)}
                      <Badge
                        variant="outline"
                        class="border-destructive/50 text-destructive px-1 py-0 text-[10px]"
                      >
                        敏感
                      </Badge>
                    {/if}
                  </span>
                </Table.Cell>
                <Table.Cell class="font-mono text-xs"
                  >{log.ip || "-"}</Table.Cell
                >
                <Table.Cell class="font-mono text-xs"
                  >{log.duration ?? 0}ms</Table.Cell
                >
                <Table.Cell>
                  <Badge variant={getStatusVariant(log.status)}>
                    {getStatusLabel(log.status)}
                  </Badge>
                </Table.Cell>
                <Table.Cell class="text-right">
                  <Button
                    size="sm"
                    variant="outline"
                    onclick={(e) => {
                      e.stopPropagation();
                      openLogDetail(log);
                    }}
                  >
                    详情
                  </Button>
                </Table.Cell>
              </Table.Row>
            {/each}
          </Table.Body>
        </Table.Root>
      </div>

      {#if displayLogs.length === 0}
        <div class="text-muted-foreground p-6 text-center text-sm">
          {onlySensitive ? "暂无敏感操作日志" : "暂无日志"}
        </div>
      {/if}

      {#if total > pageSize}
        <div class="mt-4 flex items-center justify-between">
          <p class="text-muted-foreground text-sm">
            共 {total} 条，第 {page} / {totalPages} 页
            {#if onlySensitive}
              <span class="text-destructive ml-2">（仅敏感操作）</span>
            {/if}
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
              disabled={page >= totalPages}
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
