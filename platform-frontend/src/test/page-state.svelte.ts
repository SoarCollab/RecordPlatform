// Reactive stand-in for SvelteKit's `$app/state` under Vitest.
// Kit 3 exposes live state objects instead of stores, so tests that change the
// current page must mutate a `$state` value for rendered components to re-run
// their derivations.

export interface MockPage {
  url: URL;
  params: Record<string, string>;
  route: { id: string | null };
  status: number;
  error: App.Error | null;
  data: Record<string, unknown>;
  form: Record<string, unknown> | null;
}

export const pageState = $state<MockPage>({
  url: new URL("http://localhost/platform"),
  params: {},
  route: { id: "/platform" },
  status: 200,
  error: null,
  data: {},
  form: null,
});

export const navigatingState = $state({
  to: null as null | { url: URL },
  from: null as null | { url: URL },
  type: null as null | "enter" | "leave",
  delta: null as null | number,
  willUnload: false,
  complete: null as null | Promise<void>,
});

export const updatedState = {
  current: false,
  check: async () => false,
};

/**
 * 将 mock 的当前页面切换到指定路径。
 *
 * @param path 目标路径，例如 `/platform/users`。
 * @param origin 用于解析相对路径的源；默认 `http://localhost`。
 */
export function setPagePath(path: string, origin = "http://localhost"): void {
  pageState.url = new URL(path, origin);
}
