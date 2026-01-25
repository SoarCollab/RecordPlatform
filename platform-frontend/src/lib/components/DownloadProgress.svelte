<script lang="ts">
  import { useDownload, type DownloadTask } from "$stores/download.svelte";
  import { X, Pause, Play, RotateCcw, Loader2, Check, AlertCircle, Download } from "@lucide/svelte";

  interface Props {
    task: DownloadTask;
  }

  let { task }: Props = $props();
  const download = useDownload();

  type StatusConfigItem = { label: string; icon: typeof Loader2; color: string };

  const statusConfig = {
    pending: { label: "等待中", icon: Loader2, color: "text-muted-foreground" },
    fetching_urls: { label: "获取地址", icon: Loader2, color: "text-blue-500" },
    downloading: { label: "下载中", icon: Download, color: "text-blue-500" },
    streaming: { label: "下载中", icon: Download, color: "text-blue-500" },
    paused: { label: "已暂停", icon: Pause, color: "text-yellow-500" },
    decrypting: { label: "解密中", icon: Loader2, color: "text-purple-500" },
    writing: { label: "写入中", icon: Loader2, color: "text-purple-500" },
    completed: { label: "已完成", icon: Check, color: "text-green-500" },
    failed: { label: "失败", icon: AlertCircle, color: "text-red-500" },
    cancelled: { label: "已取消", icon: X, color: "text-muted-foreground" },
  } satisfies Record<DownloadTask["status"], StatusConfigItem>;

  const config = $derived(statusConfig[task.status]);
  const Icon = $derived(config.icon);
  const isActive = $derived(
    task.status === "downloading" ||
      task.status === "fetching_urls" ||
      task.status === "streaming" ||
      task.status === "decrypting" ||
      task.status === "writing",
  );
  const canPause = $derived(
    task.status === "downloading" ||
      task.status === "streaming" ||
      task.status === "writing",
  );
  const canResume = $derived(task.status === "paused");
  const canRetry = $derived(task.status === "failed" || task.status === "cancelled");
  const canRemove = $derived(!isActive);
</script>

<div class="flex items-center gap-3 rounded-lg border bg-card p-3 text-card-foreground">
  <!-- 状态图标 -->
  <div class="flex-shrink-0">
    <Icon class="h-5 w-5 {config.color} {isActive ? 'animate-spin' : ''}" />
  </div>

  <!-- 文件信息与进度 -->
  <div class="flex-1 min-w-0">
    <div class="flex items-center justify-between gap-2">
      <span class="text-sm font-medium truncate" title={task.fileName}>
        {task.fileName}
      </span>
      <span class="text-xs {config.color} flex-shrink-0">
        {config.label}
      </span>
    </div>

    <!-- 进度条 -->
    <div class="mt-1.5 h-1.5 w-full rounded-full bg-muted overflow-hidden">
      <div
        class="h-full rounded-full transition-all duration-300 {task.status === 'completed'
          ? 'bg-green-500'
          : task.status === 'failed'
            ? 'bg-red-500'
            : 'bg-primary'}"
        style="width: {task.progress}%"
      ></div>
    </div>

    <!-- 详情 -->
    <div class="mt-1 flex items-center justify-between text-xs text-muted-foreground">
      <span>
        {#if task.totalChunks > 0}
          {task.downloadedChunks}/{task.totalChunks} 分片
        {:else}
          准备中...
        {/if}
      </span>
      <span>{task.progress}%</span>
    </div>

    {#if task.error}
      <p class="mt-1 text-xs text-red-500 truncate" title={task.error}>
        {task.error}
      </p>
    {/if}
  </div>

  <!-- 操作 -->
  <div class="flex items-center gap-1 flex-shrink-0">
    {#if canPause}
      <button
        class="p-1.5 rounded hover:bg-muted transition-colors"
        title="暂停"
        onclick={() => download.pauseDownload(task.id)}
      >
        <Pause class="h-4 w-4" />
      </button>
    {/if}

    {#if canResume}
      <button
        class="p-1.5 rounded hover:bg-muted transition-colors"
        title="继续"
        onclick={() => download.resumeDownload(task.id)}
      >
        <Play class="h-4 w-4" />
      </button>
    {/if}

    {#if canRetry}
      <button
        class="p-1.5 rounded hover:bg-muted transition-colors"
        title="重试"
        onclick={() => download.retryDownload(task.id)}
      >
        <RotateCcw class="h-4 w-4" />
      </button>
    {/if}

    {#if canRemove}
      <button
        class="p-1.5 rounded hover:bg-muted text-muted-foreground hover:text-destructive transition-colors"
        title="移除"
        onclick={() => download.removeTask(task.id)}
      >
        <X class="h-4 w-4" />
      </button>
    {:else}
      <button
        class="p-1.5 rounded hover:bg-muted text-muted-foreground hover:text-destructive transition-colors"
        title="取消"
        onclick={() => download.cancelDownload(task.id)}
      >
        <X class="h-4 w-4" />
      </button>
    {/if}
  </div>
</div>
