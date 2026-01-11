<script lang="ts">
  import { onMount } from "svelte";
  import { useAuth } from "$stores/auth.svelte";
  import { useNotifications } from "$stores/notifications.svelte";
  import { formatDateTime, formatFileSize } from "$utils/format";
  import { getFiles, getUserFileStats } from "$api/endpoints/files";
  import {
    getUnreadConversationCount,
    getUnreadAnnouncementCount,
  } from "$api/endpoints/messages";
  import { getPendingCount as getTicketPendingCount } from "$api/endpoints/tickets";
  import type { FileVO } from "$api/types";
  import { FileStatus, FileStatusLabel } from "$api/types";
  import { fly } from "svelte/transition";
  import AppIcon from "$components/ui/AppIcon.svelte";
  import { appIconMap } from "$components/ui/appIcon";
  import Skeleton from "$lib/components/ui/Skeleton.svelte";
  import * as Card from "$lib/components/ui/card";
  import { Button } from "$lib/components/ui/button";
  import { Badge } from "$lib/components/ui/badge";

  const auth = useAuth();
  const notifications = useNotifications();

  let loading = $state(true);
  let fileCount = $state(0);
  let storageUsed = $state(0);
  let unreadMessages = $state(0);
  let unreadAnnouncements = $state(0);
  let pendingTickets = $state(0);
  let recentFiles = $state<FileVO[]>([]);

  type DashboardIconName = keyof typeof appIconMap;

  type StatCard = {
    label: string;
    value: string;
    icon: DashboardIconName;
    color: string;
  };

  const stats = $derived<StatCard[]>([
    {
      label: "文件总数",
      value: fileCount.toString(),
      icon: "folder",
      color: "bg-blue-100 text-blue-600 dark:bg-blue-900/30 dark:text-blue-400",
    },
    {
      label: "存储用量",
      value: formatFileSize(storageUsed),
      icon: "database",
      color: "bg-purple-100 text-purple-600 dark:bg-purple-900/30 dark:text-purple-400",
    },
    {
      label: "未读消息",
      value: unreadMessages.toString(),
      icon: "message",
      color: "bg-green-100 text-green-600 dark:bg-green-900/30 dark:text-green-400",
    },
    {
      label: "待处理工单",
      value: pendingTickets.toString(),
      icon: "ticket",
      color: "bg-orange-100 text-orange-600 dark:bg-orange-900/30 dark:text-orange-400",
    },
  ]);

  onMount(() => {
    loadDashboardData();
  });

  async function loadDashboardData() {
    loading = true;

    try {
      const [
        statsResult,
        filesResult,
        messagesResult,
        announcementsResult,
        ticketsResult,
      ] = await Promise.allSettled([
        getUserFileStats(),
        getFiles({ pageNum: 1, pageSize: 5 }),
        getUnreadConversationCount(),
        getUnreadAnnouncementCount(),
        getTicketPendingCount(),
      ]);

      if (statsResult.status === "fulfilled") {
        const stats = statsResult.value;
        fileCount = stats.totalFiles;
        storageUsed = stats.totalStorage;
      }

      if (filesResult.status === "fulfilled") {
        recentFiles = filesResult.value.records;
      }

      if (messagesResult.status === "fulfilled") {
        unreadMessages = messagesResult.value.count;
      }

      if (announcementsResult.status === "fulfilled") {
        unreadAnnouncements = announcementsResult.value.count;
      }

      if (ticketsResult.status === "fulfilled") {
        pendingTickets = ticketsResult.value.count;
      }
    } catch (err) {
      notifications.error(
        "加载失败",
        err instanceof Error ? err.message : "请稍后重试"
      );
    } finally {
      loading = false;
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
  <title>仪表盘 - 存证平台</title>
</svelte:head>

<div class="space-y-6">
  <div class="flex items-center justify-between">
    <div>
      <h1 class="text-3xl font-bold tracking-tight">概览</h1>
      <p class="text-muted-foreground mt-1">欢迎回来，{auth.displayName}，这里是您的数字资产中心。</p>
    </div>
  </div>

  {#if loading}
    <div class="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
      {#each Array(4) as _}
        <Card.Root>
          <Card.Header class="flex flex-row items-center justify-between space-y-0 pb-2">
            <Skeleton class="h-4 w-24" />
            <Skeleton class="h-4 w-4" />
          </Card.Header>
          <Card.Content>
            <Skeleton class="h-8 w-16" />
          </Card.Content>
        </Card.Root>
      {/each}
    </div>
  {:else}
    <div class="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
      {#each stats as stat, i}
        <div in:fly={{ y: 20, duration: 300, delay: i * 50 }}>
          <Card.Root>
            <Card.Header class="flex flex-row items-center justify-between space-y-0 pb-2">
              <Card.Title class="text-sm font-medium text-muted-foreground">
                {stat.label}
              </Card.Title>
              <div class={`p-2 rounded-full ${stat.color}`}>
                <AppIcon name={stat.icon} class="h-4 w-4" />
              </div>
            </Card.Header>
            <Card.Content>
              <div class="text-2xl font-bold">{stat.value}</div>
            </Card.Content>
          </Card.Root>
        </div>
      {/each}
    </div>
  {/if}

  {#if unreadAnnouncements > 0}
    <div in:fly={{ y: 20, duration: 300 }}>
      <a
        href="/announcements"
        class="flex items-center justify-between rounded-lg border border-amber-200 bg-amber-50 p-4 transition-colors hover:bg-amber-100 dark:border-amber-800 dark:bg-amber-900/20 dark:hover:bg-amber-900/30"
      >
        <div class="flex items-center gap-3">
          <div
            class="flex h-10 w-10 items-center justify-center rounded-full bg-amber-100 text-amber-600 dark:bg-amber-900 dark:text-amber-400"
          >
            <AppIcon name="megaphone" class="h-5 w-5" />
          </div>
          <div>
            <p class="font-medium text-amber-900 dark:text-amber-100">
              您有 {unreadAnnouncements} 条未读公告
            </p>
            <p class="text-sm text-amber-700 dark:text-amber-300">
              点击查看系统公告
            </p>
          </div>
        </div>
        <AppIcon
          name="chevron-right"
          class="h-5 w-5 text-amber-600 dark:text-amber-400"
        />
      </a>
    </div>
  {/if}

  <div class="grid gap-6 md:grid-cols-1 lg:grid-cols-3">
    <div class="lg:col-span-2">
      <Card.Root class="h-full">
        <Card.Header class="flex flex-row items-center justify-between">
          <div class="space-y-1">
            <Card.Title>最近文件</Card.Title>
            <Card.Description>您最近上传或修改的文件</Card.Description>
          </div>
          <Button variant="ghost" size="sm" href="/files">查看全部</Button>
        </Card.Header>
        <Card.Content>
          {#if loading}
            <div class="space-y-4">
              {#each Array(3) as _}
                <div class="flex items-center justify-between">
                  <div class="flex items-center gap-3">
                    <Skeleton class="h-10 w-10 rounded-lg" />
                    <div>
                      <Skeleton class="h-4 w-32" />
                      <Skeleton class="mt-2 h-3 w-24" />
                    </div>
                  </div>
                  <Skeleton class="h-6 w-16 rounded-full" />
                </div>
              {/each}
            </div>
          {:else if recentFiles.length === 0}
            <div class="flex flex-col items-center justify-center py-8 text-center">
              <div
                class="mb-4 flex h-16 w-16 items-center justify-center rounded-full bg-muted"
              >
                <AppIcon name="folder" class="h-8 w-8 text-muted-foreground" />
              </div>
              <p class="text-muted-foreground">暂无文件</p>
              <Button variant="link" href="/upload" class="mt-2">
                上传您的第一个文件
              </Button>
            </div>
          {:else}
            <div class="space-y-1">
              {#each recentFiles as file, i (file.id)}
                <a
                  href="/files/{file.fileHash}"
                  class="flex items-center justify-between rounded-lg p-3 transition-colors hover:bg-muted/50"
                  in:fly={{ y: 10, duration: 300, delay: i * 50 }}
                >
                  <div class="flex items-center gap-3">
                    <div
                      class="flex h-10 w-10 items-center justify-center rounded-lg bg-primary/10 text-primary"
                    >
                      <AppIcon name="file-text" class="h-5 w-5" />
                    </div>
                    <div>
                      <p class="font-medium text-sm">{file.fileName}</p>
                      <p class="text-xs text-muted-foreground">
                        {formatFileSize(file.fileSize)} · {formatDateTime(
                          file.createTime,
                          "date"
                        )}
                      </p>
                    </div>
                  </div>
                   <Badge class={getStatusColorClass(file.status)} variant="outline">
                      {FileStatusLabel[file.status]}
                   </Badge>
                </a>
              {/each}
            </div>
          {/if}
        </Card.Content>
      </Card.Root>
    </div>

    <div class="space-y-4">
      <Card.Root>
        <Card.Header>
          <Card.Title>快速操作</Card.Title>
        </Card.Header>
        <Card.Content class="grid gap-4">
          <a
            href="/upload"
            class="group flex items-center gap-4 rounded-lg border p-4 hover:border-primary hover:bg-primary/5 transition-all"
          >
            <div
              class="flex h-10 w-10 items-center justify-center rounded-lg bg-primary text-primary-foreground group-hover:scale-110 transition-transform"
            >
              <AppIcon name="upload" class="h-5 w-5" />
            </div>
            <div>
              <p class="font-medium">上传文件</p>
              <p class="text-xs text-muted-foreground">存证您的文件</p>
            </div>
          </a>

          <a
            href="/files"
            class="group flex items-center gap-4 rounded-lg border p-4 hover:border-blue-500 hover:bg-blue-50 dark:hover:bg-blue-900/10 transition-all"
          >
            <div
              class="flex h-10 w-10 items-center justify-center rounded-lg bg-blue-500 text-white group-hover:scale-110 transition-transform"
            >
              <AppIcon name="folder" class="h-5 w-5" />
            </div>
            <div>
              <p class="font-medium">文件管理</p>
              <p class="text-xs text-muted-foreground">管理存证文件</p>
            </div>
          </a>

          <a
            href="/tickets/new"
            class="group flex items-center gap-4 rounded-lg border p-4 hover:border-orange-500 hover:bg-orange-50 dark:hover:bg-orange-900/10 transition-all"
          >
            <div
              class="flex h-10 w-10 items-center justify-center rounded-lg bg-orange-500 text-white group-hover:scale-110 transition-transform"
            >
              <AppIcon name="ticket" class="h-5 w-5" />
            </div>
            <div>
              <p class="font-medium">提交工单</p>
              <p class="text-xs text-muted-foreground">反馈问题</p>
            </div>
          </a>
        </Card.Content>
      </Card.Root>
    </div>
  </div>
</div>