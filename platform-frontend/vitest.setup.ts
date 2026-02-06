import "@testing-library/jest-dom/vitest";
import "fake-indexeddb/auto";
import { vi } from "vitest";

declare global {
  var __setDocumentVisibility: ((state: DocumentVisibilityState) => void) |
    undefined;
  var $state: <T>(initial: T) => T;
  var $derived: <T>(value: T) => T;
}

// Mock SvelteKit modules
vi.mock("$app/environment", () => ({
  browser: true,
  dev: true,
  building: false,
  version: "test",
}));

vi.mock("$app/navigation", () => ({
  goto: vi.fn(),
  invalidate: vi.fn(),
  invalidateAll: vi.fn(),
  preloadData: vi.fn(),
  preloadCode: vi.fn(),
  beforeNavigate: vi.fn(),
  afterNavigate: vi.fn(),
  onNavigate: vi.fn(),
}));

vi.mock("$app/stores", () => {
  const page = {
    subscribe: vi.fn((fn: (value: unknown) => void) => {
      fn({
        url: new URL("http://localhost"),
        params: {},
        route: { id: null },
        status: 200,
        error: null,
        data: {},
        form: null,
      });
      return () => {};
    }),
  };
  const navigating = {
    subscribe: vi.fn((fn: (value: unknown) => void) => {
      fn(null);
      return () => {};
    }),
  };
  const updated = {
    subscribe: vi.fn((fn: (value: unknown) => void) => {
      fn(false);
      return () => {};
    }),
    check: vi.fn(),
  };
  return { page, navigating, updated };
});

vi.mock("$env/static/public", () => ({
  PUBLIC_API_BASE_URL: "http://localhost:8000/record-platform",
  PUBLIC_TENANT_ID: "1",
}));

vi.mock("$env/dynamic/public", () => ({
  PUBLIC_API_BASE_URL: "http://localhost:8000/record-platform",
  PUBLIC_TENANT_ID: "1",
  env: {
    PUBLIC_API_BASE_URL: "http://localhost:8000/record-platform",
    PUBLIC_TENANT_ID: "1",
  },
}));

// Mock localStorage and sessionStorage
const createMockStorage = () => {
  let store: Record<string, string> = {};
  return {
    getItem: (key: string) => store[key] || null,
    setItem: (key: string, value: string) => {
      store[key] = value;
    },
    removeItem: (key: string) => {
      delete store[key];
    },
    clear: () => {
      store = {};
    },
    get length() {
      return Object.keys(store).length;
    },
    key: (index: number) => Object.keys(store)[index] || null,
  };
};

Object.defineProperty(globalThis, "localStorage", {
  value: createMockStorage(),
});
Object.defineProperty(globalThis, "sessionStorage", {
  value: createMockStorage(),
});

/**
 * 在测试环境中注册一个可控的 BroadcastChannel mock。
 *
 * @returns 可用于绑定到全局对象的 BroadcastChannel 构造器。
 */
function createMockBroadcastChannel() {
  const channels = new Map<string, Set<MockBroadcastChannel>>();

  class MockBroadcastChannel {
    name: string;
    onmessage: ((event: MessageEvent) => void) | null = null;

    constructor(name: string) {
      this.name = name;
      if (!channels.has(name)) {
        channels.set(name, new Set());
      }
      channels.get(name)!.add(this);
    }

    postMessage(data: unknown): void {
      const event = new MessageEvent("message", { data });
      for (const peer of channels.get(this.name) ?? []) {
        if (peer !== this) {
          peer.onmessage?.(event);
        }
      }
    }

    close(): void {
      channels.get(this.name)?.delete(this);
      if ((channels.get(this.name)?.size ?? 0) === 0) {
        channels.delete(this.name);
      }
    }
  }

  return MockBroadcastChannel;
}

/**
 * 在测试环境中注册一个可触发事件的 EventSource mock。
 *
 * @returns 可用于绑定到全局对象的 EventSource 构造器。
 */
function createMockEventSource() {
  class MockEventSource {
    static readonly CONNECTING = 0;
    static readonly OPEN = 1;
    static readonly CLOSED = 2;
    static instances: MockEventSource[] = [];

    static resetInstances(): void {
      MockEventSource.instances = [];
    }

    url: string;
    readyState = MockEventSource.CONNECTING;
    withCredentials = false;
    onopen: ((event: Event) => void) | null = null;
    onmessage: ((event: MessageEvent) => void) | null = null;
    onerror: ((event: Event) => void) | null = null;
    private listeners = new Map<string, Set<(event: Event) => void>>();

    constructor(url: string) {
      this.url = url;
      MockEventSource.instances.push(this);
    }

    addEventListener(type: string, listener: (event: Event) => void): void {
      if (!this.listeners.has(type)) {
        this.listeners.set(type, new Set());
      }
      this.listeners.get(type)!.add(listener);
    }

    removeEventListener(type: string, listener: (event: Event) => void): void {
      this.listeners.get(type)?.delete(listener);
    }

    close(): void {
      this.readyState = MockEventSource.CLOSED;
    }

    emitOpen(): void {
      this.readyState = MockEventSource.OPEN;
      const event = new Event("open");
      this.onopen?.(event);
    }

    emitMessage(data: string): void {
      const event = new MessageEvent("message", { data });
      this.onmessage?.(event);
      this.listeners.get("message")?.forEach((listener) => listener(event));
    }

    emitNamedEvent(type: string, data: string): void {
      const event = new MessageEvent(type, { data });
      this.listeners.get(type)?.forEach((listener) => listener(event));
    }

    emitError(): void {
      const event = new Event("error");
      this.onerror?.(event);
      this.listeners.get("error")?.forEach((listener) => listener(event));
    }
  }

  return MockEventSource;
}

/**
 * 在测试环境中安装 `crypto.randomUUID`，便于断言连接 ID。
 */
function installRandomUUIDMock(): void {
  const cryptoObj = globalThis.crypto as Crypto & {
    randomUUID?: () => string;
  };

  if (!cryptoObj.randomUUID) {
    let counter = 0;
    cryptoObj.randomUUID = () => `mock-uuid-${++counter}`;
  }
}

const MockBroadcastChannel = createMockBroadcastChannel();
const MockEventSource = createMockEventSource();
Object.defineProperty(globalThis, "BroadcastChannel", {
  value: MockBroadcastChannel,
  configurable: true,
});
Object.defineProperty(globalThis, "EventSource", {
  value: MockEventSource,
  configurable: true,
});
installRandomUUIDMock();

let visibilityStateValue: DocumentVisibilityState = "visible";
Object.defineProperty(document, "visibilityState", {
  get: () => visibilityStateValue,
  configurable: true,
});

/**
 * 在测试里设置页面可见性并触发 `visibilitychange`。
 *
 * @param state 目标页面可见状态。
 */
function setDocumentVisibility(state: DocumentVisibilityState): void {
  visibilityStateValue = state;
  document.dispatchEvent(new Event("visibilitychange"));
}

globalThis.__setDocumentVisibility = setDocumentVisibility;

/**
 * 在测试环境中为 rune 语法提供最小化 polyfill。
 *
 *  仅用于让 .svelte.ts 在 Vitest 中可执行，不模拟完整响应式语义。
 */
function installSvelteRunesPolyfill(): void {
  globalThis.$state = <T>(initial: T): T => initial;
  globalThis.$derived = <T>(value: T): T => value;
}

installSvelteRunesPolyfill();

// Note: For API client tests that need fetch mocking, consider using MSW (Mock Service Worker)
// which provides better ESM compatibility. Simple fetch mocking with vi.stubGlobal has
// issues with ESM module caching in vitest.

// Mock crypto.subtle for encryption tests
if (typeof globalThis.crypto === "undefined") {
  Object.defineProperty(globalThis, "crypto", {
    value: {
      subtle: {
        importKey: vi.fn(),
        decrypt: vi.fn(),
        encrypt: vi.fn(),
        digest: vi.fn(),
      },
      getRandomValues: (arr: Uint8Array) => {
        for (let i = 0; i < arr.length; i++) {
          arr[i] = Math.floor(Math.random() * 256);
        }
        return arr;
      },
    },
  });
}

// Mock TextEncoder/TextDecoder
if (typeof globalThis.TextEncoder === "undefined") {
  globalThis.TextEncoder = class TextEncoder {
    encode(str: string): Uint8Array {
      return new Uint8Array(Buffer.from(str, "utf-8"));
    }
  } as unknown as typeof TextEncoder;
}

if (typeof globalThis.TextDecoder === "undefined") {
  globalThis.TextDecoder = class TextDecoder {
    decode(arr: Uint8Array): string {
      return Buffer.from(arr).toString("utf-8");
    }
  } as unknown as typeof TextDecoder;
}

// Reset mocks between tests
beforeEach(() => {
  vi.clearAllMocks();
  localStorage.clear();
  sessionStorage.clear();
  MockEventSource.resetInstances();
  visibilityStateValue = "visible";
});
