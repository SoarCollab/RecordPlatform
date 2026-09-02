<script lang="ts">
  import * as echarts from "echarts";
  import type { ErrorOperationStatsVO } from "$api/types";
  import { chartLifecycle } from "./chartLifecycle";

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

  const colors = [
    "#ef4444",
    "#f97316",
    "#eab308",
    "#22c55e",
    "#3b82f6",
    "#8b5cf6",
  ];

  type ChartState = {
    data: ErrorOperationStatsVO[];
    onItemClick?: (item: ErrorOperationStatsVO) => void;
  };

  function updateChart(
    chart: echarts.ECharts,
    chartData: ErrorOperationStatsVO[],
  ) {
    if (!chartData.length) {
      chart.clear();
      return;
    }

    const pieData = chartData.slice(0, 6).map((item, idx) => ({
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

  function initAction(node: HTMLDivElement, state: ChartState) {
    return chartLifecycle(
      node,
      state,
      (chart, current) => updateChart(chart, current.data),
      (chart, getState) => {
        chart.on("click", (params) => {
          const current = getState();
          if (params.dataIndex !== undefined && current.onItemClick) {
            const item = current.data[params.dataIndex];
            if (item) current.onItemClick(item);
          }
        });
      },
    );
  }
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
    <div
      use:initAction={{ data, onItemClick }}
      class="mt-2 h-[200px] w-full"
    ></div>
  {/if}
</div>
