<script lang="ts">
  import * as Dialog from "$components/ui/dialog";
  import { Badge } from "$components/ui/badge";
  import type { SysOperationLog, AuditLogVO } from "$api/types";
  import { formatAuditPayload } from "../audit-log-display";

  interface Props {
    open: boolean;
    log: AuditLogVO | null;
    detail: SysOperationLog | null;
    loading: boolean;
    onOpenChange: (open: boolean) => void;
  }

  let { open, log, detail, loading, onOpenChange }: Props = $props();

  function getStatusVariant(status: number): "default" | "destructive" {
    return status === 0 ? "default" : "destructive";
  }

  function getStatusLabel(status: number): string {
    return status === 0 ? "成功" : "失败";
  }
</script>

<Dialog.Root {open} {onOpenChange}>
  <Dialog.Content
    class="max-h-[calc(100vh-2rem)] w-[calc(100vw-2rem)] max-w-5xl grid-rows-[auto_minmax(0,1fr)] overflow-hidden sm:max-w-5xl"
  >
    <Dialog.Header>
      <Dialog.Title>日志详情</Dialog.Title>
      {#if log}
        <Dialog.Description>
          {log.module} · {log.action} · {log.username}
        </Dialog.Description>
      {/if}
    </Dialog.Header>

    {#if loading}
      <div class="flex items-center justify-center p-10">
        <div
          class="border-primary h-8 w-8 animate-spin rounded-full border-4 border-t-transparent"
        ></div>
      </div>
    {:else}
      <div class="min-h-0 min-w-0 overflow-y-auto pr-1">
        {#if detail}
          <div class="grid min-w-0 gap-4 rounded-lg border p-4">
            <div class="grid min-w-0 gap-4 md:grid-cols-2">
              <div class="min-w-0">
                <p class="text-muted-foreground text-xs">请求 URL</p>
                <p class="mt-1 font-mono text-sm break-all">
                  {detail.requestUrl || "-"}
                </p>
              </div>
              <div class="min-w-0">
                <p class="text-muted-foreground text-xs">HTTP 请求方式</p>
                <p class="mt-1 font-mono text-sm break-all">
                  {detail.requestMethod || "-"}
                </p>
              </div>
              <div class="min-w-0 md:col-span-2">
                <p class="text-muted-foreground text-xs">处理方法</p>
                <p class="mt-1 font-mono text-sm break-all">
                  {detail.method || "-"}
                </p>
              </div>
              <div class="min-w-0 md:col-span-2">
                <p class="text-muted-foreground text-xs">操作描述</p>
                <p class="mt-1 text-sm break-words">
                  {detail.description || "-"}
                </p>
              </div>
              <div class="min-w-0">
                <p class="text-muted-foreground text-xs">操作时间</p>
                <p class="mt-1 font-mono text-sm break-all">
                  {detail.operationTime || "-"}
                </p>
              </div>
              <div class="min-w-0">
                <p class="text-muted-foreground text-xs">IP 地址</p>
                <p class="mt-1 font-mono text-sm break-all">
                  {detail.requestIp || "-"}
                </p>
              </div>
              <div class="min-w-0">
                <p class="text-muted-foreground text-xs">执行耗时</p>
                <p class="mt-1 font-mono text-sm">
                  {detail.executionTime == null
                    ? "-"
                    : `${detail.executionTime}ms`}
                </p>
              </div>
              <div class="min-w-0">
                <p class="text-muted-foreground text-xs">状态</p>
                <div class="mt-1">
                  <Badge variant={getStatusVariant(detail.status)}>
                    {getStatusLabel(detail.status)}
                  </Badge>
                </div>
              </div>
              <div class="min-w-0">
                <p class="text-muted-foreground text-xs">操作用户</p>
                <p class="mt-1 text-sm break-all">
                  {detail.username || detail.userId || "-"}
                </p>
              </div>
            </div>

            <div class="min-w-0">
              <p class="text-muted-foreground text-xs">请求参数</p>
              <pre
                class="bg-muted/30 mt-1 max-h-[240px] max-w-full overflow-auto rounded-md p-3 font-mono text-xs break-words whitespace-pre-wrap">{formatAuditPayload(
                  detail.requestParam,
                )}</pre>
            </div>

            <div class="min-w-0">
              <p class="text-muted-foreground text-xs">响应结果</p>
              <pre
                class="bg-muted/30 mt-1 max-h-[240px] max-w-full overflow-auto rounded-md p-3 font-mono text-xs break-words whitespace-pre-wrap">{formatAuditPayload(
                  detail.responseResult,
                )}</pre>
            </div>

            <div class="min-w-0">
              <p class="text-muted-foreground text-xs">错误信息</p>
              <pre
                data-testid="audit-error-message"
                class="bg-destructive/10 text-destructive mt-1 max-h-[180px] max-w-full overflow-auto rounded-md p-3 font-mono text-xs break-words whitespace-pre-wrap">{formatAuditPayload(
                  detail.errorMsg,
                )}</pre>
            </div>
          </div>
        {:else}
          <div class="text-muted-foreground p-6 text-center text-sm">
            无法获取详情
          </div>
        {/if}
      </div>
    {/if}
  </Dialog.Content>
</Dialog.Root>
