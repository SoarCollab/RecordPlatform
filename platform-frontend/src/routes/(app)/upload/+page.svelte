<script lang="ts">
	import { useUpload, type UploadTask } from '$stores/upload.svelte';
	import { useNotifications } from '$stores/notifications.svelte';
	import { formatFileSize, formatSpeed } from '$utils/format';
	import * as Dialog from '$lib/components/ui/dialog';
	import { Button } from '$lib/components/ui/button';

	const upload = useUpload();
	const uploadOps = upload as unknown as {
		clearFailedAndCancelled: () => void;
		retryAllFailedAndCancelled: () => Promise<number>;
		cancelAllActiveAndProcessing: () => Promise<number>;
	};
	const notifications = useNotifications();

	const MAX_FILE_SIZE_BYTES = 4 * 1024 * 1024 * 1024;

	let dragOver = $state(false);
	let fileInput: HTMLInputElement;

	let removeDialogOpen = $state(false);
	let removeTarget = $state<UploadTask | null>(null);
	let clearDialogOpen = $state(false);
	let clearFailedDialogOpen = $state(false);
	let retryAllDialogOpen = $state(false);
	let cancelAllDialogOpen = $state(false);

	let bulkBusy = $state(false);
	let busyIds = $state<Set<string>>(new Set());

	function setBusy(id: string, value: boolean): void {
		const next = new Set(busyIds);
		if (value) next.add(id);
		else next.delete(id);
		busyIds = next;
	}

	function isBusy(id: string): boolean {
		return busyIds.has(id) || bulkBusy;
	}

	type UploadTaskView = Omit<UploadTask, 'status'> & {
		status: string;
		processProgress?: number;
		serverProgress?: number;
	};

	let cancelledCount = $derived(
		upload.tasks.filter((t) => (t as unknown as UploadTaskView).status === 'cancelled').length,
	);
	let pausedCount = $derived(
		upload.tasks.filter((t) => (t as unknown as UploadTaskView).status === 'paused').length,
	);
	let processingCount = $derived(
		upload.tasks.filter((t) => (t as unknown as UploadTaskView).status === 'processing').length,
	);
	let retryableCount = $derived(upload.failedTasks.length + cancelledCount);
	let cancelableCount = $derived(upload.activeTasks.length + processingCount + pausedCount);

	function openRemoveDialog(task: UploadTask) {
		if (isBusy(task.id)) return;
		removeTarget = task;
		removeDialogOpen = true;
	}

	function confirmRemove() {
		if (!removeTarget) return;
		upload.removeTask(removeTarget.id);
		notifications.info('已移除任务', removeTarget.file.name);
		removeDialogOpen = false;
		removeTarget = null;
	}

	function openClearDialog() {
		clearDialogOpen = true;
	}

	function confirmClearCompleted() {
		upload.clearCompleted();
		notifications.success('已清除已完成任务');
		clearDialogOpen = false;
	}

	function openClearFailedDialog() {
		clearFailedDialogOpen = true;
	}

	function confirmClearFailedAndCancelled() {
		uploadOps.clearFailedAndCancelled();
		notifications.success('已清除失败/取消任务');
		clearFailedDialogOpen = false;
	}

	function openRetryAllDialog() {
		retryAllDialogOpen = true;
	}

	async function confirmRetryAll() {
		bulkBusy = true;
		try {
			const count = await uploadOps.retryAllFailedAndCancelled();
			if (count > 0) {
				notifications.success('已开始重试', `共 ${count} 个任务`);
			}
			retryAllDialogOpen = false;
		} catch (err) {
			notifications.error('批量重试失败', err instanceof Error ? err.message : '请稍后重试');
		} finally {
			bulkBusy = false;
		}
	}

	function openCancelAllDialog() {
		cancelAllDialogOpen = true;
	}

	async function confirmCancelAll() {
		bulkBusy = true;
		try {
			const count = await uploadOps.cancelAllActiveAndProcessing();
			if (count > 0) {
				notifications.success('已取消任务', `共 ${count} 个任务`);
			}
			cancelAllDialogOpen = false;
		} catch (err) {
			notifications.error('批量取消失败', err instanceof Error ? err.message : '请稍后重试');
		} finally {
			bulkBusy = false;
		}
	}

	async function pauseTask(id: string): Promise<void> {
		if (isBusy(id)) return;
		setBusy(id, true);
		try {
			await upload.pauseUpload(id);
		} finally {
			setBusy(id, false);
		}
	}

	async function resumeTask(id: string): Promise<void> {
		if (isBusy(id)) return;
		setBusy(id, true);
		try {
			await upload.resumeUpload(id);
		} finally {
			setBusy(id, false);
		}
	}

	async function retryTask(id: string): Promise<void> {
		if (isBusy(id)) return;
		setBusy(id, true);
		try {
			await upload.retryUpload(id);
		} finally {
			setBusy(id, false);
		}
	}

	function handleDrop(e: DragEvent) {
		e.preventDefault();
		dragOver = false;

		const files = e.dataTransfer?.files;
		if (files && files.length > 0) {
			handleFiles(Array.from(files));
		}
	}

	function handleFileSelect(e: Event) {
		const input = e.target as HTMLInputElement;
		if (input.files && input.files.length > 0) {
			handleFiles(Array.from(input.files));
			input.value = '';
		}
	}

	async function handleFiles(files: File[]) {
		for (const file of files) {
			if (file.size > MAX_FILE_SIZE_BYTES) {
				notifications.warning(
					'文件过大',
					`${file.name} 超过 ${formatFileSize(MAX_FILE_SIZE_BYTES)} 限制`,
				);
				continue;
			}

			await upload.addFile(file);
		}
	}

	function getStatusText(status: string): string {
		switch (status) {
			case 'pending':
				return '等待中';
			case 'uploading':
				return '上传中';
			case 'processing':
				return '处理中';
			case 'paused':
				return '已暂停';
			case 'completed':
				return '已完成';
			case 'failed':
				return '上传失败';
			case 'cancelled':
				return '已取消';
			default:
				return '未知状态';
		}
	}

	function getStatusColor(status: string): string {
		switch (status) {
			case 'pending':
				return 'bg-gray-100 text-gray-700';
			case 'uploading':
				return 'bg-blue-100 text-blue-700';
			case 'processing':
				return 'bg-purple-100 text-purple-700';
			case 'paused':
				return 'bg-yellow-100 text-yellow-700';
			case 'completed':
				return 'bg-green-100 text-green-700';
			case 'failed':
				return 'bg-red-100 text-red-700';
			case 'cancelled':
				return 'bg-gray-100 text-gray-500';
			default:
				return 'bg-gray-100 text-gray-700';
		}
	}
</script>

<svelte:head>
	<title>上传文件 - 存证平台</title>
</svelte:head>

<div class="space-y-6">
	<div>
		<h1 class="text-2xl font-bold">上传文件</h1>
		<p class="text-muted-foreground">上传文件并存证到区块链</p>
	</div>

	<!-- Drop zone -->
	<div
		class="rounded-lg border-2 border-dashed p-12 text-center transition-colors {dragOver ? 'border-primary bg-primary/5' : ''}"
		ondragover={(e) => { e.preventDefault(); dragOver = true; }}
		ondragleave={() => dragOver = false}
		ondrop={handleDrop}
		role="button"
		tabindex="0"
		onclick={() => fileInput.click()}
		onkeypress={(e) => e.key === 'Enter' && fileInput.click()}
	>
		<input
			type="file"
			bind:this={fileInput}
			onchange={handleFileSelect}
			multiple
			class="hidden"
		/>

		<div class="flex flex-col items-center gap-4">
			<div class="flex h-16 w-16 items-center justify-center rounded-full bg-primary/10 text-primary">
				<svg class="h-8 w-8" fill="none" stroke="currentColor" viewBox="0 0 24 24">
					<path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M4 16v1a3 3 0 003 3h10a3 3 0 003-3v-1m-4-8l-4-4m0 0L8 8m4-4v12" />
				</svg>
			</div>
			<div>
				<p class="text-lg font-medium">拖拽文件到此处或点击上传</p>
				<p class="text-sm text-muted-foreground">
					支持任意格式，单文件最大 {formatFileSize(MAX_FILE_SIZE_BYTES)}
				</p>
			</div>
		</div>
	</div>

	<!-- Upload queue -->
	{#if upload.tasks.length > 0}
		<div class="rounded-lg border bg-card">
			<div class="flex items-center justify-between gap-3 border-b p-4">
				<h2 class="font-semibold">上传队列 ({upload.tasks.length})</h2>
				<div class="flex flex-wrap items-center gap-2">
					{#if cancelableCount > 0}
			<Button variant="outline" size="sm" onclick={openCancelAllDialog} disabled={bulkBusy}>
				全部取消 ({cancelableCount})
			</Button>
					{/if}
					{#if retryableCount > 0}
			<Button variant="outline" size="sm" onclick={openRetryAllDialog} disabled={bulkBusy}>
				重试失败/取消 ({retryableCount})
			</Button>
			<Button variant="outline" size="sm" onclick={openClearFailedDialog} disabled={bulkBusy}>
				清除失败/取消 ({retryableCount})
			</Button>
					{/if}
					{#if upload.completedTasks.length > 0}
					<Button variant="link" class="h-auto p-0" onclick={openClearDialog} disabled={bulkBusy}>
						清除已完成 ({upload.completedTasks.length})
					</Button>
					{/if}
				</div>
			</div>

			<div class="divide-y">
				{#each upload.tasks as task (task.id)}
					{@const t = task as unknown as UploadTaskView}
					<div class="p-4">
						<div class="flex items-start justify-between gap-4">
							<div class="flex items-start gap-3">
								<div class="flex h-10 w-10 shrink-0 items-center justify-center rounded-lg bg-primary/10 text-primary">
									<svg class="h-5 w-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
										<path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M9 12h6m-6 4h6m2 5H7a2 2 0 01-2-2V5a2 2 0 012-2h5.586a1 1 0 01.707.293l5.414 5.414a1 1 0 01.293.707V19a2 2 0 01-2 2z" />
									</svg>
								</div>
								<div class="min-w-0">
									<p class="truncate font-medium">{task.file.name}</p>
									<p class="text-sm text-muted-foreground">
										{formatFileSize(task.file.size)}
										{#if t.status === 'uploading' && task.speed > 0}
											· {formatSpeed(task.speed)}
										{/if}
									</p>
								</div>
							</div>

							<div class="flex items-center gap-2">
								<span class="rounded-full px-2 py-1 text-xs {getStatusColor(t.status)}">
									{getStatusText(t.status)}
								</span>

								{#if t.status === 'uploading'}
									<button
										class="rounded p-1 hover:bg-accent disabled:opacity-50"
										onclick={() => pauseTask(task.id)}
										disabled={isBusy(task.id)}
										title={isBusy(task.id) ? '处理中...' : '暂停'}
									>
										<svg class="h-4 w-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
											<path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M10 9v6m4-6v6m7-3a9 9 0 11-18 0 9 9 0 0118 0z" />
										</svg>
									</button>
								{:else if t.status === 'paused'}
									<button
										class="rounded p-1 hover:bg-accent disabled:opacity-50"
										onclick={() => resumeTask(task.id)}
										disabled={isBusy(task.id)}
										title={isBusy(task.id) ? '处理中...' : '继续'}
									>
										<svg class="h-4 w-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
											<path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M14.752 11.168l-3.197-2.132A1 1 0 0010 9.87v4.263a1 1 0 001.555.832l3.197-2.132a1 1 0 000-1.664z" />
											<path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M21 12a9 9 0 11-18 0 9 9 0 0118 0z" />
										</svg>
									</button>
								{:else if t.status === 'failed' || t.status === 'cancelled'}
									<button
										class="rounded p-1 hover:bg-accent disabled:opacity-50"
										onclick={() => retryTask(task.id)}
										disabled={isBusy(task.id)}
										title={isBusy(task.id) ? '处理中...' : '重试'}
									>
										<svg class="h-4 w-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
											<path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M4 4v5h.582m15.356 2A8.001 8.001 0 004.582 9m0 0H9m11 11v-5h-.581m0 0a8.003 8.003 0 01-15.357-2m15.357 2H15" />
										</svg>
									</button>
								{/if}

								{#if t.status !== 'completed'}
									<button
										class="rounded p-1 text-destructive hover:bg-destructive/10 disabled:opacity-50"
										onclick={() => openRemoveDialog(task)}
										disabled={isBusy(task.id)}
										title={isBusy(task.id) ? '处理中...' : '删除'}
									>
										<svg class="h-4 w-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
											<path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M6 18L18 6M6 6l12 12" />
										</svg>
									</button>
								{/if}
							</div>
						</div>

						<!-- Progress bar -->
						{#if t.status === 'uploading' || t.status === 'paused' || t.status === 'processing'}
							{@const isProcessing = t.status === 'processing'}
							{@const displayProgress = isProcessing ? (t.processProgress ?? 0) : task.progress}
							<div class="mt-3">
								<div class="mb-1 flex justify-between text-xs text-muted-foreground">
									{#if isProcessing}
										<span>处理中</span>
									{:else}
										<span>{task.uploadedChunks.length}/{task.totalChunks} 分片</span>
									{/if}
									<span>{displayProgress}%</span>
								</div>
								<div class="h-2 overflow-hidden rounded-full bg-secondary">
									<div
										class="h-full transition-all"
										class:bg-primary={!isProcessing}
										class:bg-purple-500={isProcessing}
										class:animate-pulse={t.status === 'uploading' || t.status === 'processing'}
										style="width: {displayProgress}%"
									></div>
								</div>
								{#if isProcessing}
									<p class="mt-2 text-xs text-muted-foreground">
										上传已完成，正在加密/存储/存证，请稍候…
									</p>
								{/if}
							</div>
						{/if}

						<!-- Error message -->
						{#if task.error}
							<p class="mt-2 text-sm text-destructive">{task.error}</p>
						{/if}
					</div>
				{/each}
			</div>
		</div>
	{:else}
		<div class="rounded-lg border bg-card p-8 text-center">
			<div class="mx-auto mb-4 flex h-16 w-16 items-center justify-center rounded-full bg-muted">
				<svg class="h-8 w-8 text-muted-foreground" fill="none" stroke="currentColor" viewBox="0 0 24 24">
					<path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M4 16v1a3 3 0 003 3h10a3 3 0 003-3v-1m-4-8l-4-4m0 0L8 8m4-4v12" />
				</svg>
			</div>
			<p class="font-medium">暂无上传任务</p>
			<p class="mt-2 text-sm text-muted-foreground">选择文件或拖拽到上方区域开始上传</p>
			<Button class="mt-4" onclick={() => fileInput.click()}>选择文件</Button>
		</div>
	{/if}

	<!-- Tips -->
	<div class="rounded-lg border bg-muted/50 p-4">
		<h3 class="mb-2 font-medium">上传说明</h3>
		<ul class="space-y-1 text-sm text-muted-foreground">
			<li>• 支持断点续传，上传中断后可继续上传</li>
			<li>• 文件上传后将进行加密处理并存储到分布式系统</li>
			<li>• 文件哈希将被记录到区块链，作为存证凭证</li>
			<li>• 存证完成后可获取交易哈希，用于验证文件真实性</li>
		</ul>
	</div>
</div>

<Dialog.Root bind:open={removeDialogOpen}>
	<Dialog.Content class="sm:max-w-md">
		<Dialog.Header>
			<Dialog.Title>确认移除任务</Dialog.Title>
			<Dialog.Description>移除后将停止上传/处理。</Dialog.Description>
		</Dialog.Header>
		<div class="space-y-2 py-4">
			<p class="text-sm">
				确定要移除
				<span class="font-medium">{removeTarget?.file.name}</span>
				吗？
			</p>
		</div>
		<Dialog.Footer>
			<Button variant="outline" onclick={() => (removeDialogOpen = false)}>取消</Button>
			<Button variant="destructive" onclick={confirmRemove}>移除</Button>
		</Dialog.Footer>
	</Dialog.Content>
</Dialog.Root>

<Dialog.Root bind:open={clearDialogOpen}>
	<Dialog.Content class="sm:max-w-md">
		<Dialog.Header>
			<Dialog.Title>清除已完成</Dialog.Title>
			<Dialog.Description>仅清除状态为“已完成”的任务记录。</Dialog.Description>
		</Dialog.Header>
		<div class="space-y-2 py-4">
			<p class="text-sm">确定要清除所有已完成任务吗？</p>
		</div>
		<Dialog.Footer>
			<Button variant="outline" onclick={() => (clearDialogOpen = false)} disabled={bulkBusy}>
				取消
			</Button>
			<Button onclick={confirmClearCompleted} disabled={bulkBusy}>清除</Button>
		</Dialog.Footer>
	</Dialog.Content>
</Dialog.Root>

<Dialog.Root bind:open={clearFailedDialogOpen}>
	<Dialog.Content class="sm:max-w-md">
		<Dialog.Header>
			<Dialog.Title>清除失败/取消</Dialog.Title>
			<Dialog.Description>仅清除失败或已取消的任务记录。</Dialog.Description>
		</Dialog.Header>
		<div class="space-y-2 py-4">
			<p class="text-sm">确定要清除所有失败/取消任务吗？</p>
		</div>
		<Dialog.Footer>
			<Button variant="outline" onclick={() => (clearFailedDialogOpen = false)} disabled={bulkBusy}>
				取消
			</Button>
			<Button onclick={confirmClearFailedAndCancelled} disabled={bulkBusy}>清除</Button>
		</Dialog.Footer>
	</Dialog.Content>
</Dialog.Root>

<Dialog.Root bind:open={retryAllDialogOpen}>
	<Dialog.Content class="sm:max-w-md">
		<Dialog.Header>
			<Dialog.Title>批量重试</Dialog.Title>
			<Dialog.Description>将重新开始所有失败/取消的任务。</Dialog.Description>
		</Dialog.Header>
		<div class="space-y-2 py-4">
			<p class="text-sm">确定要重试所有失败/取消任务吗？</p>
		</div>
		<Dialog.Footer>
			<Button variant="outline" onclick={() => (retryAllDialogOpen = false)} disabled={bulkBusy}>
				取消
			</Button>
			<Button onclick={confirmRetryAll} disabled={bulkBusy}>
				{bulkBusy ? '处理中…' : '重试'}
			</Button>
		</Dialog.Footer>
	</Dialog.Content>
</Dialog.Root>

<Dialog.Root bind:open={cancelAllDialogOpen}>
	<Dialog.Content class="sm:max-w-md">
		<Dialog.Header>
			<Dialog.Title>批量取消</Dialog.Title>
			<Dialog.Description>将取消所有上传中/暂停/处理中的任务。</Dialog.Description>
		</Dialog.Header>
		<div class="space-y-2 py-4">
			<p class="text-sm">确定要取消所有进行中的任务吗？</p>
		</div>
		<Dialog.Footer>
			<Button variant="outline" onclick={() => (cancelAllDialogOpen = false)} disabled={bulkBusy}>
				取消
			</Button>
			<Button variant="destructive" onclick={confirmCancelAll} disabled={bulkBusy}>
				{bulkBusy ? '处理中…' : '取消'}
			</Button>
		</Dialog.Footer>
	</Dialog.Content>
</Dialog.Root>
