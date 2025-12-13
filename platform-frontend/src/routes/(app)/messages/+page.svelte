<script lang="ts">
	import { onMount } from 'svelte';
	import { goto } from '$app/navigation';
	import { useNotifications } from '$stores/notifications.svelte';
	import { useAuth } from '$stores/auth.svelte';
	import { formatDateTime, formatRelativeTime } from '$utils/format';
	import { getConversations, deleteConversation } from '$api/endpoints/messages';
	import type { ConversationVO, Page } from '$api/types';
	import * as Card from '$lib/components/ui/card';
	import { Button } from '$lib/components/ui/button';
	import { Avatar } from '$lib/components/ui/avatar';
	import { Badge } from '$lib/components/ui/badge';
	import * as Dialog from '$lib/components/ui/dialog';
	import { Input } from '$lib/components/ui/input';

	const notifications = useNotifications();
	const auth = useAuth();

	let conversations = $state<ConversationVO[]>([]);
	let loading = $state(true);
	let page = $state(1);
	let total = $state(0);
	let pageSize = $state(20);

	// New conversation dialog
	let newConversationOpen = $state(false);
	let newReceiverUsername = $state('');

	onMount(() => {
		loadConversations();
	});

	async function loadConversations() {
		loading = true;
		try {
			const result = await getConversations({ pageNum: page, pageSize });
			conversations = result.records;
			total = result.total;
		} catch (err) {
			notifications.error('加载失败', err instanceof Error ? err.message : '请稍后重试');
		} finally {
			loading = false;
		}
	}

	async function handleDelete(conv: ConversationVO, e: Event) {
		e.stopPropagation();
		if (!confirm('确定要删除这个会话吗？消息记录将被清除。')) return;

		try {
			await deleteConversation(conv.id);
			notifications.success('已删除');
			await loadConversations();
		} catch (err) {
			notifications.error('删除失败', err instanceof Error ? err.message : '请稍后重试');
		}
	}

	function handleStartConversation() {
		if (!newReceiverUsername.trim()) {
			notifications.warning('请输入用户名');
			return;
		}
		// Navigate to a new conversation (the backend will create if not exists)
		goto(`/messages/new?to=${encodeURIComponent(newReceiverUsername.trim())}`);
		newConversationOpen = false;
		newReceiverUsername = '';
	}

	function getDisplayName(conv: ConversationVO): string {
		return conv.otherNickname || conv.otherUsername;
	}

	function getAvatarText(conv: ConversationVO): string {
		return (conv.otherNickname || conv.otherUsername).charAt(0).toUpperCase();
	}
</script>

<svelte:head>
	<title>消息中心 - 存证平台</title>
</svelte:head>

<div class="space-y-6">
	<div class="flex items-center justify-between">
		<div>
			<h1 class="text-2xl font-bold">消息中心</h1>
			<p class="text-muted-foreground">与其他用户的私信会话</p>
		</div>
		<Button onclick={() => newConversationOpen = true}>
			<svg class="mr-2 h-4 w-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
				<path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M12 4v16m8-8H4" />
			</svg>
			发起会话
		</Button>
	</div>

	{#if loading}
		<Card.Root>
			<Card.Content class="flex items-center justify-center p-12">
				<div class="h-8 w-8 animate-spin rounded-full border-4 border-primary border-t-transparent"></div>
			</Card.Content>
		</Card.Root>
	{:else if conversations.length === 0}
		<Card.Root>
			<Card.Content class="p-12 text-center">
				<div class="mx-auto mb-4 flex h-16 w-16 items-center justify-center rounded-full bg-muted">
					<svg class="h-8 w-8 text-muted-foreground" fill="none" stroke="currentColor" viewBox="0 0 24 24">
						<path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M8 12h.01M12 12h.01M16 12h.01M21 12c0 4.418-4.03 8-9 8a9.863 9.863 0 01-4.255-.949L3 20l1.395-3.72C3.512 15.042 3 13.574 3 12c0-4.418 4.03-8 9-8s9 3.582 9 8z" />
					</svg>
				</div>
				<p class="text-muted-foreground">暂无会话</p>
				<p class="mt-2 text-sm text-muted-foreground">开始与其他用户交流</p>
				<Button class="mt-4" onclick={() => newConversationOpen = true}>
					发起会话
				</Button>
			</Card.Content>
		</Card.Root>
	{:else}
		<Card.Root>
			<Card.Content class="p-0">
				<div class="divide-y">
					{#each conversations as conv (conv.id)}
						<div
							class="flex cursor-pointer items-center gap-4 p-4 transition-colors hover:bg-muted/50"
							onclick={() => goto(`/messages/${conv.id}`)}
							role="button"
							tabindex="0"
							onkeypress={(e) => e.key === 'Enter' && goto(`/messages/${conv.id}`)}
						>
							<!-- Avatar -->
							<div class="relative">
								<div class="flex h-12 w-12 items-center justify-center rounded-full bg-primary/10 text-lg font-medium text-primary">
									{getAvatarText(conv)}
								</div>
								{#if conv.unreadCount > 0}
									<span class="absolute -right-1 -top-1 flex h-5 w-5 items-center justify-center rounded-full bg-destructive text-xs text-white">
										{conv.unreadCount > 9 ? '9+' : conv.unreadCount}
									</span>
								{/if}
							</div>

							<!-- Content -->
							<div class="min-w-0 flex-1">
								<div class="flex items-center justify-between">
									<p class="font-medium">{getDisplayName(conv)}</p>
									{#if conv.lastMessageTime}
										<span class="text-xs text-muted-foreground">
											{formatRelativeTime(conv.lastMessageTime)}
										</span>
									{/if}
								</div>
								{#if conv.lastMessageContent}
									<p class="mt-1 truncate text-sm text-muted-foreground">
										{conv.lastMessageContent}
									</p>
								{/if}
							</div>

							<!-- Actions -->
							<button
								class="rounded p-2 text-muted-foreground opacity-0 transition-opacity hover:bg-accent hover:text-destructive group-hover:opacity-100"
								onclick={(e) => handleDelete(conv, e)}
								title="删除会话"
							>
								<svg class="h-4 w-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
									<path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M19 7l-.867 12.142A2 2 0 0116.138 21H7.862a2 2 0 01-1.995-1.858L5 7m5 4v6m4-6v6m1-10V4a1 1 0 00-1-1h-4a1 1 0 00-1 1v3M4 7h16" />
								</svg>
							</button>
						</div>
					{/each}
				</div>
			</Card.Content>
		</Card.Root>

		<!-- Pagination -->
		{#if total > pageSize}
			<div class="flex items-center justify-between">
				<p class="text-sm text-muted-foreground">共 {total} 个会话</p>
				<div class="flex gap-2">
					<Button
						variant="outline"
						size="sm"
						disabled={page <= 1}
						onclick={() => { page--; loadConversations(); }}
					>
						上一页
					</Button>
					<Button
						variant="outline"
						size="sm"
						disabled={page >= Math.ceil(total / pageSize)}
						onclick={() => { page++; loadConversations(); }}
					>
						下一页
					</Button>
				</div>
			</div>
		{/if}
	{/if}
</div>

<!-- New Conversation Dialog -->
<Dialog.Root bind:open={newConversationOpen}>
	<Dialog.Content class="sm:max-w-md">
		<Dialog.Header>
			<Dialog.Title>发起新会话</Dialog.Title>
			<Dialog.Description>
				输入对方的用户名开始对话
			</Dialog.Description>
		</Dialog.Header>

		<div class="space-y-4">
			<div>
				<label for="new-receiver-username" class="mb-2 block text-sm font-medium">用户名</label>
				<Input
					id="new-receiver-username"
					bind:value={newReceiverUsername}
					placeholder="请输入用户名"
					onkeypress={(e) => e.key === 'Enter' && handleStartConversation()}
				/>
			</div>
		</div>

		<Dialog.Footer>
			<Button variant="outline" onclick={() => newConversationOpen = false}>
				取消
			</Button>
			<Button onclick={handleStartConversation}>
				开始对话
			</Button>
		</Dialog.Footer>
	</Dialog.Content>
</Dialog.Root>
