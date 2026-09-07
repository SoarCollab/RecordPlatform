<script lang="ts">
  import { onMount } from "svelte";
  import {
    getPlatformOverview,
    getPlatformResourceHealth,
  } from "$api/endpoints/platform";
  import { useAuth } from "$stores/auth.svelte";
  import { requirePlatformCapability } from "$utils/platformAccess";
  import { platformErrorMessage } from "$utils/platformMutation";
  import { Button } from "$components/ui/button";
  import type { PlatformOverview, PlatformHealth } from "$api/types";
  import PlatformHealthPanel from "$components/features/platform/PlatformHealthPanel.svelte";
  const auth = useAuth();
  let overview = $state<PlatformOverview | null>(null);
  let health = $state<PlatformHealth | null>(null);
  let loading = $state(true);
  let error = $state("");
  let sequence = 0;
  /** Load only measured counts and health permitted by the validated session. */
  async function load(): Promise<void> {
    const request = ++sequence;
    loading = true;
    error = "";
    try {
      await requirePlatformCapability("platform:overview:read");
      const [counts, services] = await Promise.all([
        getPlatformOverview(),
        auth.hasPlatformCapability("platform:resource:read")
          ? getPlatformResourceHealth()
          : Promise.resolve(null),
      ]);
      if (request === sequence) {
        overview = counts;
        health = services;
      }
    } catch (failure) {
      if (request === sequence) {
        overview = null;
        health = null;
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
  <div class="flex items-center justify-between gap-4">
    <div>
      <h1 class="text-2xl font-semibold">平台总览</h1>
      <p class="text-muted-foreground mt-1 text-sm">
        租户、账号和共享服务的运行概况。
      </p>
    </div>
    <Button variant="outline" onclick={load} disabled={loading}>刷新</Button>
  </div>
  {#if loading}<p role="status">正在加载平台数据…</p>{:else if error}<p
      role="alert"
      class="text-destructive"
    >
      {error}
    </p>{:else if overview}
    <dl class="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
      <div class="bg-card rounded-xl border p-5">
        <dt class="text-muted-foreground text-sm">租户总数</dt>
        <dd class="my-2 text-3xl font-semibold">{overview.tenants}</dd>
        <p class="text-muted-foreground text-xs">
          启用 {overview.activeTenants} · 停用 {overview.disabledTenants}
        </p>
      </div>
      <div class="bg-card rounded-xl border p-5">
        <dt class="text-muted-foreground text-sm">用户总数</dt>
        <dd class="my-2 text-3xl font-semibold">{overview.users}</dd>
        <p class="text-muted-foreground text-xs">启用 {overview.activeUsers}</p>
      </div>
      <a
        href="/platform/tenants"
        class="bg-card hover:border-primary rounded-xl border p-5"
        ><dt class="font-medium">租户管理</dt>
        <dd class="text-muted-foreground mt-2 text-sm">
          创建租户、管理成员和邀请首位管理员。
        </dd></a
      ><a
        href="/platform/audit"
        class="bg-card hover:border-primary rounded-xl border p-5"
        ><dt class="font-medium">操作审计</dt>
        <dd class="text-muted-foreground mt-2 text-sm">
          核对平台变更与执行结果。
        </dd></a
      >
    </dl>
    {#if health}<PlatformHealthPanel {health} />{/if}
  {/if}
</div>
