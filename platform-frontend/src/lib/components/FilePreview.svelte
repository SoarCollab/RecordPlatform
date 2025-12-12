<script lang="ts">
	import { onMount } from 'svelte';

	interface Props {
		url: string;
		contentType: string;
		fileName: string;
		class?: string;
	}

	let { url, contentType, fileName, class: className = '' }: Props = $props();

	let textContent = $state('');
	let loadingText = $state(false);
	let textError = $state<string | null>(null);

	// Determine preview type based on contentType
	const previewType = $derived(getPreviewType(contentType));

	function getPreviewType(type: string): 'image' | 'video' | 'audio' | 'pdf' | 'text' | 'unsupported' {
		if (type.startsWith('image/')) return 'image';
		if (type.startsWith('video/')) return 'video';
		if (type.startsWith('audio/')) return 'audio';
		if (type === 'application/pdf') return 'pdf';
		if (
			type.startsWith('text/') ||
			type === 'application/json' ||
			type === 'application/xml' ||
			type === 'application/javascript'
		) {
			return 'text';
		}
		return 'unsupported';
	}

	onMount(() => {
		if (previewType === 'text') {
			loadTextContent();
		}
	});

	async function loadTextContent() {
		loadingText = true;
		textError = null;
		try {
			const response = await fetch(url);
			if (!response.ok) throw new Error('Failed to load file');
			const text = await response.text();
			// Limit text length for performance
			textContent = text.length > 100000 ? text.slice(0, 100000) + '\n\n... (内容过长，已截断)' : text;
		} catch (err) {
			textError = err instanceof Error ? err.message : '加载失败';
		} finally {
			loadingText = false;
		}
	}

	function getLanguageClass(type: string): string {
		if (type === 'application/json') return 'language-json';
		if (type === 'application/xml' || type === 'text/xml') return 'language-xml';
		if (type === 'application/javascript' || type === 'text/javascript') return 'language-javascript';
		if (type === 'text/html') return 'language-html';
		if (type === 'text/css') return 'language-css';
		if (type === 'text/markdown') return 'language-markdown';
		return '';
	}
</script>

<div class="file-preview {className}">
	{#if previewType === 'image'}
		<div class="flex items-center justify-center bg-muted/30 p-4">
			<img
				src={url}
				alt={fileName}
				class="max-h-[70vh] max-w-full rounded-lg object-contain shadow-lg"
			/>
		</div>
	{:else if previewType === 'video'}
		<div class="flex items-center justify-center bg-black p-4">
			<video
				src={url}
				controls
				class="max-h-[70vh] max-w-full rounded-lg"
				preload="metadata"
			>
				<track kind="captions" />
				您的浏览器不支持视频播放
			</video>
		</div>
	{:else if previewType === 'audio'}
		<div class="flex flex-col items-center justify-center gap-4 bg-muted/30 p-8">
			<div class="flex h-24 w-24 items-center justify-center rounded-full bg-primary/10 text-primary">
				<svg class="h-12 w-12" fill="none" stroke="currentColor" viewBox="0 0 24 24">
					<path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M9 19V6l12-3v13M9 19c0 1.105-1.343 2-3 2s-3-.895-3-2 1.343-2 3-2 3 .895 3 2zm12-3c0 1.105-1.343 2-3 2s-3-.895-3-2 1.343-2 3-2 3 .895 3 2zM9 10l12-3" />
				</svg>
			</div>
			<p class="text-sm font-medium">{fileName}</p>
			<audio src={url} controls class="w-full max-w-md">
				您的浏览器不支持音频播放
			</audio>
		</div>
	{:else if previewType === 'pdf'}
		<div class="h-[70vh] w-full">
			<iframe
				src={url}
				title={fileName}
				class="h-full w-full rounded-lg border-0"
			></iframe>
		</div>
	{:else if previewType === 'text'}
		<div class="overflow-hidden rounded-lg border bg-muted/30">
			{#if loadingText}
				<div class="flex items-center justify-center p-8">
					<div class="h-6 w-6 animate-spin rounded-full border-2 border-primary border-t-transparent"></div>
				</div>
			{:else if textError}
				<div class="p-8 text-center text-muted-foreground">
					<p>{textError}</p>
				</div>
			{:else}
				<pre class="max-h-[70vh] overflow-auto p-4 text-sm {getLanguageClass(contentType)}"><code>{textContent}</code></pre>
			{/if}
		</div>
	{:else}
		<div class="flex flex-col items-center justify-center gap-4 rounded-lg border bg-muted/30 p-12">
			<div class="flex h-16 w-16 items-center justify-center rounded-full bg-muted text-muted-foreground">
				<svg class="h-8 w-8" fill="none" stroke="currentColor" viewBox="0 0 24 24">
					<path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M9 12h6m-6 4h6m2 5H7a2 2 0 01-2-2V5a2 2 0 012-2h5.586a1 1 0 01.707.293l5.414 5.414a1 1 0 01.293.707V19a2 2 0 01-2 2z" />
				</svg>
			</div>
			<p class="text-muted-foreground">此文件类型不支持预览</p>
			<p class="text-sm text-muted-foreground">{contentType}</p>
		</div>
	{/if}
</div>

<style>
	pre {
		margin: 0;
		white-space: pre-wrap;
		word-wrap: break-word;
	}
	code {
		font-family: ui-monospace, SFMono-Regular, 'SF Mono', Menlo, Consolas, monospace;
	}
</style>
