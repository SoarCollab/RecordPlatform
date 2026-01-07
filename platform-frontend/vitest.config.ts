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
      },
    },
  },
  resolve: {
    alias: {
      $lib: resolve("./src/lib"),
      $app: resolve("./node_modules/@sveltejs/kit/src/runtime/app"),
      "$env/static/public": resolve("./vitest.env.ts"),
      "$env/dynamic/public": resolve("./vitest.env.ts"),
    },
  },
});
