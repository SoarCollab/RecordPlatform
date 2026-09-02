import { beforeEach, describe, expect, it, vi } from "vitest";

const echartsMocks = vi.hoisted(() => ({
  init: vi.fn(),
}));

vi.mock("echarts", () => ({
  init: echartsMocks.init,
}));

import { chartLifecycle } from "./chartLifecycle";

describe("chartLifecycle", () => {
  let resizeCallback: ResizeObserverCallback;
  let disconnect: ReturnType<typeof vi.fn>;
  let observe: ReturnType<typeof vi.fn>;
  let chart: {
    resize: ReturnType<typeof vi.fn>;
    dispose: ReturnType<typeof vi.fn>;
  };

  beforeEach(() => {
    vi.clearAllMocks();
    disconnect = vi.fn();
    observe = vi.fn();
    chart = { resize: vi.fn(), dispose: vi.fn() };
    echartsMocks.init.mockReturnValue(chart);

    vi.stubGlobal(
      "ResizeObserver",
      class {
        constructor(callback: ResizeObserverCallback) {
          resizeCallback = callback;
        }
        observe = observe;
        disconnect = disconnect;
      },
    );
  });

  function sizedNode(width = 640, height = 240) {
    const node = document.createElement("div");
    vi.spyOn(node, "getBoundingClientRect").mockReturnValue({
      width,
      height,
      x: 0,
      y: 0,
      top: 0,
      right: width,
      bottom: height,
      left: 0,
      toJSON: () => ({}),
    });
    return node;
  }

  it("initializes synchronously without waiting for ResizeObserver", () => {
    const render = vi.fn();
    const node = sizedNode();

    const lifecycle = chartLifecycle(node, [1], render);

    expect(echartsMocks.init).toHaveBeenCalledOnce();
    expect(render).toHaveBeenCalledWith(chart, [1]);
    expect(observe).toHaveBeenCalledWith(node);

    lifecycle.update([2]);
    expect(render).toHaveBeenLastCalledWith(chart, [2]);

    resizeCallback([], {} as ResizeObserver);
    expect(chart.resize).toHaveBeenCalledOnce();

    lifecycle.destroy();
    expect(disconnect).toHaveBeenCalledOnce();
    expect(chart.dispose).toHaveBeenCalledOnce();
  });

  it("uses one bounded animation-frame retry and can initialize on a later resize", () => {
    const callbacks: FrameRequestCallback[] = [];
    vi.stubGlobal(
      "requestAnimationFrame",
      vi.fn((callback: FrameRequestCallback) => {
        callbacks.push(callback);
        return callbacks.length;
      }),
    );
    vi.stubGlobal("cancelAnimationFrame", vi.fn());
    const node = sizedNode(0, 0);
    const render = vi.fn();

    const lifecycle = chartLifecycle(node, [1], render);

    expect(echartsMocks.init).not.toHaveBeenCalled();
    expect(callbacks).toHaveLength(1);
    callbacks[0](0);
    expect(echartsMocks.init).not.toHaveBeenCalled();
    expect(callbacks).toHaveLength(1);

    vi.mocked(node.getBoundingClientRect).mockReturnValue({
      width: 640,
      height: 240,
      x: 0,
      y: 0,
      top: 0,
      right: 640,
      bottom: 240,
      left: 0,
      toJSON: () => ({}),
    });
    resizeCallback([], {} as ResizeObserver);

    expect(echartsMocks.init).toHaveBeenCalledOnce();
    expect(render).toHaveBeenCalledWith(chart, [1]);
    lifecycle.destroy();
  });

  it("cancels a pending retry when the action is destroyed before layout", () => {
    const requestFrame = vi.fn(() => 17);
    const cancelFrame = vi.fn();
    vi.stubGlobal("requestAnimationFrame", requestFrame);
    vi.stubGlobal("cancelAnimationFrame", cancelFrame);

    const lifecycle = chartLifecycle(sizedNode(0, 0), [1], vi.fn());
    lifecycle.destroy();

    expect(requestFrame).toHaveBeenCalledOnce();
    expect(cancelFrame).toHaveBeenCalledWith(17);
    expect(disconnect).toHaveBeenCalledOnce();
    expect(echartsMocks.init).not.toHaveBeenCalled();
  });
});
