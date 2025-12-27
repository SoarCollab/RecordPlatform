<script lang="ts">
  import { useDownload } from "$stores/download.svelte";
  import DownloadProgress from "./DownloadProgress.svelte";
  import { Download, ChevronDown, ChevronUp, Trash2 } from "@lucide/svelte";
  import { slide } from "svelte/transition";

  const download = useDownload();

  let isMinimized = $state(false);

  const hasTasks = $derived(download.tasks.length > 0);
  const activeCount = $derived(download.activeTasks.length);
  const completedCount = $derived(download.completedTasks.length);
  const totalProgress = $derived(
    download.tasks.length > 0
      ? Math.round(
          download.tasks.reduce((sum, t) => sum + t.progress, 0) / download.tasks.length
        )
      : 0
  );
</script>

{#if hasTasks}
  <div
    class="fixed bottom-4 right-4 z-50 w-96 max-w-[calc(100vw-2rem)] rounded-lg border bg-background shadow-lg"
  >
    <!-- Header -->
    <div
      class="flex items-center justify-between gap-2 border-b px-4 py-3 cursor-pointer select-none"
      role="button"
      tabindex="0"
      onclick={() => (isMinimized = !isMinimized)}
      onkeydown={(e) => e.key === "Enter" && (isMinimized = !isMinimized)}
    >
      <div class="flex items-center gap-2">
        <Download class="h-4 w-4" />
        <span class="font-medium text-sm">
          下载管理
          {#if activeCount > 0}
            <span class="text-primary">({activeCount} 进行中)</span>
          {/if}
        </span>
      </div>

      <div class="flex items-center gap-2">
        {#if completedCount > 0}
          <button
            class="p-1 rounded hover:bg-muted text-muted-foreground hover:text-foreground transition-colors"
            title="清除已完成"
            onclick={(e) => { e.stopPropagation(); download.clearCompleted(); }}
          >
            <Trash2 class="h-4 w-4" />
          </button>
        {/if}

        <button
          class="p-1 rounded hover:bg-muted text-muted-foreground hover:text-foreground transition-colors"
          title={isMinimized ? "展开" : "最小化"}
        >
          {#if isMinimized}
            <ChevronUp class="h-4 w-4" />
          {:else}
            <ChevronDown class="h-4 w-4" />
          {/if}
        </button>
      </div>
    </div>

    <!-- Progress Summary (when minimized) -->
    {#if isMinimized}
      <div class="px-4 py-2">
        <div class="flex items-center justify-between text-xs text-muted-foreground mb-1">
          <span>{download.tasks.length} 个任务</span>
          <span>{totalProgress}%</span>
        </div>
        <div class="h-1.5 w-full rounded-full bg-muted overflow-hidden">
          <div
            class="h-full rounded-full bg-primary transition-all duration-300"
            style="width: {totalProgress}%"
          ></div>
        </div>
      </div>
    {/if}

    <!-- Task List -->
    {#if !isMinimized}
      <div
        class="max-h-80 overflow-y-auto p-2 space-y-2"
        transition:slide={{ duration: 200 }}
      >
        {#each download.tasks as task (task.id)}
          <DownloadProgress {task} />
        {/each}
      </div>
    {/if}
  </div>
{/if}
