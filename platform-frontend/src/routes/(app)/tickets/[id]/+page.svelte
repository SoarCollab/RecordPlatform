<script lang="ts">
  import { onDestroy, onMount } from "svelte";
  import { goto } from "$app/navigation";
  import { useNotifications } from "$stores/notifications.svelte";
  import { useBadges } from "$stores/badges.svelte";
  import { useSSE } from "$stores/sse.svelte";
  import { formatDateTime } from "$utils/format";
  import {
    getTicket,
    replyTicket,
    closeTicket,
    confirmTicket,
  } from "$api/endpoints/tickets";
  import type { TicketVO, TicketReplyVO } from "$api/types";
  import type { SSEMessage } from "$api/endpoints/sse";
  import {
    TicketStatusLabel,
    TicketPriorityLabel,
    TicketCategoryLabel,
    TicketStatus,
    TicketPriority,
  } from "$api/types";
  import * as Card from "$lib/components/ui/card";
  import { Button } from "$lib/components/ui/button";
  import { Textarea } from "$lib/components/ui/textarea";
  import * as Dialog from "$lib/components/ui/dialog";

  let { data } = $props();

  const notifications = useNotifications();
  const badges = useBadges();
  const sse = useSSE();

  let unsubscribeSSE: (() => void) | null = null;

  let ticket = $state<TicketVO | null>(null);
  let replies = $state<TicketReplyVO[]>([]);
  let loading = $state(true);
  let error = $state<string | null>(null);

  // 回复状态
  let replyContent = $state("");
  let isReplying = $state(false);

  // 操作对话框
  let closeDialogOpen = $state(false);
  let confirmDialogOpen = $state(false);
  let isClosing = $state(false);
  let isConfirming = $state(false);

  const canReply = $derived(
    ticket &&
      ticket.status !== TicketStatus.CLOSED &&
      ticket.status !== TicketStatus.COMPLETED
  );
  const canClose = $derived(
    ticket &&
      ticket.status !== TicketStatus.CLOSED &&
      ticket.status !== TicketStatus.COMPLETED
  );
  const canConfirm = $derived(
    ticket && ticket.status === TicketStatus.CONFIRMING
  );

  onMount(() => {
    loadTicketData();
    unsubscribeSSE = sse.subscribe(handleSSEMessage);
  });

  onDestroy(() => {
    unsubscribeSSE?.();
  });

  /**
   * 处理 SSE 推送的工单更新事件，使工单详情在不手动刷新/点击的情况下自动更新回复与状态。
   *
   * @param message SSE 推送消息
   */
  function handleSSEMessage(message: SSEMessage) {
    if (message.type !== "ticket-updated") return;

    const payload = message.data as { ticketId?: string };
    if (!payload.ticketId) return;

    // 仅在当前打开的工单匹配时刷新，避免无关刷新
    if (payload.ticketId === data.ticketId || payload.ticketId === ticket?.id) {
      loadTicketData();
    }
  }

  async function loadTicketData() {
    loading = true;
    error = null;

    try {
      // 后端 getTicket 返回 TicketDetailVO，包含 replies 字段
      const ticketData = await getTicket(data.ticketId);
      ticket = ticketData;
      // 重新加载徽标以清除未读数（后端在 getTicket 时会更新查看时间）
      badges.fetch();
      // 从 TicketDetailVO 中获取回复列表（如果后端返回）
      // @ts-expect-error - TicketDetailVO 包含 replies，但前端 TicketVO 类型未定义
      replies = ticketData.replies || [];
    } catch (err) {
      error = err instanceof Error ? err.message : "加载失败";
      notifications.error("加载失败", error);
    } finally {
      loading = false;
    }
  }

  async function handleReply() {
    if (!replyContent.trim() || !ticket) return;

    isReplying = true;
    try {
      // replyTicket 返回 void，需要重新加载工单获取最新回复
      await replyTicket({
        ticketId: ticket.id,
        content: replyContent.trim(),
      });
      replyContent = "";
      notifications.success("回复成功");
      // 重新加载工单以获取最新回复与状态
      await loadTicketData();
    } catch (err) {
      notifications.error(
        "回复失败",
        err instanceof Error ? err.message : "请稍后重试"
      );
    } finally {
      isReplying = false;
    }
  }

  async function handleClose() {
    if (!ticket) return;

    isClosing = true;
    try {
      await closeTicket(ticket.id);
      notifications.success("工单已关闭");
      closeDialogOpen = false;
      // 重新加载工单
      ticket = await getTicket(data.ticketId);
    } catch (err) {
      notifications.error(
        "关闭失败",
        err instanceof Error ? err.message : "请稍后重试"
      );
    } finally {
      isClosing = false;
    }
  }

  async function handleConfirm() {
    if (!ticket) return;

    isConfirming = true;
    try {
      await confirmTicket(ticket.id);
      notifications.success("已确认问题解决");
      confirmDialogOpen = false;
      // 重新加载工单
      ticket = await getTicket(data.ticketId);
    } catch (err) {
      notifications.error(
        "确认失败",
        err instanceof Error ? err.message : "请稍后重试"
      );
    } finally {
      isConfirming = false;
    }
  }

  function getStatusClass(status: TicketStatus): string {
    switch (status) {
      case TicketStatus.PENDING:
        return "bg-blue-100 text-blue-700 dark:bg-blue-900 dark:text-blue-300";
      case TicketStatus.PROCESSING:
        return "bg-yellow-100 text-yellow-700 dark:bg-yellow-900 dark:text-yellow-300";
      case TicketStatus.CONFIRMING:
        return "bg-purple-100 text-purple-700 dark:bg-purple-900 dark:text-purple-300";
      case TicketStatus.COMPLETED:
        return "bg-green-100 text-green-700 dark:bg-green-900 dark:text-green-300";
      case TicketStatus.CLOSED:
        return "bg-gray-100 text-gray-700 dark:bg-gray-800 dark:text-gray-300";
      default:
        return "bg-gray-100 text-gray-700";
    }
  }

  function getPriorityClass(priority: TicketPriority): string {
    switch (priority) {
      case TicketPriority.HIGH:
        return "text-red-600 dark:text-red-400";
      case TicketPriority.MEDIUM:
        return "text-orange-600 dark:text-orange-400";
      default:
        return "text-muted-foreground";
    }
  }
</script>

<svelte:head>
  <title>{ticket?.title ?? "工单详情"} - 存证平台</title>
</svelte:head>

<div class="mx-auto max-w-4xl space-y-6">
  <!-- 返回按钮 -->
  <a
    href="/tickets"
    class="inline-flex items-center gap-1 text-sm text-muted-foreground hover:text-foreground"
  >
    <svg class="h-4 w-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
      <path
        stroke-linecap="round"
        stroke-linejoin="round"
        stroke-width="2"
        d="M10 19l-7-7m0 0l7-7m-7 7h18"
      />
    </svg>
    返回工单列表
  </a>

  {#if loading}
    <Card.Root>
      <Card.Content class="flex items-center justify-center p-12">
        <div
          class="h-8 w-8 animate-spin rounded-full border-4 border-primary border-t-transparent"
        ></div>
      </Card.Content>
    </Card.Root>
  {:else if error}
    <Card.Root>
      <Card.Content class="p-12 text-center">
        <div
          class="mx-auto mb-4 flex h-16 w-16 items-center justify-center rounded-full bg-destructive/10"
        >
          <svg
            class="h-8 w-8 text-destructive"
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
        </div>
        <p class="text-muted-foreground">{error}</p>
        <Button variant="outline" class="mt-4" onclick={() => goto("/tickets")}>
          返回工单列表
        </Button>
      </Card.Content>
    </Card.Root>
  {:else if ticket}
    <!-- 工单头部 -->
    <Card.Root>
      <Card.Header>
        <div class="flex items-start justify-between">
          <div class="space-y-1">
            <div class="flex items-center gap-2 text-sm text-muted-foreground">
              <span>{ticket.ticketNo}</span>
              {#if ticket.category !== undefined}
                <span>·</span>
                <span>{TicketCategoryLabel[ticket.category]}</span>
              {/if}
              {#if ticket.priority >= TicketPriority.HIGH}
                <span class={getPriorityClass(ticket.priority)}>
                  {TicketPriorityLabel[ticket.priority]}优先级
                </span>
              {/if}
            </div>
            <Card.Title class="text-xl">{ticket.title}</Card.Title>
            <Card.Description>
              由 {ticket.creatorUsername} 创建于 {formatDateTime(
                ticket.createTime
              )}
              {#if ticket.assigneeUsername}
                · 负责人: {ticket.assigneeUsername}
              {/if}
            </Card.Description>
          </div>
          <span
            class="rounded-full px-3 py-1 text-sm {getStatusClass(
              ticket.status
            )}"
          >
            {TicketStatusLabel[ticket.status]}
          </span>
        </div>
      </Card.Header>
      <Card.Content>
        <div class="whitespace-pre-wrap rounded-lg bg-muted/50 p-4">
          {ticket.content}
        </div>
      </Card.Content>
      {#if canClose || canConfirm}
        <Card.Footer class="flex gap-2">
          {#if canConfirm}
            <Button onclick={() => (confirmDialogOpen = true)}>
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
                  d="M5 13l4 4L19 7"
                />
              </svg>
              确认已解决
            </Button>
          {/if}
          {#if canClose}
            <Button variant="outline" onclick={() => (closeDialogOpen = true)}>
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
                  d="M6 18L18 6M6 6l12 12"
                />
              </svg>
              关闭工单
            </Button>
          {/if}
        </Card.Footer>
      {/if}
    </Card.Root>

    <!-- 回复列表 -->
    <Card.Root>
      <Card.Header>
        <Card.Title>回复记录 ({replies.length})</Card.Title>
      </Card.Header>
      <Card.Content class="p-0">
        {#if replies.length === 0}
          <div class="p-8 text-center text-muted-foreground">暂无回复</div>
        {:else}
          <div class="divide-y">
            {#each replies as reply (reply.id)}
              <div class="p-4">
                <div class="mb-2 flex items-center gap-2">
                  <div
                    class="flex h-8 w-8 items-center justify-center rounded-full text-sm font-medium {reply.isInternal ||
                    reply.isAdmin
                      ? 'bg-primary text-primary-foreground'
                      : 'bg-muted'}"
                  >
                    {(reply.replierName || "?").charAt(0).toUpperCase()}
                  </div>
                  <div>
                    <span class="font-medium">{reply.replierName}</span>
                    {#if reply.isInternal || reply.isAdmin}
                      <span
                        class="ml-2 rounded bg-primary/10 px-1.5 py-0.5 text-xs text-primary"
                        >管理员</span
                      >
                    {/if}
                  </div>
                  <span class="text-xs text-muted-foreground">
                    {formatDateTime(reply.createTime)}
                  </span>
                </div>
                <div class="ml-10 whitespace-pre-wrap">{reply.content}</div>
              </div>
            {/each}
          </div>
        {/if}
      </Card.Content>
    </Card.Root>

    <!-- 回复表单 -->
    {#if canReply}
      <Card.Root>
        <Card.Header>
          <Card.Title>添加回复</Card.Title>
        </Card.Header>
        <Card.Content>
          <Textarea
            bind:value={replyContent}
            placeholder="请输入回复内容..."
            class="min-h-[120px]"
            disabled={isReplying}
          />
        </Card.Content>
        <Card.Footer>
          <Button
            onclick={handleReply}
            disabled={!replyContent.trim() || isReplying}
          >
            {isReplying ? "发送中..." : "发送回复"}
          </Button>
        </Card.Footer>
      </Card.Root>
    {:else if ticket.status === TicketStatus.CLOSED || ticket.status === TicketStatus.COMPLETED}
      <Card.Root>
        <Card.Content class="py-6 text-center text-muted-foreground">
          工单已{ticket.status === TicketStatus.CLOSED
            ? "关闭"
            : "完成"}，无法继续回复
        </Card.Content>
      </Card.Root>
    {/if}
  {/if}
</div>

<!-- 关闭确认对话框 -->
<Dialog.Root bind:open={closeDialogOpen}>
  <Dialog.Content class="sm:max-w-md">
    <Dialog.Header>
      <Dialog.Title>关闭工单</Dialog.Title>
      <Dialog.Description>
        确定要关闭此工单吗？关闭后将无法继续回复。
      </Dialog.Description>
    </Dialog.Header>
    <Dialog.Footer>
      <Button variant="outline" onclick={() => (closeDialogOpen = false)}
        >取消</Button
      >
      <Button variant="destructive" onclick={handleClose} disabled={isClosing}>
        {isClosing ? "关闭中..." : "确认关闭"}
      </Button>
    </Dialog.Footer>
  </Dialog.Content>
</Dialog.Root>

<!-- 确认解决对话框 -->
<Dialog.Root bind:open={confirmDialogOpen}>
  <Dialog.Content class="sm:max-w-md">
    <Dialog.Header>
      <Dialog.Title>确认问题已解决</Dialog.Title>
      <Dialog.Description>
        确认问题已解决后，工单状态将变为"已解决"。如果问题未完全解决，请继续回复。
      </Dialog.Description>
    </Dialog.Header>
    <Dialog.Footer>
      <Button variant="outline" onclick={() => (confirmDialogOpen = false)}
        >取消</Button
      >
      <Button onclick={handleConfirm} disabled={isConfirming}>
        {isConfirming ? "确认中..." : "确认已解决"}
      </Button>
    </Dialog.Footer>
  </Dialog.Content>
</Dialog.Root>
