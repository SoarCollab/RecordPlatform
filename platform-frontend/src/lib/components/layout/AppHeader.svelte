<script lang="ts">
  import AppIcon from "$components/ui/AppIcon.svelte";
  import GlobalSearch from "$lib/components/GlobalSearch.svelte";

  type SseState = {
    status: string;
    isLeader: boolean;
    isConnected: boolean;
    canManualReconnect: boolean;
    manualReconnect: () => void;
  };

  type Props = {
    sidebarCollapsed: boolean;
    modeCurrent: string;
    onToggleMode: () => void;
    sse: SseState;
    displayName: string | null | undefined;
    onOpenSettings: () => void;
    onLogout: () => void;
  };

  let props: Props = $props();

  const sidebarCollapsed = $derived(props.sidebarCollapsed);
  const modeCurrent = $derived(props.modeCurrent);
  const sse = $derived(props.sse);
  const displayName = $derived(props.displayName);
</script>

<header class="flex h-16 items-center justify-between border-b bg-card px-6">
  <div>
    <GlobalSearch />
  </div>

  <div class="flex items-center gap-4">
    <button
      class="rounded-lg p-2 text-muted-foreground hover:bg-accent hover:text-foreground"
      onclick={props.onToggleMode}
      aria-label={modeCurrent === "dark" ? "切换到亮色模式" : "切换到暗色模式"}
      title={modeCurrent === "dark" ? "切换到亮色模式" : "切换到暗色模式"}
    >
      {#if modeCurrent === "dark"}
        <AppIcon name="sun" class="h-5 w-5" />
      {:else}
        <AppIcon name="moon" class="h-5 w-5" />
      {/if}
    </button>

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
          onclick={sse.manualReconnect}
          title="点击重新连接"
        >
          重连
        </button>
      {/if}
    </div>

    <div class="relative">
      <button
        class="flex items-center gap-2 rounded-lg px-3 py-2 hover:bg-accent"
        onclick={props.onOpenSettings}
      >
        <div
          class="flex h-8 w-8 items-center justify-center rounded-full bg-primary/10 text-sm font-medium text-primary"
        >
          {displayName?.charAt(0).toUpperCase() || "U"}
        </div>
        <span class="hidden text-sm sm:inline">{displayName}</span>
      </button>
    </div>

    <button
      class="rounded-lg px-3 py-2 text-sm text-muted-foreground hover:bg-accent hover:text-foreground"
      onclick={props.onLogout}
      aria-label="退出登录"
    >
      <AppIcon name="logout" class="h-5 w-5" />
    </button>
  </div>
</header>
