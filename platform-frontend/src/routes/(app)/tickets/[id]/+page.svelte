<script lang="ts">
	import { onMount } from 'svelte';
	import { goto } from '$app/navigation';
	import { useNotifications } from '$stores/notifications.svelte';
	import { useAuth } from '$stores/auth.svelte';
	import { formatDateTime } from '$utils/format';
	import {
		getTicket,
		getTicketReplies,
		replyTicket,
		closeTicket,
		confirmTicket
	} from '$api/endpoints/tickets';
	import type { TicketVO, TicketReplyVO } from '$api/types';
	import {
		TicketStatusLabel,
		TicketPriorityLabel,
		TicketCategoryLabel,
		TicketStatus,
		TicketPriority
	} from '$api/types';
	import * as Card from '$lib/components/ui/card';
	import { Button } from '$lib/components/ui/button';
	import { Textarea } from '$lib/components/ui/textarea';
	import { Badge } from '$lib/components/ui/badge';
	import { Separator } from '$lib/components/ui/separator';
	import * as Dialog from '$lib/components/ui/dialog';

	let { data } = $props();

	const notifications = useNotifications();
	const auth = useAuth();

	let ticket = $state<TicketVO | null>(null);
	let replies = $state<TicketReplyVO[]>([]);
	let loading = $state(true);
	let error = $state<string | null>(null);

	// Reply state
	let replyContent = $state('');
	let isReplying = $state(false);

	// Action dialogs
	let closeDialogOpen = $state(false);
	let confirmDialogOpen = $state(false);
	let isClosing = $state(false);
	let isConfirming = $state(false);

	const canReply = $derived(
		ticket && ticket.status !== TicketStatus.CLOSED && ticket.status !== TicketStatus.RESOLVED
	);
	const canClose = $derived(
		ticket && ticket.status !== TicketStatus.CLOSED && ticket.status !== TicketStatus.RESOLVED
	);
	const canConfirm = $derived(ticket && ticket.status === TicketStatus.PENDING);

	onMount(() => {
		loadTicketData();
	});

	async function loadTicketData() {
		loading = true;
		error = null;

		try {
			const [ticketData, repliesData] = await Promise.all([
				getTicket(data.ticketId),
				getTicketReplies(data.ticketId, { current: 1, size: 100 })
			]);
			ticket = ticketData;
			replies = repliesData.records;
		} catch (err) {
			error = err instanceof Error ? err.message : '加载失败';
			notifications.error('加载失败', error);
		} finally {
			loading = false;
		}
	}

	async function handleReply() {
		if (!replyContent.trim() || !ticket) return;

		isReplying = true;
		try {
			const newReply = await replyTicket({
				ticketId: ticket.id,
				content: replyContent.trim()
			});
			replies = [...replies, newReply];
			replyContent = '';
			notifications.success('回复成功');
			// Reload ticket to get updated status
			ticket = await getTicket(data.ticketId);
		} catch (err) {
			notifications.error('回复失败', err instanceof Error ? err.message : '请稍后重试');
		} finally {
			isReplying = false;
		}
	}

	async function handleClose() {
		if (!ticket) return;

		isClosing = true;
		try {
			await closeTicket(ticket.id);
			notifications.success('工单已关闭');
			closeDialogOpen = false;
			// Reload ticket
			ticket = await getTicket(data.ticketId);
		} catch (err) {
			notifications.error('关闭失败', err instanceof Error ? err.message : '请稍后重试');
		} finally {
			isClosing = false;
		}
	}

	async function handleConfirm() {
		if (!ticket) return;

		isConfirming = true;
		try {
			await confirmTicket(ticket.id);
			notifications.success('已确认问题解决');
			confirmDialogOpen = false;
			// Reload ticket
			ticket = await getTicket(data.ticketId);
		} catch (err) {
			notifications.error('确认失败', err instanceof Error ? err.message : '请稍后重试');
		} finally {
			isConfirming = false;
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
			default:
				return 'text-muted-foreground';
		}
	}
</script>

<svelte:head>
	<title>{ticket?.title ?? '工单详情'} - 存证平台</title>
</svelte:head>

<div class="mx-auto max-w-4xl space-y-6">
	<!-- Back button -->
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
				<Button variant="outline" class="mt-4" onclick={() => goto('/tickets')}>
					返回工单列表
				</Button>
			</Card.Content>
		</Card.Root>
	{:else if ticket}
		<!-- Ticket Header -->
		<Card.Root>
			<Card.Header>
				<div class="flex items-start justify-between">
					<div class="space-y-1">
						<div class="flex items-center gap-2 text-sm text-muted-foreground">
							<span>{ticket.ticketNo}</span>
							<span>·</span>
							<span>{TicketCategoryLabel[ticket.category]}</span>
							{#if ticket.priority >= TicketPriority.HIGH}
								<span class={getPriorityClass(ticket.priority)}>
									{TicketPriorityLabel[ticket.priority]}优先级
								</span>
							{/if}
						</div>
						<Card.Title class="text-xl">{ticket.title}</Card.Title>
						<Card.Description>
							由 {ticket.creatorUsername} 创建于 {formatDateTime(ticket.createTime)}
							{#if ticket.assigneeUsername}
								· 负责人: {ticket.assigneeUsername}
							{/if}
						</Card.Description>
					</div>
					<span class="rounded-full px-3 py-1 text-sm {getStatusClass(ticket.status)}">
						{TicketStatusLabel[ticket.status]}
					</span>
				</div>
			</Card.Header>
			<Card.Content>
				<div class="whitespace-pre-wrap rounded-lg bg-muted/50 p-4">{ticket.content}</div>
			</Card.Content>
			{#if canClose || canConfirm}
				<Card.Footer class="flex gap-2">
					{#if canConfirm}
						<Button onclick={() => (confirmDialogOpen = true)}>
							<svg class="mr-2 h-4 w-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
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
							<svg class="mr-2 h-4 w-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
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

		<!-- Replies -->
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
										class="flex h-8 w-8 items-center justify-center rounded-full text-sm font-medium {reply.isStaff
											? 'bg-primary text-primary-foreground'
											: 'bg-muted'}"
									>
										{(reply.replyerNickname || reply.replyerUsername).charAt(0).toUpperCase()}
									</div>
									<div>
										<span class="font-medium">{reply.replyerNickname || reply.replyerUsername}</span>
										{#if reply.isStaff}
											<span
												class="ml-2 rounded bg-primary/10 px-1.5 py-0.5 text-xs text-primary"
												>客服</span
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

		<!-- Reply Form -->
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
					<Button onclick={handleReply} disabled={!replyContent.trim() || isReplying}>
						{isReplying ? '发送中...' : '发送回复'}
					</Button>
				</Card.Footer>
			</Card.Root>
		{:else if ticket.status === TicketStatus.CLOSED || ticket.status === TicketStatus.RESOLVED}
			<Card.Root>
				<Card.Content class="py-6 text-center text-muted-foreground">
					工单已{ticket.status === TicketStatus.CLOSED ? '关闭' : '解决'}，无法继续回复
				</Card.Content>
			</Card.Root>
		{/if}
	{/if}
</div>

<!-- Close Confirmation Dialog -->
<Dialog.Root bind:open={closeDialogOpen}>
	<Dialog.Content class="sm:max-w-md">
		<Dialog.Header>
			<Dialog.Title>关闭工单</Dialog.Title>
			<Dialog.Description>
				确定要关闭此工单吗？关闭后将无法继续回复。
			</Dialog.Description>
		</Dialog.Header>
		<Dialog.Footer>
			<Button variant="outline" onclick={() => (closeDialogOpen = false)}>取消</Button>
			<Button variant="destructive" onclick={handleClose} disabled={isClosing}>
				{isClosing ? '关闭中...' : '确认关闭'}
			</Button>
		</Dialog.Footer>
	</Dialog.Content>
</Dialog.Root>

<!-- Confirm Resolution Dialog -->
<Dialog.Root bind:open={confirmDialogOpen}>
	<Dialog.Content class="sm:max-w-md">
		<Dialog.Header>
			<Dialog.Title>确认问题已解决</Dialog.Title>
			<Dialog.Description>
				确认问题已解决后，工单状态将变为"已解决"。如果问题未完全解决，请继续回复。
			</Dialog.Description>
		</Dialog.Header>
		<Dialog.Footer>
			<Button variant="outline" onclick={() => (confirmDialogOpen = false)}>取消</Button>
			<Button onclick={handleConfirm} disabled={isConfirming}>
				{isConfirming ? '确认中...' : '确认已解决'}
			</Button>
		</Dialog.Footer>
	</Dialog.Content>
</Dialog.Root>
