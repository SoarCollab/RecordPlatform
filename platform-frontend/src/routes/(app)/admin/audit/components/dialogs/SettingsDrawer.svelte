<script lang="ts">
  import { Button } from "$lib/components/ui/button";
  import { Input } from "$lib/components/ui/input";
  import * as Dialog from "$lib/components/ui/dialog";
  import * as Table from "$lib/components/ui/table";
  import type { AuditConfigVO } from "$api/types";

  interface Props {
    open: boolean;
    onOpenChange: (open: boolean) => void;
    // Configs
    configs: AuditConfigVO[];
    loadingConfigs: boolean;
    onRefreshConfigs: () => void;
    onSaveConfig: (config: AuditConfigVO) => Promise<void>;
    // Anomaly check
    anomalies: Record<string, unknown> | null;
    checkingAnomalies: boolean;
    onCheckAnomalies: () => void;
    onClearAnomalies: () => void;
    // Backup
    backupResult: string | null;
    backupRunning: boolean;
    onBackup: (days: number, deleteAfter: boolean) => void;
    onClearBackupResult: () => void;
  }

  let {
    open,
    onOpenChange,
    configs,
    loadingConfigs,
    onRefreshConfigs,
    onSaveConfig,
    anomalies,
    checkingAnomalies,
    onCheckAnomalies,
    onClearAnomalies,
    backupResult,
    backupRunning,
    onBackup,
    onClearBackupResult,
  }: Props = $props();

  let activeSection = $state<"configs" | "anomaly" | "backup">("configs");

  // Config editing
  let configDialogOpen = $state(false);
  let editingConfig = $state<AuditConfigVO | null>(null);
  let editingConfigValue = $state("");
  let savingConfig = $state(false);

  // Backup settings
  let backupDays = $state(180);
  let backupDeleteAfter = $state(false);

  function openEditConfig(cfg: AuditConfigVO) {
    editingConfig = cfg;
    editingConfigValue = cfg.configValue;
    configDialogOpen = true;
  }

  async function handleSaveConfig() {
    if (!editingConfig) return;
    savingConfig = true;
    try {
      await onSaveConfig({
        ...editingConfig,
        configValue: editingConfigValue,
      });
      configDialogOpen = false;
      editingConfig = null;
    } finally {
      savingConfig = false;
    }
  }

  function handleBackup() {
    onBackup(backupDays, backupDeleteAfter);
  }
</script>

<Dialog.Root {open} {onOpenChange}>
  <Dialog.Content class="max-w-2xl max-h-[85vh] overflow-y-auto">
    <Dialog.Header>
      <Dialog.Title class="flex items-center gap-2">
        <svg class="h-5 w-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
          <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M10.325 4.317c.426-1.756 2.924-1.756 3.35 0a1.724 1.724 0 002.573 1.066c1.543-.94 3.31.826 2.37 2.37a1.724 1.724 0 001.065 2.572c1.756.426 1.756 2.924 0 3.35a1.724 1.724 0 00-1.066 2.573c.94 1.543-.826 3.31-2.37 2.37a1.724 1.724 0 00-2.572 1.065c-.426 1.756-2.924 1.756-3.35 0a1.724 1.724 0 00-2.573-1.066c-1.543.94-3.31-.826-2.37-2.37a1.724 1.724 0 00-1.065-2.572c-1.756-.426-1.756-2.924 0-3.35a1.724 1.724 0 001.066-2.573c-.94-1.543.826-3.31 2.37-2.37.996.608 2.296.07 2.572-1.065z" />
          <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M15 12a3 3 0 11-6 0 3 3 0 016 0z" />
        </svg>
        审计设置
      </Dialog.Title>
      <Dialog.Description>管理审计配置、异常检查和日志备份</Dialog.Description>
    </Dialog.Header>

    <div class="mt-4 flex gap-1 rounded-lg bg-muted/50 p-1">
      <button
        class="flex-1 rounded-md px-3 py-1.5 text-sm font-medium transition-colors {activeSection === 'configs' ? 'bg-background shadow' : 'hover:bg-background/50'}"
        onclick={() => (activeSection = "configs")}
      >
        审计配置
      </button>
      <button
        class="flex-1 rounded-md px-3 py-1.5 text-sm font-medium transition-colors {activeSection === 'anomaly' ? 'bg-background shadow' : 'hover:bg-background/50'}"
        onclick={() => (activeSection = "anomaly")}
      >
        异常检查
      </button>
      <button
        class="flex-1 rounded-md px-3 py-1.5 text-sm font-medium transition-colors {activeSection === 'backup' ? 'bg-background shadow' : 'hover:bg-background/50'}"
        onclick={() => (activeSection = "backup")}
      >
        日志备份
      </button>
    </div>

    {#if activeSection === "configs"}
      <div class="mt-4 space-y-4">
        <div class="flex items-center justify-between">
          <p class="text-sm text-muted-foreground">配置审计系统参数</p>
          <Button variant="outline" size="sm" onclick={onRefreshConfigs} disabled={loadingConfigs}>
            刷新
          </Button>
        </div>

        {#if loadingConfigs}
          <div class="flex items-center justify-center p-8">
            <div class="h-6 w-6 animate-spin rounded-full border-2 border-primary border-t-transparent"></div>
          </div>
        {:else if configs.length === 0}
          <div class="p-6 text-center text-sm text-muted-foreground">暂无配置</div>
        {:else}
          <div class="rounded-lg border">
            <Table.Root>
              <Table.Header>
                <Table.Row>
                  <Table.Head>配置项</Table.Head>
                  <Table.Head>值</Table.Head>
                  <Table.Head class="text-right">操作</Table.Head>
                </Table.Row>
              </Table.Header>
              <Table.Body>
                {#each configs as cfg (cfg.id)}
                  <Table.Row>
                    <Table.Cell>
                      <div>
                        <p class="font-mono text-xs">{cfg.configKey}</p>
                        {#if cfg.description}
                          <p class="mt-0.5 text-xs text-muted-foreground">{cfg.description}</p>
                        {/if}
                      </div>
                    </Table.Cell>
                    <Table.Cell class="font-mono text-xs">{cfg.configValue}</Table.Cell>
                    <Table.Cell class="text-right">
                      <Button size="sm" variant="ghost" onclick={() => openEditConfig(cfg)}>
                        编辑
                      </Button>
                    </Table.Cell>
                  </Table.Row>
                {/each}
              </Table.Body>
            </Table.Root>
          </div>
        {/if}
      </div>
    {:else if activeSection === "anomaly"}
      <div class="mt-4 space-y-4">
        <p class="text-sm text-muted-foreground">检测系统中的异常操作行为</p>

        <div class="flex gap-2">
          <Button onclick={onCheckAnomalies} disabled={checkingAnomalies}>
            {checkingAnomalies ? "检查中..." : "执行检查"}
          </Button>
          <Button variant="outline" onclick={onClearAnomalies} disabled={!anomalies}>
            清空结果
          </Button>
        </div>

        {#if anomalies}
          <pre class="max-h-[300px] overflow-auto rounded-lg border bg-muted/20 p-4 font-mono text-xs">{JSON.stringify(anomalies, null, 2)}</pre>
        {/if}
      </div>
    {:else if activeSection === "backup"}
      <div class="mt-4 space-y-4">
        <p class="text-sm text-muted-foreground">备份历史审计日志</p>

        <div class="grid gap-4">
          <div class="grid gap-2">
            <label for="backupDays" class="text-sm">备份天数</label>
            <Input id="backupDays" type="number" bind:value={backupDays} min={1} max={3650} />
          </div>

          <label class="flex cursor-pointer items-center gap-2 text-sm">
            <input
              type="checkbox"
              bind:checked={backupDeleteAfter}
              class="h-4 w-4 rounded border accent-primary"
            />
            <span>备份后删除原日志</span>
          </label>

          <div class="flex gap-2">
            <Button onclick={handleBackup} disabled={backupRunning}>
              {backupRunning ? "执行中..." : "开始备份"}
            </Button>
            <Button variant="outline" onclick={onClearBackupResult} disabled={!backupResult}>
              清空结果
            </Button>
          </div>

          {#if backupResult}
            <pre class="max-h-[200px] overflow-auto rounded-lg border bg-muted/20 p-4 font-mono text-xs">{backupResult}</pre>
          {/if}
        </div>
      </div>
    {/if}
  </Dialog.Content>
</Dialog.Root>

<!-- Config Edit Dialog -->
<Dialog.Root bind:open={configDialogOpen}>
  <Dialog.Content class="max-w-md">
    <Dialog.Header>
      <Dialog.Title>编辑配置</Dialog.Title>
      {#if editingConfig}
        <Dialog.Description class="font-mono text-xs">{editingConfig.configKey}</Dialog.Description>
      {/if}
    </Dialog.Header>
    <div class="space-y-4">
      {#if editingConfig?.description}
        <p class="text-sm text-muted-foreground">{editingConfig.description}</p>
      {/if}
      <Input bind:value={editingConfigValue} placeholder="配置值" />
      <div class="flex justify-end gap-2">
        <Button variant="secondary" onclick={() => (configDialogOpen = false)}>取消</Button>
        <Button onclick={handleSaveConfig} disabled={savingConfig}>
          {savingConfig ? "保存中..." : "保存"}
        </Button>
      </div>
    </div>
  </Dialog.Content>
</Dialog.Root>
