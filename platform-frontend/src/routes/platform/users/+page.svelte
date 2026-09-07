<script lang="ts">
  import { onMount } from "svelte";
  import { listPlatformUsers } from "$api/endpoints/platform";
  import { requirePlatformCapability } from "$utils/platformAccess";
  import { platformErrorMessage } from "$utils/platformMutation";
  import { useAuth } from "$stores/auth.svelte";
  import { Button } from "$components/ui/button";
  import { Input } from "$components/ui/input";
  import { formatDateTime } from "$utils/format";
  import type { PlatformPage, PlatformUser, PlatformRole } from "$api/types";
  import PlatformPager from "$components/features/platform/PlatformPager.svelte";
  import PlatformTenantPicker from "$components/features/platform/PlatformTenantPicker.svelte";
  const auth = useAuth();
  let selectedTenant = $state("");
  let keyword = $state("");
  let role = $state<PlatformRole | "">("");
  let status = $state<0 | 1 | "">("");
  let result = $state<PlatformPage<PlatformUser> | null>(null);
  let loading = $state(true);
  let error = $state("");
  let sequence = 0;
  const roleLabels = {
    user: "普通用户",
    admin: "租户管理员",
    monitor: "监控员",
  };
  /** Load only the current filter's global metadata page. */
  async function load(pageNum = 1): Promise<void> {
    const request = ++sequence;
    const params = {
      pageNum,
      pageSize: 20,
      tenantId: selectedTenant || undefined,
      keyword: keyword || undefined,
      role: role || undefined,
      status: status === "" ? undefined : status,
    };
    loading = true;
    error = "";
    try {
      await requirePlatformCapability("platform:user:read");
      const next = await listPlatformUsers(params);
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
  onMount(() => {
    void load();
    return () => {
      sequence += 1;
    };
  });
</script>

<div class="space-y-6">
  <div>
    <h1 class="text-2xl font-semibold">用户元数据</h1>
    <p class="text-muted-foreground mt-1 text-sm">
      跨租户查询账号基本状态，在对应租户详情中管理成员。
    </p>
  </div>
  {#if auth.hasPlatformCapability("platform:tenant:read")}<PlatformTenantPicker
      bind:value={selectedTenant}
      allowAll
      onSelect={() => {
        void load();
      }}
    />{/if}
  <form
    class="flex flex-wrap items-end gap-3"
    onsubmit={(event) => {
      event.preventDefault();
      void load();
    }}
  >
    <label for="platform-user-search" class="flex-1 space-y-1 text-sm"
      ><span>用户名或昵称</span><Input
        id="platform-user-search"
        bind:value={keyword}
        maxlength={100}
      /></label
    ><label for="platform-user-role" class="space-y-1 text-sm"
      ><span>角色</span><select
        id="platform-user-role"
        bind:value={role}
        class="bg-background border-input block h-10 rounded-md border px-3"
        ><option value="">全部角色</option><option value="user">普通用户</option
        ><option value="admin">租户管理员</option><option value="monitor"
          >监控员</option
        ></select
      ></label
    ><label for="platform-user-status" class="space-y-1 text-sm"
      ><span>状态</span><select
        id="platform-user-status"
        bind:value={status}
        class="bg-background border-input block h-10 rounded-md border px-3"
        ><option value="">全部状态</option><option value={1}>启用</option
        ><option value={0}>停用</option></select
      ></label
    ><Button type="submit">查询</Button>
  </form>
  {#if loading}<p role="status">正在查询用户…</p>{:else if error}<p
      role="alert"
      class="text-destructive"
    >
      {error}
    </p>{:else if result}
    {#if !result.records.length}<p class="text-muted-foreground">
        没有符合条件的用户。
      </p>{:else}<div class="bg-card overflow-x-auto rounded-xl border">
        <table class="w-full text-left text-sm">
          <caption class="sr-only">平台用户元数据</caption><thead
            class="bg-muted/40 text-muted-foreground"
            ><tr
              ><th class="p-3">用户</th><th class="p-3">角色</th><th class="p-3"
                >状态</th
              ><th class="p-3">租户</th><th class="p-3">最近登录</th></tr
            ></thead
          ><tbody
            >{#each result.records as user (user.id)}<tr class="border-t"
                ><td class="p-3 font-medium"
                  >{user.username}{#if user.nickname}<p
                      class="text-muted-foreground font-normal"
                    >
                      {user.nickname}
                    </p>{/if}</td
                ><td class="p-3">{roleLabels[user.role]}</td><td class="p-3"
                  >{user.status === 1 ? "启用" : "停用"}</td
                ><td class="p-3"
                  >{#if auth.hasPlatformCapability("platform:tenant:read")}<a
                      class="text-primary hover:underline"
                      href={`/platform/tenants/${encodeURIComponent(user.tenantId)}`}
                      >{user.tenantId}</a
                    >{:else}{user.tenantId}{/if}</td
                ><td class="p-3"
                  >{user.lastLoginTime === null
                    ? "暂无记录"
                    : formatDateTime(user.lastLoginTime)}</td
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
