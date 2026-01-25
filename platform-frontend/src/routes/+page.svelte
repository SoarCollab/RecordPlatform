<script lang="ts">
  import { goto } from "$app/navigation";
  import { fly } from "svelte/transition";
  import logo from "$lib/assets/logo.png";

  const features = [
    {
      icon: "shield-check",
      title: "区块链存证",
      description: "基于 FISCO BCOS 联盟链，文件哈希永久上链，不可篡改",
    },
    {
      icon: "lock",
      title: "安全加密",
      description: "文件分片加密存储，多节点冗余备份，数据安全有保障",
    },
    {
      icon: "share-2",
      title: "便捷分享",
      description: "一键生成分享链接，支持密码保护和有效期设置",
    },
    {
      icon: "activity",
      title: "实时追踪",
      description: "完整的操作审计日志，文件状态实时追踪",
    },
  ];
</script>

<svelte:head>
  <title>存证平台 - 区块链文件存证服务</title>
</svelte:head>

<div class="flex min-h-screen flex-col">
  <!-- 页头 -->
  <header class="border-b bg-card">
    <div class="container mx-auto flex h-16 items-center justify-between px-4">
      <div class="flex items-center gap-2">
        <img src={logo} alt="Logo" class="h-8 w-8 rounded-lg" />
        <span class="text-xl font-bold">存证平台</span>
      </div>
      <nav class="flex items-center gap-4">
        <button
          class="rounded-lg px-4 py-2 text-sm font-medium hover:bg-accent"
          onclick={() => goto("/login")}
        >
          登录
        </button>
        <button
          class="rounded-lg bg-primary px-4 py-2 text-sm font-medium text-primary-foreground hover:bg-primary/90"
          onclick={() => goto("/register")}
        >
          注册
        </button>
      </nav>
    </div>
  </header>

  <!-- 主视觉 -->
  <main class="flex-1">
    <section class="container mx-auto px-4 py-20 text-center">
      <h1
        class="mb-4 text-4xl font-bold tracking-tight sm:text-5xl md:text-6xl"
      >
        安全可靠的
        <span class="text-primary">区块链存证</span>
        服务
      </h1>
      <p class="mx-auto mb-8 max-w-2xl text-lg text-muted-foreground">
        基于联盟链技术，为您的重要文件提供不可篡改的存证服务。上传即上链，安全永久保存。
      </p>
      <div class="flex justify-center gap-4">
        <button
          class="rounded-lg bg-primary px-6 py-3 text-sm font-medium text-primary-foreground hover:bg-primary/90"
          onclick={() => goto("/register")}
        >
          立即开始
        </button>
        <button
          class="rounded-lg border px-6 py-3 text-sm font-medium hover:bg-accent"
          onclick={() =>
            document
              .getElementById("features")
              ?.scrollIntoView({ behavior: "smooth" })}
        >
          了解更多
        </button>
      </div>
    </section>

    <!-- 功能特性 -->
    <section id="features" class="border-t bg-muted/50 py-20">
      <div class="container mx-auto px-4">
        <h2 class="mb-12 text-center text-3xl font-bold">核心功能</h2>
        <div class="grid gap-8 sm:grid-cols-2 lg:grid-cols-4">
          {#each features as feature, i}
            <div
              class="rounded-lg border bg-card p-6"
              in:fly={{ y: 20, duration: 400, delay: i * 100 }}
            >
              <div
                class="mb-4 flex h-12 w-12 items-center justify-center rounded-lg bg-primary/10 text-primary"
              >
                <svg
                  class="h-6 w-6"
                  fill="none"
                  stroke="currentColor"
                  viewBox="0 0 24 24"
                >
                  {#if feature.icon === "shield-check"}
                    <path
                      stroke-linecap="round"
                      stroke-linejoin="round"
                      stroke-width="2"
                      d="M9 12l2 2 4-4m5.618-4.016A11.955 11.955 0 0112 2.944a11.955 11.955 0 01-8.618 3.04A12.02 12.02 0 003 9c0 5.591 3.824 10.29 9 11.622 5.176-1.332 9-6.03 9-11.622 0-1.042-.133-2.052-.382-3.016z"
                    />
                  {:else if feature.icon === "lock"}
                    <path
                      stroke-linecap="round"
                      stroke-linejoin="round"
                      stroke-width="2"
                      d="M12 15v2m-6 4h12a2 2 0 002-2v-6a2 2 0 00-2-2H6a2 2 0 00-2 2v6a2 2 0 002 2zm10-10V7a4 4 0 00-8 0v4h8z"
                    />
                  {:else if feature.icon === "share-2"}
                    <path
                      stroke-linecap="round"
                      stroke-linejoin="round"
                      stroke-width="2"
                      d="M8.684 13.342C8.886 12.938 9 12.482 9 12c0-.482-.114-.938-.316-1.342m0 2.684a3 3 0 110-2.684m0 2.684l6.632 3.316m-6.632-6l6.632-3.316m0 0a3 3 0 105.367-2.684 3 3 0 00-5.367 2.684zm0 9.316a3 3 0 105.368 2.684 3 3 0 00-5.368-2.684z"
                    />
                  {:else if feature.icon === "activity"}
                    <path
                      stroke-linecap="round"
                      stroke-linejoin="round"
                      stroke-width="2"
                      d="M9 19v-6a2 2 0 00-2-2H5a2 2 0 00-2 2v6a2 2 0 002 2h2a2 2 0 002-2zm0 0V9a2 2 0 012-2h2a2 2 0 012 2v10m-6 0a2 2 0 002 2h2a2 2 0 002-2m0 0V5a2 2 0 012-2h2a2 2 0 012 2v14a2 2 0 01-2 2h-2a2 2 0 01-2-2z"
                    />
                  {/if}
                </svg>
              </div>
              <h3 class="mb-2 font-semibold">{feature.title}</h3>
              <p class="text-sm text-muted-foreground">{feature.description}</p>
            </div>
          {/each}
        </div>
      </div>
    </section>
  </main>

  <!-- 页脚 -->
  <footer class="border-t py-8">
    <div
      class="container mx-auto px-4 text-center text-sm text-muted-foreground"
    >
      <p>&copy; 2024 存证平台. All rights reserved.</p>
    </div>
  </footer>
</div>
