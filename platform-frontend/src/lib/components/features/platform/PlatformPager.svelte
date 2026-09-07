<script lang="ts">
  import { Button } from "$components/ui/button";
  import type { PlatformPage } from "$api/types";
  let {
    page,
    disabled = false,
    onPage,
  }: {
    page: PlatformPage<unknown> | null;
    disabled?: boolean;
    onPage: (page: number) => void;
  } = $props();
</script>

{#if page}
  <nav
    aria-label="分页"
    class="mt-5 flex items-center justify-between gap-3 text-sm"
  >
    <p class="text-muted-foreground" aria-live="polite">
      共 {page.total} 条，第 {page.current} / {Math.max(1, page.pages)} 页
    </p>
    <div class="flex gap-2">
      <Button
        variant="outline"
        size="sm"
        disabled={disabled || page.current <= 1}
        onclick={() => {
          if (page) onPage(page.current - 1);
        }}>上一页</Button
      ><Button
        variant="outline"
        size="sm"
        disabled={disabled || page.current >= page.pages}
        onclick={() => {
          if (page) onPage(page.current + 1);
        }}>下一页</Button
      >
    </div>
  </nav>
{/if}
