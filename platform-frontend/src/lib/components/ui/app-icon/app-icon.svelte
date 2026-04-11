<script lang="ts">
  import { appIconMap, type AppIconName } from "./app-icon-map";

  type Props = {
    name: AppIconName;
    class?: string;
  };

  let props: Props = $props();

  const className = $derived(props.class ?? "");
  const Icon = $derived(appIconMap[props.name]);
</script>

{#if Icon}
  <Icon class={className} aria-hidden="true" />
{/if}
