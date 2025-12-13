<script lang="ts">
	import { goto } from '$app/navigation';
	import { useNotifications } from '$stores/notifications.svelte';
	import { createTicket } from '$api/endpoints/tickets';
	import { TicketCategory, TicketPriority, TicketCategoryLabel, TicketPriorityLabel } from '$api/types';

	const notifications = useNotifications();

	let title = $state('');
	let content = $state('');
	let category = $state(TicketCategory.QUESTION);
	let priority = $state(TicketPriority.MEDIUM);
	let isSubmitting = $state(false);

	async function handleSubmit(e: Event) {
		e.preventDefault();

		if (!title.trim()) {
			notifications.warning('请填写标题');
			return;
		}

		if (!content.trim()) {
			notifications.warning('请填写工单内容');
			return;
		}

		isSubmitting = true;
		try {
			const result = await createTicket({ title, content, category, priority });
			notifications.success('工单已提交', `工单号：${result.ticketNo}`);
			await goto('/tickets');
		} catch (err) {
			notifications.error('提交失败', err instanceof Error ? err.message : '请稍后重试');
		} finally {
			isSubmitting = false;
		}
	}
</script>

<svelte:head>
	<title>创建工单 - 存证平台</title>
</svelte:head>

<div class="mx-auto max-w-2xl space-y-6">
	<div>
		<a href="/tickets" class="mb-2 inline-flex items-center gap-1 text-sm text-muted-foreground hover:text-foreground">
			<svg class="h-4 w-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
				<path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M10 19l-7-7m0 0l7-7m-7 7h18" />
			</svg>
			返回工单列表
		</a>
		<h1 class="text-2xl font-bold">创建工单</h1>
		<p class="text-muted-foreground">描述您遇到的问题或需求</p>
	</div>

	<form onsubmit={handleSubmit} class="space-y-6">
		<div class="rounded-lg border bg-card p-6 space-y-4">
			<div>
				<label for="title" class="mb-2 block text-sm font-medium">
					标题 <span class="text-destructive">*</span>
				</label>
				<input
					type="text"
					id="title"
					bind:value={title}
					placeholder="简要描述您的问题"
					class="w-full rounded-lg border bg-background px-3 py-2 text-sm focus:border-primary focus:outline-none"
					disabled={isSubmitting}
				/>
			</div>

			<div class="grid gap-4 sm:grid-cols-2">
				<div>
					<label for="category" class="mb-2 block text-sm font-medium">类别</label>
					<select
						id="category"
						bind:value={category}
						class="w-full rounded-lg border bg-background px-3 py-2 text-sm focus:border-primary focus:outline-none"
						disabled={isSubmitting}
					>
						{#each Object.entries(TicketCategoryLabel) as [value, label]}
							<option value={Number(value)}>{label}</option>
						{/each}
					</select>
				</div>

				<div>
					<label for="priority" class="mb-2 block text-sm font-medium">优先级</label>
					<select
						id="priority"
						bind:value={priority}
						class="w-full rounded-lg border bg-background px-3 py-2 text-sm focus:border-primary focus:outline-none"
						disabled={isSubmitting}
					>
						{#each Object.entries(TicketPriorityLabel) as [value, label]}
							<option value={Number(value)}>{label}</option>
						{/each}
					</select>
				</div>
			</div>

			<div>
				<label for="content" class="mb-2 block text-sm font-medium">
					详细描述 <span class="text-destructive">*</span>
				</label>
				<textarea
					id="content"
					bind:value={content}
					rows="8"
					placeholder="请详细描述您的问题或需求，包括操作步骤、错误信息等..."
					class="w-full rounded-lg border bg-background px-3 py-2 text-sm focus:border-primary focus:outline-none resize-none"
					disabled={isSubmitting}
				></textarea>
			</div>
		</div>

		<div class="flex justify-end gap-4">
			<a
				href="/tickets"
				class="rounded-lg border px-4 py-2 text-sm font-medium hover:bg-accent"
			>
				取消
			</a>
			<button
				type="submit"
				class="rounded-lg bg-primary px-4 py-2 text-sm font-medium text-primary-foreground hover:bg-primary/90 disabled:opacity-50"
				disabled={isSubmitting}
			>
				{isSubmitting ? '提交中...' : '提交工单'}
			</button>
		</div>
	</form>
</div>
