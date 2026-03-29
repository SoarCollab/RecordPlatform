<script lang="ts">
  import { cn } from "$lib/utils.js";

  interface Props {
    checked?: boolean;
    disabled?: boolean;
    indeterminate?: boolean;
    onchange?: (event: Event) => void;
    class?: string;
    "aria-label"?: string;
    [key: string]: unknown;
  }

  let {
    checked = $bindable(false),
    disabled = false,
    indeterminate = false,
    onchange,
    class: className,
    ...restProps
  }: Props = $props();
</script>

<button
  type="button"
  role="checkbox"
  aria-checked={indeterminate ? "mixed" : checked}
  aria-disabled={disabled}
  {disabled}
  class={cn(
    "peer inline-flex h-[18px] w-[18px] shrink-0 items-center justify-center rounded-[4px] border-2 shadow-xs transition-colors",
    "focus-visible:ring-ring focus-visible:ring-offset-background focus-visible:ring-2 focus-visible:ring-offset-2 focus-visible:outline-none",
    "disabled:cursor-not-allowed disabled:opacity-50",
    checked || indeterminate
      ? "border-primary bg-primary text-primary-foreground"
      : "border-muted-foreground/40 hover:border-muted-foreground/70 bg-transparent",
    className,
  )}
  onclick={() => {
    if (disabled) return;
    checked = !checked;
    // Dispatch a synthetic change event for compatibility
    const syntheticEvent = new Event("change", { bubbles: true });
    Object.defineProperty(syntheticEvent, "currentTarget", {
      value: { checked },
      writable: false,
    });
    onchange?.(syntheticEvent);
  }}
  {...restProps}
>
  {#if indeterminate}
    <svg
      class="h-3 w-3"
      fill="none"
      stroke="currentColor"
      stroke-width="3"
      viewBox="0 0 24 24"
    >
      <path d="M5 12h14" />
    </svg>
  {:else if checked}
    <svg
      class="h-3 w-3"
      fill="none"
      stroke="currentColor"
      stroke-width="3"
      stroke-linecap="round"
      stroke-linejoin="round"
      viewBox="0 0 24 24"
    >
      <path d="M20 6L9 17l-5-5" />
    </svg>
  {/if}
</button>
