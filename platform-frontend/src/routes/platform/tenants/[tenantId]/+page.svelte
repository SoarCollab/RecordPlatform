<script lang="ts">
  import {
    getPlatformTenant,
    listPlatformTenantMembers,
    listPlatformTenantInvitations,
  } from "$api/endpoints/platform";
  import { useAuth } from "$stores/auth.svelte";
  import { requirePlatformCapability } from "$utils/platformAccess";
  import {
    executePlatformMutation,
    platformErrorMessage,
    type PlatformCommand,
  } from "$utils/platformMutation";
  import { formatDateTime } from "$utils/format";
  import { Button } from "$components/ui/button";
  import { Input } from "$components/ui/input";
  import type {
    PlatformTenant,
    PlatformMember,
    PlatformInvitation,
    PlatformPage,
    PlatformRole,
    PlatformMemberQuery,
  } from "$api/types";
  import PlatformPager from "$components/features/platform/PlatformPager.svelte";
  import PlatformQuotaPanel from "$components/features/platform/PlatformQuotaPanel.svelte";
  import PlatformMutationDialog from "$components/features/platform/PlatformMutationDialog.svelte";
  let { data }: { data: { tenantId: string } } = $props();
  const auth = useAuth();
  let tenant = $state<PlatformTenant | null>(null);
  let members = $state<PlatformPage<PlatformMember> | null>(null);
  let invitations = $state<PlatformInvitation[]>([]);
  let loading = $state(true);
  let error = $state("");
  let sequence = 0;
  let keyword = $state("");
  let memberRole = $state<PlatformRole | "">("");
  let memberStatus = $state<0 | 1 | "">("");
  let action = $state<PlatformCommand["operation"]>("TENANT_UPDATE");
  let editing = $state(false);
  let chosenMember = $state<PlatformMember | null>(null);
  let chosenInvitation = $state<PlatformInvitation | null>(null);
  let draftName = $state("");
  let draftRole = $state<PlatformRole>("user");
  let email = $state("");
  let expiresInHours = $state(72);
  const roles = { user: "普通用户", admin: "租户管理员", monitor: "监控员" };
  const invitationStatuses = {
    PENDING: "待接受",
    ACCEPTED: "已接受",
    REVOKED: "已撤销",
    EXPIRED: "已过期",
  };
  const titles: Partial<Record<PlatformCommand["operation"], string>> = {
    TENANT_UPDATE: "修改租户名称",
    TENANT_STATUS_CHANGE: "变更租户状态",
    USER_ROLE_CHANGE: "修改成员角色",
    USER_STATUS_CHANGE: "变更成员状态",
    USER_SESSIONS_REVOKE: "撤销成员会话",
    INVITATION_CREATE: "邀请租户成员",
    INVITATION_REVOKE: "撤销成员邀请",
  };
  const targetLabel = $derived(
    `${tenant?.name ?? data.tenantId}${chosenMember ? ` / ${chosenMember.username}` : chosenInvitation ? ` / ${chosenInvitation.email}` : ""}`,
  );
  const command = $derived.by<PlatformCommand | null>(() => {
    if (!tenant) return null;
    const tenantId = data.tenantId;
    if (action === "TENANT_UPDATE")
      return {
        operation: action,
        tenantId,
        payload: { name: draftName, expectedVersion: tenant.version },
      };
    if (action === "TENANT_STATUS_CHANGE")
      return {
        operation: action,
        tenantId,
        payload: {
          status: tenant.status === 1 ? 0 : 1,
          expectedVersion: tenant.version,
        },
      };
    if (action === "INVITATION_CREATE")
      return {
        operation: action,
        tenantId,
        payload: { email, role: draftRole, expiresInHours },
      };
    if (action === "INVITATION_REVOKE" && chosenInvitation)
      return {
        operation: action,
        tenantId,
        invitationId: chosenInvitation.id,
        payload: {},
      };
    if (!chosenMember) return null;
    if (action === "USER_ROLE_CHANGE")
      return {
        operation: action,
        tenantId,
        userId: chosenMember.id,
        payload: { role: draftRole },
      };
    if (action === "USER_STATUS_CHANGE")
      return {
        operation: action,
        tenantId,
        userId: chosenMember.id,
        payload: { status: chosenMember.status === 1 ? 0 : 1 },
      };
    if (action === "USER_SESSIONS_REVOKE")
      return {
        operation: action,
        tenantId,
        userId: chosenMember.id,
        payload: {},
      };
    return null;
  });
  /** Load metadata and permitted member projections for one exact route target. */
  async function load(
    id: string,
    params: PlatformMemberQuery = {},
    strict = false,
  ): Promise<void> {
    const request = ++sequence;
    const query = { pageNum: 1, pageSize: 20, ...params };
    tenant = null;
    members = null;
    invitations = [];
    loading = true;
    error = "";
    try {
      await requirePlatformCapability("platform:tenant:read");
      const canReadMembers = auth.hasPlatformCapability("platform:user:read");
      const [details, nextMembers, nextInvitations] = await Promise.all([
        getPlatformTenant(id),
        canReadMembers ? listPlatformTenantMembers(id, query) : null,
        canReadMembers ? listPlatformTenantInvitations(id) : [],
      ]);
      if (request !== sequence) return;
      tenant = details;
      members = nextMembers;
      invitations = nextInvitations;
      draftName = details.name;
    } catch (failure) {
      if (request === sequence) error = platformErrorMessage(failure);
      if (strict) throw failure;
    } finally {
      if (request === sequence) loading = false;
    }
  }
  /** Capture the currently visible member filters for an explicit search or page change. */
  function memberQuery(pageNum = 1): PlatformMemberQuery {
    return {
      pageNum,
      pageSize: 20,
      keyword: keyword || undefined,
      role: memberRole || undefined,
      status: memberStatus === "" ? undefined : memberStatus,
    };
  }
  /** Start one reviewed command for the selected target without capturing mutable row references. */
  function edit(
    operation: PlatformCommand["operation"],
    member: PlatformMember | null = null,
    invitation: PlatformInvitation | null = null,
  ): void {
    action = operation;
    chosenMember = member;
    chosenInvitation = invitation;
    draftName = tenant?.name ?? "";
    draftRole = member?.role ?? (tenant?.memberCount === 0 ? "admin" : "user");
    email = "";
    expiresInHours = 72;
    editing = true;
  }
  /** Propagate reload failures so a version conflict cannot become an editable stale command. */
  async function reload(): Promise<void> {
    await load(data.tenantId, memberQuery(), true);
  }
  $effect(() => {
    const id = data.tenantId;
    void load(id);
    return () => {
      sequence += 1;
    };
  });
</script>

<div class="space-y-6">
  <a href="/platform/tenants" class="text-primary text-sm hover:underline"
    >返回租户列表</a
  >
  {#if loading}<p role="status">正在读取租户详情…</p>{:else if error}<p
      role="alert"
      class="text-destructive"
    >
      {error}
    </p>
    <Button variant="outline" onclick={() => load(data.tenantId)}>重试</Button
    >{:else if tenant}
    <section class="bg-card space-y-4 rounded-xl border p-5">
      <div class="flex flex-wrap items-start justify-between gap-4">
        <div>
          <h1 class="text-2xl font-semibold">{tenant.name}</h1>
          <p class="text-muted-foreground mt-1 text-sm">
            {tenant.code} · {tenant.status === 1 ? "启用" : "停用"} · 版本 {tenant.version}
          </p>
        </div>
        {#if auth.hasPlatformCapability("platform:tenant:write")}<div
            class="flex gap-2"
          >
            <Button variant="outline" onclick={() => edit("TENANT_UPDATE")}
              >修改名称</Button
            ><Button
              variant={tenant.status === 1 ? "destructive" : "outline"}
              onclick={() => edit("TENANT_STATUS_CHANGE")}
              >{tenant.status === 1 ? "停用租户" : "恢复租户"}</Button
            >
          </div>{/if}
      </div>
      <p class="text-muted-foreground text-xs break-all">
        租户标识：{tenant.id}
      </p>
      {#if tenant.disabledReason}<p class="text-sm">
          停用原因：{tenant.disabledReason}
        </p>{/if}
    </section>
    {#if auth.hasPlatformCapability("platform:quota:read")}<PlatformQuotaPanel
        tenantId={data.tenantId}
        tenantName={tenant.name}
      />{/if}
    {#if auth.hasPlatformCapability("platform:user:read")}
      <section class="space-y-4">
        <div class="flex items-center justify-between">
          <h2 class="text-lg font-semibold">租户成员</h2>
          {#if auth.hasPlatformCapability("platform:user:write")}<Button
              onclick={() => edit("INVITATION_CREATE")}
              >{tenant.memberCount === 0
                ? "邀请首位管理员"
                : "邀请成员"}</Button
            >{/if}
        </div>
        <form
          class="flex flex-wrap items-end gap-2"
          onsubmit={(event) => {
            event.preventDefault();
            void load(data.tenantId, memberQuery());
          }}
        >
          <label for="member-search" class="min-w-44 flex-1 space-y-1 text-sm"
            ><span>搜索成员</span><Input
              id="member-search"
              bind:value={keyword}
              maxlength={100}
              placeholder="用户名、昵称或邮箱"
            /></label
          ><label for="member-role" class="space-y-1 text-sm"
            ><span>角色</span><select
              id="member-role"
              bind:value={memberRole}
              class="bg-background border-input block h-10 rounded-md border px-3"
              ><option value="">全部角色</option
              >{#each Object.entries(roles) as [key, label]}<option value={key}
                  >{label}</option
                >{/each}</select
            ></label
          ><label for="member-status" class="space-y-1 text-sm"
            ><span>状态</span><select
              id="member-status"
              bind:value={memberStatus}
              class="bg-background border-input block h-10 rounded-md border px-3"
              ><option value="">全部状态</option><option value={1}>启用</option
              ><option value={0}>停用</option></select
            ></label
          ><Button type="submit">查询成员</Button>
        </form>
        {#if members?.records.length}<div
            class="bg-card overflow-x-auto rounded-xl border"
          >
            <table class="w-full text-left text-sm">
              <caption class="sr-only">租户成员列表</caption><thead
                class="bg-muted/40 text-muted-foreground"
                ><tr
                  ><th class="p-3">成员</th><th class="p-3">邮箱</th><th
                    class="p-3">角色</th
                  ><th class="p-3">状态</th><th class="p-3">管理</th></tr
                ></thead
              ><tbody
                >{#each members.records as member (member.id)}<tr
                    class="border-t"
                    ><td class="p-3 font-medium">{member.username}</td><td
                      class="p-3">{member.email}</td
                    ><td class="p-3">{roles[member.role]}</td><td class="p-3"
                      >{member.status === 1 ? "启用" : "停用"}</td
                    ><td class="p-3"
                      >{#if auth.hasPlatformCapability("platform:user:write")}<div
                          class="flex flex-wrap gap-2"
                        >
                          <Button
                            size="sm"
                            variant="outline"
                            onclick={() => edit("USER_ROLE_CHANGE", member)}
                            >修改角色</Button
                          ><Button
                            size="sm"
                            variant="outline"
                            onclick={() => edit("USER_STATUS_CHANGE", member)}
                            >{member.status === 1
                              ? "停用成员"
                              : "恢复成员"}</Button
                          ><Button
                            size="sm"
                            variant="outline"
                            onclick={() => edit("USER_SESSIONS_REVOKE", member)}
                            >撤销会话</Button
                          >
                        </div>{/if}</td
                    ></tr
                  >{/each}</tbody
              >
            </table>
          </div>{:else}<p class="text-muted-foreground text-sm">
            暂无符合条件的成员。
          </p>{/if}<PlatformPager
          page={members}
          onPage={(page) => {
            void load(data.tenantId, memberQuery(page));
          }}
        />
      </section>
      <section class="space-y-4">
        <h2 class="text-lg font-semibold">成员邀请</h2>
        <p class="text-muted-foreground text-sm">
          邀请链接通过邮件发送给收件人，此处仅显示邀请状态。
        </p>
        {#if invitations.length}<div
            class="bg-card overflow-x-auto rounded-xl border"
          >
            <table class="w-full text-left text-sm">
              <caption class="sr-only">成员邀请列表</caption><thead
                class="bg-muted/40 text-muted-foreground"
                ><tr
                  ><th class="p-3">邮箱</th><th class="p-3">角色</th><th
                    class="p-3">状态</th
                  ><th class="p-3">到期时间</th><th class="p-3">操作</th></tr
                ></thead
              ><tbody
                >{#each invitations as invitation (invitation.id)}<tr
                    class="border-t"
                    ><td class="p-3">{invitation.email}</td><td class="p-3"
                      >{roles[invitation.role]}</td
                    ><td class="p-3">{invitationStatuses[invitation.status]}</td
                    ><td class="p-3">{formatDateTime(invitation.expiresAt)}</td
                    ><td class="p-3"
                      >{#if auth.hasPlatformCapability("platform:user:write")}<Button
                          size="sm"
                          variant="outline"
                          disabled={invitation.status !== "PENDING"}
                          onclick={() =>
                            edit("INVITATION_REVOKE", null, invitation)}
                          >撤销邀请</Button
                        >{/if}</td
                    ></tr
                  >{/each}</tbody
              >
            </table>
          </div>{:else}<p class="text-muted-foreground text-sm">
            暂无邀请记录。
          </p>{/if}
      </section>
    {/if}
  {/if}
</div>
<PlatformMutationDialog
  bind:open={editing}
  title={titles[action] ?? "平台操作"}
  {targetLabel}
  {command}
  onConfirm={executePlatformMutation}
  onReload={reload}
  onSuccess={reload}
>
  {#if action === "TENANT_UPDATE"}<label
      for="tenant-new-name"
      class="block space-y-1 text-sm"
      ><span>新租户名称</span><Input
        id="tenant-new-name"
        bind:value={draftName}
        maxlength={128}
      /></label
    >
  {:else if action === "USER_ROLE_CHANGE" || action === "INVITATION_CREATE"}{#if action === "INVITATION_CREATE"}<label
        for="invitation-email"
        class="block space-y-1 text-sm"
        ><span>邀请邮箱</span><Input
          id="invitation-email"
          type="email"
          bind:value={email}
          maxlength={100}
        /></label
      >{/if}<label for="member-new-role" class="block space-y-1 text-sm"
      ><span>目标角色</span><select
        id="member-new-role"
        bind:value={draftRole}
        class="bg-background border-input h-10 w-full rounded-md border px-3"
        >{#each Object.entries(roles) as [key, label]}<option value={key}
            >{label}</option
          >{/each}</select
      ></label
    >{#if action === "INVITATION_CREATE"}<label
        for="invitation-hours"
        class="block space-y-1 text-sm"
        ><span>邀请有效期（小时）</span><Input
          id="invitation-hours"
          type="number"
          bind:value={expiresInHours}
          min={1}
          max={168}
          step={1}
        /></label
      >{/if}
  {:else if action === "TENANT_STATUS_CHANGE"}<p class="text-sm">
      停用租户会阻止其成员继续使用现有会话；恢复后可重新登录。
    </p>
  {:else if action === "USER_SESSIONS_REVOKE"}<p class="text-sm">
      此操作会使该成员的现有登录会话失效。
    </p>
  {:else if action === "USER_STATUS_CHANGE"}<p class="text-sm">
      成员状态变化会使其旧会话失效，最后一位有效管理员受到保护。
    </p>
  {:else if action === "INVITATION_REVOKE"}<p class="text-sm">
      撤销后，原邀请链接将无法用于加入租户。
    </p>{/if}
</PlatformMutationDialog>
