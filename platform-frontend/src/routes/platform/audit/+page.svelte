<script lang="ts">
  import { onMount } from "svelte";
  import { listPlatformAudit, getPlatformAudit } from "$api/endpoints/platform";
  import { requirePlatformCapability } from "$utils/platformAccess";
  import { platformErrorMessage } from "$utils/platformMutation";
  import { useAuth } from "$stores/auth.svelte";
  import { Button } from "$components/ui/button";
  import * as Dialog from "$components/ui/dialog";
  import { formatDateTime } from "$utils/format";
  import type { PlatformAudit, PlatformPage } from "$api/types";
  import PlatformPager from "$components/features/platform/PlatformPager.svelte";
  import PlatformTenantPicker from "$components/features/platform/PlatformTenantPicker.svelte";
  const auth = useAuth();
  let selectedTenant = $state("");
  let status = $state<PlatformAudit["status"] | "">("");
  let result = $state<PlatformPage<PlatformAudit> | null>(null);
  let loading = $state(true);
  let error = $state("");
  let sequence = 0;
  let detailSequence = 0;
  let selected = $state<PlatformAudit | null>(null);
  let detailOpen = $state(false);
  let detailLoading = $state(false);
  let detailError = $state("");
  const statuses = { SUCCESS: "成功", FAILURE: "失败", PROCESSING: "处理中" };
  const operations = {
    TENANT_CREATE: "创建租户",
    TENANT_UPDATE: "修改租户",
    TENANT_STATUS_CHANGE: "变更租户状态",
    USER_ROLE_CHANGE: "修改成员角色",
    USER_STATUS_CHANGE: "变更成员状态",
    USER_SESSIONS_REVOKE: "撤销成员会话",
    INVITATION_CREATE: "发送邀请",
    INVITATION_REVOKE: "撤销邀请",
    QUOTA_UPDATE: "修改配额",
    CONFIGURATION_UPDATE: "修改全局配置",
  };
  /** Read one current platform audit filter without accepting stale responses. */
  async function load(pageNum = 1): Promise<void> {
    const request = ++sequence;
    const params = {
      pageNum,
      pageSize: 20,
      tenantId: selectedTenant || undefined,
      status: status || undefined,
    };
    loading = true;
    error = "";
    try {
      await requirePlatformCapability("platform:audit:read");
      const next = await listPlatformAudit(params);
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
  /** Fetch the authoritative sanitized detail for the selected durable operation. */
  async function detail(id: string): Promise<void> {
    const request = ++detailSequence;
    selected = null;
    detailLoading = true;
    detailError = "";
    detailOpen = true;
    try {
      await requirePlatformCapability("platform:audit:read");
      const next = await getPlatformAudit(id);
      if (request === detailSequence) selected = next;
    } catch (failure) {
      if (request === detailSequence)
        detailError = platformErrorMessage(failure);
    } finally {
      if (request === detailSequence) detailLoading = false;
    }
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
  <div>
    <h1 class="text-2xl font-semibold">平台操作审计</h1>
    <p class="text-muted-foreground mt-1 text-sm">
      核对平台变更、执行结果和关联记录。
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
    class="flex items-end gap-3"
    onsubmit={(event) => {
      event.preventDefault();
      void load();
    }}
  >
    <label for="platform-audit-status" class="space-y-1 text-sm"
      ><span>执行状态</span><select
        id="platform-audit-status"
        bind:value={status}
        class="bg-background border-input block h-10 rounded-md border px-3"
        ><option value="">全部状态</option><option value="SUCCESS">成功</option
        ><option value="FAILURE">失败</option><option value="PROCESSING"
          >处理中</option
        ></select
      ></label
    ><Button type="submit">查询</Button>
  </form>
  {#if loading}<p role="status">正在读取审计记录…</p>{:else if error}<p
      role="alert"
      class="text-destructive"
    >
      {error}
    </p>{:else if result}{#if !result.records.length}<p
        class="text-muted-foreground"
      >
        暂无符合条件的操作记录。
      </p>{:else}<div class="bg-card overflow-x-auto rounded-xl border">
        <table class="w-full text-left text-sm">
          <caption class="sr-only">平台操作记录</caption><thead
            class="bg-muted/40 text-muted-foreground"
            ><tr
              ><th class="p-3">操作</th><th class="p-3">原因</th><th class="p-3"
                >状态</th
              ><th class="p-3">开始时间</th><th class="p-3">详情</th></tr
            ></thead
          ><tbody
            >{#each result.records as record (record.id)}<tr class="border-t"
                ><td class="p-3 font-medium">{operations[record.operation]}</td
                ><td class="max-w-sm p-3 break-words">{record.reason}</td><td
                  class="p-3">{statuses[record.status]}</td
                ><td class="p-3">{formatDateTime(record.startedAt)}</td><td
                  class="p-3"
                  ><Button
                    size="sm"
                    variant="outline"
                    onclick={() => detail(record.id)}>查看记录</Button
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
<Dialog.Root bind:open={detailOpen}
  ><Dialog.Content class="max-h-[85vh] overflow-y-auto sm:max-w-2xl"
    ><Dialog.Header
      ><Dialog.Title>平台操作详情</Dialog.Title><Dialog.Description
        >仅展示已脱敏的操作证据。</Dialog.Description
      ></Dialog.Header
    >{#if detailLoading}<p role="status">
        正在读取详情…
      </p>{:else if detailError}<p role="alert" class="text-destructive">
        {detailError}
      </p>{:else if selected}<dl
        class="grid grid-cols-[auto_1fr] gap-x-4 gap-y-3 text-sm"
      >
        <dt>记录编号</dt>
        <dd class="break-all">{selected.id}</dd>
        <dt>操作</dt>
        <dd>{operations[selected.operation]}</dd>
        <dt>状态</dt>
        <dd>{statuses[selected.status]}</dd>
        <dt>操作者</dt>
        <dd class="break-all">{selected.actorId}</dd>
        <dt>目标租户</dt>
        <dd class="break-all">{selected.targetTenantId ?? "全局操作"}</dd>
        <dt>目标资源</dt>
        <dd class="break-all">{selected.resourceId ?? "尚未产生"}</dd>
        <dt>操作原因</dt>
        <dd class="break-words">{selected.reason}</dd>
        <dt>变更前</dt>
        <dd class="break-words">{selected.beforeSummary ?? "无摘要"}</dd>
        <dt>变更后</dt>
        <dd class="break-words">{selected.afterSummary ?? "无摘要"}</dd>
        <dt>执行耗时</dt>
        <dd>
          {selected.durationMs === null
            ? "尚未完成"
            : `${selected.durationMs} ms`}
        </dd>
        <dt>完成时间</dt>
        <dd>
          {selected.completedAt === null
            ? "尚未完成"
            : formatDateTime(selected.completedAt)}
        </dd>
        {#if selected.errorCode !== null}<dt>错误码</dt>
          <dd>{selected.errorCode}</dd>{/if}{#if selected.traceId}<dt>
            追踪号
          </dt>
          <dd class="break-all">
            {selected.traceId}
          </dd>{/if}{#if selected.result}<dt>结果资源</dt>
          <dd class="break-all">{selected.result.resourceId}</dd>
          {#if selected.result.version !== null}<dt>结果版本</dt>
            <dd>{selected.result.version}</dd>{/if}{/if}
      </dl>{/if}</Dialog.Content
  ></Dialog.Root
>
