<script lang="ts">
	import { onMount } from 'svelte';
	import { goto } from '$app/navigation';
	import { useNotifications } from '$stores/notifications.svelte';
	import { formatRelativeTime } from '$utils/format';
	import { getAvatarUrl } from '$utils/avatar';
	import {
		getReceivedFriendShares,
		getSentFriendShares,
		markFriendShareAsRead,
		cancelFriendShare
	} from '$api/endpoints/friends';
	import type { FriendFileShareDetailVO } from '$api/types';
	import * as Card from '$lib/components/ui/card';
	import { Button } from '$lib/components/ui/button';
	import * as Tabs from '$lib/components/ui/tabs';
	import * as Avatar from '$lib/components/ui/avatar';

	const notifications = useNotifications();

	let activeTab = $state('received');
	let receivedShares = $state<FriendFileShareDetailVO[]>([]);
	let sentShares = $state<FriendFileShareDetailVO[]>([]);
	let loading = $state(true);
	let page = $state(1);
	let total = $state(0);
	let pageSize = $state(20);

	onMount(() => {
		loadShares();
	});

	async function loadShares() {
		loading = true;
		try {
			if (activeTab === 'received') {
				const result = await getReceivedFriendShares({ pageNum: page, pageSize });
				receivedShares = result.records;
				total = result.total;
			} else {
				const result = await getSentFriendShares({ pageNum: page, pageSize });
				sentShares = result.records;
				total = result.total;
			}
		} catch (err) {
			notifications.error('加载失败', err instanceof Error ? err.message : '请稍后重试');
		} finally {
			loading = false;
		}
	}

	function handleTabChange(value: string) {
		activeTab = value;
		page = 1;
		loadShares();
	}

	async function handleMarkAsRead(share: FriendFileShareDetailVO) {
		try {
			await markFriendShareAsRead(share.id);
			share.isRead = true;
			receivedShares = [...receivedShares];
		} catch (err) {
			notifications.error('操作失败', err instanceof Error ? err.message : '请稍后重试');
		}
	}

	async function handleCancel(share: FriendFileShareDetailVO) {
		if (!confirm('确定要取消这个分享吗？')) return;
		try {
			await cancelFriendShare(share.id);
			notifications.success('已取消分享');
			await loadShares();
		} catch (err) {
			notifications.error('操作失败', err instanceof Error ? err.message : '请稍后重试');
		}
	}

	function viewFile(fileHash: string) {
		goto(`/files/${fileHash}`);
	}

	const currentShares = $derived(activeTab === 'received' ? receivedShares : sentShares);
</script>

<svelte:head>
	<title>好友分享 - 存证平台</title>
</svelte:head>

<div class="space-y-6">
	<div class="flex items-center justify-between">
		<div>
			<h1 class="text-2xl font-bold">好友分享</h1>
			<p class="text-muted-foreground">好友直接分享给你的文件</p>
		</div>
		<Button variant="outline" onclick={() => history.back()}>
			返回
		</Button>
	</div>

	<Tabs.Root value={activeTab} onValueChange={handleTabChange}>
		<Tabs.List>
			<Tabs.Trigger value="received">收到的分享</Tabs.Trigger>
			<Tabs.Trigger value="sent">发送的分享</Tabs.Trigger>
		</Tabs.List>

		<div class="mt-4">
			{#if loading}
				<Card.Root>
					<Card.Content class="flex items-center justify-center p-12">
						<div class="h-8 w-8 animate-spin rounded-full border-4 border-primary border-t-transparent"></div>
					</Card.Content>
				</Card.Root>
			{:else if currentShares.length === 0}
				<Card.Root>
					<Card.Content class="flex flex-col items-center justify-center p-12 text-center">
						<svg class="mb-4 h-12 w-12 text-muted-foreground" fill="none" stroke="currentColor" viewBox="0 0 24 24">
							<path stroke-linecap="round" stroke-linejoin="round" stroke-width="1.5" d="M8.684 13.342C8.886 12.938 9 12.482 9 12c0-.482-.114-.938-.316-1.342m0 2.684a3 3 0 110-2.684m0 2.684l6.632 3.316m-6.632-6l6.632-3.316m0 0a3 3 0 105.367-2.684 3 3 0 00-5.367 2.684zm0 9.316a3 3 0 105.367 2.684 3 3 0 00-5.367-2.684z" />
						</svg>
						<h3 class="text-lg font-medium">暂无分享</h3>
						<p class="mt-1 text-sm text-muted-foreground">
							{activeTab === 'received' ? '还没有好友分享文件给你' : '还没有分享过文件给好友'}
						</p>
					</Card.Content>
				</Card.Root>
			{:else}
				<div class="space-y-4">
					{#each currentShares as share}
						<Card.Root class={!share.isRead && activeTab === 'received' ? 'border-primary/50 bg-primary/5' : ''}>
							<Card.Content class="p-4">
								<div class="flex items-start gap-4">
									<Avatar.Root class="h-12 w-12">
										{#if activeTab === 'received'}
											{#if share.sharerAvatar}
												<Avatar.Image src={getAvatarUrl(share.sharerAvatar)} alt={share.sharerUsername} />
											{/if}
											<Avatar.Fallback>{share.sharerUsername?.charAt(0).toUpperCase() || '?'}</Avatar.Fallback>
										{:else}
											<Avatar.Fallback>
												<svg class="h-6 w-6" fill="none" stroke="currentColor" viewBox="0 0 24 24">
													<path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M16 7a4 4 0 11-8 0 4 4 0 018 0zM12 14a7 7 0 00-7 7h14a7 7 0 00-7-7z" />
												</svg>
											</Avatar.Fallback>
										{/if}
									</Avatar.Root>
									<div class="flex-1 min-w-0">
										<div class="flex items-center gap-2">
											<h3 class="font-medium">
												{activeTab === 'received' ? share.sharerUsername : share.friendUsername || '好友'}
											</h3>
											{#if !share.isRead && activeTab === 'received'}
												<span class="text-xs px-2 py-0.5 rounded bg-primary text-primary-foreground">
													未读
												</span>
											{/if}
										</div>
										{#if share.message}
											<p class="text-sm text-muted-foreground mt-1">
												"{share.message}"
											</p>
										{/if}
										<p class="text-xs text-muted-foreground mt-1">
											{formatRelativeTime(share.createTime)} · {share.fileCount} 个文件
										</p>
									</div>
								</div>

								<!-- 文件列表 -->
								<div class="mt-4 space-y-2">
									{#each share.fileNames.slice(0, 5) as fileName, i}
										<button
											class="w-full flex items-center gap-3 p-2 rounded border hover:bg-muted/50 transition-colors text-left"
											onclick={() => viewFile(share.fileHashes[i])}
										>
											<svg class="h-5 w-5 text-muted-foreground flex-shrink-0" fill="none" stroke="currentColor" viewBox="0 0 24 24">
												<path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M9 12h6m-6 4h6m2 5H7a2 2 0 01-2-2V5a2 2 0 012-2h5.586a1 1 0 01.707.293l5.414 5.414a1 1 0 01.293.707V19a2 2 0 01-2 2z" />
											</svg>
											<span class="flex-1 truncate text-sm">{fileName}</span>
											<svg class="h-4 w-4 text-muted-foreground flex-shrink-0" fill="none" stroke="currentColor" viewBox="0 0 24 24">
												<path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M9 5l7 7-7 7" />
											</svg>
										</button>
									{/each}
									{#if share.fileCount > 5}
										<p class="text-xs text-muted-foreground text-center">
											还有 {share.fileCount - 5} 个文件...
										</p>
									{/if}
								</div>

								<div class="flex gap-2 mt-4">
									{#if activeTab === 'received' && !share.isRead}
										<Button size="sm" variant="outline" onclick={() => handleMarkAsRead(share)}>
											标记已读
										</Button>
									{/if}
									{#if activeTab === 'sent'}
										<Button size="sm" variant="outline" class="text-destructive" onclick={() => handleCancel(share)}>
											取消分享
										</Button>
									{/if}
								</div>
							</Card.Content>
						</Card.Root>
					{/each}
				</div>

				{#if total > pageSize}
					<div class="flex justify-center gap-2 mt-6">
						<Button
							variant="outline"
							disabled={page <= 1}
							onclick={() => { page--; loadShares(); }}
						>
							上一页
						</Button>
						<span class="flex items-center px-4 text-sm text-muted-foreground">
							{page} / {Math.ceil(total / pageSize)}
						</span>
						<Button
							variant="outline"
							disabled={page >= Math.ceil(total / pageSize)}
							onclick={() => { page++; loadShares(); }}
						>
							下一页
						</Button>
					</div>
				{/if}
			{/if}
		</div>
	</Tabs.Root>
</div>
