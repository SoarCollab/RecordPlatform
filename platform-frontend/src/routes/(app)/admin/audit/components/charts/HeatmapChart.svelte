<script lang="ts">
  import { onDestroy } from "svelte";
  import * as echarts from "echarts";
  import type { UserTimeDistributionVO } from "$api/types";

  interface Props {
    data: UserTimeDistributionVO[];
    title?: string;
    loading?: boolean;
  }

  let { data, title = "操作时间热力图", loading = false }: Props = $props();

  let chart: echarts.ECharts | null = null;

  const days = ["周一", "周二", "周三", "周四", "周五", "周六", "周日"];
  const hours = Array.from({ length: 24 }, (_, i) => `${i}:00`);

  function handleResize() {
    chart?.resize();
  }

  function updateChart() {
    if (!chart) return;

    if (!data.length) {
      chart.clear();
      return;
    }

    const heatmapData: Array<[number, number, number]> = [];
    const maxValue = data.reduce(
      (max, item) => Math.max(max, item.operationCount),
      0,
    );

    for (let day = 0; day < 7; day++) {
      for (let hour = 0; hour < 24; hour++) {
        const item = data.find(
          (d) => d.dayOfWeek === day && d.hourOfDay === hour,
        );
        heatmapData.push([hour, day, item?.operationCount ?? 0]);
      }
    }

    chart.setOption({
      tooltip: {
        position: "top",
        backgroundColor: "rgba(0, 0, 0, 0.75)",
        borderColor: "transparent",
        textStyle: { color: "#fff" },
        formatter: (params: unknown) => {
          const p = params as { data: [number, number, number] };
          const [hour, day, value] = p.data;
          return `<div class="font-medium">${days[day]} ${hour}:00</div><div>操作数: <strong>${value.toLocaleString()}</strong></div>`;
        },
      },
      grid: {
        left: "8%",
        right: "10%",
        top: "8%",
        bottom: "15%",
      },
      xAxis: {
        type: "category",
        data: hours,
        splitArea: { show: true },
        axisLabel: {
          color: "rgba(156, 163, 175, 0.8)",
          fontSize: 10,
          interval: 2,
        },
        axisLine: { show: false },
        axisTick: { show: false },
      },
      yAxis: {
        type: "category",
        data: days,
        splitArea: { show: true },
        axisLabel: {
          color: "rgba(156, 163, 175, 0.8)",
          fontSize: 11,
        },
        axisLine: { show: false },
        axisTick: { show: false },
      },
      visualMap: {
        min: 0,
        max: maxValue || 100,
        calculable: true,
        orient: "horizontal",
        left: "center",
        bottom: "0%",
        inRange: {
          color: [
            "#e0f2fe",
            "#7dd3fc",
            "#38bdf8",
            "#0ea5e9",
            "#0284c7",
            "#0369a1",
          ],
        },
        textStyle: {
          color: "rgba(156, 163, 175, 0.8)",
          fontSize: 10,
        },
        itemWidth: 12,
        itemHeight: 80,
      },
      series: [
        {
          name: "操作数",
          type: "heatmap",
          data: heatmapData,
          label: { show: false },
          emphasis: {
            itemStyle: {
              shadowBlur: 10,
              shadowColor: "rgba(0, 0, 0, 0.5)",
            },
          },
        },
      ],
    });
  }

  function initAction(node: HTMLDivElement) {
    requestAnimationFrame(() => {
      if (!chart) {
        chart = echarts.init(node, undefined, { renderer: "canvas" });
        updateChart();
        window.addEventListener("resize", handleResize);
      }
    });

    return {
      destroy() {
        window.removeEventListener("resize", handleResize);
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
    window.removeEventListener("resize", handleResize);
    chart?.dispose();
    chart = null;
  });
</script>

<div class="bg-card/50 rounded-xl border p-4">
  <p class="text-sm font-medium">{title}</p>
  {#if loading}
    <div class="flex h-[260px] items-center justify-center">
      <div
        class="border-primary h-6 w-6 animate-spin rounded-full border-2 border-t-transparent"
      ></div>
    </div>
  {:else if data.length === 0}
    <div
      class="text-muted-foreground flex h-[260px] items-center justify-center text-sm"
    >
      暂无时间分布数据
    </div>
  {:else}
    <div use:initAction class="mt-2 h-[260px] w-full"></div>
  {/if}
</div>
