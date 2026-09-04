<script lang="ts">
  import { browser } from "$app/environment";
  import { goto } from "$app/navigation";
  import { onMount } from "svelte";
  import { Button } from "$components/ui/button";
  import { Input } from "$components/ui/input";
  import { acceptTenantInvitation } from "$api/endpoints/tenant-users";
  import { useNotifications } from "$stores/notifications.svelte";
  import { readInvitationTokenFromFragment } from "./invitation-token";

  const notifications = useNotifications();
  let username = $state("");
  let nickname = $state("");
  let password = $state("");
  let submitting = $state(false);
  let invitationToken = $state("");

  onMount(() => {
    invitationToken = readInvitationTokenFromFragment(
      new URL(window.location.href),
    );
    if (browser && invitationToken) {
      history.replaceState(history.state, "", window.location.pathname);
    }
  });

  /** Accepts the URL token once and clears it by navigating to login. */
  async function submit(event: SubmitEvent) {
    event.preventDefault();
    if (!invitationToken) {
      notifications.error("邀请无效", "链接缺少邀请令牌");
      return;
    }
    submitting = true;
    try {
      await acceptTenantInvitation({
        token: invitationToken,
        username,
        nickname: nickname || undefined,
        password,
      });
      notifications.success("加入成功", "请使用新账号登录");
      await goto("/login", { replaceState: true });
    } catch (error) {
      notifications.error(
        "接受邀请失败",
        error instanceof Error ? error.message : "请联系租户管理员",
      );
    } finally {
      submitting = false;
    }
  }
</script>

<svelte:head><title>接受邀请 - 存证平台</title></svelte:head>
<main class="mx-auto flex min-h-[70vh] max-w-md items-center px-4">
  <form
    class="bg-card w-full space-y-5 rounded-xl border p-6 shadow-sm"
    onsubmit={submit}
  >
    <div>
      <h1 class="text-2xl font-bold">接受成员邀请</h1>
      <p class="text-muted-foreground mt-1 text-sm">
        设置账号信息后加入受邀租户
      </p>
    </div>
    <label class="block space-y-2 text-sm"
      ><span>用户名</span><Input
        required
        minlength={3}
        maxlength={50}
        pattern="[A-Za-z0-9_.-]+"
        bind:value={username}
      /></label
    ><label class="block space-y-2 text-sm"
      ><span>昵称（可选）</span><Input
        maxlength={50}
        bind:value={nickname}
      /></label
    ><label class="block space-y-2 text-sm"
      ><span>密码</span><Input
        type="password"
        required
        minlength={8}
        maxlength={72}
        autocomplete="new-password"
        bind:value={password}
      /></label
    ><Button type="submit" class="w-full" disabled={submitting}
      >{submitting ? "提交中…" : "接受邀请"}</Button
    >
  </form>
</main>
