<script lang="ts">
  import { onDestroy, type Snippet } from "svelte";
  import { beforeNavigate } from "$app/navigation";
  import {
    getCredentialSnapshot,
    isCurrentCredential,
    type CredentialSnapshot,
  } from "$api/client";
  import * as Dialog from "$components/ui/dialog";
  import { Button } from "$components/ui/button";
  import { Label } from "$components/ui/label";
  import { Textarea } from "$components/ui/textarea";
  import {
    createPlatformMutationAttempt,
    classifyPlatformMutationFailure,
    platformErrorMessage,
    type PlatformCommand,
    type PlatformMutationAttempt,
  } from "$utils/platformMutation";
  import type { PlatformMutationVO } from "$api/types";

  interface Props {
    open: boolean;
    title: string;
    targetLabel: string;
    command: PlatformCommand | null;
    children?: Snippet;
    onConfirm: (
      attempt: PlatformMutationAttempt,
    ) => Promise<PlatformMutationVO>;
    onReload: () => Promise<void>;
    onSuccess?: (result: PlatformMutationVO) => void | Promise<void>;
    onCancel?: () => void;
  }
  let {
    open = $bindable(false),
    title,
    targetLabel,
    command,
    children,
    onConfirm,
    onReload,
    onSuccess,
    onCancel,
  }: Props = $props();
  let phase = $state<
    | "edit"
    | "review"
    | "pending"
    | "uncertain"
    | "processing"
    | "conflict"
    | "rejected"
    | "success"
  >("edit");
  let reason = $state("");
  let attempt = $state<PlatformMutationAttempt | null>(null);
  let reviewedTarget = $state("");
  let reviewedTitle = $state("");
  let message = $state("");
  let result = $state<PlatformMutationVO | null>(null);
  let reloading = $state(false);
  let generation = 0;
  let reviewedCredential: CredentialSnapshot | null = null;
  const locked = $derived(
    phase === "pending" || phase === "uncertain" || phase === "processing",
  );
  const fieldLabels: Record<string, string> = {
    code: "租户编码",
    name: "租户名称",
    status: "状态",
    role: "角色",
    email: "邀请邮箱",
    expiresInHours: "有效小时数",
    maxStorageBytes: "存储限额（字节）",
    maxFileCount: "文件数量限额",
    expectedVersion: "当前版本",
    value: "配置值",
  };

  /** Clear a closed draft and invalidate callbacks from its previous target. */
  function resetDraft(): void {
    generation += 1;
    phase = "edit";
    attempt = null;
    reviewedCredential = null;
    result = null;
    reason = "";
    message = "";
    reloading = false;
  }

  // An external close is a lifecycle event; this guarded reset settles after one transition.
  $effect(() => {
    if (!open && !locked && (phase !== "edit" || reason || message))
      resetDraft();
  });

  /** Keep pending or unknown attempts visible even if an outside close is requested. */
  function getOpen(): boolean {
    return open || locked;
  }
  /** Close only a resolved operation or an unsubmitted draft. */
  function setOpen(next: boolean): void {
    if (!next && locked) return;
    open = next;
    if (!next) {
      if (phase !== "success") onCancel?.();
      resetDraft();
    }
  }
  /** Freeze the reviewed target, reason and command before enabling the final action. */
  function review(): void {
    message = "";
    if (!command || !reason.trim() || reason.trim().length > 255) {
      message = "请填写完整的变更内容和 1 至 255 字的操作原因。";
      return;
    }
    try {
      attempt = createPlatformMutationAttempt(command, reason);
      reviewedCredential = getCredentialSnapshot();
      reviewedTarget = targetLabel;
      reviewedTitle = title;
      phase = "review";
    } catch {
      message = "请检查变更内容、数值范围和当前资源版本。";
    }
  }
  /** Submit or retry the same immutable attempt, never regenerating its UUID. */
  async function confirm(): Promise<void> {
    if (
      !attempt ||
      phase === "pending" ||
      phase === "success" ||
      phase === "conflict" ||
      phase === "rejected"
    )
      return;
    if (!reviewedCredential || !isCurrentCredential(reviewedCredential)) {
      phase = "rejected";
      message = "会话已改变，请重新打开页面并核对操作。";
      return;
    }
    const current = attempt;
    const request = ++generation;
    phase = "pending";
    message = "";
    try {
      const outcome = await onConfirm(current);
      if (request !== generation) return;
      result = outcome;
      phase = "success";
      try {
        await onSuccess?.(outcome);
      } catch {
        if (request === generation)
          message =
            "操作已成功，但页面数据刷新失败。可重新读取数据，不要重复提交。";
      }
    } catch (failure) {
      if (request !== generation) return;
      const kind = classifyPlatformMutationFailure(failure);
      phase =
        kind === "version-conflict"
          ? "conflict"
          : kind === "processing"
            ? "processing"
            : kind === "uncertain"
              ? "uncertain"
              : "rejected";
      message = platformErrorMessage(failure);
    }
  }
  /** Reload authoritative state after a confirmed version conflict or a successful write. */
  async function reload(): Promise<void> {
    if (reloading) return;
    reloading = true;
    const request = ++generation;
    try {
      await onReload();
      if (request !== generation) return;
      if (phase === "conflict") {
        attempt = null;
        phase = "edit";
      }
      message = "";
    } catch {
      if (request === generation) message = "读取最新状态失败，请稍后重试。";
    } finally {
      if (request === generation) reloading = false;
    }
  }
  /** Format only the frozen allowlisted scalar fields for the human review step. */
  function valueLabel(key: string, value: unknown): string {
    if (value === null || value === undefined) return "使用默认值";
    if (key === "status") return value === 1 ? "启用" : "停用";
    if (key === "role")
      return value === "admin"
        ? "租户管理员"
        : value === "monitor"
          ? "监控员"
          : "普通用户";
    return String(value);
  }

  beforeNavigate((navigation) => {
    if (locked && reviewedCredential && isCurrentCredential(reviewedCredential))
      navigation.cancel();
    else if (open) setOpen(false);
  });
  onDestroy(() => {
    generation += 1;
  });
</script>

<Dialog.Root bind:open={getOpen, setOpen}>
  <Dialog.Content
    class="max-h-[90vh] overflow-y-auto sm:max-w-xl"
    showCloseButton={!locked}
    interactOutsideBehavior={locked ? "ignore" : "close"}
    escapeKeydownBehavior={locked ? "ignore" : "close"}
  >
    <Dialog.Header>
      <Dialog.Title>{attempt ? reviewedTitle : title}</Dialog.Title>
      <Dialog.Description
        >{attempt ? reviewedTarget : targetLabel}</Dialog.Description
      >
    </Dialog.Header>
    {#if phase === "edit"}
      <div class="space-y-4">
        {@render children?.()}
        <div class="space-y-2">
          <Label for="platform-operation-reason">操作原因</Label><Textarea
            id="platform-operation-reason"
            bind:value={reason}
            maxlength={255}
            placeholder="说明此次变更的原因"
            required
          />
        </div>
      </div>
    {:else if attempt}
      <div class="space-y-4">
        <p class="font-medium">核对后确认此操作</p>
        <dl
          class="bg-muted/40 grid grid-cols-[auto_1fr] gap-x-4 gap-y-2 rounded-lg p-4 text-sm"
        >
          <dt class="text-muted-foreground">目标</dt>
          <dd class="break-all">{reviewedTarget}</dd>
          {#if "tenantId" in attempt.command}<dt class="text-muted-foreground">
              租户标识
            </dt>
            <dd class="break-all">{attempt.command.tenantId}</dd>{/if}
          {#if "userId" in attempt.command}<dt class="text-muted-foreground">
              成员标识
            </dt>
            <dd class="break-all">{attempt.command.userId}</dd>{/if}
          {#if "invitationId" in attempt.command}<dt
              class="text-muted-foreground"
            >
              邀请标识
            </dt>
            <dd class="break-all">{attempt.command.invitationId}</dd>{/if}
          {#if "key" in attempt.command}<dt class="text-muted-foreground">
              配置项
            </dt>
            <dd class="break-all">{attempt.command.key}</dd>{/if}
          {#each Object.entries(attempt.command.payload) as [key, value] (key)}<dt
              class="text-muted-foreground"
            >
              {fieldLabels[key] ?? key}
            </dt>
            <dd class="break-all">{valueLabel(key, value)}</dd>{/each}
          <dt class="text-muted-foreground">原因</dt>
          <dd class="break-all">{attempt.reason}</dd>
        </dl>
        <p class="text-muted-foreground text-xs break-all">
          操作标识：{attempt.idempotencyKey}
        </p>
      </div>
    {/if}
    {#if message}<p role="alert" class="text-destructive text-sm">
        {message}
      </p>{/if}
    {#if phase === "pending"}<p
        role="status"
        class="text-muted-foreground text-sm"
      >
        正在提交，请勿重复操作…
      </p>{/if}
    {#if result}<div role="status" class="space-y-1 text-sm">
        <p class="font-medium text-green-700 dark:text-green-400">操作成功</p>
        <p class="break-all">记录编号：{result.operationId}</p>
        {#if result.version !== null}<p>资源版本：{result.version}</p>{/if}
      </div>{/if}
    <Dialog.Footer>
      {#if phase === "edit"}<Button
          variant="outline"
          onclick={() => setOpen(false)}>取消</Button
        ><Button onclick={review} disabled={!command || !reason.trim()}
          >核对变更</Button
        >
      {:else if phase === "review"}<Button
          variant="outline"
          onclick={() => {
            phase = "edit";
            attempt = null;
          }}>返回修改</Button
        ><Button onclick={confirm}>确认执行</Button>
      {:else if phase === "pending"}<Button disabled>提交中…</Button>
      {:else if phase === "uncertain" || phase === "processing"}<Button
          onclick={confirm}>使用同一操作重试</Button
        >
      {:else if phase === "conflict"}<Button
          variant="outline"
          onclick={() => setOpen(false)}>关闭</Button
        ><Button onclick={reload} disabled={reloading}
          >{reloading ? "读取中…" : "重新读取并编辑"}</Button
        >
      {:else if phase === "success"}{#if message}<Button
            variant="outline"
            onclick={reload}
            disabled={reloading}>重新读取数据</Button
          >{/if}<Button onclick={() => setOpen(false)}>完成</Button>
      {:else}<Button variant="outline" onclick={() => setOpen(false)}
          >关闭</Button
        >{/if}
    </Dialog.Footer>
  </Dialog.Content>
</Dialog.Root>
