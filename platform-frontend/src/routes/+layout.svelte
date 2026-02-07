<script lang="ts">
  import "../app.css";
  import { ModeWatcher } from "mode-watcher";
  import { useNotifications } from "$stores/notifications.svelte";
  import { useSSE } from "$stores/sse.svelte";
  import { useAuth } from "$stores/auth.svelte";
  import { useDownload } from "$stores/download.svelte";
  import { browser } from "$app/environment";
  import { onMount } from "svelte";
  import DownloadManager from "$lib/components/DownloadManager.svelte";
  import LoadingBar from "$lib/components/ui/LoadingBar.svelte";
  import logo from "$lib/assets/logo.png";

  let { children } = $props();

  const notifications = useNotifications();
  const sse = useSSE();
  const auth = useAuth();
  const download = useDownload();

  // 登录后连接 SSE
  $effect(() => {
    if (browser && auth.isAuthenticated) {
      sse.connect();
    }
  });

  // 显示前清理文本（转义 HTML 实体）
  function sanitizeText(text: unknown): string {
    const str = String(text ?? "");
    return str
      .replace(/&/g, "&amp;")
      .replace(/</g, "&lt;")
      .replace(/>/g, "&gt;")
      .replace(/"/g, "&quot;")
      .replace(/'/g, "&#039;");
  }

  // 处理 SSE 消息并恢复下载任务
  onMount(() => {
    // 从 IndexedDB 恢复待处理的下载任务
    download.restoreTasks();

    const unsubscribe = sse.subscribe((message) => {
      switch (message.type) {
        case "notification":
          notifications.info("系统通知", sanitizeText(message.data));
          break;
        case "message-received":
          notifications.info("新消息", "您收到一条新消息");
          break;
        case "file-record-success":
          notifications.success("文件存证成功", "您的文件已完成存证");
          break;
        case "file-record-failed":
          notifications.error("文件存证失败", "文件存证过程中出错");
          break;
      }
    });

    return () => {
      unsubscribe();
      sse.disconnect();
    };
  });
</script>

<ModeWatcher />
<LoadingBar />
<svelte:head>
  <link rel="icon" href={logo} />
  <link rel="apple-touch-icon" href={logo} />
</svelte:head>
<div class="min-h-screen bg-background">
  {@render children()}
</div>

<!-- 下载管理器 -->
<DownloadManager />

<!-- 通知提示（左侧显示，避免与下载管理器重叠） -->
{#if notifications.notifications.length > 0}
  <div class="fixed bottom-4 left-4 z-50 flex flex-col gap-2">
    {#each notifications.notifications as notification (notification.id)}
      <div
        class="flex items-start gap-3 rounded-lg border bg-card p-4 shadow-lg transition-all"
        class:border-green-500={notification.type === "success"}
        class:border-red-500={notification.type === "error"}
        class:border-yellow-500={notification.type === "warning"}
        class:border-blue-500={notification.type === "info"}
      >
        <div class="flex-1">
          <p class="font-medium">{notification.title}</p>
          {#if notification.message}
            <p class="text-sm text-muted-foreground">{notification.message}</p>
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
