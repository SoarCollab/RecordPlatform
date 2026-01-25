<script lang="ts">
  import { onMount } from "svelte";
  import { goto } from "$app/navigation";
  import { useNotifications } from "$stores/notifications.svelte";
  import { useAuth } from "$stores/auth.svelte";
  import {
    exportAuditLogs,
    getAuditConfigs,
    updateAuditConfig,
    checkAuditAnomalies,
    backupAuditLogs,
  } from "$api/endpoints/system";
  import type { AuditConfigVO, AuditLogQueryParams } from "$api/types";
  import { Button } from "$lib/components/ui/button";
  import * as Tabs from "$lib/components/ui/tabs";
  import AuditDashboard from "./components/AuditDashboard.svelte";
  import AuditLogList from "./components/AuditLogList.svelte";
  import SensitiveLogList from "./components/SensitiveLogList.svelte";
  import SettingsDrawer from "./components/dialogs/SettingsDrawer.svelte";

  const notifications = useNotifications();
  const auth = useAuth();

  let activeTab = $state("dashboard");
  let isExporting = $state(false);
  let settingsOpen = $state(false);

  // 设置数据
  let auditConfigs = $state<AuditConfigVO[]>([]);
  let loadingConfigs = $state(false);
  let anomalies = $state<Record<string, unknown> | null>(null);
  let checkingAnomalies = $state(false);
  let backupResult = $state<string | null>(null);
  let backupRunning = $state(false);

  const canAccess = $derived(auth.isAdminOrMonitor);

  onMount(() => {
    if (!canAccess) {
      notifications.error("权限不足", "仅管理员或监控员可访问此页面");
      goto("/dashboard");
    }
  });

  async function handleExport() {
    isExporting = true;
    try {
      const params: AuditLogQueryParams = {};
      const blob = await exportAuditLogs(params);
      const url = URL.createObjectURL(blob);
      const a = document.createElement("a");
      a.href = url;
      a.download = `audit_logs_${new Date().toISOString().split("T")[0]}.xlsx`;
      document.body.appendChild(a);
      a.click();
      document.body.removeChild(a);
      URL.revokeObjectURL(url);
      notifications.success("导出成功");
    } catch (err) {
      notifications.error(
        "导出失败",
        err instanceof Error ? err.message : "请稍后重试",
      );
    } finally {
      isExporting = false;
    }
  }

  async function loadConfigs() {
    loadingConfigs = true;
    try {
      auditConfigs = await getAuditConfigs();
    } catch (err) {
      notifications.error(
        "加载配置失败",
        err instanceof Error ? err.message : "请稍后重试",
      );
    } finally {
      loadingConfigs = false;
    }
  }

  async function handleSaveConfig(config: AuditConfigVO) {
    const ok = await updateAuditConfig(config);
    if (!ok) {
      throw new Error("更新失败");
    }
    auditConfigs = auditConfigs.map((c) => (c.id === config.id ? config : c));
    notifications.success("更新成功");
  }

  async function handleCheckAnomalies() {
    checkingAnomalies = true;
    anomalies = null;
    try {
      anomalies = await checkAuditAnomalies();
      notifications.success("检查完成");
    } catch (err) {
      notifications.error(
        "检查失败",
        err instanceof Error ? err.message : "请稍后重试",
      );
    } finally {
      checkingAnomalies = false;
    }
  }

  async function handleBackup(days: number, deleteAfter: boolean) {
    backupRunning = true;
    backupResult = null;
    try {
      backupResult = await backupAuditLogs({
        days,
        deleteAfterBackup: deleteAfter,
      });
      notifications.success("备份完成");
    } catch (err) {
      notifications.error(
        "备份失败",
        err instanceof Error ? err.message : "请稍后重试",
      );
    } finally {
      backupRunning = false;
    }
  }

  function handleViewHighFreq() {
    activeTab = "sensitive";
  }

  function handleOpenSettings() {
    if (auditConfigs.length === 0) {
      loadConfigs();
    }
    settingsOpen = true;
  }
</script>

<svelte:head>
  <title>系统审计 - 存证平台</title>
</svelte:head>

<div class="space-y-6">
  <div class="flex items-center justify-between">
    <div>
      <h1 class="text-2xl font-bold">系统审计</h1>
      <p class="text-muted-foreground">操作日志、敏感操作与审计配置</p>
    </div>
    <div class="flex items-center gap-2">
      <Button onclick={handleExport} disabled={isExporting} variant="outline">
        {#if isExporting}
          <div class="mr-2 h-4 w-4 animate-spin rounded-full border-2 border-current border-t-transparent"></div>
        {/if}
        导出日志
      </Button>
      <Button variant="outline" onclick={handleOpenSettings}>
        <svg class="mr-2 h-4 w-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
          <path
            stroke-linecap="round"
            stroke-linejoin="round"
            stroke-width="2"
            d="M10.325 4.317c.426-1.756 2.924-1.756 3.35 0a1.724 1.724 0 002.573 1.066c1.543-.94 3.31.826 2.37 2.37a1.724 1.724 0 001.065 2.572c1.756.426 1.756 2.924 0 3.35a1.724 1.724 0 00-1.066 2.573c.94 1.543-.826 3.31-2.37 2.37a1.724 1.724 0 00-2.572 1.065c-.426 1.756-2.924 1.756-3.35 0a1.724 1.724 0 00-2.573-1.066c-1.543.94-3.31-.826-2.37-2.37a1.724 1.724 0 00-1.065-2.572c-1.756-.426-1.756-2.924 0-3.35a1.724 1.724 0 001.066-2.573c-.94-1.543.826-3.31 2.37-2.37.996.608 2.296.07 2.572-1.065z"
          />
          <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M15 12a3 3 0 11-6 0 3 3 0 016 0z" />
        </svg>
        设置
      </Button>
    </div>
  </div>

  <Tabs.Root value={activeTab} onValueChange={(v) => (activeTab = v)}>
    <Tabs.List>
      <Tabs.Trigger value="dashboard">仪表盘</Tabs.Trigger>
      <Tabs.Trigger value="logs">日志查询</Tabs.Trigger>
      <Tabs.Trigger value="sensitive">敏感操作</Tabs.Trigger>
    </Tabs.List>

    <Tabs.Content value="dashboard" class="mt-4">
      <AuditDashboard onViewHighFreq={handleViewHighFreq} />
    </Tabs.Content>

    <Tabs.Content value="logs" class="mt-4">
      <AuditLogList />
    </Tabs.Content>

    <Tabs.Content value="sensitive" class="mt-4">
      <SensitiveLogList />
    </Tabs.Content>
  </Tabs.Root>
</div>

<SettingsDrawer
  open={settingsOpen}
  onOpenChange={(open) => (settingsOpen = open)}
  configs={auditConfigs}
  {loadingConfigs}
  onRefreshConfigs={loadConfigs}
  onSaveConfig={handleSaveConfig}
  {anomalies}
  {checkingAnomalies}
  onCheckAnomalies={handleCheckAnomalies}
  onClearAnomalies={() => (anomalies = null)}
  {backupResult}
  {backupRunning}
  onBackup={handleBackup}
  onClearBackupResult={() => (backupResult = null)}
/>
