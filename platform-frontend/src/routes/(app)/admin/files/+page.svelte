<script lang="ts">
  import { onMount, onDestroy } from "svelte";
  import { useNotifications } from "$stores/notifications.svelte";
  import { formatFileSize, formatDateTime } from "$utils/format";
  import {
    getAllFiles,
    getFileDetail,
    updateFileStatus,
    forceDeleteFile,
    getAllShares,
    forceCancelShare,
    getShareAccessLogs,
  } from "$api/endpoints/admin";
  import { downloadFile } from "$api/endpoints/files";
  import {
    type AdminFileVO,
    type AdminFileDetailVO,
    type AdminShareVO,
    type ShareAccessLogVO,
    AdminFileStatus,
    AdminFileStatusLabel,
    AdminShareStatus,
  } from "$api/types";
  import * as Card from "$lib/components/ui/card";
  import { Button } from "$lib/components/ui/button";
  import { Badge } from "$lib/components/ui/badge";
  import { Input } from "$lib/components/ui/input";
  import * as Dialog from "$lib/components/ui/dialog";
  import * as Tabs from "$lib/components/ui/tabs";
  import * as Table from "$lib/components/ui/table";
  import { Separator } from "$lib/components/ui/separator";
  import DateTimePicker from "$lib/components/ui/date-picker/date-time-picker.svelte";
  import FilePreview from "$lib/components/FilePreview.svelte";

  const notifications = useNotifications();

  // 标签页状态
  let activeTab = $state<"files" | "shares">("files");

  // 文件列表状态
  let files = $state<AdminFileVO[]>([]);
  let filesLoading = $state(true);
  let filesPageNum = $state(1);
  let filesTotalPages = $state(1);
  let filesKeyword = $state("");
  let filesStatus = $state<number | "">("");
  let filesSourceType = $state<"" | "original" | "shared">("");
  let filesOwnerName = $state("");
  let filesStartTime = $state("");
  let filesEndTime = $state("");

  // 分享列表状态
  let shares = $state<AdminShareVO[]>([]);
  let sharesLoading = $state(true);
  let sharesPageNum = $state(1);
  let sharesTotalPages = $state(1);
  let sharesKeyword = $state("");
  let sharesStatus = $state<number | "">("");
  let sharesShareType = $state<number | "">("");
  let sharesSharerName = $state("");
  let sharesStartTime = $state("");
  let sharesEndTime = $state("");

  // 文件详情对话框
  let detailDialogOpen = $state(false);
  let selectedFile = $state<AdminFileDetailVO | null>(null);
  let detailLoading = $state(false);

  // 删除确认对话框
  let deleteDialogOpen = $state(false);
  let deleteTarget = $state<{
    type: "file" | "share";
    id: string;
    name: string;
  } | null>(null);
  let deleteReason = $state("");
  let deleting = $state(false);

  // 访问日志对话框
  let logsDialogOpen = $state(false);
  let logsShareCode = $state("");
  let accessLogs = $state<ShareAccessLogVO[]>([]);
  let logsLoading = $state(false);
  let logsPageNum = $state(1);
  let logsTotalPages = $state(1);
  let logsPageSize = 20;

  async function loadLogsPage() {
    logsLoading = true;
    try {
      const result = await getShareAccessLogs(logsShareCode, {
        pageNum: logsPageNum,
        pageSize: logsPageSize,
      });
      accessLogs = result.records;
      logsTotalPages = Math.max(1, Math.ceil(result.total / logsPageSize));
    } catch (err) {
      notifications.error(
        "加载日志失败",
        err instanceof Error ? err.message : "请稍后重试",
      );
    } finally {
      logsLoading = false;
    }
  }

  onMount(() => {
    loadFiles();
  });

  async function loadFiles() {
    filesLoading = true;
    try {
      const result = await getAllFiles({
        pageNum: filesPageNum,
        pageSize: 20,
        keyword: filesKeyword || undefined,
        status: filesStatus !== "" ? filesStatus : undefined,
        originalOnly: filesSourceType === "original" ? true : undefined,
        sharedOnly: filesSourceType === "shared" ? true : undefined,
        ownerName: filesOwnerName || undefined,
        startTime: filesStartTime || undefined,
        endTime: filesEndTime || undefined,
      });
      files = result.records;
      filesTotalPages = Math.max(1, Math.ceil(result.total / 20));
    } catch (err) {
      notifications.error(
        "加载失败",
        err instanceof Error ? err.message : "请稍后重试",
      );
    } finally {
      filesLoading = false;
    }
  }

  async function loadShares() {
    sharesLoading = true;
    try {
      const result = await getAllShares({
        pageNum: sharesPageNum,
        pageSize: 20,
        keyword: sharesKeyword || undefined,
        status: sharesStatus !== "" ? sharesStatus : undefined,
        shareType: sharesShareType !== "" ? sharesShareType : undefined,
        sharerName: sharesSharerName || undefined,
        startTime: sharesStartTime || undefined,
        endTime: sharesEndTime || undefined,
      });
      shares = result.records;
      sharesTotalPages = Math.max(1, Math.ceil(result.total / 20));
    } catch (err) {
      notifications.error(
        "加载失败",
        err instanceof Error ? err.message : "请稍后重试",
      );
    } finally {
      sharesLoading = false;
    }
  }

  function handleFilesSearch() {
    filesPageNum = 1;
    loadFiles();
  }

  function handleSharesSearch() {
    sharesPageNum = 1;
    loadShares();
  }

  function clearFilesFilters() {
    filesKeyword = "";
    filesStatus = "";
    filesSourceType = "";
    filesOwnerName = "";
    filesStartTime = "";
    filesEndTime = "";
    filesPageNum = 1;
    loadFiles();
  }

  function clearSharesFilters() {
    sharesKeyword = "";
    sharesStatus = "";
    sharesShareType = "";
    sharesSharerName = "";
    sharesStartTime = "";
    sharesEndTime = "";
    sharesPageNum = 1;
    loadShares();
  }

  async function handleViewDetail(file: AdminFileVO) {
    detailDialogOpen = true;
    detailLoading = true;
    try {
      selectedFile = await getFileDetail(file.id);
    } catch (err) {
      notifications.error(
        "加载详情失败",
        err instanceof Error ? err.message : "请稍后重试",
      );
      detailDialogOpen = false;
    } finally {
      detailLoading = false;
    }
  }

  let statusUpdatingId = $state<string | null>(null);

  async function handleUpdateStatus(fileId: string, newStatus: number) {
    statusUpdatingId = fileId;
    try {
      await updateFileStatus(fileId, { status: newStatus });
      notifications.success("状态更新成功");
      loadFiles();
      if (selectedFile && selectedFile.id === fileId) {
        selectedFile = {
          ...selectedFile,
          status: newStatus,
          statusDesc: AdminFileStatusLabel[newStatus],
        };
      }
    } catch (err) {
      notifications.error(
        "更新失败",
        err instanceof Error ? err.message : "请稍后重试",
      );
    } finally {
      statusUpdatingId = null;
    }
  }

  function confirmDelete(type: "file" | "share", id: string, name: string) {
    deleteTarget = { type, id, name };
    deleteReason = "";
    deleteDialogOpen = true;
  }

  async function executeDelete() {
    if (!deleteTarget) return;
    deleting = true;
    try {
      if (deleteTarget.type === "file") {
        await forceDeleteFile(deleteTarget.id, deleteReason || undefined);
        notifications.success("文件已删除");
        loadFiles();
      } else {
        await forceCancelShare(deleteTarget.id, deleteReason || undefined);
        notifications.success("分享已取消");
        loadShares();
      }
      deleteDialogOpen = false;
    } catch (err) {
      notifications.error(
        "操作失败",
        err instanceof Error ? err.message : "请稍后重试",
      );
    } finally {
      deleting = false;
    }
  }

  async function handleViewLogs(shareCode: string) {
    logsShareCode = shareCode;
    logsDialogOpen = true;
    logsPageNum = 1;
    await loadLogsPage();
  }

  function handleTabChange(tab: string) {
    activeTab = tab as "files" | "shares";
    if (tab === "shares" && shares.length === 0) {
      loadShares();
    }
  }

  // 预览状态
  let previewDialogOpen = $state(false);
  let previewUrl = $state("");
  let previewContentType = $state("");
  let previewFileName = $state("");
  let previewLoading = $state(false);

  // 下载状态
  let downloadingHash = $state<string | null>(null);

  async function handlePreview(
    fileHash: string,
    contentType: string,
    fileName: string,
  ) {
    previewLoading = true;
    previewDialogOpen = true;
    previewContentType = contentType;
    previewFileName = fileName;
    previewUrl = "";
    try {
      const blob = await downloadFile(fileHash);
      previewUrl = URL.createObjectURL(blob);
    } catch (err) {
      notifications.error(
        "预览失败",
        err instanceof Error ? err.message : "无法加载文件",
      );
      previewDialogOpen = false;
    } finally {
      previewLoading = false;
    }
  }

  function closePreview() {
    previewDialogOpen = false;
    if (previewUrl) {
      URL.revokeObjectURL(previewUrl);
      previewUrl = "";
    }
  }

  async function handleDownload(fileHash: string, fileName: string) {
    downloadingHash = fileHash;
    try {
      const blob = await downloadFile(fileHash);
      const url = URL.createObjectURL(blob);
      const a = document.createElement("a");
      a.href = url;
      a.download = fileName;
      document.body.appendChild(a);
      a.click();
      document.body.removeChild(a);
      URL.revokeObjectURL(url);
      notifications.success("下载完成");
    } catch (err) {
      notifications.error(
        "下载失败",
        err instanceof Error ? err.message : "请稍后重试",
      );
    } finally {
      downloadingHash = null;
    }
  }

  onDestroy(() => {
    if (previewUrl) URL.revokeObjectURL(previewUrl);
  });

  function isPreviewable(contentType: string): boolean {
    return (
      contentType.startsWith("image/") ||
      contentType.startsWith("video/") ||
      contentType.startsWith("audio/") ||
      contentType === "application/pdf" ||
      contentType.startsWith("text/") ||
      contentType === "application/json" ||
      contentType === "application/xml"
    );
  }

  function getStatusBadgeVariant(
    status: number,
  ): "default" | "secondary" | "destructive" | "outline" {
    switch (status) {
      case AdminFileStatus.COMPLETED:
      case AdminShareStatus.ACTIVE:
        return "default";
      case AdminFileStatus.FAILED:
      case AdminShareStatus.CANCELLED:
        return "destructive";
      case AdminFileStatus.DELETED:
      case AdminShareStatus.EXPIRED:
        return "secondary";
      default:
        return "outline";
    }
  }
</script>

<svelte:head>
  <title>文件审计 - 管理后台</title>
</svelte:head>

<div class="mx-auto max-w-7xl space-y-6">
  <div>
    <h1 class="text-2xl font-bold">文件审计</h1>
    <p class="text-muted-foreground">管理和审查系统中的所有文件和分享</p>
  </div>

  <Tabs.Root value={activeTab} onValueChange={handleTabChange}>
    <Tabs.List>
      <Tabs.Trigger value="files">文件管理</Tabs.Trigger>
      <Tabs.Trigger value="shares">分享管理</Tabs.Trigger>
    </Tabs.List>

    <!-- 文件管理标签页 -->
    <Tabs.Content value="files" class="mt-4">
      <Card.Root>
        <Card.Header class="pb-4">
          <div class="flex items-center justify-between">
            <Card.Title>所有文件</Card.Title>
          </div>

          <!-- 筛选面板 -->
          <div class="bg-muted/30 mt-4 rounded-lg border p-4">
            <div
              class="grid gap-4 md:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4"
            >
              <Input
                placeholder="搜索文件名或哈希..."
                bind:value={filesKeyword}
                onkeydown={(e) => e.key === "Enter" && handleFilesSearch()}
              />

              <select
                bind:value={filesStatus}
                class="bg-background focus:border-primary h-9 w-full rounded-md border px-3 text-sm focus:outline-none"
              >
                <option value="">全部状态</option>
                <option value={0}>处理中</option>
                <option value={1}>已完成</option>
                <option value={2}>已删除</option>
                <option value={-1}>失败</option>
              </select>

              <select
                bind:value={filesSourceType}
                class="bg-background focus:border-primary h-9 w-full rounded-md border px-3 text-sm focus:outline-none"
              >
                <option value="">全部来源</option>
                <option value="original">原始上传</option>
                <option value="shared">分享保存</option>
              </select>

              <Input
                placeholder="所有者用户名"
                bind:value={filesOwnerName}
                onkeydown={(e) => e.key === "Enter" && handleFilesSearch()}
              />

              <DateTimePicker
                bind:value={filesStartTime}
                placeholder="开始时间"
              />
              <DateTimePicker
                bind:value={filesEndTime}
                placeholder="结束时间"
              />

              <div class="flex gap-2">
                <Button onclick={handleFilesSearch} class="flex-1">搜索</Button>
                <Button variant="secondary" onclick={clearFilesFilters}
                  >重置</Button
                >
              </div>
            </div>
          </div>
        </Card.Header>
        <Card.Content>
          {#if filesLoading}
            <div class="flex items-center justify-center p-12">
              <div
                class="border-primary h-8 w-8 animate-spin rounded-full border-4 border-t-transparent"
              ></div>
            </div>
          {:else if files.length === 0}
            <div class="text-muted-foreground p-12 text-center">暂无文件</div>
          {:else}
            <Table.Root>
              <Table.Header>
                <Table.Row>
                  <Table.Head>文件名</Table.Head>
                  <Table.Head>所有者</Table.Head>
                  <Table.Head>来源</Table.Head>
                  <Table.Head>大小</Table.Head>
                  <Table.Head>状态</Table.Head>
                  <Table.Head>创建时间</Table.Head>
                  <Table.Head class="text-right">操作</Table.Head>
                </Table.Row>
              </Table.Header>
              <Table.Body>
                {#each files as file}
                  <Table.Row>
                    <Table.Cell class="max-w-[200px] truncate font-medium">
                      {file.fileName}
                    </Table.Cell>
                    <Table.Cell>{file.ownerName}</Table.Cell>
                    <Table.Cell>
                      {#if file.isOriginal}
                        <Badge variant="outline">原始上传</Badge>
                      {:else}
                        <span class="text-muted-foreground text-sm">
                          来自 {file.sharedFromUserName || "未知"}
                        </span>
                      {/if}
                    </Table.Cell>
                    <Table.Cell>{formatFileSize(file.fileSize)}</Table.Cell>
                    <Table.Cell>
                      <Badge variant={getStatusBadgeVariant(file.status)}>
                        {file.statusDesc}
                      </Badge>
                    </Table.Cell>
                    <Table.Cell class="text-muted-foreground text-sm">
                      {formatDateTime(file.createTime)}
                    </Table.Cell>
                    <Table.Cell class="text-right">
                      <div class="flex justify-end gap-1">
                        {#if file.status === AdminFileStatus.COMPLETED && isPreviewable(file.contentType)}
                          <Button
                            variant="ghost"
                            size="sm"
                            title="预览"
                            onclick={() =>
                              handlePreview(
                                file.fileHash,
                                file.contentType,
                                file.fileName,
                              )}
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
                          </Button>
                        {/if}
                        {#if file.status === AdminFileStatus.COMPLETED}
                          <Button
                            variant="ghost"
                            size="sm"
                            title="下载"
                            disabled={downloadingHash === file.fileHash}
                            onclick={() =>
                              handleDownload(file.fileHash, file.fileName)}
                          >
                            {#if downloadingHash === file.fileHash}
                              <div
                                class="h-4 w-4 animate-spin rounded-full border-2 border-current border-t-transparent"
                              ></div>
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
                          </Button>
                        {/if}
                        <Button
                          variant="ghost"
                          size="sm"
                          onclick={() => handleViewDetail(file)}
                        >
                          详情
                        </Button>
                        <Button
                          variant="ghost"
                          size="sm"
                          class="text-destructive"
                          onclick={() =>
                            confirmDelete("file", file.id, file.fileName)}
                        >
                          删除
                        </Button>
                      </div>
                    </Table.Cell>
                  </Table.Row>
                {/each}
              </Table.Body>
            </Table.Root>

            <!-- 分页 -->
            <div class="mt-4 flex items-center justify-between">
              <span class="text-muted-foreground text-sm">
                第 {filesPageNum} / {filesTotalPages} 页
              </span>
              <div class="flex gap-2">
                <Button
                  variant="outline"
                  size="sm"
                  disabled={filesPageNum <= 1}
                  onclick={() => {
                    filesPageNum--;
                    loadFiles();
                  }}
                >
                  上一页
                </Button>
                <Button
                  variant="outline"
                  size="sm"
                  disabled={filesPageNum >= filesTotalPages}
                  onclick={() => {
                    filesPageNum++;
                    loadFiles();
                  }}
                >
                  下一页
                </Button>
              </div>
            </div>
          {/if}
        </Card.Content>
      </Card.Root>
    </Tabs.Content>

    <!-- 分享管理标签页 -->
    <Tabs.Content value="shares" class="mt-4">
      <Card.Root>
        <Card.Header class="pb-4">
          <div class="flex items-center justify-between">
            <Card.Title>所有分享</Card.Title>
          </div>

          <!-- 筛选面板 -->
          <div class="bg-muted/30 mt-4 rounded-lg border p-4">
            <div
              class="grid gap-4 md:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4"
            >
              <Input
                placeholder="搜索分享码或文件名..."
                bind:value={sharesKeyword}
                onkeydown={(e) => e.key === "Enter" && handleSharesSearch()}
              />

              <select
                bind:value={sharesStatus}
                class="bg-background focus:border-primary h-9 w-full rounded-md border px-3 text-sm focus:outline-none"
              >
                <option value="">全部状态</option>
                <option value={1}>有效</option>
                <option value={0}>已取消</option>
                <option value={2}>已过期</option>
              </select>

              <select
                bind:value={sharesShareType}
                class="bg-background focus:border-primary h-9 w-full rounded-md border px-3 text-sm focus:outline-none"
              >
                <option value="">全部类型</option>
                <option value={0}>公开</option>
                <option value={1}>私密</option>
              </select>

              <Input
                placeholder="分享者用户名"
                bind:value={sharesSharerName}
                onkeydown={(e) => e.key === "Enter" && handleSharesSearch()}
              />

              <DateTimePicker
                bind:value={sharesStartTime}
                placeholder="开始时间"
              />
              <DateTimePicker
                bind:value={sharesEndTime}
                placeholder="结束时间"
              />

              <div class="flex gap-2">
                <Button onclick={handleSharesSearch} class="flex-1">搜索</Button
                >
                <Button variant="secondary" onclick={clearSharesFilters}
                  >重置</Button
                >
              </div>
            </div>
          </div>
        </Card.Header>
        <Card.Content>
          {#if sharesLoading}
            <div class="flex items-center justify-center p-12">
              <div
                class="border-primary h-8 w-8 animate-spin rounded-full border-4 border-t-transparent"
              ></div>
            </div>
          {:else if shares.length === 0}
            <div class="text-muted-foreground p-12 text-center">暂无分享</div>
          {:else}
            <Table.Root>
              <Table.Header>
                <Table.Row>
                  <Table.Head>分享码</Table.Head>
                  <Table.Head>分享者</Table.Head>
                  <Table.Head>类型</Table.Head>
                  <Table.Head>文件数</Table.Head>
                  <Table.Head>访问统计</Table.Head>
                  <Table.Head>状态</Table.Head>
                  <Table.Head>创建时间</Table.Head>
                  <Table.Head class="text-right">操作</Table.Head>
                </Table.Row>
              </Table.Header>
              <Table.Body>
                {#each shares as share}
                  <Table.Row>
                    <Table.Cell class="font-mono">{share.shareCode}</Table.Cell>
                    <Table.Cell>{share.sharerName}</Table.Cell>
                    <Table.Cell>
                      <Badge
                        variant={share.shareType === 0
                          ? "default"
                          : "secondary"}
                      >
                        {share.shareTypeDesc}
                      </Badge>
                    </Table.Cell>
                    <Table.Cell>{share.fileCount} 个</Table.Cell>
                    <Table.Cell class="text-sm">
                      <div class="text-muted-foreground flex gap-2">
                        <span>👁 {share.viewCount}</span>
                        <span>⬇ {share.downloadCount}</span>
                        <span>💾 {share.saveCount}</span>
                      </div>
                    </Table.Cell>
                    <Table.Cell>
                      <Badge variant={getStatusBadgeVariant(share.status)}>
                        {share.statusDesc}
                      </Badge>
                    </Table.Cell>
                    <Table.Cell class="text-muted-foreground text-sm">
                      {formatDateTime(share.createTime)}
                    </Table.Cell>
                    <Table.Cell class="text-right">
                      <div class="flex justify-end gap-1">
                        <Button
                          variant="ghost"
                          size="sm"
                          onclick={() => handleViewLogs(share.shareCode)}
                        >
                          日志
                        </Button>
                        {#if share.status === AdminShareStatus.ACTIVE}
                          <Button
                            variant="ghost"
                            size="sm"
                            class="text-destructive"
                            onclick={() =>
                              confirmDelete(
                                "share",
                                share.shareCode,
                                share.shareCode,
                              )}
                          >
                            取消
                          </Button>
                        {/if}
                      </div>
                    </Table.Cell>
                  </Table.Row>
                {/each}
              </Table.Body>
            </Table.Root>

            <!-- 分页 -->
            <div class="mt-4 flex items-center justify-between">
              <span class="text-muted-foreground text-sm">
                第 {sharesPageNum} / {sharesTotalPages} 页
              </span>
              <div class="flex gap-2">
                <Button
                  variant="outline"
                  size="sm"
                  disabled={sharesPageNum <= 1}
                  onclick={() => {
                    sharesPageNum--;
                    loadShares();
                  }}
                >
                  上一页
                </Button>
                <Button
                  variant="outline"
                  size="sm"
                  disabled={sharesPageNum >= sharesTotalPages}
                  onclick={() => {
                    sharesPageNum++;
                    loadShares();
                  }}
                >
                  下一页
                </Button>
              </div>
            </div>
          {/if}
        </Card.Content>
      </Card.Root>
    </Tabs.Content>
  </Tabs.Root>
</div>

<!-- 文件详情对话框 -->
<Dialog.Root bind:open={detailDialogOpen}>
  <Dialog.Content class="max-h-[90vh] max-w-3xl overflow-y-auto">
    <Dialog.Header>
      <Dialog.Title>文件详情</Dialog.Title>
    </Dialog.Header>

    {#if detailLoading}
      <div class="flex items-center justify-center p-12">
        <div
          class="border-primary h-8 w-8 animate-spin rounded-full border-4 border-t-transparent"
        ></div>
      </div>
    {:else if selectedFile}
      <div class="space-y-6">
        <!-- 基本信息 -->
        <div>
          <h3 class="mb-3 font-semibold">基本信息</h3>
          <div class="bg-muted/50 grid gap-4 rounded-lg p-4 sm:grid-cols-2">
            <div>
              <p class="text-muted-foreground text-sm">文件名</p>
              <p class="font-medium">{selectedFile.fileName}</p>
            </div>
            <div>
              <p class="text-muted-foreground text-sm">大小</p>
              <p class="font-medium">{formatFileSize(selectedFile.fileSize)}</p>
            </div>
            <div>
              <p class="text-muted-foreground text-sm">类型</p>
              <p class="font-medium">{selectedFile.contentType}</p>
            </div>
            <div>
              <p class="text-muted-foreground text-sm">状态</p>
              <Badge variant={getStatusBadgeVariant(selectedFile.status)}>
                {selectedFile.statusDesc}
              </Badge>
            </div>
            <div class="sm:col-span-2">
              <p class="text-muted-foreground text-sm">文件哈希</p>
              <p class="font-mono text-sm break-all">{selectedFile.fileHash}</p>
            </div>
          </div>
        </div>

        <Separator />

        <!-- 所有权信息 -->
        <div>
          <h3 class="mb-3 font-semibold">所有权信息</h3>
          <div class="bg-muted/50 grid gap-4 rounded-lg p-4 sm:grid-cols-2">
            <div>
              <p class="text-muted-foreground text-sm">当前所有者</p>
              <p class="font-medium">{selectedFile.ownerName}</p>
            </div>
            <div>
              <p class="text-muted-foreground text-sm">来源类型</p>
              <Badge
                variant={selectedFile.isOriginal ? "default" : "secondary"}
              >
                {selectedFile.isOriginal ? "原始上传" : "分享保存"}
              </Badge>
            </div>
            {#if !selectedFile.isOriginal}
              <div>
                <p class="text-muted-foreground text-sm">原始上传者</p>
                <p class="font-medium">
                  {selectedFile.originOwnerName || "未知"}
                </p>
              </div>
              <div>
                <p class="text-muted-foreground text-sm">直接分享者</p>
                <p class="font-medium">
                  {selectedFile.sharedFromUserName || "未知"}
                </p>
              </div>
              <div>
                <p class="text-muted-foreground text-sm">分享链路深度</p>
                <p class="font-medium">{selectedFile.depth} 级</p>
              </div>
            {/if}
          </div>
        </div>

        <!-- 分享链路 -->
        {#if selectedFile.provenanceChain && selectedFile.provenanceChain.length > 0}
          <Separator />
          <div>
            <h3 class="mb-3 font-semibold">分享链路</h3>
            <div class="space-y-2">
              {#each selectedFile.provenanceChain as node, i}
                <div class="flex items-center gap-2">
                  <div
                    class="bg-primary text-primary-foreground flex h-6 w-6 items-center justify-center rounded-full text-xs"
                  >
                    {node.depth}
                  </div>
                  <span class="font-medium">{node.userName}</span>
                  {#if node.shareCode}
                    <span class="text-muted-foreground text-sm">
                      (分享码: {node.shareCode})
                    </span>
                  {/if}
                  {#if i < selectedFile.provenanceChain.length - 1}
                    <span class="text-muted-foreground">→</span>
                  {/if}
                </div>
              {/each}
            </div>
          </div>
        {/if}

        <!-- 相关分享 -->
        {#if selectedFile.relatedShares && selectedFile.relatedShares.length > 0}
          <Separator />
          <div>
            <h3 class="mb-3 font-semibold">相关分享</h3>
            <div class="space-y-2">
              {#each selectedFile.relatedShares as share}
                <div
                  class="bg-muted/50 flex items-center justify-between rounded-lg p-3"
                >
                  <div>
                    <span class="font-mono">{share.shareCode}</span>
                    <span class="text-muted-foreground ml-2 text-sm">
                      by {share.sharerName}
                    </span>
                  </div>
                  <Badge variant={getStatusBadgeVariant(share.status)}>
                    {share.status === AdminShareStatus.CANCELLED
                      ? "已取消"
                      : share.status === AdminShareStatus.ACTIVE
                        ? "有效"
                        : "已过期"}
                  </Badge>
                </div>
              {/each}
            </div>
          </div>
        {/if}

        <!-- 最近访问日志 -->
        {#if selectedFile.recentAccessLogs && selectedFile.recentAccessLogs.length > 0}
          <Separator />
          <div>
            <h3 class="mb-3 font-semibold">最近访问日志</h3>
            <div class="space-y-2">
              {#each selectedFile.recentAccessLogs as log}
                <div
                  class="bg-muted/50 flex items-center justify-between rounded-lg p-3 text-sm"
                >
                  <div>
                    <span class="font-medium">{log.actorUserName}</span>
                    <Badge variant="outline" class="ml-2"
                      >{log.actionTypeDesc}</Badge
                    >
                  </div>
                  <span class="text-muted-foreground"
                    >{formatDateTime(log.accessTime)}</span
                  >
                </div>
              {/each}
            </div>
          </div>
        {/if}

        <!-- 操作按钮 -->
        <Separator />
        <div class="flex items-center justify-between">
          <div class="flex gap-2">
            {#if selectedFile.status === AdminFileStatus.COMPLETED}
              {#if isPreviewable(selectedFile.contentType)}
                <Button
                  variant="outline"
                  onclick={() =>
                    handlePreview(
                      selectedFile!.fileHash,
                      selectedFile!.contentType,
                      selectedFile!.fileName,
                    )}
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
                      d="M15 12a3 3 0 11-6 0 3 3 0 016 0z"
                    />
                    <path
                      stroke-linecap="round"
                      stroke-linejoin="round"
                      stroke-width="2"
                      d="M2.458 12C3.732 7.943 7.523 5 12 5c4.478 0 8.268 2.943 9.542 7-1.274 4.057-5.064 7-9.542 7-4.477 0-8.268-2.943-9.542-7z"
                    />
                  </svg>
                  预览
                </Button>
              {/if}
              <Button
                variant="outline"
                disabled={downloadingHash === selectedFile.fileHash}
                onclick={() =>
                  handleDownload(
                    selectedFile!.fileHash,
                    selectedFile!.fileName,
                  )}
              >
                {#if downloadingHash === selectedFile.fileHash}
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
                      d="M4 16v1a3 3 0 003 3h10a3 3 0 003-3v-1m-4-4l-4 4m0 0l-4-4m4 4V4"
                    />
                  </svg>
                {/if}
                下载
              </Button>
            {/if}
          </div>
          <div class="flex gap-2">
            {#if selectedFile.status === AdminFileStatus.COMPLETED}
              <Button
                variant="outline"
                onclick={() =>
                  handleUpdateStatus(selectedFile!.id, AdminFileStatus.DELETED)}
                disabled={statusUpdatingId === selectedFile!.id}
              >
                {statusUpdatingId === selectedFile!.id
                  ? "处理中..."
                  : "标记删除"}
              </Button>
            {:else if selectedFile.status === AdminFileStatus.DELETED}
              <Button
                variant="outline"
                onclick={() =>
                  handleUpdateStatus(
                    selectedFile!.id,
                    AdminFileStatus.COMPLETED,
                  )}
                disabled={statusUpdatingId === selectedFile!.id}
              >
                {statusUpdatingId === selectedFile!.id
                  ? "处理中..."
                  : "恢复文件"}
              </Button>
            {/if}
            <Button
              variant="destructive"
              onclick={() => {
                if (selectedFile) {
                  confirmDelete("file", selectedFile.id, selectedFile.fileName);
                  detailDialogOpen = false;
                }
              }}
            >
              强制删除
            </Button>
          </div>
        </div>
      </div>
    {/if}
  </Dialog.Content>
</Dialog.Root>

<!-- 删除确认对话框 -->
<Dialog.Root bind:open={deleteDialogOpen}>
  <Dialog.Content class="sm:max-w-md">
    <Dialog.Header>
      <Dialog.Title
        >确认{deleteTarget?.type === "file"
          ? "删除文件"
          : "取消分享"}</Dialog.Title
      >
      <Dialog.Description>
        {#if deleteTarget?.type === "file"}
          确定要永久删除文件 "{deleteTarget?.name}" 吗？此操作不可撤销。
        {:else}
          确定要取消分享 "{deleteTarget?.name}" 吗？
        {/if}
      </Dialog.Description>
    </Dialog.Header>

    <div class="space-y-4">
      <div>
        <label for="delete-reason" class="mb-2 block text-sm font-medium"
          >操作原因（可选）</label
        >
        <Input
          id="delete-reason"
          bind:value={deleteReason}
          placeholder="请输入原因..."
        />
      </div>
    </div>

    <Dialog.Footer>
      <Button variant="outline" onclick={() => (deleteDialogOpen = false)}
        >取消</Button
      >
      <Button variant="destructive" onclick={executeDelete} disabled={deleting}>
        {deleting ? "处理中..." : "确认"}
      </Button>
    </Dialog.Footer>
  </Dialog.Content>
</Dialog.Root>

<!-- 访问日志对话框 -->
<Dialog.Root bind:open={logsDialogOpen}>
  <Dialog.Content class="max-h-[80vh] max-w-2xl overflow-y-auto">
    <Dialog.Header>
      <Dialog.Title>分享访问日志</Dialog.Title>
      <Dialog.Description>分享码: {logsShareCode}</Dialog.Description>
    </Dialog.Header>

    {#if logsLoading}
      <div class="flex items-center justify-center p-12">
        <div
          class="border-primary h-8 w-8 animate-spin rounded-full border-4 border-t-transparent"
        ></div>
      </div>
    {:else if accessLogs.length === 0}
      <div class="text-muted-foreground p-12 text-center">暂无访问日志</div>
    {:else}
      <div class="space-y-2">
        {#each accessLogs as log}
          <div
            class="bg-muted/50 flex items-center justify-between rounded-lg p-3"
          >
            <div class="space-y-1">
              <div class="flex items-center gap-2">
                <span class="font-medium">{log.actorUserName}</span>
                <Badge variant="outline">{log.actionTypeDesc}</Badge>
              </div>
              {#if log.fileName}
                <p class="text-muted-foreground text-sm">{log.fileName}</p>
              {/if}
              <p class="text-muted-foreground text-xs">IP: {log.actorIp}</p>
            </div>
            <span class="text-muted-foreground text-sm">
              {formatDateTime(log.accessTime)}
            </span>
          </div>
        {/each}
      </div>

      {#if logsTotalPages > 1}
        <div class="mt-4 flex items-center justify-between border-t pt-4">
          <span class="text-muted-foreground text-sm">
            第 {logsPageNum} / {logsTotalPages} 页
          </span>
          <div class="flex gap-2">
            <Button
              variant="outline"
              size="sm"
              disabled={logsPageNum <= 1 || logsLoading}
              onclick={() => {
                logsPageNum--;
                loadLogsPage();
              }}
            >
              上一页
            </Button>
            <Button
              variant="outline"
              size="sm"
              disabled={logsPageNum >= logsTotalPages || logsLoading}
              onclick={() => {
                logsPageNum++;
                loadLogsPage();
              }}
            >
              下一页
            </Button>
          </div>
        </div>
      {/if}
    {/if}

    <Dialog.Footer>
      <Button onclick={() => (logsDialogOpen = false)}>关闭</Button>
    </Dialog.Footer>
  </Dialog.Content>
</Dialog.Root>

<!-- 文件预览对话框 -->
<Dialog.Root
  open={previewDialogOpen}
  onOpenChange={(open) => {
    if (!open) closePreview();
  }}
>
  <Dialog.Content class="max-h-[90vh] max-w-4xl overflow-y-auto">
    <Dialog.Header>
      <Dialog.Title>文件预览</Dialog.Title>
      <Dialog.Description>{previewFileName}</Dialog.Description>
    </Dialog.Header>
    {#if previewLoading}
      <div class="flex items-center justify-center p-16">
        <div
          class="border-primary h-8 w-8 animate-spin rounded-full border-4 border-t-transparent"
        ></div>
        <span class="text-muted-foreground ml-3 text-sm">加载中...</span>
      </div>
    {:else if previewUrl}
      <FilePreview
        url={previewUrl}
        contentType={previewContentType}
        fileName={previewFileName}
      />
    {/if}
    <Dialog.Footer>
      <Button variant="outline" onclick={closePreview}>关闭</Button>
    </Dialog.Footer>
  </Dialog.Content>
</Dialog.Root>
