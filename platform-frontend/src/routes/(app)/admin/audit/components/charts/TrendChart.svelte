<script lang="ts">
  import { onMount, onDestroy } from "svelte";
  import * as echarts from "echarts";

  interface Props {
    data: Array<{ date: string; count: number }>;
    title?: string;
    loading?: boolean;
  }

  let { data, title = "7天操作趋势", loading = false }: Props = $props();

  let chartContainer: HTMLDivElement = $state()!;
  let chart: echarts.ECharts | null = null;

  function initChart() {
    if (!chartContainer || chart) return;
    chart = echarts.init(chartContainer, undefined, { renderer: "canvas" });
    updateChart();
    window.addEventListener("resize", handleResize);
  }

  function handleResize() {
    chart?.resize();
  }

  function updateChart() {
    if (!chart || !data.length) return;

    const dates = data.map((d) => d.date);
    const counts = data.map((d) => d.count);

    chart.setOption({
      tooltip: {
        trigger: "axis",
        backgroundColor: "rgba(0, 0, 0, 0.75)",
        borderColor: "transparent",
        textStyle: { color: "#fff" },
        formatter: (params: unknown) => {
          const p = params as Array<{ axisValue: string; value: number }>;
          if (!p || !p[0]) return "";
          return `<div class="font-medium">${p[0].axisValue}</div><div>操作数: <strong>${p[0].value.toLocaleString()}</strong></div>`;
        },
      },
      grid: {
        left: "3%",
        right: "4%",
        bottom: "3%",
        top: "12%",
        containLabel: true,
      },
      xAxis: {
        type: "category",
        boundaryGap: false,
        data: dates,
        axisLine: { lineStyle: { color: "rgba(156, 163, 175, 0.3)" } },
        axisLabel: {
          color: "rgba(156, 163, 175, 0.8)",
          fontSize: 11,
        },
      },
      yAxis: {
        type: "value",
        splitLine: { lineStyle: { color: "rgba(156, 163, 175, 0.1)" } },
        axisLabel: {
          color: "rgba(156, 163, 175, 0.8)",
          fontSize: 11,
          formatter: (val: number) => {
            if (val >= 1000) return (val / 1000).toFixed(1) + "k";
            return val.toString();
          },
        },
      },
      series: [
        {
          name: "操作数",
          type: "line",
          smooth: true,
          symbol: "circle",
          symbolSize: 6,
          lineStyle: { width: 2, color: "#3b82f6" },
          itemStyle: { color: "#3b82f6" },
          areaStyle: {
            color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [
              { offset: 0, color: "rgba(59, 130, 246, 0.25)" },
              { offset: 1, color: "rgba(59, 130, 246, 0.02)" },
            ]),
          },
          data: counts,
        },
      ],
    });
  }

  $effect(() => {
    if (data && chart) {
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
      暂无趋势数据
    </div>
  {:else}
    <div bind:this={chartContainer} class="mt-2 h-[200px] w-full"></div>
  {/if}
</div>
