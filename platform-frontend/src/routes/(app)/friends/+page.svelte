<script lang="ts">
	import { onMount } from 'svelte';
	import { goto } from '$app/navigation';
	import { useNotifications } from '$stores/notifications.svelte';
	import { formatRelativeTime } from '$utils/format';
	import { getAvatarUrl } from '$utils/avatar';
	import {
		getFriends,
		unfriend,
		updateFriendRemark,
		searchUsers,
		sendFriendRequest
	} from '$api/endpoints/friends';
	import type { FriendVO, UserSearchVO } from '$api/types';
	import * as Card from '$lib/components/ui/card';
	import { Button } from '$lib/components/ui/button';
	import * as Dialog from '$lib/components/ui/dialog';
	import { Input } from '$lib/components/ui/input';
	import * as Avatar from '$lib/components/ui/avatar';

	const notifications = useNotifications();

	let friends = $state<FriendVO[]>([]);
	let loading = $state(true);
	let page = $state(1);
	let total = $state(0);
	let pageSize = $state(20);

	// Add friend dialog
	let addFriendOpen = $state(false);
	let searchKeyword = $state('');
	let searchResults = $state<UserSearchVO[]>([]);
	let searching = $state(false);
	let requestMessage = $state('');
	let selectedUser = $state<UserSearchVO | null>(null);

	// Edit remark dialog
	let editRemarkOpen = $state(false);
	let editingFriend = $state<FriendVO | null>(null);
	let newRemark = $state('');

	onMount(() => {
		loadFriends();
	});

	async function loadFriends() {
		loading = true;
		try {
			const result = await getFriends({ pageNum: page, pageSize });
			friends = result.records;
			total = result.total;
		} catch (err) {
			notifications.error('加载失败', err instanceof Error ? err.message : '请稍后重试');
		} finally {
			loading = false;
		}
	}

	async function handleSearch() {
		if (!searchKeyword.trim()) {
			searchResults = [];
			return;
		}
		searching = true;
		try {
			searchResults = await searchUsers(searchKeyword.trim());
		} catch (err) {
			notifications.error('搜索失败', err instanceof Error ? err.message : '请稍后重试');
		} finally {
			searching = false;
		}
	}

	async function handleSendRequest() {
		if (!selectedUser) return;
		try {
			await sendFriendRequest({
				addresseeId: selectedUser.id,
				message: requestMessage || undefined
			});
			notifications.success('好友请求已发送');
			addFriendOpen = false;
			selectedUser = null;
			requestMessage = '';
			searchKeyword = '';
			searchResults = [];
		} catch (err) {
			notifications.error('发送失败', err instanceof Error ? err.message : '请稍后重试');
		}
	}

	async function handleUnfriend(friend: FriendVO) {
		if (!confirm(`确定要删除好友 "${friend.remark || friend.nickname || friend.username}" 吗？`)) return;
		try {
			await unfriend(friend.id);
			notifications.success('已删除好友');
			await loadFriends();
		} catch (err) {
			notifications.error('删除失败', err instanceof Error ? err.message : '请稍后重试');
		}
	}

	function openEditRemark(friend: FriendVO) {
		editingFriend = friend;
		newRemark = friend.remark || '';
		editRemarkOpen = true;
	}

	async function handleSaveRemark() {
		if (!editingFriend) return;
		try {
			await updateFriendRemark(editingFriend.id, { remark: newRemark || undefined });
			notifications.success('备注已更新');
			editRemarkOpen = false;
			await loadFriends();
		} catch (err) {
			notifications.error('更新失败', err instanceof Error ? err.message : '请稍后重试');
		}
	}

	function startChat(friend: FriendVO) {
		/**
		 * 发起与指定好友的新会话。
		 * 通过 URL 参数传递好友的用户ID（FriendVO.id），确保新会话页能从好友列表中正确匹配到接收者。
		 */
		goto(`/messages/new?to=${encodeURIComponent(friend.id)}`);
	}

	function getDisplayName(friend: FriendVO): string {
		return friend.remark || friend.nickname || friend.username;
	}
</script>

<svelte:head>
	<title>好友列表 - 存证平台</title>
</svelte:head>

<div class="space-y-6">
	<div class="flex items-center justify-between">
		<div>
			<h1 class="text-2xl font-bold">好友列表</h1>
			<p class="text-muted-foreground">管理你的好友关系</p>
		</div>
		<div class="flex gap-2">
			<Button variant="outline" onclick={() => goto('/friends/requests')}>
				好友请求
			</Button>
			<Button variant="outline" onclick={() => goto('/friends/shares')}>
				好友分享
			</Button>
			<Button onclick={() => addFriendOpen = true}>
				<svg class="mr-2 h-4 w-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
					<path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M18 9v3m0 0v3m0-3h3m-3 0h-3m-2-5a4 4 0 11-8 0 4 4 0 018 0zM3 20a6 6 0 0112 0v1H3v-1z" />
				</svg>
				添加好友
			</Button>
		</div>
	</div>

	{#if loading}
		<Card.Root>
			<Card.Content class="flex items-center justify-center p-12">
				<div class="h-8 w-8 animate-spin rounded-full border-4 border-primary border-t-transparent"></div>
			</Card.Content>
		</Card.Root>
	{:else if friends.length === 0}
		<Card.Root>
			<Card.Content class="flex flex-col items-center justify-center p-12 text-center">
				<svg class="mb-4 h-12 w-12 text-muted-foreground" fill="none" stroke="currentColor" viewBox="0 0 24 24">
					<path stroke-linecap="round" stroke-linejoin="round" stroke-width="1.5" d="M17 20h5v-2a3 3 0 00-5.356-1.857M17 20H7m10 0v-2c0-.656-.126-1.283-.356-1.857M7 20H2v-2a3 3 0 015.356-1.857M7 20v-2c0-.656.126-1.283.356-1.857m0 0a5.002 5.002 0 019.288 0M15 7a3 3 0 11-6 0 3 3 0 016 0zm6 3a2 2 0 11-4 0 2 2 0 014 0zM7 10a2 2 0 11-4 0 2 2 0 014 0z" />
				</svg>
				<h3 class="text-lg font-medium">暂无好友</h3>
				<p class="mt-1 text-sm text-muted-foreground">点击右上角添加好友开始社交吧</p>
			</Card.Content>
		</Card.Root>
	{:else}
		<div class="grid gap-4 md:grid-cols-2 lg:grid-cols-3">
			{#each friends as friend}
				<Card.Root class="hover:shadow-md transition-shadow">
					<Card.Content class="p-4">
						<div class="flex items-start gap-4">
								<Avatar.Root class="h-12 w-12">
									{#if friend.avatar}
										<Avatar.Image src={getAvatarUrl(friend.avatar)} alt={friend.username} />
									{/if}
									<Avatar.Fallback>{friend.username.charAt(0).toUpperCase()}</Avatar.Fallback>
								</Avatar.Root>
							<div class="flex-1 min-w-0">
								<div class="flex items-center gap-2">
									<h3 class="font-medium truncate">{getDisplayName(friend)}</h3>
									{#if friend.remark && friend.remark !== friend.username}
										<span class="text-xs text-muted-foreground">@{friend.username}</span>
									{/if}
								</div>
								{#if friend.nickname && friend.nickname !== friend.username}
									<p class="text-sm text-muted-foreground truncate">{friend.nickname}</p>
								{/if}
								<p class="text-xs text-muted-foreground mt-1">
									好友 {formatRelativeTime(friend.friendSince)}
								</p>
							</div>
						</div>
						<div class="flex gap-2 mt-4">
							<Button size="sm" variant="outline" class="flex-1" onclick={() => startChat(friend)}>
								发消息
							</Button>
							<Button size="sm" variant="ghost" onclick={() => openEditRemark(friend)}>
								备注
							</Button>
							<Button size="sm" variant="ghost" class="text-destructive" onclick={() => handleUnfriend(friend)}>
								删除
							</Button>
						</div>
					</Card.Content>
				</Card.Root>
			{/each}
		</div>

		{#if total > pageSize}
			<div class="flex justify-center gap-2">
				<Button
					variant="outline"
					disabled={page <= 1}
					onclick={() => { page--; loadFriends(); }}
				>
					上一页
				</Button>
				<span class="flex items-center px-4 text-sm text-muted-foreground">
					{page} / {Math.ceil(total / pageSize)}
				</span>
				<Button
					variant="outline"
					disabled={page >= Math.ceil(total / pageSize)}
					onclick={() => { page++; loadFriends(); }}
				>
					下一页
				</Button>
			</div>
		{/if}
	{/if}
</div>

<!-- Add Friend Dialog -->
<Dialog.Root bind:open={addFriendOpen}>
	<Dialog.Content class="sm:max-w-md">
		<Dialog.Header>
			<Dialog.Title>添加好友</Dialog.Title>
			<Dialog.Description>搜索用户名或昵称</Dialog.Description>
		</Dialog.Header>
		<div class="space-y-4">
			<div class="flex gap-2">
				<Input
					bind:value={searchKeyword}
					placeholder="输入用户名或昵称"
					onkeydown={(e) => e.key === 'Enter' && handleSearch()}
				/>
				<Button onclick={handleSearch} disabled={searching}>
					{searching ? '搜索中...' : '搜索'}
				</Button>
			</div>

			{#if searchResults.length > 0}
				<div class="max-h-60 overflow-y-auto space-y-2">
					{#each searchResults as user}
						<button
							class="w-full flex items-center gap-3 p-3 rounded-lg border hover:bg-muted/50 transition-colors {selectedUser?.id === user.id ? 'border-primary bg-muted/50' : ''}"
							onclick={() => selectedUser = user}
						>
								<Avatar.Root class="h-10 w-10">
									{#if user.avatar}
										<Avatar.Image src={getAvatarUrl(user.avatar)} alt={user.username} />
									{/if}
									<Avatar.Fallback>{user.username.charAt(0).toUpperCase()}</Avatar.Fallback>
								</Avatar.Root>
							<div class="flex-1 text-left">
								<div class="font-medium">{user.nickname || user.username}</div>
								{#if user.nickname}
									<div class="text-xs text-muted-foreground">@{user.username}</div>
								{/if}
							</div>
							{#if user.isFriend}
								<span class="text-xs text-green-600">已是好友</span>
							{:else if user.hasPendingRequest}
								<span class="text-xs text-yellow-600">请求中</span>
							{/if}
						</button>
					{/each}
				</div>
			{/if}

			{#if selectedUser && !selectedUser.isFriend && !selectedUser.hasPendingRequest}
				<div class="space-y-2">
					<label for="friend-request-message" class="text-sm font-medium">验证消息（可选）</label>
					<Input
						id="friend-request-message"
						bind:value={requestMessage}
						placeholder="请输入验证消息"
						maxlength={255}
					/>
				</div>
			{/if}
		</div>
		<Dialog.Footer>
			<Button variant="outline" onclick={() => addFriendOpen = false}>取消</Button>
			<Button
				onclick={handleSendRequest}
				disabled={!selectedUser || selectedUser.isFriend || selectedUser.hasPendingRequest}
			>
				发送请求
			</Button>
		</Dialog.Footer>
	</Dialog.Content>
</Dialog.Root>

<!-- Edit Remark Dialog -->
<Dialog.Root bind:open={editRemarkOpen}>
	<Dialog.Content class="sm:max-w-md">
		<Dialog.Header>
			<Dialog.Title>修改备注</Dialog.Title>
			<Dialog.Description>
				为好友 {editingFriend?.nickname || editingFriend?.username} 设置备注
			</Dialog.Description>
		</Dialog.Header>
		<div class="space-y-4">
			<Input
				bind:value={newRemark}
				placeholder="输入备注名"
				maxlength={50}
			/>
		</div>
		<Dialog.Footer>
			<Button variant="outline" onclick={() => editRemarkOpen = false}>取消</Button>
			<Button onclick={handleSaveRemark}>保存</Button>
		</Dialog.Footer>
	</Dialog.Content>
</Dialog.Root>
