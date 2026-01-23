<script lang="ts">
  import * as Dialog from "$lib/components/ui/dialog";
  import { Badge } from "$lib/components/ui/badge";
  import type { SysOperationLog, AuditLogVO } from "$api/types";

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
  <Dialog.Content class="max-w-3xl">
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
        <div class="h-8 w-8 animate-spin rounded-full border-4 border-primary border-t-transparent"></div>
      </div>
    {:else}
      <div class="grid gap-4">
        {#if detail}
          <div class="grid gap-4 rounded-lg border p-4">
            <div class="grid gap-4 md:grid-cols-2">
              <div>
                <p class="text-xs text-muted-foreground">请求URL</p>
                <p class="mt-1 break-all font-mono text-sm">{detail.requestUrl || "-"}</p>
              </div>
              <div>
                <p class="text-xs text-muted-foreground">请求方法</p>
                <p class="mt-1 font-mono text-sm">{detail.method || detail.requestMethod || "-"}</p>
              </div>
              <div>
                <p class="text-xs text-muted-foreground">IP地址</p>
                <p class="mt-1 font-mono text-sm">{detail.requestIp || "-"}</p>
              </div>
              <div>
                <p class="text-xs text-muted-foreground">执行耗时</p>
                <p class="mt-1 font-mono text-sm">{detail.executionTime ?? 0}ms</p>
              </div>
              <div>
                <p class="text-xs text-muted-foreground">状态</p>
                <div class="mt-1">
                  <Badge variant={getStatusVariant(detail.status)}>
                    {getStatusLabel(detail.status)}
                  </Badge>
                </div>
              </div>
              <div>
                <p class="text-xs text-muted-foreground">操作用户</p>
                <p class="mt-1 text-sm">{detail.username || detail.userId || "-"}</p>
              </div>
            </div>

            <div>
              <p class="text-xs text-muted-foreground">请求参数</p>
              <pre class="mt-1 max-h-[180px] overflow-auto rounded-md bg-muted/30 p-3 font-mono text-xs">{detail.requestParam || "-"}</pre>
            </div>

            <div>
              <p class="text-xs text-muted-foreground">响应结果</p>
              <pre class="mt-1 max-h-[180px] overflow-auto rounded-md bg-muted/30 p-3 font-mono text-xs">{detail.responseResult || "-"}</pre>
            </div>

            {#if detail.errorMsg}
              <div>
                <p class="text-xs text-muted-foreground">错误信息</p>
                <pre class="mt-1 max-h-[120px] overflow-auto rounded-md bg-destructive/10 p-3 font-mono text-xs text-destructive">{detail.errorMsg}</pre>
              </div>
            {/if}
          </div>
        {:else}
          <div class="p-6 text-center text-sm text-muted-foreground">无法获取详情</div>
        {/if}
      </div>
    {/if}
  </Dialog.Content>
</Dialog.Root>
