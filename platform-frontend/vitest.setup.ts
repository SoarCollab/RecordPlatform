import "@testing-library/jest-dom/vitest";
import { vi } from "vitest";

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
});
