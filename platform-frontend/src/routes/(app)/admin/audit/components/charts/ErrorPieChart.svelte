<script lang="ts">
  import { onMount, onDestroy } from "svelte";
  import * as echarts from "echarts";
  import type { ErrorOperationStatsVO } from "$api/types";

  interface Props {
    data: ErrorOperationStatsVO[];
    title?: string;
    loading?: boolean;
    onItemClick?: (item: ErrorOperationStatsVO) => void;
  }

  let { data, title = "错误类型分布", loading = false, onItemClick }: Props = $props();

  let chartContainer: HTMLDivElement;
  let chart: echarts.ECharts | null = null;

  const colors = ["#ef4444", "#f97316", "#eab308", "#22c55e", "#3b82f6", "#8b5cf6"];

  function initChart() {
    if (!chartContainer || chart) return;
    chart = echarts.init(chartContainer, undefined, { renderer: "canvas" });

    chart.on("click", (params) => {
      if (params.dataIndex !== undefined && onItemClick) {
        onItemClick(data[params.dataIndex]);
      }
    });

    updateChart();
    window.addEventListener("resize", handleResize);
  }

  function handleResize() {
    chart?.resize();
  }

  function updateChart() {
    if (!chart) return;

    if (!data.length) {
      chart.clear();
      return;
    }

    const pieData = data.slice(0, 6).map((item, idx) => ({
      name: item.module + " - " + item.operationType,
      value: item.errorCount,
      itemStyle: { color: colors[idx % colors.length] },
    }));

    chart.setOption({
      tooltip: {
        trigger: "item",
        backgroundColor: "rgba(0, 0, 0, 0.75)",
        borderColor: "transparent",
        textStyle: { color: "#fff" },
        formatter: (params: unknown) => {
          const p = params as { name: string; value: number; percent: number };
          return `<div class="font-medium">${p.name}</div><div>错误数: <strong>${p.value.toLocaleString()}</strong> (${p.percent}%)</div>`;
        },
      },
      legend: {
        type: "scroll",
        orient: "vertical",
        right: 10,
        top: "middle",
        textStyle: {
          color: "rgba(156, 163, 175, 0.8)",
          fontSize: 11,
        },
        formatter: (name: string) => {
          if (name.length > 15) return name.slice(0, 15) + "...";
          return name;
        },
      },
      series: [
        {
          name: "错误分布",
          type: "pie",
          radius: ["45%", "70%"],
          center: ["35%", "50%"],
          avoidLabelOverlap: false,
          label: { show: false },
          emphasis: {
            label: {
              show: true,
              fontSize: 12,
              fontWeight: "bold",
            },
          },
          data: pieData,
        },
      ],
    });
  }

  $effect(() => {
    if (chart) {
      updateChart();
    }
  });

  onMount(() => {
    initChart();
  });

  onDestroy(() => {
    window.removeEventListener("resize", handleResize);
    chart?.dispose();
    chart = null;
  });
</script>

<div class="rounded-xl border bg-card/50 p-4">
  <p class="text-sm font-medium">{title}</p>
  {#if loading}
    <div class="flex h-[200px] items-center justify-center">
      <div class="h-6 w-6 animate-spin rounded-full border-2 border-primary border-t-transparent"></div>
    </div>
  {:else if data.length === 0}
    <div class="flex h-[200px] items-center justify-center text-sm text-muted-foreground">
      暂无错误数据
    </div>
  {:else}
    <div bind:this={chartContainer} class="mt-2 h-[200px] w-full"></div>
  {/if}
</div>
