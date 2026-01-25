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

  // 用户可用时初始化 SSE（兼容首次加载与用户变更）
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
