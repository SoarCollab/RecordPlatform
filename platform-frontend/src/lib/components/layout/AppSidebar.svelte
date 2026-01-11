<script lang="ts">
  import AppIcon from "$components/ui/AppIcon.svelte";
  import { adminItems, menuItems, type BadgeKey } from "$lib/config/navigation";
  import logo from "$lib/assets/logo.png";

  let { collapsed, onToggle, pathname, isAdmin, getBadgeCount } = $props<{
    collapsed: boolean;
    onToggle: () => void;
    pathname: string;
    isAdmin: boolean;
    getBadgeCount: (key: BadgeKey) => number;
  }>();


  function isActive(href: string) {
    return pathname === href || pathname.startsWith(href + "/");
  }
</script>

<aside
  class="flex flex-col border-r bg-card transition-all duration-300"
  class:w-64={!collapsed}
  class:w-16={collapsed}
>
  <div class="flex h-16 items-center border-b px-4">
    {#if !collapsed}
      <div class="flex items-center gap-2">
        <img src={logo} alt="Logo" class="h-8 w-8 rounded-lg" />
        <span class="font-bold">存证平台</span>
      </div>
    {:else}
      <img src={logo} alt="Logo" class="mx-auto h-8 w-8 rounded-lg" />
    {/if}
  </div>

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
            class:justify-center={collapsed}
            title={collapsed
              ? `${item.label}${badgeCount > 0 ? ` (${badgeCount})` : ""}`
              : undefined}
          >
            <div class="relative">
              <AppIcon name={item.icon} class="h-5 w-5 shrink-0" />
              {#if collapsed && badgeCount > 0}
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
            {#if !collapsed}
              <span class="flex-1">{item.label}</span>
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

    {#if isAdmin}
      <div class="my-4 border-t"></div>
      <p
        class="mb-2 px-3 text-xs font-medium uppercase text-muted-foreground"
        class:hidden={collapsed}
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
              class:justify-center={collapsed}
              title={collapsed ? item.label : undefined}
            >
              <AppIcon name={item.icon} class="h-5 w-5 shrink-0" />
              {#if !collapsed}
                <span>{item.label}</span>
              {/if}
            </a>
          </li>
        {/each}
      </ul>
    {/if}
  </nav>

  <div class="border-t p-2">
    <button
      class="flex w-full items-center justify-center gap-2 rounded-lg px-3 py-2 text-sm text-muted-foreground hover:bg-accent"
      onclick={onToggle}
      aria-label={collapsed ? "展开侧边栏" : "收起侧边栏"}
    >
      <AppIcon
        name="chevron-left"
        class={"h-5 w-5 " + (collapsed ? "rotate-180" : "")}
      />
    </button>
  </div>
</aside>
