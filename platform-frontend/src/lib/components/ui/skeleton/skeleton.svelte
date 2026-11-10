<script lang="ts">
  import { cn } from "#lib/utils.ts";

  let { class: className, ...props } = $props();
</script>

<div
  class={cn("bg-muted animate-pulse rounded-md", className)}
  {...props}
></div>
