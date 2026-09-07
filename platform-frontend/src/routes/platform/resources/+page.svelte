<script lang="ts">
  import { onMount } from "svelte";
  import { page } from "$app/stores";
  import { getPlatformResourceHealth } from "$api/endpoints/platform";
  import { requirePlatformCapability } from "$utils/platformAccess";
  import { platformErrorMessage } from "$utils/platformMutation";
  import { useAuth } from "$stores/auth.svelte";
  import type { PlatformHealth } from "$api/types";
  import { Button } from "$components/ui/button";
  import PlatformTenantPicker from "$components/features/platform/PlatformTenantPicker.svelte";
  import PlatformHealthPanel from "$components/features/platform/PlatformHealthPanel.svelte";
  import PlatformQuotaPanel from "$components/features/platform/PlatformQuotaPanel.svelte";
  const auth = useAuth();
  let selectedTenant = $state("");
  let health = $state<PlatformHealth | null>(null);
  let loading = $state(false);
  let error = $state("");
  let sequence = 0;
  /** Refresh sanitized shared health without treating missing information as healthy. */
  async function load(): Promise<void> {
    const request = ++sequence;
    loading = true;
    error = "";
    try {
      await requirePlatformCapability("platform:resource:read");
      const result = await getPlatformResourceHealth();
      if (request === sequence) health = result;
    } catch (failure) {
      if (request === sequence) {
        health = null;
        error = platformErrorMessage(failure);
      }
    } finally {
      if (request === sequence) loading = false;
    }
  }
  onMount(() => {
    selectedTenant = $page.url.searchParams.get("tenant") ?? "";
    void load();
    return () => {
      sequence += 1;
    };
  });
</script>

<div class="space-y-6">
  <div class="flex items-center justify-between">
    <div>
      <h1 class="text-2xl font-semibold">配额与资源</h1>
      <p class="text-muted-foreground mt-1 text-sm">
        共享服务状态与明确目标租户的资源用量。
      </p>
    </div>
    <Button variant="outline" onclick={load} disabled={loading}
      >刷新服务状态</Button
    >
  </div>
  {#if loading}<p role="status">正在检查共享服务…</p>{:else if error}<p
      role="alert"
      class="text-destructive"
    >
      {error}
    </p>{:else if health}<PlatformHealthPanel {health} />{/if}
  {#if auth.hasPlatformCapability("platform:tenant:read")}<PlatformTenantPicker
      bind:value={selectedTenant}
    />{/if}
  {#if selectedTenant && auth.hasPlatformCapability("platform:quota:read")}<PlatformQuotaPanel
      tenantId={selectedTenant}
    />{:else}<p class="text-muted-foreground text-sm">
      选择租户后查看其用量和配额。
    </p>{/if}
</div>
