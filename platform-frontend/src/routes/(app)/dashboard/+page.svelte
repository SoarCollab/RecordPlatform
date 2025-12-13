<script lang="ts">
	import { onMount } from 'svelte';
	import { useAuth } from '$stores/auth.svelte';
	import { useNotifications } from '$stores/notifications.svelte';
	import { formatDateTime, formatFileSize } from '$utils/format';
	import { getFiles } from '$api/endpoints/files';
	import { getUnreadConversationCount, getUnreadAnnouncementCount } from '$api/endpoints/messages';
	import { getPendingCount as getTicketPendingCount } from '$api/endpoints/tickets';
	import type { FileVO } from '$api/types';
	import { FileStatus, FileStatusLabel } from '$api/types';

	const auth = useAuth();
	const notifications = useNotifications();

	// Dashboard data state
	let loading = $state(true);
	let fileCount = $state(0);
	let storageUsed = $state(0);
	let unreadMessages = $state(0);
	let unreadAnnouncements = $state(0);
	let pendingTickets = $state(0);
	let recentFiles = $state<FileVO[]>([]);

	// Computed stats for display
	const stats = $derived([
		{
			label: '文件总数',
			value: fileCount.toString(),
			icon: 'folder'
		},
		{
			label: '存储用量',
			value: formatFileSize(storageUsed),
			icon: 'database'
		},
		{
			label: '未读消息',
			value: unreadMessages.toString(),
			icon: 'message'
		},
		{
			label: '待处理工单',
			value: pendingTickets.toString(),
			icon: 'ticket'
		}
	]);

	onMount(() => {
		loadDashboardData();
	});

	async function loadDashboardData() {
		loading = true;

		try {
			// Load all data in parallel
			const [filesResult, messagesResult, announcementsResult, ticketsResult] =
				await Promise.allSettled([
					getFiles({ pageNum: 1, pageSize: 100 }),
					getUnreadConversationCount(),
					getUnreadAnnouncementCount(),
					getTicketPendingCount()
				]);

			// Process files result
			if (filesResult.status === 'fulfilled') {
				const files = filesResult.value;
				fileCount = files.total;
				// Calculate storage usage from records
				storageUsed = files.records.reduce((sum, f) => sum + (f.fileSize || 0), 0);
				// Get recent 5 files
				recentFiles = files.records.slice(0, 5);
			}

			// Process message count
			if (messagesResult.status === 'fulfilled') {
				unreadMessages = messagesResult.value.count;
			}

			// Process announcement count
			if (announcementsResult.status === 'fulfilled') {
				unreadAnnouncements = announcementsResult.value.count;
			}

			// Process ticket count
			if (ticketsResult.status === 'fulfilled') {
				pendingTickets = ticketsResult.value.count;
			}
		} catch (err) {
			notifications.error('加载失败', err instanceof Error ? err.message : '请稍后重试');
		} finally {
			loading = false;
		}
	}

	function getStatusVariant(status: FileStatus): 'completed' | 'processing' | 'failed' {
		switch (status) {
			case FileStatus.COMPLETED:
				return 'completed';
			case FileStatus.FAILED:
				return 'failed';
			default:
				return 'processing';
		}
	}
</script>

<svelte:head>
	<title>仪表盘 - 存证平台</title>
</svelte:head>

<div class="space-y-6">
	<!-- Welcome -->
	<div>
		<h1 class="text-2xl font-bold">欢迎回来，{auth.displayName}</h1>
		<p class="text-muted-foreground">这是您的存证平台概览</p>
	</div>

	<!-- Stats -->
	{#if loading}
		<div class="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
			{#each Array(4) as _}
				<div class="animate-pulse rounded-lg border bg-card p-6">
					<div class="h-4 w-24 rounded bg-muted"></div>
					<div class="mt-4 h-8 w-16 rounded bg-muted"></div>
				</div>
			{/each}
		</div>
	{:else}
		<div class="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
			{#each stats as stat}
				<div class="rounded-lg border bg-card p-6">
					<div class="flex items-center justify-between">
						<p class="text-sm text-muted-foreground">{stat.label}</p>
						<svg class="h-5 w-5 text-muted-foreground" fill="none" stroke="currentColor" viewBox="0 0 24 24">
							{#if stat.icon === 'folder'}
								<path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M3 7v10a2 2 0 002 2h14a2 2 0 002-2V9a2 2 0 00-2-2h-6l-2-2H5a2 2 0 00-2 2z" />
							{:else if stat.icon === 'database'}
								<path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M4 7v10c0 2.21 3.582 4 8 4s8-1.79 8-4V7M4 7c0 2.21 3.582 4 8 4s8-1.79 8-4M4 7c0-2.21 3.582-4 8-4s8 1.79 8 4m0 5c0 2.21-3.582 4-8 4s-8-1.79-8-4" />
							{:else if stat.icon === 'message'}
								<path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M8 12h.01M12 12h.01M16 12h.01M21 12c0 4.418-4.03 8-9 8a9.863 9.863 0 01-4.255-.949L3 20l1.395-3.72C3.512 15.042 3 13.574 3 12c0-4.418 4.03-8 9-8s9 3.582 9 8z" />
							{:else if stat.icon === 'ticket'}
								<path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M9 12h6m-6 4h6m2 5H7a2 2 0 01-2-2V5a2 2 0 012-2h5.586a1 1 0 01.707.293l5.414 5.414a1 1 0 01.293.707V19a2 2 0 01-2 2z" />
							{/if}
						</svg>
					</div>
					<div class="mt-2">
						<span class="text-3xl font-bold">{stat.value}</span>
					</div>
				</div>
			{/each}
		</div>
	{/if}

	<!-- Notification Banner (if unread announcements) -->
	{#if unreadAnnouncements > 0}
		<a
			href="/announcements"
			class="flex items-center justify-between rounded-lg border border-amber-200 bg-amber-50 p-4 transition-colors hover:bg-amber-100 dark:border-amber-800 dark:bg-amber-900/20 dark:hover:bg-amber-900/30"
		>
			<div class="flex items-center gap-3">
				<div class="flex h-10 w-10 items-center justify-center rounded-full bg-amber-100 text-amber-600 dark:bg-amber-900 dark:text-amber-400">
					<svg class="h-5 w-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
						<path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M11 5.882V19.24a1.76 1.76 0 01-3.417.592l-2.147-6.15M18 13a3 3 0 100-6M5.436 13.683A4.001 4.001 0 017 6h1.832c4.1 0 7.625-1.234 9.168-3v14c-1.543-1.766-5.067-3-9.168-3H7a3.988 3.988 0 01-1.564-.317z" />
					</svg>
				</div>
				<div>
					<p class="font-medium text-amber-900 dark:text-amber-100">
						您有 {unreadAnnouncements} 条未读公告
					</p>
					<p class="text-sm text-amber-700 dark:text-amber-300">点击查看系统公告</p>
				</div>
			</div>
			<svg class="h-5 w-5 text-amber-600 dark:text-amber-400" fill="none" stroke="currentColor" viewBox="0 0 24 24">
				<path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M9 5l7 7-7 7" />
			</svg>
		</a>
	{/if}

	<!-- Recent files -->
	<div class="rounded-lg border bg-card">
		<div class="flex items-center justify-between border-b p-4">
			<h2 class="font-semibold">最近文件</h2>
			<a href="/files" class="text-sm text-primary hover:underline">查看全部</a>
		</div>
		{#if loading}
			<div class="divide-y">
				{#each Array(3) as _}
					<div class="flex items-center justify-between p-4">
						<div class="flex items-center gap-3">
							<div class="h-10 w-10 animate-pulse rounded-lg bg-muted"></div>
							<div>
								<div class="h-4 w-32 animate-pulse rounded bg-muted"></div>
								<div class="mt-2 h-3 w-24 animate-pulse rounded bg-muted"></div>
							</div>
						</div>
						<div class="h-6 w-16 animate-pulse rounded-full bg-muted"></div>
					</div>
				{/each}
			</div>
		{:else if recentFiles.length === 0}
			<div class="p-8 text-center">
				<div class="mx-auto mb-4 flex h-16 w-16 items-center justify-center rounded-full bg-muted">
					<svg class="h-8 w-8 text-muted-foreground" fill="none" stroke="currentColor" viewBox="0 0 24 24">
						<path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M3 7v10a2 2 0 002 2h14a2 2 0 002-2V9a2 2 0 00-2-2h-6l-2-2H5a2 2 0 00-2 2z" />
					</svg>
				</div>
				<p class="text-muted-foreground">暂无文件</p>
				<a href="/upload" class="mt-2 inline-block text-sm text-primary hover:underline">
					上传您的第一个文件
				</a>
			</div>
		{:else}
			<div class="divide-y">
				{#each recentFiles as file (file.id)}
					{@const statusVariant = getStatusVariant(file.status)}
					<a
						href="/files/{file.fileHash}"
						class="flex items-center justify-between p-4 transition-colors hover:bg-muted/50"
					>
						<div class="flex items-center gap-3">
							<div class="flex h-10 w-10 items-center justify-center rounded-lg bg-primary/10 text-primary">
								<svg class="h-5 w-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
									<path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M9 12h6m-6 4h6m2 5H7a2 2 0 01-2-2V5a2 2 0 012-2h5.586a1 1 0 01.707.293l5.414 5.414a1 1 0 01.293.707V19a2 2 0 01-2 2z" />
								</svg>
							</div>
							<div>
								<p class="font-medium">{file.fileName}</p>
								<p class="text-sm text-muted-foreground">
									{formatFileSize(file.fileSize)} · {formatDateTime(file.createTime, 'date')}
								</p>
							</div>
						</div>
						<span
							class="rounded-full px-2 py-1 text-xs"
							class:bg-green-100={statusVariant === 'completed'}
							class:text-green-700={statusVariant === 'completed'}
							class:dark:bg-green-900={statusVariant === 'completed'}
							class:dark:text-green-300={statusVariant === 'completed'}
							class:bg-yellow-100={statusVariant === 'processing'}
							class:text-yellow-700={statusVariant === 'processing'}
							class:dark:bg-yellow-900={statusVariant === 'processing'}
							class:dark:text-yellow-300={statusVariant === 'processing'}
							class:bg-red-100={statusVariant === 'failed'}
							class:text-red-700={statusVariant === 'failed'}
							class:dark:bg-red-900={statusVariant === 'failed'}
							class:dark:text-red-300={statusVariant === 'failed'}
						>
							{FileStatusLabel[file.status]}
						</span>
					</a>
				{/each}
			</div>
		{/if}
	</div>

	<!-- Quick actions -->
	<div class="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
		<a
			href="/upload"
			class="flex items-center gap-4 rounded-lg border bg-card p-6 transition-colors hover:border-primary"
		>
			<div class="flex h-12 w-12 items-center justify-center rounded-lg bg-primary text-primary-foreground">
				<svg class="h-6 w-6" fill="none" stroke="currentColor" viewBox="0 0 24 24">
					<path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M4 16v1a3 3 0 003 3h10a3 3 0 003-3v-1m-4-8l-4-4m0 0L8 8m4-4v12" />
				</svg>
			</div>
			<div>
				<p class="font-semibold">上传文件</p>
				<p class="text-sm text-muted-foreground">上传并存证您的文件</p>
			</div>
		</a>

		<a
			href="/files"
			class="flex items-center gap-4 rounded-lg border bg-card p-6 transition-colors hover:border-primary"
		>
			<div class="flex h-12 w-12 items-center justify-center rounded-lg bg-blue-500 text-white">
				<svg class="h-6 w-6" fill="none" stroke="currentColor" viewBox="0 0 24 24">
					<path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M3 7v10a2 2 0 002 2h14a2 2 0 002-2V9a2 2 0 00-2-2h-6l-2-2H5a2 2 0 00-2 2z" />
				</svg>
			</div>
			<div>
				<p class="font-semibold">文件管理</p>
				<p class="text-sm text-muted-foreground">查看和管理您的文件</p>
			</div>
		</a>

		<a
			href="/tickets/new"
			class="flex items-center gap-4 rounded-lg border bg-card p-6 transition-colors hover:border-primary"
		>
			<div class="flex h-12 w-12 items-center justify-center rounded-lg bg-orange-500 text-white">
				<svg class="h-6 w-6" fill="none" stroke="currentColor" viewBox="0 0 24 24">
					<path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M8.228 9c.549-1.165 2.03-2 3.772-2 2.21 0 4 1.343 4 3 0 1.4-1.278 2.575-3.006 2.907-.542.104-.994.54-.994 1.093m0 3h.01M21 12a9 9 0 11-18 0 9 9 0 0118 0z" />
				</svg>
			</div>
			<div>
				<p class="font-semibold">提交工单</p>
				<p class="text-sm text-muted-foreground">反馈问题或获取帮助</p>
			</div>
		</a>
	</div>
</div>
