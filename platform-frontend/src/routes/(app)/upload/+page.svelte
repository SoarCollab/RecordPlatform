<script lang="ts">
	import { useUpload, type UploadTask } from '$stores/upload.svelte';
	import { useNotifications } from '$stores/notifications.svelte';
	import { formatFileSize, formatSpeed } from '$utils/format';

	const upload = useUpload();
	const notifications = useNotifications();

	let dragOver = $state(false);
	let fileInput: HTMLInputElement;

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
			// Basic validation
			if (file.size > 100 * 1024 * 1024) {
				notifications.warning('文件过大', `${file.name} 超过 100MB 限制`);
				continue;
			}

			await upload.addFile(file);
		}
	}

	function getStatusText(status: UploadTask['status']): string {
		const map: Record<UploadTask['status'], string> = {
			pending: '等待中',
			uploading: '上传中',
			paused: '已暂停',
			completed: '已完成',
			failed: '上传失败',
			cancelled: '已取消'
		};
		return map[status];
	}

	function getStatusColor(status: UploadTask['status']): string {
		const map: Record<UploadTask['status'], string> = {
			pending: 'bg-gray-100 text-gray-700',
			uploading: 'bg-blue-100 text-blue-700',
			paused: 'bg-yellow-100 text-yellow-700',
			completed: 'bg-green-100 text-green-700',
			failed: 'bg-red-100 text-red-700',
			cancelled: 'bg-gray-100 text-gray-500'
		};
		return map[status];
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
				<p class="text-sm text-muted-foreground">支持任意格式，单文件最大 100MB</p>
			</div>
		</div>
	</div>

	<!-- Upload queue -->
	{#if upload.tasks.length > 0}
		<div class="rounded-lg border bg-card">
			<div class="flex items-center justify-between border-b p-4">
				<h2 class="font-semibold">上传队列 ({upload.tasks.length})</h2>
				{#if upload.completedTasks.length > 0}
					<button
						class="text-sm text-primary hover:underline"
						onclick={() => upload.clearCompleted()}
					>
						清除已完成
					</button>
				{/if}
			</div>

			<div class="divide-y">
				{#each upload.tasks as task (task.id)}
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
										{#if task.status === 'uploading' && task.speed > 0}
											· {formatSpeed(task.speed)}
										{/if}
									</p>
								</div>
							</div>

							<div class="flex items-center gap-2">
								<span class="rounded-full px-2 py-1 text-xs {getStatusColor(task.status)}">
									{getStatusText(task.status)}
								</span>

								{#if task.status === 'uploading'}
									<button
										class="rounded p-1 hover:bg-accent"
										onclick={() => upload.pauseUpload(task.id)}
										title="暂停"
									>
										<svg class="h-4 w-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
											<path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M10 9v6m4-6v6m7-3a9 9 0 11-18 0 9 9 0 0118 0z" />
										</svg>
									</button>
								{:else if task.status === 'paused'}
									<button
										class="rounded p-1 hover:bg-accent"
										onclick={() => upload.resumeUpload(task.id)}
										title="继续"
									>
										<svg class="h-4 w-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
											<path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M14.752 11.168l-3.197-2.132A1 1 0 0010 9.87v4.263a1 1 0 001.555.832l3.197-2.132a1 1 0 000-1.664z" />
											<path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M21 12a9 9 0 11-18 0 9 9 0 0118 0z" />
										</svg>
									</button>
								{:else if task.status === 'failed' || task.status === 'cancelled'}
									<button
										class="rounded p-1 hover:bg-accent"
										onclick={() => upload.retryUpload(task.id)}
										title="重试"
									>
										<svg class="h-4 w-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
											<path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M4 4v5h.582m15.356 2A8.001 8.001 0 004.582 9m0 0H9m11 11v-5h-.581m0 0a8.003 8.003 0 01-15.357-2m15.357 2H15" />
										</svg>
									</button>
								{/if}

								{#if task.status !== 'completed'}
									<button
										class="rounded p-1 text-destructive hover:bg-destructive/10"
										onclick={() => upload.removeTask(task.id)}
										title="删除"
									>
										<svg class="h-4 w-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
											<path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M6 18L18 6M6 6l12 12" />
										</svg>
									</button>
								{/if}
							</div>
						</div>

						<!-- Progress bar -->
						{#if task.status === 'uploading' || task.status === 'paused'}
							<div class="mt-3">
								<div class="mb-1 flex justify-between text-xs text-muted-foreground">
									<span>{task.uploadedChunks.length}/{task.totalChunks} 分片</span>
									<span>{task.progress}%</span>
								</div>
								<div class="h-2 overflow-hidden rounded-full bg-secondary">
									<div
										class="h-full bg-primary transition-all"
										class:animate-pulse={task.status === 'uploading'}
										style="width: {task.progress}%"
									></div>
								</div>
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
