import { defineConfig } from "vitest/config";
import { resolve } from "path";

export default defineConfig({
  test: {
    include: ["src/**/*.{test,spec}.ts"],
    globals: true,
    environment: "jsdom",
    setupFiles: ["./vitest.setup.ts"],
    coverage: {
      provider: "v8",
      reporter: ["text", "lcov", "html"],
      include: ["src/lib/**/*.ts"],
      exclude: [
        "src/lib/api/types/**",
        "src/lib/components/ui/**",
        "**/*.d.ts",
        "**/index.ts",
      ],
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
