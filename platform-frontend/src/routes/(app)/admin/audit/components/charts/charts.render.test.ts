import { cleanup, render } from "@testing-library/svelte";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

const echartsMocks = vi.hoisted(() => ({
  init: vi.fn(),
}));

vi.mock("echarts", () => ({
  init: echartsMocks.init,
  graphic: {
    LinearGradient: class {},
  },
}));

import ErrorPieChart from "./ErrorPieChart.svelte";
import HeatmapChart from "./HeatmapChart.svelte";
import TrendChart from "./TrendChart.svelte";

describe("audit charts", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    echartsMocks.init.mockImplementation((node: HTMLElement) => {
      node.appendChild(document.createElement("canvas"));
      return {
        on: vi.fn(),
        setOption: vi.fn(),
        clear: vi.fn(),
        resize: vi.fn(),
        dispose: vi.fn(),
      };
    });
    vi.spyOn(HTMLElement.prototype, "getBoundingClientRect").mockReturnValue({
      width: 640,
      height: 260,
      x: 0,
      y: 0,
      top: 0,
      right: 640,
      bottom: 260,
      left: 0,
      toJSON: () => ({}),
    });
    vi.stubGlobal(
      "ResizeObserver",
      class {
        observe() {}
        disconnect() {}
      },
    );
  });

  afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
  });

  it("creates a canvas immediately and updates without recreating the chart", async () => {
    const trend = render(TrendChart, {
      data: [{ date: "2026-09-02", count: 12 }],
    });
    const errors = render(ErrorPieChart, {
      data: [
        {
          module: "file",
          operationType: "UPDATE",
          errorMsg: "failure",
          errorCount: 2,
          firstOccurrence: "2026-09-02 10:00:00",
          lastOccurrence: "2026-09-02 10:01:00",
        },
      ],
    });
    const heatmap = render(HeatmapChart, {
      data: [{ dayOfWeek: 2, hourOfDay: 10, operationCount: 8 }],
    });

    expect(echartsMocks.init).toHaveBeenCalledTimes(3);
    expect(trend.container.querySelector("canvas")).not.toBeNull();
    expect(errors.container.querySelector("canvas")).not.toBeNull();
    expect(heatmap.container.querySelector("canvas")).not.toBeNull();

    await trend.rerender({ data: [{ date: "2026-09-03", count: 18 }] });
    expect(echartsMocks.init).toHaveBeenCalledTimes(3);
    expect(
      echartsMocks.init.mock.results[0]?.value.setOption,
    ).toHaveBeenCalledTimes(2);
  });
});
