<script lang="ts">
  import { page } from "$app/stores";
  import { goto } from "$app/navigation";
  import { fly } from "svelte/transition";
  import { onMount, onDestroy } from "svelte";
  import { useAuth } from "$stores/auth.svelte";
  import { useSSE } from "$stores/sse.svelte";
  import { useBadges } from "$stores/badges.svelte";
  import { useNotifications } from "$stores/notifications.svelte";
  import { handleSseMessage } from "$services/sseMessageHandler";
  import { sidebar as sidebarStorage } from "$utils/storage";
  import { toggleMode, mode } from "mode-watcher";
  import type { SSEMessage } from "$api/endpoints/sse";
  import AppHeader from "$components/layout/AppHeader.svelte";
  import AppSidebar from "$components/layout/AppSidebar.svelte";

  let { children } = $props();

  const auth = useAuth();
  const sse = useSSE();
  const badges = useBadges();
  const notifications = useNotifications();

  let sidebarCollapsed = $state(sidebarStorage.get());
  let unsubscribeSSE: (() => void) | null = null;

  onMount(() => {
    badges.startAutoRefresh();
    unsubscribeSSE = sse.subscribe(handleSSEMessage);
  });

  onDestroy(() => {
    badges.stopAutoRefresh();
    sse.cleanup();
    unsubscribeSSE?.();
  });

  // 用户可用时初始化 SSE（处理首次加载与用户变更）
  $effect(() => {
    if (auth.user?.id && auth.initialized) {
      sse.init(auth.user.id);
    }
  });

  function handleSSEMessage(message: SSEMessage) {
    handleSseMessage(message, {
      pathname: $page.url.pathname,
      badges,
      notifications,
    });
  }

  function toggleSidebar() {
    sidebarCollapsed = !sidebarCollapsed;
    sidebarStorage.set(sidebarCollapsed);
  }


  function getBadgeCount(
    key: "messages" | "announcements" | "tickets" | "friends" | null
  ): number {
    if (!key) return 0;
    switch (key) {
      case "messages":
        return badges.unreadMessages;
      case "announcements":
        return badges.unreadAnnouncements;
      case "tickets":
        return badges.pendingTickets;
      case "friends":
        return badges.friendBadgeTotal;
      default:
        return 0;
    }
  }
</script>

{#if !auth.initialized}
  <div class="flex h-screen items-center justify-center bg-background">
    <div class="flex flex-col items-center gap-4">
      <div
        class="h-8 w-8 animate-spin rounded-full border-4 border-primary border-t-transparent"
      ></div>
      <p class="text-sm text-muted-foreground">正在加载用户信息...</p>
    </div>
  </div>
{:else if auth.error && !auth.user}
  <div class="flex h-screen items-center justify-center bg-background">
    <div class="flex flex-col items-center gap-4 text-center max-w-sm px-4">
      <div class="rounded-full bg-destructive/10 p-4 text-destructive mb-2">
        <svg class="h-8 w-8" fill="none" stroke="currentColor" viewBox="0 0 24 24">
          <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M12 9v2m0 4h.01m-6.938 4h13.856c1.54 0 2.502-1.667 1.732-3L13.732 4c-.77-1.333-2.694-1.333-3.464 0L3.34 16c-.77 1.333.192 3 1.732 3z" />
        </svg>
      </div>
      <div>
        <h2 class="text-xl font-semibold mb-2">会话加载失败</h2>
        <p class="text-sm text-muted-foreground">{auth.error}</p>
      </div>
      <div class="flex gap-4 mt-6">
        <button
          class="rounded-lg bg-primary px-6 py-2 text-sm font-medium text-primary-foreground hover:bg-primary/90"
          onclick={() => auth.fetchUser()}
        >
          重试
        </button>
        <button
          class="rounded-lg border bg-background px-6 py-2 text-sm font-medium hover:bg-accent"
          onclick={() => auth.logout()}
        >
          重新登录
        </button>
      </div>
    </div>
  </div>
{:else}
  <div class="flex h-screen">
    <AppSidebar
      collapsed={sidebarCollapsed}
      onToggle={toggleSidebar}
      pathname={$page.url.pathname}
      isAdmin={auth.isAdmin}
      getBadgeCount={getBadgeCount}
    />

    <!-- 主内容 -->
    <div class="flex flex-1 flex-col overflow-hidden">
      <AppHeader
        sidebarCollapsed={sidebarCollapsed}
        modeCurrent={mode.current ?? ""}
        onToggleMode={toggleMode}
        sse={sse}
        displayName={auth.displayName}
        onOpenSettings={() => goto("/settings")}
        onLogout={() => auth.logout()}
      />

      <!-- 页面内容 -->
      <main class="flex-1 overflow-y-auto bg-muted/30 p-6">
        {#key $page.url.pathname}
          <div in:fly={{ y: 10, duration: 400, delay: 100 }} class="h-full">
            {@render children()}
          </div>
        {/key}
      </main>
    </div>
  </div>
{/if}
