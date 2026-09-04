<script lang="ts">
  import { onMount } from "svelte";
  import { browser } from "$app/environment";
  import { goto } from "$app/navigation";
  import { Button } from "$components/ui/button";
  import { Input } from "$components/ui/input";
  import * as Dialog from "$components/ui/dialog";
  import {
    changeTenantMemberRole,
    changeTenantMemberStatus,
    createTenantInvitation,
    listTenantInvitations,
    listTenantMembers,
    revokeTenantInvitation,
    revokeTenantMemberSessions,
  } from "$api/endpoints/tenant-users";
  import type { TenantInvitation, TenantMember, TenantRole } from "$api/types";
  import { useNotifications } from "$stores/notifications.svelte";
  import { useAuth } from "$stores/auth.svelte";
  import { formatDateTime } from "$utils/format";

  const notifications = useNotifications();
  const auth = useAuth();
  const pageSize = 20;
  let members = $state<TenantMember[]>([]);
  let invitations = $state<TenantInvitation[]>([]);
  let loading = $state(false);
  let pageNum = $state(1);
  let totalPages = $state(1);
  let keyword = $state("");
  let role = $state<TenantRole | "">("");
  let status = $state<0 | 1 | "">("");
  let requestGeneration = 0;

  let inviteOpen = $state(false);
  let inviteEmail = $state("");
  let inviteRole = $state<TenantRole>("user");
  let inviteHours = $state(72);
  let inviteReason = $state("");
  let saving = $state(false);

  type Action =
    | { kind: "role"; member: TenantMember; value: TenantRole }
    | { kind: "status"; member: TenantMember; value: 0 | 1 }
    | { kind: "sessions"; member: TenantMember }
    | { kind: "invitation"; invitation: TenantInvitation };
  let action = $state<Action | null>(null);
  let actionReason = $state("");

  /** Loads a page and lets only the newest request mutate visible state. */
  async function loadMembers() {
    const generation = ++requestGeneration;
    loading = true;
    try {
      const result = await listTenantMembers({
        pageNum,
        pageSize,
        keyword: keyword.trim() || undefined,
        role: role || undefined,
        status: status === "" ? undefined : status,
      });
      if (generation !== requestGeneration) return;
      members = result.records;
      totalPages = Math.max(
        1,
        result.pages || Math.ceil(result.total / pageSize),
      );
    } catch (error) {
      if (generation !== requestGeneration) return;
      notifications.error(
        "成员加载失败",
        error instanceof Error ? error.message : "请稍后重试",
      );
    } finally {
      if (generation === requestGeneration) loading = false;
    }
  }

  /** Refreshes invitation metadata independently from member paging. */
  async function loadInvitations() {
    try {
      invitations = await listTenantInvitations();
    } catch (error) {
      notifications.error(
        "邀请加载失败",
        error instanceof Error ? error.message : "请稍后重试",
      );
    }
  }

  onMount(() => {
    if (auth.isAdmin) {
      void Promise.all([loadMembers(), loadInvitations()]);
    }
  });

  $effect(() => {
    if (browser && auth.initialized && !auth.isAdmin) {
      void goto("/dashboard", { replaceState: true });
    }
  });

  /** Applies filters from the first page. */
  function search(event: SubmitEvent) {
    event.preventDefault();
    pageNum = 1;
    void loadMembers();
  }

  /** Creates an email-delivered invitation without exposing its token in the UI. */
  async function submitInvitation(event: SubmitEvent) {
    event.preventDefault();
    if (!inviteReason.trim()) return;
    saving = true;
    try {
      await createTenantInvitation({
        email: inviteEmail,
        role: inviteRole,
        expiresInHours: inviteHours,
        reason: inviteReason,
      });
      inviteOpen = false;
      inviteEmail = "";
      inviteReason = "";
      notifications.success("邀请已发送", "一次性邀请链接已投递至目标邮箱");
      await loadInvitations();
    } catch (error) {
      notifications.error(
        "邀请失败",
        error instanceof Error ? error.message : "请稍后重试",
      );
    } finally {
      saving = false;
    }
  }

  /** Executes one confirmed, reasoned member mutation. */
  async function confirmAction() {
    if (!action || !actionReason.trim()) return;
    saving = true;
    try {
      if (action.kind === "role") {
        await changeTenantMemberRole(
          action.member.id,
          action.value,
          actionReason,
        );
      } else if (action.kind === "status") {
        await changeTenantMemberStatus(
          action.member.id,
          action.value,
          actionReason,
        );
      } else if (action.kind === "sessions") {
        await revokeTenantMemberSessions(action.member.id, actionReason);
      } else {
        await revokeTenantInvitation(action.invitation.id, {
          reason: actionReason,
        });
      }
      notifications.success("操作成功", "授权状态已立即生效");
      action = null;
      actionReason = "";
      await Promise.all([loadMembers(), loadInvitations()]);
    } catch (error) {
      notifications.error(
        "操作失败",
        error instanceof Error ? error.message : "请稍后重试",
      );
    } finally {
      saving = false;
    }
  }

  /** Opens a mutation confirmation with a clean mandatory reason. */
  function openAction(next: Action) {
    actionReason = "";
    action = next;
  }
</script>

<svelte:head><title>成员管理 - 存证平台</title></svelte:head>

{#if auth.isAdmin}
  <div class="space-y-6 p-4 md:p-8">
    <header
      class="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between"
    >
      <div>
        <h1 class="text-2xl font-bold">成员管理</h1>
        <p class="text-muted-foreground text-sm">
          管理当前租户的成员、角色、状态和登录会话
        </p>
      </div>
      <Button onclick={() => (inviteOpen = true)}>邀请成员</Button>
    </header>

    <form
      class="bg-card grid gap-3 rounded-xl border p-4 md:grid-cols-[1fr_10rem_10rem_auto]"
      onsubmit={search}
    >
      <Input
        aria-label="搜索成员"
        bind:value={keyword}
        placeholder="搜索用户名、邮箱或昵称"
      />
      <select
        aria-label="角色筛选"
        bind:value={role}
        class="bg-background h-10 rounded-md border px-3 text-sm"
      >
        <option value="">全部角色</option><option value="admin">管理员</option
        ><option value="user">普通用户</option><option value="monitor"
          >监控员</option
        >
      </select>
      <select
        aria-label="状态筛选"
        bind:value={status}
        class="bg-background h-10 rounded-md border px-3 text-sm"
      >
        <option value="">全部状态</option><option value={1}>启用</option><option
          value={0}>禁用</option
        >
      </select>
      <Button type="submit">搜索</Button>
    </form>

    <section
      class="bg-card overflow-hidden rounded-xl border"
      aria-busy={loading}
    >
      <div class="hidden overflow-x-auto md:block">
        <table class="w-full text-sm">
          <thead class="bg-muted/40 border-b text-left"
            ><tr
              ><th class="p-4">成员</th><th class="p-4">角色</th><th class="p-4"
                >状态</th
              ><th class="p-4">最后登录</th><th class="p-4 text-right">操作</th
              ></tr
            ></thead
          >
          <tbody>
            {#each members as member (member.id)}
              <tr class="border-b last:border-0">
                <td class="p-4"
                  ><div class="font-medium">
                    {member.nickname || member.username}
                  </div>
                  <div class="text-muted-foreground">{member.email}</div></td
                >
                <td class="p-4">
                  <select
                    aria-label={`修改 ${member.username} 的角色`}
                    value={member.role}
                    disabled={member.id === auth.user?.externalId}
                    onchange={(event) =>
                      openAction({
                        kind: "role",
                        member,
                        value: event.currentTarget.value as TenantRole,
                      })}
                    class="bg-background rounded-md border px-2 py-1"
                  >
                    <option value="admin">管理员</option><option value="user"
                      >普通用户</option
                    ><option value="monitor">监控员</option>
                  </select>
                </td>
                <td class="p-4"
                  ><span
                    class={member.status === 1
                      ? "text-green-600"
                      : "text-destructive"}
                    >{member.status === 1 ? "启用" : "禁用"}</span
                  ></td
                >
                <td class="text-muted-foreground p-4"
                  >{member.lastLoginTime
                    ? formatDateTime(member.lastLoginTime)
                    : "从未登录"}</td
                >
                <td class="p-4"
                  ><div class="flex justify-end gap-2">
                    <Button
                      size="sm"
                      variant="outline"
                      onclick={() => openAction({ kind: "sessions", member })}
                      >强制退出</Button
                    ><Button
                      size="sm"
                      variant={member.status === 1 ? "destructive" : "outline"}
                      disabled={member.id === auth.user?.externalId}
                      onclick={() =>
                        openAction({
                          kind: "status",
                          member,
                          value: member.status === 1 ? 0 : 1,
                        })}>{member.status === 1 ? "禁用" : "恢复"}</Button
                    >
                  </div></td
                >
              </tr>
            {/each}
          </tbody>
        </table>
      </div>
      <div class="grid gap-3 p-3 md:hidden">
        {#each members as member (member.id)}
          <article class="space-y-3 rounded-lg border p-4">
            <div>
              <div class="font-medium">
                {member.nickname || member.username}
              </div>
              <div class="text-muted-foreground text-sm break-all">
                {member.email}
              </div>
            </div>
            <div class="flex justify-between text-sm">
              <span>{member.role}</span><span
                >{member.status === 1 ? "启用" : "禁用"}</span
              >
            </div>
            <label class="block space-y-1 text-sm">
              <span class="text-muted-foreground">角色</span>
              <select
                aria-label={`修改 ${member.username} 的角色`}
                value={member.role}
                disabled={member.id === auth.user?.externalId}
                onchange={(event) =>
                  openAction({
                    kind: "role",
                    member,
                    value: event.currentTarget.value as TenantRole,
                  })}
                class="bg-background h-9 w-full rounded-md border px-2"
              >
                <option value="admin">管理员</option><option value="user"
                  >普通用户</option
                ><option value="monitor">监控员</option>
              </select>
            </label>
            <div class="flex flex-wrap gap-2">
              <Button
                size="sm"
                variant="outline"
                onclick={() => openAction({ kind: "sessions", member })}
                >强制退出</Button
              ><Button
                size="sm"
                variant="outline"
                disabled={member.id === auth.user?.externalId}
                onclick={() =>
                  openAction({
                    kind: "status",
                    member,
                    value: member.status === 1 ? 0 : 1,
                  })}>{member.status === 1 ? "禁用" : "恢复"}</Button
              >
            </div>
          </article>
        {/each}
      </div>
      {#if !loading && members.length === 0}<p
          class="text-muted-foreground p-10 text-center"
        >
          暂无成员
        </p>{/if}
    </section>

    <div class="flex items-center justify-between">
      <span class="text-muted-foreground text-sm"
        >第 {pageNum} / {totalPages} 页</span
      >
      <div class="flex gap-2">
        <Button
          variant="outline"
          disabled={pageNum <= 1 || loading}
          onclick={() => {
            pageNum -= 1;
            void loadMembers();
          }}>上一页</Button
        ><Button
          variant="outline"
          disabled={pageNum >= totalPages || loading}
          onclick={() => {
            pageNum += 1;
            void loadMembers();
          }}>下一页</Button
        >
      </div>
    </div>

    <section class="space-y-3">
      <h2 class="text-lg font-semibold">最近邀请</h2>
      {#each invitations as invitation (invitation.id)}<div
          class="bg-card flex flex-col gap-2 rounded-lg border p-4 sm:flex-row sm:items-center sm:justify-between"
        >
          <div>
            <div class="font-medium">{invitation.email}</div>
            <div class="text-muted-foreground text-sm">
              {invitation.role} · {invitation.status} · 到期 {formatDateTime(
                invitation.expiresAt,
              )}
            </div>
          </div>
          {#if invitation.status === "PENDING"}<Button
              size="sm"
              variant="outline"
              onclick={() => openAction({ kind: "invitation", invitation })}
              >撤销邀请</Button
            >{/if}
        </div>{/each}
    </section>
  </div>

  <Dialog.Root bind:open={inviteOpen}>
    <Dialog.Content class="sm:max-w-md"
      ><form onsubmit={submitInvitation} class="space-y-4">
        <Dialog.Header
          ><Dialog.Title>邀请成员</Dialog.Title><Dialog.Description
            >邀请链接只通过邮件投递，页面不会显示令牌。</Dialog.Description
          ></Dialog.Header
        ><label class="block space-y-2 text-sm"
          ><span>邮箱</span><Input
            type="email"
            required
            bind:value={inviteEmail}
          /></label
        ><label class="block space-y-2 text-sm"
          ><span>角色</span><select
            required
            bind:value={inviteRole}
            class="bg-background h-10 w-full rounded-md border px-3"
            ><option value="user">普通用户</option><option value="monitor"
              >监控员</option
            ><option value="admin">管理员</option></select
          ></label
        ><label class="block space-y-2 text-sm"
          ><span>有效小时数</span><Input
            type="number"
            min={1}
            max={168}
            required
            bind:value={inviteHours}
          /></label
        ><label class="block space-y-2 text-sm"
          ><span>邀请原因</span><Input
            required
            maxlength={255}
            bind:value={inviteReason}
          /></label
        ><Dialog.Footer
          ><Button
            type="button"
            variant="outline"
            onclick={() => (inviteOpen = false)}>取消</Button
          ><Button type="submit" disabled={saving || !inviteReason.trim()}
            >{saving ? "发送中…" : "发送邀请"}</Button
          ></Dialog.Footer
        >
      </form></Dialog.Content
    >
  </Dialog.Root>

  <Dialog.Root
    open={action !== null}
    onOpenChange={(open) => {
      if (!open) action = null;
    }}
  >
    <Dialog.Content class="sm:max-w-md"
      ><Dialog.Header
        ><Dialog.Title>确认成员管理操作</Dialog.Title><Dialog.Description
          >此操作会写入审计日志；角色、状态及强制退出会立即使旧令牌失效。</Dialog.Description
        ></Dialog.Header
      ><label class="block space-y-2 text-sm"
        ><span>操作原因</span><Input
          required
          maxlength={255}
          bind:value={actionReason}
          placeholder="请输入操作原因"
        /></label
      ><Dialog.Footer
        ><Button variant="outline" onclick={() => (action = null)}>取消</Button
        ><Button
          variant="destructive"
          disabled={saving || !actionReason.trim()}
          onclick={confirmAction}>{saving ? "处理中…" : "确认"}</Button
        ></Dialog.Footer
      ></Dialog.Content
    >
  </Dialog.Root>
{/if}
