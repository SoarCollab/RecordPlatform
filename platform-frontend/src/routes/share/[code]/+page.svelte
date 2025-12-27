<script lang="ts">
  import { onMount } from "svelte";
  import { goto } from "$app/navigation";
  import { useAuth } from "$stores/auth.svelte";
  import { useNotifications } from "$stores/notifications.svelte";
  import { useDownload } from "$stores/download.svelte";
  import { formatFileSize } from "$utils/format";
  import { ApiError } from "$api/client";
  import {
    getSharedFiles,
    publicGetDecryptInfo,
    saveSharedFiles,
  } from "$api/endpoints/files";
  import { ResultCode, type SharedFileVO } from "$api/types";
  import * as Card from "$lib/components/ui/card";
  import { Button } from "$lib/components/ui/button";
  import { Badge } from "$lib/components/ui/badge";

  let { data } = $props();

  const auth = useAuth();
  const notifications = useNotifications();
  const download = useDownload();

  let files = $state<SharedFileVO[]>([]);
  let loading = $state(true);
  let error = $state<string | null>(null);
  let selectedFiles = $state<Set<string>>(new Set());
  let isSaving = $state(false);
  let isCancelled = $state(false);
  let isExpired = $state(false);

  const allSelected = $derived(
    files.length > 0 && selectedFiles.size === files.length
  );

  onMount(() => {
    loadSharedFiles();
  });

  async function loadSharedFiles() {
    loading = true;
    error = null;
    isCancelled = false;
    isExpired = false;

    try {
      files = await getSharedFiles(data.code);
    } catch (err) {
      if (err instanceof ApiError) {
        if (err.code === ResultCode.SHARE_CANCELLED) {
          isCancelled = true;
          error = "此分享链接已被取消";
        } else if (err.code === ResultCode.SHARE_EXPIRED) {
          isExpired = true;
          error = "此分享链接已过期";
        } else {
          error = err.message || "分享链接无效";
        }
      } else {
        error = err instanceof Error ? err.message : "分享链接无效";
      }
    } finally {
      loading = false;
    }
  }

  function toggleSelect(fileId: string) {
    const newSet = new Set(selectedFiles);
    if (newSet.has(fileId)) {
      newSet.delete(fileId);
    } else {
      newSet.add(fileId);
    }
    selectedFiles = newSet;
  }

  function toggleSelectAll() {
    if (allSelected) {
      selectedFiles = new Set();
    } else {
      selectedFiles = new Set(files.map((f) => f.id));
    }
  }

  // 处理分享文件下载：未登录时先验证是否为公开分享，私密分享引导登录
  async function handleDownload(file: SharedFileVO) {
    if (!auth.isAuthenticated) {
      try {
        await publicGetDecryptInfo(data.code, file.fileHash);
      } catch (err) {
        const needsLogin =
          err instanceof ApiError &&
          (err.code === ResultCode.PERMISSION_UNAUTHORIZED ||
            err.code === ResultCode.PERMISSION_UNAUTHENTICATED ||
            err.code === ResultCode.USER_NOT_LOGGED_IN) &&
          err.message.includes("登录");
        if (needsLogin) {
          notifications.info("请先登录", "私密分享需要登录后才能下载");
          await goto(`/login?redirect=/share/${data.code}`);
          return;
        }

        notifications.error(
          "下载失败",
          err instanceof Error ? err.message : "请稍后重试"
        );
        return;
      }
    }

    // Shared files use backend proxy (no presigned URLs available)
    // Try public share first, fallback to private share if user is authenticated
    const sourceType = auth.isAuthenticated ? "private_share" : "public_share";

    download.startDownload(file.fileHash, file.fileName, {
      type: sourceType,
      shareCode: data.code,
    });

    notifications.info("下载已开始", "可在右下角查看下载进度");
  }

  async function handleSaveSelected() {
    if (selectedFiles.size === 0) {
      notifications.warning("请选择要保存的文件");
      return;
    }

    if (!auth.isAuthenticated) {
      notifications.info("请先登录", "登录后即可保存文件到您的账户");
      await goto(`/login?redirect=/share/${data.code}`);
      return;
    }

    isSaving = true;
    try {
      await saveSharedFiles({
        sharingFileIdList: Array.from(selectedFiles),
      });
      notifications.success(
        "保存成功",
        `已将 ${selectedFiles.size} 个文件保存到您的账户`
      );
      selectedFiles = new Set();
    } catch (err) {
      notifications.error(
        "保存失败",
        err instanceof Error ? err.message : "请稍后重试"
      );
    } finally {
      isSaving = false;
    }
  }
</script>

<svelte:head>
  <title>分享文件 - 存证平台</title>
</svelte:head>

<div class="min-h-screen bg-muted/30">
  <div class="mx-auto max-w-4xl p-6">
    <!-- Header -->
    <div class="mb-8 text-center">
      <div
        class="mx-auto mb-4 flex h-16 w-16 items-center justify-center rounded-full bg-primary text-primary-foreground"
      >
        <svg
          class="h-8 w-8"
          fill="none"
          stroke="currentColor"
          viewBox="0 0 24 24"
        >
          <path
            stroke-linecap="round"
            stroke-linejoin="round"
            stroke-width="2"
            d="M8.684 13.342C8.886 12.938 9 12.482 9 12c0-.482-.114-.938-.316-1.342m0 2.684a3 3 0 110-2.684m0 2.684l6.632 3.316m-6.632-6l6.632-3.316m0 0a3 3 0 105.367-2.684 3 3 0 00-5.367 2.684zm0 9.316a3 3 0 105.368 2.684 3 3 0 00-5.368-2.684z"
          />
        </svg>
      </div>
      <h1 class="text-2xl font-bold">分享文件</h1>
      <p class="text-muted-foreground">有人与您分享了以下文件</p>
    </div>

    {#if loading}
      <Card.Root>
        <Card.Content class="flex items-center justify-center p-12">
          <div
            class="h-8 w-8 animate-spin rounded-full border-4 border-primary border-t-transparent"
          ></div>
        </Card.Content>
      </Card.Root>
    {:else if error}
      <Card.Root>
        <Card.Content class="p-12 text-center">
          <div
            class="mx-auto mb-4 flex h-16 w-16 items-center justify-center rounded-full {isCancelled
              ? 'bg-orange-500/10'
              : isExpired
                ? 'bg-blue-500/10'
                : 'bg-destructive/10'}"
          >
            {#if isCancelled}
              <!-- 禁止图标 - 分享已取消 -->
              <svg
                class="h-8 w-8 text-orange-500"
                fill="none"
                stroke="currentColor"
                viewBox="0 0 24 24"
              >
                <path
                  stroke-linecap="round"
                  stroke-linejoin="round"
                  stroke-width="2"
                  d="M18.364 18.364A9 9 0 005.636 5.636m12.728 12.728A9 9 0 015.636 5.636m12.728 12.728L5.636 5.636"
                />
              </svg>
            {:else if isExpired}
              <!-- 时钟图标 - 分享已过期 -->
              <svg
                class="h-8 w-8 text-blue-500"
                fill="none"
                stroke="currentColor"
                viewBox="0 0 24 24"
              >
                <path
                  stroke-linecap="round"
                  stroke-linejoin="round"
                  stroke-width="2"
                  d="M12 8v4l3 3m6-3a9 9 0 11-18 0 9 9 0 0118 0z"
                />
              </svg>
            {:else}
              <!-- 警告图标 - 其他错误 -->
              <svg
                class="h-8 w-8 text-destructive"
                fill="none"
                stroke="currentColor"
                viewBox="0 0 24 24"
              >
                <path
                  stroke-linecap="round"
                  stroke-linejoin="round"
                  stroke-width="2"
                  d="M12 9v2m0 4h.01m-6.938 4h13.856c1.54 0 2.502-1.667 1.732-3L13.732 4c-.77-1.333-2.694-1.333-3.464 0L3.34 16c-.77 1.333.192 3 1.732 3z"
                />
              </svg>
            {/if}
          </div>
          <p class="font-medium">
            {isCancelled ? "分享已取消" : isExpired ? "分享已过期" : "分享链接无效"}
          </p>
          <p class="mt-1 text-muted-foreground">{error}</p>
          <div class="mt-6 flex justify-center gap-4">
            <Button variant="outline" onclick={() => goto("/")}>
              返回首页
            </Button>
            {#if !auth.isAuthenticated}
              <Button onclick={() => goto("/login")}>登录</Button>
            {/if}
          </div>
        </Card.Content>
      </Card.Root>
    {:else if files.length === 0}
      <Card.Root>
        <Card.Content class="p-12 text-center">
          <div
            class="mx-auto mb-4 flex h-16 w-16 items-center justify-center rounded-full bg-muted"
          >
            <svg
              class="h-8 w-8 text-muted-foreground"
              fill="none"
              stroke="currentColor"
              viewBox="0 0 24 24"
            >
              <path
                stroke-linecap="round"
                stroke-linejoin="round"
                stroke-width="2"
                d="M3 7v10a2 2 0 002 2h14a2 2 0 002-2V9a2 2 0 00-2-2h-6l-2-2H5a2 2 0 00-2 2z"
              />
            </svg>
          </div>
          <p class="text-muted-foreground">此分享链接暂无文件</p>
        </Card.Content>
      </Card.Root>
    {:else}
      <Card.Root>
        <Card.Header>
          <div class="flex items-center justify-between">
            <div class="flex items-center gap-4">
              <label class="flex cursor-pointer items-center gap-2">
                <input
                  type="checkbox"
                  checked={allSelected}
                  onchange={toggleSelectAll}
                  class="h-4 w-4 rounded border-border"
                />
                <span class="text-sm">全选</span>
              </label>
              <Badge variant="secondary">{files.length} 个文件</Badge>
            </div>
            {#if selectedFiles.size > 0}
              <Button onclick={handleSaveSelected} disabled={isSaving}>
                <svg
                  class="mr-2 h-4 w-4"
                  fill="none"
                  stroke="currentColor"
                  viewBox="0 0 24 24"
                >
                  <path
                    stroke-linecap="round"
                    stroke-linejoin="round"
                    stroke-width="2"
                    d="M8 7H5a2 2 0 00-2 2v9a2 2 0 002 2h14a2 2 0 002-2V9a2 2 0 00-2-2h-3m-1 4l-3 3m0 0l-3-3m3 3V4"
                  />
                </svg>
                {isSaving ? "保存中..." : `保存 ${selectedFiles.size} 个文件`}
              </Button>
            {/if}
          </div>
        </Card.Header>
        <Card.Content class="p-0">
          <div class="divide-y">
            {#each files as file (file.id)}
              <div class="flex items-center gap-4 p-4 hover:bg-muted/30">
                <input
                  type="checkbox"
                  checked={selectedFiles.has(file.id)}
                  onchange={() => toggleSelect(file.id)}
                  class="h-4 w-4 rounded border-border"
                />
                <div
                  class="flex h-10 w-10 shrink-0 items-center justify-center rounded-lg bg-primary/10 text-primary"
                >
                  <svg
                    class="h-5 w-5"
                    fill="none"
                    stroke="currentColor"
                    viewBox="0 0 24 24"
                  >
                    <path
                      stroke-linecap="round"
                      stroke-linejoin="round"
                      stroke-width="2"
                      d="M9 12h6m-6 4h6m2 5H7a2 2 0 01-2-2V5a2 2 0 012-2h5.586a1 1 0 01.707.293l5.414 5.414a1 1 0 01.293.707V19a2 2 0 01-2 2z"
                    />
                  </svg>
                </div>
                <div class="min-w-0 flex-1">
                  <p class="truncate font-medium">{file.fileName}</p>
                  <p class="text-sm text-muted-foreground">
                    {formatFileSize(file.fileSize)}
                    {#if file.ownerName}
                      · 来自 {file.ownerName}
                    {/if}
                  </p>
                </div>
                <Button
                  variant="outline"
                  size="sm"
                  onclick={() => handleDownload(file)}
                >
                  <svg
                    class="mr-2 h-4 w-4"
                    fill="none"
                    stroke="currentColor"
                    viewBox="0 0 24 24"
                  >
                    <path
                      stroke-linecap="round"
                      stroke-linejoin="round"
                      stroke-width="2"
                      d="M4 16v1a3 3 0 003 3h10a3 3 0 003-3v-1m-4-4l-4 4m0 0l-4-4m4 4V4"
                    />
                  </svg>
                  下载
                </Button>
              </div>
            {/each}
          </div>
        </Card.Content>
      </Card.Root>

      <!-- Login prompt for unauthenticated users -->
      {#if !auth.isAuthenticated}
        <Card.Root class="mt-6">
          <Card.Content class="flex items-center justify-between p-6">
            <div>
              <p class="font-medium">登录后可保存文件</p>
              <p class="text-sm text-muted-foreground">
                将文件保存到您的账户，方便日后管理
              </p>
            </div>
            <Button onclick={() => goto(`/login?redirect=/share/${data.code}`)}>
              立即登录
            </Button>
          </Card.Content>
        </Card.Root>
      {/if}
    {/if}

    <!-- Footer -->
    <div class="mt-8 text-center">
      <a href="/" class="text-sm text-muted-foreground hover:text-foreground">
        了解更多关于存证平台
      </a>
    </div>
  </div>
</div>
