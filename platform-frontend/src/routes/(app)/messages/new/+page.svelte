<script lang="ts">
	import { onMount } from 'svelte';
	import { goto } from '$app/navigation';
	import { useNotifications } from '$stores/notifications.svelte';
	import { getOrCreateConversation } from '$api/endpoints/messages';
	import * as Card from '$lib/components/ui/card';

	let { data } = $props();

	const notifications = useNotifications();

	let loading = $state(true);
	let error = $state<string | null>(null);

	onMount(async () => {
		if (!data.receiverUsername) {
			await goto('/messages');
			return;
		}

		try {
			// Get or create conversation with the specified user
			const conversation = await getOrCreateConversation(data.receiverUsername);
			// Redirect to the conversation
			await goto(`/messages/${conversation.id}`, { replaceState: true });
		} catch (err) {
			error = err instanceof Error ? err.message : '创建会话失败';
			notifications.error('创建会话失败', error);
			loading = false;
		}
	});
</script>

<svelte:head>
	<title>新会话 - 存证平台</title>
</svelte:head>

<div class="mx-auto max-w-md">
	{#if loading && !error}
		<Card.Root>
			<Card.Content class="flex flex-col items-center justify-center p-12">
				<div class="h-8 w-8 animate-spin rounded-full border-4 border-primary border-t-transparent"></div>
				<p class="mt-4 text-muted-foreground">正在创建会话...</p>
			</Card.Content>
		</Card.Root>
	{:else if error}
		<Card.Root>
			<Card.Content class="p-12 text-center">
				<div class="mx-auto mb-4 flex h-16 w-16 items-center justify-center rounded-full bg-destructive/10">
					<svg class="h-8 w-8 text-destructive" fill="none" stroke="currentColor" viewBox="0 0 24 24">
						<path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M12 9v2m0 4h.01m-6.938 4h13.856c1.54 0 2.502-1.667 1.732-3L13.732 4c-.77-1.333-2.694-1.333-3.464 0L3.34 16c-.77 1.333.192 3 1.732 3z" />
					</svg>
				</div>
				<p class="font-medium">创建会话失败</p>
				<p class="mt-1 text-muted-foreground">{error}</p>
				<p class="mt-4">
					<a href="/messages" class="text-primary hover:underline">返回消息列表</a>
				</p>
			</Card.Content>
		</Card.Root>
	{/if}
</div>
