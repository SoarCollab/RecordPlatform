<script lang="ts">
  import { onDestroy, onMount } from "svelte";
  import {
    createManifestBackfillRun,
    createManifestReferenceCensus,
    getManifestBackfillRun,
    listManifestBackfillItems,
    listManifestBackfillRuns,
    markManifestReferenceSweepObject,
    pauseManifestBackfillRun,
    resumeManifestBackfillRun,
    retryManifestBackfillItem,
  } from "$api/endpoints/manifest-backfill";
  import type {
    ManifestBackfillItemVO,
    ManifestBackfillMode,
    ManifestBackfillRunVO,
    ManifestReferenceCensusVO,
  } from "$api/types";
  import { useNotifications } from "$stores/notifications.svelte";
  import { formatDateTime } from "$utils/format";
  import { Button } from "$components/ui/button";
  import { Badge } from "$components/ui/badge";
  import { Input } from "$components/ui/input";
  import * as Card from "$components/ui/card";
  import * as Table from "$components/ui/table";

  const notifications = useNotifications();

  let runs = $state<ManifestBackfillRunVO[]>([]);
  let selectedRun = $state<ManifestBackfillRunVO | null>(null);
  let items = $state<ManifestBackfillItemVO[]>([]);
  let nextCursor = $state<string | undefined>();
  let cursor = $state<string | undefined>();
  let mode = $state<ManifestBackfillMode>("SCAN");
  let snapshotRunId = $state("");
  let statusFilter = $state("");
  let classificationFilter = $state("");
  let reasonFilter = $state("");
  let census = $state<ManifestReferenceCensusVO | null>(null);
  let sweepPath = $state("");
  let sweepHash = $state("");
  let loading = $state(true);
  let itemsLoading = $state(false);
  let acting = $state(false);
  let refreshTimer: ReturnType<typeof setInterval> | undefined;

  /** Loads the newest runs and keeps the selected run synchronized. */
  async function loadRuns(quiet = false) {
    if (!quiet) loading = true;
    try {
      runs = await listManifestBackfillRuns();
      if (selectedRun) {
        selectedRun = runs.find((run) => run.id === selectedRun?.id) ?? null;
      }
    } catch (error) {
      if (!quiet) notifyError("加载治理任务失败", error);
    } finally {
      if (!quiet) loading = false;
    }
  }

  /** Selects one run and resets its cursor pagination. */
  async function selectRun(run: ManifestBackfillRunVO) {
    selectedRun = run;
    cursor = undefined;
    await loadItems();
  }

  /** Refreshes the selected run and its first/current item page. */
  async function refreshSelected() {
    if (!selectedRun) return;
    try {
      selectedRun = await getManifestBackfillRun(selectedRun.id);
      runs = runs.map((run) =>
        run.id === selectedRun?.id ? selectedRun : run,
      );
      await loadItems();
    } catch (error) {
      notifyError("刷新任务失败", error);
    }
  }

  /** Loads one bounded item page using exact machine-readable filters. */
  async function loadItems() {
    if (!selectedRun) return;
    itemsLoading = true;
    try {
      const page = await listManifestBackfillItems(selectedRun.id, {
        cursor,
        status: statusFilter || undefined,
        classification: classificationFilter || undefined,
        reason: reasonFilter || undefined,
        limit: 50,
      });
      items = page.records;
      nextCursor = page.nextCursor;
    } catch (error) {
      notifyError("加载文件结果失败", error);
    } finally {
      itemsLoading = false;
    }
  }

  /** Creates a scan or derivative run from an explicit frozen source ID. */
  async function createRun() {
    if (mode !== "SCAN" && !snapshotRunId.trim()) {
      notifications.error("DRY_RUN/APPLY 必须填写来源扫描 ID");
      return;
    }
    acting = true;
    try {
      const run = await createManifestBackfillRun({
        mode,
        snapshotRunId: mode === "SCAN" ? undefined : snapshotRunId.trim(),
      });
      notifications.success("治理任务已创建");
      await loadRuns(true);
      await selectRun(run);
    } catch (error) {
      notifyError("创建任务失败", error);
    } finally {
      acting = false;
    }
  }

  /** Applies a pause or resume transition and refreshes all visible state. */
  async function transitionRun(action: "pause" | "resume") {
    if (!selectedRun) return;
    acting = true;
    try {
      selectedRun =
        action === "pause"
          ? await pauseManifestBackfillRun(selectedRun.id)
          : await resumeManifestBackfillRun(selectedRun.id);
      notifications.success(action === "pause" ? "任务已暂停" : "任务已恢复");
      await loadRuns(true);
    } catch (error) {
      notifyError(action === "pause" ? "暂停失败" : "恢复失败", error);
    } finally {
      acting = false;
    }
  }

  /** Requeues one failed item through the audited admin endpoint. */
  async function retryItem(item: ManifestBackfillItemVO) {
    if (!selectedRun) return;
    acting = true;
    try {
      selectedRun = await retryManifestBackfillItem(selectedRun.id, item.id);
      notifications.success("失败项已重新入队");
      await loadItems();
    } catch (error) {
      notifyError("重试失败", error);
    } finally {
      acting = false;
    }
  }

  /** Creates a fresh completed reference census for operator evidence. */
  async function runCensus() {
    acting = true;
    try {
      census = await createManifestReferenceCensus();
      notifications.success("引用普查已完成");
    } catch (error) {
      notifyError("引用普查失败", error);
    } finally {
      acting = false;
    }
  }

  /** Requests a feature-gated grace mark for one exact object identity. */
  async function markSweepObject() {
    if (!sweepPath.trim() || !sweepHash.trim()) {
      notifications.error("对象路径和密文哈希不能为空");
      return;
    }
    acting = true;
    try {
      const mark = await markManifestReferenceSweepObject({
        storagePath: sweepPath.trim(),
        cipherHash: sweepHash.trim(),
      });
      notifications.success(`对象已进入保护期：${mark.id}`);
      sweepPath = "";
      sweepHash = "";
    } catch (error) {
      notifyError("对象标记失败", error);
    } finally {
      acting = false;
    }
  }

  /** Resets machine-readable item filters and reloads from the first page. */
  async function resetFilters() {
    statusFilter = "";
    classificationFilter = "";
    reasonFilter = "";
    cursor = undefined;
    await loadItems();
  }

  /** Shows a stable notification for unknown API failures. */
  function notifyError(title: string, error: unknown) {
    notifications.error(
      title,
      error instanceof Error ? error.message : "请稍后重试",
    );
  }

  /** Selects a bounded badge variant for durable run/item lifecycle states. */
  function badgeVariant(status: string) {
    if (["FAILED", "UNRECOVERABLE"].includes(status))
      return "destructive" as const;
    if (
      ["COMPLETED", "SNAPSHOT_READY", "BACKFILLED", "ACTIVE"].includes(status)
    ) {
      return "default" as const;
    }
    if (["PAUSED", "REUPLOAD_REQUIRED", "IGNORED"].includes(status)) {
      return "secondary" as const;
    }
    return "outline" as const;
  }

  onMount(() => {
    void loadRuns();
    refreshTimer = setInterval(() => {
      void loadRuns(true);
      if (
        selectedRun &&
        ["PLANNED", "SCANNING", "APPLYING"].includes(selectedRun.status)
      ) {
        void refreshSelected();
      }
    }, 5000);
  });

  onDestroy(() => {
    if (refreshTimer) clearInterval(refreshTimer);
  });
</script>

<svelte:head><title>清单治理 - RecordPlatform</title></svelte:head>

<div class="space-y-6 p-6">
  <div class="flex flex-wrap items-start justify-between gap-4">
    <div>
      <h1 class="text-2xl font-semibold tracking-tight">分片清单治理</h1>
      <p class="text-muted-foreground mt-1 text-sm">
        扫描历史文件、审核确定性证据，并通过独立开关执行回填与引用安全清理。
      </p>
    </div>
    <Button variant="outline" onclick={() => loadRuns()} disabled={loading}
      >刷新任务</Button
    >
  </div>

  <div class="grid gap-6 xl:grid-cols-[360px_minmax(0,1fr)]">
    <div class="space-y-6">
      <Card.Root>
        <Card.Header>
          <Card.Title>创建治理任务</Card.Title>
          <Card.Description>APPLY 默认由服务端灰度开关拒绝。</Card.Description>
        </Card.Header>
        <Card.Content class="space-y-4">
          <label class="block space-y-2 text-sm font-medium">
            <span>模式</span>
            <select
              bind:value={mode}
              class="border-input bg-background h-9 w-full rounded-md border px-3 text-sm"
            >
              <option value="SCAN">SCAN · 生成冻结快照</option>
              <option value="DRY_RUN">DRY_RUN · 审核快照</option>
              <option value="APPLY">APPLY · 发布可回填清单</option>
            </select>
          </label>
          {#if mode !== "SCAN"}
            <label class="block space-y-2 text-sm font-medium">
              <span>来源扫描外部 ID</span>
              <Input
                bind:value={snapshotRunId}
                placeholder="粘贴 SNAPSHOT_READY 任务 ID"
              />
            </label>
          {/if}
          <Button class="w-full" onclick={createRun} disabled={acting}
            >创建任务</Button
          >
        </Card.Content>
      </Card.Root>

      <Card.Root>
        <Card.Header>
          <Card.Title>最近任务</Card.Title>
          <Card.Description>仅显示当前租户，按创建时间倒序。</Card.Description>
        </Card.Header>
        <Card.Content class="max-h-[480px] space-y-2 overflow-y-auto">
          {#if loading}
            <p class="text-muted-foreground py-8 text-center text-sm">
              正在加载…
            </p>
          {:else if runs.length === 0}
            <p class="text-muted-foreground py-8 text-center text-sm">
              暂无治理任务
            </p>
          {:else}
            {#each runs as run (run.id)}
              <button
                class="hover:bg-accent w-full rounded-lg border p-3 text-left transition-colors {selectedRun?.id ===
                run.id
                  ? 'border-primary bg-accent'
                  : ''}"
                onclick={() => selectRun(run)}
              >
                <div class="flex items-center justify-between gap-2">
                  <span class="font-medium">{run.mode}</span>
                  <Badge variant={badgeVariant(run.status)}>{run.status}</Badge>
                </div>
                <p
                  class="text-muted-foreground mt-2 truncate font-mono text-xs"
                >
                  {run.id}
                </p>
                <p class="text-muted-foreground mt-1 text-xs">
                  {formatDateTime(run.createTime)}
                </p>
              </button>
            {/each}
          {/if}
        </Card.Content>
      </Card.Root>
    </div>

    <div class="min-w-0 space-y-6">
      {#if selectedRun}
        <Card.Root>
          <Card.Header
            class="gap-4 sm:flex-row sm:items-start sm:justify-between"
          >
            <div>
              <Card.Title class="flex flex-wrap items-center gap-2">
                {selectedRun.mode}
                <Badge variant={badgeVariant(selectedRun.status)}
                  >{selectedRun.status}</Badge
                >
              </Card.Title>
              <Card.Description class="mt-2 font-mono"
                >{selectedRun.id}</Card.Description
              >
            </div>
            <div class="flex gap-2">
              {#if ["PLANNED", "SCANNING", "APPLYING"].includes(selectedRun.status)}
                <Button
                  variant="outline"
                  onclick={() => transitionRun("pause")}
                  disabled={acting}>暂停</Button
                >
              {:else if selectedRun.status === "PAUSED"}
                <Button
                  onclick={() => transitionRun("resume")}
                  disabled={acting}>恢复</Button
                >
              {/if}
              <Button
                variant="outline"
                onclick={refreshSelected}
                disabled={itemsLoading}>刷新</Button
              >
            </div>
          </Card.Header>
          <Card.Content>
            <div class="grid grid-cols-2 gap-3 sm:grid-cols-4 xl:grid-cols-7">
              {#each [["总数", selectedRun.totalCount], ["待处理", selectedRun.pendingCount], ["已回填", selectedRun.backfilledCount], ["需重传", selectedRun.reuploadCount], ["不可恢复", selectedRun.unrecoverableCount], ["已忽略", selectedRun.ignoredCount], ["失败", selectedRun.failedCount]] as metric}
                <div class="bg-muted/40 rounded-lg border p-3">
                  <p class="text-muted-foreground text-xs">{metric[0]}</p>
                  <p class="mt-1 text-xl font-semibold">{metric[1]}</p>
                </div>
              {/each}
            </div>
            <dl class="mt-4 grid gap-2 text-sm lg:grid-cols-2">
              <div>
                <dt class="text-muted-foreground inline">快照摘要：</dt>
                <dd class="inline font-mono break-all">
                  {selectedRun.snapshotDigest ?? "尚未封存"}
                </dd>
              </div>
              <div>
                <dt class="text-muted-foreground inline">来源快照：</dt>
                <dd class="inline font-mono">
                  {selectedRun.snapshotRunId ?? "新扫描"}
                </dd>
              </div>
            </dl>
          </Card.Content>
        </Card.Root>

        <Card.Root>
          <Card.Header>
            <Card.Title>逐文件结果</Card.Title>
            <Card.Description
              >按稳定状态、分类和原因码精确筛选；原始证据 payload 不向列表暴露。</Card.Description
            >
          </Card.Header>
          <Card.Content class="space-y-4">
            <div class="grid gap-3 md:grid-cols-4">
              <Input bind:value={statusFilter} placeholder="状态，如 FAILED" />
              <Input
                bind:value={classificationFilter}
                placeholder="分类，如 BACKFILLABLE"
              />
              <Input
                bind:value={reasonFilter}
                placeholder="原因码，如 MISSING_SIZE"
              />
              <div class="flex gap-2">
                <Button
                  class="flex-1"
                  onclick={() => {
                    cursor = undefined;
                    void loadItems();
                  }}>查询</Button
                >
                <Button variant="outline" onclick={resetFilters}>重置</Button>
              </div>
            </div>

            <div class="overflow-x-auto rounded-lg border">
              <Table.Root>
                <Table.Header>
                  <Table.Row>
                    <Table.Head>文件</Table.Head><Table.Head>状态</Table.Head
                    ><Table.Head>分类 / 原因</Table.Head><Table.Head
                      >证据摘要</Table.Head
                    ><Table.Head>操作</Table.Head>
                  </Table.Row>
                </Table.Header>
                <Table.Body>
                  {#if itemsLoading}
                    <Table.Row
                      ><Table.Cell colspan={5} class="py-10 text-center"
                        >正在加载…</Table.Cell
                      ></Table.Row
                    >
                  {:else if items.length === 0}
                    <Table.Row
                      ><Table.Cell
                        colspan={5}
                        class="text-muted-foreground py-10 text-center"
                        >暂无匹配结果</Table.Cell
                      ></Table.Row
                    >
                  {:else}
                    {#each items as item (item.id)}
                      <Table.Row>
                        <Table.Cell
                          ><p class="font-mono text-xs">{item.fileId}</p>
                          <p class="text-muted-foreground text-xs">
                            v{item.fileVersion}
                          </p></Table.Cell
                        >
                        <Table.Cell
                          ><Badge variant={badgeVariant(item.status)}
                            >{item.status}</Badge
                          ></Table.Cell
                        >
                        <Table.Cell
                          ><p class="font-medium">{item.classification}</p>
                          <p class="text-muted-foreground text-xs">
                            {item.reasonCode}
                          </p></Table.Cell
                        >
                        <Table.Cell
                          class="max-w-64 truncate font-mono text-xs"
                          title={item.evidenceDigest}
                          >{item.evidenceDigest}</Table.Cell
                        >
                        <Table.Cell>
                          {#if item.status === "FAILED" && item.retryable}
                            <Button
                              size="sm"
                              variant="outline"
                              onclick={() => retryItem(item)}
                              disabled={acting}>重试</Button
                            >
                          {:else}<span class="text-muted-foreground text-xs"
                              >—</span
                            >{/if}
                        </Table.Cell>
                      </Table.Row>
                    {/each}
                  {/if}
                </Table.Body>
              </Table.Root>
            </div>
            <div class="flex justify-end">
              <Button
                variant="outline"
                disabled={!nextCursor || itemsLoading}
                onclick={() => {
                  cursor = nextCursor;
                  void loadItems();
                }}>下一页</Button
              >
            </div>
          </Card.Content>
        </Card.Root>
      {:else}
        <Card.Root
          ><Card.Content class="text-muted-foreground py-24 text-center"
            >选择左侧任务查看冻结快照与逐文件结果</Card.Content
          ></Card.Root
        >
      {/if}

      <div class="grid gap-6 lg:grid-cols-2">
        <Card.Root>
          <Card.Header
            ><Card.Title>引用普查</Card.Title><Card.Description
              >物化 manifest、版本、分享、存证、proof、Saga 与降级修复持有关系。</Card.Description
            ></Card.Header
          >
          <Card.Content class="space-y-3">
            <Button onclick={runCensus} disabled={acting}>立即执行普查</Button>
            {#if census}
              <div class="bg-muted/40 rounded-lg border p-3 text-sm">
                <p>
                  <span class="text-muted-foreground">状态：</span
                  >{census.status}
                </p>
                <p>
                  <span class="text-muted-foreground">已知引用：</span
                  >{census.knownReferenceCount}
                </p>
                <p>
                  <span class="text-muted-foreground">未知持有：</span
                  >{census.unknownHoldCount}
                </p>
                <p class="mt-1 font-mono text-xs break-all">
                  {census.censusDigest}
                </p>
              </div>
            {/if}
          </Card.Content>
        </Card.Root>

        <Card.Root>
          <Card.Header
            ><Card.Title>引用安全清理标记</Card.Title><Card.Description
              >默认关闭；启用后仍需 fresh census、保护期与删除时 HEAD 复核。</Card.Description
            ></Card.Header
          >
          <Card.Content class="space-y-3">
            <Input
              bind:value={sweepPath}
              placeholder="storage/tenant/123/chunk/sha256..."
            />
            <Input bind:value={sweepHash} placeholder="密文对象哈希" />
            <Button
              variant="outline"
              onclick={markSweepObject}
              disabled={acting}>申请保护期标记</Button
            >
          </Card.Content>
        </Card.Root>
      </div>
    </div>
  </div>
</div>
