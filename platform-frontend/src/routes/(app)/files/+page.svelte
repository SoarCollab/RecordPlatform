<script lang="ts">
	import { onMount } from 'svelte';
	import { goto } from '$app/navigation';
	import { useNotifications } from '$stores/notifications.svelte';
	import { formatFileSize, formatDateTime, getFileIcon } from '$utils/format';
	import { getFiles, deleteFile, downloadEncryptedChunks, getDecryptInfo, createShare } from '$api/endpoints/files';
	import { FileStatus, FileStatusLabel, type FileVO, type Page } from '$api/types';
	import { decryptFile, arrayToBlob, downloadBlob } from '$utils/crypto';

	const notifications = useNotifications();

	let files = $state<FileVO[]>([]);
	let loading = $state(true);
	let page = $state(1);
	let total = $state(0);
	let pageSize = $state(10);
	let keyword = $state('');
	let statusFilter = $state<FileStatus | undefined>(undefined);

	// Share dialog state
	let shareDialogOpen = $state(false);
	let shareFile = $state<FileVO | null>(null);
	let shareExpireHours = $state(72);
	let shareMaxDownloads = $state<number | undefined>(undefined);
	let shareCode = $state('');
	let downloading = $state<string | null>(null);

	onMount(() => {
		loadFiles();
	});

	async function loadFiles() {
		loading = true;
		try {
			const result = await getFiles({
				current: page,
				size: pageSize,
				keyword: keyword || undefined,
				status: statusFilter
			});
			files = result.records;
			total = result.total;
		} catch (err) {
			notifications.error('加载失败', err instanceof Error ? err.message : '请稍后重试');
		} finally {
			loading = false;
		}
	}

	async function handleDelete(file: FileVO) {
		if (!confirm(`确定要删除文件 "${file.fileName}" 吗？`)) return;

		try {
			await deleteFile(file.id);
			notifications.success('删除成功');
			await loadFiles();
		} catch (err) {
			notifications.error('删除失败', err instanceof Error ? err.message : '请稍后重试');
		}
	}

	async function handleDownload(file: FileVO) {
		if (downloading) {
			notifications.warning('下载中', '请等待当前下载完成');
			return;
		}

		downloading = file.id;
		try {
			notifications.info('准备下载', '正在获取文件解密信息...');

			// 1. 获取解密信息（包含初始密钥）
			const decryptInfo = await getDecryptInfo(file.fileHash);

			// 2. 获取加密分片
			notifications.info('下载中', '正在下载加密分片...');
			const encryptedChunksBase64 = await downloadEncryptedChunks(file.fileHash);

			// 将 Base64 转换为 Uint8Array
			const chunks = encryptedChunksBase64.map((base64) => {
				const binary = atob(base64);
				const bytes = new Uint8Array(binary.length);
				for (let i = 0; i < binary.length; i++) {
					bytes[i] = binary.charCodeAt(i);
				}
				return bytes;
			});

			// 3. 解密分片
			notifications.info('解密中', '正在解密文件...');
			const decryptedData = await decryptFile(chunks, decryptInfo.initialKey);

			// 4. 创建 Blob 并下载
			const blob = arrayToBlob(decryptedData, decryptInfo.contentType || 'application/octet-stream');
			downloadBlob(blob, decryptInfo.fileName || file.fileName);

			notifications.success('下载完成', file.fileName);
		} catch (err) {
			console.error('下载失败:', err);
			notifications.error('下载失败', err instanceof Error ? err.message : '请稍后重试');
		} finally {
			downloading = null;
		}
	}

	function openShareDialog(file: FileVO) {
		shareFile = file;
		shareCode = '';
		shareDialogOpen = true;
	}

	async function handleShare() {
		if (!shareFile) return;

		try {
			const result = await createShare({
				fileId: shareFile.id,
				expireHours: shareExpireHours,
				maxDownloads: shareMaxDownloads
			});
			shareCode = result.shareCode;
			notifications.success('分享链接已创建');
		} catch (err) {
			notifications.error('创建分享失败', err instanceof Error ? err.message : '请稍后重试');
		}
	}

	function copyShareLink() {
		const link = `${window.location.origin}/share/${shareCode}`;
		navigator.clipboard.writeText(link);
		notifications.success('已复制到剪贴板');
	}

	function getStatusClass(status: FileStatus): string {
		switch (status) {
			case FileStatus.COMPLETED:
				return 'bg-green-100 text-green-700';
			case FileStatus.FAILED:
				return 'bg-red-100 text-red-700';
			default:
				return 'bg-yellow-100 text-yellow-700';
		}
	}
</script>

<svelte:head>
	<title>文件管理 - 存证平台</title>
</svelte:head>

<div class="space-y-6">
	<div class="flex items-center justify-between">
		<div>
			<h1 class="text-2xl font-bold">文件管理</h1>
			<p class="text-muted-foreground">管理您的存证文件</p>
		</div>
		<a
			href="/upload"
			class="flex items-center gap-2 rounded-lg bg-primary px-4 py-2 text-sm font-medium text-primary-foreground hover:bg-primary/90"
		>
			<svg class="h-4 w-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
				<path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M12 4v16m8-8H4" />
			</svg>
			上传文件
		</a>
	</div>

	<!-- Filters -->
	<div class="flex flex-wrap gap-4">
		<input
			type="text"
			placeholder="搜索文件名..."
			bind:value={keyword}
			onkeypress={(e) => e.key === 'Enter' && loadFiles()}
			class="rounded-lg border bg-background px-3 py-2 text-sm focus:border-primary focus:outline-none"
		/>
		<select
			bind:value={statusFilter}
			onchange={loadFiles}
			class="rounded-lg border bg-background px-3 py-2 text-sm focus:border-primary focus:outline-none"
		>
			<option value={undefined}>全部状态</option>
			{#each Object.entries(FileStatusLabel) as [value, label]}
				<option value={Number(value)}>{label}</option>
			{/each}
		</select>
		<button
			class="rounded-lg border px-4 py-2 text-sm hover:bg-accent"
			onclick={loadFiles}
		>
			搜索
		</button>
	</div>

	<!-- File list -->
	<div class="rounded-lg border bg-card">
		{#if loading}
			<div class="flex items-center justify-center p-12">
				<div class="h-8 w-8 animate-spin rounded-full border-4 border-primary border-t-transparent"></div>
			</div>
		{:else if files.length === 0}
			<div class="p-12 text-center">
				<div class="mx-auto mb-4 flex h-16 w-16 items-center justify-center rounded-full bg-muted">
					<svg class="h-8 w-8 text-muted-foreground" fill="none" stroke="currentColor" viewBox="0 0 24 24">
						<path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M3 7v10a2 2 0 002 2h14a2 2 0 002-2V9a2 2 0 00-2-2h-6l-2-2H5a2 2 0 00-2 2z" />
					</svg>
				</div>
				<p class="text-muted-foreground">暂无文件</p>
				<a href="/upload" class="mt-2 inline-block text-primary hover:underline">立即上传</a>
			</div>
		{:else}
			<div class="overflow-x-auto">
				<table class="w-full">
					<thead class="border-b bg-muted/50">
						<tr>
							<th class="px-4 py-3 text-left text-sm font-medium">文件名</th>
							<th class="px-4 py-3 text-left text-sm font-medium">大小</th>
							<th class="px-4 py-3 text-left text-sm font-medium">状态</th>
							<th class="px-4 py-3 text-left text-sm font-medium">上传时间</th>
							<th class="px-4 py-3 text-right text-sm font-medium">操作</th>
						</tr>
					</thead>
					<tbody class="divide-y">
						{#each files as file (file.id)}
							<tr class="hover:bg-muted/30">
								<td class="px-4 py-3">
									<div class="flex items-center gap-3">
										<div class="flex h-10 w-10 shrink-0 items-center justify-center rounded-lg bg-primary/10 text-primary">
											<svg class="h-5 w-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
												<path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M9 12h6m-6 4h6m2 5H7a2 2 0 01-2-2V5a2 2 0 012-2h5.586a1 1 0 01.707.293l5.414 5.414a1 1 0 01.293.707V19a2 2 0 01-2 2z" />
											</svg>
										</div>
										<div class="min-w-0">
											<p class="truncate font-medium">{file.fileName}</p>
											{#if file.transactionHash}
												<p class="truncate text-xs text-muted-foreground" title={file.transactionHash}>
													TX: {file.transactionHash.slice(0, 16)}...
												</p>
											{/if}
										</div>
									</div>
								</td>
								<td class="px-4 py-3 text-sm text-muted-foreground">
									{formatFileSize(file.fileSize)}
								</td>
								<td class="px-4 py-3">
									<span class="rounded-full px-2 py-1 text-xs {getStatusClass(file.status)}">
										{FileStatusLabel[file.status]}
									</span>
								</td>
								<td class="px-4 py-3 text-sm text-muted-foreground">
									{formatDateTime(file.createTime)}
								</td>
								<td class="px-4 py-3">
									<div class="flex justify-end gap-2">
										<button
											class="rounded p-1 hover:bg-accent"
											onclick={() => goto(`/files/${file.fileHash}`)}
											title="详情"
										>
											<svg class="h-4 w-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
												<path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M15 12a3 3 0 11-6 0 3 3 0 016 0z" />
												<path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M2.458 12C3.732 7.943 7.523 5 12 5c4.478 0 8.268 2.943 9.542 7-1.274 4.057-5.064 7-9.542 7-4.477 0-8.268-2.943-9.542-7z" />
											</svg>
										</button>
										{#if file.status === FileStatus.COMPLETED}
											<button
												class="rounded p-1 hover:bg-accent disabled:opacity-50"
												onclick={() => handleDownload(file)}
												disabled={downloading === file.id}
												title={downloading === file.id ? '下载中...' : '下载'}
											>
												{#if downloading === file.id}
													<svg class="h-4 w-4 animate-spin" viewBox="0 0 24 24">
														<circle class="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" stroke-width="4" fill="none"></circle>
														<path class="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4zm2 5.291A7.962 7.962 0 014 12H0c0 3.042 1.135 5.824 3 7.938l3-2.647z"></path>
													</svg>
												{:else}
													<svg class="h-4 w-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
														<path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M4 16v1a3 3 0 003 3h10a3 3 0 003-3v-1m-4-4l-4 4m0 0l-4-4m4 4V4" />
													</svg>
												{/if}
											</button>
											<button
												class="rounded p-1 hover:bg-accent"
												onclick={() => openShareDialog(file)}
												title="分享"
											>
												<svg class="h-4 w-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
													<path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M8.684 13.342C8.886 12.938 9 12.482 9 12c0-.482-.114-.938-.316-1.342m0 2.684a3 3 0 110-2.684m0 2.684l6.632 3.316m-6.632-6l6.632-3.316m0 0a3 3 0 105.367-2.684 3 3 0 00-5.367 2.684zm0 9.316a3 3 0 105.368 2.684 3 3 0 00-5.368-2.684z" />
												</svg>
											</button>
										{/if}
										<button
											class="rounded p-1 text-destructive hover:bg-destructive/10"
											onclick={() => handleDelete(file)}
											title="删除"
										>
											<svg class="h-4 w-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
												<path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M19 7l-.867 12.142A2 2 0 0116.138 21H7.862a2 2 0 01-1.995-1.858L5 7m5 4v6m4-6v6m1-10V4a1 1 0 00-1-1h-4a1 1 0 00-1 1v3M4 7h16" />
											</svg>
										</button>
									</div>
								</td>
							</tr>
						{/each}
					</tbody>
				</table>
			</div>

			<!-- Pagination -->
			{#if total > pageSize}
				<div class="flex items-center justify-between border-t p-4">
					<p class="text-sm text-muted-foreground">
						共 {total} 个文件
					</p>
					<div class="flex gap-2">
						<button
							class="rounded-lg border px-3 py-1 text-sm hover:bg-accent disabled:opacity-50"
							disabled={page <= 1}
							onclick={() => { page--; loadFiles(); }}
						>
							上一页
						</button>
						<span class="px-3 py-1 text-sm">
							{page} / {Math.ceil(total / pageSize)}
						</span>
						<button
							class="rounded-lg border px-3 py-1 text-sm hover:bg-accent disabled:opacity-50"
							disabled={page >= Math.ceil(total / pageSize)}
							onclick={() => { page++; loadFiles(); }}
						>
							下一页
						</button>
					</div>
				</div>
			{/if}
		{/if}
	</div>
</div>

<!-- Share Dialog -->
{#if shareDialogOpen}
	<!-- svelte-ignore a11y_click_events_have_key_events a11y_no_static_element_interactions -->
	<div
		class="fixed inset-0 z-50 flex items-center justify-center bg-black/50"
		onclick={() => shareDialogOpen = false}
		role="dialog"
		aria-modal="true"
		aria-labelledby="share-dialog-title"
		tabindex="-1"
	>
		<!-- svelte-ignore a11y_no_static_element_interactions -->
		<div class="w-full max-w-md rounded-lg bg-card p-6 shadow-lg" onclick={(e) => e.stopPropagation()}>
			<h3 id="share-dialog-title" class="mb-4 text-lg font-semibold">分享文件</h3>

			{#if shareCode}
				<div class="space-y-4">
					<p class="text-sm text-muted-foreground">分享链接已创建，请复制以下链接：</p>
					<div class="flex gap-2">
						<input
							type="text"
							readonly
							value={`${window.location.origin}/share/${shareCode}`}
							class="flex-1 rounded-lg border bg-muted px-3 py-2 text-sm"
						/>
						<button
							class="rounded-lg bg-primary px-4 py-2 text-sm text-primary-foreground hover:bg-primary/90"
							onclick={copyShareLink}
						>
							复制
						</button>
					</div>
				</div>
			{:else}
				<div class="space-y-4">
					<p class="text-sm text-muted-foreground">
						正在分享：{shareFile?.fileName}
					</p>

					<div>
						<label for="share-expire" class="mb-2 block text-sm font-medium">有效期</label>
						<select
							id="share-expire"
							bind:value={shareExpireHours}
							class="w-full rounded-lg border bg-background px-3 py-2 text-sm"
						>
							<option value={24}>24 小时</option>
							<option value={72}>3 天</option>
							<option value={168}>7 天</option>
							<option value={720}>30 天</option>
						</select>
					</div>

					<div>
						<label for="share-max-downloads" class="mb-2 block text-sm font-medium">下载次数限制（可选）</label>
						<input
							id="share-max-downloads"
							type="number"
							bind:value={shareMaxDownloads}
							placeholder="不限制"
							min="1"
							class="w-full rounded-lg border bg-background px-3 py-2 text-sm"
						/>
					</div>
				</div>
			{/if}

			<div class="mt-6 flex justify-end gap-2">
				<button
					class="rounded-lg border px-4 py-2 text-sm hover:bg-accent"
					onclick={() => shareDialogOpen = false}
				>
					{shareCode ? '关闭' : '取消'}
				</button>
				{#if !shareCode}
					<button
						class="rounded-lg bg-primary px-4 py-2 text-sm text-primary-foreground hover:bg-primary/90"
						onclick={handleShare}
					>
						创建分享
					</button>
				{/if}
			</div>
		</div>
	</div>
{/if}
