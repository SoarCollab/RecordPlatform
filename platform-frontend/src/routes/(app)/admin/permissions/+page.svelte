<script lang="ts">
  import { onMount } from "svelte";
  import { goto } from "$app/navigation";
  import { useNotifications } from "$stores/notifications.svelte";
  import { useAuth } from "$stores/auth.svelte";
  import {
    getPermissionTree,
    getRolePermissions,
    grantRolePermission,
    revokeRolePermission,
  } from "$api/endpoints/system";
  import type { SysPermission } from "$api/types";
  import { Button } from "$components/ui/button";
  import { Input } from "$components/ui/input";
  import { Badge } from "$components/ui/badge";
  import { Checkbox } from "$components/ui/checkbox";

  const notifications = useNotifications();
  const auth = useAuth();

  const ROLES = ["admin", "monitor", "user"] as const;
  type Role = (typeof ROLES)[number];
  const ROLE_LABELS: Record<Role, string> = {
    admin: "管理员",
    monitor: "监控员",
    user: "普通用户",
  };

  interface ModuleGroup {
    module: string;
    displayName: string;
    permissions: SysPermission[];
  }

  let loading = $state(true);
  let saving = $state(false);
  let searchQuery = $state("");
  let expandedModules = $state<Set<string>>(new Set());

  let moduleGroups = $state<ModuleGroup[]>([]);

  // 三个角色各自的当前/期望权限集
  let currentCodes = $state<Record<Role, Set<string>>>({
    admin: new Set(),
    monitor: new Set(),
    user: new Set(),
  });
  let desiredCodes = $state<Record<Role, Set<string>>>({
    admin: new Set(),
    monitor: new Set(),
    user: new Set(),
  });
  let pendingCodes = $state<Set<string>>(new Set());

  // 变更计数
  const changeCount = $derived(
    (() => {
      let count = 0;
      for (const role of ROLES) {
        const cur = currentCodes[role];
        const des = desiredCodes[role];
        des.forEach((c) => {
          if (!cur.has(c)) count++;
        });
        cur.forEach((c) => {
          if (!des.has(c)) count++;
        });
      }
      return count;
    })(),
  );

  // 搜索过滤
  const filteredGroups = $derived(
    (() => {
      const q = searchQuery.trim().toLowerCase();
      if (!q) return moduleGroups;
      return moduleGroups
        .map((g) => ({
          ...g,
          permissions: g.permissions.filter(
            (p) =>
              p.name.toLowerCase().includes(q) ||
              p.code.toLowerCase().includes(q) ||
              (p.description || "").toLowerCase().includes(q),
          ),
        }))
        .filter((g) => g.permissions.length > 0);
    })(),
  );

  const MODULE_NAMES: Record<string, string> = {
    announcement: "公告管理",
    file: "文件管理",
    friend: "好友管理",
    message: "消息管理",
    system: "系统管理",
    ticket: "工单管理",
    user: "用户管理",
    permission: "权限管理",
    role: "角色管理",
    audit: "审计日志",
    share: "分享管理",
    quota: "配额管理",
  };

  function getModuleDisplayName(mod: string): string {
    return MODULE_NAMES[mod] || mod;
  }

  onMount(() => {
    if (!auth.isAdmin) {
      notifications.error("权限不足", "仅管理员可访问此页面");
      goto("/dashboard");
      return;
    }
    loadAll();
  });

  async function loadAll() {
    loading = true;
    try {
      const [perms, adminPerms, monitorPerms, userPerms] = await Promise.all([
        getPermissionTree(),
        getRolePermissions("admin"),
        getRolePermissions("monitor"),
        getRolePermissions("user"),
      ]);

      // 按模块分组
      const groups = new Map<string, SysPermission[]>();
      for (const p of perms) {
        const mod =
          p.module || (p.code.includes(":") ? p.code.split(":")[0] : "other");
        if (!groups.has(mod)) groups.set(mod, []);
        groups.get(mod)!.push(p);
      }

      moduleGroups = Array.from(groups.entries())
        .map(([mod, permsArr]) => ({
          module: mod,
          displayName: getModuleDisplayName(mod),
          permissions: permsArr.sort((a, b) => a.code.localeCompare(b.code)),
        }))
        .sort((a, b) => a.displayName.localeCompare(b.displayName));

      // 默认全部展开
      expandedModules = new Set(moduleGroups.map((g) => g.module));

      // 设置角色权限
      currentCodes = {
        admin: new Set(adminPerms),
        monitor: new Set(monitorPerms),
        user: new Set(userPerms),
      };
      desiredCodes = {
        admin: new Set(adminPerms),
        monitor: new Set(monitorPerms),
        user: new Set(userPerms),
      };
    } catch (err) {
      notifications.error(
        "加载失败",
        err instanceof Error ? err.message : "请稍后重试",
      );
    } finally {
      loading = false;
    }
  }

  function toggleExpand(mod: string) {
    const next = new Set(expandedModules);
    if (next.has(mod)) next.delete(mod);
    else next.add(mod);
    expandedModules = next;
  }

  function togglePermission(role: Role, code: string) {
    if (saving) return;
    const next = new Set(desiredCodes[role]);
    if (next.has(code)) next.delete(code);
    else next.add(code);
    desiredCodes = { ...desiredCodes, [role]: next };
  }

  function isChanged(role: Role, code: string): boolean {
    return currentCodes[role].has(code) !== desiredCodes[role].has(code);
  }

  /** 模块级全选/取消 */
  function toggleModuleRole(role: Role, group: ModuleGroup) {
    if (saving) return;
    const activeCodes = group.permissions
      .filter((p) => p.status === 1)
      .map((p) => p.code);
    const allGranted = activeCodes.every((c) => desiredCodes[role].has(c));

    const next = new Set(desiredCodes[role]);
    if (allGranted) {
      activeCodes.forEach((c) => next.delete(c));
    } else {
      activeCodes.forEach((c) => next.add(c));
    }
    desiredCodes = { ...desiredCodes, [role]: next };
  }

  /** 模块下某角色的选中状态 */
  function getModuleRoleState(
    role: Role,
    group: ModuleGroup,
  ): { checked: boolean; indeterminate: boolean } {
    const activeCodes = group.permissions
      .filter((p) => p.status === 1)
      .map((p) => p.code);
    if (activeCodes.length === 0)
      return { checked: false, indeterminate: false };
    const grantedCount = activeCodes.filter((c) =>
      desiredCodes[role].has(c),
    ).length;
    return {
      checked: grantedCount === activeCodes.length,
      indeterminate: grantedCount > 0 && grantedCount < activeCodes.length,
    };
  }

  function resetChanges() {
    desiredCodes = {
      admin: new Set(currentCodes.admin),
      monitor: new Set(currentCodes.monitor),
      user: new Set(currentCodes.user),
    };
  }

  async function saveChanges() {
    if (saving || changeCount === 0) return;
    saving = true;
    const errors: string[] = [];

    try {
      for (const role of ROLES) {
        const toGrant: string[] = [];
        const toRevoke: string[] = [];

        desiredCodes[role].forEach((c) => {
          if (!currentCodes[role].has(c)) toGrant.push(c);
        });
        currentCodes[role].forEach((c) => {
          if (!desiredCodes[role].has(c)) toRevoke.push(c);
        });

        for (const code of toGrant) {
          pendingCodes = new Set([...pendingCodes, code]);
          try {
            await grantRolePermission(role, { permissionCode: code });
          } catch {
            errors.push(`${ROLE_LABELS[role]}:${code}`);
          } finally {
            const next = new Set(pendingCodes);
            next.delete(code);
            pendingCodes = next;
          }
        }

        for (const code of toRevoke) {
          pendingCodes = new Set([...pendingCodes, code]);
          try {
            await revokeRolePermission(role, code);
          } catch {
            errors.push(`${ROLE_LABELS[role]}:${code}`);
          } finally {
            const next = new Set(pendingCodes);
            next.delete(code);
            pendingCodes = next;
          }
        }
      }
    } finally {
      saving = false;
      pendingCodes = new Set();
    }

    if (errors.length > 0) {
      notifications.warning("部分变更未生效", `失败 ${errors.length} 项`);
    } else {
      notifications.success("保存成功");
    }

    // 重新加载当前状态
    try {
      const [adminPerms, monitorPerms, userPerms] = await Promise.all([
        getRolePermissions("admin"),
        getRolePermissions("monitor"),
        getRolePermissions("user"),
      ]);
      currentCodes = {
        admin: new Set(adminPerms),
        monitor: new Set(monitorPerms),
        user: new Set(userPerms),
      };
      desiredCodes = {
        admin: new Set(adminPerms),
        monitor: new Set(monitorPerms),
        user: new Set(userPerms),
      };
    } catch (err) {
      console.error('reload permissions failed:', err);
    }
  }
</script>

<svelte:head>
  <title>权限管理 - 存证平台</title>
</svelte:head>

<div class="space-y-6">
  <!-- 标题栏 -->
  <div class="flex items-center justify-between">
    <div>
      <h1 class="text-2xl font-bold">权限管理</h1>
      <p class="text-muted-foreground">管理各角色的接口与功能权限</p>
    </div>
    <div class="flex items-center gap-3">
      {#if changeCount > 0}
        <span class="text-muted-foreground text-sm">
          <span class="text-primary font-medium">{changeCount}</span> 项未保存
        </span>
      {/if}
      <Button
        variant="outline"
        onclick={resetChanges}
        disabled={saving || changeCount === 0}
      >
        撤销更改
      </Button>
      <Button onclick={saveChanges} disabled={saving || changeCount === 0}>
        {#if saving}
          <div
            class="mr-2 h-4 w-4 animate-spin rounded-full border-2 border-current border-t-transparent"
          ></div>
        {/if}
        保存更改
      </Button>
    </div>
  </div>

  <!-- 搜索栏 -->
  <div class="flex items-center gap-3">
    <Input
      placeholder="搜索权限名称或编码..."
      bind:value={searchQuery}
      class="max-w-sm"
    />
    {#if searchQuery}
      <Button variant="outline" size="sm" onclick={() => (searchQuery = "")}
        >清除</Button
      >
    {/if}
    <div class="flex-1"></div>
    <Button variant="outline" onclick={loadAll} disabled={loading}>刷新</Button>
  </div>

  <!-- 权限矩阵 -->
  {#if loading}
    <div class="flex items-center justify-center p-16">
      <div
        class="border-primary h-8 w-8 animate-spin rounded-full border-4 border-t-transparent"
      ></div>
    </div>
  {:else if filteredGroups.length === 0}
    <div class="text-muted-foreground rounded-lg border p-12 text-center">
      {searchQuery ? "没有匹配的权限" : "暂无权限数据"}
    </div>
  {:else}
    <div class="overflow-hidden rounded-lg border">
      <!-- 表头 -->
      <div
        class="bg-muted/30 grid grid-cols-[1fr_100px_100px_100px] border-b px-4 py-3 text-sm font-medium"
      >
        <div>权限</div>
        {#each ROLES as role}
          <div class="text-center">{ROLE_LABELS[role]}</div>
        {/each}
      </div>

      <!-- 模块分组 -->
      {#each filteredGroups as group (group.module)}
        <!-- 模块行 -->
        <div
          class="bg-muted/10 grid grid-cols-[1fr_100px_100px_100px] border-b px-4 py-2.5"
        >
          <button
            class="flex items-center gap-2 text-left text-sm font-medium"
            onclick={() => toggleExpand(group.module)}
          >
            <svg
              class="text-muted-foreground h-4 w-4 shrink-0 transition-transform duration-200 {expandedModules.has(
                group.module,
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
            <span>{group.displayName}</span>
            <span class="text-muted-foreground text-xs"
              >({group.permissions.length})</span
            >
          </button>
          {#each ROLES as role}
            {@const state = getModuleRoleState(role, group)}
            <div class="flex items-center justify-center">
              <Checkbox
                checked={state.checked}
                indeterminate={state.indeterminate}
                disabled={saving}
                onchange={() => toggleModuleRole(role, group)}
              />
            </div>
          {/each}
        </div>

        <!-- 权限行 -->
        {#if expandedModules.has(group.module)}
          {#each group.permissions as perm (perm.id)}
            {@const disabled = perm.status !== 1}
            <div
              class="grid grid-cols-[1fr_100px_100px_100px] border-b px-4 py-2.5 transition-colors last:border-b-0
                {disabled ? 'opacity-50' : 'hover:bg-muted/30'}"
            >
              <div class="flex items-center gap-3 pl-6">
                <div class="min-w-0">
                  <div class="flex items-center gap-2">
                    <span class="text-sm">{perm.name}</span>
                    {#if disabled}
                      <Badge variant="outline" class="text-[10px]">禁用</Badge>
                    {/if}
                    {#if ROLES.some((r) => isChanged(r, perm.code))}
                      <span
                        class="bg-primary inline-block h-1.5 w-1.5 rounded-full"
                        title="未保存"
                      ></span>
                    {/if}
                  </div>
                  <p class="text-muted-foreground truncate font-mono text-xs">
                    {perm.code}
                  </p>
                </div>
              </div>
              {#each ROLES as role}
                <div class="flex items-center justify-center">
                  <Checkbox
                    checked={desiredCodes[role].has(perm.code)}
                    disabled={disabled || saving || pendingCodes.has(perm.code)}
                    onchange={() => togglePermission(role, perm.code)}
                  />
                </div>
              {/each}
            </div>
          {/each}
        {/if}
      {/each}
    </div>
  {/if}
</div>
