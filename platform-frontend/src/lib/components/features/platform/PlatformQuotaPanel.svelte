<script lang="ts">
  import {
    getPlatformTenantQuota,
    getPlatformTenantUsage,
  } from "$api/endpoints/platform";
  import { useAuth } from "$stores/auth.svelte";
  import { requirePlatformCapability } from "$utils/platformAccess";
  import {
    executePlatformMutation,
    platformErrorMessage,
    type PlatformCommand,
  } from "$utils/platformMutation";
  import { formatFileSize } from "$utils/format";
  import { Button } from "$components/ui/button";
  import { Input } from "$components/ui/input";
  import type { PlatformQuota, PlatformUsage } from "$api/types";
  import PlatformMutationDialog from "./PlatformMutationDialog.svelte";
  let {
    tenantId,
    tenantName = "目标租户",
  }: { tenantId: string; tenantName?: string } = $props();
  const auth = useAuth();
  let quota = $state<PlatformQuota | null>(null);
  let usage = $state<PlatformUsage | null>(null);
  let loading = $state(false);
  let error = $state("");
  let sequence = 0;
  let editing = $state(false);
  let maxStorageBytes = $state(0);
  let maxFileCount = $state(0);
  const sourceLabels = {
    TENANT_OVERRIDE: "租户覆盖",
    TENANT_DEFAULT: "租户默认",
    APPLICATION_DEFAULT: "应用默认",
  };
  const command = $derived<PlatformCommand | null>(
    quota
      ? {
          operation: "QUOTA_UPDATE",
          tenantId,
          payload: {
            maxStorageBytes,
            maxFileCount,
            expectedVersion: quota.version,
          },
        }
      : null,
  );
  /** Read one target's effective limits and available measured usage. */
  async function load(id: string, strict = false): Promise<void> {
    const request = ++sequence;
    quota = null;
    usage = null;
    loading = true;
    error = "";
    try {
      await requirePlatformCapability("platform:quota:read");
      const [nextQuota, nextUsage] = await Promise.all([
        getPlatformTenantQuota(id),
        auth.hasPlatformCapability("platform:tenant:read")
          ? getPlatformTenantUsage(id)
          : Promise.resolve(null),
      ]);
      if (request !== sequence) return;
      quota = nextQuota;
      usage = nextUsage;
      maxStorageBytes = nextQuota.maxStorageBytes;
      maxFileCount = nextQuota.maxFileCount;
    } catch (failure) {
      if (request === sequence) error = platformErrorMessage(failure);
      if (strict) throw failure;
    } finally {
      if (request === sequence) loading = false;
    }
  }
  $effect(() => {
    const id = tenantId;
    void load(id);
    return () => {
      sequence += 1;
    };
  });
</script>

<section
  aria-label="租户配额与用量"
  class="bg-card space-y-4 rounded-xl border p-5"
>
  <div class="flex items-center justify-between gap-3">
    <h2 class="font-semibold">租户配额与用量</h2>
    <Button
      variant="outline"
      size="sm"
      onclick={() => load(tenantId)}
      disabled={loading}>刷新用量</Button
    >
  </div>
  {#if loading}<p role="status" class="text-muted-foreground text-sm">
      正在读取用量…
    </p>{:else if error}<p role="alert" class="text-destructive text-sm">
      {error}
    </p>{:else if quota}
    <dl class="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
      <div>
        <dt class="text-muted-foreground text-sm">已用存储</dt>
        <dd class="mt-1 text-xl font-semibold">
          {formatFileSize(quota.usedStorageBytes)}
        </dd>
      </div>
      <div>
        <dt class="text-muted-foreground text-sm">存储限额</dt>
        <dd class="mt-1 text-xl font-semibold">
          {formatFileSize(quota.maxStorageBytes)}
        </dd>
      </div>
      <div>
        <dt class="text-muted-foreground text-sm">有效文件</dt>
        <dd class="mt-1 text-xl font-semibold">{quota.usedFileCount}</dd>
      </div>
      <div>
        <dt class="text-muted-foreground text-sm">文件限额</dt>
        <dd class="mt-1 text-xl font-semibold">{quota.maxFileCount}</dd>
      </div>
    </dl>
    <p class="text-muted-foreground text-sm">
      来源：{sourceLabels[quota.source]} · {quota.enforcementMode === "ENFORCE"
        ? "强制执行"
        : "观察模式"} · 版本 {quota.version}
    </p>
    {#if usage}<dl class="grid gap-3 border-t pt-4 text-sm sm:grid-cols-3">
        <div>
          <dt class="text-muted-foreground">租户用户</dt>
          <dd>{usage.users}</dd>
        </div>
        <div>
          <dt class="text-muted-foreground">租户业务审计</dt>
          <dd>{usage.auditRecords}</dd>
        </div>
        <div>
          <dt class="text-muted-foreground">已完成存证批次</dt>
          <dd>{usage.completedAttestations}</dd>
        </div>
      </dl>{/if}
    {#if auth.hasPlatformCapability("platform:quota:write")}<Button
        onclick={() => {
          if (quota) {
            maxStorageBytes = quota.maxStorageBytes;
            maxFileCount = quota.maxFileCount;
            editing = true;
          }
        }}>修改配额</Button
      >{/if}
  {/if}
</section>
<PlatformMutationDialog
  bind:open={editing}
  title="修改租户配额"
  targetLabel={tenantName}
  {command}
  onConfirm={executePlatformMutation}
  onReload={() => load(tenantId, true)}
  onSuccess={() => load(tenantId, true)}
>
  <label class="block space-y-1 text-sm" for="quota-storage"
    ><span>存储限额（字节）</span><Input
      id="quota-storage"
      type="number"
      bind:value={maxStorageBytes}
      min={0}
      max={Number.MAX_SAFE_INTEGER}
      step={1}
    /></label
  >
  <label class="block space-y-1 text-sm" for="quota-files"
    ><span>文件数量限额</span><Input
      id="quota-files"
      type="number"
      bind:value={maxFileCount}
      min={0}
      max={Number.MAX_SAFE_INTEGER}
      step={1}
    /></label
  >
</PlatformMutationDialog>
