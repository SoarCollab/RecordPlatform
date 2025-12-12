<script lang="ts">
	import { onMount, onDestroy, tick } from 'svelte';
	import { goto } from '$app/navigation';
	import { useNotifications } from '$stores/notifications.svelte';
	import { useAuth } from '$stores/auth.svelte';
	import { useSSE } from '$stores/sse.svelte';
	import { formatDateTime, formatRelativeTime } from '$utils/format';
	import {
		getConversation,
		getMessages,
		sendMessage,
		markAsRead
	} from '$api/endpoints/messages';
	import type { ConversationVO, MessageVO, Page } from '$api/types';
	import { MessageType, MessageStatus } from '$api/types';
	import type { SSEMessage } from '$api/endpoints/sse';
	import * as Card from '$lib/components/ui/card';
	import { Button } from '$lib/components/ui/button';
	import { Textarea } from '$lib/components/ui/textarea';
	import { ScrollArea } from '$lib/components/ui/scroll-area';

	let { data } = $props();

	const notifications = useNotifications();
	const auth = useAuth();
	const sse = useSSE();

	let conversation = $state<ConversationVO | null>(null);
	let messages = $state<MessageVO[]>([]);
	let loading = $state(true);
	let loadingMore = $state(false);
	let error = $state<string | null>(null);
	let page = $state(1);
	let total = $state(0);
	let pageSize = $state(30);
	let hasMore = $derived(messages.length < total);

	// Message input
	let messageContent = $state('');
	let isSending = $state(false);

	// Scroll container ref
	let scrollContainer = $state<HTMLDivElement | null>(null);
	let shouldScrollToBottom = $state(true);

	// SSE subscription cleanup
	let unsubscribeSSE: (() => void) | null = null;

	onMount(() => {
		loadConversationAndMessages();
		// Subscribe to real-time messages
		unsubscribeSSE = sse.subscribe(handleSSEMessage);
	});

	onDestroy(() => {
		unsubscribeSSE?.();
	});

	function handleSSEMessage(message: SSEMessage) {
		if (message.type === 'message-received' && conversation) {
			const msgData = message.data as {
				conversationId?: string;
				senderId?: string;
				senderName?: string;
				content?: string;
				createTime?: string;
				id?: string;
			};
			// Check if message belongs to this conversation
			if (msgData.conversationId === conversation.id && msgData.senderId !== auth.user?.id) {
				// Add incoming message to the list
				const newMessage: MessageVO = {
					id: msgData.id || crypto.randomUUID(),
					conversationId: conversation.id,
					senderId: msgData.senderId || '',
					senderUsername: msgData.senderName || '',
					receiverId: auth.user?.id || '',
					content: msgData.content || '',
					type: MessageType.TEXT,
					status: MessageStatus.READ,
					createTime: msgData.createTime || new Date().toISOString()
				};
				messages = [...messages, newMessage];
				total++;
				// Auto scroll and mark as read
				tick().then(() => {
					scrollToBottom();
					markAsRead(data.conversationId).catch(() => {});
				});
			}
		}
	}

	async function loadConversationAndMessages() {
		loading = true;
		error = null;

		try {
			// Load conversation details
			conversation = await getConversation(data.conversationId);

			// Load initial messages
			const result = await getMessages(data.conversationId, { current: 1, size: pageSize });
			// Messages come newest first, reverse for display
			messages = result.records.slice().reverse();
			total = result.total;
			page = 1;

			// Mark as read
			await markAsRead(data.conversationId);

			// Scroll to bottom after render
			await tick();
			scrollToBottom();
		} catch (err) {
			error = err instanceof Error ? err.message : '加载失败';
			notifications.error('加载失败', error);
		} finally {
			loading = false;
		}
	}

	async function loadMoreMessages() {
		if (loadingMore || !hasMore) return;

		loadingMore = true;
		try {
			const nextPage = page + 1;
			const result = await getMessages(data.conversationId, { current: nextPage, size: pageSize });
			// Prepend older messages (reversed)
			messages = [...result.records.slice().reverse(), ...messages];
			page = nextPage;
		} catch (err) {
			notifications.error('加载更多失败', err instanceof Error ? err.message : '请稍后重试');
		} finally {
			loadingMore = false;
		}
	}

	async function handleSend() {
		if (!messageContent.trim() || !conversation) return;

		const content = messageContent.trim();
		messageContent = '';
		isSending = true;

		try {
			const newMessage = await sendMessage({
				receiverUsername: conversation.otherUsername,
				content,
				type: MessageType.TEXT
			});
			messages = [...messages, newMessage];

			// Scroll to bottom
			await tick();
			scrollToBottom();
		} catch (err) {
			// Restore message content on failure
			messageContent = content;
			notifications.error('发送失败', err instanceof Error ? err.message : '请稍后重试');
		} finally {
			isSending = false;
		}
	}

	function handleKeyDown(e: KeyboardEvent) {
		// Ctrl+Enter or Cmd+Enter to send
		if ((e.ctrlKey || e.metaKey) && e.key === 'Enter') {
			e.preventDefault();
			handleSend();
		}
	}

	function scrollToBottom() {
		if (scrollContainer) {
			scrollContainer.scrollTop = scrollContainer.scrollHeight;
		}
	}

	function isOwnMessage(msg: MessageVO): boolean {
		return auth.user?.id === msg.senderId;
	}

	function getDisplayName(conv: ConversationVO): string {
		return conv.otherNickname || conv.otherUsername;
	}

	function getAvatarText(name: string): string {
		return name.charAt(0).toUpperCase();
	}
</script>

<svelte:head>
	<title>{conversation ? `与 ${getDisplayName(conversation)} 的对话` : '消息'} - 存证平台</title>
</svelte:head>

<div class="flex h-[calc(100vh-8rem)] flex-col">
	<!-- Header -->
	<div class="flex items-center gap-4 border-b pb-4">
		<a
			href="/messages"
			class="flex items-center gap-1 text-sm text-muted-foreground hover:text-foreground"
		>
			<svg class="h-4 w-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
				<path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M10 19l-7-7m0 0l7-7m-7 7h18" />
			</svg>
			返回
		</a>

		{#if conversation}
			<div class="flex items-center gap-3">
				<div class="flex h-10 w-10 items-center justify-center rounded-full bg-primary/10 text-lg font-medium text-primary">
					{getAvatarText(getDisplayName(conversation))}
				</div>
				<div>
					<p class="font-medium">{getDisplayName(conversation)}</p>
					<p class="text-xs text-muted-foreground">@{conversation.otherUsername}</p>
				</div>
			</div>
		{/if}
	</div>

	{#if loading}
		<div class="flex flex-1 items-center justify-center">
			<div class="h-8 w-8 animate-spin rounded-full border-4 border-primary border-t-transparent"></div>
		</div>
	{:else if error}
		<div class="flex flex-1 flex-col items-center justify-center gap-4">
			<div class="flex h-16 w-16 items-center justify-center rounded-full bg-destructive/10">
				<svg class="h-8 w-8 text-destructive" fill="none" stroke="currentColor" viewBox="0 0 24 24">
					<path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M12 9v2m0 4h.01m-6.938 4h13.856c1.54 0 2.502-1.667 1.732-3L13.732 4c-.77-1.333-2.694-1.333-3.464 0L3.34 16c-.77 1.333.192 3 1.732 3z" />
				</svg>
			</div>
			<p class="text-muted-foreground">{error}</p>
			<Button variant="outline" onclick={() => goto('/messages')}>
				返回消息列表
			</Button>
		</div>
	{:else}
		<!-- Messages Area -->
		<div
			bind:this={scrollContainer}
			class="flex-1 overflow-y-auto p-4"
		>
			<!-- Load More Button -->
			{#if hasMore}
				<div class="mb-4 flex justify-center">
					<Button
						variant="ghost"
						size="sm"
						disabled={loadingMore}
						onclick={loadMoreMessages}
					>
						{loadingMore ? '加载中...' : '加载更多消息'}
					</Button>
				</div>
			{/if}

			<!-- Message List -->
			{#if messages.length === 0}
				<div class="flex h-full flex-col items-center justify-center text-muted-foreground">
					<svg class="mb-4 h-16 w-16" fill="none" stroke="currentColor" viewBox="0 0 24 24">
						<path stroke-linecap="round" stroke-linejoin="round" stroke-width="1.5" d="M8 12h.01M12 12h.01M16 12h.01M21 12c0 4.418-4.03 8-9 8a9.863 9.863 0 01-4.255-.949L3 20l1.395-3.72C3.512 15.042 3 13.574 3 12c0-4.418 4.03-8 9-8s9 3.582 9 8z" />
					</svg>
					<p>暂无消息</p>
					<p class="mt-1 text-sm">发送第一条消息开始对话</p>
				</div>
			{:else}
				<div class="space-y-4">
					{#each messages as msg (msg.id)}
						{@const isOwn = isOwnMessage(msg)}
						<div class="flex {isOwn ? 'justify-end' : 'justify-start'}">
							<div class="flex max-w-[70%] gap-2 {isOwn ? 'flex-row-reverse' : ''}">
								<!-- Avatar -->
								<div class="flex h-8 w-8 shrink-0 items-center justify-center rounded-full {isOwn ? 'bg-primary text-primary-foreground' : 'bg-muted'} text-sm font-medium">
									{isOwn ? getAvatarText(auth.displayName) : getAvatarText(conversation?.otherNickname || conversation?.otherUsername || '')}
								</div>

								<!-- Message Content -->
								<div>
									<div class="rounded-lg px-4 py-2 {isOwn ? 'bg-primary text-primary-foreground' : 'bg-muted'}">
										<p class="whitespace-pre-wrap break-words">{msg.content}</p>
									</div>
									<p class="mt-1 text-xs text-muted-foreground {isOwn ? 'text-right' : ''}">
										{formatRelativeTime(msg.createTime)}
									</p>
								</div>
							</div>
						</div>
					{/each}
				</div>
			{/if}
		</div>

		<!-- Input Area -->
		<div class="border-t pt-4">
			<div class="flex gap-2">
				<Textarea
					bind:value={messageContent}
					placeholder="输入消息... (Ctrl+Enter 发送)"
					class="min-h-[80px] resize-none"
					onkeydown={handleKeyDown}
					disabled={isSending}
				/>
				<div class="flex flex-col gap-2">
					<Button
						onclick={handleSend}
						disabled={!messageContent.trim() || isSending}
						class="h-full"
					>
						{#if isSending}
							<svg class="h-4 w-4 animate-spin" fill="none" viewBox="0 0 24 24">
								<circle class="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" stroke-width="4"></circle>
								<path class="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4zm2 5.291A7.962 7.962 0 014 12H0c0 3.042 1.135 5.824 3 7.938l3-2.647z"></path>
							</svg>
						{:else}
							<svg class="h-4 w-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
								<path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M12 19l9 2-9-18-9 18 9-2zm0 0v-8" />
							</svg>
						{/if}
					</Button>
				</div>
			</div>
			<p class="mt-2 text-xs text-muted-foreground">
				按 Ctrl+Enter 或 ⌘+Enter 快捷发送
			</p>
		</div>
	{/if}
</div>
