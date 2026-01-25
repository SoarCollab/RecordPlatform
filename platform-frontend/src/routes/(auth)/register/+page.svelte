<script lang="ts">
  import { goto } from "$app/navigation";
  import { useAuth } from "$stores/auth.svelte";
  import { useNotifications } from "$stores/notifications.svelte";
  import * as validation from "$utils/validation";
  import { sendRegisterCode } from "$api/endpoints/auth";
  import { fly } from "svelte/transition";

  const auth = useAuth();
  const notifications = useNotifications();

  let username = $state("");
  let password = $state("");
  let confirmPwd = $state("");
  let nickname = $state("");
  let email = $state("");
  let verifyCode = $state("");
  let isSubmitting = $state(false);
  let sendingCode = $state(false);
  let countdown = $state(0);

  $effect(() => {
    if (auth.initialized && auth.isAuthenticated) {
      goto("/dashboard", { replaceState: true });
    }
  });

  // 倒计时
  $effect(() => {
    if (countdown > 0) {
      const timer = setTimeout(() => {
        countdown--;
      }, 1000);
      return () => clearTimeout(timer);
    }
  });

  async function handleSendCode() {
    // 先校验邮箱
    const emailResult = validation.email(email);
    if (!emailResult.valid) {
      notifications.warning("邮箱格式错误", emailResult.message);
      return;
    }

    sendingCode = true;
    try {
      await sendRegisterCode(email);
      notifications.success("验证码已发送", "请查收邮件");
      countdown = 60; // 开始 60 秒倒计时
    } catch (err) {
      notifications.error(
        "发送失败",
        err instanceof Error ? err.message : "请稍后重试"
      );
    } finally {
      sendingCode = false;
    }
  }

  async function handleSubmit(e: Event) {
    e.preventDefault();

    // 校验
    const usernameResult = validation.username(username);
    if (!usernameResult.valid) {
      notifications.warning("用户名格式错误", usernameResult.message);
      return;
    }

    const emailResult = validation.email(email);
    if (!emailResult.valid) {
      notifications.warning("邮箱格式错误", emailResult.message);
      return;
    }

    if (!verifyCode.trim()) {
      notifications.warning("验证码不能为空", "请输入邮箱验证码");
      return;
    }

    const passwordResult = validation.password(password);
    if (!passwordResult.valid) {
      notifications.warning("密码格式错误", passwordResult.message);
      return;
    }

    const confirmResult = validation.confirmPassword(confirmPwd, password);
    if (!confirmResult.valid) {
      notifications.warning("确认密码错误", confirmResult.message);
      return;
    }

    isSubmitting = true;

    try {
      await auth.register({
        username,
        password,
        nickname,
        email,
        code: verifyCode,
      });
      notifications.success("注册成功", "欢迎使用存证平台");
      await goto("/dashboard");
    } catch (err) {
      notifications.error(
        "注册失败",
        err instanceof Error ? err.message : "请稍后重试"
      );
    } finally {
      isSubmitting = false;
    }
  }
</script>

<svelte:head>
  <title>注册 - 存证平台</title>
</svelte:head>

<div
  class="rounded-lg border bg-card p-8 shadow-sm"
  in:fly={{ y: 20, duration: 400, delay: 100 }}
>
  <div class="mb-6 text-center">
    <div
      class="mx-auto mb-4 flex h-12 w-12 items-center justify-center rounded-lg bg-primary text-primary-foreground"
    >
      <svg
        class="h-6 w-6"
        fill="none"
        stroke="currentColor"
        viewBox="0 0 24 24"
      >
        <path
          stroke-linecap="round"
          stroke-linejoin="round"
          stroke-width="2"
          d="M18 9v3m0 0v3m0-3h3m-3 0h-3m-2-5a4 4 0 11-8 0 4 4 0 018 0zM3 20a6 6 0 0112 0v1H3v-1z"
        />
      </svg>
    </div>
    <h1 class="text-2xl font-bold">创建账户</h1>
    <p class="text-sm text-muted-foreground">注册成为存证平台用户</p>
  </div>

  <form onsubmit={handleSubmit} class="space-y-4">
    <div>
      <label for="username" class="mb-2 block text-sm font-medium">
        用户名 <span class="text-destructive">*</span>
      </label>
      <input
        type="text"
        id="username"
        bind:value={username}
        class="w-full rounded-lg border bg-background px-3 py-2 text-sm focus:border-primary focus:outline-none focus:ring-1 focus:ring-primary"
        placeholder="4-20位字母、数字或下划线"
        disabled={isSubmitting}
      />
    </div>

    <div>
      <label for="email" class="mb-2 block text-sm font-medium">
        邮箱 <span class="text-destructive">*</span>
      </label>
      <input
        type="email"
        id="email"
        bind:value={email}
        class="w-full rounded-lg border bg-background px-3 py-2 text-sm focus:border-primary focus:outline-none focus:ring-1 focus:ring-primary"
        placeholder="用于接收验证码"
        disabled={isSubmitting}
        required
      />
    </div>

    <div>
      <label for="verifyCode" class="mb-2 block text-sm font-medium">
        验证码 <span class="text-destructive">*</span>
      </label>
      <div class="flex gap-2">
        <input
          type="text"
          id="verifyCode"
          bind:value={verifyCode}
          class="flex-1 rounded-lg border bg-background px-3 py-2 text-sm focus:border-primary focus:outline-none focus:ring-1 focus:ring-primary"
          placeholder="输入邮箱验证码"
          disabled={isSubmitting}
          required
        />
        <button
          type="button"
          onclick={handleSendCode}
          class="rounded-lg border bg-background px-4 py-2 text-sm font-medium hover:bg-accent disabled:opacity-50"
          disabled={sendingCode || countdown > 0 || !email}
        >
          {#if sendingCode}
            发送中...
          {:else if countdown > 0}
            {countdown}秒后重试
          {:else}
            发送验证码
          {/if}
        </button>
      </div>
    </div>

    <div>
      <label for="nickname" class="mb-2 block text-sm font-medium">昵称</label>
      <input
        type="text"
        id="nickname"
        bind:value={nickname}
        class="w-full rounded-lg border bg-background px-3 py-2 text-sm focus:border-primary focus:outline-none focus:ring-1 focus:ring-primary"
        placeholder="可选，用于显示"
        disabled={isSubmitting}
      />
    </div>

    <div>
      <label for="password" class="mb-2 block text-sm font-medium">
        密码 <span class="text-destructive">*</span>
      </label>
      <input
        type="password"
        id="password"
        bind:value={password}
        class="w-full rounded-lg border bg-background px-3 py-2 text-sm focus:border-primary focus:outline-none focus:ring-1 focus:ring-primary"
        placeholder="至少8位，包含字母和数字"
        disabled={isSubmitting}
      />
    </div>

    <div>
      <label for="confirmPwd" class="mb-2 block text-sm font-medium">
        确认密码 <span class="text-destructive">*</span>
      </label>
      <input
        type="password"
        id="confirmPwd"
        bind:value={confirmPwd}
        class="w-full rounded-lg border bg-background px-3 py-2 text-sm focus:border-primary focus:outline-none focus:ring-1 focus:ring-primary"
        placeholder="再次输入密码"
        disabled={isSubmitting}
      />
    </div>

    <button
      type="submit"
      class="w-full rounded-lg bg-primary px-4 py-2 text-sm font-medium text-primary-foreground hover:bg-primary/90 disabled:opacity-50"
      disabled={isSubmitting}
    >
      {isSubmitting ? "注册中..." : "注册"}
    </button>
  </form>

  <div class="mt-6 text-center text-sm">
    <span class="text-muted-foreground">已有账户？</span>
    <a href="/login" class="text-primary hover:underline">立即登录</a>
  </div>
</div>
