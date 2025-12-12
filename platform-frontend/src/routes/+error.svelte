<script lang="ts">
	import { page } from '$app/stores';
</script>

<div class="flex min-h-screen flex-col items-center justify-center px-4">
	<div class="text-center">
		<h1 class="mb-2 text-6xl font-bold text-primary">{$page.status}</h1>
		<h2 class="mb-4 text-2xl font-semibold">
			{#if $page.status === 404}
				页面未找到
			{:else if $page.status === 403}
				无权访问
			{:else if $page.status === 500}
				服务器错误
			{:else}
				出错了
			{/if}
		</h2>
		<p class="mb-8 text-muted-foreground">
			{$page.error?.message || '抱歉，请求的页面发生了错误'}
		</p>
		<a
			href="/"
			class="inline-flex items-center gap-2 rounded-lg bg-primary px-4 py-2 text-sm font-medium text-primary-foreground hover:bg-primary/90"
		>
			<svg class="h-4 w-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
				<path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M10 19l-7-7m0 0l7-7m-7 7h18" />
			</svg>
			返回首页
		</a>
	</div>
</div>
