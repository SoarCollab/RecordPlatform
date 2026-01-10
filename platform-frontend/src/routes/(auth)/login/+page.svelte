<script lang="ts">
  import { goto } from "$app/navigation";
  import { useAuth } from "$stores/auth.svelte";
  import { useNotifications } from "$stores/notifications.svelte";
  import { wasRememberMeSelected } from "$api/client";
  import { fly } from "svelte/transition";
  import * as Card from "$lib/components/ui/card";
  import { Button } from "$lib/components/ui/button";
  import { Input } from "$lib/components/ui/input";
  import { Label } from "$lib/components/ui/label";

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

<div in:fly={{ y: 20, duration: 400, delay: 100 }}>
  <Card.Root class="w-full shadow-lg">
    <Card.Header class="space-y-1 text-center">
      <div class="mx-auto mb-2 flex h-12 w-12 items-center justify-center rounded-lg bg-primary text-primary-foreground">
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
      <Card.Title class="text-2xl font-bold">欢迎回来</Card.Title>
      <Card.Description>登录您的存证平台账户</Card.Description>
    </Card.Header>
    <Card.Content>
      <form onsubmit={handleSubmit} class="space-y-4">
        <div class="space-y-2">
          <Label for="username">用户名</Label>
          <Input
            id="username"
            type="text"
            bind:value={username}
            placeholder="请输入用户名"
            disabled={isSubmitting}
            required
          />
        </div>

        <div class="space-y-2">
          <Label for="password">密码</Label>
          <Input
            id="password"
            type="password"
            bind:value={password}
            placeholder="请输入密码"
            disabled={isSubmitting}
            required
          />
        </div>

        <div class="flex items-center justify-between">
          <div class="flex items-center space-x-2">
            <input
              type="checkbox"
              id="remember"
              bind:checked={rememberMe}
              class="h-4 w-4 rounded border-primary text-primary focus:ring-primary"
              disabled={isSubmitting}
            />
            <Label for="remember" class="text-sm font-normal cursor-pointer">记住我</Label>
          </div>
          <a href="/reset-password" class="text-sm text-primary hover:underline">
            忘记密码？
          </a>
        </div>

        <Button type="submit" class="w-full" disabled={isSubmitting}>
          {#if isSubmitting}
            <svg class="mr-2 h-4 w-4 animate-spin" viewBox="0 0 24 24">
              <circle class="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" stroke-width="4" fill="none"></circle>
              <path class="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4zm2 5.291A7.962 7.962 0 014 12H0c0 3.042 1.135 5.824 3 7.938l3-2.647z"></path>
            </svg>
            登录中...
          {:else}
            登录
          {/if}
        </Button>
      </form>
    </Card.Content>
    <Card.Footer class="justify-center border-t p-4">
      <div class="text-sm text-muted-foreground">
        还没有账户？
        <a href="/register" class="text-primary font-medium hover:underline">立即注册</a>
      </div>
    </Card.Footer>
  </Card.Root>
</div>
