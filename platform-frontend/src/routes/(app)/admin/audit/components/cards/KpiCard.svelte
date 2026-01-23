<script lang="ts">
  import { Badge } from "$lib/components/ui/badge";

  interface Props {
    title: string;
    value: number | string;
    subtitle?: string;
    trend?: number;
    badge?: string;
    badgeVariant?: "default" | "secondary" | "destructive" | "outline";
    icon?: "activity" | "error" | "alert" | "users";
    onclick?: () => void;
  }

  let {
    title,
    value,
    subtitle,
    trend,
    badge,
    badgeVariant = "default",
    icon = "activity",
    onclick,
  }: Props = $props();

  const iconPaths: Record<string, string> = {
    activity: "M22 12h-4l-3 9L9 3l-3 9H2",
    error: "M12 9v2m0 4h.01m-6.938 4h13.856c1.54 0 2.502-1.667 1.732-3L13.732 4c-.77-1.333-2.694-1.333-3.464 0L3.34 16c-.77 1.333.192 3 1.732 3z",
    alert: "M15 17h5l-1.405-1.405A2.032 2.032 0 0118 14.158V11a6.002 6.002 0 00-4-5.659V5a2 2 0 10-4 0v.341C7.67 6.165 6 8.388 6 11v3.159c0 .538-.214 1.055-.595 1.436L4 17h5m6 0v1a3 3 0 11-6 0v-1m6 0H9",
    users: "M17 20h5v-2a3 3 0 00-5.356-1.857M17 20H7m10 0v-2c0-.656-.126-1.283-.356-1.857M7 20H2v-2a3 3 0 015.356-1.857M7 20v-2c0-.656.126-1.283.356-1.857m0 0a5.002 5.002 0 019.288 0M15 7a3 3 0 11-6 0 3 3 0 016 0zm6 3a2 2 0 11-4 0 2 2 0 014 0zM7 10a2 2 0 11-4 0 2 2 0 014 0z",
  };

  const iconColors: Record<string, string> = {
    activity: "text-blue-500",
    error: "text-red-500",
    alert: "text-orange-500",
    users: "text-green-500",
  };

  function formatValue(val: number | string): string {
    if (typeof val === "string") return val;
    if (val >= 1000000) return (val / 1000000).toFixed(1) + "M";
    if (val >= 1000) return (val / 1000).toFixed(1) + "k";
    return val.toLocaleString();
  }
</script>

<button
  type="button"
  class="group rounded-xl border bg-card/50 p-4 text-left transition-all hover:border-primary/30 hover:bg-card/80 {onclick ? 'cursor-pointer' : 'cursor-default'}"
  onclick={onclick}
  disabled={!onclick}
>
  <div class="flex items-start justify-between">
    <p class="text-sm text-muted-foreground">{title}</p>
    <svg
      class="h-4 w-4 {iconColors[icon]}"
      fill="none"
      stroke="currentColor"
      stroke-width="2"
      stroke-linecap="round"
      stroke-linejoin="round"
      viewBox="0 0 24 24"
    >
      <path d={iconPaths[icon]} />
    </svg>
  </div>
  <div class="mt-2 flex items-baseline gap-2">
    <p class="text-2xl font-semibold">{formatValue(value)}</p>
    {#if badge}
      <Badge variant={badgeVariant} class="text-xs">{badge}</Badge>
    {/if}
  </div>
  {#if trend !== undefined}
    <p class="mt-1 text-xs {trend >= 0 ? 'text-green-500' : 'text-red-500'}">
      {trend >= 0 ? "+" : ""}{trend}% 较昨日
    </p>
  {:else if subtitle}
    <p class="mt-1 text-xs text-muted-foreground">{subtitle}</p>
  {/if}
</button>
