<script lang="ts">
  import { browser } from "$app/environment";
  import { goto } from "$app/navigation";
  import { onMount } from "svelte";
  import { Button } from "$components/ui/button";
  import { Input } from "$components/ui/input";
  import { getToken } from "$api/client";
  import { acceptTenantInvitation } from "$api/endpoints/tenant-users";
  import { useNotifications } from "$stores/notifications.svelte";
  import { readInvitationTokenFromFragment } from "./invitation-token";

  const notifications = useNotifications();
  let username = $state("");
  let nickname = $state("");
  let password = $state("");
  let submitting = $state(false);
  let invitationToken = $state("");
  let accepted = $state(false);
  let hasExistingSession = $state(false);
  let switchingAccount = $state(false);

  onMount(() => {
    invitationToken = readInvitationTokenFromFragment(
      new URL(window.location.href),
    );
    if (browser && invitationToken) {
      history.replaceState(history.state, "", window.location.pathname);
    }
  });

  /** Accepts the capability anonymously without replacing or ending an existing session. */
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
      invitationToken = "";
      password = "";
      accepted = true;
      hasExistingSession = Boolean(getToken());
      notifications.success("加入成功", "请使用新账号登录");
      if (!hasExistingSession) {
        await goto("/login", { replaceState: true });
      }
    } catch (error) {
      notifications.error(
        "接受邀请失败",
        error instanceof Error ? error.message : "请联系租户管理员",
      );
    } finally {
      submitting = false;
    }
  }

  /** Ends the current session only after the user explicitly chooses the new-account login. */
  async function loginNewAccount() {
    switchingAccount = true;
    try {
      if (hasExistingSession || getToken()) {
        const { useAuth } = await import("$stores/auth.svelte");
        await useAuth().logout();
      } else {
        await goto("/login", { replaceState: true });
      }
    } catch (error) {
      notifications.error(
        "切换账号失败",
        error instanceof Error ? error.message : "请稍后重试",
      );
    } finally {
      switchingAccount = false;
    }
  }

  /** Returns to the existing account's normal landing page without changing its session. */
  async function continueWithCurrentAccount() {
    await goto("/dashboard", { replaceState: true });
  }
</script>

<svelte:head><title>接受邀请 - 存证平台</title></svelte:head>
<main class="bg-muted/50 flex min-h-screen items-center justify-center px-4">
  {#if accepted}
    <section
      class="bg-card w-full max-w-md space-y-5 rounded-xl border p-6 shadow-sm"
      aria-labelledby="invitation-accepted-title"
    >
      <div>
        <h1 id="invitation-accepted-title" class="text-2xl font-bold">
          已加入受邀租户
        </h1>
        <p class="text-muted-foreground mt-2 text-sm">
          {hasExistingSession
            ? "当前账号仍保持登录。登录新账号将退出当前账号，也可以继续使用当前账号。"
            : "账号已创建，请使用新账号登录。"}
        </p>
      </div>
      <div class="flex flex-col gap-3">
        <Button disabled={switchingAccount} onclick={loginNewAccount}
          >{switchingAccount ? "切换中…" : "登录新账号"}</Button
        >
        {#if hasExistingSession}
          <Button
            variant="outline"
            disabled={switchingAccount}
            onclick={continueWithCurrentAccount}>继续使用当前账号</Button
          >
        {/if}
      </div>
    </section>
  {:else}
    <form
      class="bg-card w-full max-w-md space-y-5 rounded-xl border p-6 shadow-sm"
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
  {/if}
</main>
