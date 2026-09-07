<script lang="ts">
  import { onMount, type Snippet } from "svelte";
  import { goto } from "$app/navigation";
  import { getToken } from "$api/client";
  import { getStoredScopeHint, landingForScope } from "$utils/authSession";
  let { children }: { children: Snippet } = $props();
  let redirecting = $state(false);
  onMount(() => {
    if (getToken()) {
      redirecting = true;
      void goto(landingForScope(getStoredScopeHint()), { replaceState: true });
    }
  });
</script>

{#if !redirecting}
  <div class="bg-muted/50 flex min-h-screen items-center justify-center px-4">
    <div class="w-full max-w-md">{@render children()}</div>
  </div>
{/if}
