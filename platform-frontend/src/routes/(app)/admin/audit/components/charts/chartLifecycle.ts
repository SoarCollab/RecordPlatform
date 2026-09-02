import * as echarts from "echarts";

/**
 * Mounts an ECharts instance immediately when the container has layout, then
 * keeps resize observation and disposal behind one shared lifecycle boundary.
 */
export function chartLifecycle<T>(
  node: HTMLDivElement,
  initialValue: T,
  render: (chart: echarts.ECharts, value: T) => void,
  connect?: (chart: echarts.ECharts, getValue: () => T) => void,
) {
  let value = initialValue;
  let chart: echarts.ECharts | null = null;
  let retryFrame: number | null = null;

  const hasLayout = () => {
    const rect = node.getBoundingClientRect();
    return (
      (node.clientWidth > 0 || rect.width > 0) &&
      (node.clientHeight > 0 || rect.height > 0)
    );
  };

  const initialize = () => {
    if (chart || !hasLayout()) return false;

    chart = echarts.init(node, undefined, { renderer: "canvas" });
    connect?.(chart, () => value);
    render(chart, value);
    return true;
  };

  initialize();
  if (!chart) {
    retryFrame = requestAnimationFrame(() => {
      retryFrame = null;
      initialize();
    });
  }

  const resizeObserver = new ResizeObserver(() => {
    if (!chart) {
      initialize();
      return;
    }
    chart.resize();
  });
  resizeObserver.observe(node);

  return {
    update(nextValue: T) {
      value = nextValue;
      if (chart) {
        render(chart, value);
      } else {
        initialize();
      }
    },
    destroy() {
      resizeObserver.disconnect();
      if (retryFrame !== null) {
        cancelAnimationFrame(retryFrame);
        retryFrame = null;
      }
      chart?.dispose();
      chart = null;
    },
  };
}
