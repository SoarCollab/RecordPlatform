<script lang="ts">
	import { onMount } from "svelte";
	import { goto } from "$app/navigation";
	import { useNotifications } from "$stores/notifications.svelte";
	import { getAllFriends } from "$api/endpoints/friends";
	import { getConversations, sendMessage } from "$api/endpoints/messages";
	import type { FriendVO } from "$api/types";
	import { Button } from "$lib/components/ui/button";
	import { Textarea } from "$lib/components/ui/textarea";
	import * as Card from "$lib/components/ui/card";

	let { data } = $props();

	const notifications = useNotifications();

	let loading = $state(true);
	let error = $state<string | null>(null);
	let receiver = $state<FriendVO | null>(null);
	let content = $state("");
	let sending = $state(false);

	onMount(async () => {
		if (!data.receiverId) {
			await goto("/messages");
			return;
		}

		loading = true;
		error = null;
		try {
			const friends = await getAllFriends();
			receiver = friends.find((f) => f.id === data.receiverId) || null;
			if (!receiver) {
				throw new Error("接收者不存在或不在好友列表中");
			}
		} catch (err) {
			error = err instanceof Error ? err.message : "加载失败";
			notifications.error("加载失败", error);
		} finally {
			loading = false;
		}
	});

	function getDisplayName(friend: FriendVO): string {
		return friend.remark || friend.nickname || friend.username;
	}

	async function afterSendRedirect() {
		const pageSize = 50;
		for (let pageNum = 1; pageNum <= 10; pageNum++) {
			const result = await getConversations({ pageNum, pageSize });
			const match = result.records.find((c) => c.otherUserId === data.receiverId);
			if (match) {
				await goto(`/messages/${match.id}`, { replaceState: true });
				return;
			}
			if (pageNum >= result.pages) break;
		}
		await goto("/messages", { replaceState: true });
	}

	async function handleSend() {
		if (!receiver) return;
		const trimmed = content.trim();
		if (!trimmed) {
			notifications.warning("请输入消息内容");
			return;
		}
		sending = true;
		try {
			await sendMessage({ receiverId: receiver.id, content: trimmed });
			await afterSendRedirect();
		} catch (err) {
			notifications.error(
				"发送失败",
				err instanceof Error ? err.message : "请稍后重试",
			);
		} finally {
			sending = false;
		}
	}
</script>

<svelte:head>
	<title>新会话 - 存证平台</title>
</svelte:head>

<div class="mx-auto max-w-lg space-y-4">
	{#if loading}
		<Card.Root>
			<Card.Content class="flex flex-col items-center justify-center p-12">
				<div class="h-8 w-8 animate-spin rounded-full border-4 border-primary border-t-transparent"></div>
				<p class="mt-4 text-muted-foreground">正在加载...</p>
			</Card.Content>
		</Card.Root>
	{:else if error}
		<Card.Root>
			<Card.Content class="p-12 text-center">
				<p class="font-medium">打开会话失败</p>
				<p class="mt-1 text-muted-foreground">{error}</p>
				<p class="mt-4">
					<a href="/messages" class="text-primary hover:underline">返回消息列表</a>
				</p>
			</Card.Content>
		</Card.Root>
	{:else if receiver}
		<Card.Root>
			<Card.Content class="space-y-4 p-6">
				<div>
					<p class="text-sm text-muted-foreground">发起会话</p>
					<p class="text-lg font-semibold">发送给 {getDisplayName(receiver)}</p>
					<p class="text-xs text-muted-foreground">@{receiver.username}</p>
				</div>
				<Textarea
					rows={4}
					placeholder="输入第一条消息..."
					bind:value={content}
					onkeydown={(e) => {
						if ((e.ctrlKey || e.metaKey) && e.key === "Enter") {
							e.preventDefault();
							handleSend();
						}
					}}
				/>
				<div class="flex justify-end gap-2">
					<Button variant="outline" onclick={() => goto("/messages")}>取消</Button>
					<Button onclick={handleSend} disabled={sending}>
						{#if sending}
							<div class="mr-2 h-4 w-4 animate-spin rounded-full border-2 border-current border-t-transparent"></div>
						{/if}
						发送
					</Button>
				</div>
			</Card.Content>
		</Card.Root>
	{/if}
</div>
