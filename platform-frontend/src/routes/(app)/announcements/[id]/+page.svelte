<script lang="ts">
	import { onMount } from 'svelte';
	import { goto } from '$app/navigation';
	import { useNotifications } from '$stores/notifications.svelte';
	import { formatDateTime } from '$utils/format';
	import { getAnnouncement, markAnnouncementAsRead } from '$api/endpoints/messages';
	import type { AnnouncementVO } from '$api/types';
	import { AnnouncementPriority, AnnouncementPriorityLabel } from '$api/types';
	import * as Card from '$lib/components/ui/card';
	import { Button } from '$lib/components/ui/button';
	import { Separator } from '$lib/components/ui/separator';

	let { data } = $props();

	const notifications = useNotifications();

	let announcement = $state<AnnouncementVO | null>(null);
	let loading = $state(true);
	let error = $state<string | null>(null);

	onMount(() => {
		loadAnnouncement();
	});

	async function loadAnnouncement() {
		loading = true;
		error = null;

		try {
			announcement = await getAnnouncement(data.announcementId);
			// 标记为已读
			await markAnnouncementAsRead(data.announcementId).catch(() => {
				// 标记已读失败时忽略错误
			});
		} catch (err) {
			error = err instanceof Error ? err.message : '加载失败';
			notifications.error('加载失败', error);
		} finally {
			loading = false;
		}
	}

	function getPriorityClass(priority: AnnouncementPriority): string {
		switch (priority) {
			case AnnouncementPriority.URGENT:
				return 'bg-red-100 text-red-700 dark:bg-red-900 dark:text-red-300';
			case AnnouncementPriority.IMPORTANT:
				return 'bg-orange-100 text-orange-700 dark:bg-orange-900 dark:text-orange-300';
			case AnnouncementPriority.NORMAL:
				return 'bg-blue-100 text-blue-700 dark:bg-blue-900 dark:text-blue-300';
			default:
				return 'bg-blue-100 text-blue-700';
		}
	}
</script>

<svelte:head>
	<title>{announcement?.title ?? '公告详情'} - 存证平台</title>
</svelte:head>

<div class="mx-auto max-w-3xl space-y-6">
	<!-- 返回按钮 -->
	<a
		href="/announcements"
		class="inline-flex items-center gap-1 text-sm text-muted-foreground hover:text-foreground"
	>
		<svg class="h-4 w-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
			<path
				stroke-linecap="round"
				stroke-linejoin="round"
				stroke-width="2"
				d="M10 19l-7-7m0 0l7-7m-7 7h18"
			/>
		</svg>
		返回公告列表
	</a>

	{#if loading}
		<Card.Root>
			<Card.Content class="flex items-center justify-center p-12">
				<div
					class="h-8 w-8 animate-spin rounded-full border-4 border-primary border-t-transparent"
				></div>
			</Card.Content>
		</Card.Root>
	{:else if error}
		<Card.Root>
			<Card.Content class="p-12 text-center">
				<div
					class="mx-auto mb-4 flex h-16 w-16 items-center justify-center rounded-full bg-destructive/10"
				>
					<svg
						class="h-8 w-8 text-destructive"
						fill="none"
						stroke="currentColor"
						viewBox="0 0 24 24"
					>
						<path
							stroke-linecap="round"
							stroke-linejoin="round"
							stroke-width="2"
							d="M12 9v2m0 4h.01m-6.938 4h13.856c1.54 0 2.502-1.667 1.732-3L13.732 4c-.77-1.333-2.694-1.333-3.464 0L3.34 16c-.77 1.333.192 3 1.732 3z"
						/>
					</svg>
				</div>
				<p class="text-muted-foreground">{error}</p>
				<Button variant="outline" class="mt-4" onclick={() => goto('/announcements')}>
					返回公告列表
				</Button>
			</Card.Content>
		</Card.Root>
	{:else if announcement}
		<Card.Root>
			<Card.Header>
				<div class="flex items-start justify-between">
					<div class="space-y-2">
						<div class="flex items-center gap-2">
							<span
								class="rounded-full px-2 py-1 text-xs {getPriorityClass(announcement.priority)}"
							>
								{AnnouncementPriorityLabel[announcement.priority]}
							</span>
						</div>
						<Card.Title class="text-2xl">{announcement.title}</Card.Title>
						<Card.Description class="flex items-center gap-2">
							<svg class="h-4 w-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
								<path
									stroke-linecap="round"
									stroke-linejoin="round"
									stroke-width="2"
									d="M8 7V3m8 4V3m-9 8h10M5 21h14a2 2 0 002-2V7a2 2 0 00-2-2H5a2 2 0 00-2 2v12a2 2 0 002 2z"
								/>
							</svg>
							发布于 {formatDateTime(announcement.publishTime || announcement.createTime)}
							{#if announcement.author}
								<span>·</span>
								<svg class="h-4 w-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
									<path
										stroke-linecap="round"
										stroke-linejoin="round"
										stroke-width="2"
										d="M16 7a4 4 0 11-8 0 4 4 0 018 0zM12 14a7 7 0 00-7 7h14a7 7 0 00-7-7z"
									/>
								</svg>
								{announcement.author}
							{/if}
						</Card.Description>
					</div>
				</div>
			</Card.Header>
			<Separator />
			<Card.Content class="pt-6">
				<div class="prose prose-neutral max-w-none dark:prose-invert">
					<div class="whitespace-pre-wrap">{announcement.content}</div>
				</div>
			</Card.Content>
		</Card.Root>
	{/if}
</div>
