<script lang="ts">
  import { onMount } from "svelte";
  import { goto } from "$app/navigation";
  import { useNotifications } from "$stores/notifications.svelte";
  import { useDownload } from "$stores/download.svelte";
  import { formatFileSize, formatDateTime } from "$utils/format";
  import {
    getFileByHash,
    getTransaction,
    downloadFile,
    createShare,
  } from "$api/endpoints/files";
  import { getAllFriends, shareToFriend } from "$api/endpoints/friends";
  import {
    FileStatus,
    FileStatusLabel,
    ShareType,
    ShareTypeLabel,
    ShareTypeDesc,
    type FileVO,
    type TransactionVO,
    type FriendVO,
  } from "$api/types";
  import * as Card from "$lib/components/ui/card";
  import { Button } from "$lib/components/ui/button";
  import * as Dialog from "$lib/components/ui/dialog";
  import { Badge } from "$lib/components/ui/badge";
  import { Separator } from "$lib/components/ui/separator";
  import { Input } from "$lib/components/ui/input";
  import { Textarea } from "$lib/components/ui/textarea";
  import * as Avatar from "$lib/components/ui/avatar";
  import FilePreview from "$lib/components/FilePreview.svelte";

  let { data } = $props();

  const notifications = useNotifications();
  const download = useDownload();

  let file = $state<FileVO | null>(null);
  let transaction = $state<TransactionVO | null>(null);
  let loading = $state(true);
  let error = $state<string | null>(null);

  // Preview state
  let previewUrl = $state<string | null>(null);
  let showPreview = $state(false);
  let loadingPreview = $state(false);

  // Share dialog state
  let shareDialogOpen = $state(false);
  let shareExpireHours = $state(72);
  let shareType = $state<ShareType>(ShareType.PUBLIC);
  let shareCode = $state("");
  let isSharing = $state(false);

  // Friend share dialog state
  let friendShareDialogOpen = $state(false);
  let friends = $state<FriendVO[]>([]);
  let loadingFriends = $state(false);
  let selectedFriends = $state<Set<string>>(new Set());
  let friendShareMessage = $state("");
  let isSharingToFriend = $state(false);
  let friendSearchKeyword = $state("");

  // Check if file type supports preview
  const canPreview = $derived(file && isPreviewable(file.contentType));

  function isPreviewable(contentType: string): boolean {
    if (!contentType) return false;
    return (
      contentType.startsWith("image/") ||
      contentType.startsWith("video/") ||
      contentType.startsWith("audio/") ||
      contentType.startsWith("text/") ||
      contentType === "application/pdf" ||
      contentType === "application/json" ||
      contentType === "application/xml" ||
      contentType === "application/javascript"
    );
  }

  onMount(() => {
    loadFileDetail();
  });

  async function loadFileDetail() {
    loading = true;
    error = null;

    try {
      file = await getFileByHash(data.hash);

      // Load transaction details if available
      if (file.transactionHash) {
        try {
          transaction = await getTransaction(file.transactionHash);
        } catch {
          // Transaction might not be available yet
        }
      }
    } catch (err) {
      error = err instanceof Error ? err.message : "加载文件信息失败";
      notifications.error("加载失败", error);
    } finally {
      loading = false;
    }
  }

  async function handleDownload() {
    if (!file) return;

    // Use the new download manager with presigned URLs
    download.startDownload(file.fileHash, file.fileName, { type: "owned" });
    notifications.info("下载已开始", "可在右下角查看下载进度");
  }

  async function handleShare() {
    if (!file) return;

    isSharing = true;
    try {
      const code = await createShare({
        fileHash: [file.fileHash],
        expireMinutes: shareExpireHours * 60,
        shareType,
      });
      shareCode = code;
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

  function copyShareLink() {
    const link = `${window.location.origin}/share/${shareCode}`;
    navigator.clipboard.writeText(link);
    notifications.success("已复制到剪贴板");
  }

  async function openFriendShareDialog() {
    friendShareDialogOpen = true;
    selectedFriends = new Set();
    friendShareMessage = "";
    friendSearchKeyword = "";
    if (friends.length === 0) {
      loadingFriends = true;
      try {
        friends = await getAllFriends();
      } catch (err) {
        notifications.error("加载好友失败", err instanceof Error ? err.message : "请稍后重试");
      } finally {
        loadingFriends = false;
      }
    }
  }

  function toggleFriendSelection(friendId: string) {
    const newSet = new Set(selectedFriends);
    if (newSet.has(friendId)) {
      newSet.delete(friendId);
    } else {
      newSet.add(friendId);
    }
    selectedFriends = newSet;
  }

  async function handleShareToFriend() {
    if (!file || selectedFriends.size === 0) return;

    isSharingToFriend = true;
    try {
      // Share to each selected friend
      const sharePromises = Array.from(selectedFriends).map(friendId =>
        shareToFriend({
          friendId,
          fileHashes: [file!.fileHash],
          message: friendShareMessage || undefined
        })
      );
      await Promise.all(sharePromises);
      notifications.success(`已分享给 ${selectedFriends.size} 位好友`);
      friendShareDialogOpen = false;
    } catch (err) {
      notifications.error("分享失败", err instanceof Error ? err.message : "请稍后重试");
    } finally {
      isSharingToFriend = false;
    }
  }

  function getDisplayFriendName(friend: FriendVO): string {
    return friend.remark || friend.nickname || friend.username;
  }

  const filteredFriends = $derived(
    friendSearchKeyword.trim()
      ? friends.filter(f =>
          f.username.toLowerCase().includes(friendSearchKeyword.toLowerCase()) ||
          (f.nickname?.toLowerCase().includes(friendSearchKeyword.toLowerCase())) ||
          (f.remark?.toLowerCase().includes(friendSearchKeyword.toLowerCase()))
        )
      : friends
  );

  async function handlePreview() {
    if (!file || previewUrl) {
      showPreview = true;
      return;
    }

    loadingPreview = true;
    try {
      const blob = await downloadFile(file.fileHash);
      previewUrl = URL.createObjectURL(blob);
      showPreview = true;
    } catch (err) {
      notifications.error(
        "加载预览失败",
        err instanceof Error ? err.message : "请稍后重试"
      );
    } finally {
      loadingPreview = false;
    }
  }

  function closePreview() {
    showPreview = false;
  }

  function getStatusVariant(
    status: FileStatus
  ): "default" | "secondary" | "destructive" | "outline" {
    switch (status) {
      case FileStatus.COMPLETED:
        return "default";
      case FileStatus.FAILED:
        return "destructive";
      default:
        return "secondary";
    }
  }
</script>

<svelte:head>
  <title>{file?.fileName ?? "文件详情"} - 存证平台</title>
</svelte:head>

<div class="mx-auto max-w-4xl space-y-6">
  <!-- Back button -->
  <a
    href="/files"
    class="inline-flex items-center gap-1 text-sm text-muted-foreground hover:text-foreground"
  >
    <svg class="h-4 w-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
      <path
        stroke-linecap="round"
        stroke-linejoin="round"
        stroke-width="2"
        d="M10 19l-7-7m0 0l7-7m-7 7h18"
      />
    </svg>
    返回文件列表
  </a>

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
          class="mx-auto mb-4 flex h-16 w-16 items-center justify-center rounded-full bg-destructive/10"
        >
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
        </div>
        <p class="text-muted-foreground">{error}</p>
        <Button variant="outline" class="mt-4" onclick={() => goto("/files")}>
          返回文件列表
        </Button>
      </Card.Content>
    </Card.Root>
  {:else if file}
    <!-- File Info Card -->
    <Card.Root>
      <Card.Header>
        <div class="flex items-start justify-between">
          <div class="flex items-center gap-4">
            <div
              class="flex h-14 w-14 items-center justify-center rounded-lg bg-primary/10 text-primary"
            >
              <svg
                class="h-7 w-7"
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
            <div>
              <Card.Title class="text-xl">{file.fileName}</Card.Title>
              <Card.Description>
                {formatFileSize(file.fileSize)} · {file.contentType}
              </Card.Description>
            </div>
          </div>
          <Badge variant={getStatusVariant(file.status)}>
            {FileStatusLabel[file.status]}
          </Badge>
        </div>
      </Card.Header>
      <Card.Content class="space-y-4">
        <div class="grid gap-4 sm:grid-cols-2">
          <div>
            <p class="text-sm font-medium text-muted-foreground">文件哈希</p>
            <p class="mt-1 break-all font-mono text-sm">{file.fileHash}</p>
          </div>
          <div>
            <p class="text-sm font-medium text-muted-foreground">上传时间</p>
            <p class="mt-1 text-sm">{formatDateTime(file.createTime)}</p>
          </div>
        </div>

        {#if file.sharedFromUserName}
          <div
            class="flex items-center gap-2 rounded-lg bg-blue-500/10 px-4 py-3"
          >
            <svg
              class="h-5 w-5 text-blue-500"
              fill="none"
              stroke="currentColor"
              viewBox="0 0 24 24"
            >
              <path
                stroke-linecap="round"
                stroke-linejoin="round"
                stroke-width="2"
                d="M16 7a4 4 0 11-8 0 4 4 0 018 0zM12 14a7 7 0 00-7 7h14a7 7 0 00-7-7z"
              />
            </svg>
            <span class="text-sm">
              分享自 <span class="font-medium text-blue-600">{file.sharedFromUserName}</span>
            </span>
          </div>
        {/if}

        {#if file.status === FileStatus.COMPLETED}
          <Separator />
          <div class="flex flex-wrap gap-2">
            {#if canPreview}
              <Button
                variant="secondary"
                onclick={handlePreview}
                disabled={loadingPreview}
              >
                {#if loadingPreview}
                  <div
                    class="mr-2 h-4 w-4 animate-spin rounded-full border-2 border-current border-t-transparent"
                  ></div>
                {:else}
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
                      d="M15 12a3 3 0 11-6 0 3 3 0 016 0z"
                    />
                    <path
                      stroke-linecap="round"
                      stroke-linejoin="round"
                      stroke-width="2"
                      d="M2.458 12C3.732 7.943 7.523 5 12 5c4.478 0 8.268 2.943 9.542 7-1.274 4.057-5.064 7-9.542 7-4.477 0-8.268-2.943-9.542-7z"
                    />
                  </svg>
                {/if}
                预览文件
              </Button>
            {/if}
            <Button onclick={handleDownload}>
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
              下载文件
            </Button>
            <Button
              variant="outline"
              onclick={() => {
                shareCode = "";
                shareDialogOpen = true;
              }}
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
                  d="M8.684 13.342C8.886 12.938 9 12.482 9 12c0-.482-.114-.938-.316-1.342m0 2.684a3 3 0 110-2.684m0 2.684l6.632 3.316m-6.632-6l6.632-3.316m0 0a3 3 0 105.367-2.684 3 3 0 00-5.367 2.684zm0 9.316a3 3 0 105.368 2.684 3 3 0 00-5.368-2.684z"
                />
              </svg>
              分享链接
            </Button>
            <Button
              variant="outline"
              onclick={openFriendShareDialog}
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
                  d="M17 20h5v-2a3 3 0 00-5.356-1.857M17 20H7m10 0v-2c0-.656-.126-1.283-.356-1.857M7 20H2v-2a3 3 0 015.356-1.857M7 20v-2c0-.656.126-1.283.356-1.857m0 0a5.002 5.002 0 019.288 0M15 7a3 3 0 11-6 0 3 3 0 016 0zm6 3a2 2 0 11-4 0 2 2 0 014 0zM7 10a2 2 0 11-4 0 2 2 0 014 0z"
                />
              </svg>
              分享给好友
            </Button>
          </div>
        {/if}
      </Card.Content>
    </Card.Root>

    <!-- Blockchain Certificate Card -->
    {#if file.transactionHash}
      <Card.Root>
        <Card.Header>
          <Card.Title class="flex items-center gap-2">
            <svg
              class="h-5 w-5 text-primary"
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
            区块链存证信息
          </Card.Title>
          <Card.Description>文件已上链存证，不可篡改</Card.Description>
        </Card.Header>
        <Card.Content class="space-y-4">
          <div class="rounded-lg bg-muted/50 p-4">
            <div class="grid gap-4">
              <div>
                <p class="text-sm font-medium text-muted-foreground">
                  交易哈希
                </p>
                <p class="mt-1 break-all font-mono text-sm">
                  {file.transactionHash}
                </p>
              </div>
              {#if transaction}
                <div class="grid gap-4 sm:grid-cols-2">
                  <div>
                    <p class="text-sm font-medium text-muted-foreground">
                      区块号
                    </p>
                    <p class="mt-1 font-mono text-sm">
                      {transaction.blockNumber}
                    </p>
                  </div>
                  <div>
                    <p class="text-sm font-medium text-muted-foreground">
                      存证时间
                    </p>
                    <p class="mt-1 text-sm">
                      {formatDateTime(transaction.timestamp)}
                    </p>
                  </div>
                </div>
              {:else if file.blockNumber}
                <div>
                  <p class="text-sm font-medium text-muted-foreground">
                    区块号
                  </p>
                  <p class="mt-1 font-mono text-sm">{file.blockNumber}</p>
                </div>
              {/if}
            </div>
          </div>

          <div class="flex items-center gap-2 text-sm text-muted-foreground">
            <svg
              class="h-4 w-4 text-green-500"
              fill="none"
              stroke="currentColor"
              viewBox="0 0 24 24"
            >
              <path
                stroke-linecap="round"
                stroke-linejoin="round"
                stroke-width="2"
                d="M5 13l4 4L19 7"
              />
            </svg>
            <span>此文件已通过 FISCO BCOS 区块链存证</span>
          </div>
        </Card.Content>
      </Card.Root>
    {:else if file.status !== FileStatus.COMPLETED && file.status !== FileStatus.FAILED}
      <Card.Root>
        <Card.Content class="flex items-center gap-4 p-6">
          <div
            class="h-8 w-8 animate-spin rounded-full border-4 border-primary border-t-transparent"
          ></div>
          <div>
            <p class="font-medium">正在处理中</p>
            <p class="text-sm text-muted-foreground">
              {FileStatusLabel[file.status]}，请稍候...
            </p>
          </div>
        </Card.Content>
      </Card.Root>
    {/if}

    <!-- File Preview Card -->
    {#if showPreview && previewUrl && file}
      <Card.Root>
        <Card.Header>
          <div class="flex items-center justify-between">
            <Card.Title class="flex items-center gap-2">
              <svg
                class="h-5 w-5 text-primary"
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
              文件预览
            </Card.Title>
            <Button variant="ghost" size="sm" onclick={closePreview}>
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
                  d="M6 18L18 6M6 6l12 12"
                />
              </svg>
            </Button>
          </div>
        </Card.Header>
        <Card.Content>
          <FilePreview
            url={previewUrl}
            contentType={file.contentType}
            fileName={file.fileName}
          />
        </Card.Content>
      </Card.Root>
    {/if}
  {/if}
</div>

<!-- Share Dialog -->
<Dialog.Root bind:open={shareDialogOpen}>
  <Dialog.Content class="sm:max-w-md">
    <Dialog.Header>
      <Dialog.Title>分享文件</Dialog.Title>
      <Dialog.Description>
        {#if shareCode}
          分享链接已创建，请复制以下链接
        {:else}
          创建分享链接
        {/if}
      </Dialog.Description>
    </Dialog.Header>

    {#if shareCode}
      <div class="space-y-4">
        <div class="flex gap-2">
          <Input
            readonly
            value={`${window.location.origin}/share/${shareCode}`}
            class="flex-1 font-mono text-sm"
          />
          <Button onclick={copyShareLink}>复制</Button>
        </div>
      </div>
    {:else}
      <div class="space-y-4">
        <div>
          <label for="share-type" class="mb-2 block text-sm font-medium">分享类型</label>
          <div class="flex gap-3">
            {#each [ShareType.PUBLIC, ShareType.PRIVATE] as type}
              <label
                class="flex flex-1 cursor-pointer items-start gap-3 rounded-lg border p-3 transition-colors
                  {shareType === type ? 'border-primary bg-primary/5' : 'border-border hover:bg-accent/50'}"
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
                  <div class="text-xs text-muted-foreground">{ShareTypeDesc[type]}</div>
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

    <Dialog.Footer>
      <Button variant="outline" onclick={() => (shareDialogOpen = false)}>
        {shareCode ? "关闭" : "取消"}
      </Button>
      {#if !shareCode}
        <Button onclick={handleShare} disabled={isSharing}>
          {isSharing ? "创建中..." : "创建分享"}
        </Button>
      {/if}
    </Dialog.Footer>
  </Dialog.Content>
</Dialog.Root>

<!-- Friend Share Dialog -->
<Dialog.Root bind:open={friendShareDialogOpen}>
  <Dialog.Content class="sm:max-w-md">
    <Dialog.Header>
      <Dialog.Title>分享给好友</Dialog.Title>
      <Dialog.Description>
        选择好友直接分享文件
      </Dialog.Description>
    </Dialog.Header>

    <div class="space-y-4">
      <Input
        bind:value={friendSearchKeyword}
        placeholder="搜索好友..."
      />

      {#if loadingFriends}
        <div class="flex items-center justify-center p-8">
          <div class="h-6 w-6 animate-spin rounded-full border-2 border-primary border-t-transparent"></div>
        </div>
      {:else if filteredFriends.length === 0}
        <div class="p-8 text-center text-muted-foreground">
          {#if friends.length === 0}
            <p>暂无好友</p>
            <Button variant="link" class="mt-2" onclick={() => { friendShareDialogOpen = false; goto('/friends'); }}>
              去添加好友
            </Button>
          {:else}
            <p>未找到匹配的好友</p>
          {/if}
        </div>
      {:else}
        <div class="max-h-48 overflow-y-auto space-y-2">
          {#each filteredFriends as friend}
            <button
              class="w-full flex items-center gap-3 p-3 rounded-lg border hover:bg-muted/50 transition-colors text-left {selectedFriends.has(friend.id) ? 'border-primary bg-primary/5' : ''}"
              onclick={() => toggleFriendSelection(friend.id)}
            >
              <Avatar.Root class="h-10 w-10">
                {#if friend.avatar}
                  <Avatar.Image src={friend.avatar} alt={friend.username} />
                {/if}
                <Avatar.Fallback>{friend.username.charAt(0).toUpperCase()}</Avatar.Fallback>
              </Avatar.Root>
              <div class="flex-1 min-w-0">
                <div class="font-medium truncate">{getDisplayFriendName(friend)}</div>
                {#if friend.remark || friend.nickname}
                  <div class="text-xs text-muted-foreground truncate">@{friend.username}</div>
                {/if}
              </div>
              {#if selectedFriends.has(friend.id)}
                <svg class="h-5 w-5 text-primary flex-shrink-0" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                  <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M5 13l4 4L19 7" />
                </svg>
              {/if}
            </button>
          {/each}
        </div>
      {/if}

      {#if selectedFriends.size > 0}
        <div class="space-y-2">
          <label for="friend-share-message" class="text-sm font-medium">留言（可选）</label>
          <Textarea
            id="friend-share-message"
            bind:value={friendShareMessage}
            placeholder="给好友留言..."
            rows={2}
          />
        </div>
      {/if}
    </div>

    <Dialog.Footer>
      <Button variant="outline" onclick={() => (friendShareDialogOpen = false)}>
        取消
      </Button>
      <Button
        onclick={handleShareToFriend}
        disabled={selectedFriends.size === 0 || isSharingToFriend}
      >
        {#if isSharingToFriend}
          分享中...
        {:else if selectedFriends.size > 0}
          分享给 {selectedFriends.size} 位好友
        {:else}
          选择好友
        {/if}
      </Button>
    </Dialog.Footer>
  </Dialog.Content>
</Dialog.Root>
