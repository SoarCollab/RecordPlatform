<script lang="ts">
  import { onMount } from "svelte";
  import {
    listPlatformConfiguration,
    getPlatformConfiguration,
  } from "$api/endpoints/platform";
  import { useAuth } from "$stores/auth.svelte";
  import { requirePlatformCapability } from "$utils/platformAccess";
  import {
    executePlatformMutation,
    platformErrorMessage,
    type PlatformCommand,
  } from "$utils/platformMutation";
  import { Button } from "$components/ui/button";
  import { Input } from "$components/ui/input";
  import type {
    PlatformConfiguration,
    PlatformConfigurationKey,
  } from "$api/types";
  import PlatformMutationDialog from "$components/features/platform/PlatformMutationDialog.svelte";
  const auth = useAuth();
  let rows = $state<PlatformConfiguration[]>([]);
  let loading = $state(true);
  let error = $state("");
  let sequence = 0;
  let detailSequence = 0;
  let selected = $state<PlatformConfiguration | null>(null);
  let opening = $state(false);
  let editing = $state(false);
  let value = $state<number | undefined>();
  const command = $derived<PlatformCommand | null>(
    selected &&
      selected.mutable &&
      selected.version !== null &&
      value !== undefined
      ? {
          operation: "CONFIGURATION_UPDATE",
          key: selected.key,
          payload: { value, expectedVersion: selected.version },
        }
      : null,
  );
  /** Refresh the entire allowlist and retain unavailable values as explicit nulls. */
  async function load(): Promise<void> {
    const request = ++sequence;
    loading = true;
    error = "";
    try {
      await requirePlatformCapability("platform:configuration:read");
      const result = await listPlatformConfiguration();
      if (request === sequence) rows = result;
    } catch (failure) {
      if (request === sequence) {
        rows = [];
        error = platformErrorMessage(failure);
      }
    } finally {
      if (request === sequence) loading = false;
    }
  }
  /** Read the selected setting again before opening or refreshing its versioned editor. */
  async function readSetting(
    key: PlatformConfigurationKey,
    openEditor = false,
  ): Promise<void> {
    const request = ++detailSequence;
    opening = true;
    error = "";
    try {
      await requirePlatformCapability("platform:configuration:read");
      const result = await getPlatformConfiguration(key);
      if (request !== detailSequence) return;
      selected = result;
      value = result.value ?? undefined;
      if (openEditor) editing = true;
    } catch (failure) {
      if (request === detailSequence) error = platformErrorMessage(failure);
      throw failure;
    } finally {
      if (request === detailSequence) opening = false;
    }
  }
  /** Reload a confirmed editor target so any new attempt uses its authoritative version. */
  async function reloadSelected(): Promise<void> {
    if (!selected) throw new Error("No configuration selected");
    await readSetting(selected.key);
    await load();
  }
  onMount(() => {
    void load();
    return () => {
      sequence += 1;
      detailSequence += 1;
    };
  });
</script>

<div class="space-y-6">
  <div class="flex items-center justify-between gap-4">
    <div>
      <h1 class="text-2xl font-semibold">全局配置</h1>
      <p class="text-muted-foreground mt-1 text-sm">
        仅管理允许在线修改的安全配置，所有变更保留平台审计。
      </p>
    </div>
    <Button variant="outline" onclick={load} disabled={loading}>刷新</Button>
  </div>
  {#if error}<p role="alert" class="text-destructive">{error}</p>{/if}
  {#if loading}<p role="status">
      正在读取配置…
    </p>{:else if !error && !rows.length}<p class="text-muted-foreground">
      暂无配置项。
    </p>{:else}
    <div class="grid gap-4 md:grid-cols-2">
      {#each rows as row (row.key)}<section
          class="bg-card space-y-4 rounded-xl border p-5"
          aria-label={row.key}
        >
          <div>
            <h2 class="font-semibold">{row.key}</h2>
            <p class="text-muted-foreground mt-1 text-sm">{row.description}</p>
          </div>
          <p class="text-2xl font-semibold">
            {row.value === null ? "值不可用" : row.value}
          </p>
          <dl class="text-muted-foreground grid grid-cols-2 gap-2 text-sm">
            <div>
              <dt>允许范围</dt>
              <dd>{row.minimum} – {row.maximum}</dd>
            </div>
            <div>
              <dt>当前版本</dt>
              <dd>{row.version === null ? "版本不可用" : row.version}</dd>
            </div>
            <div>
              <dt>生效方式</dt>
              <dd>{row.restartRequired ? "需要重启" : "在线生效"}</dd>
            </div>
            <div>
              <dt>适用范围</dt>
              <dd>全局 · 数据库配置</dd>
            </div>
          </dl>
          {#if auth.hasPlatformCapability("platform:configuration:write")}<Button
              variant="outline"
              disabled={opening || !row.mutable || row.version === null}
              onclick={() => {
                void readSetting(row.key, true).catch(() => undefined);
              }}>{row.value === null ? "修正配置" : "修改配置"}</Button
            >{/if}
        </section>{/each}
    </div>
  {/if}
</div>
<PlatformMutationDialog
  bind:open={editing}
  title="修改全局配置"
  targetLabel={selected?.key ?? "全局配置"}
  {command}
  onConfirm={executePlatformMutation}
  onReload={reloadSelected}
  onSuccess={reloadSelected}
>
  {#if selected}<p class="text-muted-foreground text-sm">
      {selected.description}，允许范围 {selected.minimum} – {selected.maximum}。
    </p>
    {#if !selected.mutable}<p role="alert" class="text-destructive text-sm">
        此配置当前不可修改。
      </p>
    {:else if selected.version === null}<p
        role="alert"
        class="text-destructive text-sm"
      >
        配置版本不可用，请重新读取后再修改。
      </p>{/if}
    <label for="configuration-value" class="block space-y-1 text-sm"
      ><span>新配置值</span><Input
        id="configuration-value"
        type="number"
        bind:value
        disabled={!selected.mutable || selected.version === null}
        min={selected.minimum}
        max={selected.maximum}
        step={1}
      /></label
    >{/if}
</PlatformMutationDialog>
