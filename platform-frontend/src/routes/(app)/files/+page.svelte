<script lang="ts">
  import { onMount } from "svelte";
  import { goto } from "$app/navigation";
  import { useNotifications } from "$stores/notifications.svelte";
  import { useDownload } from "$stores/download.svelte";
  import { useUpload } from "$stores/upload.svelte";
  import { formatFileSize, formatDateTime } from "$utils/format";
  import { getFiles, deleteFile, createShare } from "$api/endpoints/files";
  import {
    FileStatus,
    FileStatusLabel,
    ShareType,
    ShareTypeLabel,
    ShareTypeDesc,
    type FileVO,
  } from "$api/types";
  import { fly } from "svelte/transition";
  import Skeleton from "$lib/components/ui/Skeleton.svelte";

  const notifications = useNotifications();
  const download = useDownload();
  const upload = useUpload();

  let files = $state<FileVO[]>([]);
  let loading = $state(true);
  let page = $state(1);
  let total = $state(0);
  let pageSize = $state(10);
  let keyword = $state("");
  let statusFilter = $state<FileStatus | undefined>(undefined);

  // Share dialog state
  let shareDialogOpen = $state(false);
  let shareFile = $state<FileVO | null>(null);
  let shareExpireHours = $state(72);
  let shareType = $state<ShareType>(ShareType.PUBLIC);
  let shareCode = $state("");

  onMount(() => {
    loadFiles();
  });

  async function loadFiles(silent = false) {
    if (!silent) loading = true;
    try {
      const result = await getFiles({
        pageNum: page,
        pageSize,
        keyword: keyword || undefined,
        status: statusFilter,
      });
      files = result.records;
      total = result.total;
    } catch (err) {
      if (!silent)
        notifications.error(
          "加载失败",
          err instanceof Error ? err.message : "请稍后重试"
        );
    } finally {
      if (!silent) loading = false;
    }
  }

  // Auto-refresh logic
  let pollInterval: ReturnType<typeof setInterval> | undefined;

  $effect(() => {
    // Check if any files are processing
    const hasProcessing = files.some((f) => f.status === FileStatus.PROCESSING);

    if (hasProcessing) {
      if (!pollInterval) {
        pollInterval = setInterval(() => loadFiles(true), 3000);
      }
    } else {
      if (pollInterval) {
        clearInterval(pollInterval);
        pollInterval = undefined;
      }
    }

    // Cleanup when effect is destroyed
    return () => {
      if (pollInterval) {
        clearInterval(pollInterval);
        pollInterval = undefined;
      }
    };
  });

  // Refresh when an upload completes
  $effect(() => {
    if (upload.completedTasks.length > 0) {
      loadFiles(true);
    }
  });

  async function handleDelete(file: FileVO) {
    if (!confirm(`确定要删除文件 "${file.fileName}" 吗？`)) return;

    try {
      await deleteFile(file.id);
      notifications.success("删除成功");
      await loadFiles();
    } catch (err) {
      notifications.error(
        "删除失败",
        err instanceof Error ? err.message : "请稍后重试"
      );
    }
  }

  async function handleDownload(file: FileVO) {
    // Use the new download manager with presigned URLs
    download.startDownload(file.fileHash, file.fileName, { type: "owned" });
    notifications.info("下载已开始", "可在右下角查看下载进度");
  }

  function openShareDialog(file: FileVO) {
    shareFile = file;
    shareCode = "";
    shareType = ShareType.PUBLIC;
    shareDialogOpen = true;
  }

  async function handleShare() {
    if (!shareFile) return;

    try {
      // createShare 现在接受对象参数：fileHash + expireMinutes + shareType
      shareCode = await createShare({
        fileHash: [shareFile.fileHash],
        expireMinutes: shareExpireHours * 60,
        shareType,
      });
      notifications.success("分享链接已创建");
    } catch (err) {
      notifications.error(
        "创建分享失败",
        err instanceof Error ? err.message : "请稍后重试"
      );
    }
  }

  function copyShareLink() {
    const link = `${window.location.origin}/share/${shareCode}`;
    navigator.clipboard.writeText(link);
    notifications.success("已复制到剪贴板");
  }

  function getStatusClass(status: FileStatus): string {
    switch (status) {
      case FileStatus.COMPLETED:
        return "bg-green-100 text-green-700";
      case FileStatus.FAILED:
        return "bg-red-100 text-red-700";
      default:
        return "bg-yellow-100 text-yellow-700";
    }
  }
</script>

<svelte:head>
  <title>文件管理 - 存证平台</title>
</svelte:head>

<div class="space-y-6">
  <div class="flex items-center justify-between">
    <div>
      <h1 class="text-2xl font-bold">文件管理</h1>
      <p class="text-muted-foreground">管理您的存证文件</p>
    </div>
    <a
      href="/upload"
      class="flex items-center gap-2 rounded-lg bg-primary px-4 py-2 text-sm font-medium text-primary-foreground hover:bg-primary/90"
    >
      <svg
        class="h-4 w-4"
        fill="none"
        stroke="currentColor"
        viewBox="0 0 24 24"
      >
        <path
          stroke-linecap="round"
          stroke-linejoin="round"
          stroke-width="2"
          d="M12 4v16m8-8H4"
        />
      </svg>
      上传文件
    </a>
  </div>

  <!-- Active Uploads -->
  {#if upload.activeTasks.length > 0}
    <div class="rounded-lg border bg-blue-50/50 p-4 dark:bg-blue-950/20">
      <div class="mb-3 flex items-center justify-between">
        <h3 class="text-sm font-medium text-blue-900 dark:text-blue-100">
          正在上传 ({upload.activeTasks.length})
        </h3>
        <span class="text-xs text-blue-700 dark:text-blue-300">
          总进度 {upload.totalProgress}%
        </span>
      </div>
      <div class="space-y-3">
        {#each upload.activeTasks as task (task.id)}
          <div>
            <div class="mb-1 flex justify-between text-xs">
              <span
                class="truncate font-medium text-blue-800 dark:text-blue-200"
                >{task.file.name}</span
              >
              <span class="text-blue-600 dark:text-blue-400"
                >{task.progress}%</span
              >
            </div>
            <div
              class="h-1.5 overflow-hidden rounded-full bg-blue-200 dark:bg-blue-900"
            >
              <div
                class="h-full bg-blue-500 transition-all duration-300 dark:bg-blue-400"
                style="width: {task.progress}%"
              ></div>
            </div>
          </div>
        {/each}
      </div>
    </div>
  {/if}

  <!-- Filters -->
  <div class="flex flex-wrap gap-4">
    <input
      type="text"
      placeholder="搜索文件名..."
      bind:value={keyword}
      onkeypress={(e) => e.key === "Enter" && loadFiles()}
      class="rounded-lg border bg-background px-3 py-2 text-sm focus:border-primary focus:outline-none"
    />
    <select
      bind:value={statusFilter}
      onchange={() => loadFiles()}
      class="rounded-lg border bg-background px-3 py-2 text-sm focus:border-primary focus:outline-none"
    >
      <option value={undefined}>全部状态</option>
      {#each Object.entries(FileStatusLabel) as [value, label]}
        <option value={Number(value)}>{label}</option>
      {/each}
    </select>
    <button
      class="rounded-lg border px-4 py-2 text-sm hover:bg-accent"
      onclick={() => loadFiles()}
    >
      搜索
    </button>
  </div>

  <!-- File list -->
  <div class="rounded-lg border bg-card">
    {#if loading}
      <div class="space-y-4 p-6">
        {#each Array(5) as _}
          <div class="flex items-center gap-4">
            <Skeleton class="h-12 w-12 rounded-lg" />
            <div class="flex-1 space-y-2">
              <Skeleton class="h-4 w-1/3" />
              <Skeleton class="h-4 w-1/4" />
            </div>
            <Skeleton class="h-8 w-24" />
          </div>
        {/each}
      </div>
    {:else if files.length === 0}
      <div class="p-12 text-center">
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
        <p class="text-muted-foreground">暂无文件</p>
        <a href="/upload" class="mt-2 inline-block text-primary hover:underline"
          >立即上传</a
        >
      </div>
    {:else}
      <div class="overflow-x-auto">
        <table class="w-full">
          <thead class="border-b bg-muted/50">
            <tr>
              <th class="px-4 py-3 text-left text-sm font-medium">文件名</th>
              <th class="px-4 py-3 text-left text-sm font-medium">大小</th>
              <th class="px-4 py-3 text-left text-sm font-medium">状态</th>
              <th class="px-4 py-3 text-left text-sm font-medium">上传时间</th>
              <th class="px-4 py-3 text-right text-sm font-medium">操作</th>
            </tr>
          </thead>
          <tbody class="divide-y">
            {#each files as file, i (file.id)}
              <tr
                class="hover:bg-muted/30"
                in:fly={{ y: 20, duration: 300, delay: i * 50 }}
              >
                <td class="px-4 py-3">
                  <div class="flex items-center gap-3">
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
                    <div class="min-w-0">
                      <p class="truncate font-medium">{file.fileName}</p>
                      {#if file.transactionHash}
                        <p
                          class="truncate text-xs text-muted-foreground"
                          title={file.transactionHash}
                        >
                          TX: {file.transactionHash.slice(0, 16)}...
                        </p>
                      {/if}
                    </div>
                  </div>
                </td>
                <td class="px-4 py-3 text-sm text-muted-foreground">
                  {formatFileSize(file.fileSize)}
                </td>
                <td class="px-4 py-3">
                  <span
                    class="rounded-full px-2 py-1 text-xs {getStatusClass(
                      file.status
                    )}"
                  >
                    {FileStatusLabel[file.status]}
                  </span>
                </td>
                <td class="px-4 py-3 text-sm text-muted-foreground">
                  {formatDateTime(file.createTime)}
                </td>
                <td class="px-4 py-3">
                  <div class="flex justify-end gap-2">
                    <button
                      class="rounded p-1 hover:bg-accent"
                      onclick={() => goto(`/files/${file.fileHash}`)}
                      title="详情"
                    >
                      <svg
                        class="h-4 w-4"
                        fill="none"
                        stroke="currentColor"
                        viewBox="0 0 24 24"
                      >
                        <path
                          stroke-linecap="round"
                          stroke-linejoin="round"
                          stroke-width="2"
                          d="M15 12a3 3 0 11-6 0 3 3 0 016 0z"
                        />
                        <path
                          stroke-linecap="round"
                          stroke-linejoin="round"
                          stroke-width="2"
                          d="M2.458 12C3.732 7.943 7.523 5 12 5c4.478 0 8.268 2.943 9.542 7-1.274 4.057-5.064 7-9.542 7-4.477 0-8.268-2.943-9.542-7z"
                        />
                      </svg>
                    </button>
                    {#if file.status === FileStatus.COMPLETED}
                      {@const isDownloading = download.tasks.some(
                        (t) =>
                          t.fileHash === file.fileHash &&
                          t.status !== "completed" &&
                          t.status !== "failed" &&
                          t.status !== "cancelled"
                      )}
                      <button
                        class="rounded p-1 hover:bg-accent disabled:opacity-50"
                        onclick={() => handleDownload(file)}
                        disabled={isDownloading}
                        title={isDownloading ? "下载中..." : "下载"}
                      >
                        {#if isDownloading}
                          <svg class="h-4 w-4 animate-spin" viewBox="0 0 24 24">
                            <circle
                              class="opacity-25"
                              cx="12"
                              cy="12"
                              r="10"
                              stroke="currentColor"
                              stroke-width="4"
                              fill="none"
                            ></circle>
                            <path
                              class="opacity-75"
                              fill="currentColor"
                              d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4zm2 5.291A7.962 7.962 0 014 12H0c0 3.042 1.135 5.824 3 7.938l3-2.647z"
                            ></path>
                          </svg>
                        {:else}
                          <svg
                            class="h-4 w-4"
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
                        {/if}
                      </button>
                      <button
                        class="rounded p-1 hover:bg-accent"
                        onclick={() => openShareDialog(file)}
                        title="分享"
                      >
                        <svg
                          class="h-4 w-4"
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
                      </button>
                    {/if}
                    <button
                      class="rounded p-1 text-destructive hover:bg-destructive/10"
                      onclick={() => handleDelete(file)}
                      title="删除"
                    >
                      <svg
                        class="h-4 w-4"
                        fill="none"
                        stroke="currentColor"
                        viewBox="0 0 24 24"
                      >
                        <path
                          stroke-linecap="round"
                          stroke-linejoin="round"
                          stroke-width="2"
                          d="M19 7l-.867 12.142A2 2 0 0116.138 21H7.862a2 2 0 01-1.995-1.858L5 7m5 4v6m4-6v6m1-10V4a1 1 0 00-1-1h-4a1 1 0 00-1 1v3M4 7h16"
                        />
                      </svg>
                    </button>
                  </div>
                </td>
              </tr>
            {/each}
          </tbody>
        </table>
      </div>

      <!-- Pagination -->
      {#if total > pageSize}
        <div class="flex items-center justify-between border-t p-4">
          <p class="text-sm text-muted-foreground">
            共 {total} 个文件
          </p>
          <div class="flex gap-2">
            <button
              class="rounded-lg border px-3 py-1 text-sm hover:bg-accent disabled:opacity-50"
              disabled={page <= 1}
              onclick={() => {
                page--;
                loadFiles();
              }}
            >
              上一页
            </button>
            <span class="px-3 py-1 text-sm">
              {page} / {Math.ceil(total / pageSize)}
            </span>
            <button
              class="rounded-lg border px-3 py-1 text-sm hover:bg-accent disabled:opacity-50"
              disabled={page >= Math.ceil(total / pageSize)}
              onclick={() => {
                page++;
                loadFiles();
              }}
            >
              下一页
            </button>
          </div>
        </div>
      {/if}
    {/if}
  </div>
</div>

<!-- Share Dialog -->
{#if shareDialogOpen}
  <div
    class="fixed inset-0 z-50 flex items-center justify-center bg-black/50"
    onclick={(e) => e.currentTarget === e.target && (shareDialogOpen = false)}
    onkeydown={(e) => e.key === "Escape" && (shareDialogOpen = false)}
    role="dialog"
    aria-modal="true"
    aria-labelledby="share-dialog-title"
    tabindex="-1"
  >
    <div class="w-full max-w-md rounded-lg bg-card p-6 shadow-lg">
      <h3 id="share-dialog-title" class="mb-4 text-lg font-semibold">
        分享文件
      </h3>

      {#if shareCode}
        <div class="space-y-4">
          <p class="text-sm text-muted-foreground">
            分享链接已创建，请复制以下链接：
          </p>
          <div class="flex gap-2">
            <input
              type="text"
              readonly
              value={`${window.location.origin}/share/${shareCode}`}
              class="flex-1 rounded-lg border bg-muted px-3 py-2 text-sm"
            />
            <button
              class="rounded-lg bg-primary px-4 py-2 text-sm text-primary-foreground hover:bg-primary/90"
              onclick={copyShareLink}
            >
              复制
            </button>
          </div>
        </div>
      {:else}
        <div class="space-y-4">
          <p class="text-sm text-muted-foreground">
            正在分享：{shareFile?.fileName}
          </p>

          <div>
            <label for="share-type" class="mb-2 block text-sm font-medium"
              >分享类型</label
            >
            <div class="flex gap-3">
              {#each [ShareType.PUBLIC, ShareType.PRIVATE] as type}
                <label
                  class="flex flex-1 cursor-pointer items-start gap-3 rounded-lg border p-3 transition-colors
										{shareType === type
                    ? 'border-primary bg-primary/5'
                    : 'border-border hover:bg-accent/50'}"
                >
                  <input
                    type="radio"
                    name="share-type"
                    value={type}
                    bind:group={shareType}
                    class="mt-0.5"
                  />
                  <div>
                    <div class="font-medium">{ShareTypeLabel[type]}</div>
                    <div class="text-xs text-muted-foreground">
                      {ShareTypeDesc[type]}
                    </div>
                  </div>
                </label>
              {/each}
            </div>
          </div>

          <div>
            <label for="share-expire" class="mb-2 block text-sm font-medium"
              >有效期</label
            >
            <select
              id="share-expire"
              bind:value={shareExpireHours}
              class="w-full rounded-lg border bg-background px-3 py-2 text-sm"
            >
              <option value={24}>24 小时</option>
              <option value={72}>3 天</option>
              <option value={168}>7 天</option>
              <option value={720}>30 天</option>
            </select>
          </div>
        </div>
      {/if}

      <div class="mt-6 flex justify-end gap-2">
        <button
          class="rounded-lg border px-4 py-2 text-sm hover:bg-accent"
          onclick={() => (shareDialogOpen = false)}
        >
          {shareCode ? "关闭" : "取消"}
        </button>
        {#if !shareCode}
          <button
            class="rounded-lg bg-primary px-4 py-2 text-sm text-primary-foreground hover:bg-primary/90"
            onclick={handleShare}
          >
            创建分享
          </button>
        {/if}
      </div>
    </div>
  </div>
{/if}
