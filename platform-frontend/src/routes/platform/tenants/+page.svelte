<script lang="ts">
  import { onMount } from "svelte";
  import { goto } from "$app/navigation";
  import { listPlatformTenants } from "$api/endpoints/platform";
  import { useAuth } from "$stores/auth.svelte";
  import { requirePlatformCapability } from "$utils/platformAccess";
  import {
    executePlatformMutation,
    platformErrorMessage,
    type PlatformCommand,
  } from "$utils/platformMutation";
  import { Button } from "$components/ui/button";
  import { Input } from "$components/ui/input";
  import type { PlatformTenant, PlatformPage } from "$api/types";
  import PlatformPager from "$components/features/platform/PlatformPager.svelte";
  import PlatformMutationDialog from "$components/features/platform/PlatformMutationDialog.svelte";
  const auth = useAuth();
  let keyword = $state("");
  let status = $state<0 | 1 | "">("");
  let result = $state<PlatformPage<PlatformTenant> | null>(null);
  let loading = $state(true);
  let error = $state("");
  let sequence = 0;
  let creating = $state(false);
  let code = $state("");
  let name = $state("");
  let maxStorageBytes = $state<number | undefined>();
  let maxFileCount = $state<number | undefined>();
  const command = $derived<PlatformCommand>({
    operation: "TENANT_CREATE",
    payload: {
      code,
      name,
      ...(maxStorageBytes === undefined ? {} : { maxStorageBytes }),
      ...(maxFileCount === undefined ? {} : { maxFileCount }),
    },
  });
  /** Load the current tenant filter and reject stale success, error and loading completion. */
  async function load(pageNum = 1): Promise<void> {
    const request = ++sequence;
    const params = {
      keyword: keyword || undefined,
      status: status === "" ? undefined : status,
      pageNum,
      pageSize: 20,
    };
    loading = true;
    error = "";
    try {
      await requirePlatformCapability("platform:tenant:read");
      const next = await listPlatformTenants(params);
      if (request === sequence) result = next;
    } catch (failure) {
      if (request === sequence) {
        result = null;
        error = platformErrorMessage(failure);
      }
    } finally {
      if (request === sequence) loading = false;
    }
  }
  /** Begin a new unsubmitted tenant draft without provisioning any credentials. */
  function create(): void {
    code = "";
    name = "";
    maxStorageBytes = undefined;
    maxFileCount = undefined;
    creating = true;
  }
  onMount(() => {
    void load();
    return () => {
      sequence += 1;
    };
  });
</script>

<div class="space-y-6">
  <div class="flex items-center justify-between gap-4">
    <div>
      <h1 class="text-2xl font-semibold">租户管理</h1>
      <p class="text-muted-foreground mt-1 text-sm">
        管理租户生命周期，并在租户内邀请和管理成员。
      </p>
    </div>
    {#if auth.hasPlatformCapability("platform:tenant:write")}<Button
        onclick={create}>创建租户</Button
      >{/if}
  </div>
  <form
    class="flex flex-wrap items-end gap-3"
    onsubmit={(event) => {
      event.preventDefault();
      void load();
    }}
  >
    <label for="tenant-keyword" class="min-w-48 flex-1 space-y-1 text-sm"
      ><span>名称或编码</span><Input
        id="tenant-keyword"
        bind:value={keyword}
        maxlength={100}
        placeholder="搜索租户"
      /></label
    ><label for="tenant-status" class="space-y-1 text-sm"
      ><span>租户状态</span><select
        id="tenant-status"
        bind:value={status}
        class="bg-background border-input block h-10 rounded-md border px-3"
        ><option value="">全部状态</option><option value={1}>启用</option
        ><option value={0}>停用</option></select
      ></label
    ><Button type="submit">查询</Button>
  </form>
  {#if loading}<p role="status">正在查询租户…</p>{:else if error}<p
      role="alert"
      class="text-destructive"
    >
      {error}
    </p>{:else if result}{#if !result.records.length}<p
        class="text-muted-foreground"
      >
        没有符合条件的租户。
      </p>{:else}<div class="bg-card overflow-x-auto rounded-xl border">
        <table class="w-full text-left text-sm">
          <caption class="sr-only">租户列表</caption><thead
            class="bg-muted/40 text-muted-foreground"
            ><tr
              ><th class="p-3">租户</th><th class="p-3">编码</th><th class="p-3"
                >状态</th
              ><th class="p-3">成员</th><th class="p-3">版本</th><th class="p-3"
                >管理</th
              ></tr
            ></thead
          ><tbody
            >{#each result.records as tenant (tenant.id)}<tr class="border-t"
                ><td class="p-3 font-medium">{tenant.name}</td><td
                  class="p-3 font-mono text-xs">{tenant.code}</td
                ><td class="p-3">{tenant.status === 1 ? "启用" : "停用"}</td><td
                  class="p-3">{tenant.memberCount}</td
                ><td class="p-3">{tenant.version}</td><td class="p-3"
                  ><a
                    href={`/platform/tenants/${encodeURIComponent(tenant.id)}`}
                    class="text-primary hover:underline">查看租户</a
                  ></td
                ></tr
              >{/each}</tbody
          >
        </table>
      </div>{/if}<PlatformPager
      page={result}
      disabled={loading}
      onPage={(page) => {
        void load(page);
      }}
    />{/if}
</div>
<PlatformMutationDialog
  bind:open={creating}
  title="创建租户"
  targetLabel={name || "新租户"}
  {command}
  onConfirm={executePlatformMutation}
  onReload={() => load()}
  onSuccess={async (outcome) => {
    await goto(`/platform/tenants/${encodeURIComponent(outcome.resourceId)}`);
  }}
>
  <p class="text-muted-foreground text-sm">
    创建后请在租户详情中邀请首位管理员。不会创建默认账号或密码。
  </p>
  <label for="new-tenant-code" class="block space-y-1 text-sm"
    ><span>租户编码</span><Input
      id="new-tenant-code"
      bind:value={code}
      minlength={2}
      maxlength={64}
      placeholder="以小写字母开头，如 team-alpha"
    /></label
  >
  <label for="new-tenant-name" class="block space-y-1 text-sm"
    ><span>租户名称</span><Input
      id="new-tenant-name"
      bind:value={name}
      maxlength={128}
    /></label
  >
  <label for="new-tenant-storage" class="block space-y-1 text-sm"
    ><span>初始存储限额（字节，可选）</span><Input
      id="new-tenant-storage"
      type="number"
      bind:value={maxStorageBytes}
      min={0}
      max={Number.MAX_SAFE_INTEGER}
      step={1}
      placeholder="留空使用默认值"
    /></label
  >
  <label for="new-tenant-files" class="block space-y-1 text-sm"
    ><span>初始文件限额（可选）</span><Input
      id="new-tenant-files"
      type="number"
      bind:value={maxFileCount}
      min={0}
      max={Number.MAX_SAFE_INTEGER}
      step={1}
      placeholder="留空使用默认值"
    /></label
  >
</PlatformMutationDialog>
