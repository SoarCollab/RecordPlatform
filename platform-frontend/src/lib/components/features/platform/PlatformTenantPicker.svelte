<script lang="ts">
  import { onMount } from "svelte";
  import { listPlatformTenants } from "$api/endpoints/platform";
  import { requirePlatformCapability } from "$utils/platformAccess";
  import { platformErrorMessage } from "$utils/platformMutation";
  import { Button } from "$components/ui/button";
  import { Input } from "$components/ui/input";
  import type { PlatformPage, PlatformTenant } from "$api/types";
  import PlatformPager from "./PlatformPager.svelte";
  let {
    value = $bindable(""),
    allowAll = false,
    onSelect,
  }: {
    value: string;
    allowAll?: boolean;
    onSelect?: (value: string) => void;
  } = $props();
  let keyword = $state("");
  let tenants = $state<PlatformPage<PlatformTenant> | null>(null);
  let loading = $state(false);
  let error = $state("");
  let sequence = 0;
  const id = $props.id();
  /** Search every tenant page without converting target selection into identity headers. */
  async function load(pageNum = 1): Promise<void> {
    const request = ++sequence;
    const params = { keyword: keyword || undefined, pageNum, pageSize: 20 };
    loading = true;
    error = "";
    try {
      await requirePlatformCapability("platform:tenant:read");
      const result = await listPlatformTenants(params);
      if (request === sequence) tenants = result;
    } catch (failure) {
      if (request === sequence) {
        tenants = null;
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

<section
  class="bg-card space-y-3 rounded-xl border p-4"
  aria-label="选择目标租户"
>
  <form
    class="flex flex-wrap items-end gap-2"
    onsubmit={(event) => {
      event.preventDefault();
      void load();
    }}
  >
    <label class="min-w-48 flex-1 space-y-1 text-sm" for={`${id}-search`}
      ><span>查找租户</span><Input
        id={`${id}-search`}
        bind:value={keyword}
        maxlength={100}
        placeholder="名称或编码"
      /></label
    ><Button type="submit" variant="outline" disabled={loading}>查找</Button>
  </form>
  <label for={`${id}-tenant`} class="block space-y-1 text-sm"
    ><span>目标租户</span><select
      id={`${id}-tenant`}
      bind:value
      onchange={() => onSelect?.(value)}
      disabled={loading}
      class="bg-background border-input h-10 w-full rounded-md border px-3"
      ><option value="">{allowAll ? "全部租户" : "请选择租户"}</option
      >{#if value && !tenants?.records.some((tenant) => tenant.id === value)}<option
          {value}>{value}</option
        >{/if}{#each tenants?.records ?? [] as tenant (tenant.id)}<option
          value={tenant.id}>{tenant.name}（{tenant.code}）</option
        >{/each}</select
    ></label
  >
  {#if error}<p role="alert" class="text-destructive text-sm">{error}</p>{/if}
  <PlatformPager
    page={tenants}
    disabled={loading}
    onPage={(page) => {
      void load(page);
    }}
  />
</section>
