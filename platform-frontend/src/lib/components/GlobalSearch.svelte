<script lang="ts">
	import { onMount, onDestroy } from 'svelte';
	import { goto } from '$app/navigation';
	import { getFiles } from '$api/endpoints/files';
	import type { FileVO } from '$api/types';
	import { formatFileSize, formatRelativeTime } from '$utils/format';

	let searchOpen = $state(false);
	let query = $state('');
	let results = $state<FileVO[]>([]);
	let loading = $state(false);
	let selectedIndex = $state(0);
	let inputElement = $state<HTMLInputElement | null>(null);

	// 防抖计时器
	let searchTimeout: ReturnType<typeof setTimeout> | null = null;

	// 最近搜索记录（存储于 localStorage）
	const RECENT_KEY = 'global_search_recent';
	let recentSearches = $state<string[]>([]);

	onMount(() => {
		// 加载最近搜索记录
		try {
			const stored = localStorage.getItem(RECENT_KEY);
			if (stored) recentSearches = JSON.parse(stored);
		} catch { /* 忽略 localStorage 错误 */ }

		// 键盘快捷键
		window.addEventListener('keydown', handleGlobalKeydown);
	});

	onDestroy(() => {
		if (typeof window !== 'undefined') {
			window.removeEventListener('keydown', handleGlobalKeydown);
		}
		if (searchTimeout) clearTimeout(searchTimeout);
	});

	function handleGlobalKeydown(e: KeyboardEvent) {
		// Cmd/Ctrl + K 打开搜索
		if ((e.metaKey || e.ctrlKey) && e.key === 'k') {
			e.preventDefault();
			openSearch();
		}
		// Esc 关闭
		if (e.key === 'Escape' && searchOpen) {
			closeSearch();
		}
	}

	function openSearch() {
		searchOpen = true;
		query = '';
		results = [];
		selectedIndex = 0;
		// 渲染后聚焦输入框
		setTimeout(() => inputElement?.focus(), 50);
	}

	function closeSearch() {
		searchOpen = false;
		query = '';
		results = [];
	}

	function handleInputKeydown(e: KeyboardEvent) {
		if (e.key === 'ArrowDown') {
			e.preventDefault();
			selectedIndex = Math.min(selectedIndex + 1, results.length - 1);
		} else if (e.key === 'ArrowUp') {
			e.preventDefault();
			selectedIndex = Math.max(selectedIndex - 1, 0);
		} else if (e.key === 'Enter') {
			e.preventDefault();
			if (results.length > 0 && results[selectedIndex]) {
				selectResult(results[selectedIndex]);
			} else if (query.trim()) {
				// 使用关键词搜索
				performSearch();
			}
		}
	}

	function handleQueryChange() {
		if (searchTimeout) clearTimeout(searchTimeout);

		if (!query.trim()) {
			results = [];
			return;
		}

		// 搜索防抖
		searchTimeout = setTimeout(performSearch, 300);
	}

	async function performSearch() {
		if (!query.trim()) return;

		loading = true;
		selectedIndex = 0;

		try {
			const page = await getFiles({ keyword: query.trim(), pageNum: 1, pageSize: 10 });
			results = page.records;
		} catch {
			results = [];
		} finally {
			loading = false;
		}
	}

	function selectResult(file: FileVO) {
		// 保存到最近搜索
		saveRecentSearch(query.trim());
		// 跳转到文件详情
		goto(`/files/${file.fileHash}`);
		closeSearch();
	}

	function selectRecentSearch(search: string) {
		query = search;
		performSearch();
	}

	function saveRecentSearch(search: string) {
		if (!search) return;
		// 若已存在则先移除，再追加到最前
		recentSearches = [search, ...recentSearches.filter(s => s !== search)].slice(0, 5);
		localStorage.setItem(RECENT_KEY, JSON.stringify(recentSearches));
	}

	function clearRecentSearches() {
		recentSearches = [];
		localStorage.removeItem(RECENT_KEY);
	}

	function getFileIcon(contentType: string): string {
		if (contentType.startsWith('image/')) return 'image';
		if (contentType.startsWith('video/')) return 'video';
		if (contentType.startsWith('audio/')) return 'audio';
		if (contentType === 'application/pdf') return 'pdf';
		return 'file';
	}
</script>

<!-- 搜索触发按钮 -->
<button
	class="flex items-center gap-2 rounded-lg border bg-muted/50 px-3 py-1.5 text-sm text-muted-foreground transition-colors hover:bg-muted"
	onclick={openSearch}
>
	<svg class="h-4 w-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
		<path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M21 21l-6-6m2-5a7 7 0 11-14 0 7 7 0 0114 0z" />
	</svg>
	<span class="hidden sm:inline">搜索文件...</span>
	<kbd class="ml-2 hidden rounded border bg-background px-1.5 py-0.5 text-xs font-mono sm:inline">
		⌘K
	</kbd>
</button>

<!-- 搜索弹窗 -->
{#if searchOpen}
	<!-- 遮罩层 -->
	<div
		class="fixed inset-0 z-50 bg-black/50"
		onclick={closeSearch}
		onkeydown={(e) => e.key === 'Escape' && closeSearch()}
		role="button"
		tabindex="-1"
	></div>

	<!-- 对话框 -->
	<div class="fixed left-1/2 top-1/4 z-50 w-full max-w-lg -translate-x-1/2 rounded-lg border bg-card shadow-2xl">
		<!-- 搜索输入框 -->
		<div class="flex items-center gap-3 border-b px-4 py-3">
			<svg class="h-5 w-5 text-muted-foreground" fill="none" stroke="currentColor" viewBox="0 0 24 24">
				<path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M21 21l-6-6m2-5a7 7 0 11-14 0 7 7 0 0114 0z" />
			</svg>
			<input
				bind:this={inputElement}
				bind:value={query}
				oninput={handleQueryChange}
				onkeydown={handleInputKeydown}
				type="text"
				placeholder="搜索文件..."
				class="flex-1 bg-transparent text-sm outline-none placeholder:text-muted-foreground"
			/>
			{#if loading}
				<div class="h-4 w-4 animate-spin rounded-full border-2 border-primary border-t-transparent"></div>
			{/if}
			<button
				class="rounded p-1 text-muted-foreground hover:bg-muted"
				onclick={closeSearch}
				aria-label="关闭搜索"
			>
				<svg class="h-4 w-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
					<path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M6 18L18 6M6 6l12 12" />
				</svg>
			</button>
		</div>

		<!-- 搜索结果 / 最近 -->
		<div class="max-h-80 overflow-y-auto">
			{#if results.length > 0}
				<div class="p-2">
					<p class="px-2 py-1 text-xs font-medium text-muted-foreground">文件</p>
					{#each results as file, i (file.id)}
						<button
							class="flex w-full items-center gap-3 rounded-lg px-3 py-2 text-left transition-colors {i === selectedIndex ? 'bg-accent' : 'hover:bg-muted/50'}"
							onclick={() => selectResult(file)}
							onmouseenter={() => selectedIndex = i}
						>
							<div class="flex h-8 w-8 shrink-0 items-center justify-center rounded bg-primary/10 text-primary">
								{#if getFileIcon(file.contentType) === 'image'}
									<svg class="h-4 w-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
										<path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M4 16l4.586-4.586a2 2 0 012.828 0L16 16m-2-2l1.586-1.586a2 2 0 012.828 0L20 14m-6-6h.01M6 20h12a2 2 0 002-2V6a2 2 0 00-2-2H6a2 2 0 00-2 2v12a2 2 0 002 2z" />
									</svg>
								{:else if getFileIcon(file.contentType) === 'video'}
									<svg class="h-4 w-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
										<path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M15 10l4.553-2.276A1 1 0 0121 8.618v6.764a1 1 0 01-1.447.894L15 14M5 18h8a2 2 0 002-2V8a2 2 0 00-2-2H5a2 2 0 00-2 2v8a2 2 0 002 2z" />
									</svg>
								{:else if getFileIcon(file.contentType) === 'pdf'}
									<svg class="h-4 w-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
										<path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M7 21h10a2 2 0 002-2V9.414a1 1 0 00-.293-.707l-5.414-5.414A1 1 0 0012.586 3H7a2 2 0 00-2 2v14a2 2 0 002 2z" />
									</svg>
								{:else}
									<svg class="h-4 w-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
										<path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M9 12h6m-6 4h6m2 5H7a2 2 0 01-2-2V5a2 2 0 012-2h5.586a1 1 0 01.707.293l5.414 5.414a1 1 0 01.293.707V19a2 2 0 01-2 2z" />
									</svg>
								{/if}
							</div>
							<div class="min-w-0 flex-1">
								<p class="truncate text-sm font-medium">{file.fileName}</p>
								<p class="text-xs text-muted-foreground">
									{formatFileSize(file.fileSize)} · {formatRelativeTime(file.createTime)}
								</p>
							</div>
							<svg class="h-4 w-4 shrink-0 text-muted-foreground" fill="none" stroke="currentColor" viewBox="0 0 24 24">
								<path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M9 5l7 7-7 7" />
							</svg>
						</button>
					{/each}
				</div>
			{:else if query.trim() && !loading}
				<div class="p-8 text-center text-muted-foreground">
					<p>未找到匹配的文件</p>
				</div>
			{:else if recentSearches.length > 0}
				<div class="p-2">
					<div class="flex items-center justify-between px-2 py-1">
						<p class="text-xs font-medium text-muted-foreground">最近搜索</p>
						<button
							class="text-xs text-muted-foreground hover:text-foreground"
							onclick={clearRecentSearches}
						>
							清除
						</button>
					</div>
					{#each recentSearches as search}
						<button
							class="flex w-full items-center gap-3 rounded-lg px-3 py-2 text-left hover:bg-muted/50"
							onclick={() => selectRecentSearch(search)}
						>
							<svg class="h-4 w-4 text-muted-foreground" fill="none" stroke="currentColor" viewBox="0 0 24 24">
								<path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M12 8v4l3 3m6-3a9 9 0 11-18 0 9 9 0 0118 0z" />
							</svg>
							<span class="text-sm">{search}</span>
						</button>
					{/each}
				</div>
			{:else}
				<div class="p-8 text-center text-muted-foreground">
					<p class="text-sm">输入关键词搜索文件</p>
					<p class="mt-1 text-xs">支持按文件名搜索</p>
				</div>
			{/if}
		</div>

		<!-- 底部提示 -->
		<div class="flex items-center justify-between border-t px-4 py-2 text-xs text-muted-foreground">
			<div class="flex items-center gap-2">
				<kbd class="rounded border bg-muted px-1.5 py-0.5 font-mono">↑↓</kbd>
				<span>导航</span>
				<kbd class="ml-2 rounded border bg-muted px-1.5 py-0.5 font-mono">↵</kbd>
				<span>选择</span>
			</div>
			<div>
				<kbd class="rounded border bg-muted px-1.5 py-0.5 font-mono">Esc</kbd>
				<span class="ml-1">关闭</span>
			</div>
		</div>
	</div>
{/if}
