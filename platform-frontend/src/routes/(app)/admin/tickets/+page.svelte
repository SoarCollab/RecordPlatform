<script lang="ts">
  import { onMount } from "svelte";
  import { goto } from "$app/navigation";
  import { useAuth } from "$stores/auth.svelte";
  import { useNotifications } from "$stores/notifications.svelte";
  import { formatDateTime } from "$utils/format";
  import {
    getAdminTickets,
    assignTicket,
    updateTicketStatus,
    getAdminPendingCount,
  } from "$api/endpoints/tickets";
  import {
    type TicketVO,
    TicketStatus,
    TicketStatusLabel,
    TicketPriority,
    TicketPriorityLabel,
    TicketCategory,
    TicketCategoryLabel,
  } from "$api/types";
  import * as Card from "$lib/components/ui/card";
  import { Button } from "$lib/components/ui/button";
  import { Badge } from "$lib/components/ui/badge";
  import { Input } from "$lib/components/ui/input";
  import * as Dialog from "$lib/components/ui/dialog";
  import * as Table from "$lib/components/ui/table";
  import * as DropdownMenu from "$lib/components/ui/dropdown-menu";

  const auth = useAuth();
  const notifications = useNotifications();

  // State
  let tickets = $state<TicketVO[]>([]);
  let loading = $state(true);
  let pageNum = $state(1);
  let totalPages = $state(1);
  let total = $state(0);
  let pendingCount = $state(0);

  // Filters
  let keyword = $state("");
  let statusFilter = $state<TicketStatus | "">("");
  let priorityFilter = $state<TicketPriority | "">("");
  let categoryFilter = $state<TicketCategory | "">("");

  // Assign dialog
  let assignDialogOpen = $state(false);
  let assignTarget = $state<TicketVO | null>(null);
  let assigneeId = $state("");
  let assigning = $state(false);

  // Status update dialog
  let statusDialogOpen = $state(false);
  let statusTarget = $state<TicketVO | null>(null);
  let newStatus = $state<TicketStatus>(TicketStatus.PENDING);
  let updatingStatus = $state(false);

  onMount(() => {
    if (!auth.isAdmin) {
      notifications.error("权限不足", "仅管理员可访问此页面");
      goto("/dashboard");
      return;
    }
    loadTickets();
    loadPendingCount();
  });

  async function loadTickets() {
    loading = true;
    try {
      const result = await getAdminTickets({
        pageNum,
        pageSize: 20,
        keyword: keyword || undefined,
        status: statusFilter !== "" ? statusFilter : undefined,
        priority: priorityFilter !== "" ? priorityFilter : undefined,
        category: categoryFilter !== "" ? categoryFilter : undefined,
      });
      tickets = result.records;
      totalPages = Math.ceil(result.total / 20);
      total = result.total;
    } catch (err) {
      notifications.error("加载失败", err instanceof Error ? err.message : "请稍后重试");
    } finally {
      loading = false;
    }
  }

  async function loadPendingCount() {
    try {
      const result = await getAdminPendingCount();
      pendingCount = result.count;
    } catch {
      // Ignore
    }
  }

  function handleSearch() {
    pageNum = 1;
    loadTickets();
  }

  function clearFilters() {
    keyword = "";
    statusFilter = "";
    priorityFilter = "";
    categoryFilter = "";
    pageNum = 1;
    loadTickets();
  }

  function openAssignDialog(ticket: TicketVO) {
    assignTarget = ticket;
    assigneeId = ticket.assigneeId || "";
    assignDialogOpen = true;
  }

  async function handleAssign() {
    if (!assignTarget || !assigneeId.trim()) {
      notifications.warning("请输入处理人ID");
      return;
    }

    assigning = true;
    try {
      await assignTicket(assignTarget.id, assigneeId.trim());
      notifications.success("分配成功");
      assignDialogOpen = false;
      loadTickets();
    } catch (err) {
      notifications.error("分配失败", err instanceof Error ? err.message : "请稍后重试");
    } finally {
      assigning = false;
    }
  }

  function openStatusDialog(ticket: TicketVO) {
    statusTarget = ticket;
    newStatus = ticket.status;
    statusDialogOpen = true;
  }

  async function handleStatusUpdate() {
    if (!statusTarget) return;

    updatingStatus = true;
    try {
      await updateTicketStatus(statusTarget.id, newStatus);
      notifications.success("状态更新成功");
      statusDialogOpen = false;
      loadTickets();
      loadPendingCount();
    } catch (err) {
      notifications.error("更新失败", err instanceof Error ? err.message : "请稍后重试");
    } finally {
      updatingStatus = false;
    }
  }

  function getStatusBadgeVariant(status: TicketStatus): "default" | "secondary" | "destructive" | "outline" {
    switch (status) {
      case TicketStatus.PENDING:
        return "destructive";
      case TicketStatus.PROCESSING:
        return "default";
      case TicketStatus.CONFIRMING:
        return "outline";
      case TicketStatus.COMPLETED:
        return "secondary";
      case TicketStatus.CLOSED:
        return "secondary";
      default:
        return "outline";
    }
  }

  function getPriorityBadgeVariant(priority: TicketPriority): "default" | "secondary" | "destructive" | "outline" {
    switch (priority) {
      case TicketPriority.HIGH:
        return "destructive";
      case TicketPriority.MEDIUM:
        return "default";
      case TicketPriority.LOW:
        return "secondary";
      default:
        return "outline";
    }
  }

  function viewTicketDetail(ticket: TicketVO) {
    goto(`/tickets/${ticket.id}`);
  }
</script>

<svelte:head>
  <title>工单管理 - 管理后台</title>
</svelte:head>

<div class="mx-auto max-w-7xl space-y-6">
  <div class="flex items-center justify-between">
    <div>
      <h1 class="text-2xl font-bold">工单管理</h1>
      <p class="text-muted-foreground">管理所有用户的工单</p>
    </div>
    <div class="flex items-center gap-4">
      <div class="text-sm text-muted-foreground">
        待处理工单: <span class="font-bold text-destructive">{pendingCount}</span>
      </div>
    </div>
  </div>

  <Card.Root>
    <Card.Header class="pb-4">
      <div class="flex items-center justify-between">
        <Card.Title>所有工单 ({total})</Card.Title>
      </div>

      <!-- Filter panel -->
      <div class="mt-4 rounded-lg border bg-muted/30 p-4">
        <div class="grid gap-4 md:grid-cols-2 lg:grid-cols-4">
          <Input
            placeholder="搜索标题或工单号..."
            bind:value={keyword}
            onkeydown={(e) => e.key === "Enter" && handleSearch()}
          />

          <select
            bind:value={statusFilter}
            class="h-9 w-full rounded-md border bg-background px-3 text-sm focus:border-primary focus:outline-none"
          >
            <option value="">全部状态</option>
            <option value={TicketStatus.PENDING}>待处理</option>
            <option value={TicketStatus.PROCESSING}>处理中</option>
            <option value={TicketStatus.CONFIRMING}>待确认</option>
            <option value={TicketStatus.COMPLETED}>已完成</option>
            <option value={TicketStatus.CLOSED}>已关闭</option>
          </select>

          <select
            bind:value={priorityFilter}
            class="h-9 w-full rounded-md border bg-background px-3 text-sm focus:border-primary focus:outline-none"
          >
            <option value="">全部优先级</option>
            <option value={TicketPriority.HIGH}>高</option>
            <option value={TicketPriority.MEDIUM}>中</option>
            <option value={TicketPriority.LOW}>低</option>
          </select>

          <select
            bind:value={categoryFilter}
            class="h-9 w-full rounded-md border bg-background px-3 text-sm focus:border-primary focus:outline-none"
          >
            <option value="">全部类别</option>
            <option value={TicketCategory.BUG}>Bug</option>
            <option value={TicketCategory.FEATURE}>功能请求</option>
            <option value={TicketCategory.QUESTION}>问题咨询</option>
            <option value={TicketCategory.FEEDBACK}>反馈建议</option>
            <option value={TicketCategory.OTHER}>其他</option>
          </select>
        </div>
        <div class="mt-4 flex gap-2">
          <Button onclick={handleSearch}>搜索</Button>
          <Button variant="secondary" onclick={clearFilters}>重置</Button>
        </div>
      </div>
    </Card.Header>

    <Card.Content>
      {#if loading}
        <div class="flex items-center justify-center p-12">
          <div class="h-8 w-8 animate-spin rounded-full border-4 border-primary border-t-transparent"></div>
        </div>
      {:else if tickets.length === 0}
        <div class="p-12 text-center text-muted-foreground">暂无工单</div>
      {:else}
        <Table.Root>
          <Table.Header>
            <Table.Row>
              <Table.Head>工单号</Table.Head>
              <Table.Head>标题</Table.Head>
              <Table.Head>创建者</Table.Head>
              <Table.Head>处理人</Table.Head>
              <Table.Head>优先级</Table.Head>
              <Table.Head>状态</Table.Head>
              <Table.Head>回复数</Table.Head>
              <Table.Head>创建时间</Table.Head>
              <Table.Head class="text-right">操作</Table.Head>
            </Table.Row>
          </Table.Header>
          <Table.Body>
            {#each tickets as ticket}
              <Table.Row class="cursor-pointer hover:bg-muted/50" onclick={() => viewTicketDetail(ticket)}>
                <Table.Cell class="font-mono text-sm">{ticket.ticketNo}</Table.Cell>
                <Table.Cell class="max-w-[200px] truncate font-medium">
                  {ticket.title}
                </Table.Cell>
                <Table.Cell>{ticket.creatorUsername}</Table.Cell>
                <Table.Cell>
                  {#if ticket.assigneeUsername}
                    {ticket.assigneeUsername}
                  {:else}
                    <span class="text-muted-foreground">未分配</span>
                  {/if}
                </Table.Cell>
                <Table.Cell>
                  <Badge variant={getPriorityBadgeVariant(ticket.priority)}>
                    {TicketPriorityLabel[ticket.priority]}
                  </Badge>
                </Table.Cell>
                <Table.Cell>
                  <Badge variant={getStatusBadgeVariant(ticket.status)}>
                    {TicketStatusLabel[ticket.status]}
                  </Badge>
                </Table.Cell>
                <Table.Cell>{ticket.replyCount}</Table.Cell>
                <Table.Cell class="text-sm text-muted-foreground">
                  {formatDateTime(ticket.createTime)}
                </Table.Cell>
                <Table.Cell class="text-right" onclick={(e) => e.stopPropagation()}>
                  <DropdownMenu.Root>
                    <DropdownMenu.Trigger>
                      <Button variant="ghost" size="sm">操作</Button>
                    </DropdownMenu.Trigger>
                    <DropdownMenu.Content>
                      <DropdownMenu.Item onclick={() => viewTicketDetail(ticket)}>
                        查看详情
                      </DropdownMenu.Item>
                      <DropdownMenu.Item onclick={() => openAssignDialog(ticket)}>
                        分配处理人
                      </DropdownMenu.Item>
                      <DropdownMenu.Item onclick={() => openStatusDialog(ticket)}>
                        修改状态
                      </DropdownMenu.Item>
                    </DropdownMenu.Content>
                  </DropdownMenu.Root>
                </Table.Cell>
              </Table.Row>
            {/each}
          </Table.Body>
        </Table.Root>

        <!-- Pagination -->
        <div class="mt-4 flex items-center justify-between">
          <span class="text-sm text-muted-foreground">
            第 {pageNum} / {totalPages} 页，共 {total} 条
          </span>
          <div class="flex gap-2">
            <Button
              variant="outline"
              size="sm"
              disabled={pageNum <= 1}
              onclick={() => {
                pageNum--;
                loadTickets();
              }}
            >
              上一页
            </Button>
            <Button
              variant="outline"
              size="sm"
              disabled={pageNum >= totalPages}
              onclick={() => {
                pageNum++;
                loadTickets();
              }}
            >
              下一页
            </Button>
          </div>
        </div>
      {/if}
    </Card.Content>
  </Card.Root>
</div>

<!-- Assign dialog -->
<Dialog.Root bind:open={assignDialogOpen}>
  <Dialog.Content class="sm:max-w-md">
    <Dialog.Header>
      <Dialog.Title>分配处理人</Dialog.Title>
      <Dialog.Description>
        为工单 "{assignTarget?.ticketNo}" 分配处理人
      </Dialog.Description>
    </Dialog.Header>

    <div class="space-y-4">
      <div>
        <label for="assignee-id" class="mb-2 block text-sm font-medium">处理人ID</label>
        <Input
          id="assignee-id"
          bind:value={assigneeId}
          placeholder="请输入处理人的用户ID"
        />
        <p class="mt-1 text-xs text-muted-foreground">输入要分配的管理员用户ID</p>
      </div>
    </div>

    <Dialog.Footer>
      <Button variant="outline" onclick={() => (assignDialogOpen = false)}>取消</Button>
      <Button onclick={handleAssign} disabled={assigning}>
        {assigning ? "分配中..." : "确认分配"}
      </Button>
    </Dialog.Footer>
  </Dialog.Content>
</Dialog.Root>

<!-- Status update dialog -->
<Dialog.Root bind:open={statusDialogOpen}>
  <Dialog.Content class="sm:max-w-md">
    <Dialog.Header>
      <Dialog.Title>修改工单状态</Dialog.Title>
      <Dialog.Description>
        修改工单 "{statusTarget?.ticketNo}" 的状态
      </Dialog.Description>
    </Dialog.Header>

    <div class="space-y-4">
      <div>
        <label for="new-status" class="mb-2 block text-sm font-medium">新状态</label>
        <select
          id="new-status"
          bind:value={newStatus}
          class="h-9 w-full rounded-md border bg-background px-3 text-sm focus:border-primary focus:outline-none"
        >
          <option value={TicketStatus.PENDING}>待处理</option>
          <option value={TicketStatus.PROCESSING}>处理中</option>
          <option value={TicketStatus.CONFIRMING}>待确认</option>
          <option value={TicketStatus.COMPLETED}>已完成</option>
          <option value={TicketStatus.CLOSED}>已关闭</option>
        </select>
      </div>

      {#if statusTarget}
        <div class="rounded-lg bg-muted/50 p-3 text-sm">
          <p>
            当前状态: <Badge variant={getStatusBadgeVariant(statusTarget.status)}>
              {TicketStatusLabel[statusTarget.status]}
            </Badge>
          </p>
        </div>
      {/if}
    </div>

    <Dialog.Footer>
      <Button variant="outline" onclick={() => (statusDialogOpen = false)}>取消</Button>
      <Button onclick={handleStatusUpdate} disabled={updatingStatus}>
        {updatingStatus ? "更新中..." : "确认修改"}
      </Button>
    </Dialog.Footer>
  </Dialog.Content>
</Dialog.Root>
