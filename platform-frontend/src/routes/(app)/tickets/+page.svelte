<script lang="ts">
	import { onMount } from 'svelte';
	import { goto } from '$app/navigation';
	import { useNotifications } from '$stores/notifications.svelte';
	import { formatDateTime } from '$utils/format';
	import { getTickets } from '$api/endpoints/tickets';
	import type { TicketVO } from '$api/types';
	import {
		TicketStatusLabel,
		TicketPriorityLabel,
		TicketCategoryLabel,
		TicketStatus,
		TicketPriority,
		TicketCategory
	} from '$api/types';
	import { Button } from '$lib/components/ui/button';
	import * as Card from '$lib/components/ui/card';
	import { Badge } from '$lib/components/ui/badge';

	const notifications = useNotifications();

	let tickets = $state<TicketVO[]>([]);
	let loading = $state(true);
	let page = $state(1);
	let total = $state(0);
	let pageSize = $state(20);

	// Filters
	let statusFilter = $state<TicketStatus | undefined>(undefined);

	onMount(() => {
		loadTickets();
	});

	async function loadTickets() {
		loading = true;
		try {
			const result = await getTickets({
				current: page,
				size: pageSize,
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

	function getStatusVariant(
		status: TicketStatus
	): 'default' | 'secondary' | 'destructive' | 'outline' {
		switch (status) {
			case TicketStatus.OPEN:
				return 'secondary';
			case TicketStatus.IN_PROGRESS:
				return 'default';
			case TicketStatus.PENDING:
				return 'outline';
			case TicketStatus.RESOLVED:
				return 'default';
			case TicketStatus.CLOSED:
				return 'secondary';
			default:
				return 'secondary';
		}
	}

	function getStatusClass(status: TicketStatus): string {
		switch (status) {
			case TicketStatus.OPEN:
				return 'bg-blue-100 text-blue-700 dark:bg-blue-900 dark:text-blue-300';
			case TicketStatus.IN_PROGRESS:
				return 'bg-yellow-100 text-yellow-700 dark:bg-yellow-900 dark:text-yellow-300';
			case TicketStatus.PENDING:
				return 'bg-purple-100 text-purple-700 dark:bg-purple-900 dark:text-purple-300';
			case TicketStatus.RESOLVED:
				return 'bg-green-100 text-green-700 dark:bg-green-900 dark:text-green-300';
			case TicketStatus.CLOSED:
				return 'bg-gray-100 text-gray-700 dark:bg-gray-800 dark:text-gray-300';
			default:
				return 'bg-gray-100 text-gray-700';
		}
	}

	function getPriorityClass(priority: TicketPriority): string {
		switch (priority) {
			case TicketPriority.URGENT:
				return 'text-red-600 dark:text-red-400';
			case TicketPriority.HIGH:
				return 'text-orange-600 dark:text-orange-400';
			case TicketPriority.NORMAL:
				return 'text-muted-foreground';
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

	<!-- Filters -->
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
			class="rounded-full px-3 py-1 text-sm transition-colors {statusFilter === TicketStatus.OPEN
				? 'bg-primary text-primary-foreground'
				: 'bg-muted hover:bg-muted/80'}"
			onclick={() => handleFilterChange(TicketStatus.OPEN)}
		>
			待处理
		</button>
		<button
			class="rounded-full px-3 py-1 text-sm transition-colors {statusFilter ===
			TicketStatus.IN_PROGRESS
				? 'bg-primary text-primary-foreground'
				: 'bg-muted hover:bg-muted/80'}"
			onclick={() => handleFilterChange(TicketStatus.IN_PROGRESS)}
		>
			处理中
		</button>
		<button
			class="rounded-full px-3 py-1 text-sm transition-colors {statusFilter ===
			TicketStatus.PENDING
				? 'bg-primary text-primary-foreground'
				: 'bg-muted hover:bg-muted/80'}"
			onclick={() => handleFilterChange(TicketStatus.PENDING)}
		>
			等待反馈
		</button>
		<button
			class="rounded-full px-3 py-1 text-sm transition-colors {statusFilter ===
			TicketStatus.RESOLVED
				? 'bg-primary text-primary-foreground'
				: 'bg-muted hover:bg-muted/80'}"
			onclick={() => handleFilterChange(TicketStatus.RESOLVED)}
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
									<span class="rounded bg-muted px-1.5 py-0.5 text-xs">
										{TicketCategoryLabel[ticket.category]}
									</span>
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

		<!-- Pagination -->
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
