<script lang="ts">
  import "../app.css";
  import { ModeWatcher } from "mode-watcher";
  import { onNavigate } from "$app/navigation";
  import { useNotifications } from "$stores/notifications.svelte";
  import { useSSE } from "$stores/sse.svelte";
  import { useDownload } from "$stores/download.svelte";
  import { onMount } from "svelte";
  import DownloadManager from "$lib/components/DownloadManager.svelte";
  import LoadingBar from "$lib/components/ui/LoadingBar.svelte";
  import logo from "$lib/assets/logo.png";

  let { children } = $props();

  const notifications = useNotifications();
  const sse = useSSE();
  const download = useDownload();

  onMount(() => {
    download.restoreTasks();

    return () => {
      sse.disconnect();
    };
  });

  // 页面切换动画 — 使用浏览器原生 View Transitions API
  onNavigate((navigation) => {
    if (!document.startViewTransition) return;

    return new Promise((resolve) => {
      document.startViewTransition(async () => {
        resolve();
        await navigation.complete;
      });
    });
  });
</script>

<ModeWatcher />
<LoadingBar />
<svelte:head>
  <link rel="icon" href={logo} />
  <link rel="apple-touch-icon" href={logo} />
</svelte:head>
<div class="bg-background min-h-screen">
  {@render children()}
</div>

<!-- 下载管理器 -->
<DownloadManager />

<!-- 通知提示（左侧显示，避免与下载管理器重叠） -->
{#if notifications.notifications.length > 0}
  <div class="fixed bottom-4 left-4 z-50 flex flex-col gap-2">
    {#each notifications.notifications as notification (notification.id)}
      <div
        class="bg-card animate-in fade-in slide-in-from-left-4 flex items-start gap-3 rounded-lg border p-4 shadow-lg duration-300"
        class:border-green-500={notification.type === "success"}
        class:border-red-500={notification.type === "error"}
        class:border-yellow-500={notification.type === "warning"}
        class:border-blue-500={notification.type === "info"}
      >
        <div class="flex-1">
          <p class="font-medium">{notification.title}</p>
          {#if notification.message}
            <p class="text-muted-foreground text-sm">{notification.message}</p>
          {/if}
        </div>
        <button
          class="text-muted-foreground hover:text-foreground"
          onclick={() => notifications.dismiss(notification.id)}
          aria-label="关闭通知"
        >
          <svg
            class="h-4 w-4"
            fill="none"
            stroke="currentColor"
            viewBox="0 0 24 24"
          >
            <path
              stroke-linecap="round"
              stroke-linejoin="round"
              stroke-width="2"
              d="M6 18L18 6M6 6l12 12"
            />
          </svg>
        </button>
      </div>
    {/each}
  </div>
{/if}
