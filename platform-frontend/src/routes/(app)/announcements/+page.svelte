<script lang="ts">
	import { onMount } from 'svelte';
	import { goto } from '$app/navigation';
	import { useNotifications } from '$stores/notifications.svelte';
	import { formatDateTime } from '$utils/format';
	import { getAnnouncements } from '$api/endpoints/messages';
	import type { AnnouncementVO } from '$api/types';
	import { AnnouncementPriority, AnnouncementPriorityLabel } from '$api/types';
	import { Button } from '$lib/components/ui/button';
	import * as Card from '$lib/components/ui/card';
	import { Badge } from '$lib/components/ui/badge';

	const notifications = useNotifications();

	let announcements = $state<AnnouncementVO[]>([]);
	let loading = $state(true);
	let page = $state(1);
	let total = $state(0);
	let pageSize = $state(20);

	onMount(() => {
		loadAnnouncements();
	});

	async function loadAnnouncements() {
		loading = true;
		try {
			const result = await getAnnouncements({ current: page, size: pageSize });
			announcements = result.records;
			total = result.total;
		} catch (err) {
			notifications.error('加载失败', err instanceof Error ? err.message : '请稍后重试');
		} finally {
			loading = false;
		}
	}

	function getPriorityClass(priority: AnnouncementPriority): string {
		switch (priority) {
			case AnnouncementPriority.URGENT:
				return 'bg-red-100 text-red-700 dark:bg-red-900 dark:text-red-300';
			case AnnouncementPriority.HIGH:
				return 'bg-orange-100 text-orange-700 dark:bg-orange-900 dark:text-orange-300';
			case AnnouncementPriority.NORMAL:
				return 'bg-blue-100 text-blue-700 dark:bg-blue-900 dark:text-blue-300';
			case AnnouncementPriority.LOW:
				return 'bg-gray-100 text-gray-700 dark:bg-gray-800 dark:text-gray-300';
			default:
				return 'bg-blue-100 text-blue-700';
		}
	}

	function truncateContent(content: string, maxLength = 150): string {
		if (content.length <= maxLength) return content;
		return content.slice(0, maxLength) + '...';
	}
</script>

<svelte:head>
	<title>系统公告 - 存证平台</title>
</svelte:head>

<div class="space-y-6">
	<div>
		<h1 class="text-2xl font-bold">系统公告</h1>
		<p class="text-muted-foreground">查看平台最新公告</p>
	</div>

	{#if loading}
		<div class="space-y-4">
			{#each Array(3) as _}
				<Card.Root>
					<Card.Content class="p-6">
						<div class="mb-2 flex items-center gap-2">
							<div class="h-6 w-12 animate-pulse rounded-full bg-muted"></div>
							<div class="h-4 w-32 animate-pulse rounded bg-muted"></div>
						</div>
						<div class="mb-2 h-6 w-48 animate-pulse rounded bg-muted"></div>
						<div class="h-4 w-full animate-pulse rounded bg-muted"></div>
						<div class="mt-1 h-4 w-3/4 animate-pulse rounded bg-muted"></div>
					</Card.Content>
				</Card.Root>
			{/each}
		</div>
	{:else if announcements.length === 0}
		<Card.Root>
			<Card.Content class="p-12 text-center">
				<div class="mx-auto mb-4 flex h-16 w-16 items-center justify-center rounded-full bg-muted">
					<svg
						class="h-8 w-8 text-muted-foreground"
						fill="none"
						stroke="currentColor"
						viewBox="0 0 24 24"
					>
						<path
							stroke-linecap="round"
							stroke-linejoin="round"
							stroke-width="2"
							d="M11 5.882V19.24a1.76 1.76 0 01-3.417.592l-2.147-6.15M18 13a3 3 0 100-6M5.436 13.683A4.001 4.001 0 017 6h1.832c4.1 0 7.625-1.234 9.168-3v14c-1.543-1.766-5.067-3-9.168-3H7a3.988 3.988 0 01-1.564-.317z"
						/>
					</svg>
				</div>
				<p class="text-muted-foreground">暂无公告</p>
			</Card.Content>
		</Card.Root>
	{:else}
		<div class="space-y-4">
			{#each announcements as announcement (announcement.id)}
				<Card.Root
					class="cursor-pointer transition-colors hover:bg-muted/30"
				>
					<a href="/announcements/{announcement.id}" class="block">
						<Card.Content class="p-6">
							<div class="mb-2 flex items-center gap-2">
								<span class="rounded-full px-2 py-1 text-xs {getPriorityClass(announcement.priority)}">
									{AnnouncementPriorityLabel[announcement.priority]}
								</span>
								<span class="text-sm text-muted-foreground">
									{formatDateTime(announcement.publishTime || announcement.createTime)}
								</span>
								{#if announcement.author}
									<span class="text-sm text-muted-foreground">· {announcement.author}</span>
								{/if}
							</div>
							<h3 class="mb-2 text-lg font-semibold">{announcement.title}</h3>
							<p class="text-muted-foreground">{truncateContent(announcement.content)}</p>
						</Card.Content>
					</a>
				</Card.Root>
			{/each}
		</div>

		<!-- Pagination -->
		{#if total > pageSize}
			<div class="flex items-center justify-between">
				<p class="text-sm text-muted-foreground">共 {total} 条公告</p>
				<div class="flex gap-2">
					<Button
						variant="outline"
						size="sm"
						disabled={page <= 1}
						onclick={() => {
							page--;
							loadAnnouncements();
						}}
					>
						上一页
					</Button>
					<Button
						variant="outline"
						size="sm"
						disabled={page >= Math.ceil(total / pageSize)}
						onclick={() => {
							page++;
							loadAnnouncements();
						}}
					>
						下一页
					</Button>
				</div>
			</div>
		{/if}
	{/if}
</div>
