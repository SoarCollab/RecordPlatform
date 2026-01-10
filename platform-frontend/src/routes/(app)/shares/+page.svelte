<script lang="ts">
	import { onMount } from 'svelte';
	import { browser } from '$app/environment';
	import { page as appPage } from '$app/state';
	import { useNotifications } from '$stores/notifications.svelte';
	import { formatDateTime } from '$utils/format';
	import { getMyShares, cancelShare, updateShare } from '$api/endpoints/files';
	import {
		ShareType,
		ShareTypeLabel,
		ShareTypeDesc,
		type FileShareVO,
	} from '$api/types';

	const notifications = useNotifications();

	let shares = $state<FileShareVO[]>([]);
	let loading = $state(true);
	let page = $state(1);
	let total = $state(0);
	let pageSize = $state(10);

	// Edit dialog state
	let editDialogOpen = $state(false);
	let editingShare = $state<FileShareVO | null>(null);
	let editShareType = $state<ShareType>(ShareType.PUBLIC);
	let editExtendHours = $state(0);
	let saving = $state(false);

	onMount(() => {
		loadShares();
	});

	async function loadShares() {
		loading = true;
		try {
			const result = await getMyShares({ pageNum: page, pageSize });
			shares = result.records;
			total = result.total;
		} catch (err) {
			notifications.error('加载失败', err instanceof Error ? err.message : '请稍后重试');
		} finally {
			loading = false;
		}
	}

	function getStatusBadge(status: number): { class: string; label: string } {
		switch (status) {
			case 1:
				return { class: 'bg-green-100 text-green-700', label: '有效' };
			case 2:
				return { class: 'bg-yellow-100 text-yellow-700', label: '已过期' };
			case 0:
			default:
				return { class: 'bg-gray-100 text-gray-700', label: '已取消' };
		}
	}

	async function copyShareLink(shareCode: string) {
		if (!browser) return;
		const link = `${appPage.url.origin}/share/${shareCode}`;

		try {
			await navigator.clipboard.writeText(link);
			notifications.success('已复制到剪贴板');
			return;
		} catch {
			// ignore
		}

		try {
			const textarea = document.createElement('textarea');
			textarea.value = link;
			textarea.setAttribute('readonly', '');
			textarea.style.position = 'fixed';
			textarea.style.top = '0';
			textarea.style.left = '0';
			textarea.style.opacity = '0';
			document.body.appendChild(textarea);
			textarea.select();
			textarea.setSelectionRange(0, textarea.value.length);

			const ok = document.execCommand('copy');
			document.body.removeChild(textarea);

			if (ok) {
				notifications.success('已复制到剪贴板');
			} else {
				notifications.warning('复制失败', '请手动复制分享链接');
			}
		} catch {
			notifications.warning('复制失败', '请手动复制分享链接');
		}
	}

	function openEditDialog(share: FileShareVO) {
		editingShare = share;
		editShareType = share.shareType;
		editExtendHours = 0;
		editDialogOpen = true;
	}

	async function handleSaveEdit() {
		if (!editingShare) return;

		saving = true;
		try {
			await updateShare({
				shareCode: editingShare.sharingCode,
				shareType: editShareType,
				extendMinutes: editExtendHours > 0 ? editExtendHours * 60 : undefined,
			});
			notifications.success('分享设置已更新');
			editDialogOpen = false;
			await loadShares();
		} catch (err) {
			notifications.error('更新失败', err instanceof Error ? err.message : '请稍后重试');
		} finally {
			saving = false;
		}
	}

	async function handleCancelShare(share: FileShareVO) {
		if (!confirm(`确定要取消分享 "${share.fileNames.join(', ')}" 吗？取消后他人将无法通过此链接访问。`)) {
			return;
		}

		try {
			await cancelShare(share.sharingCode);
			notifications.success('分享已取消');
			await loadShares();
		} catch (err) {
			notifications.error('取消失败', err instanceof Error ? err.message : '请稍后重试');
		}
	}

	function goToPage(newPage: number) {
		if (newPage < 1 || newPage > Math.ceil(total / pageSize)) return;
		page = newPage;
		loadShares();
	}
</script>

<svelte:head>
	<title>分享管理 - 存证平台</title>
</svelte:head>

<div class="space-y-6">
	<div>
		<h1 class="text-2xl font-bold">分享管理</h1>
		<p class="text-muted-foreground">管理您创建的文件分享链接</p>
	</div>

	<div class="rounded-lg border bg-card">
		{#if loading}
			<div class="flex h-64 items-center justify-center">
				<div class="h-8 w-8 animate-spin rounded-full border-4 border-primary border-t-transparent"></div>
			</div>
		{:else if shares.length === 0}
			<div class="flex h-64 flex-col items-center justify-center gap-4 text-muted-foreground">
				<svg class="h-12 w-12" fill="none" stroke="currentColor" viewBox="0 0 24 24">
					<path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M8.684 13.342C8.886 12.938 9 12.482 9 12c0-.482-.114-.938-.316-1.342m0 2.684a3 3 0 110-2.684m0 2.684l6.632 3.316m-6.632-6l6.632-3.316m0 0a3 3 0 105.367-2.684 3 3 0 00-5.367 2.684zm0 9.316a3 3 0 105.368 2.684 3 3 0 00-5.368-2.684z" />
				</svg>
				<p>暂无分享记录</p>
				<a href="/files" class="text-primary hover:underline">去文件列表创建分享</a>
			</div>
		{:else}
			<div class="overflow-x-auto">
				<table class="w-full">
					<thead class="border-b bg-muted/50">
						<tr>
							<th class="px-4 py-3 text-left text-sm font-medium">文件</th>
							<th class="px-4 py-3 text-left text-sm font-medium">分享类型</th>
							<th class="px-4 py-3 text-left text-sm font-medium">状态</th>
							<th class="px-4 py-3 text-left text-sm font-medium">过期时间</th>
							<th class="px-4 py-3 text-left text-sm font-medium">访问次数</th>
							<th class="px-4 py-3 text-left text-sm font-medium">创建时间</th>
							<th class="px-4 py-3 text-right text-sm font-medium">操作</th>
						</tr>
					</thead>
					<tbody class="divide-y">
						{#each shares as share}
							{@const status = getStatusBadge(share.status)}
							<tr class="hover:bg-muted/30">
								<td class="px-4 py-3">
									<div class="max-w-xs">
										{#each share.fileNames as fileName}
											<div class="truncate text-sm" title={fileName}>
												{fileName}
											</div>
										{/each}
									</div>
								</td>
								<td class="px-4 py-3">
									<span class="inline-flex items-center gap-1.5 rounded-full px-2 py-1 text-xs font-medium
										{share.shareType === ShareType.PUBLIC ? 'bg-blue-100 text-blue-700' : 'bg-purple-100 text-purple-700'}">
										{#if share.shareType === ShareType.PUBLIC}
											<svg class="h-3 w-3" fill="none" stroke="currentColor" viewBox="0 0 24 24">
												<path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M3.055 11H5a2 2 0 012 2v1a2 2 0 002 2 2 2 0 012 2v2.945M8 3.935V5.5A2.5 2.5 0 0010.5 8h.5a2 2 0 012 2 2 2 0 104 0 2 2 0 012-2h1.064M15 20.488V18a2 2 0 012-2h3.064M21 12a9 9 0 11-18 0 9 9 0 0118 0z" />
											</svg>
										{:else}
											<svg class="h-3 w-3" fill="none" stroke="currentColor" viewBox="0 0 24 24">
												<path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M12 15v2m-6 4h12a2 2 0 002-2v-6a2 2 0 00-2-2H6a2 2 0 00-2 2v6a2 2 0 002 2zm10-10V7a4 4 0 00-8 0v4h8z" />
											</svg>
										{/if}
										{ShareTypeLabel[share.shareType]}
									</span>
								</td>
								<td class="px-4 py-3">
									<span class="rounded-full px-2 py-1 text-xs font-medium {status.class}">
										{status.label}
									</span>
								</td>
								<td class="px-4 py-3 text-sm text-muted-foreground">
									{share.expireTime ? formatDateTime(share.expireTime) : '永久'}
								</td>
								<td class="px-4 py-3 text-sm">
									{share.accessCount}
									{#if share.maxAccesses}
										<span class="text-muted-foreground">/ {share.maxAccesses}</span>
									{/if}
								</td>
								<td class="px-4 py-3 text-sm text-muted-foreground">
									{formatDateTime(share.createTime)}
								</td>
								<td class="px-4 py-3">
									<div class="flex justify-end gap-2">
										{#if share.status === 1}
											<button
												class="rounded p-1.5 text-muted-foreground hover:bg-muted hover:text-foreground"
												title="复制链接"
												onclick={() => copyShareLink(share.sharingCode)}
											>
												<svg class="h-4 w-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
													<path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M8 16H6a2 2 0 01-2-2V6a2 2 0 012-2h8a2 2 0 012 2v2m-6 12h8a2 2 0 002-2v-8a2 2 0 00-2-2h-8a2 2 0 00-2 2v8a2 2 0 002 2z" />
												</svg>
											</button>
											<button
												class="rounded p-1.5 text-muted-foreground hover:bg-muted hover:text-foreground"
												title="编辑"
												onclick={() => openEditDialog(share)}
											>
												<svg class="h-4 w-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
													<path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M11 5H6a2 2 0 00-2 2v11a2 2 0 002 2h11a2 2 0 002-2v-5m-1.414-9.414a2 2 0 112.828 2.828L11.828 15H9v-2.828l8.586-8.586z" />
												</svg>
											</button>
											<button
												class="rounded p-1.5 text-destructive hover:bg-destructive/10"
												title="取消分享"
												onclick={() => handleCancelShare(share)}
											>
												<svg class="h-4 w-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
													<path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M19 7l-.867 12.142A2 2 0 0116.138 21H7.862a2 2 0 01-1.995-1.858L5 7m5 4v6m4-6v6m1-10V4a1 1 0 00-1-1h-4a1 1 0 00-1 1v3M4 7h16" />
												</svg>
											</button>
										{:else}
											<span class="text-xs text-muted-foreground">-</span>
										{/if}
									</div>
								</td>
							</tr>
						{/each}
					</tbody>
				</table>
			</div>

			<!-- Pagination -->
			{#if total > pageSize}
				<div class="flex items-center justify-between border-t px-4 py-3">
					<div class="text-sm text-muted-foreground">
						共 {total} 条记录
					</div>
					<div class="flex gap-2">
						<button
							class="rounded-lg border px-3 py-1.5 text-sm disabled:opacity-50"
							disabled={page === 1}
							onclick={() => goToPage(page - 1)}
						>
							上一页
						</button>
						<span class="flex items-center px-3 text-sm">
							{page} / {Math.ceil(total / pageSize)}
						</span>
						<button
							class="rounded-lg border px-3 py-1.5 text-sm disabled:opacity-50"
							disabled={page >= Math.ceil(total / pageSize)}
							onclick={() => goToPage(page + 1)}
						>
							下一页
						</button>
					</div>
				</div>
			{/if}
		{/if}
	</div>
</div>

<!-- Edit Dialog -->
{#if editDialogOpen}
	<!-- svelte-ignore a11y_click_events_have_key_events a11y_no_static_element_interactions -->
	<div
		class="fixed inset-0 z-50 flex items-center justify-center bg-black/50"
		onclick={() => editDialogOpen = false}
		role="dialog"
		aria-modal="true"
		tabindex="-1"
	>
		<!-- svelte-ignore a11y_no_static_element_interactions -->
		<div class="w-full max-w-md rounded-lg bg-card p-6 shadow-lg" onclick={(e) => e.stopPropagation()}>
			<h3 class="mb-4 text-lg font-semibold">编辑分享设置</h3>

			<div class="space-y-4">
				<div>
					<span class="mb-2 block text-sm font-medium">分享类型</span>
					<div class="flex gap-3">
						{#each [ShareType.PUBLIC, ShareType.PRIVATE] as type}
							<label
								class="flex flex-1 cursor-pointer items-start gap-3 rounded-lg border p-3 transition-colors
									{editShareType === type ? 'border-primary bg-primary/5' : 'border-border hover:bg-accent/50'}"
							>
								<input
									type="radio"
									name="edit-share-type"
									value={type}
									bind:group={editShareType}
									class="mt-0.5"
								/>
								<div>
									<div class="font-medium">{ShareTypeLabel[type]}</div>
									<div class="text-xs text-muted-foreground">{ShareTypeDesc[type]}</div>
								</div>
							</label>
						{/each}
					</div>
				</div>

				<div>
					<label for="edit-extend" class="mb-2 block text-sm font-medium">延长有效期</label>
					<select
						id="edit-extend"
						bind:value={editExtendHours}
						class="w-full rounded-lg border bg-background px-3 py-2 text-sm"
					>
						<option value={0}>不延长</option>
						<option value={24}>延长 24 小时</option>
						<option value={72}>延长 3 天</option>
						<option value={168}>延长 7 天</option>
						<option value={720}>延长 30 天</option>
					</select>
				</div>
			</div>

			<div class="mt-6 flex justify-end gap-2">
				<button
					class="rounded-lg border px-4 py-2 text-sm hover:bg-accent"
					onclick={() => editDialogOpen = false}
				>
					取消
				</button>
				<button
					class="rounded-lg bg-primary px-4 py-2 text-sm text-primary-foreground hover:bg-primary/90 disabled:opacity-50"
					disabled={saving}
					onclick={handleSaveEdit}
				>
					{saving ? '保存中...' : '保存'}
				</button>
			</div>
		</div>
	</div>
{/if}
