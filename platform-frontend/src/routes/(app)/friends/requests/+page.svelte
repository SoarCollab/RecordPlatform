<script lang="ts">
	import { onMount } from 'svelte';
	import { useNotifications } from '$stores/notifications.svelte';
	import { formatRelativeTime } from '$utils/format';
	import { getAvatarUrl } from '$utils/avatar';
	import {
		getReceivedRequests,
		getSentRequests,
		acceptFriendRequest,
		rejectFriendRequest,
		cancelFriendRequest
	} from '$api/endpoints/friends';
	import { FriendRequestStatus, type FriendRequestDetailVO } from '$api/types';
	import * as Card from '$lib/components/ui/card';
	import { Button } from '$lib/components/ui/button';
	import * as Tabs from '$lib/components/ui/tabs';
	import * as Avatar from '$lib/components/ui/avatar';

	const notifications = useNotifications();

	let activeTab = $state('received');
	let receivedRequests = $state<FriendRequestDetailVO[]>([]);
	let sentRequests = $state<FriendRequestDetailVO[]>([]);
	let loading = $state(true);
	let page = $state(1);
	let total = $state(0);
	let pageSize = $state(20);

	onMount(() => {
		loadRequests();
	});

	async function loadRequests() {
		loading = true;
		try {
			if (activeTab === 'received') {
				const result = await getReceivedRequests({ pageNum: page, pageSize });
				receivedRequests = result.records;
				total = result.total;
			} else {
				const result = await getSentRequests({ pageNum: page, pageSize });
				sentRequests = result.records;
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
		loadRequests();
	}

	async function handleAccept(request: FriendRequestDetailVO) {
		try {
			await acceptFriendRequest(request.id);
			notifications.success('已添加好友');
			await loadRequests();
		} catch (err) {
			notifications.error('操作失败', err instanceof Error ? err.message : '请稍后重试');
		}
	}

	async function handleReject(request: FriendRequestDetailVO) {
		try {
			await rejectFriendRequest(request.id);
			notifications.success('已拒绝请求');
			await loadRequests();
		} catch (err) {
			notifications.error('操作失败', err instanceof Error ? err.message : '请稍后重试');
		}
	}

	async function handleCancel(request: FriendRequestDetailVO) {
		if (!confirm('确定要取消这个好友请求吗？')) return;
		try {
			await cancelFriendRequest(request.id);
			notifications.success('已取消请求');
			await loadRequests();
		} catch (err) {
			notifications.error('操作失败', err instanceof Error ? err.message : '请稍后重试');
		}
	}

	function getStatusText(status: FriendRequestStatus): string {
		switch (status) {
			case FriendRequestStatus.PENDING: return '待处理';
			case FriendRequestStatus.ACCEPTED: return '已接受';
			case FriendRequestStatus.REJECTED: return '已拒绝';
			case FriendRequestStatus.CANCELLED: return '已取消';
			default: return '未知';
		}
	}

	function getStatusClass(status: FriendRequestStatus): string {
		switch (status) {
			case FriendRequestStatus.PENDING: return 'text-yellow-600 bg-yellow-50';
			case FriendRequestStatus.ACCEPTED: return 'text-green-600 bg-green-50';
			case FriendRequestStatus.REJECTED: return 'text-red-600 bg-red-50';
			case FriendRequestStatus.CANCELLED: return 'text-gray-600 bg-gray-50';
			default: return 'text-gray-600 bg-gray-50';
		}
	}

	const currentRequests = $derived(activeTab === 'received' ? receivedRequests : sentRequests);
</script>

<svelte:head>
	<title>好友请求 - 存证平台</title>
</svelte:head>

<div class="space-y-6">
	<div class="flex items-center justify-between">
		<div>
			<h1 class="text-2xl font-bold">好友请求</h1>
			<p class="text-muted-foreground">管理好友请求</p>
		</div>
		<Button variant="outline" onclick={() => history.back()}>
			返回
		</Button>
	</div>

	<Tabs.Root value={activeTab} onValueChange={handleTabChange}>
		<Tabs.List>
			<Tabs.Trigger value="received">收到的请求</Tabs.Trigger>
			<Tabs.Trigger value="sent">发送的请求</Tabs.Trigger>
		</Tabs.List>

		<div class="mt-4">
			{#if loading}
				<Card.Root>
					<Card.Content class="flex items-center justify-center p-12">
						<div class="h-8 w-8 animate-spin rounded-full border-4 border-primary border-t-transparent"></div>
					</Card.Content>
				</Card.Root>
			{:else if currentRequests.length === 0}
				<Card.Root>
					<Card.Content class="flex flex-col items-center justify-center p-12 text-center">
						<svg class="mb-4 h-12 w-12 text-muted-foreground" fill="none" stroke="currentColor" viewBox="0 0 24 24">
							<path stroke-linecap="round" stroke-linejoin="round" stroke-width="1.5" d="M20 13V6a2 2 0 00-2-2H6a2 2 0 00-2 2v7m16 0v5a2 2 0 01-2 2H6a2 2 0 01-2-2v-5m16 0h-2.586a1 1 0 00-.707.293l-2.414 2.414a1 1 0 01-.707.293h-3.172a1 1 0 01-.707-.293l-2.414-2.414A1 1 0 006.586 13H4" />
						</svg>
						<h3 class="text-lg font-medium">暂无请求</h3>
						<p class="mt-1 text-sm text-muted-foreground">
							{activeTab === 'received' ? '还没有收到好友请求' : '还没有发送过好友请求'}
						</p>
					</Card.Content>
				</Card.Root>
			{:else}
				<div class="space-y-4">
					{#each currentRequests as request}
						<Card.Root>
							<Card.Content class="p-4">
								<div class="flex items-center gap-4">
									<Avatar.Root class="h-12 w-12">
										{#if activeTab === 'received'}
											{#if request.requesterAvatar}
												<Avatar.Image src={getAvatarUrl(request.requesterAvatar)} alt={request.requesterUsername} />
											{/if}
											<Avatar.Fallback>{request.requesterUsername?.charAt(0).toUpperCase() || '?'}</Avatar.Fallback>
										{:else}
											{#if request.addresseeAvatar}
												<Avatar.Image src={getAvatarUrl(request.addresseeAvatar)} alt={request.addresseeUsername} />
											{/if}
											<Avatar.Fallback>{request.addresseeUsername?.charAt(0).toUpperCase() || '?'}</Avatar.Fallback>
										{/if}
									</Avatar.Root>
									<div class="flex-1 min-w-0">
										<div class="flex items-center gap-2">
											<h3 class="font-medium">
												{activeTab === 'received' ? request.requesterUsername : request.addresseeUsername}
											</h3>
											<span class="text-xs px-2 py-0.5 rounded {getStatusClass(request.status)}">
												{getStatusText(request.status)}
											</span>
										</div>
										{#if request.message}
											<p class="text-sm text-muted-foreground mt-1 truncate">
												{request.message}
											</p>
										{/if}
										<p class="text-xs text-muted-foreground mt-1">
											{formatRelativeTime(request.createTime)}
										</p>
									</div>
									{#if request.status === FriendRequestStatus.PENDING}
										<div class="flex gap-2">
											{#if activeTab === 'received'}
												<Button size="sm" onclick={() => handleAccept(request)}>
													接受
												</Button>
												<Button size="sm" variant="outline" onclick={() => handleReject(request)}>
													拒绝
												</Button>
											{:else}
												<Button size="sm" variant="outline" onclick={() => handleCancel(request)}>
													取消
												</Button>
											{/if}
										</div>
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
							onclick={() => { page--; loadRequests(); }}
						>
							上一页
						</Button>
						<span class="flex items-center px-4 text-sm text-muted-foreground">
							{page} / {Math.ceil(total / pageSize)}
						</span>
						<Button
							variant="outline"
							disabled={page >= Math.ceil(total / pageSize)}
							onclick={() => { page++; loadRequests(); }}
						>
							下一页
						</Button>
					</div>
				{/if}
			{/if}
		</div>
	</Tabs.Root>
</div>
