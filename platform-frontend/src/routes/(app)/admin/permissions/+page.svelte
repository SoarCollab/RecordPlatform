<script lang="ts">
	import { onMount } from 'svelte';
	import { useNotifications } from '$stores/notifications.svelte';
	import { useAuth } from '$stores/auth.svelte';
	import { goto } from '$app/navigation';
	import { getPermissionTree } from '$api/endpoints/system';
	import { PermissionType, type SysPermission } from '$api/types';
	import * as Card from '$lib/components/ui/card';
	import { Button } from '$lib/components/ui/button';
	import { Badge } from '$lib/components/ui/badge';
	import { Input } from '$lib/components/ui/input';

	const notifications = useNotifications();
	const auth = useAuth();

	let permissions = $state<SysPermission[]>([]);
	let loading = $state(true);
	let searchQuery = $state('');
	let expandedNodes = $state<Set<string>>(new Set());

	// Filtered permissions based on search
	const filteredPermissions = $derived(
		searchQuery ? filterPermissions(permissions, searchQuery.toLowerCase()) : permissions
	);

	onMount(() => {
		if (!auth.isAdmin) {
			notifications.error('权限不足', '仅管理员可访问此页面');
			goto('/dashboard');
			return;
		}
		loadPermissions();
	});

	async function loadPermissions() {
		loading = true;
		try {
			permissions = await getPermissionTree();
			// Expand first level by default
			permissions.forEach(p => expandedNodes.add(p.id));
			expandedNodes = new Set(expandedNodes);
		} catch (err) {
			notifications.error('加载失败', err instanceof Error ? err.message : '请稍后重试');
		} finally {
			loading = false;
		}
	}

	function filterPermissions(perms: SysPermission[], query: string): SysPermission[] {
		const result: SysPermission[] = [];
		for (const perm of perms) {
			const matches = perm.name.toLowerCase().includes(query) ||
				perm.code.toLowerCase().includes(query);
			const childMatches = perm.children ? filterPermissions(perm.children, query) : [];

			if (matches || childMatches.length > 0) {
				result.push({
					...perm,
					children: childMatches.length > 0 ? childMatches : perm.children
				});
			}
		}
		return result;
	}

	function toggleExpand(id: string) {
		if (expandedNodes.has(id)) {
			expandedNodes.delete(id);
		} else {
			expandedNodes.add(id);
		}
		expandedNodes = new Set(expandedNodes);
	}

	function expandAll() {
		const allIds = collectAllIds(permissions);
		expandedNodes = new Set(allIds);
	}

	function collapseAll() {
		expandedNodes = new Set();
	}

	function collectAllIds(perms: SysPermission[]): string[] {
		const ids: string[] = [];
		for (const perm of perms) {
			ids.push(perm.id);
			if (perm.children) {
				ids.push(...collectAllIds(perm.children));
			}
		}
		return ids;
	}

	function getTypeLabel(type: PermissionType): string {
		switch (type) {
			case PermissionType.MENU:
				return '菜单';
			case PermissionType.BUTTON:
				return '按钮';
			case PermissionType.API:
				return 'API';
			default:
				return '未知';
		}
	}

	function getTypeVariant(type: PermissionType): 'default' | 'secondary' | 'outline' {
		switch (type) {
			case PermissionType.MENU:
				return 'default';
			case PermissionType.BUTTON:
				return 'secondary';
			case PermissionType.API:
				return 'outline';
			default:
				return 'outline';
		}
	}

	function getTypeIcon(type: PermissionType): string {
		switch (type) {
			case PermissionType.MENU:
				return 'menu';
			case PermissionType.BUTTON:
				return 'button';
			case PermissionType.API:
				return 'api';
			default:
				return 'unknown';
		}
	}
</script>

<svelte:head>
	<title>权限管理 - 存证平台</title>
</svelte:head>

<div class="space-y-6">
	<div class="flex items-center justify-between">
		<div>
			<h1 class="text-2xl font-bold">权限管理</h1>
			<p class="text-muted-foreground">查看系统权限树结构</p>
		</div>
		<div class="flex gap-2">
			<Button variant="outline" size="sm" onclick={expandAll}>展开全部</Button>
			<Button variant="outline" size="sm" onclick={collapseAll}>折叠全部</Button>
		</div>
	</div>

	<!-- Search -->
	<Card.Root>
		<Card.Content class="pt-6">
			<div class="flex gap-4">
				<Input
					placeholder="搜索权限名称或编码..."
					bind:value={searchQuery}
					class="max-w-md"
				/>
				{#if searchQuery}
					<Button variant="outline" onclick={() => searchQuery = ''}>清除</Button>
				{/if}
			</div>
		</Card.Content>
	</Card.Root>

	<!-- Permission Tree -->
	<Card.Root>
		<Card.Content class="pt-6">
			{#if loading}
				<div class="flex items-center justify-center p-12">
					<div class="h-8 w-8 animate-spin rounded-full border-4 border-primary border-t-transparent"></div>
				</div>
			{:else if filteredPermissions.length === 0}
				<div class="p-12 text-center text-muted-foreground">
					{#if searchQuery}
						<p>未找到匹配的权限</p>
					{:else}
						<p>暂无权限数据</p>
					{/if}
				</div>
			{:else}
				<div class="space-y-1">
					{#each filteredPermissions as permission (permission.id)}
						{@render PermissionNode(permission, 0)}
					{/each}
				</div>
			{/if}
		</Card.Content>
	</Card.Root>

	<!-- Legend -->
	<Card.Root>
		<Card.Content class="pt-6">
			<p class="mb-3 text-sm font-medium text-muted-foreground">图例</p>
			<div class="flex flex-wrap gap-4">
				<div class="flex items-center gap-2">
					<Badge variant="default">菜单</Badge>
					<span class="text-sm text-muted-foreground">导航菜单项</span>
				</div>
				<div class="flex items-center gap-2">
					<Badge variant="secondary">按钮</Badge>
					<span class="text-sm text-muted-foreground">页面操作按钮</span>
				</div>
				<div class="flex items-center gap-2">
					<Badge variant="outline">API</Badge>
					<span class="text-sm text-muted-foreground">后端接口权限</span>
				</div>
			</div>
		</Card.Content>
	</Card.Root>
</div>

{#snippet PermissionNode(perm: SysPermission, level: number)}
	<div class="permission-node" style="padding-left: {level * 24}px">
		<div class="flex items-center gap-2 rounded-lg px-3 py-2 hover:bg-muted/50">
			<!-- Expand/Collapse -->
			{#if perm.children && perm.children.length > 0}
				<button
					class="flex h-6 w-6 items-center justify-center rounded hover:bg-muted"
					onclick={() => toggleExpand(perm.id)}
					aria-label={expandedNodes.has(perm.id) ? '收起' : '展开'}
				>
					<svg
						class="h-4 w-4 transition-transform {expandedNodes.has(perm.id) ? 'rotate-90' : ''}"
						fill="none"
						stroke="currentColor"
						viewBox="0 0 24 24"
					>
						<path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M9 5l7 7-7 7" />
					</svg>
				</button>
			{:else}
				<div class="h-6 w-6"></div>
			{/if}

			<!-- Icon -->
			<div class="flex h-8 w-8 items-center justify-center rounded bg-primary/10 text-primary">
				{#if getTypeIcon(perm.type) === 'menu'}
					<svg class="h-4 w-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
						<path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M4 6h16M4 12h16M4 18h16" />
					</svg>
				{:else if getTypeIcon(perm.type) === 'button'}
					<svg class="h-4 w-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
						<path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M15 15l-2 5L9 9l11 4-5 2zm0 0l5 5M7.188 2.239l.777 2.897M5.136 7.965l-2.898-.777M13.95 4.05l-2.122 2.122m-5.657 5.656l-2.12 2.122" />
					</svg>
				{:else}
					<svg class="h-4 w-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
						<path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M8 9l3 3-3 3m5 0h3M5 20h14a2 2 0 002-2V6a2 2 0 00-2-2H5a2 2 0 00-2 2v12a2 2 0 002 2z" />
					</svg>
				{/if}
			</div>

			<!-- Name & Code -->
			<div class="min-w-0 flex-1">
				<p class="font-medium">{perm.name}</p>
				<p class="truncate text-sm text-muted-foreground">{perm.code}</p>
			</div>

			<!-- Type Badge -->
			<Badge variant={getTypeVariant(perm.type)}>
				{getTypeLabel(perm.type)}
			</Badge>

			<!-- Status -->
			{#if perm.status === 0}
				<Badge variant="destructive">禁用</Badge>
			{/if}

			<!-- Path -->
			{#if perm.path}
				<span class="hidden text-sm text-muted-foreground lg:inline">{perm.path}</span>
			{/if}
		</div>

		<!-- Children -->
		{#if perm.children && perm.children.length > 0 && expandedNodes.has(perm.id)}
			<div class="children">
				{#each perm.children as child (child.id)}
					{@render PermissionNode(child, level + 1)}
				{/each}
			</div>
		{/if}
	</div>
{/snippet}
