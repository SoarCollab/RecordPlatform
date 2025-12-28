<script lang="ts">
  import { onMount } from "svelte";
  import { useNotifications } from "$stores/notifications.svelte";
  import { useAuth } from "$stores/auth.svelte";
  import { goto } from "$app/navigation";
  import { getPermissionTree } from "$api/endpoints/system";
  import { PermissionType, type SysPermission } from "$api/types";
  import { Button } from "$lib/components/ui/button";
  import { Badge } from "$lib/components/ui/badge";
  import { Input } from "$lib/components/ui/input";

  const notifications = useNotifications();
  const auth = useAuth();

  let permissions = $state<SysPermission[]>([]);
  let loading = $state(true);
  let searchQuery = $state("");
  let expandedNodes = $state<Set<string>>(new Set());

  // Filtered permissions based on search
  const filteredPermissions = $derived(
    searchQuery
      ? filterPermissions(permissions, searchQuery.toLowerCase())
      : permissions
  );

  onMount(() => {
    if (!auth.isAdmin) {
      notifications.error("权限不足", "仅管理员可访问此页面");
      goto("/dashboard");
      return;
    }
    loadPermissions();
  });

  async function loadPermissions() {
    loading = true;
    try {
      const rawPermissions = await getPermissionTree();
      permissions = buildPermissionTree(rawPermissions);
      // Expand first level by default
      permissions.forEach((p) => expandedNodes.add(p.id));
      expandedNodes = new Set(expandedNodes);
    } catch (err) {
      notifications.error(
        "加载失败",
        err instanceof Error ? err.message : "请稍后重试"
      );
    } finally {
      loading = false;
    }
  }

  function buildPermissionTree(flatPerms: SysPermission[]): SysPermission[] {
    const modules = new Map<string, SysPermission[]>();

    // Group by module
    flatPerms.forEach((p) => {
      const action =
        p.action || (p.code.includes(":") ? p.code.split(":")[1] : "");

      // Infer type since backend doesn't provide it
      p.type = inferPermissionType(action);

      const moduleName =
        p.module || (p.code.includes(":") ? p.code.split(":")[0] : "other");

      if (!modules.has(moduleName)) {
        modules.set(moduleName, []);
      }
      modules.get(moduleName)!.push(p);
    });

    // Create tree
    const tree: SysPermission[] = [];
    let sortCounter = 0;

    modules.forEach((children, moduleName) => {
      tree.push({
        id: `module-${moduleName}`,
        name: getModuleDisplayName(moduleName),
        code: `module:${moduleName}`,
        type: PermissionType.MENU,
        module: moduleName,
        sort: sortCounter++,
        status: 1,
        children: children,
      });
    });

    // Sort modules by name/sort
    return tree.sort((a, b) => a.sort - b.sort);
  }

  function inferPermissionType(action: string): PermissionType {
    const lowerAction = action.toLowerCase();
    if (
      ["read", "view", "list", "query", "get", "monitor"].some((k) =>
        lowerAction.includes(k)
      )
    ) {
      return PermissionType.API;
    }
    if (["admin", "manage"].some((k) => lowerAction.includes(k))) {
      return PermissionType.BUTTON;
    }
    return PermissionType.BUTTON; // Default for write/update/delete etc.
  }

  function getModuleDisplayName(module: string): string {
    const map: Record<string, string> = {
      announcement: "公告管理",
      file: "文件管理",
      message: "消息管理",
      system: "系统管理",
      ticket: "工单管理",
      user: "用户管理",
      permission: "权限管理",
      role: "角色管理",
      audit: "审计日志",
    };
    return map[module] || module.charAt(0).toUpperCase() + module.slice(1);
  }

  function filterPermissions(
    perms: SysPermission[],
    query: string
  ): SysPermission[] {
    const result: SysPermission[] = [];
    for (const perm of perms) {
      const matches =
        perm.name.toLowerCase().includes(query) ||
        perm.code.toLowerCase().includes(query);
      const childMatches = perm.children
        ? filterPermissions(perm.children, query)
        : [];

      if (matches || childMatches.length > 0) {
        result.push({
          ...perm,
          children: childMatches.length > 0 ? childMatches : perm.children,
        });
      }
    }
    return result;
  }

  function toggleExpand(id: string) {
    if (expandedNodes.has(id)) {
      expandedNodes.delete(id);
    } else {
      expandedNodes.add(id);
    }
    expandedNodes = new Set(expandedNodes);
  }

  function expandAll() {
    const allIds = collectAllIds(permissions);
    expandedNodes = new Set(allIds);
  }

  function collapseAll() {
    expandedNodes = new Set();
  }

  function collectAllIds(perms: SysPermission[]): string[] {
    const ids: string[] = [];
    for (const perm of perms) {
      ids.push(perm.id);
      if (perm.children) {
        ids.push(...collectAllIds(perm.children));
      }
    }
    return ids;
  }

  function getTypeLabel(type: PermissionType): string {
    switch (type) {
      case PermissionType.MENU:
        return "菜单";
      case PermissionType.BUTTON:
        return "按钮";
      case PermissionType.API:
        return "API";
      default:
        return "未知";
    }
  }

  function getTypeIcon(type: PermissionType): string {
    switch (type) {
      case PermissionType.MENU:
        return "menu";
      case PermissionType.BUTTON:
        return "button";
      case PermissionType.API:
        return "api";
      default:
        return "unknown";
    }
  }
</script>

<svelte:head>
  <title>权限管理 - 存证平台</title>
</svelte:head>

<div class="space-y-6">
  <div class="flex items-center justify-between">
    <div>
      <h1 class="text-2xl font-bold">权限管理</h1>
      <p class="text-muted-foreground">查看系统权限树结构</p>
    </div>
    <div class="flex gap-2">
      <Button variant="outline" size="sm" onclick={expandAll}>展开全部</Button>
      <Button variant="outline" size="sm" onclick={collapseAll}>折叠全部</Button
      >
    </div>
  </div>

  <!-- Search -->
  <div
    class="mb-4 flex gap-4 rounded-xl border bg-card/50 p-4 backdrop-blur-sm"
  >
    <div class="relative flex-1 max-w-lg">
      <div
        class="pointer-events-none absolute left-3 top-2.5 text-muted-foreground"
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
            d="M21 21l-6-6m2-5a7 7 0 11-14 0 7 7 0 0114 0z"
          />
        </svg>
      </div>
      <Input
        placeholder="搜索权限名称或编码..."
        bind:value={searchQuery}
        class="pl-9"
      />
    </div>
    {#if searchQuery}
      <Button
        variant="ghost"
        onclick={() => (searchQuery = "")}
        class="text-muted-foreground hover:text-foreground"
      >
        清除
      </Button>
    {/if}
  </div>

  <!-- Permission Tree -->
  <div class="rounded-xl border bg-card text-card-foreground shadow-sm">
    <div class="p-6">
      {#if loading}
        <div class="flex h-64 items-center justify-center">
          <div class="flex flex-col items-center gap-2">
            <div
              class="h-8 w-8 animate-spin rounded-full border-4 border-primary border-t-transparent"
            ></div>
            <p class="text-sm text-muted-foreground">加载权限数据中...</p>
          </div>
        </div>
      {:else if filteredPermissions.length === 0}
        <div
          class="flex h-64 flex-col items-center justify-center p-12 text-center text-muted-foreground"
        >
          <div
            class="mb-4 flex h-16 w-16 items-center justify-center rounded-full bg-muted"
          >
            <svg
              class="h-8 w-8 text-muted-foreground"
              fill="none"
              stroke="currentColor"
              viewBox="0 0 24 24"
            >
              <path
                stroke-linecap="round"
                stroke-linejoin="round"
                stroke-width="2"
                d="M12 15v2m-6 4h12a2 2 0 002-2v-6a2 2 0 00-2-2H6a2 2 0 00-2 2v6a2 2 0 002 2zm10-10V7a4 4 0 00-8 0v4h8z"
              />
            </svg>
          </div>
          {#if searchQuery}
            <p>未找到匹配的权限</p>
          {:else}
            <p>暂无权限数据</p>
          {/if}
        </div>
      {:else}
        <div class="space-y-1">
          {#each filteredPermissions as permission (permission.id)}
            {@render PermissionNode(permission, 0)}
          {/each}
        </div>
      {/if}
    </div>
  </div>

  <!-- Legend -->
  <div
    class="mt-4 flex items-center justify-between rounded-lg border bg-muted/30 px-4 py-3"
  >
    <div class="flex items-center gap-6">
      <span class="text-sm font-medium text-muted-foreground">图例说明：</span>
      <div class="flex items-center gap-2">
        <div
          class="flex h-5 w-5 items-center justify-center rounded bg-blue-100 text-blue-700 dark:bg-blue-900/30 dark:text-blue-300"
        >
          <svg
            class="h-3 w-3"
            fill="none"
            stroke="currentColor"
            viewBox="0 0 24 24"
          >
            <path
              stroke-linecap="round"
              stroke-linejoin="round"
              stroke-width="2"
              d="M4 6h16M4 12h16M4 18h16"
            />
          </svg>
        </div>
        <span class="text-sm">菜单</span>
      </div>
      <div class="flex items-center gap-2">
        <div
          class="flex h-5 w-5 items-center justify-center rounded bg-purple-100 text-purple-700 dark:bg-purple-900/30 dark:text-purple-300"
        >
          <svg
            class="h-3 w-3"
            fill="none"
            stroke="currentColor"
            viewBox="0 0 24 24"
          >
            <path
              stroke-linecap="round"
              stroke-linejoin="round"
              stroke-width="2"
              d="M15 15l-2 5L9 9l11 4-5 2zm0 0l5 5"
            />
          </svg>
        </div>
        <span class="text-sm">按钮</span>
      </div>
      <div class="flex items-center gap-2">
        <div
          class="flex h-5 w-5 items-center justify-center rounded bg-emerald-100 text-emerald-700 dark:bg-emerald-900/30 dark:text-emerald-300"
        >
          <svg
            class="h-3 w-3"
            fill="none"
            stroke="currentColor"
            viewBox="0 0 24 24"
          >
            <path
              stroke-linecap="round"
              stroke-linejoin="round"
              stroke-width="2"
              d="M10 20l4-16m4 4l4 4-4 4M6 16l-4-4 4-4"
            />
          </svg>
        </div>
        <span class="text-sm">API</span>
      </div>
    </div>
  </div>
</div>

{#snippet PermissionNode(perm: SysPermission, level: number)}
  <div class="permission-node relative">
    <!-- Connection Line for Children -->
    {#if level > 0}
      <div
        class="absolute -left-3 top-0 h-full w-px bg-border/50"
        style="left: calc(-12px + 24px * {level})"
      ></div>
    {/if}

    <div
      class="relative flex items-center gap-3 rounded-lg border border-transparent px-3 py-2 transition-colors hover:bg-muted/50 hover:border-border/50"
      style="margin-left: {level * 24}px"
    >
      <!-- Connector (Horizontal) if not root -->
      {#if level > 0}
        <div class="absolute -left-6 top-1/2 h-px w-6 bg-border/50"></div>
      {/if}

      <!-- Expand/Collapse Button -->
      {#if perm.children && perm.children.length > 0}
        <button
          class="z-10 flex h-6 w-6 shrink-0 items-center justify-center rounded-md border bg-background shadow-sm hover:bg-accent hover:text-accent-foreground"
          onclick={() => toggleExpand(perm.id)}
          aria-label={expandedNodes.has(perm.id) ? "收起" : "展开"}
        >
          <svg
            class="h-3 w-3 transition-transform duration-200 {expandedNodes.has(
              perm.id
            )
              ? 'rotate-90'
              : ''}"
            fill="none"
            stroke="currentColor"
            viewBox="0 0 24 24"
          >
            <path
              stroke-linecap="round"
              stroke-linejoin="round"
              stroke-width="2"
              d="M9 5l7 7-7 7"
            />
          </svg>
        </button>
      {:else}
        <div class="h-6 w-6 shrink-0">
          <!-- Dot for leaf nodes -->
          <div class="mx-auto mt-2 h-1.5 w-1.5 rounded-full bg-border"></div>
        </div>
      {/if}

      <!-- Content Card -->
      <div class="flex flex-1 items-center gap-3">
        <!-- Icon -->
        <div
          class="flex h-8 w-8 shrink-0 items-center justify-center rounded-lg border shadow-sm
					{getTypeIcon(perm.type) === 'menu'
            ? 'bg-blue-50 text-blue-600 border-blue-100 dark:bg-blue-950 dark:text-blue-400 dark:border-blue-900'
            : getTypeIcon(perm.type) === 'button'
              ? 'bg-purple-50 text-purple-600 border-purple-100 dark:bg-purple-950 dark:text-purple-400 dark:border-purple-900'
              : 'bg-emerald-50 text-emerald-600 border-emerald-100 dark:bg-emerald-950 dark:text-emerald-400 dark:border-emerald-900'}"
        >
          {#if getTypeIcon(perm.type) === "menu"}
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
                d="M4 6h16M4 12h16M4 18h16"
              />
            </svg>
          {:else if getTypeIcon(perm.type) === "button"}
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
                d="M15 15l-2 5L9 9l11 4-5 2zm0 0l5 5"
              />
            </svg>
          {:else}
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
                d="M10 20l4-16m4 4l4 4-4 4M6 16l-4-4 4-4"
              />
            </svg>
          {/if}
        </div>

        <!-- Info -->
        <div class="flex min-w-0 flex-1 flex-col justify-center">
          <div class="flex items-center gap-2">
            <span class="font-medium">{perm.name}</span>
            {#if perm.status === 0}
              <Badge variant="destructive" class="h-4 px-1 text-[10px]"
                >禁用</Badge
              >
            {/if}
          </div>
          <div class="flex items-center gap-2 text-xs text-muted-foreground">
            <span class="font-mono">{perm.code}</span>
            {#if perm.path}
              <span class="hidden text-muted-foreground/50 sm:inline">•</span>
              <span class="hidden truncate font-mono sm:inline"
                >{perm.path}</span
              >
            {/if}
          </div>
        </div>

        <!-- Tag -->
        <Badge variant="outline" class="shrink-0 bg-background/50 font-normal">
          {getTypeLabel(perm.type)}
        </Badge>
      </div>
    </div>

    <!-- Children Recursion -->
    {#if perm.children && perm.children.length > 0 && expandedNodes.has(perm.id)}
      <div class="children relative">
        <!-- Vertical line extension for children -->
        {#each perm.children as child (child.id)}
          {@render PermissionNode(child, level + 1)}
        {/each}
      </div>
    {/if}
  </div>
{/snippet}
