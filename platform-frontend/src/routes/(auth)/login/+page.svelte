<script lang="ts">
  import { goto } from "$app/navigation";
  import { useAuth } from "$stores/auth.svelte";
  import { useNotifications } from "$stores/notifications.svelte";
  import { wasRememberMeSelected } from "$api/client";

  const auth = useAuth();
  const notifications = useNotifications();

  let username = $state("");
  let password = $state("");
  let rememberMe = $state(wasRememberMeSelected());
  let isSubmitting = $state(false);

  $effect(() => {
    if (auth.initialized && auth.isAuthenticated) {
      goto("/dashboard", { replaceState: true });
    }
  });

  async function handleSubmit(e: Event) {
    e.preventDefault();

    if (!username || !password) {
      notifications.warning("请填写完整", "用户名和密码不能为空");
      return;
    }

    isSubmitting = true;

    try {
      await auth.login({ username, password }, { rememberMe });
      notifications.success("登录成功", `欢迎回来，${auth.displayName}`);
      await goto("/dashboard");
    } catch (err) {
      notifications.error(
        "登录失败",
        err instanceof Error ? err.message : "请检查用户名和密码"
      );
    } finally {
      isSubmitting = false;
    }
  }
</script>

<svelte:head>
  <title>登录 - 存证平台</title>
</svelte:head>

<div class="rounded-lg border bg-card p-8 shadow-sm">
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
          d="M9 12l2 2 4-4m5.618-4.016A11.955 11.955 0 0112 2.944a11.955 11.955 0 01-8.618 3.04A12.02 12.02 0 003 9c0 5.591 3.824 10.29 9 11.622 5.176-1.332 9-6.03 9-11.622 0-1.042-.133-2.052-.382-3.016z"
        />
      </svg>
    </div>
    <h1 class="text-2xl font-bold">欢迎回来</h1>
    <p class="text-sm text-muted-foreground">登录您的存证平台账户</p>
  </div>

  <form onsubmit={handleSubmit} class="space-y-4">
    <div>
      <label for="username" class="mb-2 block text-sm font-medium">用户名</label
      >
      <input
        type="text"
        id="username"
        bind:value={username}
        class="w-full rounded-lg border bg-background px-3 py-2 text-sm focus:border-primary focus:outline-none focus:ring-1 focus:ring-primary"
        placeholder="请输入用户名"
        disabled={isSubmitting}
      />
    </div>

    <div>
      <label for="password" class="mb-2 block text-sm font-medium">密码</label>
      <input
        type="password"
        id="password"
        bind:value={password}
        class="w-full rounded-lg border bg-background px-3 py-2 text-sm focus:border-primary focus:outline-none focus:ring-1 focus:ring-primary"
        placeholder="请输入密码"
        disabled={isSubmitting}
      />
    </div>

    <div class="flex items-center justify-between">
      <label class="flex items-center gap-2 text-sm cursor-pointer">
        <input
          type="checkbox"
          bind:checked={rememberMe}
          class="h-4 w-4 rounded border accent-primary"
          disabled={isSubmitting}
        />
        <span>记住我</span>
      </label>
      <a href="/reset-password" class="text-sm text-primary hover:underline"
        >忘记密码？</a
      >
    </div>

    <button
      type="submit"
      class="w-full rounded-lg bg-primary px-4 py-2 text-sm font-medium text-primary-foreground hover:bg-primary/90 disabled:opacity-50"
      disabled={isSubmitting}
    >
      {isSubmitting ? "登录中..." : "登录"}
    </button>
  </form>

  <div class="mt-6 text-center text-sm">
    <span class="text-muted-foreground">还没有账户？</span>
    <a href="/register" class="text-primary hover:underline">立即注册</a>
  </div>
</div>
