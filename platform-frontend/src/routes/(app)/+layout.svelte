<script lang="ts">
  import { page } from "$app/stores";
  import { goto } from "$app/navigation";
  import { fly } from "svelte/transition";
  import { onMount, onDestroy } from "svelte";
  import { useAuth } from "$stores/auth.svelte";
  import { useSSE } from "$stores/sse.svelte";
  import { useBadges } from "$stores/badges.svelte";
  import { useNotifications } from "$stores/notifications.svelte";
  import { sidebar as sidebarStorage } from "$utils/storage";
  import { toggleMode, mode } from "mode-watcher";
  import type { SSEMessage } from "$api/endpoints/sse";
  import GlobalSearch from "$lib/components/GlobalSearch.svelte";
  import logo from "$lib/assets/logo.png";

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

  // Initialize SSE when user is available (handles both initial load and user changes)
  $effect(() => {
    if (auth.user?.id && auth.initialized) {
      sse.init(auth.user.id);
    }
  });

  function handleSSEMessage(message: SSEMessage) {
    switch (message.type) {
      case "message-received": {
        // New private message received
        const data = message.data as { senderName?: string; content?: string };
        badges.updateMessageCount(badges.unreadMessages + 1);
        // Show notification if not on messages page
        if (!$page.url.pathname.startsWith("/messages")) {
          notifications.info(
            data.senderName ? `来自 ${data.senderName} 的新消息` : "收到新消息",
            data.content || "点击查看"
          );
        }
        break;
      }
      case "announcement-published": {
        // New announcement
        const data = message.data as { title?: string };
        badges.updateAnnouncementCount(badges.unreadAnnouncements + 1);
        notifications.info("新公告", data.title || "系统发布了新公告");
        break;
      }
      case "ticket-updated": {
        // Ticket status changed
        const data = message.data as { title?: string; status?: string };
        badges.fetch(); // Refresh badge counts
        notifications.info(
          "工单更新",
          data.title ? `工单「${data.title}」状态已更新` : "您的工单有新回复"
        );
        break;
      }
      case "file-processed": {
        // File upload completed
        const data = message.data as { fileName?: string; status?: string };
        if (data.status === "completed") {
          notifications.success(
            "文件处理完成",
            data.fileName || "您的文件已处理完毕"
          );
        } else if (data.status === "failed") {
          notifications.error(
            "文件处理失败",
            data.fileName || "文件处理过程中出错"
          );
        }
        break;
      }
      case "badge-update": {
        // Direct badge count update from server
        const data = message.data as {
          messages?: number;
          announcements?: number;
          tickets?: number;
        };
        if (typeof data.messages === "number")
          badges.updateMessageCount(data.messages);
        if (typeof data.announcements === "number")
          badges.updateAnnouncementCount(data.announcements);
        if (typeof data.tickets === "number")
          badges.updateTicketCount(data.tickets);
        break;
      }
      case "notification": {
        // Generic notification
        const data = message.data as {
          title?: string;
          message?: string;
          type?: string;
        };
        const title = data.title || "通知";
        const content = data.message || "";
        if (data.type === "error") {
          notifications.error(title, content);
        } else if (data.type === "warning") {
          notifications.warning(title, content);
        } else if (data.type === "success") {
          notifications.success(title, content);
        } else {
          notifications.info(title, content);
        }
        break;
      }
    }
  }

  function toggleSidebar() {
    sidebarCollapsed = !sidebarCollapsed;
    sidebarStorage.set(sidebarCollapsed);
  }

  // Menu items with badge keys
  const menuItems = [
    { href: "/dashboard", icon: "home", label: "仪表盘", badgeKey: null },
    { href: "/files", icon: "folder", label: "文件管理", badgeKey: null },
    { href: "/shares", icon: "share", label: "分享管理", badgeKey: null },
    { href: "/upload", icon: "upload", label: "上传文件", badgeKey: null },
    {
      href: "/messages",
      icon: "message",
      label: "消息中心",
      badgeKey: "messages" as const,
    },
    {
      href: "/announcements",
      icon: "megaphone",
      label: "系统公告",
      badgeKey: "announcements" as const,
    },
    {
      href: "/tickets",
      icon: "ticket",
      label: "工单系统",
      badgeKey: "tickets" as const,
    },
    { href: "/settings", icon: "settings", label: "个人设置", badgeKey: null },
  ];

  const adminItems = [
    { href: "/admin/monitor", icon: "activity", label: "系统监控" },
    { href: "/admin/audit", icon: "shield", label: "审计日志" },
    { href: "/admin/permissions", icon: "users", label: "权限管理" },
  ];

  function isActive(href: string) {
    return (
      $page.url.pathname === href || $page.url.pathname.startsWith(href + "/")
    );
  }

  function getBadgeCount(
    key: "messages" | "announcements" | "tickets" | null
  ): number {
    if (!key) return 0;
    switch (key) {
      case "messages":
        return badges.unreadMessages;
      case "announcements":
        return badges.unreadAnnouncements;
      case "tickets":
        return badges.pendingTickets;
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
    <!-- Sidebar -->
    <aside
      class="flex flex-col border-r bg-card transition-all duration-300"
      class:w-64={!sidebarCollapsed}
      class:w-16={sidebarCollapsed}
    >
      <!-- Logo -->
      <div class="flex h-16 items-center border-b px-4">
        {#if !sidebarCollapsed}
          <div class="flex items-center gap-2">
            <img src={logo} alt="Logo" class="h-8 w-8 rounded-lg" />
            <span class="font-bold">存证平台</span>
          </div>
        {:else}
          <img src={logo} alt="Logo" class="mx-auto h-8 w-8 rounded-lg" />
        {/if}
      </div>

      <!-- Navigation -->
      <nav class="flex-1 overflow-y-auto p-2">
        <ul class="space-y-1">
          {#each menuItems as item}
            {@const badgeCount = getBadgeCount(item.badgeKey)}
            <li>
              <a
                href={item.href}
                class="relative flex items-center gap-3 rounded-lg px-3 py-2 text-sm transition-colors hover:bg-accent"
                class:bg-accent={isActive(item.href)}
                class:text-primary={isActive(item.href)}
                class:justify-center={sidebarCollapsed}
                title={sidebarCollapsed
                  ? `${item.label}${badgeCount > 0 ? ` (${badgeCount})` : ""}`
                  : undefined}
              >
                <div class="relative">
                  <svg
                    class="h-5 w-5 shrink-0"
                    fill="none"
                    stroke="currentColor"
                    viewBox="0 0 24 24"
                  >
                    {#if item.icon === "home"}
                      <path
                        stroke-linecap="round"
                        stroke-linejoin="round"
                        stroke-width="2"
                        d="M3 12l2-2m0 0l7-7 7 7M5 10v10a1 1 0 001 1h3m10-11l2 2m-2-2v10a1 1 0 01-1 1h-3m-6 0a1 1 0 001-1v-4a1 1 0 011-1h2a1 1 0 011 1v4a1 1 0 001 1m-6 0h6"
                      />
                    {:else if item.icon === "folder"}
                      <path
                        stroke-linecap="round"
                        stroke-linejoin="round"
                        stroke-width="2"
                        d="M3 7v10a2 2 0 002 2h14a2 2 0 002-2V9a2 2 0 00-2-2h-6l-2-2H5a2 2 0 00-2 2z"
                      />
                    {:else if item.icon === "share"}
                      <path
                        stroke-linecap="round"
                        stroke-linejoin="round"
                        stroke-width="2"
                        d="M8.684 13.342C8.886 12.938 9 12.482 9 12c0-.482-.114-.938-.316-1.342m0 2.684a3 3 0 110-2.684m0 2.684l6.632 3.316m-6.632-6l6.632-3.316m0 0a3 3 0 105.367-2.684 3 3 0 00-5.367 2.684zm0 9.316a3 3 0 105.368 2.684 3 3 0 00-5.368-2.684z"
                      />
                    {:else if item.icon === "upload"}
                      <path
                        stroke-linecap="round"
                        stroke-linejoin="round"
                        stroke-width="2"
                        d="M4 16v1a3 3 0 003 3h10a3 3 0 003-3v-1m-4-8l-4-4m0 0L8 8m4-4v12"
                      />
                    {:else if item.icon === "message"}
                      <path
                        stroke-linecap="round"
                        stroke-linejoin="round"
                        stroke-width="2"
                        d="M8 12h.01M12 12h.01M16 12h.01M21 12c0 4.418-4.03 8-9 8a9.863 9.863 0 01-4.255-.949L3 20l1.395-3.72C3.512 15.042 3 13.574 3 12c0-4.418 4.03-8 9-8s9 3.582 9 8z"
                      />
                    {:else if item.icon === "megaphone"}
                      <path
                        stroke-linecap="round"
                        stroke-linejoin="round"
                        stroke-width="2"
                        d="M11 5.882V19.24a1.76 1.76 0 01-3.417.592l-2.147-6.15M18 13a3 3 0 100-6M5.436 13.683A4.001 4.001 0 017 6h1.832c4.1 0 7.625-1.234 9.168-3v14c-1.543-1.766-5.067-3-9.168-3H7a3.988 3.988 0 01-1.564-.317z"
                      />
                    {:else if item.icon === "ticket"}
                      <path
                        stroke-linecap="round"
                        stroke-linejoin="round"
                        stroke-width="2"
                        d="M15 5v2m0 4v2m0 4v2M5 5a2 2 0 00-2 2v3a2 2 0 110 4v3a2 2 0 002 2h14a2 2 0 002-2v-3a2 2 0 110-4V7a2 2 0 00-2-2H5z"
                      />
                    {:else if item.icon === "settings"}
                      <path
                        stroke-linecap="round"
                        stroke-linejoin="round"
                        stroke-width="2"
                        d="M10.325 4.317c.426-1.756 2.924-1.756 3.35 0a1.724 1.724 0 002.573 1.066c1.543-.94 3.31.826 2.37 2.37a1.724 1.724 0 001.065 2.572c1.756.426 1.756 2.924 0 3.35a1.724 1.724 0 00-1.066 2.573c.94 1.543-.826 3.31-2.37 2.37a1.724 1.724 0 00-2.572 1.065c-.426 1.756-2.924 1.756-3.35 0a1.724 1.724 0 00-2.573-1.066c-1.543.94-3.31-.826-2.37-2.37a1.724 1.724 0 00-1.065-2.572c-1.756-.426-1.756-2.924 0-3.35a1.724 1.724 0 001.066-2.573c-.94-1.543.826-3.31 2.37-2.37.996.608 2.296.07 2.572-1.065z"
                      />
                      <path
                        stroke-linecap="round"
                        stroke-linejoin="round"
                        stroke-width="2"
                        d="M15 12a3 3 0 11-6 0 3 3 0 016 0z"
                      />
                    {/if}
                  </svg>
                  <!-- Badge dot for collapsed sidebar -->
                  {#if sidebarCollapsed && badgeCount > 0}
                    <span class="absolute -right-1 -top-1 flex h-2 w-2">
                      <span
                        class="absolute inline-flex h-full w-full animate-ping rounded-full bg-destructive opacity-75"
                      ></span>
                      <span
                        class="relative inline-flex h-2 w-2 rounded-full bg-destructive"
                      ></span>
                    </span>
                  {/if}
                </div>
                {#if !sidebarCollapsed}
                  <span class="flex-1">{item.label}</span>
                  <!-- Badge count for expanded sidebar -->
                  {#if badgeCount > 0}
                    <span
                      class="flex h-5 min-w-5 items-center justify-center rounded-full bg-destructive px-1.5 text-xs font-medium text-white"
                    >
                      {badgeCount > 99 ? "99+" : badgeCount}
                    </span>
                  {/if}
                {/if}
              </a>
            </li>
          {/each}
        </ul>

        {#if auth.isAdmin}
          <div class="my-4 border-t"></div>
          <p
            class="mb-2 px-3 text-xs font-medium uppercase text-muted-foreground"
            class:hidden={sidebarCollapsed}
          >
            管理功能
          </p>
          <ul class="space-y-1">
            {#each adminItems as item}
              <li>
                <a
                  href={item.href}
                  class="flex items-center gap-3 rounded-lg px-3 py-2 text-sm transition-colors hover:bg-accent"
                  class:bg-accent={isActive(item.href)}
                  class:text-primary={isActive(item.href)}
                  class:justify-center={sidebarCollapsed}
                  title={sidebarCollapsed ? item.label : undefined}
                >
                  <svg
                    class="h-5 w-5 shrink-0"
                    fill="none"
                    stroke="currentColor"
                    viewBox="0 0 24 24"
                  >
                    {#if item.icon === "activity"}
                      <path
                        stroke-linecap="round"
                        stroke-linejoin="round"
                        stroke-width="2"
                        d="M9 19v-6a2 2 0 00-2-2H5a2 2 0 00-2 2v6a2 2 0 002 2h2a2 2 0 002-2zm0 0V9a2 2 0 012-2h2a2 2 0 012 2v10m-6 0a2 2 0 002 2h2a2 2 0 002-2m0 0V5a2 2 0 012-2h2a2 2 0 012 2v14a2 2 0 01-2 2h-2a2 2 0 01-2-2z"
                      />
                    {:else if item.icon === "shield"}
                      <path
                        stroke-linecap="round"
                        stroke-linejoin="round"
                        stroke-width="2"
                        d="M9 12l2 2 4-4m5.618-4.016A11.955 11.955 0 0112 2.944a11.955 11.955 0 01-8.618 3.04A12.02 12.02 0 003 9c0 5.591 3.824 10.29 9 11.622 5.176-1.332 9-6.03 9-11.622 0-1.042-.133-2.052-.382-3.016z"
                      />
                    {:else if item.icon === "users"}
                      <path
                        stroke-linecap="round"
                        stroke-linejoin="round"
                        stroke-width="2"
                        d="M12 4.354a4 4 0 110 5.292M15 21H3v-1a6 6 0 0112 0v1zm0 0h6v-1a6 6 0 00-9-5.197M13 7a4 4 0 11-8 0 4 4 0 018 0z"
                      />
                    {/if}
                  </svg>
                  {#if !sidebarCollapsed}
                    <span>{item.label}</span>
                  {/if}
                </a>
              </li>
            {/each}
          </ul>
        {/if}
      </nav>

      <!-- Collapse button -->
      <div class="border-t p-2">
        <button
          class="flex w-full items-center justify-center gap-2 rounded-lg px-3 py-2 text-sm text-muted-foreground hover:bg-accent"
          onclick={toggleSidebar}
          aria-label={sidebarCollapsed ? "展开侧边栏" : "收起侧边栏"}
        >
          <svg
            class="h-5 w-5"
            fill="none"
            stroke="currentColor"
            viewBox="0 0 24 24"
            class:rotate-180={sidebarCollapsed}
          >
            <path
              stroke-linecap="round"
              stroke-linejoin="round"
              stroke-width="2"
              d="M11 19l-7-7 7-7m8 14l-7-7 7-7"
            />
          </svg>
        </button>
      </div>
    </aside>

    <!-- Main content -->
    <div class="flex flex-1 flex-col overflow-hidden">
      <!-- Header -->
      <header
        class="flex h-16 items-center justify-between border-b bg-card px-6"
      >
        <div>
          <!-- Global Search -->
          <GlobalSearch />
        </div>
        <div class="flex items-center gap-4">
          <!-- Theme Toggle -->
          <button
            class="rounded-lg p-2 text-muted-foreground hover:bg-accent hover:text-foreground"
            onclick={toggleMode}
            aria-label={mode.current === "dark"
              ? "切换到亮色模式"
              : "切换到暗色模式"}
            title={mode.current === "dark"
              ? "切换到亮色模式"
              : "切换到暗色模式"}
          >
            {#if mode.current === "dark"}
              <svg
                class="h-5 w-5"
                fill="none"
                stroke="currentColor"
                viewBox="0 0 24 24"
              >
                <path
                  stroke-linecap="round"
                  stroke-linejoin="round"
                  stroke-width="2"
                  d="M12 3v1m0 16v1m9-9h-1M4 12H3m15.364 6.364l-.707-.707M6.343 6.343l-.707-.707m12.728 0l-.707.707M6.343 17.657l-.707.707M16 12a4 4 0 11-8 0 4 4 0 018 0z"
                />
              </svg>
            {:else}
              <svg
                class="h-5 w-5"
                fill="none"
                stroke="currentColor"
                viewBox="0 0 24 24"
              >
                <path
                  stroke-linecap="round"
                  stroke-linejoin="round"
                  stroke-width="2"
                  d="M20.354 15.354A9 9 0 018.646 3.646 9.003 9.003 0 0012 21a9.003 9.003 0 008.354-5.646z"
                />
              </svg>
            {/if}
          </button>

          <!-- SSE Status -->
          <div
            class="flex items-center gap-2 text-sm text-muted-foreground"
            title={`SSE: ${sse.status}${sse.isLeader ? " (主窗口)" : ""}`}
          >
            <span
              class="h-2 w-2 rounded-full"
              class:bg-green-500={sse.isConnected}
              class:bg-yellow-500={sse.status === "connecting"}
              class:bg-red-500={sse.status === "error"}
              class:bg-gray-400={sse.status === "disconnected"}
            ></span>
            {#if !sidebarCollapsed}
              <span class="hidden sm:inline">
                {sse.isConnected
                  ? "已连接"
                  : sse.status === "connecting"
                    ? "连接中"
                    : "未连接"}
              </span>
            {/if}
            {#if sse.canManualReconnect}
              <button
                class="rounded px-2 py-0.5 text-xs text-primary hover:bg-accent"
                onclick={() => sse.manualReconnect()}
                title="点击重新连接"
              >
                重连
              </button>
            {/if}
          </div>

          <!-- User menu -->
          <div class="relative">
            <button
              class="flex items-center gap-2 rounded-lg px-3 py-2 hover:bg-accent"
              onclick={() => goto("/settings")}
            >
              <div
                class="flex h-8 w-8 items-center justify-center rounded-full bg-primary/10 text-sm font-medium text-primary"
              >
                {auth.displayName?.charAt(0).toUpperCase() || "U"}
              </div>
              <span class="hidden text-sm sm:inline">{auth.displayName}</span>
            </button>
          </div>

          <!-- Logout -->
          <button
            class="rounded-lg px-3 py-2 text-sm text-muted-foreground hover:bg-accent hover:text-foreground"
            onclick={() => auth.logout()}
            aria-label="退出登录"
          >
            <svg
              class="h-5 w-5"
              fill="none"
              stroke="currentColor"
              viewBox="0 0 24 24"
            >
              <path
                stroke-linecap="round"
                stroke-linejoin="round"
                stroke-width="2"
                d="M17 16l4-4m0 0l-4-4m4 4H7m6 4v1a3 3 0 01-3 3H6a3 3 0 01-3-3V7a3 3 0 013-3h4a3 3 0 013 3v1"
              />
            </svg>
          </button>
        </div>
      </header>

      <!-- Page content -->
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
