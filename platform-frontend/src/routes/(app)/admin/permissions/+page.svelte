<script lang="ts">
  import { onMount } from "svelte";
  import { goto } from "$app/navigation";
  import { useNotifications } from "$stores/notifications.svelte";
  import { useAuth } from "$stores/auth.svelte";
  import {
    getPermissionTree,
    listPermissionModules,
    listPermissions,
    createPermission,
    updatePermission,
    deletePermission,
    getRolePermissions,
    grantRolePermission,
    revokeRolePermission,
  } from "$api/endpoints/system";
  import { PermissionType, type SysPermission } from "$api/types";
  import { Button } from "$lib/components/ui/button";
  import { Input } from "$lib/components/ui/input";
  import { Badge } from "$lib/components/ui/badge";
  import * as Table from "$lib/components/ui/table";
  import * as Dialog from "$lib/components/ui/dialog";
  import * as Card from "$lib/components/ui/card";
  import * as Tabs from "$lib/components/ui/tabs";

  const notifications = useNotifications();
  const auth = useAuth();

  let activeTab = $state("tree");

  let tree = $state<SysPermission[]>([]);
  let treeLoading = $state(true);
  let searchQuery = $state("");
  let expandedNodes = $state<Set<string>>(new Set());

  let modules = $state<string[]>([]);

  let listLoading = $state(false);
  let listRecords = $state<SysPermission[]>([]);
  let listTotal = $state(0);
  let listPage = $state(1);
  let listPageSize = $state(20);
  let listModule = $state("");
  let listSearch = $state("");

  let createDialogOpen = $state(false);
  let creating = $state(false);
  let createCode = $state("");
  let createName = $state("");
  let createModule = $state("");
  let createAction = $state("");
  let createDescription = $state("");

  let editDialogOpen = $state(false);
  let editing = $state(false);
  let editingPermission = $state<SysPermission | null>(null);
  let editName = $state("");
  let editDescription = $state("");
  let editStatus = $state<number>(1);

  let deleteDialogOpen = $state(false);
  let deleting = $state(false);
  let deletingPermission = $state<SysPermission | null>(null);

  let roleTab = $state("admin");
  let roleCodes = $state<Set<string>>(new Set());
  let roleDesiredCodes = $state<Set<string>>(new Set());
  let roleLoading = $state(false);
  let roleSaving = $state(false);
  let showGrantedOnly = $state(false);
  let roleModule = $state("");
  let roleSearch = $state("");
  let rolePermLoading = $state(false);
  let rolePermRecords = $state<SysPermission[]>([]);
  let rolePermTotal = $state(0);
  let rolePermPage = $state(1);
  let rolePermPageSize = $state(50);
  let rolePendingCodes = $state<Set<string>>(new Set());

  const filteredTree = $derived(
    searchQuery ? filterPermissions(tree, searchQuery.toLowerCase()) : tree,
  );

  const filteredListRecords = $derived(
    (() => {
      const q = listSearch.trim().toLowerCase();
      if (!q) return listRecords;
      return listRecords.filter(
        (p) =>
          p.code.toLowerCase().includes(q) ||
          p.name.toLowerCase().includes(q) ||
          (p.description || "").toLowerCase().includes(q),
      );
    })(),
  );

  const filteredRolePermRecords = $derived(
    (() => {
      const q = roleSearch.trim().toLowerCase();
      return rolePermRecords.filter((p) => {
        const matchesQuery =
          !q ||
          p.code.toLowerCase().includes(q) ||
          p.name.toLowerCase().includes(q) ||
          (p.description || "").toLowerCase().includes(q);

        const matchesGranted = !showGrantedOnly || roleDesiredCodes.has(p.code);

        return matchesQuery && matchesGranted;
      });
    })(),
  );

  const roleChangeCount = $derived(
    (() => {
      let count = 0;
      roleDesiredCodes.forEach((code) => {
        if (!roleCodes.has(code)) count++;
      });
      roleCodes.forEach((code) => {
        if (!roleDesiredCodes.has(code)) count++;
      });
      return count;
    })(),
  );

  onMount(() => {
    if (!auth.isAdmin) {
      notifications.error("权限不足", "仅管理员可访问此页面");
      goto("/dashboard");
      return;
    }
    reloadAll();
  });

  async function reloadAll() {
    await Promise.allSettled([loadTree(), loadModules(), loadPermissionList()]);
  }

  async function loadModules() {
    try {
      modules = await listPermissionModules();
    } catch {
      modules = [];
    }
  }

  async function loadTree() {
    treeLoading = true;
    try {
      const raw = await getPermissionTree();
      tree = buildPermissionTree(raw);
      const nextExpanded = new Set<string>();
      tree.forEach((p) => nextExpanded.add(p.id));
      expandedNodes = nextExpanded;
    } catch (err) {
      notifications.error(
        "加载失败",
        err instanceof Error ? err.message : "请稍后重试",
      );
    } finally {
      treeLoading = false;
    }
  }

  async function loadPermissionList() {
    listLoading = true;
    try {
      const result = await listPermissions({
        module: listModule || undefined,
        pageNum: listPage,
        pageSize: listPageSize,
      });
      listRecords = result.records;
      listTotal = result.total;
    } catch (err) {
      notifications.error(
        "加载失败",
        err instanceof Error ? err.message : "请稍后重试",
      );
    } finally {
      listLoading = false;
    }
  }

  function handleListSearch() {
    listPage = 1;
    loadPermissionList();
  }

  function resetListFilters() {
    listModule = "";
    listSearch = "";
    listPage = 1;
    loadPermissionList();
  }

  function buildPermissionTree(flatPerms: SysPermission[]): SysPermission[] {
    const modulesMap = new Map<string, SysPermission[]>();

    flatPerms.forEach((p) => {
      const action = p.action || (p.code.includes(":") ? p.code.split(":")[1] : "");
      p.type = inferPermissionType(action);

      const moduleName =
        p.module || (p.code.includes(":") ? p.code.split(":")[0] : "other");

      if (!modulesMap.has(moduleName)) {
        modulesMap.set(moduleName, []);
      }
      modulesMap.get(moduleName)!.push(p);
    });

    const treeNodes: SysPermission[] = [];
    let sortCounter = 0;

    modulesMap.forEach((children, moduleName) => {
      treeNodes.push({
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

    return treeNodes.sort((a, b) => a.sort - b.sort);
  }

  function inferPermissionType(action: string): PermissionType {
    const lowerAction = action.toLowerCase();
    if (
      ["read", "view", "list", "query", "get", "monitor"].some((k) =>
        lowerAction.includes(k),
      )
    ) {
      return PermissionType.API;
    }
    if (["admin", "manage"].some((k) => lowerAction.includes(k))) {
      return PermissionType.BUTTON;
    }
    return PermissionType.BUTTON;
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
    return map[module] || module;
  }

  function filterPermissions(perms: SysPermission[], query: string): SysPermission[] {
    const mapped = perms
      .map((perm) => {
        const children = perm.children
          ? filterPermissions(perm.children, query)
          : undefined;

        const matches =
          perm.name.toLowerCase().includes(query) ||
          perm.code.toLowerCase().includes(query) ||
          (perm.module || "").toLowerCase().includes(query) ||
          (perm.action || "").toLowerCase().includes(query);

        if (matches || (children && children.length > 0)) {
          return { ...perm, children };
        }
        return null;
      })
      .filter((x) => x !== null);

    return mapped as SysPermission[];
  }

  function toggleExpand(id: string) {
    const next = new Set(expandedNodes);
    if (next.has(id)) {
      next.delete(id);
    } else {
      next.add(id);
    }
    expandedNodes = next;
  }

  function getTypeLabel(type: PermissionType): string {
    if (type === PermissionType.MENU) return "菜单";
    if (type === PermissionType.API) return "API";
    return "按钮";
  }

  function openCreateDialog() {
    createCode = "";
    createName = "";
    createModule = listModule || "";
    createAction = "";
    createDescription = "";
    createDialogOpen = true;
  }

  async function submitCreate() {
    const code = createCode.trim();
    const name = createName.trim();
    const module = createModule.trim();
    const action = createAction.trim();

    if (!code || !name || !module || !action) {
      notifications.warning("请完整填写必填项");
      return;
    }

    creating = true;
    try {
      await createPermission({
        code,
        name,
        module,
        action,
        description: createDescription.trim() || undefined,
      });
      notifications.success("创建成功");
      createDialogOpen = false;
      await Promise.allSettled([loadPermissionList(), loadModules(), loadTree()]);
    } catch (err) {
      notifications.error(
        "创建失败",
        err instanceof Error ? err.message : "请稍后重试",
      );
    } finally {
      creating = false;
    }
  }

  function openEditDialog(perm: SysPermission) {
    editingPermission = perm;
    editName = perm.name;
    editDescription = perm.description || "";
    editStatus = perm.status ?? 1;
    editDialogOpen = true;
  }

  async function submitEdit() {
    if (!editingPermission) return;
    editing = true;
    try {
      await updatePermission(String(editingPermission.id), {
        name: editName.trim() || undefined,
        description: editDescription.trim() || undefined,
        status: editStatus,
      });
      notifications.success("更新成功");
      editDialogOpen = false;
      editingPermission = null;
      await Promise.allSettled([loadPermissionList(), loadTree()]);
    } catch (err) {
      notifications.error(
        "更新失败",
        err instanceof Error ? err.message : "请稍后重试",
      );
    } finally {
      editing = false;
    }
  }

  function openDeleteDialog(perm: SysPermission) {
    deletingPermission = perm;
    deleteDialogOpen = true;
  }

  async function confirmDelete() {
    if (!deletingPermission) return;
    deleting = true;
    try {
      await deletePermission(String(deletingPermission.id));
      notifications.success("删除成功");
      deleteDialogOpen = false;
      deletingPermission = null;
      await Promise.allSettled([loadPermissionList(), loadModules(), loadTree()]);
    } catch (err) {
      notifications.error(
        "删除失败",
        err instanceof Error ? err.message : "请稍后重试",
      );
    } finally {
      deleting = false;
    }
  }

  async function loadRolePermissionsFor(role: string) {
    roleLoading = true;
    try {
      const list = await getRolePermissions(role);
      const set = new Set<string>(list);
      roleCodes = set;
      roleDesiredCodes = new Set(set);
    } catch (err) {
      notifications.error(
        "加载失败",
        err instanceof Error ? err.message : "请稍后重试",
      );
      roleCodes = new Set();
      roleDesiredCodes = new Set();
    } finally {
      roleLoading = false;
    }
  }

  async function loadRolePermissionList() {
    rolePermLoading = true;
    try {
      const result = await listPermissions({
        module: roleModule || undefined,
        pageNum: rolePermPage,
        pageSize: rolePermPageSize,
      });
      rolePermRecords = result.records;
      rolePermTotal = result.total;
    } catch (err) {
      notifications.error(
        "加载失败",
        err instanceof Error ? err.message : "请稍后重试",
      );
    } finally {
      rolePermLoading = false;
    }
  }

  function handleRoleTabChange(role: string) {
    roleTab = role;
    rolePermPage = 1;
    loadRolePermissionsFor(role);
    loadRolePermissionList();
  }

  function resetRoleFilters() {
    roleModule = "";
    roleSearch = "";
    rolePermPage = 1;
    loadRolePermissionList();
  }

  function setRoleDesired(code: string, checked: boolean) {
    const next = new Set(roleDesiredCodes);
    if (checked) {
      next.add(code);
    } else {
      next.delete(code);
    }
    roleDesiredCodes = next;
  }

  function resetRoleDesired() {
    roleDesiredCodes = new Set(roleCodes);
  }

  function selectAllVisible() {
    const next = new Set(roleDesiredCodes);
    filteredRolePermRecords.forEach((p) => {
      if (p.status === 1) next.add(p.code);
    });
    roleDesiredCodes = next;
  }

  function clearAllVisible() {
    const next = new Set(roleDesiredCodes);
    filteredRolePermRecords.forEach((p) => {
      next.delete(p.code);
    });
    roleDesiredCodes = next;
  }

  async function saveRoleChanges() {
    if (roleSaving) return;

    const toGrant: string[] = [];
    roleDesiredCodes.forEach((code) => {
      if (!roleCodes.has(code)) toGrant.push(code);
    });

    const toRevoke: string[] = [];
    roleCodes.forEach((code) => {
      if (!roleDesiredCodes.has(code)) toRevoke.push(code);
    });

    if (toGrant.length === 0 && toRevoke.length === 0) {
      notifications.info("没有需要保存的变更");
      return;
    }

    roleSaving = true;
    const errors: string[] = [];
    const pending = new Set<string>();

    try {
      for (const code of toGrant) {
        pending.add(code);
        rolePendingCodes = new Set(pending);
        try {
          await grantRolePermission(roleTab, { permissionCode: code });
        } catch (err) {
          errors.push(code);
          notifications.error(
            "授权失败",
            err instanceof Error ? err.message : "请稍后重试",
          );
        } finally {
          pending.delete(code);
          rolePendingCodes = new Set(pending);
        }
      }

      for (const code of toRevoke) {
        pending.add(code);
        rolePendingCodes = new Set(pending);
        try {
          await revokeRolePermission(roleTab, code);
        } catch (err) {
          errors.push(code);
          notifications.error(
            "撤销失败",
            err instanceof Error ? err.message : "请稍后重试",
          );
        } finally {
          pending.delete(code);
          rolePendingCodes = new Set(pending);
        }
      }
    } finally {
      roleSaving = false;
      rolePendingCodes = new Set();
      await loadRolePermissionsFor(roleTab);
    }

    if (errors.length > 0) {
      notifications.warning("部分变更未生效", `失败项：${errors.length}`);
    } else {
      notifications.success("保存成功");
    }
  }

  function handleTabChange(value: string) {
    activeTab = value;
    if (value === "list") loadPermissionList();
    if (value === "roles") {
      loadRolePermissionsFor(roleTab);
      loadRolePermissionList();
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
      <p class="text-muted-foreground">权限定义维护与角色授权</p>
    </div>
    <div class="flex gap-2">
      <Button variant="outline" onclick={reloadAll}>刷新</Button>
      <Button onclick={openCreateDialog}>创建权限</Button>
    </div>
  </div>

  <Tabs.Root value={activeTab} onValueChange={handleTabChange}>
    <Tabs.List>
      <Tabs.Trigger value="tree">权限树</Tabs.Trigger>
      <Tabs.Trigger value="list">权限列表</Tabs.Trigger>
      <Tabs.Trigger value="roles">角色权限</Tabs.Trigger>
    </Tabs.List>

    <Tabs.Content value="tree" class="mt-4">
      <Card.Root>
        <Card.Header class="pb-4">
          <div class="flex items-center justify-between gap-3">
            <Input
              placeholder="搜索权限名称或编码..."
              bind:value={searchQuery}
            />
            <Button
              variant="outline"
              onclick={() => {
                searchQuery = "";
              }}
              disabled={!searchQuery}
            >
              清除
            </Button>
          </div>
        </Card.Header>
        <Card.Content>
          {#if treeLoading}
            <div class="flex items-center justify-center p-10">
              <div class="h-8 w-8 animate-spin rounded-full border-4 border-primary border-t-transparent"></div>
            </div>
          {:else if filteredTree.length === 0}
            <div class="p-12 text-center text-muted-foreground">暂无权限数据</div>
          {:else}
            <div class="space-y-1">
              {#each filteredTree as perm (perm.id)}
                {@render PermissionNode(perm, 0)}
              {/each}
            </div>
          {/if}
        </Card.Content>
      </Card.Root>
    </Tabs.Content>

    <Tabs.Content value="list" class="mt-4">
      <Card.Root>
        <Card.Header class="pb-4">
          <div class="grid gap-4 md:grid-cols-2 lg:grid-cols-4">
            <select
              bind:value={listModule}
              class="h-9 w-full rounded-md border bg-background px-3 text-sm focus:border-primary focus:outline-none"
            >
              <option value="">全部模块</option>
              {#each modules as m (m)}
                <option value={m}>{m}</option>
              {/each}
            </select>
            <Input
              placeholder="搜索 code/name/description"
              bind:value={listSearch}
              onkeydown={(e) => e.key === "Enter" && handleListSearch()}
            />
            <div class="flex gap-2">
              <Button onclick={handleListSearch} class="flex-1">搜索</Button>
              <Button variant="secondary" onclick={resetListFilters}>重置</Button>
            </div>
            <div class="flex justify-end">
              <Button variant="outline" onclick={loadPermissionList} disabled={listLoading}>
                刷新
              </Button>
            </div>
          </div>
        </Card.Header>
        <Card.Content>
          {#if listLoading}
            <div class="flex items-center justify-center p-10">
              <div class="h-8 w-8 animate-spin rounded-full border-4 border-primary border-t-transparent"></div>
            </div>
          {:else}
            <Table.Root>
              <Table.Header>
                <Table.Row>
                  <Table.Head>Code</Table.Head>
                  <Table.Head>Name</Table.Head>
                  <Table.Head>Module</Table.Head>
                  <Table.Head>Action</Table.Head>
                  <Table.Head>Status</Table.Head>
                  <Table.Head class="text-right">操作</Table.Head>
                </Table.Row>
              </Table.Header>
              <Table.Body>
                {#each filteredListRecords as perm (perm.id)}
                  <Table.Row>
                    <Table.Cell class="font-mono text-xs">{perm.code}</Table.Cell>
                    <Table.Cell>{perm.name}</Table.Cell>
                    <Table.Cell>{perm.module || "-"}</Table.Cell>
                    <Table.Cell>{perm.action || "-"}</Table.Cell>
                    <Table.Cell>
                      <Badge variant={perm.status === 1 ? "default" : "destructive"}>
                        {perm.status === 1 ? "启用" : "禁用"}
                      </Badge>
                    </Table.Cell>
                    <Table.Cell class="text-right">
                      <div class="flex justify-end gap-2">
                        <Button size="sm" variant="outline" onclick={() => openEditDialog(perm)}>
                          编辑
                        </Button>
                        <Button size="sm" variant="destructive" onclick={() => openDeleteDialog(perm)}>
                          删除
                        </Button>
                      </div>
                    </Table.Cell>
                  </Table.Row>
                {/each}
              </Table.Body>
            </Table.Root>

            {#if listTotal > listPageSize}
              <div class="mt-4 flex items-center justify-between">
                <p class="text-sm text-muted-foreground">
                  共 {listTotal} 条，第 {listPage} / {Math.max(1, Math.ceil(listTotal / listPageSize))} 页
                </p>
                <div class="flex gap-2">
                  <Button
                    variant="outline"
                    disabled={listPage <= 1}
                    onclick={() => {
                      listPage = Math.max(1, listPage - 1);
                      loadPermissionList();
                    }}
                  >
                    上一页
                  </Button>
                  <Button
                    variant="outline"
                    disabled={listPage >= Math.ceil(listTotal / listPageSize)}
                    onclick={() => {
                      listPage = listPage + 1;
                      loadPermissionList();
                    }}
                  >
                    下一页
                  </Button>
                </div>
              </div>
            {/if}

            {#if filteredListRecords.length === 0}
              <div class="p-6 text-sm text-muted-foreground">暂无数据</div>
            {/if}
          {/if}
        </Card.Content>
      </Card.Root>
    </Tabs.Content>

    <Tabs.Content value="roles" class="mt-4">
      <Card.Root>
        <Card.Header class="pb-4">
          <div class="flex flex-col gap-4">
            <div class="flex flex-wrap items-center justify-between gap-3">
              <div class="flex items-center gap-3">
                <Card.Title>角色授权</Card.Title>
                <span class="text-sm text-muted-foreground">待保存 {roleChangeCount} 项</span>
              </div>
              <div class="flex flex-wrap gap-2">
                <Button
                  variant={roleTab === "admin" ? "default" : "outline"}
                  onclick={() => handleRoleTabChange("admin")}
                >
                  管理员
                </Button>
                <Button
                  variant={roleTab === "monitor" ? "default" : "outline"}
                  onclick={() => handleRoleTabChange("monitor")}
                >
                  监控员
                </Button>
                <Button
                  variant={roleTab === "user" ? "default" : "outline"}
                  onclick={() => handleRoleTabChange("user")}
                >
                  普通用户
                </Button>
              </div>
            </div>

            <div class="flex flex-wrap items-center gap-2">
              <label class="flex items-center gap-2 text-sm cursor-pointer">
                <input
                  type="checkbox"
                  bind:checked={showGrantedOnly}
                  class="h-4 w-4 rounded border accent-primary"
                  disabled={roleSaving}
                />
                <span>仅看已授权</span>
              </label>
              <Button variant="outline" onclick={selectAllVisible} disabled={roleSaving}>
                全选当前列表
              </Button>
              <Button variant="outline" onclick={clearAllVisible} disabled={roleSaving}>
                取消当前列表
              </Button>
              <Button
                variant="secondary"
                onclick={resetRoleDesired}
                disabled={roleSaving || roleChangeCount === 0}
              >
                撤销更改
              </Button>
              <Button onclick={saveRoleChanges} disabled={roleSaving || roleChangeCount === 0}>
                {#if roleSaving}
                  <div class="mr-2 h-4 w-4 animate-spin rounded-full border-2 border-current border-t-transparent"></div>
                {/if}
                保存更改
              </Button>
            </div>

            <div class="grid gap-4 md:grid-cols-2 lg:grid-cols-4">
              <select
                bind:value={roleModule}
                class="h-9 w-full rounded-md border bg-background px-3 text-sm focus:border-primary focus:outline-none"
              >
                <option value="">全部模块</option>
                {#each modules as m (m)}
                  <option value={m}>{m}</option>
                {/each}
              </select>
              <Input
                placeholder="搜索 code/name/description"
                bind:value={roleSearch}
                onkeydown={(e) => e.key === "Enter" && loadRolePermissionList()}
              />
              <div class="flex gap-2">
                <Button
                  onclick={() => {
                    rolePermPage = 1;
                    loadRolePermissionList();
                  }}
                  class="flex-1"
                >
                  搜索
                </Button>
                <Button variant="secondary" onclick={resetRoleFilters}>重置</Button>
              </div>
              <div class="flex justify-end">
                <Button variant="outline" onclick={() => {
                  loadRolePermissionsFor(roleTab);
                  loadRolePermissionList();
                }} disabled={roleLoading || rolePermLoading}>
                  刷新
                </Button>
              </div>
            </div>
          </div>
        </Card.Header>
        <Card.Content>
          {#if roleLoading || rolePermLoading}
            <div class="flex items-center justify-center p-10">
              <div class="h-8 w-8 animate-spin rounded-full border-4 border-primary border-t-transparent"></div>
            </div>
          {:else}
            <Table.Root>
              <Table.Header>
                <Table.Row>
                  <Table.Head class="w-[80px]">授权</Table.Head>
                  <Table.Head>Code</Table.Head>
                  <Table.Head>Name</Table.Head>
                  <Table.Head>Module</Table.Head>
                  <Table.Head>Action</Table.Head>
                  <Table.Head>Status</Table.Head>
                </Table.Row>
              </Table.Header>
              <Table.Body>
                {#each filteredRolePermRecords as perm (perm.id)}
                  {@const checked = roleDesiredCodes.has(perm.code)}
                  {@const changed = checked !== roleCodes.has(perm.code)}
                  {@const disabled = perm.status !== 1}
                  {@const pending = rolePendingCodes.has(perm.code)}
                  <Table.Row>
                    <Table.Cell>
                      <input
                        type="checkbox"
                        checked={checked}
                        disabled={disabled || pending || roleSaving}
                        class="h-4 w-4 rounded border accent-primary"
                        onchange={(e) =>
                          setRoleDesired(perm.code, (e.currentTarget as HTMLInputElement).checked)
                        }
                      />
                    </Table.Cell>
                    <Table.Cell class="font-mono text-xs">
                      <div class="flex items-center gap-2">
                        <span>{perm.code}</span>
                        {#if changed}
                          <span class="rounded bg-muted px-2 py-0.5 text-[10px] text-muted-foreground">未保存</span>
                        {/if}
                      </div>
                    </Table.Cell>
                    <Table.Cell>{perm.name}</Table.Cell>
                    <Table.Cell>{perm.module || "-"}</Table.Cell>
                    <Table.Cell>{perm.action || "-"}</Table.Cell>
                    <Table.Cell>
                      <Badge variant={perm.status === 1 ? "default" : "destructive"}>
                        {perm.status === 1 ? "启用" : "禁用"}
                      </Badge>
                    </Table.Cell>
                  </Table.Row>
                {/each}
              </Table.Body>
            </Table.Root>

            {#if rolePermTotal > rolePermPageSize}
              <div class="mt-4 flex items-center justify-between">
                <p class="text-sm text-muted-foreground">
                  共 {rolePermTotal} 条，第 {rolePermPage} / {Math.max(1, Math.ceil(rolePermTotal / rolePermPageSize))} 页
                </p>
                <div class="flex gap-2">
                  <Button
                    variant="outline"
                    disabled={rolePermPage <= 1}
                    onclick={() => {
                      rolePermPage = Math.max(1, rolePermPage - 1);
                      loadRolePermissionList();
                    }}
                  >
                    上一页
                  </Button>
                  <Button
                    variant="outline"
                    disabled={rolePermPage >= Math.ceil(rolePermTotal / rolePermPageSize)}
                    onclick={() => {
                      rolePermPage = rolePermPage + 1;
                      loadRolePermissionList();
                    }}
                  >
                    下一页
                  </Button>
                </div>
              </div>
            {/if}

            {#if filteredRolePermRecords.length === 0}
              <div class="p-6 text-sm text-muted-foreground">暂无数据</div>
            {/if}
          {/if}
        </Card.Content>
      </Card.Root>
    </Tabs.Content>
  </Tabs.Root>
</div>

<Dialog.Root bind:open={createDialogOpen}>
  <Dialog.Content class="max-w-lg">
    <Dialog.Header>
      <Dialog.Title>创建权限</Dialog.Title>
    </Dialog.Header>
    <div class="space-y-4">
      <Input placeholder="Code (module:action)" bind:value={createCode} />
      <Input placeholder="Name" bind:value={createName} />
      <Input placeholder="Module" bind:value={createModule} />
      <Input placeholder="Action" bind:value={createAction} />
      <Input placeholder="Description" bind:value={createDescription} />
      <div class="flex justify-end gap-2">
        <Button variant="secondary" onclick={() => (createDialogOpen = false)}>
          取消
        </Button>
        <Button onclick={submitCreate} disabled={creating}>
          {creating ? "创建中..." : "创建"}
        </Button>
      </div>
    </div>
  </Dialog.Content>
</Dialog.Root>

<Dialog.Root bind:open={editDialogOpen}>
  <Dialog.Content class="max-w-lg">
    <Dialog.Header>
      <Dialog.Title>编辑权限</Dialog.Title>
      {#if editingPermission}
        <Dialog.Description class="font-mono text-xs">
          {editingPermission.code}
        </Dialog.Description>
      {/if}
    </Dialog.Header>
    <div class="space-y-4">
      <Input placeholder="Name" bind:value={editName} />
      <Input placeholder="Description" bind:value={editDescription} />
      <select
        bind:value={editStatus}
        class="h-9 w-full rounded-md border bg-background px-3 text-sm focus:border-primary focus:outline-none"
      >
        <option value={1}>启用</option>
        <option value={0}>禁用</option>
      </select>
      <div class="flex justify-end gap-2">
        <Button variant="secondary" onclick={() => (editDialogOpen = false)}>
          取消
        </Button>
        <Button onclick={submitEdit} disabled={editing}>
          {editing ? "保存中..." : "保存"}
        </Button>
      </div>
    </div>
  </Dialog.Content>
</Dialog.Root>

<Dialog.Root bind:open={deleteDialogOpen}>
  <Dialog.Content class="max-w-md">
    <Dialog.Header>
      <Dialog.Title>删除权限</Dialog.Title>
    </Dialog.Header>
    <div class="space-y-4">
      <p class="text-sm text-muted-foreground">
        {#if deletingPermission}
          确认删除权限 {deletingPermission.code}？
        {/if}
      </p>
      <div class="flex justify-end gap-2">
        <Button variant="secondary" onclick={() => (deleteDialogOpen = false)}>
          取消
        </Button>
        <Button variant="destructive" onclick={confirmDelete} disabled={deleting}>
          {deleting ? "删除中..." : "删除"}
        </Button>
      </div>
    </div>
  </Dialog.Content>
</Dialog.Root>

{#snippet PermissionNode(perm: SysPermission, level: number)}
  <div class="permission-node">
    <div
      class="flex items-center gap-3 rounded-lg border border-transparent px-3 py-2 transition-colors hover:bg-muted/50 hover:border-border/50"
      style="margin-left: {level * 24}px"
    >
      {#if perm.children && perm.children.length > 0}
        <button
          class="flex h-6 w-6 shrink-0 items-center justify-center rounded-md border bg-background shadow-sm hover:bg-accent hover:text-accent-foreground"
          onclick={() => toggleExpand(perm.id)}
          aria-label={expandedNodes.has(perm.id) ? "收起" : "展开"}
        >
          <svg
            class="h-3 w-3 transition-transform duration-200 {expandedNodes.has(perm.id) ? 'rotate-90' : ''}"
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
          <div class="mx-auto mt-2 h-1.5 w-1.5 rounded-full bg-border"></div>
        </div>
      {/if}

      <div class="flex min-w-0 flex-1 items-center justify-between gap-3">
        <div class="min-w-0">
          <div class="flex items-center gap-2">
            <span class="font-medium">{perm.name}</span>
            <Badge variant={perm.status === 1 ? "default" : "destructive"}>
              {perm.status === 1 ? "启用" : "禁用"}
            </Badge>
            <span class="rounded bg-muted px-2 py-0.5 text-xs text-muted-foreground">
              {getTypeLabel(perm.type)}
            </span>
          </div>
          <p class="mt-1 truncate text-xs text-muted-foreground">{perm.code}</p>
        </div>
        {#if level > 0}
          <div class="flex gap-2">
            <Button size="sm" variant="outline" onclick={() => openEditDialog(perm)}>
              编辑
            </Button>
            <Button size="sm" variant="destructive" onclick={() => openDeleteDialog(perm)}>
              删除
            </Button>
          </div>
        {/if}
      </div>
    </div>

    {#if perm.children && perm.children.length > 0 && expandedNodes.has(perm.id)}
      <div class="space-y-1">
        {#each perm.children as child (child.id)}
          {@render PermissionNode(child, level + 1)}
        {/each}
      </div>
    {/if}
  </div>
{/snippet}
