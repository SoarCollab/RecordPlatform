<script lang="ts">
  import { navigating } from "$app/state";
  import { onDestroy } from "svelte";

  let progress = $state(0);
  let visible = $state(false);
  let interval: ReturnType<typeof setInterval> | undefined;
  let hideTimeoutId: ReturnType<typeof setTimeout> | undefined;
  let resetTimeoutId: ReturnType<typeof setTimeout> | undefined;

  $effect(() => {
    if (navigating.to) {
      start();
    } else {
      complete();
    }
  });

  // 组件销毁时清理所有定时器
  onDestroy(() => {
    cleanup();
  });

  function cleanup() {
    if (interval) {
      clearInterval(interval);
      interval = undefined;
    }
    if (hideTimeoutId) {
      clearTimeout(hideTimeoutId);
      hideTimeoutId = undefined;
    }
    if (resetTimeoutId) {
      clearTimeout(resetTimeoutId);
      resetTimeoutId = undefined;
    }
  }

  function start() {
    cleanup();
    visible = true;
    progress = 0;
    interval = setInterval(() => {
      progress += Math.random() * 0.1;
      if (progress > 0.85) {
        clearInterval(interval);
        interval = undefined;
      }
    }, 200);
  }

  function complete() {
    progress = 1;
    if (interval) {
      clearInterval(interval);
      interval = undefined;
    }
    hideTimeoutId = setTimeout(() => {
      visible = false;
      hideTimeoutId = undefined;
      resetTimeoutId = setTimeout(() => {
        progress = 0;
        resetTimeoutId = undefined;
      }, 200);
    }, 200);
  }
</script>

{#if visible}
  <div
    class="bg-primary fixed top-0 right-0 left-0 z-[100] h-0.5 transition-all duration-300 ease-out"
    style="width: {progress * 100}%"
  >
    <div
      class="absolute right-0 h-full w-[100px] translate-y-[-4px] rotate-3 opacity-100 shadow-[0_0_10px_#3b82f6]"
    ></div>
  </div>
{/if}
