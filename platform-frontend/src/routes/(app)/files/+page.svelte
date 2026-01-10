<script lang="ts">
  import { onMount } from "svelte";
  import { browser } from "$app/environment";
  import { goto } from "$app/navigation";
  import { page as appPage } from "$app/state";
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
  import Skeleton from "$lib/components/ui/Skeleton.svelte";
  import * as Card from "$lib/components/ui/card";
  import * as Table from "$lib/components/ui/table";
  import * as Dialog from "$lib/components/ui/dialog";
  import { Button } from "$lib/components/ui/button";
  import { Input } from "$lib/components/ui/input";
  import { Badge } from "$lib/components/ui/badge";
  import { Label } from "$lib/components/ui/label";

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
  let shareLink = $derived(shareCode ? `${appPage.url.origin}/share/${shareCode}` : "");
  let isSharing = $state(false);

  // Delete dialog state
  let deleteDialogOpen = $state(false);
  let deleteTarget = $state<FileVO | null>(null);
  let isDeleting = $state(false);

  onMount(() => {
    loadFiles();

    const interval = setInterval(() => {
      if (!browser) return;
      if (document.visibilityState !== "visible") return;

      const hasProcessing = files.some((f) => f.status === FileStatus.PROCESSING);
      if (hasProcessing) {
        loadFiles(true);
      }
    }, 3000);

    return () => clearInterval(interval);
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


  $effect(() => {
    if (upload.completedTasks.length > 0) {
      loadFiles(true);
    }
  });

  function openDeleteDialog(file: FileVO): void {
    deleteTarget = file;
    deleteDialogOpen = true;
  }

  async function confirmDelete(): Promise<void> {
    if (!deleteTarget) return;

    isDeleting = true;
    try {
      await deleteFile(deleteTarget.id);
      notifications.success("删除成功");
      deleteDialogOpen = false;
      deleteTarget = null;
      await loadFiles();
    } catch (err) {
      notifications.error(
        "删除失败",
        err instanceof Error ? err.message : "请稍后重试"
      );
    } finally {
      isDeleting = false;
    }
  }

  async function handleDownload(file: FileVO) {
    download.startDownload(file.fileHash, file.fileName, { type: "owned" });
    notifications.info("下载已开始", "可在右下角查看下载进度");
  }

  function openShareDialog(file: FileVO) {
    shareFile = file;
    shareCode = "";
    shareType = ShareType.PUBLIC;
    isSharing = false;
    shareDialogOpen = true;
  }

  async function handleShare() {
    if (!shareFile || isSharing) return;

    isSharing = true;
    try {
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
    } finally {
      isSharing = false;
    }
  }

  async function copyShareLink(): Promise<void> {
    if (!shareLink || !browser) return;

    try {
      await navigator.clipboard.writeText(shareLink);
      notifications.success("已复制到剪贴板");
      return;
    } catch {
      // Fallback to legacy copy flow
    }

    try {
      const textarea = document.createElement("textarea");
      textarea.value = shareLink;
      textarea.setAttribute("readonly", "");
      textarea.style.position = "fixed";
      textarea.style.top = "0";
      textarea.style.left = "0";
      textarea.style.opacity = "0";
      document.body.appendChild(textarea);
      textarea.select();
      textarea.setSelectionRange(0, textarea.value.length);

      const ok = document.execCommand("copy");
      document.body.removeChild(textarea);

      if (ok) {
        notifications.success("已复制到剪贴板");
      } else {
        notifications.warning("复制失败", "请手动复制分享链接");
      }
    } catch {
      notifications.warning("复制失败", "请手动复制分享链接");
    }
  }

  function getStatusColorClass(status: FileStatus): string {
    switch(status) {
      case FileStatus.COMPLETED: return "bg-green-100 text-green-800 dark:bg-green-900/30 dark:text-green-300 hover:bg-green-100/80";
      case FileStatus.FAILED: return "bg-red-100 text-red-800 dark:bg-red-900/30 dark:text-red-300 hover:bg-red-100/80";
      case FileStatus.PROCESSING: return "bg-yellow-100 text-yellow-800 dark:bg-yellow-900/30 dark:text-yellow-300 hover:bg-yellow-100/80";
      default: return "bg-gray-100 text-gray-800 dark:bg-gray-800 dark:text-gray-300 hover:bg-gray-100/80";
    }
  }
</script>

<svelte:head>
  <title>文件管理 - 存证平台</title>
</svelte:head>

<div class="space-y-6">
  <div class="flex items-center justify-between">
    <div>
      <h1 class="text-3xl font-bold tracking-tight">文件管理</h1>
      <p class="text-muted-foreground mt-1">管理您的存证文件</p>
    </div>
    <Button href="/upload">
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
          d="M12 4v16m8-8H4"
        />
      </svg>
      上传文件
    </Button>
  </div>

  {#if upload.activeTasks.length > 0}
    <Card.Root class="border-blue-200 bg-blue-50/50 dark:border-blue-800 dark:bg-blue-950/20">
      <Card.Content class="p-4">
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
                <span class="truncate font-medium text-blue-800 dark:text-blue-200">
                  {task.file.name}
                </span>
                <span class="text-blue-600 dark:text-blue-400">
                  {task.progress}%
                </span>
              </div>
              <div class="h-1.5 overflow-hidden rounded-full bg-blue-200 dark:bg-blue-900">
                <div
                  class="h-full bg-blue-500 transition-all duration-300 dark:bg-blue-400"
                  style="width: {task.progress}%"
                ></div>
              </div>
            </div>
          {/each}
        </div>
      </Card.Content>
    </Card.Root>
  {/if}

  <div class="flex flex-wrap gap-4">
    <div class="w-full max-w-sm">
      <Input
        placeholder="搜索文件名..."
        bind:value={keyword}
        onkeydown={(e) => {
          if (e.key === "Enter") {
            page = 1;
            loadFiles();
          }
        }}
      />
    </div>
    <select
      bind:value={statusFilter}
      onchange={() => {
        page = 1;
        loadFiles();
      }}
      class="h-10 rounded-md border border-input bg-background px-3 py-2 text-sm ring-offset-background focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2"
    >
      <option value={undefined}>全部状态</option>
      {#each Object.entries(FileStatusLabel) as [value, label]}
        <option value={Number(value)}>{label}</option>
      {/each}
    </select>
    <Button variant="outline" onclick={() => {
        page = 1;
        loadFiles();
      }}>搜索</Button>
  </div>

  <Card.Root>
    <Card.Content class="p-0">
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
          <div class="mx-auto mb-4 flex h-16 w-16 items-center justify-center rounded-full bg-muted">
            <svg class="h-8 w-8 text-muted-foreground" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M3 7v10a2 2 0 002 2h14a2 2 0 002-2V9a2 2 0 00-2-2h-6l-2-2H5a2 2 0 00-2 2z" />
            </svg>
          </div>
          <p class="text-muted-foreground">暂无文件</p>
          <Button variant="link" href="/upload" class="mt-2">立即上传</Button>
        </div>
      {:else}
        <Table.Root>
          <Table.Header>
            <Table.Row>
              <Table.Head>文件名</Table.Head>
              <Table.Head>大小</Table.Head>
              <Table.Head>状态</Table.Head>
              <Table.Head>上传时间</Table.Head>
              <Table.Head class="text-right">操作</Table.Head>
            </Table.Row>
          </Table.Header>
          <Table.Body>
            {#each files as file (file.id)}
              <Table.Row>
                <Table.Cell>
                  <div class="flex items-center gap-3">
                    <div class="flex h-10 w-10 shrink-0 items-center justify-center rounded-lg bg-primary/10 text-primary">
                      <svg class="h-5 w-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                        <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M9 12h6m-6 4h6m2 5H7a2 2 0 01-2-2V5a2 2 0 012-2h5.586a1 1 0 01.707.293l5.414 5.414a1 1 0 01.293.707V19a2 2 0 01-2 2z" />
                      </svg>
                    </div>
                    <div class="min-w-0 max-w-[200px] lg:max-w-xs">
                      <p class="truncate font-medium">{file.fileName}</p>
                      {#if file.transactionHash}
                        <p class="truncate text-xs text-muted-foreground" title={file.transactionHash}>
                          TX: {file.transactionHash.slice(0, 16)}...
                        </p>
                      {/if}
                    </div>
                  </div>
                </Table.Cell>
                <Table.Cell class="text-muted-foreground">
                  {formatFileSize(file.fileSize)}
                </Table.Cell>
                <Table.Cell>
                  <Badge class={getStatusColorClass(file.status)} variant="outline">
                    {FileStatusLabel[file.status]}
                  </Badge>
                </Table.Cell>
                <Table.Cell class="text-muted-foreground">
                  {formatDateTime(file.createTime)}
                </Table.Cell>
                <Table.Cell class="text-right">
                  <div class="flex justify-end gap-2">
                    <Button variant="ghost" size="icon" class="h-8 w-8" onclick={() => goto(`/files/${file.fileHash}`)} title="详情">
                      <svg class="h-4 w-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                        <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M15 12a3 3 0 11-6 0 3 3 0 016 0z" />
                        <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M2.458 12C3.732 7.943 7.523 5 12 5c4.478 0 8.268 2.943 9.542 7-1.274 4.057-5.064 7-9.542 7-4.477 0-8.268-2.943-9.542-7z" />
                      </svg>
                    </Button>
                    {#if file.status === FileStatus.COMPLETED}
                      {@const isDownloading = download.tasks.some(
                        (t) =>
                          t.fileHash === file.fileHash &&
                          t.status !== "completed" &&
                          t.status !== "failed" &&
                          t.status !== "cancelled"
                      )}
                      <Button variant="ghost" size="icon" class="h-8 w-8" onclick={() => handleDownload(file)} disabled={isDownloading} title={isDownloading ? "下载中..." : "下载"}>
                        {#if isDownloading}
                          <svg class="h-4 w-4 animate-spin" viewBox="0 0 24 24">
                            <circle class="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" stroke-width="4" fill="none"></circle>
                            <path class="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4zm2 5.291A7.962 7.962 0 014 12H0c0 3.042 1.135 5.824 3 7.938l3-2.647z"></path>
                          </svg>
                        {:else}
                          <svg class="h-4 w-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                            <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M4 16v1a3 3 0 003 3h10a3 3 0 003-3v-1m-4-4l-4 4m0 0l-4-4m4 4V4" />
                          </svg>
                        {/if}
                      </Button>
                      <Button variant="ghost" size="icon" class="h-8 w-8" onclick={() => openShareDialog(file)} title="分享">
                        <svg class="h-4 w-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                          <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M8.684 13.342C8.886 12.938 9 12.482 9 12c0-.482-.114-.938-.316-1.342m0 2.684a3 3 0 110-2.684m0 2.684l6.632 3.316m-6.632-6l6.632-3.316m0 0a3 3 0 105.367-2.684 3 3 0 00-5.367 2.684zm0 9.316a3 3 0 105.368 2.684 3 3 0 00-5.368-2.684z" />
                        </svg>
                      </Button>
                    {/if}
                    <Button variant="ghost" size="icon" class="h-8 w-8 text-destructive hover:text-destructive" onclick={() => openDeleteDialog(file)} title="删除">
                      <svg class="h-4 w-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                        <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M19 7l-.867 12.142A2 2 0 0116.138 21H7.862a2 2 0 01-1.995-1.858L5 7m5 4v6m4-6v6m1-10V4a1 1 0 00-1-1h-4a1 1 0 00-1 1v3M4 7h16" />
                      </svg>
                    </Button>
                  </div>
                </Table.Cell>
              </Table.Row>
            {/each}
          </Table.Body>
        </Table.Root>

        {#if total > pageSize}
          <div class="flex items-center justify-between border-t p-4">
            <p class="text-sm text-muted-foreground">
              共 {total} 个文件
            </p>
            <div class="flex gap-2">
              <Button
                variant="outline"
                size="sm"
                disabled={page <= 1}
                onclick={() => {
                  page--;
                  loadFiles();
                }}
              >
                上一页
              </Button>
              <div class="flex items-center px-2 text-sm">
                {page} / {Math.ceil(total / pageSize)}
              </div>
              <Button
                variant="outline"
                size="sm"
                disabled={page >= Math.ceil(total / pageSize)}
                onclick={() => {
                  page++;
                  loadFiles();
                }}
              >
                下一页
              </Button>
            </div>
          </div>
        {/if}
      {/if}
    </Card.Content>
  </Card.Root>
</div>

<Dialog.Root bind:open={shareDialogOpen}>
  <Dialog.Content class="sm:max-w-md">
    <Dialog.Header>
      <Dialog.Title>分享文件</Dialog.Title>
      <Dialog.Description>
        {#if shareFile}
          正在分享：{shareFile.fileName}
        {/if}
      </Dialog.Description>
    </Dialog.Header>
    {#if shareCode}
      <div class="space-y-4 py-4">
        <p class="text-sm text-muted-foreground">
          分享链接已创建，请复制以下链接：
        </p>
        <div class="flex gap-2">
          <Input
            readonly
            value={shareLink}
          />
          <Button onclick={copyShareLink}>复制</Button>
        </div>
      </div>
      <Dialog.Footer>
        <Button variant="secondary" onclick={() => (shareDialogOpen = false)}>关闭</Button>
      </Dialog.Footer>
    {:else}
      <div class="space-y-4 py-4">
        <div class="space-y-2">
          <Label>分享类型</Label>
          <div class="flex gap-3">
            {#each [ShareType.PUBLIC, ShareType.PRIVATE] as type}
              <button
                class="flex flex-1 cursor-pointer items-start gap-3 rounded-lg border p-3 text-left transition-colors hover:bg-accent/50 {shareType === type ? 'border-primary bg-primary/5' : ''}"
                onclick={() => (shareType = type)}
              >
                <input
                  type="radio"
                  name="share-type"
                  checked={shareType === type}
                  class="mt-0.5"
                />
                <div>
                  <div class="font-medium">{ShareTypeLabel[type]}</div>
                  <div class="text-xs text-muted-foreground">
                    {ShareTypeDesc[type]}
                  </div>
                </div>
              </button>
            {/each}
          </div>
        </div>

        <div class="space-y-2">
          <Label for="share-expire">有效期</Label>
          <select
            id="share-expire"
            bind:value={shareExpireHours}
            class="w-full rounded-md border border-input bg-background px-3 py-2 text-sm"
          >
            <option value={24}>24 小时</option>
            <option value={72}>3 天</option>
            <option value={168}>7 天</option>
            <option value={720}>30 天</option>
          </select>
        </div>
      </div>
      <Dialog.Footer>
        <Button variant="outline" onclick={() => (shareDialogOpen = false)}>取消</Button>
        <Button onclick={handleShare} disabled={isSharing}>
          {#if isSharing}
            <svg class="mr-2 h-4 w-4 animate-spin" viewBox="0 0 24 24">
              <circle class="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" stroke-width="4" fill="none"></circle>
              <path class="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4zm2 5.291A7.962 7.962 0 014 12H0c0 3.042 1.135 5.824 3 7.938l3-2.647z"></path>
            </svg>
            创建中...
          {:else}
            创建分享
          {/if}
        </Button>
      </Dialog.Footer>
    {/if}
  </Dialog.Content>
</Dialog.Root>

<Dialog.Root bind:open={deleteDialogOpen}>
  <Dialog.Content class="sm:max-w-md">
    <Dialog.Header>
      <Dialog.Title>确认删除</Dialog.Title>
      <Dialog.Description>
        此操作不可撤销，请确认。
      </Dialog.Description>
    </Dialog.Header>

    <div class="space-y-2 py-4">
      <p class="text-sm">
        确定要删除文件
        <span class="font-medium">{deleteTarget?.fileName}</span>
        吗？
      </p>
    </div>

    <Dialog.Footer>
      <Button
        variant="outline"
        disabled={isDeleting}
        onclick={() => (deleteDialogOpen = false)}
      >
        取消
      </Button>
      <Button variant="destructive" disabled={isDeleting} onclick={confirmDelete}>
        {#if isDeleting}
          <svg class="mr-2 h-4 w-4 animate-spin" viewBox="0 0 24 24">
            <circle class="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" stroke-width="4" fill="none"></circle>
            <path class="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4zm2 5.291A7.962 7.962 0 014 12H0c0 3.042 1.135 5.824 3 7.938l3-2.647z"></path>
          </svg>
          删除中...
        {:else}
          删除
        {/if}
      </Button>
    </Dialog.Footer>
  </Dialog.Content>
</Dialog.Root>
