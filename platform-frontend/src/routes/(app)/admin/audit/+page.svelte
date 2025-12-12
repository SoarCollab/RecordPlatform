<script lang="ts">
	import { onMount } from 'svelte';
	import { useNotifications } from '$stores/notifications.svelte';
	import { useAuth } from '$stores/auth.svelte';
	import { goto } from '$app/navigation';
	import { formatDateTime } from '$utils/format';
	import { getAuditLogs, exportAuditLogs } from '$api/endpoints/system';
	import type { AuditLogVO, AuditLogQueryParams } from '$api/types';
	import * as Card from '$lib/components/ui/card';
	import { Button } from '$lib/components/ui/button';
	import { Input } from '$lib/components/ui/input';
	import { Badge } from '$lib/components/ui/badge';
	import * as Table from '$lib/components/ui/table';
	import * as Dialog from '$lib/components/ui/dialog';

	const notifications = useNotifications();
	const auth = useAuth();

	let logs = $state<AuditLogVO[]>([]);
	let loading = $state(true);
	let page = $state(1);
	let pageSize = $state(20);
	let total = $state(0);

	// Filters
	let filterUsername = $state('');
	let filterModule = $state('');
	let filterAction = $state('');
	let filterStartTime = $state('');
	let filterEndTime = $state('');

	// Detail dialog
	let selectedLog = $state<AuditLogVO | null>(null);
	let detailDialogOpen = $state(false);

	// Export state
	let isExporting = $state(false);

	onMount(() => {
		if (!auth.isAdmin) {
			notifications.error('权限不足', '仅管理员可访问此页面');
			goto('/dashboard');
			return;
		}
		loadLogs();
	});

	async function loadLogs() {
		loading = true;
		try {
			const params: AuditLogQueryParams & { current: number; size: number } = {
				current: page,
				size: pageSize
			};
			if (filterUsername) params.username = filterUsername;
			if (filterModule) params.module = filterModule;
			if (filterAction) params.action = filterAction;
			if (filterStartTime) params.startTime = filterStartTime;
			if (filterEndTime) params.endTime = filterEndTime;

			const result = await getAuditLogs(params);
			logs = result.records;
			total = result.total;
		} catch (err) {
			notifications.error('加载失败', err instanceof Error ? err.message : '请稍后重试');
		} finally {
			loading = false;
		}
	}

	function handleSearch() {
		page = 1;
		loadLogs();
	}

	function clearFilters() {
		filterUsername = '';
		filterModule = '';
		filterAction = '';
		filterStartTime = '';
		filterEndTime = '';
		page = 1;
		loadLogs();
	}

	function showDetail(log: AuditLogVO) {
		selectedLog = log;
		detailDialogOpen = true;
	}

	async function handleExport() {
		isExporting = true;
		try {
			const params: AuditLogQueryParams = {};
			if (filterUsername) params.username = filterUsername;
			if (filterModule) params.module = filterModule;
			if (filterAction) params.action = filterAction;
			if (filterStartTime) params.startTime = filterStartTime;
			if (filterEndTime) params.endTime = filterEndTime;

			const blob = await exportAuditLogs(params);
			const url = URL.createObjectURL(blob);
			const a = document.createElement('a');
			a.href = url;
			a.download = `audit_logs_${new Date().toISOString().split('T')[0]}.xlsx`;
			document.body.appendChild(a);
			a.click();
			document.body.removeChild(a);
			URL.revokeObjectURL(url);
			notifications.success('导出成功');
		} catch (err) {
			notifications.error('导出失败', err instanceof Error ? err.message : '请稍后重试');
		} finally {
			isExporting = false;
		}
	}

	function getStatusVariant(status: number): 'default' | 'destructive' {
		return status === 1 ? 'default' : 'destructive';
	}

	function getStatusLabel(status: number): string {
		return status === 1 ? '成功' : '失败';
	}
</script>

<svelte:head>
	<title>审计日志 - 存证平台</title>
</svelte:head>

<div class="space-y-6">
	<div class="flex items-center justify-between">
		<div>
			<h1 class="text-2xl font-bold">审计日志</h1>
			<p class="text-muted-foreground">查看系统操作日志和安全审计记录</p>
		</div>
		<Button onclick={handleExport} disabled={isExporting} variant="outline">
			{#if isExporting}
				<div class="mr-2 h-4 w-4 animate-spin rounded-full border-2 border-current border-t-transparent"></div>
			{:else}
				<svg class="mr-2 h-4 w-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
					<path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M12 10v6m0 0l-3-3m3 3l3-3m2 8H7a2 2 0 01-2-2V5a2 2 0 012-2h5.586a1 1 0 01.707.293l5.414 5.414a1 1 0 01.293.707V19a2 2 0 01-2 2z" />
				</svg>
			{/if}
			导出日志
		</Button>
	</div>

	<!-- Filters -->
	<Card.Root>
		<Card.Content class="pt-6">
			<div class="grid gap-4 sm:grid-cols-2 lg:grid-cols-5">
				<Input
					placeholder="用户名"
					bind:value={filterUsername}
					onkeydown={(e) => e.key === 'Enter' && handleSearch()}
				/>
				<Input
					placeholder="模块"
					bind:value={filterModule}
					onkeydown={(e) => e.key === 'Enter' && handleSearch()}
				/>
				<Input
					placeholder="操作"
					bind:value={filterAction}
					onkeydown={(e) => e.key === 'Enter' && handleSearch()}
				/>
				<Input
					type="datetime-local"
					placeholder="开始时间"
					bind:value={filterStartTime}
				/>
				<Input
					type="datetime-local"
					placeholder="结束时间"
					bind:value={filterEndTime}
				/>
			</div>
			<div class="mt-4 flex gap-2">
				<Button onclick={handleSearch}>
					<svg class="mr-2 h-4 w-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
						<path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M21 21l-6-6m2-5a7 7 0 11-14 0 7 7 0 0114 0z" />
					</svg>
					搜索
				</Button>
				<Button variant="outline" onclick={clearFilters}>清除筛选</Button>
			</div>
		</Card.Content>
	</Card.Root>

	<!-- Table -->
	<Card.Root>
		<Card.Content class="p-0">
			{#if loading}
				<div class="flex items-center justify-center p-12">
					<div class="h-8 w-8 animate-spin rounded-full border-4 border-primary border-t-transparent"></div>
				</div>
			{:else if logs.length === 0}
				<div class="p-12 text-center text-muted-foreground">
					<p>暂无审计日志</p>
				</div>
			{:else}
				<Table.Root>
					<Table.Header>
						<Table.Row>
							<Table.Head>时间</Table.Head>
							<Table.Head>用户</Table.Head>
							<Table.Head>模块</Table.Head>
							<Table.Head>操作</Table.Head>
							<Table.Head>IP</Table.Head>
							<Table.Head>耗时</Table.Head>
							<Table.Head>状态</Table.Head>
							<Table.Head class="w-20"></Table.Head>
						</Table.Row>
					</Table.Header>
					<Table.Body>
						{#each logs as log (log.id)}
							<Table.Row>
								<Table.Cell class="text-sm">{formatDateTime(log.createTime)}</Table.Cell>
								<Table.Cell class="font-medium">{log.username}</Table.Cell>
								<Table.Cell>{log.module}</Table.Cell>
								<Table.Cell>{log.action}</Table.Cell>
								<Table.Cell class="font-mono text-sm">{log.ip}</Table.Cell>
								<Table.Cell>{log.duration}ms</Table.Cell>
								<Table.Cell>
									<Badge variant={getStatusVariant(log.status)}>
										{getStatusLabel(log.status)}
									</Badge>
								</Table.Cell>
								<Table.Cell>
									<Button variant="ghost" size="sm" onclick={() => showDetail(log)}>
										详情
									</Button>
								</Table.Cell>
							</Table.Row>
						{/each}
					</Table.Body>
				</Table.Root>
			{/if}
		</Card.Content>
	</Card.Root>

	<!-- Pagination -->
	{#if total > pageSize}
		<div class="flex items-center justify-between">
			<p class="text-sm text-muted-foreground">共 {total} 条记录</p>
			<div class="flex gap-2">
				<Button
					variant="outline"
					size="sm"
					disabled={page <= 1}
					onclick={() => { page--; loadLogs(); }}
				>
					上一页
				</Button>
				<Button
					variant="outline"
					size="sm"
					disabled={page >= Math.ceil(total / pageSize)}
					onclick={() => { page++; loadLogs(); }}
				>
					下一页
				</Button>
			</div>
		</div>
	{/if}
</div>

<!-- Detail Dialog -->
<Dialog.Root bind:open={detailDialogOpen}>
	<Dialog.Content class="sm:max-w-lg">
		<Dialog.Header>
			<Dialog.Title>日志详情</Dialog.Title>
		</Dialog.Header>
		{#if selectedLog}
			<div class="space-y-4">
				<div class="grid grid-cols-2 gap-4">
					<div>
						<p class="text-sm font-medium text-muted-foreground">用户</p>
						<p class="mt-1">{selectedLog.username}</p>
					</div>
					<div>
						<p class="text-sm font-medium text-muted-foreground">时间</p>
						<p class="mt-1">{formatDateTime(selectedLog.createTime)}</p>
					</div>
					<div>
						<p class="text-sm font-medium text-muted-foreground">模块</p>
						<p class="mt-1">{selectedLog.module}</p>
					</div>
					<div>
						<p class="text-sm font-medium text-muted-foreground">操作</p>
						<p class="mt-1">{selectedLog.action}</p>
					</div>
					<div>
						<p class="text-sm font-medium text-muted-foreground">IP 地址</p>
						<p class="mt-1 font-mono">{selectedLog.ip}</p>
					</div>
					<div>
						<p class="text-sm font-medium text-muted-foreground">耗时</p>
						<p class="mt-1">{selectedLog.duration}ms</p>
					</div>
					<div>
						<p class="text-sm font-medium text-muted-foreground">状态</p>
						<p class="mt-1">
							<Badge variant={getStatusVariant(selectedLog.status)}>
								{getStatusLabel(selectedLog.status)}
							</Badge>
						</p>
					</div>
					{#if selectedLog.targetType}
						<div>
							<p class="text-sm font-medium text-muted-foreground">目标类型</p>
							<p class="mt-1">{selectedLog.targetType}</p>
						</div>
					{/if}
				</div>
				{#if selectedLog.targetId}
					<div>
						<p class="text-sm font-medium text-muted-foreground">目标 ID</p>
						<p class="mt-1 break-all font-mono text-sm">{selectedLog.targetId}</p>
					</div>
				{/if}
				{#if selectedLog.detail}
					<div>
						<p class="text-sm font-medium text-muted-foreground">详情</p>
						<pre class="mt-1 max-h-40 overflow-auto rounded bg-muted p-2 text-sm">{selectedLog.detail}</pre>
					</div>
				{/if}
				{#if selectedLog.errorMessage}
					<div>
						<p class="text-sm font-medium text-muted-foreground">错误信息</p>
						<p class="mt-1 text-destructive">{selectedLog.errorMessage}</p>
					</div>
				{/if}
				{#if selectedLog.userAgent}
					<div>
						<p class="text-sm font-medium text-muted-foreground">User-Agent</p>
						<p class="mt-1 break-all text-sm text-muted-foreground">{selectedLog.userAgent}</p>
					</div>
				{/if}
			</div>
		{/if}
		<Dialog.Footer>
			<Button onclick={() => detailDialogOpen = false}>关闭</Button>
		</Dialog.Footer>
	</Dialog.Content>
</Dialog.Root>
