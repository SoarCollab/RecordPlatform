import path from "node:path";
import { fileURLToPath } from "node:url";

import { defineConfig } from "vite";

const frontendRoot = fileURLToPath(new URL("../..", import.meta.url));

export default defineConfig({
  root: frontendRoot,
  resolve: {
    alias: {
      $api: path.resolve(frontendRoot, "src/lib/api"),
      $components: path.resolve(frontendRoot, "src/lib/components"),
      $services: path.resolve(frontendRoot, "src/lib/services"),
      $stores: path.resolve(frontendRoot, "src/lib/stores"),
      $utils: path.resolve(frontendRoot, "src/lib/utils"),
    },
  },
  server: {
    host: "127.0.0.1",
    port: 4174,
    strictPort: true,
  },
});
