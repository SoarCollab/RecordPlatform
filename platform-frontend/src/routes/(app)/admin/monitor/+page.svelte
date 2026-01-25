<script lang="ts">
	import { onMount, onDestroy } from 'svelte';
	import { goto } from '$app/navigation';
	import { useNotifications } from '$stores/notifications.svelte';
	import { useAuth } from '$stores/auth.svelte';
	import { formatFileSize, formatDateTime, formatNumber } from '$utils/format';
	import { getSystemStats, getChainStatus, getSystemHealth } from '$api/endpoints/system';
	import type { SystemStats, ChainStatus, SystemHealth } from '$api/types';
	import { ChainType } from '$api/types';
	import * as Card from '$lib/components/ui/card';
	import { Button } from '$lib/components/ui/button';
	import { Badge } from '$lib/components/ui/badge';

	const notifications = useNotifications();
	const auth = useAuth();

	// 状态
	let loading = $state(true);
	let systemStats = $state<SystemStats | null>(null);
	let chainStatus = $state<ChainStatus | null>(null);
	let systemHealth = $state<SystemHealth | null>(null);
	let refreshInterval: ReturnType<typeof setInterval> | null = null;
	let lastRefresh = $state<Date | null>(null);

	// 派生计算
	const chainTypeLabel = $derived(
		chainStatus
			? {
					[ChainType.LOCAL_FISCO]: 'FISCO BCOS (本地)',
					[ChainType.BSN_FISCO]: 'BSN FISCO',
					[ChainType.BSN_BESU]: 'BSN Besu'
				}[chainStatus.chainType] || chainStatus.chainType
			: '-'
	);

	const healthStatusClass = $derived({
		UP: 'bg-green-100 text-green-700 dark:bg-green-900 dark:text-green-300',
		DOWN: 'bg-red-100 text-red-700 dark:bg-red-900 dark:text-red-300',
		DEGRADED: 'bg-yellow-100 text-yellow-700 dark:bg-yellow-900 dark:text-yellow-300',
		UNKNOWN: 'bg-gray-100 text-gray-700 dark:bg-gray-800 dark:text-gray-300'
	});

	onMount(() => {
		if (!auth.isAdmin) {
			notifications.error('权限不足', '仅管理员可访问此页面');
			goto('/dashboard');
			return;
		}

		loadAllData();

		// 每 30 秒自动刷新
		refreshInterval = setInterval(loadAllData, 30000);
	});

	onDestroy(() => {
		if (refreshInterval) {
			clearInterval(refreshInterval);
		}
	});

	async function loadAllData() {
		loading = true;

		try {
			// 并行加载所有数据，并提供优雅降级
			const [statsResult, chainResult, healthResult] = await Promise.allSettled([
				getSystemStats(),
				getChainStatus(),
				getSystemHealth()
			]);

			if (statsResult.status === 'fulfilled') {
				systemStats = statsResult.value;
			}

			if (chainResult.status === 'fulfilled') {
				chainStatus = chainResult.value;
			}

			if (healthResult.status === 'fulfilled') {
				systemHealth = healthResult.value;
			}

			lastRefresh = new Date();
		} catch (err) {
			notifications.error('加载失败', err instanceof Error ? err.message : '请稍后重试');
		} finally {
			loading = false;
		}
	}

	function getComponentStatusClass(status: string): string {
		return healthStatusClass[status as keyof typeof healthStatusClass] || healthStatusClass.UNKNOWN;
	}

	function formatUptime(seconds: number): string {
		const days = Math.floor(seconds / 86400);
		const hours = Math.floor((seconds % 86400) / 3600);
		const minutes = Math.floor((seconds % 3600) / 60);

		const parts: string[] = [];
		if (days > 0) parts.push(`${days}天`);
		if (hours > 0) parts.push(`${hours}小时`);
		if (minutes > 0) parts.push(`${minutes}分钟`);

		return parts.length > 0 ? parts.join(' ') : '刚刚启动';
	}
</script>

<svelte:head>
	<title>系统监控 - 存证平台</title>
</svelte:head>

<div class="space-y-6">
	<div class="flex items-center justify-between">
		<div>
			<h1 class="text-2xl font-bold">系统监控</h1>
			<p class="text-muted-foreground">实时查看系统状态和区块链信息</p>
		</div>
		<div class="flex items-center gap-4">
			{#if lastRefresh}
				<span class="text-sm text-muted-foreground">
					上次刷新: {formatDateTime(lastRefresh.toISOString(), 'time')}
				</span>
			{/if}
			<Button variant="outline" onclick={loadAllData} disabled={loading}>
				{#if loading}
					<div class="mr-2 h-4 w-4 animate-spin rounded-full border-2 border-current border-t-transparent"></div>
				{:else}
					<svg class="mr-2 h-4 w-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
						<path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M4 4v5h.582m15.356 2A8.001 8.001 0 004.582 9m0 0H9m11 11v-5h-.581m0 0a8.003 8.003 0 01-15.357-2m15.357 2H15" />
					</svg>
				{/if}
				刷新
			</Button>
		</div>
	</div>

	<!-- 系统健康概览 -->
	<Card.Root>
		<Card.Header>
			<Card.Title class="flex items-center gap-2">
				<svg class="h-5 w-5 text-primary" fill="none" stroke="currentColor" viewBox="0 0 24 24">
					<path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M9 12l2 2 4-4m5.618-4.016A11.955 11.955 0 0112 2.944a11.955 11.955 0 01-8.618 3.04A12.02 12.02 0 003 9c0 5.591 3.824 10.29 9 11.622 5.176-1.332 9-6.03 9-11.622 0-1.042-.133-2.052-.382-3.016z" />
				</svg>
				系统健康状态
			</Card.Title>
		</Card.Header>
		<Card.Content>
			{#if loading && !systemHealth}
				<div class="flex items-center justify-center p-8">
					<div class="h-8 w-8 animate-spin rounded-full border-4 border-primary border-t-transparent"></div>
				</div>
			{:else if systemHealth}
				<div class="space-y-6">
					<!-- 整体状态 -->
					<div class="flex items-center justify-between rounded-lg bg-muted/50 p-4">
						<div class="flex items-center gap-4">
							<div class="flex h-12 w-12 items-center justify-center rounded-full {systemHealth.status === 'UP' ? 'bg-green-100 text-green-600 dark:bg-green-900 dark:text-green-400' : systemHealth.status === 'DOWN' ? 'bg-red-100 text-red-600 dark:bg-red-900 dark:text-red-400' : 'bg-yellow-100 text-yellow-600 dark:bg-yellow-900 dark:text-yellow-400'}">
								{#if systemHealth.status === 'UP'}
									<svg class="h-6 w-6" fill="none" stroke="currentColor" viewBox="0 0 24 24">
										<path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M5 13l4 4L19 7" />
									</svg>
								{:else if systemHealth.status === 'DOWN'}
									<svg class="h-6 w-6" fill="none" stroke="currentColor" viewBox="0 0 24 24">
										<path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M6 18L18 6M6 6l12 12" />
									</svg>
								{:else}
									<svg class="h-6 w-6" fill="none" stroke="currentColor" viewBox="0 0 24 24">
										<path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M12 9v2m0 4h.01m-6.938 4h13.856c1.54 0 2.502-1.667 1.732-3L13.732 4c-.77-1.333-2.694-1.333-3.464 0L3.34 16c-.77 1.333.192 3 1.732 3z" />
									</svg>
								{/if}
							</div>
							<div>
								<p class="text-lg font-semibold">
									{systemHealth.status === 'UP' ? '系统运行正常' : systemHealth.status === 'DOWN' ? '系统异常' : '系统降级运行'}
								</p>
								<p class="text-sm text-muted-foreground">
									运行时间: {formatUptime(systemHealth.uptime)}
								</p>
							</div>
						</div>
						<Badge variant={systemHealth.status === 'UP' ? 'default' : 'destructive'}>
							{systemHealth.status}
						</Badge>
					</div>

					<!-- 组件状态网格 -->
					<div class="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
						{#each Object.entries(systemHealth.components) as [name, component]}
							<div class="rounded-lg border p-4">
								<div class="mb-2 flex items-center justify-between">
									<span class="text-sm font-medium capitalize">
										{{
											database: '数据库',
											redis: 'Redis',
											blockchain: '区块链',
											storage: '存储服务'
										}[name] || name}
									</span>
									<span class="rounded-full px-2 py-0.5 text-xs {getComponentStatusClass(component.status)}">
										{component.status}
									</span>
								</div>
								<div class="flex items-center gap-2">
									<span class="h-2 w-2 rounded-full {component.status === 'UP' ? 'bg-green-500' : component.status === 'DOWN' ? 'bg-red-500' : 'bg-gray-400'}"></span>
									<span class="text-sm text-muted-foreground">
										{component.status === 'UP' ? '正常' : component.status === 'DOWN' ? '异常' : '未知'}
									</span>
								</div>
							</div>
						{/each}
					</div>
				</div>
			{:else}
				<div class="p-8 text-center text-muted-foreground">
					<p>无法获取系统健康状态</p>
				</div>
			{/if}
		</Card.Content>
	</Card.Root>

	<!-- 统计网格 -->
	<div class="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
		{#if loading && !systemStats}
			{#each Array(4) as _}
				<Card.Root>
					<Card.Content class="p-6">
						<div class="h-4 w-24 animate-pulse rounded bg-muted"></div>
						<div class="mt-4 h-8 w-16 animate-pulse rounded bg-muted"></div>
					</Card.Content>
				</Card.Root>
			{/each}
		{:else if systemStats}
			<Card.Root>
				<Card.Content class="p-6">
					<div class="flex items-center justify-between">
						<p class="text-sm text-muted-foreground">总用户数</p>
						<svg class="h-5 w-5 text-muted-foreground" fill="none" stroke="currentColor" viewBox="0 0 24 24">
							<path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M17 20h5v-2a3 3 0 00-5.356-1.857M17 20H7m10 0v-2c0-.656-.126-1.283-.356-1.857M7 20H2v-2a3 3 0 015.356-1.857M7 20v-2c0-.656.126-1.283.356-1.857m0 0a5.002 5.002 0 019.288 0M15 7a3 3 0 11-6 0 3 3 0 016 0zm6 3a2 2 0 11-4 0 2 2 0 014 0zM7 10a2 2 0 11-4 0 2 2 0 014 0z" />
						</svg>
					</div>
					<p class="mt-2 text-3xl font-bold">{formatNumber(systemStats.totalUsers)}</p>
				</Card.Content>
			</Card.Root>

			<Card.Root>
				<Card.Content class="p-6">
					<div class="flex items-center justify-between">
						<p class="text-sm text-muted-foreground">总文件数</p>
						<svg class="h-5 w-5 text-muted-foreground" fill="none" stroke="currentColor" viewBox="0 0 24 24">
							<path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M3 7v10a2 2 0 002 2h14a2 2 0 002-2V9a2 2 0 00-2-2h-6l-2-2H5a2 2 0 00-2 2z" />
						</svg>
					</div>
					<p class="mt-2 text-3xl font-bold">{formatNumber(systemStats.totalFiles)}</p>
				</Card.Content>
			</Card.Root>

			<Card.Root>
				<Card.Content class="p-6">
					<div class="flex items-center justify-between">
						<p class="text-sm text-muted-foreground">存储用量</p>
						<svg class="h-5 w-5 text-muted-foreground" fill="none" stroke="currentColor" viewBox="0 0 24 24">
							<path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M4 7v10c0 2.21 3.582 4 8 4s8-1.79 8-4V7M4 7c0 2.21 3.582 4 8 4s8-1.79 8-4M4 7c0-2.21 3.582-4 8-4s8 1.79 8 4m0 5c0 2.21-3.582 4-8 4s-8-1.79-8-4" />
						</svg>
					</div>
					<p class="mt-2 text-3xl font-bold">{formatFileSize(systemStats.totalStorage)}</p>
				</Card.Content>
			</Card.Root>

			<Card.Root>
				<Card.Content class="p-6">
					<div class="flex items-center justify-between">
						<p class="text-sm text-muted-foreground">链上交易数</p>
						<svg class="h-5 w-5 text-muted-foreground" fill="none" stroke="currentColor" viewBox="0 0 24 24">
							<path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M9 5H7a2 2 0 00-2 2v12a2 2 0 002 2h10a2 2 0 002-2V7a2 2 0 00-2-2h-2M9 5a2 2 0 002 2h2a2 2 0 002-2M9 5a2 2 0 012-2h2a2 2 0 012 2m-6 9l2 2 4-4" />
						</svg>
					</div>
					<p class="mt-2 text-3xl font-bold">{formatNumber(systemStats.totalTransactions)}</p>
				</Card.Content>
			</Card.Root>
		{/if}
	</div>

	<!-- 今日活动 -->
	{#if systemStats}
		<div class="grid gap-4 sm:grid-cols-2">
			<Card.Root>
				<Card.Content class="p-6">
					<div class="flex items-center gap-4">
						<div class="flex h-12 w-12 items-center justify-center rounded-lg bg-blue-100 text-blue-600 dark:bg-blue-900 dark:text-blue-400">
							<svg class="h-6 w-6" fill="none" stroke="currentColor" viewBox="0 0 24 24">
								<path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M4 16v1a3 3 0 003 3h10a3 3 0 003-3v-1m-4-8l-4-4m0 0L8 8m4-4v12" />
							</svg>
						</div>
						<div>
							<p class="text-sm text-muted-foreground">今日上传</p>
							<p class="text-2xl font-bold">{formatNumber(systemStats.todayUploads)} 个文件</p>
						</div>
					</div>
				</Card.Content>
			</Card.Root>

			<Card.Root>
				<Card.Content class="p-6">
					<div class="flex items-center gap-4">
						<div class="flex h-12 w-12 items-center justify-center rounded-lg bg-green-100 text-green-600 dark:bg-green-900 dark:text-green-400">
							<svg class="h-6 w-6" fill="none" stroke="currentColor" viewBox="0 0 24 24">
								<path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M4 16v1a3 3 0 003 3h10a3 3 0 003-3v-1m-4-4l-4 4m0 0l-4-4m4 4V4" />
							</svg>
						</div>
						<div>
							<p class="text-sm text-muted-foreground">今日下载</p>
							<p class="text-2xl font-bold">{formatNumber(systemStats.todayDownloads)} 次</p>
						</div>
					</div>
				</Card.Content>
			</Card.Root>
		</div>
	{/if}

	<!-- 区块链状态 -->
	<Card.Root>
		<Card.Header>
			<Card.Title class="flex items-center gap-2">
				<svg class="h-5 w-5 text-primary" fill="none" stroke="currentColor" viewBox="0 0 24 24">
					<path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M19.428 15.428a2 2 0 00-1.022-.547l-2.387-.477a6 6 0 00-3.86.517l-.318.158a6 6 0 01-3.86.517L6.05 15.21a2 2 0 00-1.806.547M8 4h8l-1 1v5.172a2 2 0 00.586 1.414l5 5c1.26 1.26.367 3.414-1.415 3.414H4.828c-1.782 0-2.674-2.154-1.414-3.414l5-5A2 2 0 009 10.172V5L8 4z" />
				</svg>
				区块链状态
			</Card.Title>
			<Card.Description>
				当前连接的区块链网络信息
			</Card.Description>
		</Card.Header>
		<Card.Content>
			{#if loading && !chainStatus}
				<div class="flex items-center justify-center p-8">
					<div class="h-8 w-8 animate-spin rounded-full border-4 border-primary border-t-transparent"></div>
				</div>
			{:else if chainStatus}
				<div class="space-y-6">
					<!-- 链信息标题栏 -->
					<div class="flex items-center justify-between rounded-lg bg-muted/50 p-4">
						<div class="flex items-center gap-4">
							<div class="flex h-12 w-12 items-center justify-center rounded-full {chainStatus.healthy ? 'bg-green-100 text-green-600 dark:bg-green-900 dark:text-green-400' : 'bg-red-100 text-red-600 dark:bg-red-900 dark:text-red-400'}">
								<svg class="h-6 w-6" fill="none" stroke="currentColor" viewBox="0 0 24 24">
									<path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M13.828 10.172a4 4 0 00-5.656 0l-4 4a4 4 0 105.656 5.656l1.102-1.101m-.758-4.899a4 4 0 005.656 0l4-4a4 4 0 00-5.656-5.656l-1.1 1.1" />
								</svg>
							</div>
							<div>
								<p class="text-lg font-semibold">{chainTypeLabel}</p>
								<p class="text-sm text-muted-foreground">
									{chainStatus.healthy ? '区块链连接正常' : '区块链连接异常'}
								</p>
							</div>
						</div>
						<Badge variant={chainStatus.healthy ? 'default' : 'destructive'}>
							{chainStatus.healthy ? '在线' : '离线'}
						</Badge>
					</div>

					<!-- 链指标网格 -->
					<div class="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
						<div class="rounded-lg border p-4">
							<p class="text-sm text-muted-foreground">区块高度</p>
							<p class="mt-1 text-2xl font-bold font-mono">{formatNumber(chainStatus.blockNumber)}</p>
						</div>
						<div class="rounded-lg border p-4">
							<p class="text-sm text-muted-foreground">总交易数</p>
							<p class="mt-1 text-2xl font-bold font-mono">{formatNumber(chainStatus.transactionCount)}</p>
						</div>
						<div class="rounded-lg border p-4">
							<p class="text-sm text-muted-foreground">失败交易</p>
							<p class="mt-1 text-2xl font-bold font-mono {Number(chainStatus.failedTransactionCount) > 0 ? 'text-destructive' : ''}">
								{formatNumber(chainStatus.failedTransactionCount)}
							</p>
						</div>
						<div class="rounded-lg border p-4">
							<p class="text-sm text-muted-foreground">节点数量</p>
							<p class="mt-1 text-2xl font-bold font-mono">{chainStatus.nodeCount}</p>
						</div>
					</div>

					<!-- 最后更新 -->
					{#if chainStatus.lastUpdateTime}
						<p class="text-sm text-muted-foreground">
							数据更新时间: {formatDateTime(chainStatus.lastUpdateTime)}
						</p>
					{/if}
				</div>
			{:else}
				<div class="p-8 text-center text-muted-foreground">
					<p>无法获取区块链状态</p>
					<p class="mt-2 text-sm">请检查区块链服务是否正常运行</p>
				</div>
			{/if}
		</Card.Content>
	</Card.Root>
</div>
