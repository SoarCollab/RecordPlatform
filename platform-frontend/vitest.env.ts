// Stub for `$app/env/public` and the deprecated `$env/*/public` modules under Vitest.
// Public environment variables are inlined at build time by SvelteKit, so tests get
// deterministic values from here instead.

export const browser = true;
export const dev = false;
export const building = false;
export const version = "test";

// Mock environment variables for testing
export const PUBLIC_API_BASE_URL = "http://localhost:8000/record-platform";
export const PUBLIC_TENANT_ID = "1";

export const env = {
  PUBLIC_API_BASE_URL,
  PUBLIC_TENANT_ID,
};
