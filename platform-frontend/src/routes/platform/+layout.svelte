<script lang="ts">
  import { onMount, type Snippet } from "svelte";
  import { page } from "$app/stores";
  import { goto } from "$app/navigation";
  import { useAuth } from "$stores/auth.svelte";
  import { subscribeCredentialChanges } from "$api/client";
  import {
    platformNavigation,
    platformCapabilityForPath,
  } from "$lib/config/platformNavigation";
  import { requirePlatformSession } from "$utils/platformAccess";
  import { Button } from "$components/ui/button";
  import { toggleMode } from "mode-watcher";
  import logo from "$lib/assets/logo.png";

  let { children }: { children: Snippet } = $props();
  const auth = useAuth();
  let validationError = $state("");
  let validationGeneration = 0;
  const capability = $derived(platformCapabilityForPath($page.url.pathname));
  const allowed = $derived(
    auth.isPlatformAdmin &&
      (!capability || auth.hasPlatformCapability(capability)),
  );

  /** Revalidate changed credentials without rendering protected children on failure. */
  async function validate(): Promise<void> {
    const generation = ++validationGeneration;
    validationError = "";
    try {
      await requirePlatformSession();
    } catch (failure) {
      if (generation !== validationGeneration) return;
      if (failure instanceof DOMException && failure.name === "AbortError")
        return;
      if (
        failure &&
        typeof failure === "object" &&
        "location" in failure &&
        typeof failure.location === "string"
      ) {
        await goto(failure.location, { replaceState: true });
      } else {
        validationError = "无法验证当前平台会话，请重试或重新登录。";
      }
    }
  }

  onMount(() => {
    void validate();
    const unsubscribe = subscribeCredentialChanges(() => {
      void validate();
    });
    return () => {
      validationGeneration += 1;
      unsubscribe();
    };
  });
</script>

<svelte:head><title>平台管理 - 存证平台</title></svelte:head>

{#if allowed}
  <div class="bg-muted/20 min-h-screen">
    <header class="bg-card border-b">
      <div
        class="mx-auto flex max-w-7xl items-center justify-between gap-4 px-5 py-4"
      >
        <a href="/platform" class="flex items-center gap-3 font-semibold"
          ><img
            src={logo}
            alt="存证平台"
            class="h-8 w-8 rounded-lg"
          />平台管理</a
        >
        <div class="flex items-center gap-3 text-sm">
          <span class="text-muted-foreground">{auth.displayName}</span><Button
            variant="ghost"
            size="sm"
            onclick={toggleMode}>切换主题</Button
          ><Button
            variant="outline"
            size="sm"
            onclick={() => auth.logout()}
            disabled={auth.isLoading}>退出登录</Button
          >
        </div>
      </div>
      <nav
        aria-label="平台管理导航"
        class="mx-auto flex max-w-7xl gap-1 overflow-x-auto px-5"
      >
        {#each platformNavigation.filter( (item) => auth.hasPlatformCapability(item.capability), ) as item (item.href)}
          <a
            href={item.href}
            aria-current={capability === item.capability ? "page" : undefined}
            class="border-b-2 px-4 py-3 text-sm whitespace-nowrap {capability ===
            item.capability
              ? 'border-primary text-primary font-medium'
              : 'text-muted-foreground hover:text-foreground border-transparent'}"
            >{item.label}</a
          >
        {/each}
      </nav>
    </header>
    <main class="mx-auto max-w-7xl px-5 py-7">{@render children()}</main>
  </div>
{:else}
  <main
    class="mx-auto flex min-h-screen max-w-md flex-col items-center justify-center gap-4 px-6 text-center"
  >
    <h1 class="text-xl font-semibold">
      {auth.isPlatformAdmin ? "没有页面访问权限" : "正在验证平台会话"}
    </h1>
    {#if validationError || auth.error}<p
        role="alert"
        class="text-destructive text-sm"
      >
        {validationError || auth.error}
      </p>
      <Button onclick={validate}>重试</Button><Button
        variant="outline"
        onclick={() => auth.logout()}>重新登录</Button
      >{/if}
    {#if auth.isPlatformAdmin}<p class="text-muted-foreground text-sm">
        当前账号没有此页面所需的权限。
      </p>
      <Button variant="outline" onclick={() => auth.logout()}>退出登录</Button
      >{/if}
  </main>
{/if}
