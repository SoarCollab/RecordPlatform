<script lang="ts">
  import { onDestroy } from "svelte";
  import * as echarts from "echarts";
  import type { ErrorOperationStatsVO } from "$api/types";

  interface Props {
    data: ErrorOperationStatsVO[];
    title?: string;
    loading?: boolean;
    onItemClick?: (item: ErrorOperationStatsVO) => void;
  }

  let {
    data,
    title = "错误类型分布",
    loading = false,
    onItemClick,
  }: Props = $props();

  let chart: echarts.ECharts | null = null;
  let resizeObserver: ResizeObserver | null = null;

  const colors = [
    "#ef4444",
    "#f97316",
    "#eab308",
    "#22c55e",
    "#3b82f6",
    "#8b5cf6",
  ];

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

  function initAction(node: HTMLDivElement) {
    resizeObserver = new ResizeObserver((entries) => {
      const entry = entries[0];
      if (!entry) return;
      const { width, height } = entry.contentRect;
      if (width === 0 || height === 0) return;

      if (!chart) {
        chart = echarts.init(node, undefined, { renderer: "canvas" });
        chart.on("click", (params) => {
          if (params.dataIndex !== undefined && onItemClick) {
            onItemClick(data[params.dataIndex]);
          }
        });
        updateChart();
      } else {
        chart.resize();
      }
    });
    resizeObserver.observe(node);

    return {
      destroy() {
        resizeObserver?.disconnect();
        resizeObserver = null;
        chart?.dispose();
        chart = null;
      },
    };
  }

  $effect(() => {
    if (data && chart) {
      updateChart();
    }
  });

  onDestroy(() => {
    resizeObserver?.disconnect();
    resizeObserver = null;
    chart?.dispose();
    chart = null;
  });
</script>

<div class="bg-card/50 rounded-xl border p-4">
  <p class="text-sm font-medium">{title}</p>
  {#if loading}
    <div class="flex h-[200px] items-center justify-center">
      <div
        class="border-primary h-6 w-6 animate-spin rounded-full border-2 border-t-transparent"
      ></div>
    </div>
  {:else if data.length === 0}
    <div
      class="text-muted-foreground flex h-[200px] items-center justify-center text-sm"
    >
      暂无错误数据
    </div>
  {:else}
    <div use:initAction class="mt-2 h-[200px] w-full"></div>
  {/if}
</div>
