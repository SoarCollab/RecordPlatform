import { defineConfig } from "vitest/config";
import { resolve } from "path";

export default defineConfig({
  test: {
    include: ["src/**/*.{test,spec}.{ts,js}"],
    globals: true,
    environment: "jsdom",
    setupFiles: ["./vitest.setup.ts"],
    testTimeout: 10000,
    hookTimeout: 10000,
    coverage: {
      provider: "v8",
      reporter: ["text", "lcov", "html"],
      include: ["src/lib/**/*.ts"],
      exclude: [
        "src/lib/api/types/**",
        "src/lib/components/ui/**",
        "**/*.d.ts",
        "**/index.ts",
        "**/*.test.ts",
        "**/*.spec.ts",
      ],
      thresholds: {
        "src/lib/utils/**": {
          lines: 70,
          functions: 70,
          branches: 60,
          statements: 70,
        },
        "src/lib/api/endpoints/**": {
          lines: 90,
          functions: 90,
          branches: 85,
          statements: 90,
        },
        "src/lib/stores/**": {
          lines: 90,
          functions: 90,
          branches: 80,
          statements: 90,
        },
        "src/lib/services/**": {
          lines: 90,
          functions: 90,
          branches: 85,
          statements: 90,
        },
      },
    },
  },
  resolve: {
    alias: {
      $lib: resolve("./src/lib"),
      $components: resolve("./src/lib/components"),
      $stores: resolve("./src/lib/stores"),
      $api: resolve("./src/lib/api"),
      $services: resolve("./src/lib/services"),
      $utils: resolve("./src/lib/utils"),
      $app: resolve("./node_modules/@sveltejs/kit/src/runtime/app"),
      "$env/static/public": resolve("./vitest.env.ts"),
      "$env/dynamic/public": resolve("./vitest.env.ts"),
    },
  },
});
