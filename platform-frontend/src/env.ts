import { defineEnvVars } from "@sveltejs/kit/env";

// Public runtime configuration is optional: the app falls back to the same-origin
// `/record-platform` prefix and tenant `0` when a variable is absent, so an empty
// string default keeps `$app/env/public` imports total instead of throwing.
export const variables = defineEnvVars({
  PUBLIC_API_BASE_URL: { public: true, schema: (input) => input ?? "" },
  PUBLIC_TENANT_ID: { public: true, schema: (input) => input ?? "" },
});
