<script lang="ts">
	import { onDestroy, onMount } from 'svelte';
	import { goto } from '$app/navigation';
	import { useNotifications } from '$stores/notifications.svelte';
	import { useSSE } from '$stores/sse.svelte';
	import type { SSEMessage } from '$api/endpoints/sse';
	import { formatDateTime } from '$utils/format';
	import { getTickets } from '$api/endpoints/tickets';
	import type { TicketVO } from '$api/types';
	import {
		TicketStatusLabel,
		TicketPriorityLabel,
		TicketCategoryLabel,
		TicketStatus,
		TicketPriority
	} from '$api/types';
	import { Button } from '$lib/components/ui/button';
	import * as Card from '$lib/components/ui/card';

	const notifications = useNotifications();
	const sse = useSSE();

	let tickets = $state<TicketVO[]>([]);
	let loading = $state(true);
	let page = $state(1);
	let total = $state(0);
	let pageSize = $state(20);
	let unsubscribeSSE: (() => void) | null = null;

	// 筛选条件
	let statusFilter = $state<TicketStatus | undefined>(undefined);

	onMount(() => {
		loadTickets();
		unsubscribeSSE = sse.subscribe(handleSSEMessage);
	});

	onDestroy(() => {
		unsubscribeSSE?.();
	});

	/**
	 * 处理 SSE 推送的工单更新事件，使工单列表在不手动刷新/点击的情况下自动刷新。
	 *
	 * @param message SSE 推送消息
	 */
	function handleSSEMessage(message: SSEMessage) {
		if (message.type !== 'ticket-updated') return;

		const data = message.data as { ticketId?: string };
		// 如果事件没有指定工单ID，直接刷新当前列表
		if (!data.ticketId) {
			loadTickets();
			return;
		}

		// 当前页包含该工单则刷新；不包含也刷新一次以更新排序/回复数等信息
		//（工单列表通常量不大，直接刷新实现最稳妥）
		loadTickets();
	}

	async function loadTickets() {
		loading = true;
		try {
			const result = await getTickets({
				pageNum: page,
				pageSize,
				status: statusFilter
			});
			tickets = result.records;
			total = result.total;
		} catch (err) {
			notifications.error('加载失败', err instanceof Error ? err.message : '请稍后重试');
		} finally {
			loading = false;
		}
	}

	function _getStatusVariant(
		status: TicketStatus
	): 'default' | 'secondary' | 'destructive' | 'outline' {
		switch (status) {
			case TicketStatus.PENDING:
				return 'secondary';
			case TicketStatus.PROCESSING:
				return 'default';
			case TicketStatus.CONFIRMING:
				return 'outline';
			case TicketStatus.COMPLETED:
				return 'default';
			case TicketStatus.CLOSED:
				return 'secondary';
			default:
				return 'secondary';
		}
	}

	function getStatusClass(status: TicketStatus): string {
		switch (status) {
			case TicketStatus.PENDING:
				return 'bg-blue-100 text-blue-700 dark:bg-blue-900 dark:text-blue-300';
			case TicketStatus.PROCESSING:
				return 'bg-yellow-100 text-yellow-700 dark:bg-yellow-900 dark:text-yellow-300';
			case TicketStatus.CONFIRMING:
				return 'bg-purple-100 text-purple-700 dark:bg-purple-900 dark:text-purple-300';
			case TicketStatus.COMPLETED:
				return 'bg-green-100 text-green-700 dark:bg-green-900 dark:text-green-300';
			case TicketStatus.CLOSED:
				return 'bg-gray-100 text-gray-700 dark:bg-gray-800 dark:text-gray-300';
			default:
				return 'bg-gray-100 text-gray-700';
		}
	}

	function getPriorityClass(priority: TicketPriority): string {
		switch (priority) {
			case TicketPriority.HIGH:
				return 'text-red-600 dark:text-red-400';
			case TicketPriority.MEDIUM:
				return 'text-orange-600 dark:text-orange-400';
			case TicketPriority.LOW:
				return 'text-muted-foreground/60';
			default:
				return 'text-muted-foreground';
		}
	}

	function handleFilterChange(status: TicketStatus | undefined) {
		statusFilter = status;
		page = 1;
		loadTickets();
	}
</script>

<svelte:head>
	<title>工单系统 - 存证平台</title>
</svelte:head>

<div class="space-y-6">
	<div class="flex items-center justify-between">
		<div>
			<h1 class="text-2xl font-bold">工单系统</h1>
			<p class="text-muted-foreground">提交问题或获取帮助</p>
		</div>
		<Button onclick={() => goto('/tickets/new')}>
			<svg class="mr-2 h-4 w-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
				<path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M12 4v16m8-8H4" />
			</svg>
			创建工单
		</Button>
	</div>

	<!-- 筛选条件 -->
	<div class="flex flex-wrap gap-2">
		<button
			class="rounded-full px-3 py-1 text-sm transition-colors {statusFilter === undefined
				? 'bg-primary text-primary-foreground'
				: 'bg-muted hover:bg-muted/80'}"
			onclick={() => handleFilterChange(undefined)}
		>
			全部
		</button>
		<button
			class="rounded-full px-3 py-1 text-sm transition-colors {statusFilter === TicketStatus.PENDING
				? 'bg-primary text-primary-foreground'
				: 'bg-muted hover:bg-muted/80'}"
			onclick={() => handleFilterChange(TicketStatus.PENDING)}
		>
			待处理
		</button>
		<button
			class="rounded-full px-3 py-1 text-sm transition-colors {statusFilter ===
			TicketStatus.PROCESSING
				? 'bg-primary text-primary-foreground'
				: 'bg-muted hover:bg-muted/80'}"
			onclick={() => handleFilterChange(TicketStatus.PROCESSING)}
		>
			处理中
		</button>
		<button
			class="rounded-full px-3 py-1 text-sm transition-colors {statusFilter ===
			TicketStatus.CONFIRMING
				? 'bg-primary text-primary-foreground'
				: 'bg-muted hover:bg-muted/80'}"
			onclick={() => handleFilterChange(TicketStatus.CONFIRMING)}
		>
			待确认
		</button>
		<button
			class="rounded-full px-3 py-1 text-sm transition-colors {statusFilter ===
			TicketStatus.COMPLETED
				? 'bg-primary text-primary-foreground'
				: 'bg-muted hover:bg-muted/80'}"
			onclick={() => handleFilterChange(TicketStatus.COMPLETED)}
		>
			已解决
		</button>
	</div>

	{#if loading}
		<Card.Root>
			<Card.Content class="p-0">
				<div class="divide-y">
					{#each Array(3) as _}
						<div class="flex items-center justify-between p-4">
							<div>
								<div class="mb-2 h-3 w-24 animate-pulse rounded bg-muted"></div>
								<div class="h-4 w-48 animate-pulse rounded bg-muted"></div>
								<div class="mt-2 h-3 w-32 animate-pulse rounded bg-muted"></div>
							</div>
							<div class="h-6 w-16 animate-pulse rounded-full bg-muted"></div>
						</div>
					{/each}
				</div>
			</Card.Content>
		</Card.Root>
	{:else if tickets.length === 0}
		<Card.Root>
			<Card.Content class="p-12 text-center">
				<div class="mx-auto mb-4 flex h-16 w-16 items-center justify-center rounded-full bg-muted">
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
							d="M15 5v2m0 4v2m0 4v2M5 5a2 2 0 00-2 2v3a2 2 0 110 4v3a2 2 0 002 2h14a2 2 0 002-2v-3a2 2 0 110-4V7a2 2 0 00-2-2H5z"
						/>
					</svg>
				</div>
				<p class="text-muted-foreground">
					{statusFilter !== undefined ? '没有符合条件的工单' : '暂无工单'}
				</p>
				{#if statusFilter === undefined}
					<Button class="mt-4" onclick={() => goto('/tickets/new')}>创建第一个工单</Button>
				{:else}
					<button
						class="mt-2 text-sm text-primary hover:underline"
						onclick={() => handleFilterChange(undefined)}
					>
						查看全部工单
					</button>
				{/if}
			</Card.Content>
		</Card.Root>
	{:else}
		<Card.Root>
			<Card.Content class="p-0">
				<div class="divide-y">
					{#each tickets as ticket (ticket.id)}
						<div
							class="flex cursor-pointer items-center justify-between p-4 transition-colors hover:bg-muted/50"
							onclick={() => goto(`/tickets/${ticket.id}`)}
							role="button"
							tabindex="0"
							onkeypress={(e) => e.key === 'Enter' && goto(`/tickets/${ticket.id}`)}
						>
							<div class="min-w-0 flex-1">
								<div class="mb-1 flex items-center gap-2">
									<span class="text-xs text-muted-foreground">{ticket.ticketNo}</span>
									{#if ticket.category !== undefined}
										<span class="rounded bg-muted px-1.5 py-0.5 text-xs">
											{TicketCategoryLabel[ticket.category]}
										</span>
									{/if}
									{#if ticket.priority >= TicketPriority.HIGH}
										<span class="text-xs {getPriorityClass(ticket.priority)}">
											{TicketPriorityLabel[ticket.priority]}优先级
										</span>
									{/if}
								</div>
								<p class="truncate font-medium">{ticket.title}</p>
								<p class="mt-1 text-sm text-muted-foreground">
									{formatDateTime(ticket.createTime)} · {ticket.replyCount} 回复
								</p>
							</div>
							<span class="ml-4 rounded-full px-2 py-1 text-xs {getStatusClass(ticket.status)}">
								{TicketStatusLabel[ticket.status]}
							</span>
						</div>
					{/each}
				</div>
			</Card.Content>
		</Card.Root>

		<!-- 分页 -->
		{#if total > pageSize}
			<div class="flex items-center justify-between">
				<p class="text-sm text-muted-foreground">共 {total} 个工单</p>
				<div class="flex gap-2">
					<Button
						variant="outline"
						size="sm"
						disabled={page <= 1}
						onclick={() => {
							page--;
							loadTickets();
						}}
					>
						上一页
					</Button>
					<Button
						variant="outline"
						size="sm"
						disabled={page >= Math.ceil(total / pageSize)}
						onclick={() => {
							page++;
							loadTickets();
						}}
					>
						下一页
					</Button>
				</div>
			</div>
		{/if}
	{/if}
</div>
