<script lang="ts">
  import { onMount } from "svelte";

  let { onEnter }: { onEnter: () => void } = $props();
  onMount(() => {
    onEnter();
  });
</script>

<p data-testid="protected-child">Protected route content</p>
