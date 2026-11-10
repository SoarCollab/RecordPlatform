// Stub for `$app/env` under Vitest.
// The real module lives in SvelteKit's client runtime and reads the
// `__SVELTEKIT_PAYLOAD__` global that Vitest never bootstraps.
// Kept in a dedicated file so that `vi.mock("$env/dynamic/public")` style mocks in
// tests do not accidentally replace it.

export const browser = true;
export const dev = false;
export const building = false;
export const version = "test";
